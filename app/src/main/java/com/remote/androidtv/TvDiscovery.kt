package com.remote.androidtv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

data class TvDevice(val name: String, val host: String) {
    override fun toString() = "$name\n$host"
}

/** Finds Android TVs on the local Wi-Fi via mDNS (the TV advertises _androidtvremote2._tcp). */
class TvDiscovery(context: Context, private val onChange: (List<TvDevice>) -> Unit) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, TvDevice>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = main.post { enqueue(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                devices.remove(info.serviceName)
                publish()
            }.let {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
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
                resolved.host?.hostAddress?.let { host ->
                    devices[resolved.serviceName] = TvDevice(resolved.serviceName, host)
                    publish()
                }
                resolving = false
                resolveNext()
            }.let {}

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                resolveNext()
            }.let {}
        })
    }

    private fun publish() = onChange(devices.values.toList())

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}
