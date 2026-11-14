package com.remote.swim

import kotlin.math.abs

/**
 * Notices the wrist going under water from the barometer alone: every centimetre of water adds
 * about 1 hPa, far more than weather, stairs or a lift can change in a few seconds.
 * Plain Kotlin, so it is unit tested.
 */
class WaterDetector {
    private var baseline: Double? = null
    private var firstMs = 0L
    private var lastMs = 0L
    private var lastWetMs = 0L
    private var wetMs = 0L

    /** Feed one barometer reading; returns true once the wrist has been under water long enough. */
    fun onPressure(hPa: Double, timeMs: Long): Boolean {
        val base = baseline
        if (base == null) {
            baseline = hPa
            firstMs = timeMs
            lastMs = timeMs
            return false
        }
        val dt = (timeMs - lastMs).coerceIn(0L, MAX_GAP_MS)
        lastMs = timeMs
        when {
            // Lower than the air reading: we were armed with the wrist already wet. Start over.
            hPa < base - DRIFT_HPA -> {
                baseline = hPa
                wetMs = 0L
            }
            hPa > base + WET_HPA -> {
                wetMs += dt
                lastWetMs = timeMs
            }
            else -> {
                // Follow slow weather drift, but never a reading that may be shallow water.
                if (abs(hPa - base) <= DRIFT_HPA) baseline = base + (hPa - base) * 0.05
                if (timeMs - lastWetMs > FORGET_MS) wetMs = 0L
            }
        }
        return timeMs - firstMs >= WARMUP_MS && wetMs >= WET_NEEDED_MS
    }

    companion object {
        /** About 8 cm under the surface. */
        const val WET_HPA = 8.0
        const val DRIFT_HPA = 2.0
        const val WET_NEEDED_MS = 1_500L
        const val FORGET_MS = 4_000L
        const val WARMUP_MS = 1_000L
        const val MAX_GAP_MS = 1_000L
    }
}
