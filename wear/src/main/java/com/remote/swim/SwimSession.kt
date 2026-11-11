package com.remote.swim

import java.util.Locale
import kotlin.math.roundToInt

/** One pool length: how long it took and, when the watch counts strokes, how many. */
class SwimLength(val number: Int, val millis: Long, val strokes: Int?) {
    /** Seconds plus strokes for the length; lower means more efficient. */
    val swolf: Int? get() = strokes?.let { (millis / 1000.0).roundToInt() + it }
}

/**
 * Everything measured in one swim. Distance is always lengths x pool length, so it is exact
 * as long as the length count is right. Plain Kotlin: no Android types, so it is unit tested.
 */
class SwimSession(val poolLengthM: Int, val startedAtEpochMs: Long = 0L) {

    private val done = mutableListOf<SwimLength>()
    val lengths: List<SwimLength> get() = done

    /** Swim time so far, pauses excluded. */
    var activeMillis = 0L
        private set

    var strokesTotal: Long? = null
        private set
    var heartRate: Int? = null
        private set
    var maxHeartRate: Int? = null
        private set
    var calories: Double? = null

    private var sensorLengths = 0
    private var lastLengthEndMillis = 0L
    private var strokesAtLastLength = 0L
    private var hrSum = 0L
    private var hrCount = 0

    val distanceM: Int get() = done.size * poolLengthM

    val avgHeartRate: Int? get() = if (hrCount == 0) null else (hrSum.toDouble() / hrCount).roundToInt()

    /** Milliseconds per 100 m over the whole swim, rests at the wall included. */
    val paceMillisPer100: Long?
        get() = if (distanceM == 0) null else lastLengthEndMillis * 100 / distanceM

    val lastLength: SwimLength? get() = done.lastOrNull()
    val bestLength: SwimLength? get() = done.minByOrNull { it.millis }

    val avgStrokesPerLength: Double?
        get() {
            val counted = done.mapNotNull { it.strokes }
            return if (counted.isEmpty()) null else counted.average()
        }

    val avgSwolf: Int?
        get() {
            val values = done.mapNotNull { it.swolf }
            return if (values.isEmpty()) null else values.average().roundToInt()
        }

    fun onActiveTime(millis: Long) {
        if (millis > activeMillis) activeMillis = millis
    }

    fun onHeartRate(bpm: Int) {
        if (bpm <= 0) return
        heartRate = bpm
        hrSum += bpm
        hrCount++
        if (bpm > (maxHeartRate ?: 0)) maxHeartRate = bpm
    }

    fun onStrokes(total: Long) {
        if (total >= (strokesTotal ?: 0L)) strokesTotal = total
    }

    /** The watch reported its running total of lengths; add whatever is new. */
    fun onSensorLengths(total: Int, atActiveMillis: Long) {
        val fresh = total - sensorLengths
        if (fresh <= 0) return
        sensorLengths = total
        addLengths(fresh, atActiveMillis)
    }

    /** The swimmer touched the wall and pressed the button. */
    fun addManualLength(atActiveMillis: Long) = addLengths(1, atActiveMillis)

    /** Undo a miscounted length; its time and strokes go back to the length in progress. */
    fun removeLastLength() {
        val last = done.removeLastOrNull() ?: return
        lastLengthEndMillis -= last.millis
        strokesAtLastLength -= last.strokes ?: 0
    }

    private fun addLengths(count: Int, atActiveMillis: Long) {
        onActiveTime(atActiveMillis)
        val elapsed = (activeMillis - lastLengthEndMillis).coerceAtLeast(0L)
        val strokes = strokesTotal?.let { (it - strokesAtLastLength).coerceAtLeast(0L) }
        // Several lengths arriving at once share the time and strokes evenly.
        for (i in 1..count) {
            val millis = elapsed * i / count - elapsed * (i - 1) / count
            val part = strokes?.let { (it * i / count - it * (i - 1) / count).toInt() }
            done += SwimLength(done.size + 1, millis, part)
        }
        lastLengthEndMillis += elapsed
        strokesAtLastLength += strokes ?: 0L
    }
}

object SwimFormat {

    /** 75000 -> "1:15", 3723000 -> "1:02:03". */
    fun duration(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Length times keep tenths: 48300 -> "0:48.3". */
    fun split(millis: Long): String {
        val tenths = (millis + 50) / 100
        return String.format(Locale.US, "%d:%02d.%d", tenths / 600, tenths % 600 / 10, tenths % 10)
    }

    fun pace(millisPer100: Long?): String = millisPer100?.let { duration(it + 500) } ?: "--"

    /** The full report shown after the swim and kept in the history. */
    fun summary(s: SwimSession): String = buildString {
        line("المسافة", "${s.distanceM} م")
        line("الوقت", duration(s.activeMillis))
        line("الأطوال", "${s.lengths.size} × ${s.poolLengthM} م")
        line("المعدل / 100م", pace(s.paceMillisPer100))
        s.bestLength?.let { line("أسرع طول", "${split(it.millis)} (طول ${it.number})") }
        s.strokesTotal?.let { line("مجموع الضربات", it.toString()) }
        s.avgStrokesPerLength?.let { line("ضربات / طول", String.format(Locale.US, "%.1f", it)) }
        s.avgSwolf?.let { line("SWOLF المتوسط", it.toString()) }
        s.avgHeartRate?.let { line("النبض المتوسط", it.toString()) }
        s.maxHeartRate?.let { line("أقصى نبض", it.toString()) }
        s.calories?.let { line("السعرات", it.roundToInt().toString()) }
        if (s.lengths.isNotEmpty()) {
            append("\nالأطوال:\n")
            for (l in s.lengths) {
                append("${l.number}. ${split(l.millis)}")
                l.strokes?.let { append(" · $it ضربة") }
                l.swolf?.let { append(" · SWOLF $it") }
                append('\n')
            }
        }
    }

    private fun StringBuilder.line(label: String, value: String) {
        append(label).append(": ").append(value).append('\n')
    }
}
