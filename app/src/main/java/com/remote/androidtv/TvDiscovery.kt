package com.remote.androidtv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.remote.androidtv.protocol.LgClient
import com.remote.androidtv.protocol.RokuClient
import com.remote.androidtv.protocol.SamsungClient
import com.remote.androidtv.protocol.TvBrand
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.ArrayDeque

data class TvDevice(val name: String, val host: String, val brand: TvBrand) {
    override fun toString(): String = "$name\n${brand.label} · $host"
}

/**
 * Finds TVs on the local Wi-Fi:
 * - Android TV / Google TV advertise _androidtvremote2._tcp over mDNS.
 * - Samsung, LG webOS and Roku devices answer an SSDP search, then tell their name over HTTP.
 */
class TvDiscovery(context: Context, private val onChange: (List<TvDevice>) -> Unit) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, TvDevice>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    @Volatile private var ssdpThread: Thread? = null

    fun start() {
        startNsd()
        startSsdp()
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        ssdpThread?.interrupt()
        ssdpThread = null
    }

    private fun add(device: TvDevice) {
        devices[device.host] = device
        onChange(devices.values.toList())
    }

    // ---- Android TV (mDNS) ----

    private fun startNsd() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = main.post { enqueue(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                devices.values.removeAll { it.brand == TvBrand.ANDROID_TV && it.name == info.serviceName }
                onChange(devices.values.toList())
            }.let {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsd.discoverServices(ANDROID_TV_SERVICE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun enqueue(info: NsdServiceInfo) {
        pending.add(info)
        resolveNext()
    }

    // NsdManager can only resolve one service at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = pending.poll() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) = main.post {
                resolved.host?.hostAddress?.let { add(TvDevice(resolved.serviceName, it, TvBrand.ANDROID_TV)) }
                resolving = false
                resolveNext()
            }.let {}

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                resolveNext()
            }.let {}
        })
    }

    // ---- Samsung, LG, Roku (SSDP) ----

    private fun startSsdp() {
        if (ssdpThread != null) return
        val t = Thread({ ssdpLoop() }, "ssdp-discovery").apply { isDaemon = true }
        ssdpThread = t
        t.start()
    }

    private fun ssdpLoop() {
        val lock = wifi.createMulticastLock("tv-remote-ssdp").apply { setReferenceCounted(false) }
        val probed = mutableSetOf<String>()
        var socket: MulticastSocket? = null
        try {
            lock.acquire()
            val s = MulticastSocket()
            socket = s
            s.soTimeout = 1000
            val group = InetAddress.getByName("239.255.255.250")
            val searches = SSDP_TARGETS.map {
                (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $it\r\n\r\n"
                    ).toByteArray()
            }
            val buf = ByteArray(2048)
            while (!Thread.currentThread().isInterrupted) {
                searches.forEach { runCatching { s.send(DatagramPacket(it, it.size, group, 1900)) } }
                val until = System.currentTimeMillis() + SSDP_ROUND_MS
                while (System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val reply = String(packet.data, 0, packet.length)
                    val location = ssdpLocation(reply)
                    val host = location?.let { runCatching { URL(it).host }.getOrNull() }
                        ?: packet.address.hostAddress ?: continue
                    val brand = when {
                        reply.contains(ROKU_TARGET, ignoreCase = true) -> TvBrand.ROKU
                        reply.contains(LG_TARGET, ignoreCase = true) -> TvBrand.LG
                        else -> TvBrand.SAMSUNG
                    }
                    if (!probed.add("$brand $host")) continue
                    val name = when (brand) {
                        TvBrand.ROKU -> RokuClient.probe(host)
                        TvBrand.LG -> location?.let { LgClient.nameFromDescription(it) } ?: "LG TV"
                        else -> SamsungClient.probe(host)
                    } ?: continue
                    main.post { add(TvDevice(name, host, brand)) }
                }
            }
        } catch (_: Exception) {
            // Discovery is best effort; the IP can still be typed by hand.
        } finally {
            runCatching { socket?.close() }
            runCatching { lock.release() }
        }
    }

    private fun ssdpLocation(reply: String): String? =
        Regex("(?im)^LOCATION:\\s*(\\S+)").find(reply)?.groupValues?.get(1)

    private companion object {
        const val ANDROID_TV_SERVICE = "_androidtvremote2._tcp."
        const val SSDP_ROUND_MS = 8000L
        const val ROKU_TARGET = "roku:ecp"
        const val LG_TARGET = "urn:lge-com:service:webos-second-screen:1"
        val SSDP_TARGETS = listOf("urn:samsung.com:device:RemoteControlReceiver:1", LG_TARGET, ROKU_TARGET)
    }
}
