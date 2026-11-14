package com.remote.swim

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterDetectorTest {

    /** Feeds [hPa] every 100 ms for [millis]; returns the time of the first detection, or null. */
    private fun WaterDetector.feed(from: Long, millis: Long, hPa: (Long) -> Double): Long? {
        var t = from
        while (t < from + millis) {
            if (onPressure(hPa(t), t)) return t
            t += 100
        }
        return null
    }

    @Test
    fun dryWristNeverTriggers() {
        val d = WaterDetector()
        // Ten minutes in air with slow weather drift and sensor noise.
        assertEquals(null, d.feed(0, 600_000) { t -> 1013.0 + t / 600_000.0 + if (t % 300 == 0L) 0.3 else -0.2 })
    }

    @Test
    fun walkingDownToThePoolDoesNotTrigger() {
        val d = WaterDetector()
        // Four floors down in a lift: about +1.5 hPa over 20 s.
        assertEquals(null, d.feed(0, 20_000) { t -> 1013.0 + 1.5 * t / 20_000 })
        assertEquals(null, d.feed(20_000, 60_000) { 1014.5 })
    }

    @Test
    fun handInWaterTriggersAfterAMoment() {
        val d = WaterDetector()
        assertEquals(null, d.feed(0, 5_000) { 1013.0 })
        // Wrist 30 cm under the surface.
        assertEquals(6_400L, d.feed(5_000, 10_000) { 1043.0 })
    }

    @Test
    fun aSplashIsNotEnough() {
        val d = WaterDetector()
        assertEquals(null, d.feed(0, 5_000) { 1013.0 })
        assertEquals(null, d.feed(5_000, 600) { 1040.0 }) // rinsing the hand
        assertEquals(null, d.feed(5_600, 30_000) { 1013.0 })
    }

    @Test
    fun swimmingStrokesAddUp() {
        val d = WaterDetector()
        assertEquals(null, d.feed(0, 3_000) { 1013.0 })
        // Wrist under water 0.9 s of every 1.5 s stroke cycle.
        val at = d.feed(3_000, 20_000) { t -> if ((t - 3_000) % 1_500 < 900) 1045.0 else 1013.0 }
        assertEquals(true, at != null && at < 7_000)
    }

    @Test
    fun armedUnderWaterWaitsForTheNextDip() {
        val d = WaterDetector()
        assertEquals(null, d.feed(0, 5_000) { 1040.0 }) // armed with the wrist already in the pool
        assertEquals(null, d.feed(5_000, 5_000) { 1013.0 }) // lifted out
        assertEquals(11_400L, d.feed(10_000, 10_000) { 1040.0 })
    }
}
