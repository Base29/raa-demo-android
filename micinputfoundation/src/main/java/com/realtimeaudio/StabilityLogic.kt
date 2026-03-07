package com.realtimeaudio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class StabilityStatus(
    val isSilence: Boolean,
    val isStable: Boolean
)

class StabilityLogic(
    private val silenceThresholdDbfs: Double = -50.0,
    private val stableWindowSec: Double = 0.120,
    private val maxCentsVariation: Double = 3.0
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
        inputLevelDbfs: Double?
    ): StabilityStatus {
        if (inputLevelDbfs != null && inputLevelDbfs < silenceThresholdDbfs) {
            reset()
            return StabilityStatus(isSilence = true, isStable = false)
        }

        if (noteMidi == null || centsOffset == null) {
            reset()
            return StabilityStatus(isSilence = false, isStable = false)
        }

        if (abs(centsOffset) > 50.0) {
            reset()
            return StabilityStatus(isSilence = false, isStable = false)
        }

        val currentCandidate = candidateMidi
        if (currentCandidate == null || currentCandidate != noteMidi) {
            candidateMidi = noteMidi
            windowStartSec = timestampSec
            minCents = centsOffset
            maxCents = centsOffset
            return StabilityStatus(isSilence = false, isStable = false)
        }

        minCents = min(minCents, centsOffset)
        maxCents = max(maxCents, centsOffset)

        val elapsed = timestampSec - windowStartSec
        val range = maxCents - minCents
        val isStable = elapsed >= stableWindowSec && range <= (maxCentsVariation * 2.0)

        // If it became wildly unstable within the window, restart the stability window to avoid
        // "sticking" to a note when the performer is changing pitch rapidly.
        if (!isStable && range > 25.0) {
            windowStartSec = timestampSec
            minCents = centsOffset
            maxCents = centsOffset
        }

        return StabilityStatus(isSilence = false, isStable = isStable)
    }
}
