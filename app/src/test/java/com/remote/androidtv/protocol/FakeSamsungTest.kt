package com.remote.androidtv.protocol

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class FakeSamsungTest {
    private lateinit var tv: MockWebServer
    private val received = ArrayBlockingQueue<String>(10)

    /** A port nothing listens on, so the client falls back from 8002-style TLS to plain ws. */
    private val closedPort = ServerSocket(0).use { it.localPort }

    @Before fun setUp() { tv = MockWebServer().apply { start() } }
    @After fun tearDown() = tv.shutdown()

    private fun tvAnswers(firstEvent: String) {
        tv.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(firstEvent) }
            override fun onMessage(webSocket: WebSocket, text: String) { received.add(text) }
            // A real TV answers the close frame; without this the connection lingers and
            // shutting the fake TV down times out.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
    }

    private fun client(token: String?) =
        SamsungClient("127.0.0.1", "TV Remote", token, object : SamsungClient.Listener {}, closedPort, tv.port)

    @Test fun connectsAndSendsKeys() {
        tvAnswers("""{"data":{"clients":[],"id":"x","token":"98765"},"event":"ms.channel.connect"}""")
        val c = client(token = null)
        c.connect(timeoutMs = 5000)
        assertTrue(c.isConnected)
        assertEquals("98765", c.token)

        val path = tv.takeRequest().path!!
        assertTrue(path, path.startsWith("/api/v2/channels/samsung.remote.control?name=VFYgUmVtb3Rl"))

        c.press(RemoteKey.VOLUME_UP)
        val key = received.poll(5, TimeUnit.SECONDS)!!
        assertTrue(key, key.contains("\"DataOfCmd\":\"KEY_VOLUP\"") && key.contains("\"Cmd\":\"Click\""))

        c.press(RemoteKey.OK, KeyAction.PRESS)
        assertTrue(received.poll(5, TimeUnit.SECONDS)!!.contains("\"Cmd\":\"Press\""))

        c.press(RemoteKey.PLAY_PAUSE)
        assertTrue(received.poll(5, TimeUnit.SECONDS)!!.contains("KEY_PLAY\""))
        c.press(RemoteKey.PLAY_PAUSE)
        assertTrue(received.poll(5, TimeUnit.SECONDS)!!.contains("KEY_PAUSE\""))

        c.launch(TvApp.YOUTUBE)
        val launch = received.poll(5, TimeUnit.SECONDS)!!
        assertTrue(launch, launch.contains("\"appId\":\"111299001912\"") && launch.contains("ed.apps.launch"))
        c.close()
    }

    @Test fun sendsSavedToken() {
        tvAnswers("""{"event":"ms.channel.connect","data":{"id":"x"}}""")
        val c = client(token = "555")
        c.connect(timeoutMs = 5000)
        assertTrue(tv.takeRequest().path!!.endsWith("&token=555"))
        assertEquals("555", c.token)
        c.close()
    }

    @Test fun deniedOnTv() {
        tvAnswers("""{"event":"ms.channel.unauthorized"}""")
        try {
            client(token = null).connect(timeoutMs = 5000)
            fail("connected although the TV refused")
        } catch (_: SamsungDeniedException) {}
    }

    @Test fun probeRecognisesSamsung() {
        tv.enqueue(MockResponse().setBody("""{"device":{"name":"[TV] Living room","type":"Samsung SmartTV"},"name":"[TV] Living room"}"""))
        assertEquals("[TV] Living room", SamsungClient.probe("127.0.0.1", tv.port))
        tv.enqueue(MockResponse().setBody("""{"name":"Some router"}"""))
        assertNull(SamsungClient.probe("127.0.0.1", tv.port))
        assertNull(SamsungClient.probe("127.0.0.1", closedPort))
    }
}
