package com.realtimeaudio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMapperTest {

    @Test
    fun a4_440_mapsToA4_nearZeroCents() {
        val info = NoteMapper.map(freqHz = 440.0, a4Hz = 440.0)
        assertEquals("A", info.noteName)
        assertEquals(4, info.octave)
        assertTrue(abs(info.centsOffset) < 0.01)
    }

    @Test
    fun c4_26163_mapsToC4() {
        val info = NoteMapper.map(freqHz = 261.63, a4Hz = 440.0)
        assertEquals("C", info.noteName)
        assertEquals(4, info.octave)
        assertTrue(abs(info.centsOffset) < 5.0)
    }
}
