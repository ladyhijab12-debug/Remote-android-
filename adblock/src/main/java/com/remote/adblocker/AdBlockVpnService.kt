package com.remote.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.remote.adblocker.dns.BlockList
import com.remote.adblocker.dns.DnsPacket
import com.remote.adblocker.dns.DnsQuery
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Blocks ads in every app without root: a local VPN whose only route is a fake
 * DNS server address. Android sends all DNS lookups to it; lookups for ad
 * servers get an empty answer, the rest are passed on to the real DNS server.
 * No other traffic goes through the tunnel.
 */
class AdBlockVpnService : VpnService() {

    @Volatile private var blockList: BlockList = BlockList(emptySet())
    @Volatile private var worker: Thread? = null
    @Volatile private var stopPipe: Array<FileDescriptor>? = null
    @Volatile private var upstream: List<InetAddress> = emptyList()
    @Volatile private var upstreamCheckedAt = 0L
    private var savedBlocked = 0L
    private val tunnelWriteLock = Any()

    private val forwarders = ThreadPoolExecutor(
        4, 32, 30, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.DiscardPolicy(),
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBlocking()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (worker == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                thread(name = "adblock-reload") { reloadList() }
            }
            else -> startBlocking()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopBlocking()
    }

    override fun onDestroy() {
        stopBlocking()
        forwarders.shutdownNow()
        super.onDestroy()
    }

    private fun startBlocking() {
        if (worker != null) return
        showNotification()
        running = true
        savedBlocked = Stats.blocked.get()
        worker = thread(name = "adblock-vpn") {
            try {
                runTunnel()
            } catch (e: Exception) {
                Log.w(TAG, "tunnel stopped", e)
            } finally {
                worker = null
                running = false
                saveBlockedCount()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopBlocking() {
        val pipe = stopPipe
        if (pipe != null) {
            try {
                Os.write(pipe[1], byteArrayOf(1), 0, 1)
            } catch (_: Exception) {
                // Already closed: the tunnel thread is on its way out.
            }
        } else if (worker == null) {
            running = false
            stopSelf()
        }
    }

    private fun reloadList() {
        blockList = BlockListStore.load(this)
    }

    private fun runTunnel() {
        // Created before the slow list load so a stop request is never lost.
        val pipe = Os.pipe()
        stopPipe = pipe
        var tunnel: ParcelFileDescriptor? = null
        try {
            reloadList()
            if (BlockListStore.isStale(this)) {
                thread(name = "adblock-update") {
                    try {
                        BlockListStore.update(this)
                        reloadList()
                    } catch (e: Exception) {
                        Log.i(TAG, "list update failed: $e")
                    }
                }
            }

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUNNEL_ADDRESS, 24)
                .addDnsServer(FAKE_DNS)
                .addRoute(FAKE_DNS, 32)
                .setMtu(MTU)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            tunnel = builder.establish() ?: throw IOException("VPN permission missing")

            val tunnelFd = tunnel.fileDescriptor
            val output = FileOutputStream(tunnelFd)
            val tunnelPoll = StructPollfd().apply { fd = tunnelFd; events = OsConstants.POLLIN.toShort() }
            val stopPoll = StructPollfd().apply { fd = pipe[0]; events = OsConstants.POLLIN.toShort() }
            val polls = arrayOf(tunnelPoll, stopPoll)
            val buffer = ByteArray(MTU)

            while (true) {
                try {
                    Os.poll(polls, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (stopPoll.revents.toInt() != 0) break
                val events = tunnelPoll.revents.toInt()
                if (events and OsConstants.POLLIN != 0) {
                    val length = try {
                        Os.read(tunnelFd, buffer, 0, buffer.size)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                        throw e
                    }
                    if (length > 0) handlePacket(buffer, length, output)
                } else if (events != 0) {
                    break // tunnel closed or failed
                }
            }
        } finally {
            stopPipe = null
            closeQuietly(pipe[0])
            closeQuietly(pipe[1])
            try {
                tunnel?.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handlePacket(buffer: ByteArray, length: Int, output: FileOutputStream) {
        val query = DnsPacket.parse(buffer, length) ?: return
        if (blockList.isBlocked(query.name)) {
            Stats.recordBlocked(query.name)
            writeToTunnel(output, DnsPacket.wrapResponse(query, DnsPacket.blockedResponse(query)))
            if (Stats.blocked.get() - savedBlocked >= SAVE_EVERY) saveBlockedCount()
        } else {
            Stats.recordAllowed()
            forwarders.execute { forward(query, output) }
        }
    }

    /** Asks the real DNS server, outside the VPN, and hands its answer back to the app. */
    private fun forward(query: DnsQuery, output: FileOutputStream) {
        val reply = ByteArray(MTU)
        for (server in upstreamServers().take(MAX_UPSTREAM_TRIES)) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) return
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(query.payload, query.payload.size, server, DNS_PORT))
                    val packet = DatagramPacket(reply, reply.size)
                    socket.receive(packet)
                    writeToTunnel(output, DnsPacket.wrapResponse(query, reply.copyOf(packet.length)))
                }
                return
            } catch (_: IOException) {
                // Try the next server.
            }
        }
    }

    private fun writeToTunnel(output: FileOutputStream, packet: ByteArray) {
        synchronized(tunnelWriteLock) {
            try {
                output.write(packet)
            } catch (_: IOException) {
                // Tunnel is closing.
            }
        }
    }

    /** DNS servers of the real (non-VPN) network, then public fallbacks. */
    private fun upstreamServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        val cached = upstream
        if (cached.isNotEmpty() && now - upstreamCheckedAt < UPSTREAM_CACHE_MS) return cached

        val servers = LinkedHashSet<InetAddress>()
        try {
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (network in connectivity.allNetworks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                connectivity.getLinkProperties(network)?.dnsServers?.let { servers.addAll(it) }
            }
        } catch (e: Exception) {
            Log.i(TAG, "could not read system DNS servers: $e")
        }
        for (fallback in FALLBACK_DNS) servers.add(InetAddress.getByName(fallback))

        val result = servers.toList()
        upstream = result
        upstreamCheckedAt = now
        return result
    }

    private fun saveBlockedCount() {
        synchronized(tunnelWriteLock) {
            val now = Stats.blocked.get()
            BlockListStore.addTotalBlocked(this, now - savedBlocked)
            savedBlocked = now
        }
    }

    private fun showNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // The VPN keeps the service alive even without the notification.
            Log.w(TAG, "could not start foreground", e)
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        try {
            Os.close(fd)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_START = "com.remote.adblocker.START"
        const val ACTION_STOP = "com.remote.adblocker.STOP"
        const val ACTION_RELOAD = "com.remote.adblocker.RELOAD"

        /** True while the blocker is on. */
        @Volatile var running = false
            private set

        private const val TAG = "AdBlockVpn"
        private const val CHANNEL = "adblock"
        private const val NOTIFICATION_ID = 1
        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val FAKE_DNS = "10.111.222.2"
        private const val MTU = 4096
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 3000
        private const val UPSTREAM_CACHE_MS = 15_000L
        private const val MAX_UPSTREAM_TRIES = 3
        private const val SAVE_EVERY = 20
        private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")
    }
}
