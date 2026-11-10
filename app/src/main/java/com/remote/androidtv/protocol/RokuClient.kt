package com.remote.androidtv.protocol

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Roku players and Roku TVs (TCL, Hisense, Sharp, …) over Roku's External Control Protocol:
 * plain HTTP on port 8060, no pairing.
 */
class RokuClient(
    private val host: String,
    private val listener: Listener,
    private val port: Int = PORT,
) : TvController {

    interface Listener {
        fun onDisconnected(error: Throwable?) {}
    }

    @Volatile private var connected = false

    override val isConnected: Boolean get() = connected

    /** Checks that a Roku answers at this address. */
    fun connect() {
        probe(host, port, timeoutMs = 4000) ?: throw IOException("No Roku device answered at $host")
        connected = true
    }

    override fun press(key: RemoteKey, action: KeyAction) {
        val verb = when (action) {
            KeyAction.CLICK -> "keypress"
            KeyAction.PRESS -> "keydown"
            KeyAction.RELEASE -> "keyup"
        }
        post("$verb/${key.rokuKey}")
    }

    override fun launch(app: TvApp) = post("launch/${app.rokuAppId}")

    private fun post(path: String) {
        if (!connected) throw IOException("Not connected")
        try {
            val conn = URL("http://$host:$port/$path").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            try {
                conn.outputStream.close()
                val code = conn.responseCode
                // 403: "Control by mobile apps" is switched off in the Roku's settings.
                if (code == 403) throw IOException("Roku refused: enable Settings > System > Advanced > Control by mobile apps")
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            if (connected) {
                connected = false
                listener.onDisconnected(e)
            }
            throw e
        }
    }

    override fun close() {
        connected = false
    }

    companion object {
        const val PORT = 8060
        private const val TIMEOUT_MS = 3000

        /** Returns the device's name if a Roku answers at [host], null otherwise. */
        fun probe(host: String, port: Int = PORT, timeoutMs: Int = 1500): String? = runCatching {
            val conn = URL("http://$host:$port/query/device-info").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (!body.contains("<device-info")) return null
                xmlTag(body, "user-device-name")?.takeIf { it.isNotBlank() }
                    ?: xmlTag(body, "friendly-device-name")?.takeIf { it.isNotBlank() }
                    ?: xmlTag(body, "model-name")
                    ?: "Roku"
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        private fun xmlTag(xml: String, tag: String): String? =
            Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)?.trim()
                ?.replace("&amp;", "&")?.replace("&apos;", "'")?.replace("&quot;", "\"")
    }
}
