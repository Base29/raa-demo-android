package com.realtimeaudio

import kotlin.math.ln
import kotlin.math.roundToInt

object NoteMapper {
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private const val LN2 = 0.6931471805599453

    fun map(freqHz: Double, a4Hz: Double): NoteInfo {
        val safeFreq = if (freqHz > 0.0) freqHz else 1e-12
        val safeA4 = if (a4Hz > 0.0) a4Hz else 440.0

        val midi = 69.0 + 12.0 * (ln(safeFreq / safeA4) / LN2)
        val nearest = midi.roundToInt()
        val cents = 100.0 * (midi - nearest.toDouble())

        val noteIndex = ((nearest % 12) + 12) % 12
        val noteName = NOTE_NAMES[noteIndex]
        val octave = (nearest / 12) - 1

        return NoteInfo(
            midi = nearest,
            noteName = noteName,
            octave = octave,
            centsOffset = cents
        )
    }
}
