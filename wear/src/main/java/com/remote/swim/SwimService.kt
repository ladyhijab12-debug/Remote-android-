package com.remote.swim

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.roundToInt

/** The swim in progress, shared between the service that measures it and the screen that shows it. */
object SwimTracker {
    enum class State { IDLE, STARTING, ACTIVE, PAUSED, ENDED }

    var state = State.IDLE
        private set
    var session: SwimSession? = null
        private set

    /** True when the watch itself counts lengths; false means the swimmer presses a button. */
    var autoLengths = false

    /** Called on the main thread whenever anything above changes. */
    var onChange: (() -> Unit)? = null

    /** Vibrate every this many metres, as a Garmin distance alert does; 0 turns it off. */
    var alertEveryM = 0

    /** Called when the swimmer passes an alert distance. */
    var onAlert: (() -> Unit)? = null

    private var alertedDistanceM = 0

    // Swim time is known exactly at one instant and runs on from there while active.
    private var baseActiveMs = 0L
    private var baseWallMs = 0L

    fun activeMillis(now: Long = System.currentTimeMillis()): Long =
        if (state == State.ACTIVE) baseActiveMs + (now - baseWallMs).coerceAtLeast(0L) else baseActiveMs

    fun begin(poolLengthM: Int) {
        session = SwimSession(poolLengthM, System.currentTimeMillis())
        autoLengths = false
        alertedDistanceM = 0
        baseActiveMs = 0L
        baseWallMs = System.currentTimeMillis()
        set(State.STARTING)
    }

    fun set(newState: State) {
        if (newState != state) {
            // Freeze or restart the clock at the moment of the switch.
            baseActiveMs = activeMillis()
            baseWallMs = System.currentTimeMillis()
            state = newState
            session?.onActiveTime(baseActiveMs)
        }
        notifyChanged()
    }

    /** The watch told us the exact swim time at [wallMs]. */
    fun syncClock(activeMs: Long, wallMs: Long) {
        baseActiveMs = activeMs
        baseWallMs = wallMs
    }

    fun addLength() {
        if (state != State.ACTIVE) return
        session?.addManualLength(activeMillis())
        notifyChanged()
    }

    fun removeLength() {
        session?.removeLastLength()
        notifyChanged()
    }

    /** Rest at the wall, or push off again: the Garmin lap-button behaviour. */
    fun toggleRest() {
        val s = session ?: return
        if (state != State.ACTIVE) return
        if (s.isResting) s.endRest(activeMillis())
        // Without automatic counting, stopping at the wall is also the end of a length.
        else s.startRest(activeMillis(), finishLength = !autoLengths)
        notifyChanged()
    }

    fun clear() {
        session = null
        state = State.IDLE
        notifyChanged()
    }

    fun notifyChanged() {
        val distance = session?.distanceM ?: 0
        if (SwimFormat.crossedAlert(alertedDistanceM, distance, alertEveryM)) onAlert?.invoke()
        alertedDistanceM = distance
        onChange?.invoke()
    }
}

/**
 * Keeps the swim running while the screen is off. Uses the watch's Health Services pool-swim
 * mode when it has one, and otherwise falls back to a plain timer with manual length counting.
 */
class SwimService : Service() {

