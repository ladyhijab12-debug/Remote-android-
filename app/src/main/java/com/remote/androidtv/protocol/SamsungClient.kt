package com.remote.androidtv.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The user pressed "Deny" on the TV, or did not answer the prompt in time. */
class SamsungDeniedException(message: String) : IOException(message)

/**
 * Samsung Smart TVs (Tizen, 2016+) over the TV's WebSocket remote API.
 * The first time, the TV shows an "Allow" prompt; after that it hands out a token
 * that lets us connect again without asking.
 */
class SamsungClient(
    private val host: String,
    private val appName: String,
    /** Token from an earlier connection, or null the first time. */
    token: String?,
    private val listener: Listener,
    private val securePort: Int = SECURE_PORT,
    private val plainPort: Int = PLAIN_PORT,
) : TvController {

    interface Listener {
        fun onDisconnected(error: Throwable?) {}
    }

    /** Save this after [connect] and pass it next time. */
    @Volatile var token: String? = token
        private set

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closedByUs = false
    private var playing = false

    override val isConnected: Boolean get() = connected

    /** Blocks until the TV accepts us. Allows time for the user to answer the prompt on the TV. */
    fun connect(timeoutMs: Long = 35_000) {
        val secure = runCatching { open("wss", securePort, timeoutMs) }
        if (secure.isSuccess) return
        val error = secure.exceptionOrNull()!!
        if (error is SamsungDeniedException) throw error
        // 2016-2017 models only have the unencrypted port.
        runCatching { open("ws", plainPort, timeoutMs) }.getOrElse {
            throw if (it is SamsungDeniedException) it else error
        }
    }

    private fun open(scheme: String, port: Int, timeoutMs: Long) {
        val name = Base64.getEncoder().encodeToString(appName.toByteArray(Charsets.UTF_8))
        val url = buildString {
            append("$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=")
            append(URLEncoder.encode(name, "UTF-8"))
            token?.let { append("&token=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (jsonString(text, "event")) {
                    "ms.channel.connect" -> {
                        jsonString(text, "token")?.let { token = it }
                        connected = true
                        done.countDown()
                    }
                    "ms.channel.unauthorized" -> {
                        failure = SamsungDeniedException("TV refused the connection")
                        done.countDown()
                    }
                    "ms.channel.timeOut" -> {
                        failure = SamsungDeniedException("Nobody accepted the prompt on the TV")
                        done.countDown()
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ended(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = ended(t)

            private fun ended(error: Throwable?) {
                if (done.count > 0) {
                    failure = failure ?: error ?: SamsungDeniedException("TV closed the connection")
                    done.countDown()
                } else if (connected) {
                    connected = false
                    if (!closedByUs) listener.onDisconnected(error)
                }
            }
        })
        socket = ws
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            ws.cancel()
            throw SamsungDeniedException("Nobody accepted the prompt on the TV")
        }
        failure?.let {
            ws.cancel()
            throw it
        }
    }

    override fun press(key: RemoteKey, action: KeyAction) {
        // Samsung has separate play and pause keys; alternate between them.
        val code = if (key == RemoteKey.PLAY_PAUSE && action == KeyAction.CLICK) {
            playing = !playing
            if (playing) "KEY_PLAY" else "KEY_PAUSE"
        } else {
            key.samsungKey
        }
        sendKey(code, action)
    }

    fun sendKey(code: String, action: KeyAction = KeyAction.CLICK) {
        val cmd = when (action) {
            KeyAction.CLICK -> "Click"
            KeyAction.PRESS -> "Press"
            KeyAction.RELEASE -> "Release"
        }
        send(
            """{"method":"ms.remote.control","params":{"Cmd":"$cmd","DataOfCmd":"${escape(code)}",""" +
                """"Option":"false","TypeOfRemote":"SendRemoteKey"}}""",
        )
    }

    override fun launch(app: TvApp) = launchApp(app.samsungAppId)

    fun launchApp(appId: String) = send(
        """{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host",""" +
            """"data":{"appId":"${escape(appId)}","action_type":"DEEP_LINK"}}}""",
    )

    override fun close() {
        closedByUs = true
        connected = false
        socket?.close(1000, null)
    }

    private fun send(json: String) {
        val ws = socket ?: throw IOException("Not connected")
        if (!connected || !ws.send(json)) throw IOException("Not connected")
    }

    companion object {
        const val SECURE_PORT = 8002
        const val PLAIN_PORT = 8001

        /** Samsung TVs use a self-signed certificate on port 8002. */
        private val trustTv = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        private val client: OkHttpClient by lazy {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustTv), SecureRandom()) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustTv)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Asks http://host:8001/api/v2/ for the TV's description.
         * Returns the TV's name if it is a Samsung TV, null otherwise.
         */
        fun probe(host: String, port: Int = PLAIN_PORT, timeoutMs: Int = 1500): String? = runCatching {
            val conn = URL("http://$host:$port/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (!body.contains("Samsung", ignoreCase = true) && !body.contains("Tizen", ignoreCase = true)) {
                    return null
                }
                jsonString(body, "name") ?: "Samsung TV"
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        /** Reads the first "key":"value" string in a JSON text (enough for the TV's small replies). */
        internal fun jsonString(json: String, key: String): String? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(json)?.groupValues?.get(1)
                ?.replace(Regex("\\\\(.)")) { it.groupValues[1] }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
