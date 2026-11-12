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
        assertEquals(true, text.contains("الوقت الكلي: 1:00"))
        assertEquals(true, text.contains("1. 0:48.0 · 38 ضربة · SWOLF 86"))
    }

    @Test
    fun restSplitsTheSwimIntoIntervals() {
        val s = SwimSession(50)
        s.addManualLength(50_000)
        // Manual counting: stopping at the wall counts the length just swum.
        s.startRest(102_000, finishLength = true)
        assertEquals(100, s.distanceM)
        assertEquals(20_000L, s.currentRestMillis(122_000))
        s.endRest(132_000)
        assertEquals(2, s.interval)
        s.addManualLength(180_000)
        assertEquals(48_000L, s.lengths[2].millis)

        val intervals = s.intervals
        assertEquals(listOf(100, 50), intervals.map { it.distanceM })
        assertEquals(30_000L, intervals[0].restAfterMillis)
        assertEquals(102_000L, intervals[0].paceMillisPer100)
        assertEquals(150_000L, s.swimMillis)
        assertEquals(30_000L, s.restMillis)
        // Pace ignores the 30 s at the wall: 150 s for 150 m.
        assertEquals(100_000L, s.paceMillisPer100)
        assertEquals(true, SwimFormat.summary(s).contains("1. 100 م · 1:42 · 1:42/100م · راحة 0:30"))
    }

    @Test
    fun restRightAfterALengthDoesNotCountAnother() {
        val s = SwimSession(50)
        s.addManualLength(50_000)
        s.startRest(52_000, finishLength = true)
        assertEquals(50, s.distanceM)
    }

    @Test
    fun lengthDetectedDuringRestEndsWhenTheRestBegan() {
        val s = SwimSession(50)
        s.onSensorLengths(1, 50_000)
        s.startRest(101_000, finishLength = false)
        s.onSensorLengths(2, 106_000) // the watch noticed the wall touch late
        s.endRest(121_000)
        assertEquals(51_000L, s.lengths[1].millis)
        assertEquals(1, s.lengths[1].interval)
        assertEquals(20_000L, s.restMillis)
        assertEquals(2, s.interval)
    }

    @Test
    fun restBeforeSwimmingDoesNotOpenANewInterval() {
        val s = SwimSession(50)
        s.startRest(1_000, finishLength = true)
        s.endRest(11_000)
        assertEquals(1, s.interval)
        s.addManualLength(61_000)
        assertEquals(50_000L, s.lengths[0].millis)
        assertEquals(10_000L, s.restMillis)
    }

    @Test
    fun strokeRateAndDistancePerStroke() {
        val s = SwimSession(50)
        s.onStrokes(40)
        s.addManualLength(60_000)
        s.onStrokes(90)
        s.addManualLength(120_000)
        assertEquals(40.0, s.lengths[0].strokeRate!!, 0.001)
        assertEquals(1.25, s.lengths[0].metersPerStroke!!, 0.001)
        assertEquals(45.0, s.avgStrokeRate!!, 0.001)
        assertEquals(100.0 / 90, s.avgMetersPerStroke!!, 0.001)
    }

    @Test
    fun doublePressCountsOnce() {
        val s = SwimSession(50)
        assertEquals(true, s.addManualLength(50_000))
        assertEquals(false, s.addManualLength(51_200)) // the button bounced
        assertEquals(50, s.distanceM)
        assertEquals(true, s.addManualLength(101_000))
        assertEquals(51_000L, s.lengths[1].millis)
    }

    @Test
    fun drillIsLoggedByHandAsItsOwnInterval() {
        val s = SwimSession(50)
        s.onStrokes(40)
        s.onSensorLengths(1, 50_000)
        s.startDrill(60_000)
        s.onStrokes(55)
        s.onSensorLengths(2, 100_000) // a kick length the sensors half noticed
        assertEquals(false, s.addManualLength(110_000))
        assertEquals(50, s.distanceM)
        assertEquals(70_000L, s.currentDrillMillis(130_000))
        s.stopDrill(180_000)
        assertEquals(true, s.drillAwaitingDistance)
        s.saveDrill(2)

        assertEquals(150, s.distanceM)
        assertEquals(100, s.drillDistanceM)
        assertEquals(listOf(60_000L, 60_000L), s.lengths.drop(1).map { it.millis })
        assertNull(s.lengths[1].swolf)
        assertEquals(listOf(false, true), s.intervals.map { it.isDrill })
        // Back at the wall the rest runs from the end of the drill.
        assertEquals(true, s.isResting)
        assertEquals(10_000L, s.currentRestMillis(190_000))
        s.endRest(200_000)
        s.onStrokes(97)
        s.onSensorLengths(3, 250_000)
        assertEquals(3, s.lengths[3].interval)
        assertEquals(50_000L, s.lengths[3].millis)
        assertEquals(42, s.lengths[3].strokes) // drill strokes are not charged to this length
        assertEquals(true, SwimFormat.summary(s).contains("مسافة التمارين (drills): 100 م"))
    }

    @Test
    fun discardedDrillAddsNothing() {
        val s = SwimSession(50)
        s.startDrill(10_000)
        s.stopDrill(70_000)
        s.saveDrill(0)
        assertEquals(0, s.distanceM)
        assertEquals(false, s.drillAwaitingDistance)
        s.endRest(80_000)
        assertEquals(1, s.interval)
    }

    @Test
    fun cssTestGivesThresholdPace() {
        val s = SwimSession(50, cssTest = true)
        assertEquals(400, s.cssTargetM)
        // 400 m in 6:40.
        for (i in 1..8) {
            assertEquals(false, s.cssTargetReached)
            s.addManualLength(i * 50_000L)
        }
        assertEquals(true, s.cssTargetReached)
        assertNull(s.cssMillisPer100)
        s.startRest(400_000, finishLength = false)
        s.endRest(1_000_000)
        assertEquals(200, s.cssTargetM)
        // 200 m in 3:10.
        for (i in 1..4) s.addManualLength(1_000_000 + i * 47_500L)
        assertEquals(true, s.cssTargetReached)
        s.startRest(1_190_000, finishLength = false)
        // (400 - 200) m in (400 - 190) s -> 1:45 per 100 m.
        assertEquals(105_000L, s.cssMillisPer100)
        assertNull(s.cssTargetM.takeIf { s.interval > 2 })
        val text = SwimFormat.summary(s)
        assertEquals(true, text.contains("CSS (وتيرة العتبة Z3): 1:45/100م"))
        assertEquals(true, text.contains("تحمل Z2: 1:53 – 1:57"))
        assertEquals(true, text.contains("VO2max Z4: 1:40 – 1:42"))
    }

    @Test
    fun ordinarySwimHasNoCss() {
        val s = SwimSession(50)
        for (i in 1..8) s.addManualLength(i * 50_000L)
        s.startRest(400_000, finishLength = false)
        s.endRest(500_000)
        for (i in 1..4) s.addManualLength(500_000 + i * 47_500L)
        assertNull(s.cssMillisPer100)
        assertNull(s.cssTargetM)
        assertEquals(false, s.cssTargetReached)
    }

    @Test
    fun distanceAlerts() {
        assertEquals(true, SwimFormat.crossedAlert(50, 100, 100))
        assertEquals(false, SwimFormat.crossedAlert(100, 150, 100))
        assertEquals(true, SwimFormat.crossedAlert(150, 250, 200))
        assertEquals(false, SwimFormat.crossedAlert(50, 100, 0))
    }
}