    private var client: ExerciseClient? = null
    private var usingHealthServices = false
    private var ending = false

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "exercise updates unavailable", throwable)
            startManual()
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = handle(update)

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getIntExtra(EXTRA_POOL, 50))
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_END -> end()
            // Restarted by the system with nothing to resume.
            else -> if (SwimTracker.session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(poolLengthM: Int) {
        if (SwimTracker.state == SwimTracker.State.ACTIVE || SwimTracker.state == SwimTracker.State.PAUSED) return
        ending = false
        SwimTracker.begin(poolLengthM)
        SwimTracker.onAlert = {
            // Three pulses, so it cannot be mistaken for the single buzz of a counted length.
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250, 150, 250), -1))
        }
        goForeground()

        val exercise = try {
            HealthServices.getClient(this).exerciseClient
        } catch (e: Exception) {
            null
        }
        if (exercise == null) {
            startManual()
            return
        }
        client = exercise
        exercise.getCapabilitiesAsync().then(
            { caps ->
                if (ExerciseType.SWIMMING_POOL !in caps.supportedExerciseTypes) {
                    startManual()
                } else {
                    val supported = caps.getExerciseTypeCapabilities(ExerciseType.SWIMMING_POOL).supportedDataTypes
                    startTracked(exercise, poolLengthM, supported)
                }
            },
            { startManual() },
        )
    }

    private fun startTracked(exercise: ExerciseClient, poolLengthM: Int, supported: Set<DataType<*, *>>) {
        val canReadBody = granted(Manifest.permission.BODY_SENSORS)
        val wanted = WANTED.filterTo(mutableSetOf()) { type ->
            type in supported && (canReadBody || !isHeartRate(type))
        }
        val config = ExerciseConfig.builder(ExerciseType.SWIMMING_POOL)
            .setDataTypes(wanted)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .setSwimmingPoolLengthMeters(poolLengthM.toFloat())
            .build()
        exercise.setUpdateCallback(callback)
        exercise.startExerciseAsync(config).then(
            {
                usingHealthServices = true
                SwimTracker.autoLengths = DataType.DISTANCE_TOTAL in wanted || DataType.SWIMMING_LAP_COUNT in wanted
                useDistanceForLengths = DataType.DISTANCE_TOTAL in wanted
                if (SwimTracker.state == SwimTracker.State.STARTING) SwimTracker.set(SwimTracker.State.ACTIVE)
            },
            { error ->
                Log.w(TAG, "could not start pool swim", error)
                startManual()
            },
        )
    }

    /** No sensor support: time the swim and let the swimmer count lengths with the button. */
    private fun startManual() {
        usingHealthServices = false
        SwimTracker.autoLengths = false
        if (SwimTracker.state == SwimTracker.State.STARTING) SwimTracker.set(SwimTracker.State.ACTIVE)
    }

    private var useDistanceForLengths = false
    private var lapCount = 0L

    private fun handle(update: ExerciseUpdate) {
        val session = SwimTracker.session ?: return
        if (!usingHealthServices || ending) return

        update.activeDurationCheckpoint?.let {
            SwimTracker.syncClock(it.activeDuration.toMillis(), it.time.toEpochMilli())
        }
        val info = update.exerciseStateInfo.state
        when {
            info.isEnded -> {
                // Ended from outside (another workout app took over the sensors).
                finish()
                return
            }
            info.isPaused -> SwimTracker.set(SwimTracker.State.PAUSED)
            info == ExerciseState.ACTIVE -> SwimTracker.set(SwimTracker.State.ACTIVE)
        }

        val metrics = update.latestMetrics
        metrics.getData(DataType.HEART_RATE_BPM).forEach { session.onHeartRate(it.value.roundToInt()) }
        metrics.getData(DataType.SWIMMING_STROKES_TOTAL)?.let { session.onStrokes(it.total) }
        metrics.getData(DataType.CALORIES_TOTAL)?.let { session.calories = it.total }

        val now = SwimTracker.activeMillis()
        session.onActiveTime(now)
        val pool = session.poolLengthM
        if (useDistanceForLengths) {
            // In pool mode the watch's distance moves in whole lengths.
            metrics.getData(DataType.DISTANCE_TOTAL)?.let {
                session.onSensorLengths(((it.total + pool * 0.1) / pool).toInt(), now)
            }
        } else {
            lapCount += metrics.getData(DataType.SWIMMING_LAP_COUNT).sumOf { it.value }
            session.onSensorLengths(lapCount.toInt(), now)
        }
        SwimTracker.notifyChanged()
    }

    private fun pause() {
        if (SwimTracker.state != SwimTracker.State.ACTIVE) return
        SwimTracker.set(SwimTracker.State.PAUSED)
        if (usingHealthServices) client?.pauseExerciseAsync()
    }

    private fun resume() {
        if (SwimTracker.state != SwimTracker.State.PAUSED) return
        SwimTracker.set(SwimTracker.State.ACTIVE)
        if (usingHealthServices) client?.resumeExerciseAsync()
    }

    private fun end() {
        if (SwimTracker.session == null) {
            stopSelf()
            return
        }
        ending = true
        if (usingHealthServices) client?.endExerciseAsync()
        finish()
    }

    private fun finish() {
        SwimTracker.set(SwimTracker.State.ENDED)
        try {
            client?.clearUpdateCallbackAsync(callback)
        } catch (e: Exception) {
            Log.w(TAG, "clearing callback failed", e)
        }
        usingHealthServices = false
        SwimTracker.onAlert = null
        lapCount = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SwimActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_swim)
            .setContentTitle(getString(R.string.notif_title))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(1, notification)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isHeartRate(type: DataType<*, *>) = type == DataType.HEART_RATE_BPM

    /** Runs [onDone] or [onError] on the main thread when the future settles. */
    private fun <T> ListenableFuture<T>.then(onDone: (T) -> Unit, onError: (Throwable) -> Unit) {
        addListener({
            val result = try {
                Result.success(get())
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(onDone, onError)
        }, ContextCompat.getMainExecutor(this@SwimService))
    }

    companion object {
        private const val TAG = "SwimService"
        private const val CHANNEL = "swim"
        const val ACTION_START = "com.remote.swim.START"
        const val ACTION_PAUSE = "com.remote.swim.PAUSE"
        const val ACTION_RESUME = "com.remote.swim.RESUME"
        const val ACTION_END = "com.remote.swim.END"
        const val EXTRA_POOL = "pool"

        private val WANTED: Set<DataType<*, *>> = setOf(
            DataType.DISTANCE_TOTAL,
            DataType.SWIMMING_LAP_COUNT,
            DataType.SWIMMING_STROKES_TOTAL,
            DataType.HEART_RATE_BPM,
            DataType.CALORIES_TOTAL,
        )

        fun send(context: Context, action: String, pool: Int = 0) {
            val intent = Intent(context, SwimService::class.java).setAction(action).putExtra(EXTRA_POOL, pool)
            if (action == ACTION_START) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
