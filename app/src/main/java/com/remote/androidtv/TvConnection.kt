package com.remote.androidtv

import android.content.Context
import android.content.SharedPreferences
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.remote.androidtv.protocol.CertificateManager
import com.remote.androidtv.protocol.DeviceInfo
import com.remote.androidtv.protocol.IrCodeSet
import com.remote.androidtv.protocol.IrController
import com.remote.androidtv.protocol.KeyAction
import com.remote.androidtv.protocol.LgClient
import com.remote.androidtv.protocol.LgDeniedException
import com.remote.androidtv.protocol.NotPairedException
import com.remote.androidtv.protocol.PairingClient
import com.remote.androidtv.protocol.RemoteClient
import com.remote.androidtv.protocol.RemoteKey
import com.remote.androidtv.protocol.RokuClient
import com.remote.androidtv.protocol.SamsungClient
import com.remote.androidtv.protocol.SamsungDeniedException
import com.remote.androidtv.protocol.TvApp
import com.remote.androidtv.protocol.TvBrand
import com.remote.androidtv.protocol.TvController
import com.remote.androidtv.protocol.WrongCodeException
import java.io.File
import java.util.concurrent.Executors

/** App-wide connection to one device (any [TvBrand]). All callbacks arrive on the main thread. */
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
    private var ir: ConsumerIrManager? = null
    @Volatile private var controller: TvController? = null
    private var pairing: PairingClient? = null
    private var repeater: Runnable? = null
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
        ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }

    /** True if this phone has an infrared emitter. */
    val hasIr: Boolean get() = runCatching { ir?.hasIrEmitter() == true }.getOrDefault(false)

    fun addListener(l: StateListener) { listeners += l }
    fun removeListener(l: StateListener) { listeners -= l }

    /**
     * Connects to the device. [brand] null means "unknown" (IP typed by hand): the device is probed first.
     * For [TvBrand.IR], [host] is the name of the [IrCodeSet] instead of an address.
     * [onDetected] reports the brand once known, before a possibly long wait for the user to accept on the TV.
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
        stopRepeating()
        io.execute {
            controller?.close()
            controller = null
            val actual = brand ?: detect(host).let { (found, foundName) ->
                if (name == null && foundName != null) tvName = foundName
                found
            }
            this.brand = actual
            main.post { onDetected(actual) }
            val result = when (actual) {
                TvBrand.ANDROID_TV -> connectAndroid(host)
                TvBrand.SAMSUNG -> connectSamsung(host)
                TvBrand.LG -> connectLg(host)
                TvBrand.ROKU -> connectRoku(host)
                TvBrand.IR -> connectIr(host)
            }
            main.post {
                if (result == Result.Ok) notifyConnection(true)
                callback(result)
            }
        }
    }

    /** Finds out what kind of device answers at [host]; Android TV is the fallback. */
    private fun detect(host: String): Pair<TvBrand, String?> {
        SamsungClient.probe(host)?.let { return TvBrand.SAMSUNG to it }
        RokuClient.probe(host)?.let { return TvBrand.ROKU to it }
        if (LgClient.portOpen(host, RemoteClient.REMOTE_PORT)) return TvBrand.ANDROID_TV to null
        if (LgClient.probe(host)) return TvBrand.LG to "LG TV"
        return TvBrand.ANDROID_TV to null
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

    private fun connectLg(host: String): Result {
        val key = "lg_$host"
        val c = LgClient(host, prefs.getString(key, null), object : LgClient.Listener {
            override fun onDisconnected(error: Throwable?) = main.post { notifyConnection(false) }.let {}
        })
        return try {
            c.connect()
            c.clientKey?.let { prefs.edit().putString(key, it).apply() }
            controller = c
            Result.Ok
        } catch (e: LgDeniedException) {
            Result.Denied
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    private fun connectRoku(host: String): Result {
        val c = RokuClient(host, object : RokuClient.Listener {
            override fun onDisconnected(error: Throwable?) = main.post { notifyConnection(false) }.let {}
        })
        return try {
            c.connect()
            controller = c
            Result.Ok
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    private fun connectIr(codeSetName: String): Result {
        val emitter = ir?.takeIf { hasIr } ?: return Result.Failed(IllegalStateException("No infrared emitter"))
        val codeSet = runCatching { IrCodeSet.valueOf(codeSetName) }.getOrElse { return Result.Failed(it) }
        controller = IrController(codeSet) { emitter.transmit(it.frequencyHz, it.pattern) }
        return Result.Ok
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

    /**
     * Sends a key. Devices that cannot hold a key down get repeated clicks instead,
     * so holding an arrow or the volume still scrolls.
     */
    fun press(key: RemoteKey, action: KeyAction = KeyAction.CLICK) {
        if (action == KeyAction.CLICK || controller?.supportsHold != false) {
            send(key, action)
            return
        }
        stopRepeating()
        if (action == KeyAction.PRESS) {
            send(key, KeyAction.CLICK)
            if (key.repeats) {
                val r = object : Runnable {
                    override fun run() {
                        send(key, KeyAction.CLICK)
                        main.postDelayed(this, REPEAT_MS)
                    }
                }
                repeater = r
                main.postDelayed(r, REPEAT_MS)
            }
        }
    }

    private fun send(key: RemoteKey, action: KeyAction) = io.execute {
        runCatching { controller?.press(key, action) }
    }

    private fun stopRepeating() {
        repeater?.let { main.removeCallbacks(it) }
        repeater = null
    }

    fun launch(app: TvApp) = io.execute { runCatching { controller?.launch(app) } }

    fun disconnect() = io.execute {
        main.post { stopRepeating() }
        controller?.close()
        controller = null
        main.post { notifyConnection(false) }
    }

    private fun notifyConnection(connected: Boolean) {
        if (!connected) stopRepeating()
        listeners.toList().forEach { it.onConnectionChanged(connected) }
    }

    private const val REPEAT_MS = 220L
}
