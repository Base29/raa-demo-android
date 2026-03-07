package com.realtimeaudio

data class PitchRaw(
    val frequencyHz: Double,
    val confidence: Double,
    val inputLevelDbfs: Double?
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
