package com.remote.androidtv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.remote.androidtv.protocol.CertificateManager
import com.remote.androidtv.protocol.DeviceInfo
import com.remote.androidtv.protocol.NotPairedException
import com.remote.androidtv.protocol.PairingClient
import com.remote.androidtv.protocol.RemoteClient
import com.remote.androidtv.protocol.WrongCodeException
import java.io.File
import java.util.concurrent.Executors

/** App-wide connection to one TV. All callbacks are delivered on the main thread. */
object TvConnection {

    sealed class Result {
        object Ok : Result()
        object NeedsPairing : Result()
        object WrongCode : Result()
        data class Failed(val error: Throwable) : Result()
    }

    interface StateListener {
        fun onConnectionChanged(connected: Boolean)
        fun onVolumeChanged(level: Int, max: Int, muted: Boolean) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var certs: CertificateManager
    private lateinit var device: DeviceInfo
    private var client: RemoteClient? = null
    private var pairing: PairingClient? = null
    private val listeners = mutableSetOf<StateListener>()

    var host: String? = null
        private set
    var tvName: String? = null
        private set

    val isConnected: Boolean get() = client?.isConnected == true

    fun init(context: Context) {
        if (::certs.isInitialized) return
        certs = CertificateManager(File(context.filesDir, "atvremote.p12"))
        device = DeviceInfo(model = Build.MODEL, vendor = Build.MANUFACTURER, appVersion = BuildConfigVersion.NAME)
    }

    fun addListener(l: StateListener) { listeners += l }
    fun removeListener(l: StateListener) { listeners -= l }

    fun connect(host: String, name: String?, callback: (Result) -> Unit) {
        this.host = host
        this.tvName = name ?: host
        io.execute {
            client?.close()
            client = null
            val c = RemoteClient(host, certs, device, object : RemoteClient.Listener {
                override fun onDisconnected(error: Throwable?) = main.post { notifyConnection(false) }.let {}
                override fun onVolumeChanged(level: Int, max: Int, muted: Boolean) =
                    main.post { listeners.toList().forEach { it.onVolumeChanged(level, max, muted) } }.let {}
            })
            val result = try {
                c.connect()
                client = c
                Result.Ok
            } catch (e: NotPairedException) {
                Result.NeedsPairing
            } catch (e: Exception) {
                Result.Failed(e)
            }
            main.post {
                if (result == Result.Ok) notifyConnection(true)
                callback(result)
            }
        }
    }

    /** Asks the TV to show a pairing code. */
    fun startPairing(host: String, callback: (Result) -> Unit) = io.execute {
        pairing?.close()
        val p = PairingClient(host, certs, "${Build.MANUFACTURER} ${Build.MODEL}")
        val result = try {
            p.start()
            pairing = p
            Result.Ok
        } catch (e: Exception) {
            p.close()
            Result.Failed(e)
        }
        main.post { callback(result) }
    }

    fun finishPairing(code: String, callback: (Result) -> Unit) = io.execute {
        val p = pairing
        val result = if (p == null) {
            Result.Failed(IllegalStateException("Pairing not started"))
        } else try {
            p.finish(code)
            p.close()
            pairing = null
            Result.Ok
        } catch (e: WrongCodeException) {
            Result.WrongCode
        } catch (e: Exception) {
            p.close()
            pairing = null
            Result.Failed(e)
        }
        main.post { callback(result) }
    }

    fun cancelPairing() = io.execute {
        pairing?.close()
        pairing = null
    }

    fun sendKey(keyCode: Int, direction: Int = RemoteClient.DIRECTION_SHORT) = io.execute {
        runCatching { client?.sendKey(keyCode, direction) }
    }

    fun launchApp(link: String) = io.execute { runCatching { client?.launchApp(link) } }

    fun disconnect() = io.execute {
        client?.close()
        client = null
        main.post { notifyConnection(false) }
    }

    private fun notifyConnection(connected: Boolean) =
        listeners.toList().forEach { it.onConnectionChanged(connected) }
}
