package com.remote.androidtv.protocol

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** The TV refused our certificate: it has not been paired with this phone (yet). */
class NotPairedException(cause: Throwable?) : IOException("TV is not paired with this phone", cause)

data class DeviceInfo(
    val model: String,
    val vendor: String,
    val appVersion: String = "1.0.0",
)

/**
 * Remote-control channel on port 6466 (must be paired first).
 * [connect] blocks until the TV accepts the session; afterwards a background thread answers
 * the TV's pings and reports state through [Listener].
 */
class RemoteClient(
    private val host: String,
    private val certs: CertificateManager,
    private val device: DeviceInfo,
    private val listener: Listener,
    private val port: Int = REMOTE_PORT,
) : TvController {

    interface Listener {
        fun onDisconnected(error: Throwable?) {}
        fun onPowerChanged(isOn: Boolean) {}
        fun onVolumeChanged(level: Int, max: Int, muted: Boolean) {}
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private val writeLock = Any()
    private val ready = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null

    fun connect(timeoutMs: Long = 10_000) {
        val s = try {
            certs.connect(host, port)
        } catch (e: SSLException) {
            throw NotPairedException(e)
        }
        // The TV pings every few seconds; silence for this long means the link is dead.
        s.soTimeout = 20_000
        socket = s
        Thread({ readLoop(s) }, "atv-remote-reader").apply { isDaemon = true }.start()
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw IOException("TV did not answer")
        }
        failure?.let { throw it }
    }

    fun sendKey(keyCode: Int, direction: Int = DIRECTION_SHORT) = send {
        message(10) { varint(1, keyCode); varint(2, direction) }
    }

    /** Opens an app on the TV through a deep link, e.g. "https://www.youtube.com". */
    fun launchApp(link: String) = send { message(90) { string(1, link) } }

    override val isConnected: Boolean get() = socket != null && !closed && ready.count == 0L && failure == null

    override fun press(key: RemoteKey, action: KeyAction) = sendKey(
        key.androidCode,
        when (action) {
            KeyAction.CLICK -> DIRECTION_SHORT
            KeyAction.PRESS -> DIRECTION_START_LONG
            KeyAction.RELEASE -> DIRECTION_END_LONG
        },
    )

    override fun launch(app: TvApp) = launchApp(app.androidLink)

    override fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun readLoop(s: SSLSocket) {
        var error: Throwable? = null
        try {
            val input = s.inputStream
            while (!closed) handle(ProtoMessage.parse(Framing.read(input)))
        } catch (e: Throwable) {
            error = e
        }
        if (ready.count > 0) {
            // Dropped before the session was set up: an unpaired TV hangs up right after the handshake.
            failure = if (error is SSLException || error is IOException && error !is SocketTimeoutException) {
                NotPairedException(error)
            } else {
                IOException("Connection failed", error)
            }
            ready.countDown()
        } else if (!closed) {
            listener.onDisconnected(error)
        }
        closed = true
        runCatching { s.close() }
    }

    private fun handle(msg: ProtoMessage) {
        when {
            msg.has(1) -> send {
                message(1) {
                    varint(1, FEATURES)
                    message(2) {
                        string(1, device.model)
                        string(2, device.vendor)
                        varint(3, 1)
                        string(4, "1")
                        string(5, PACKAGE_NAME)
                        string(6, device.appVersion)
                    }
                }
            }
            msg.has(2) -> {
                send { message(2) { varint(1, FEATURES) } }
                ready.countDown()
            }
            msg.has(8) -> {
                val value = msg.message(8)?.varint(1) ?: 0L
                send { message(9) { varint(1, value) } }
            }
            msg.has(40) -> listener.onPowerChanged(msg.message(40)?.varint(1) == 1L)
            msg.has(50) -> msg.message(50)?.let {
                listener.onVolumeChanged(
                    level = (it.varint(7) ?: 0).toInt(),
                    max = (it.varint(6) ?: 0).toInt(),
                    muted = it.varint(8) == 1L,
                )
            }
        }
    }

    private fun send(block: ProtoWriter.() -> Unit) {
        val s = socket ?: throw IOException("Not connected")
        val payload = ProtoWriter().apply(block).toByteArray()
        synchronized(writeLock) { Framing.write(s.outputStream, payload) }
    }

    companion object {
        const val REMOTE_PORT = 6466

        const val DIRECTION_START_LONG = 1
        const val DIRECTION_END_LONG = 2
        const val DIRECTION_SHORT = 3

        private const val PACKAGE_NAME = "atvremote"

        // PING | KEY | POWER | VOLUME | APP_LINK
        private const val FEATURES = 1 or 2 or 32 or 64 or 512
    }
}
