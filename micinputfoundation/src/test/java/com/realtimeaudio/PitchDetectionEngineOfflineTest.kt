package com.realtimeaudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchDetectionEngineOfflineTest {

    @Test
    fun detectsE2_A2_A4_sineWaves() {
        val sampleRate = 48_000
        val engine = PitchDetectionEngine(sampleRate = sampleRate).apply {
            setCalibrationA4(440.0)
            smoothingAlpha = 0.15
            yinThreshold = 0.15
        }

        fun detect(freq: Double): Double {
            val frameSize = 2048
            val frame = FloatArray(frameSize)
            val amp = 0.6
            var t = 0.0
            val dt = 1.0 / sampleRate.toDouble()

            var lastRaw: PitchRaw? = null
            // Feed enough frames to cover the analysis window and give a stable estimate.
            for (k in 0 until 12) {
                for (i in 0 until frameSize) {
                    frame[i] = (amp * sin(2.0 * PI * freq * t)).toFloat()
                    t += dt
                }
                val raw = engine.detectPitchYin(frame, frame.size, inputLevelDbfs = -10.0)
                if (raw != null) lastRaw = raw
            }

            val r = lastRaw
            assertNotNull("Expected non-null pitch for $freq Hz", r)
            return r!!.frequencyHz
        }

        val e2 = detect(82.41)
        val a2 = detect(110.0)
        val a4 = detect(440.0)

        assertTrue(abs(e2 - 82.41) < 2.0)
        assertTrue(abs(a2 - 110.0) < 2.0)
        assertTrue(abs(a4 - 440.0) < 3.0)
    }
}

