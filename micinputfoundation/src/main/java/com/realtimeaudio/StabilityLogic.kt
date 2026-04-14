package com.realtimeaudio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class StabilityStatus(
    val isSilence: Boolean,
    val isStable: Boolean,
    val requiredWindowSec: Double
)

class StabilityLogic(
    private val maxCentsVariation: Double = TunerConfig.STABLE_MAX_CENTS_VARIATION
) {
    private var candidateMidi: Int? = null
    private var windowStartSec: Double = 0.0
    private var minCents: Double = 0.0
    private var maxCents: Double = 0.0

    fun reset() {
        candidateMidi = null
        windowStartSec = 0.0
        minCents = 0.0
        maxCents = 0.0
    }

    fun update(
        timestampSec: Double,
        noteMidi: Int?,
        centsOffset: Double?,
        isSilence: Boolean,
        requiredStableWindowSec: Double
    ): StabilityStatus {
        if (isSilence) {
            reset()
            return StabilityStatus(isSilence = true, isStable = false, requiredWindowSec = requiredStableWindowSec)
        }

        if (noteMidi == null || centsOffset == null) {
            reset()
            return StabilityStatus(isSilence = false, isStable = false, requiredWindowSec = requiredStableWindowSec)
        }

        if (abs(centsOffset) > 50.0) {
            reset()
            return StabilityStatus(isSilence = false, isStable = false, requiredWindowSec = requiredStableWindowSec)
        }

        val currentCandidate = candidateMidi
        if (currentCandidate == null || currentCandidate != noteMidi) {
            candidateMidi = noteMidi
            windowStartSec = timestampSec
            minCents = centsOffset
            maxCents = centsOffset
            return StabilityStatus(isSilence = false, isStable = false, requiredWindowSec = requiredStableWindowSec)
        }

        minCents = min(minCents, centsOffset)
        maxCents = max(maxCents, centsOffset)

        val elapsed = timestampSec - windowStartSec
        val range = maxCents - minCents
        val stableWindow = requiredStableWindowSec.coerceIn(
            TunerConfig.STABLE_WINDOW_MIN_SEC,
            TunerConfig.STABLE_WINDOW_MAX_SEC
        )
        val isStable = elapsed >= stableWindow && range <= (maxCentsVariation * 2.0)

        // If it became wildly unstable within the window, restart the stability window to avoid
        // "sticking" to a note when the performer is changing pitch rapidly.
        if (!isStable && range > 25.0) {
            windowStartSec = timestampSec
            minCents = centsOffset
            maxCents = centsOffset
        }

        return StabilityStatus(isSilence = false, isStable = isStable, requiredWindowSec = stableWindow)
    }
}
