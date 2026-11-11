package com.remote.swim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwimSessionTest {

    @Test
    fun distanceIsLengthsTimesPool() {
        val s = SwimSession(50)
        s.addManualLength(50_000)
        s.addManualLength(102_000)
        s.addManualLength(155_000)
        assertEquals(150, s.distanceM)
        assertEquals(listOf(50_000L, 52_000L, 53_000L), s.lengths.map { it.millis })
        // 155 s for 150 m -> 103.3 s per 100 m.
        assertEquals(103_333L, s.paceMillisPer100)
        assertEquals("1:43", SwimFormat.pace(s.paceMillisPer100))
        assertEquals(1, s.bestLength!!.number)
    }

    @Test
    fun sensorLengthsOnlyAddWhatIsNew() {
        val s = SwimSession(25)
        s.onSensorLengths(1, 30_000)
        s.onSensorLengths(1, 40_000)
        s.onSensorLengths(2, 62_000)
        assertEquals(2, s.lengths.size)
        assertEquals(50, s.distanceM)
        assertEquals(32_000L, s.lengths[1].millis)
    }

    @Test
    fun lengthsReportedTogetherShareTimeAndStrokes() {
        val s = SwimSession(50)
        s.onStrokes(81)
        s.onSensorLengths(2, 100_000)
        assertEquals(listOf(50_000L, 50_000L), s.lengths.map { it.millis })
        assertEquals(listOf(40, 41), s.lengths.map { it.strokes })
        assertEquals(100, s.distanceM)
    }

    @Test
    fun swolfIsSecondsPlusStrokes() {
        val s = SwimSession(50)
        s.onStrokes(38)
        s.addManualLength(48_400)
        s.onStrokes(78)
        s.addManualLength(98_400)
        assertEquals(48 + 38, s.lengths[0].swolf)
        assertEquals(50 + 40, s.lengths[1].swolf)
        assertEquals(88, s.avgSwolf)
        assertEquals(39.0, s.avgStrokesPerLength!!, 0.001)
    }

    @Test
    fun noStrokesMeansNoSwolf() {
        val s = SwimSession(50)
        s.addManualLength(50_000)
        assertNull(s.lengths[0].swolf)
        assertNull(s.avgSwolf)
        assertNull(s.avgStrokesPerLength)
    }

    @Test
    fun removingALengthGivesItsTimeBack() {
        val s = SwimSession(50)
        s.onStrokes(40)
        s.addManualLength(50_000)
        s.onStrokes(60)
        s.addManualLength(70_000) // pressed by mistake mid-length
        s.removeLastLength()
        assertEquals(50, s.distanceM)
        s.onStrokes(82)
        s.addManualLength(104_000)
        assertEquals(54_000L, s.lengths[1].millis)
        assertEquals(42, s.lengths[1].strokes)
        s.removeLastLength()
        s.removeLastLength()
        s.removeLastLength() // nothing left: must not throw
        assertEquals(0, s.distanceM)
        assertNull(s.paceMillisPer100)
    }

    @Test
    fun heartRateStats() {
        val s = SwimSession(50)
        assertNull(s.avgHeartRate)
        s.onHeartRate(120)
        s.onHeartRate(0) // sensor lost contact
        s.onHeartRate(150)
        s.onHeartRate(141)
        assertEquals(141, s.heartRate)
        assertEquals(137, s.avgHeartRate)
        assertEquals(150, s.maxHeartRate)
    }

    @Test
    fun formatting() {
        assertEquals("1:15", SwimFormat.duration(75_000))
        assertEquals("1:02:03", SwimFormat.duration(3_723_000))
        assertEquals("0:48.3", SwimFormat.split(48_300))
        assertEquals("1:00.0", SwimFormat.split(59_960))
        assertEquals("--", SwimFormat.pace(null))
    }

    @Test
    fun summaryListsEveryLength() {
        val s = SwimSession(50)
        s.onStrokes(38)
        s.addManualLength(48_000)
        s.onHeartRate(130)
        s.onActiveTime(60_000)
        val text = SwimFormat.summary(s)
        assertEquals(true, text.contains("المسافة: 50 م"))
        assertEquals(true, text.contains("الوقت: 1:00"))
        assertEquals(true, text.contains("1. 0:48.0 · 38 ضربة · SWOLF 86"))
    }
}
