package com.remote.androidtv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.remote.androidtv.protocol.CertificateManager
import com.remote.androidtv.protocol.DeviceInfo
import com.remote.androidtv.protocol.KeyAction
import com.remote.androidtv.protocol.NotPairedException
import com.remote.androidtv.protocol.PairingClient
import com.remote.androidtv.protocol.RemoteClient
import com.remote.androidtv.protocol.RemoteKey
import com.remote.androidtv.protocol.SamsungClient
import com.remote.androidtv.protocol.SamsungDeniedException
import com.remote.androidtv.protocol.TvApp
import com.remote.androidtv.protocol.TvBrand
import com.remote.androidtv.protocol.TvController
import com.remote.androidtv.protocol.WrongCodeException
import java.io.File
import java.util.concurrent.Executors

/** App-wide connection to one TV (Android TV or Samsung). All callbacks arrive on the main thread. */
object TvConnection {

    sealed class Result {
        object Ok : Result()
        object NeedsPairing : Result()
        object WrongCode : Result()
        object Denied : Result()
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
    private lateinit var prefs: SharedPreferences
    private var controller: TvController? = null
    private var pairing: PairingClient? = null
    private val listeners = mutableSetOf<StateListener>()

    var host: String? = null
        private set
    var tvName: String? = null
        private set
    var brand: TvBrand? = null
        private set

    val isConnected: Boolean get() = controller?.isConnected == true

    private val phoneName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun init(context: Context) {
        if (::certs.isInitialized) return
        certs = CertificateManager(File(context.filesDir, "atvremote.p12"))
        device = DeviceInfo(model = Build.MODEL, vendor = Build.MANUFACTURER, appVersion = BuildConfigVersion.NAME)
        prefs = context.getSharedPreferences("tv_tokens", Context.MODE_PRIVATE)
    }

    fun addListener(l: StateListener) { listeners += l }
    fun removeListener(l: StateListener) { listeners -= l }

    /**
     * Connects to the TV. [brand] null means "unknown" (IP typed by hand): the TV is probed first.
     * [onDetected] reports the brand once known, before a possibly long wait for the user on a Samsung TV.
     */
    fun connect(
        host: String,
        name: String?,
        brand: TvBrand?,
        onDetected: (TvBrand) -> Unit = {},
        callback: (Result) -> Unit,
    ) {
        this.host = host
        this.tvName = name ?: host
        io.execute {
            controller?.close()
            controller = null
            var samsungName: String? = null
            val actual = brand ?: SamsungClient.probe(host).let {
                samsungName = it
                if (it != null) TvBrand.SAMSUNG else TvBrand.ANDROID_TV
            }
            this.brand = actual
            if (name == null && samsungName != null) tvName = samsungName
            main.post { onDetected(actual) }
            val result = when (actual) {
                TvBrand.ANDROID_TV -> connectAndroid(host)
                TvBrand.SAMSUNG -> connectSamsung(host)
            }
            main.post {
                if (result == Result.Ok) notifyConnection(true)
                callback(result)
            }
        }
    }

    private fun connectAndroid(host: String): Result {
        val c = RemoteClient(host, certs, device, object : RemoteClient.Listener {
            override fun onDisconnected(error: Throwable?) = main.post { notifyConnection(false) }.let {}
            override fun onVolumeChanged(level: Int, max: Int, muted: Boolean) =
                main.post { listeners.toList().forEach { it.onVolumeChanged(level, max, muted) } }.let {}
        })
        return try {
            c.connect()
            controller = c
            Result.Ok
        } catch (e: NotPairedException) {
            Result.NeedsPairing
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    private fun connectSamsung(host: String): Result {
        val key = "samsung_$host"
        val c = SamsungClient(host, "TV Remote ($phoneName)", prefs.getString(key, null), object : SamsungClient.Listener {
            override fun onDisconnected(error: Throwable?) = main.post { notifyConnection(false) }.let {}
        })
        return try {
            c.connect()
            c.token?.let { prefs.edit().putString(key, it).apply() }
            controller = c
            Result.Ok
        } catch (e: SamsungDeniedException) {
            Result.Denied
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    /** Android TV only: asks the TV to show a pairing code. */
    fun startPairing(host: String, callback: (Result) -> Unit) = io.execute {
        pairing?.close()
        val p = PairingClient(host, certs, phoneName)
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

    fun press(key: RemoteKey, action: KeyAction = KeyAction.CLICK) = io.execute {
        runCatching { controller?.press(key, action) }
    }

    fun launch(app: TvApp) = io.execute { runCatching { controller?.launch(app) } }

    fun disconnect() = io.execute {
        controller?.close()
        controller = null
        main.post { notifyConnection(false) }
    }

    private fun notifyConnection(connected: Boolean) =
        listeners.toList().forEach { it.onConnectionChanged(connected) }
}
