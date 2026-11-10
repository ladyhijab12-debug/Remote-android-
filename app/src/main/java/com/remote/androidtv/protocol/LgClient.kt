package com.remote.androidtv.protocol

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The user pressed "Deny" on the LG TV, or did not answer the prompt in time. */
class LgDeniedException(message: String) : IOException(message)

/**
 * LG Smart TVs (webOS, 2014+) over the TV's WebSocket API ("SSAP").
 * The first time, the TV shows an "Accept" prompt and hands out a client key that lets us
 * connect again without asking. Buttons go over a second socket the TV opens for input.
 */
class LgClient(
    private val host: String,
    /** Key from an earlier connection, or null the first time. */
    clientKey: String?,
    private val listener: Listener,
    private val securePort: Int = SECURE_PORT,
    private val plainPort: Int = PLAIN_PORT,
) : TvController {

    interface Listener {
        fun onDisconnected(error: Throwable?) {}
    }

    /** Save this after [connect] and pass it next time. */
    @Volatile var clientKey: String? = clientKey
        private set

    @Volatile private var socket: WebSocket? = null
    @Volatile private var input: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closedByUs = false
    private val nextId = AtomicInteger(1)
    private var playing = false

    override val isConnected: Boolean get() = connected

    /** The input socket takes one click at a time. */
    override val supportsHold: Boolean get() = false

    /** Blocks until the TV accepts us. Allows time for the user to answer the prompt on the TV. */
    fun connect(timeoutMs: Long = 35_000) {
        // Newer TVs only accept the encrypted port, older ones only the plain one.
        val secure = runCatching { open("wss", securePort, timeoutMs) }
        if (secure.isSuccess) return
        val error = secure.exceptionOrNull()!!
        if (error is LgDeniedException) throw error
        runCatching { open("ws", plainPort, timeoutMs) }.getOrElse {
            throw if (it is LgDeniedException) it else error
        }
    }

    private fun open(scheme: String, port: Int, timeoutMs: Long) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        var inputPath: String? = null
        val ws = SamsungClient.client.newWebSocket(
            Request.Builder().url("$scheme://$host:$port/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val key = clientKey?.let { ""","client-key":"${SamsungClient.escape(it)}"""" } ?: ""
                    webSocket.send(
                        """{"type":"register","id":"$REGISTER_ID","payload":{"forcePairing":false,""" +
                            """"pairingType":"PROMPT"$key,"manifest":$MANIFEST}}""",
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = SamsungClient.jsonString(text, "id")
                    when (SamsungClient.jsonString(text, "type")) {
                        "registered" -> {
                            SamsungClient.jsonString(text, "client-key")?.let { clientKey = it }
                            webSocket.send("""{"type":"request","id":"$INPUT_ID","uri":"$INPUT_SOCKET_URI"}""")
                        }
                        "response" -> if (id == INPUT_ID) {
                            inputPath = SamsungClient.jsonString(text, "socketPath")
                            if (inputPath == null) failure = IOException("TV did not open its input socket")
                            done.countDown()
                        }
                        "error" -> if (id == REGISTER_ID || id == INPUT_ID) {
                            failure = if (id == REGISTER_ID) LgDeniedException("TV refused the connection")
                            else IOException("TV did not open its input socket")
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
                        failure = failure ?: error ?: IOException("TV closed the connection")
                        done.countDown()
                    } else {
                        lost(error)
                    }
                }
            },
        )
        socket = ws
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw LgDeniedException("Nobody accepted the prompt on the TV")
            }
            failure?.let { throw it }
            openInput(inputPath!!)
            connected = true
        } catch (e: Throwable) {
            ws.cancel()
            input?.cancel()
            throw e
        }
    }

    private fun openInput(path: String) {
        val opened = CountDownLatch(1)
        var failure: Throwable? = null
        input = SamsungClient.client.newWebSocket(
            Request.Builder().url(path).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    lost(null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (opened.count > 0) {
                        failure = t
                        opened.countDown()
                    } else {
                        lost(t)
                    }
                }
            },
        )
        if (!opened.await(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IOException("TV input socket timed out")
        failure?.let { throw IOException("TV input socket failed", it) }
    }

    private fun lost(error: Throwable?) {
        if (!connected) return
        connected = false
        if (!closedByUs) {
            socket?.cancel()
            input?.cancel()
            listener.onDisconnected(error)
        }
    }

    override fun press(key: RemoteKey, action: KeyAction) {
        if (action == KeyAction.RELEASE) return
        when (key) {
            // There is no power button on the input socket of older TVs; this request always works.
            RemoteKey.POWER -> request("ssap://system/turnOff")
            RemoteKey.PLAY_PAUSE -> {
                playing = !playing
                button(if (playing) "PLAY" else "PAUSE")
            }
            else -> button(key.lgButton)
        }
    }

    fun button(name: String) {
        val ws = input ?: throw IOException("Not connected")
        if (!connected || !ws.send("type:button\nname:$name\n\n")) throw IOException("Not connected")
    }

    override fun launch(app: TvApp) = launchApp(app.lgAppId)

    fun launchApp(appId: String) =
        request("ssap://system.launcher/launch", """{"id":"${SamsungClient.escape(appId)}"}""")

    private fun request(uri: String, payload: String? = null) {
        val ws = socket ?: throw IOException("Not connected")
        val body = payload?.let { ""","payload":$it""" } ?: ""
        val json = """{"type":"request","id":"req_${nextId.getAndIncrement()}","uri":"$uri"$body}"""
        if (!connected || !ws.send(json)) throw IOException("Not connected")
    }

    override fun close() {
        closedByUs = true
        connected = false
        input?.close(1000, null)
        socket?.close(1000, null)
    }

    companion object {
        const val SECURE_PORT = 3001
        const val PLAIN_PORT = 3000
        private const val REGISTER_ID = "register_0"
        private const val INPUT_ID = "input_0"
        private const val INPUT_SOCKET_URI = "ssap://com.webos.service.networkinput/getPointerInputSocket"
        private const val INPUT_TIMEOUT_MS = 8000L

        /** True if something listens on one of the webOS ports of [host]. */
        fun probe(host: String, timeoutMs: Int = 700): Boolean =
            portOpen(host, SECURE_PORT, timeoutMs) || portOpen(host, PLAIN_PORT, timeoutMs)

        fun portOpen(host: String, port: Int, timeoutMs: Int = 700): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)

        /** Reads the TV's name from the UPnP description an SSDP reply points to. */
        fun nameFromDescription(url: String, timeoutMs: Int = 1500): String? = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Regex("<friendlyName>([^<]+)</friendlyName>").find(body)?.groupValues?.get(1)?.trim()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        /**
         * The permissions we ask the TV for. This is the app manifest every third-party webOS
         * remote sends: the TV checks the signature, so it cannot be trimmed or edited.
         */
        private const val MANIFEST =
            """{"manifestVersion":1,"appVersion":"1.1","signed":{"created":"20140509","appId":"com.lge.test","vendorId":"com.lge","localizedAppNames":{"":"LG Remote App","ko-KR":"\ub9ac\ubaa8\ucee8 \uc571","zxx-XX":"\u041b\u0413 R\u044d\u043cot\u044d A\u041f\u041f"},"localizedVendorNames":{"":"LG Electronics"},"permissions":["TEST_SECURE","CONTROL_INPUT_TEXT","CONTROL_MOUSE_AND_KEYBOARD","READ_INSTALLED_APPS","READ_LGE_SDX","READ_NOTIFICATIONS","SEARCH","WRITE_SETTINGS","WRITE_NOTIFICATION_ALERT","CONTROL_POWER","READ_CURRENT_CHANNEL","READ_RUNNING_APPS","READ_UPDATE_INFO","UPDATE_FROM_REMOTE_APP","READ_LGE_TV_INPUT_EVENTS","READ_TV_CURRENT_TIME"],"serial":"2f930e2d2cfe083771f68e4fe7bb07"},"permissions":["LAUNCH","LAUNCH_WEBAPP","APP_TO_APP","CLOSE","TEST_OPEN","TEST_PROTECTED","CONTROL_AUDIO","CONTROL_DISPLAY","CONTROL_INPUT_JOYSTICK","CONTROL_INPUT_MEDIA_RECORDING","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV","CONTROL_POWER","READ_APP_STATUS","READ_CURRENT_CHANNEL","READ_INPUT_DEVICE_LIST","READ_NETWORK_STATE","READ_RUNNING_APPS","READ_TV_CHANNEL_LIST","WRITE_NOTIFICATION_TOAST","READ_POWER_STATE","READ_COUNTRY_INFO","READ_SETTINGS","CONTROL_TV_SCREEN","CONTROL_TV_STANBY","CONTROL_FAVORITE_GROUP","CONTROL_USER_INFO","CHECK_BLUETOOTH_DEVICE","CONTROL_BLUETOOTH","CONTROL_TIMER_INFO","STB_INTERNAL_CONNECTION","CONTROL_RECORDING","READ_RECORDING_STATE","WRITE_RECORDING_LIST","READ_RECORDING_LIST","READ_RECORDING_SCHEDULE","WRITE_RECORDING_SCHEDULE","READ_STORAGE_DEVICE_LIST","READ_TV_PROGRAM_INFO","CONTROL_BOX_CHANNEL","READ_TV_ACR_AUTH_TOKEN","READ_TV_CONTENT_STATE","READ_TV_CURRENT_TIME","ADD_LAUNCHER_CHANNEL","SET_CHANNEL_SKIP","RELEASE_CHANNEL_SKIP","CONTROL_CHANNEL_BLOCK","DELETE_SELECT_CHANNEL","CONTROL_CHANNEL_GROUP","SCAN_TV_CHANNELS","CONTROL_TV_POWER","CONTROL_WOL"],"signatures":[{"signatureVersion":1,"signature":"eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="}]}"""
    }
}
