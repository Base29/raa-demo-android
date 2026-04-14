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

data class PitchResult(
    val detectedFrequency: Double,
    val noteName: String?,
    val octave: Int?,
    val centsOffset: Double?,
    val confidence: Double,
    val inputLevel: Double?,
    val isStable: Boolean,
    val tuningState: String // "inTune" | "near" | "outOfTune" | "unstable" | "silence"
)

data class NoteInfo(
    val midi: Int,
    val noteName: String,
    val octave: Int,
    val centsOffset: Double
)
