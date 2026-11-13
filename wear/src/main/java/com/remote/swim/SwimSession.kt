package com.remote.swim

import java.util.Locale
import kotlin.math.roundToInt

/** One pool length: how long it took and, when the watch counts strokes, how many. */
class SwimLength(
    val number: Int,
    /** Which interval (set) of the swim this length belongs to, starting at 1. */
    val interval: Int,
    val millis: Long,
    val strokes: Int?,
    val poolLengthM: Int,
    /** Logged by hand as a drill (kick, one arm...), which the sensors cannot count. */
    val drill: Boolean = false,
) {
    /** Seconds plus strokes for the length; lower means more efficient. */
    val swolf: Int? get() = strokes?.let { (millis / 1000.0).roundToInt() + it }

    /** Strokes per minute. */
    val strokeRate: Double? get() = strokes?.takeIf { millis > 0 }?.let { it * 60_000.0 / millis }

    /** Metres travelled per stroke. */
    val metersPerStroke: Double? get() = strokes?.takeIf { it > 0 }?.let { poolLengthM.toDouble() / it }
}

/** A stretch of swimming between two rests at the wall, as on a Garmin interval screen. */
class SwimInterval(val number: Int, val lengths: List<SwimLength>, val restAfterMillis: Long) {
    val distanceM: Int get() = lengths.sumOf { it.poolLengthM }
    val millis: Long get() = lengths.sumOf { it.millis }
    val isDrill: Boolean get() = lengths.isNotEmpty() && lengths.all { it.drill }
    val paceMillisPer100: Long? get() = if (distanceM == 0) null else millis * 100 / distanceM
}

/**
 * Everything measured in one swim. Distance is always lengths x pool length, so it is exact
 * as long as the length count is right. Plain Kotlin: no Android types, so it is unit tested.
 */
