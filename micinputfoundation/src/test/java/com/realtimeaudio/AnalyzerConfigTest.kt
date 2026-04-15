package com.realtimeaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerConfigTest {

    @Test
    fun pitchOnlyMode_disablesFftSize_inValidatedConfig() {
        val cfg = AnalyzerConfig(bufferSizeFrames = 1024, fftSize = 4096, enableFft = false).validated()
        assertEquals(0, cfg.fftSize)
        assertTrue(!cfg.enableFft)
    }

    @Test
    fun callbackRate_isClampedToSafeRange() {
        val low = AnalyzerConfig(callbackRateHz = 1).validated()
        val high = AnalyzerConfig(callbackRateHz = 500).validated()
        assertEquals(5, low.callbackRateHz)
        assertEquals(60, high.callbackRateHz)
    }
}

