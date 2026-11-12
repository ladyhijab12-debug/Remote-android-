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
    private var alertEvery = 0
    private var lockTouch = false
    private var autoStart = false
    private var wantCssTest = false
    private var drillLengths = 2
        set(value) {
            field = value
            render()
        }
    private var showingHistory = false
    private var savedSession: SwimSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_swim)
        poolLength = prefs().getInt(KEY_POOL, 50)
        alertEvery = prefs().getInt(KEY_ALERT, 0)
        lockTouch = prefs().getBoolean(KEY_LOCK, false)
        autoStart = prefs().getBoolean(KEY_AUTO, false)

        click(R.id.poolMinus) { setPool(poolLength - 1) }
        click(R.id.poolPlus) { setPool(poolLength + 1) }
        click(R.id.pool25) { setPool(25) }
        click(R.id.pool50) { setPool(50) }
        click(R.id.startBtn) { requestStart(cssTest = false) }
        click(R.id.cssBtn) { requestStart(cssTest = true) }
        click(R.id.drillBtn) { if (!touchLocked()) toggleDrill() }
        findViewById<View>(R.id.drillBtn).setOnLongClickListener {
            toggleDrill()
            true
        }
        click(R.id.drillMinus) { drillLengths = (drillLengths - 1).coerceAtLeast(0) }
        click(R.id.drillPlus) { drillLengths = (drillLengths + 1).coerceAtMost(40) }
        click(R.id.drillSave) { SwimTracker.saveDrill(drillLengths) }
        click(R.id.historyBtn) {
            showingHistory = true
            render()
        }
        click(R.id.alertBtn) {
            val next = ALERT_STEPS[(ALERT_STEPS.indexOf(alertEvery) + 1) % ALERT_STEPS.size]
            alertEvery = next
            prefs().edit().putInt(KEY_ALERT, next).apply()
            render()
        }
        click(R.id.lockBtn) {
            lockTouch = !lockTouch
            prefs().edit().putBoolean(KEY_LOCK, lockTouch).apply()
            render()
        }
        click(R.id.restBtn) { if (!touchLocked()) toggleRest() }
        // A long press gets through the touch lock: water rarely holds still that long.
        findViewById<View>(R.id.restBtn).setOnLongClickListener {
            toggleRest()
            true
        }
        click(R.id.addLengthBtn) { if (!touchLocked()) addLength() }
        click(R.id.removeLengthBtn) { if (!touchLocked()) SwimTracker.removeLength() }
        click(R.id.pauseBtn) {
            if (touchLocked()) return@click
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
        click(R.id.autoBtn) {
            autoStart = !autoStart
            prefs().edit().putBoolean(KEY_AUTO, autoStart).apply()
            render()
        }
        click(R.id.textDone) {
            when {
                SwimTracker.state == SwimTracker.State.ARMED -> SwimService.send(this, SwimService.ACTION_END)
                showingHistory -> showingHistory = false
                else -> SwimTracker.clear()
            }
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

    /**
     * Hardware buttons work when the screen is wet. With automatic counting a press is the Garmin
     * lap button: rest / go. With manual counting a press counts a length and a long press rests.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (swimming() && keyCode in SWIM_KEYS) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (swimming() && keyCode in SWIM_KEYS) {
            longPressed = true
            if (SwimTracker.session?.isResting == false) toggleRest()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (swimming() && keyCode in SWIM_KEYS) {
            val wasLong = longPressed
            longPressed = false
            if (!wasLong && !event.isCanceled) {
                val resting = SwimTracker.session?.isResting == true
                if (resting || SwimTracker.autoLengths) toggleRest() else addLength()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private var longPressed = false

    private fun swimming() =
        SwimTracker.state == SwimTracker.State.ACTIVE || SwimTracker.state == SwimTracker.State.PAUSED

    /** The hardware button and the rest button also stop a running drill. */
    private fun toggleRest() {
        if (SwimTracker.state != SwimTracker.State.ACTIVE) return
        if (SwimTracker.session?.isDrilling == true) {
            toggleDrill()
            return
        }
        SwimTracker.toggleRest()
        buzz(if (SwimTracker.session?.isResting == true) 400 else 200)
    }

    private fun toggleDrill() {
        if (SwimTracker.state != SwimTracker.State.ACTIVE) return
        SwimTracker.toggleDrill()
        buzz(if (SwimTracker.session?.isDrilling == true) 200 else 400)
    }

    private fun addLength() {
        if (SwimTracker.session?.isResting == true) return
        if (SwimTracker.addLength()) buzz(120)
    }

    /** With the lock on, taps do nothing while actually swimming; they work again at the wall. */
    private fun touchLocked(): Boolean {
        val locked = lockTouch && SwimTracker.state == SwimTracker.State.ACTIVE &&
            SwimTracker.session?.isResting == false
        if (locked) Toast.makeText(this, R.string.locked_hint, Toast.LENGTH_SHORT).show()
        return locked
    }

    private fun buzz(millis: Long) {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun setPool(meters: Int) {
        poolLength = meters.coerceIn(10, 100)
        prefs().edit().putInt(KEY_POOL, poolLength).apply()
        render()
    }

    private fun requestStart(cssTest: Boolean) {
        wantCssTest = cssTest
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
        SwimTracker.alertEveryM = alertEvery
        val action = if (autoStart) SwimService.ACTION_ARM else SwimService.ACTION_START
        SwimService.send(this, action, poolLength, wantCssTest)
    }

    private fun render() {
        val session = SwimTracker.session
        val state = SwimTracker.state
        val swimming = state == SwimTracker.State.STARTING || state == SwimTracker.State.ACTIVE ||
            state == SwimTracker.State.PAUSED || state == SwimTracker.State.ENDING

        if (swimming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        when {
            state == SwimTracker.State.ARMED -> {
                show(Screen.TEXT)
                text(R.id.textBody, getString(R.string.waiting_water))
                text(R.id.textDone, getString(R.string.cancel))
            }
            showingHistory && !swimming -> {
                show(Screen.TEXT)
                text(R.id.textBody, SwimHistory.load(this).ifEmpty { getString(R.string.no_history) })
                text(R.id.textDone, getString(R.string.back))
            }
            session != null && state == SwimTracker.State.ENDED -> {
                if (savedSession !== session) {
                    savedSession = session
                    if (session.lengths.isNotEmpty()) SwimHistory.save(this, session)
                    session.cssMillisPer100?.let { prefs().edit().putLong(KEY_CSS, it).apply() }
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
                text(
                    R.id.alertBtn,
                    if (alertEvery == 0) getString(R.string.alert_off) else getString(R.string.alert_fmt, alertEvery),
                )
                text(R.id.lockBtn, getString(if (lockTouch) R.string.lock_on else R.string.lock_off))
                text(R.id.autoBtn, getString(if (autoStart) R.string.auto_on else R.string.auto_off))
                val css = prefs().getLong(KEY_CSS, 0L)
                text(
                    R.id.cssBtn,
                    if (css > 0) getString(R.string.css_test_fmt, SwimFormat.pace(css)) else getString(R.string.css_test),
                )
            }
        }
    }

    private fun renderLive(s: SwimSession, state: SwimTracker.State) {
        val now = SwimTracker.activeMillis()
        text(
            R.id.liveStatus,
            when {
                state == SwimTracker.State.STARTING -> getString(R.string.starting)
                state == SwimTracker.State.PAUSED -> getString(R.string.paused)
                state == SwimTracker.State.ENDING -> getString(R.string.saving)
                s.isDrilling || s.drillAwaitingDistance ->
                    getString(R.string.drilling_fmt, SwimFormat.duration(s.currentDrillMillis(now)))
                s.isResting -> getString(R.string.resting_fmt, SwimFormat.duration(s.currentRestMillis(now)))
                else -> ""
            },
        )
        text(R.id.liveCss, cssGuide(s))
        visible(R.id.liveCss, s.cssTest)
        visible(R.id.drillPanel, s.drillAwaitingDistance)
        visible(R.id.drillBtn, !s.drillAwaitingDistance && !s.cssTest)
        text(R.id.drillBtn, getString(if (s.isDrilling) R.string.drill_stop else R.string.drill_start))
        text(R.id.drillValue, "${drillLengths * s.poolLengthM} m")
        text(R.id.liveDistance, "${s.distanceM} m")
        text(R.id.liveTime, SwimFormat.duration(now))
        val current = s.currentInterval
        text(
            R.id.liveInterval,
            "#${current.number} · ${current.distanceM} m · ${SwimFormat.duration(current.millis)}",
        )
        text(R.id.liveLengths, s.lengths.size.toString())
        text(R.id.livePace, SwimFormat.pace(s.paceMillisPer100))
        text(R.id.liveIntervalPace, SwimFormat.pace(current.paceMillisPer100))
        text(R.id.liveLast, s.lastLength?.let { SwimFormat.split(it.millis) } ?: "--")
        text(R.id.liveHr, s.heartRate?.toString() ?: "--")
        text(R.id.liveStrokes, s.strokesTotal?.toString() ?: "--")
        text(R.id.liveStrokeRate, SwimFormat.decimal(s.lastLength?.strokeRate))
        text(R.id.liveDps, s.lastLength?.metersPerStroke?.let { String.format(Locale.US, "%.2f", it) } ?: "--")
        text(R.id.liveSwolf, s.lastLength?.swolf?.toString() ?: "--")
        text(R.id.restBtn, getString(if (s.isResting) R.string.go else R.string.rest))
        text(R.id.pauseBtn, getString(if (state == SwimTracker.State.PAUSED) R.string.resume else R.string.pause))
        val mode = getString(if (SwimTracker.autoLengths) R.string.mode_auto else R.string.mode_manual)
        text(R.id.liveMode, if (lockTouch) mode + "\n" + getString(R.string.locked_hint) else mode)
    }

    /** What to do next in a CSS test. */
    private fun cssGuide(s: SwimSession): String {
        val target = s.cssTargetM
        return when {
            !s.cssTest -> ""
            s.cssMillisPer100 != null -> getString(R.string.css_done_fmt, SwimFormat.pace(s.cssMillisPer100))
            target == null -> getString(R.string.css_failed)
            s.isResting && s.interval == 1 && s.lengths.isNotEmpty() -> getString(R.string.css_rest)
            else -> getString(R.string.css_swim_fmt, target)
        }
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
        private const val KEY_ALERT = "alert"
        private const val KEY_LOCK = "lock"
        private const val KEY_AUTO = "auto"
        private const val KEY_CSS = "css"
        private val ALERT_STEPS = listOf(0, 100, 200, 400, 500, 1000)
        private val SWIM_KEYS = setOf(
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
