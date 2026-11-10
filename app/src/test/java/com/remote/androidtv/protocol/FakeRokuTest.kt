package com.remote.androidtv.protocol

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FakeRokuTest {
    private lateinit var roku: MockWebServer
    private var dropped = false

    @Before fun setUp() { roku = MockWebServer().apply { start() } }
    @After fun tearDown() = roku.shutdown()

    private val info = "<?xml version=\"1.0\"?><device-info><model-name>Roku Ultra</model-name>" +
        "<friendly-device-name>Roku Ultra - X1</friendly-device-name>" +
        "<user-device-name>Mum &amp; Dad's TV</user-device-name></device-info>"

    private fun client() = RokuClient("127.0.0.1", object : RokuClient.Listener {
        override fun onDisconnected(error: Throwable?) { dropped = true }
    }, roku.port)

    private fun nextPath(): String = roku.takeRequest().let { "${it.method} ${it.path}" }

    @Test fun sendsKeysAndLaunchesApps() {
        roku.enqueue(MockResponse().setBody(info))
        val c = client()
        c.connect()
        assertTrue(c.isConnected)
        assertEquals("GET /query/device-info", nextPath())

        repeat(4) { roku.enqueue(MockResponse()) }
        c.press(RemoteKey.OK)
        assertEquals("POST /keypress/Select", nextPath())
        c.press(RemoteKey.RIGHT, KeyAction.PRESS)
        assertEquals("POST /keydown/Right", nextPath())
        c.press(RemoteKey.RIGHT, KeyAction.RELEASE)
        assertEquals("POST /keyup/Right", nextPath())
        c.launch(TvApp.YOUTUBE)
        assertEquals("POST /launch/837", nextPath())
        assertFalse(dropped)
    }

    @Test fun reportsWhenControlIsSwitchedOff() {
        roku.enqueue(MockResponse().setBody(info))
        val c = client()
        c.connect()
        roku.enqueue(MockResponse().setResponseCode(403))
        try {
            c.press(RemoteKey.HOME)
            fail("a refused key press went unnoticed")
        } catch (_: IOException) {}
        assertTrue(dropped)
        assertFalse(c.isConnected)
    }

    @Test fun probeRecognisesRoku() {
        roku.enqueue(MockResponse().setBody(info))
        assertEquals("Mum & Dad's TV", RokuClient.probe("127.0.0.1", roku.port))
        roku.enqueue(MockResponse().setBody("<html>router</html>"))
        assertNull(RokuClient.probe("127.0.0.1", roku.port))
        try {
            roku.enqueue(MockResponse().setBody("<html>router</html>"))
            client().connect()
            fail("connected to something that is not a Roku")
        } catch (_: IOException) {}
    }
}