class SwimSession(
    val poolLengthM: Int,
    val startedAtEpochMs: Long = 0L,
    /** A Critical Swim Speed test: 400 m all out, a long rest, then 200 m all out. */
    val cssTest: Boolean = false,
) {

    private val done = mutableListOf<SwimLength>()
    val lengths: List<SwimLength> get() = done

    /** Time since the start, rests included and pauses excluded. */
    var activeMillis = 0L
        private set

    var strokesTotal: Long? = null
        private set
    var heartRate: Int? = null
        private set
    var maxHeartRate: Int? = null
        private set
    var calories: Double? = null

    /** The interval being swum now; a rest at the wall closes it. */
    var interval = 1
        private set
    var isResting = false
        private set

    private var sensorLengths = 0
    private var lengthStartMillis = 0L
    private var strokesAtLastLength = 0L
    private var hrSum = 0L
    private var hrCount = 0
    private var restStartMillis = 0L
    private var drillStartMillis: Long? = null
    private var drillEndMillis: Long? = null

    /** A drill is being timed; the watch's own length counting is ignored meanwhile. */
    val isDrilling: Boolean get() = drillStartMillis != null && drillEndMillis == null

    /** The drill was stopped and waits for its distance to be entered. */
    val drillAwaitingDistance: Boolean get() = drillEndMillis != null

    fun currentDrillMillis(atActiveMillis: Long): Long =
        drillStartMillis?.let { ((drillEndMillis ?: atActiveMillis) - it).coerceAtLeast(0L) } ?: 0L

    val drillDistanceM: Int get() = done.count { it.drill } * poolLengthM

    /** Rest taken after each interval; key 0 is rest before the first length. */
    private val rests = mutableMapOf<Int, Long>()

    val distanceM: Int get() = done.size * poolLengthM

    /** Time spent actually swimming: the sum of the length times. */
    val swimMillis: Long get() = done.sumOf { it.millis }

    /** Finished rests only; see [currentRestMillis] for the one in progress. */
    val restMillis: Long get() = rests.values.sum()

    fun currentRestMillis(atActiveMillis: Long): Long =
        if (isResting) (atActiveMillis - restStartMillis).coerceAtLeast(0L) else 0L

    val avgHeartRate: Int? get() = if (hrCount == 0) null else (hrSum.toDouble() / hrCount).roundToInt()

    /** Milliseconds per 100 m while swimming; rests between intervals are not counted. */
    val paceMillisPer100: Long?
        get() = if (distanceM == 0) null else swimMillis * 100 / distanceM

    val lastLength: SwimLength? get() = done.lastOrNull()
    /** Fastest swum length; drills and lengths with no time of their own do not compete. */
    val bestLength: SwimLength? get() = done.filter { !it.drill && it.millis > 0 }.minByOrNull { it.millis }

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

    /** Strokes per minute over every length that has a stroke count. */
    val avgStrokeRate: Double?
        get() {
            val counted = done.filter { it.strokes != null }
            val millis = counted.sumOf { it.millis }
            return if (millis == 0L) null else counted.sumOf { it.strokes!! } * 60_000.0 / millis
        }

    val avgMetersPerStroke: Double?
        get() {
            val counted = done.filter { it.strokes != null }
            val strokes = counted.sumOf { it.strokes!! }
            return if (strokes == 0) null else counted.size * poolLengthM.toDouble() / strokes
        }

    val intervals: List<SwimInterval>
        get() = done.groupBy { it.interval }.toSortedMap().map { (n, ls) -> SwimInterval(n, ls, rests[n] ?: 0L) }

    val currentInterval: SwimInterval
        get() = SwimInterval(interval, done.filter { it.interval == interval }, 0L)

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
        // Whatever the sensors think they saw during a drill is replaced by the logged distance.
        if (drillStartMillis != null) return
        addLengths(fresh, atActiveMillis)
    }

    /**
     * The swimmer touched the wall and pressed the button. A press too soon after the previous
     * length is a bounce or a stray touch, not a length, and is ignored; returns whether it counted.
     */
    fun addManualLength(atActiveMillis: Long): Boolean {
        if (drillStartMillis != null || isResting) return false
        if (atActiveMillis - lengthStartMillis < MIN_LENGTH_MILLIS) return false
        addLengths(1, atActiveMillis)
        return true
    }

    /** Undo a miscounted length; its time and strokes go back to the length in progress. */
    fun removeLastLength() {
        val last = done.removeLastOrNull() ?: return
        if (last.interval == interval && !isResting) lengthStartMillis -= last.millis
        strokesAtLastLength -= last.strokes ?: 0
    }

    /**
     * The swimmer stopped at the wall. With manual counting the length just swum has not been
     * counted yet, so [finishLength] counts it, unless the rest starts too soon to be a length.
     */
    fun startRest(atActiveMillis: Long, finishLength: Boolean) {
        if (isResting || drillStartMillis != null) return
        onActiveTime(atActiveMillis)
        if (finishLength && atActiveMillis - lengthStartMillis >= MIN_LENGTH_MILLIS) addLengths(1, atActiveMillis)
        isResting = true
        restStartMillis = atActiveMillis
    }

    /** Push off the wall: the rest is booked and the next interval starts now. */
    fun endRest(atActiveMillis: Long) {
        if (!isResting) return
        onActiveTime(atActiveMillis)
        val swamThisInterval = done.any { it.interval == interval }
        val key = if (swamThisInterval) interval else interval - 1
        rests[key] = (rests[key] ?: 0L) + currentRestMillis(atActiveMillis)
        if (swamThisInterval) interval++
        isResting = false
        lengthStartMillis = atActiveMillis
    }

    /** Start timing a drill from the wall; it always forms an interval of its own. */
    fun startDrill(atActiveMillis: Long) {
        if (drillStartMillis != null) return
        if (isResting) endRest(atActiveMillis)
        onActiveTime(atActiveMillis)
        if (done.any { it.interval == interval }) interval++
        drillStartMillis = atActiveMillis
    }

    /** Back at the wall: the drill's time is fixed, its distance is entered next. */
    fun stopDrill(atActiveMillis: Long) {
        if (!isDrilling) return
        onActiveTime(atActiveMillis)
        drillEndMillis = atActiveMillis
    }

    /** Log the stopped drill as [lengthCount] lengths (0 discards it); a rest starts where it ended. */
    fun saveDrill(lengthCount: Int) {
        val start = drillStartMillis ?: return
        val end = drillEndMillis ?: return
        val elapsed = end - start
        for (i in 1..lengthCount) {
            val millis = elapsed * i / lengthCount - elapsed * (i - 1) / lengthCount
            done += SwimLength(done.size + 1, interval, millis, null, poolLengthM, drill = true)
        }
        // Strokes made during the drill belong to no counted length.
        strokesAtLastLength = strokesTotal ?: 0L
        drillStartMillis = null
        drillEndMillis = null
        lengthStartMillis = end
        isResting = true
        restStartMillis = end
    }

    /** Distance of the current CSS test swim, or null when there is nothing left to swim. */
    val cssTargetM: Int?
        get() = when {
            !cssTest -> null
            interval == 1 -> CSS_LONG_M
            interval == 2 -> CSS_SHORT_M
            else -> null
        }

    /** True the moment a CSS test swim is complete and its rest should begin. */
    val cssTargetReached: Boolean
        get() = cssTargetM?.let { !isResting && currentInterval.distanceM >= it } ?: false

    /**
     * Critical Swim Speed as milliseconds per 100 m: (D400 - D200) / (T400 - T200), using the
     * distances actually swum so pools that do not divide 400 m still give a correct pace.
     */
    val cssMillisPer100: Long?
        get() {
            if (!cssTest) return null
            val swims = intervals.filter { !it.isDrill }
            if (swims.size < 2) return null
            val (long, short) = swims
            if (long.distanceM < CSS_LONG_M || short.distanceM < CSS_SHORT_M) return null
            val metres = long.distanceM - short.distanceM
            val millis = long.millis - short.millis
            return if (metres <= 0 || millis <= 0) null else millis * 100 / metres
        }

    private fun addLengths(count: Int, atActiveMillis: Long) {
        onActiveTime(atActiveMillis)
        // A length the watch reports during a rest ended when the rest began.
        val end = if (isResting) restStartMillis else atActiveMillis
        val elapsed = (end - lengthStartMillis).coerceAtLeast(0L)
        val strokes = strokesTotal?.let { (it - strokesAtLastLength).coerceAtLeast(0L) }
        // Several lengths arriving at once share the time and strokes evenly.
        for (i in 1..count) {
            val millis = elapsed * i / count - elapsed * (i - 1) / count
            val part = strokes?.let { (it * i / count - it * (i - 1) / count).toInt() }
            done += SwimLength(done.size + 1, interval, millis, part, poolLengthM)
        }
        lengthStartMillis += elapsed
        strokesAtLastLength += strokes ?: 0L
    }

    companion object {
        const val MIN_LENGTH_MILLIS = 5_000L
        const val CSS_LONG_M = 400
        const val CSS_SHORT_M = 200
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

    fun decimal(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

    /** True when swimming from [before] to [after] metres passes a multiple of [every]. */
    fun crossedAlert(before: Int, after: Int, every: Int): Boolean =
        every > 0 && after / every > before / every

    /** The full report shown after the swim and kept in the history. */
    fun summary(s: SwimSession): String = buildString {
        line("المسافة", "${s.distanceM} م")
        line("الوقت الكلي", duration(s.activeMillis))
        line("وقت السباحة", duration(s.swimMillis))
        if (s.restMillis > 0) line("وقت الراحة", duration(s.restMillis))
        line("الأطوال", "${s.lengths.size} × ${s.poolLengthM} م")
        line("المعدل / 100م", pace(s.paceMillisPer100))
        s.bestLength?.let { line("أسرع طول", "${split(it.millis)} (طول ${it.number})") }
        s.strokesTotal?.let { line("مجموع الضربات", it.toString()) }
        s.avgStrokesPerLength?.let { line("ضربات / طول", decimal(it)) }
        s.avgStrokeRate?.let { line("ضربات / دقيقة", decimal(it)) }
        s.avgMetersPerStroke?.let { line("متر / ضربة", String.format(Locale.US, "%.2f", it)) }
        s.avgSwolf?.let { line("SWOLF المتوسط", it.toString()) }
        s.avgHeartRate?.let { line("النبض المتوسط", it.toString()) }
        s.maxHeartRate?.let { line("أقصى نبض", it.toString()) }
        s.calories?.let { line("السعرات", it.roundToInt().toString()) }
        if (s.drillDistanceM > 0) line("مسافة التمارين (drills)", "${s.drillDistanceM} م")
        s.cssMillisPer100?.let { css ->
            append("\nاختبار CSS:\n")
            line("CSS (وتيرة العتبة Z3)", "${pace(css)}/100م")
            line("تحمل Z2", "${pace(css + 8_000)} – ${pace(css + 12_000)}")
            line("VO2max Z4", "${pace(css - 5_000)} – ${pace(css - 3_000)}")
        }
        val intervals = s.intervals
        if (intervals.size > 1) {
            append("\nالمجموعات:\n")
            for (i in intervals) {
                append("${i.number}. ${i.distanceM} م · ${duration(i.millis)} · ${pace(i.paceMillisPer100)}/100م")
                if (i.isDrill) append(" · تمرين")
                if (i.restAfterMillis > 0) append(" · راحة ${duration(i.restAfterMillis)}")
                append('\n')
            }
        }
        if (s.lengths.isNotEmpty()) {
            append("\nالأطوال:\n")
            for (l in s.lengths) {
                append("${l.number}. ${split(l.millis)}")
                if (l.drill) append(" · تمرين")
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
