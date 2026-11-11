package com.remote.swim

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The whole watch UI: choose the pool, watch the numbers while swimming, read the report after. */
class SwimActivity : Activity() {

    private enum class Screen { SETUP, LIVE, TEXT }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private var poolLength = 50
    private var showingHistory = false
    private var savedSession: SwimSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_swim)
        poolLength = prefs().getInt(KEY_POOL, 50)

        click(R.id.poolMinus) { setPool(poolLength - 1) }
        click(R.id.poolPlus) { setPool(poolLength + 1) }
        click(R.id.pool25) { setPool(25) }
        click(R.id.pool50) { setPool(50) }
        click(R.id.startBtn) { requestStart() }
        click(R.id.historyBtn) {
            showingHistory = true
            render()
        }
        click(R.id.addLengthBtn) { addLength() }
        click(R.id.removeLengthBtn) { SwimTracker.removeLength() }
        click(R.id.pauseBtn) {
            val action = if (SwimTracker.state == SwimTracker.State.PAUSED) SwimService.ACTION_RESUME
            else SwimService.ACTION_PAUSE
            SwimService.send(this, action)
        }
        // Wet screens produce stray taps, so ending takes a deliberate long press.
        click(R.id.endBtn) { Toast.makeText(this, R.string.end_hint, Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.endBtn).setOnLongClickListener {
            SwimService.send(this, SwimService.ACTION_END)
            true
        }
        click(R.id.textDone) {
            if (showingHistory) showingHistory = false else SwimTracker.clear()
            render()
        }
    }

    override fun onStart() {
        super.onStart()
        SwimTracker.onChange = { render() }
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        SwimTracker.onChange = null
        handler.removeCallbacks(tick)
    }

    /** While swimming, any hardware button counts a length: buttons work when the screen is wet. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val swimming = SwimTracker.state == SwimTracker.State.ACTIVE || SwimTracker.state == SwimTracker.State.PAUSED
        if (swimming && keyCode in LENGTH_KEYS) {
            if (event.repeatCount == 0) addLength()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun addLength() {
        if (SwimTracker.state != SwimTracker.State.ACTIVE) return
        SwimTracker.addLength()
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun setPool(meters: Int) {
        poolLength = meters.coerceIn(10, 100)
        prefs().edit().putInt(KEY_POOL, poolLength).apply()
        render()
    }

    private fun requestStart() {
        val needed = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startSwim() else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Heart rate and notifications are optional; activity recognition is what the tracking needs.
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            startSwim()
        } else {
            text(R.id.setupMessage, getString(R.string.need_permission))
        }
    }

    private fun startSwim() {
        text(R.id.setupMessage, "")
        SwimService.send(this, SwimService.ACTION_START, poolLength)
    }

    private fun render() {
        val session = SwimTracker.session
        val state = SwimTracker.state
        val swimming = state == SwimTracker.State.STARTING || state == SwimTracker.State.ACTIVE ||
            state == SwimTracker.State.PAUSED

        if (swimming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        when {
            showingHistory && !swimming -> {
                show(Screen.TEXT)
                text(R.id.textBody, SwimHistory.load(this).ifEmpty { getString(R.string.no_history) })
                text(R.id.textDone, getString(R.string.back))
            }
            session != null && state == SwimTracker.State.ENDED -> {
                if (savedSession !== session) {
                    savedSession = session
                    if (session.lengths.isNotEmpty()) SwimHistory.save(this, session)
                }
                show(Screen.TEXT)
                text(R.id.textBody, SwimFormat.summary(session))
                text(R.id.textDone, getString(R.string.done))
            }
            session != null && swimming -> {
                show(Screen.LIVE)
                renderLive(session, state)
            }
            else -> {
                show(Screen.SETUP)
                text(R.id.poolValue, getString(R.string.meters_fmt, poolLength))
            }
        }
    }

    private fun renderLive(s: SwimSession, state: SwimTracker.State) {
        text(
            R.id.liveStatus,
            when (state) {
                SwimTracker.State.STARTING -> getString(R.string.starting)
                SwimTracker.State.PAUSED -> getString(R.string.paused)
                else -> ""
            },
        )
        text(R.id.liveDistance, "${s.distanceM} m")
        text(R.id.liveTime, SwimFormat.duration(SwimTracker.activeMillis()))
        text(R.id.liveLengths, s.lengths.size.toString())
        text(R.id.livePace, SwimFormat.pace(s.paceMillisPer100))
        text(R.id.liveLast, s.lastLength?.let { SwimFormat.split(it.millis) } ?: "--")
        text(R.id.liveHr, s.heartRate?.toString() ?: "--")
        text(R.id.liveStrokes, s.strokesTotal?.toString() ?: "--")
        text(R.id.liveSwolf, s.lastLength?.swolf?.toString() ?: "--")
        text(R.id.pauseBtn, getString(if (state == SwimTracker.State.PAUSED) R.string.resume else R.string.pause))
        text(R.id.liveMode, getString(if (SwimTracker.autoLengths) R.string.mode_auto else R.string.mode_manual))
    }

    private fun show(screen: Screen) {
        visible(R.id.screenSetup, screen == Screen.SETUP)
        visible(R.id.screenLive, screen == Screen.LIVE)
        visible(R.id.screenText, screen == Screen.TEXT)
    }

    private fun visible(id: Int, on: Boolean) {
        val view = findViewById<View>(id)
        val wanted = if (on) View.VISIBLE else View.GONE
        if (view.visibility != wanted) {
            view.visibility = wanted
            if (on) view.scrollTo(0, 0)
        }
    }

    private fun text(id: Int, value: String) {
        val view = findViewById<TextView>(id)
        if (view.text.toString() != value) view.text = value
    }

    private fun click(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener { action() }

    private fun prefs() = getSharedPreferences("swim", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_POOL = "pool"
        private val LENGTH_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_STEM_1,
            KeyEvent.KEYCODE_STEM_2,
            KeyEvent.KEYCODE_STEM_3,
        )
    }
}

/** The last swims, kept on the watch as ready-to-read text. */
object SwimHistory {
    private const val KEY = "history"
    private const val SEPARATOR = "\u0001"
    private const val KEEP = 30

    fun save(context: Context, session: SwimSession) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(session.startedAtEpochMs))
        val entry = buildString {
            append(date).append('\n')
            append("${session.distanceM} م · ${SwimFormat.duration(session.activeMillis)}")
            append(" · ${SwimFormat.pace(session.paceMillisPer100)}/100م\n")
            append("${session.lengths.size} × ${session.poolLengthM} م")
            session.avgSwolf?.let { append(" · SWOLF $it") }
            session.avgHeartRate?.let { append(" · نبض $it") }
        }
        val all = (listOf(entry) + entries(context)).take(KEEP)
        prefs(context).edit().putString(KEY, all.joinToString(SEPARATOR)).apply()
    }

    fun load(context: Context): String = entries(context).joinToString("\n\n")

    private fun entries(context: Context): List<String> =
        prefs(context).getString(KEY, "").orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    private fun prefs(context: Context) = context.getSharedPreferences("swim", Context.MODE_PRIVATE)
}
