package com.realtimeaudio

data class PitchRaw(
    val frequencyHz: Double,
    val confidence: Double,
    val inputLevelDbfs: Double?,
    // Additional evidence for debugging and downstream logic (kept allocation-free).
    val cmndMin: Double = 1.0,
    val harmonicConsistency: Double = 0.0,
    val fftSupport: Double = 0.0,
    val chosenDivisor: Int = 1
)

enum class TuningState(val serialized: String) {
    SILENCE("silence"),
    UNSTABLE("unstable"),
    IN_TUNE("inTune"),
    NEAR("near"),
    OUT_OF_TUNE("outOfTune")
}

data class PitchDebugInfo(
    val rawFrequencyHz: Double?,
    val compositeConfidence: Double,
    val cmndMin: Double,
    val harmonicConsistency: Double,
    val fftSupport: Double,
    val chosenDivisor: Int,
    val inputLevelDbfs: Double?,
    val noiseFloorDbfs: Double,
    val smoothingAlpha: Double,
    val requiredStableWindowSec: Double,
    val lastTau: Int
)

data class PitchResult(
    val detectedFrequency: Double?,
    val noteName: String?,
    val octave: Int?,
    val centsOffset: Double?,
    val confidence: Double,
    val inputLevel: Double?,
    val isStable: Boolean,
    val tuningState: TuningState,
    val debug: PitchDebugInfo? = null
)

data class NoteInfo(
    val midi: Int,
    val noteName: String,
    val octave: Int,
    val centsOffset: Double
)
