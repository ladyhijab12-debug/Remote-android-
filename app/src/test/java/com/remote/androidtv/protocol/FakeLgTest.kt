package com.remote.androidtv.protocol

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class FakeLgTest {
    private lateinit var tv: MockWebServer
    private val requests = ArrayBlockingQueue<String>(10)
    private val buttons = ArrayBlockingQueue<String>(10)

    /** A port nothing listens on, so the client falls back from TLS to plain ws. */
    private val closedPort = ServerSocket(0).use { it.localPort }

    @Before fun setUp() { tv = MockWebServer().apply { start() } }
    @After fun tearDown() = tv.shutdown()

    /** The TV's main socket: answers the registration with [registerReply], then opens the input socket. */
    private fun tvAnswers(registerReply: String) {
        tv.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                requests.add(text)
                when {
                    text.contains("\"type\":\"register\"") -> {
                        webSocket.send("""{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}""")
                        webSocket.send(registerReply)
                    }
                    text.contains("getPointerInputSocket") -> {
                        val id = SamsungClient.jsonString(text, "id")
                        webSocket.send(
                            """{"type":"response","id":"$id","payload":{"socketPath":"ws:\/\/127.0.0.1:${tv.port}\/input","returnValue":true}}""",
                        )
                    }
                }
            }

            // Finish the closing handshake, or the server cannot shut down after the test.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        tv.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { buttons.add(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
    }

    private fun client(key: String?) =
        LgClient("127.0.0.1", key, object : LgClient.Listener {}, closedPort, tv.port)

    private fun next(queue: ArrayBlockingQueue<String>) = queue.poll(5, TimeUnit.SECONDS)!!

    @Test fun pairsAndSendsButtons() {
        tvAnswers("""{"type":"registered","id":"register_0","payload":{"client-key":"abc123"}}""")
        val c = client(key = null)
        c.connect(timeoutMs = 5000)
        assertTrue(c.isConnected)
        assertEquals("abc123", c.clientKey)

        val register = next(requests)
        assertTrue(register, register.contains("\"pairingType\":\"PROMPT\"") && register.contains("\"signatures\""))
        assertFalse(register, register.contains("client-key"))
        assertTrue(next(requests).contains("getPointerInputSocket"))
        assertEquals("/input", tv.takeRequest().let { tv.takeRequest().path })

        c.press(RemoteKey.VOLUME_UP)
        assertEquals("type:button\nname:VOLUMEUP\n\n", next(buttons))
        c.press(RemoteKey.OK, KeyAction.PRESS)
        assertEquals("type:button\nname:ENTER\n\n", next(buttons))
        c.press(RemoteKey.OK, KeyAction.RELEASE) // nothing is sent for a release

        c.press(RemoteKey.PLAY_PAUSE)
        assertEquals("type:button\nname:PLAY\n\n", next(buttons))
        c.press(RemoteKey.PLAY_PAUSE)
        assertEquals("type:button\nname:PAUSE\n\n", next(buttons))

        c.press(RemoteKey.POWER)
        assertTrue(next(requests).contains("\"uri\":\"ssap://system/turnOff\""))
        c.launch(TvApp.NETFLIX)
        val launch = next(requests)
        assertTrue(launch, launch.contains("ssap://system.launcher/launch") && launch.contains("\"payload\":{\"id\":\"netflix\"}"))
        c.close()
        assertFalse(c.isConnected)
    }

    @Test fun sendsSavedKey() {
        tvAnswers("""{"type":"registered","id":"register_0","payload":{"client-key":"saved"}}""")
        val c = client(key = "saved")
        c.connect(timeoutMs = 5000)
        assertTrue(next(requests).contains("\"client-key\":\"saved\""))
        c.close()
    }

    @Test fun deniedOnTv() {
        tvAnswers("""{"type":"error","id":"register_0","error":"403 User denied access","payload":{}}""")
        try {
            client(key = null).connect(timeoutMs = 5000)
            fail("connected although the TV refused")
        } catch (_: LgDeniedException) {}
    }

    @Test fun probeFindsOpenPort() {
        assertTrue(LgClient.portOpen("127.0.0.1", tv.port))
        assertFalse(LgClient.portOpen("127.0.0.1", closedPort))
    }

    @Test fun readsNameFromDescription() {
        tv.enqueue(MockResponse().setBody("<root><device><friendlyName>[LG] webOS TV OLED55</friendlyName></device></root>"))
        assertEquals("[LG] webOS TV OLED55", LgClient.nameFromDescription("http://127.0.0.1:${tv.port}/"))
    }
}
