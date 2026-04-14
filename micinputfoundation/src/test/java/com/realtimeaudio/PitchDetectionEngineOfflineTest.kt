package com.realtimeaudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
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

    @Test
    fun detectsLowE1_around41Hz() {
        val sampleRate = 48_000
        val engine = PitchDetectionEngine(sampleRate = sampleRate).apply {
            setCalibrationA4(440.0)
        }

        val freq = 41.20 // E1-ish
        val frameSize = 2048
        val frame = FloatArray(frameSize)
        val amp = 0.75
        var t = 0.0
        val dt = 1.0 / sampleRate.toDouble()

        var lastRaw: PitchRaw? = null
        for (k in 0 until 20) {
            for (i in 0 until frameSize) {
                frame[i] = (amp * sin(2.0 * PI * freq * t)).toFloat()
                t += dt
            }
            val raw = engine.detectPitchYin(frame, frame.size, inputLevelDbfs = -8.0)
            if (raw != null) lastRaw = raw
        }

        assertNotNull("Expected non-null pitch for $freq Hz", lastRaw)
        assertTrue(abs(lastRaw!!.frequencyHz - freq) < 2.0)
    }

    @Test
    fun correctsOctaveError_whenSecondHarmonicDominates() {
        val sampleRate = 48_000
        val engine = PitchDetectionEngine(sampleRate = sampleRate).apply {
            setCalibrationA4(440.0)
            yinThreshold = 0.15
        }

        val fundamental = 82.41
        val harmonic2 = fundamental * 2.0
        val frameSize = 4096
        val frame = FloatArray(frameSize)
        var t = 0.0
        val dt = 1.0 / sampleRate.toDouble()

        // Construct signal where 2nd harmonic is much stronger than fundamental.
        val a1 = 0.15
        val a2 = 0.80

        var verified: PitchRaw? = null
        for (k in 0 until 10) {
            for (i in 0 until frameSize) {
                val s = a1 * sin(2.0 * PI * fundamental * t) + a2 * sin(2.0 * PI * harmonic2 * t)
                frame[i] = s.toFloat()
                t += dt
            }
            // No FFT provided here; CMND-only subharmonic verification should still help.
            val raw = engine.detectPitchYin(frame, frame.size, inputLevelDbfs = -8.0)
            if (raw != null) verified = raw
        }

        assertNotNull("Expected pitch", verified)
        assertTrue(
            "Expected fundamental near $fundamental Hz, got ${verified!!.frequencyHz} (div=${verified!!.chosenDivisor})",
            abs(verified!!.frequencyHz - fundamental) < 4.0
        )
        assertTrue("Expected a divisor switch (2 or 3) in this scenario", verified!!.chosenDivisor != 1)
    }

    @Test
    fun dynamicNoiseFloor_marksNoisyRoomAsSilence_whenNoPitch() {
        val sampleRate = 48_000
        val engine = PitchDetectionEngine(sampleRate = sampleRate)

        val frameSize = 1024
        val frame = FloatArray(frameSize)

        // Simulate steady broadband noise around -45 dBFS RMS.
        val targetDb = -45.0
        val targetRms = kotlin.math.exp((targetDb / 20.0) * ln(10.0))
        val rnd = java.util.Random(123)

        var timestamp = 0.0
        for (k in 0 until 60) {
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val v = (rnd.nextDouble() * 2.0 - 1.0)
                frame[i] = v.toFloat()
                sumSq += v * v
            }
            val rms = kotlin.math.sqrt(sumSq / frameSize.toDouble())
            val gain = (targetRms / (rms + 1e-9))
            val g = gain.toFloat()
            for (i in 0 until frameSize) frame[i] = frame[i] * g

            val res = engine.processFrame(frame, timestampSec = timestamp, inputLevelDbfs = targetDb)
            timestamp += frameSize.toDouble() / sampleRate.toDouble()
            // After a short adaptation period, it should classify as silence (no stable note).
            if (k > 30) {
                assertTrue(res != null)
                assertTrue("Expected silence state in noisy room without pitch", res!!.tuningState == "silence" || res.tuningState == "unstable")
            }
        }
    }

    @Test
    fun stableDeadZone_suppressesTinyCentsJitter() {
        val sampleRate = 48_000
        val engine = PitchDetectionEngine(sampleRate = sampleRate)

        fun sineFrame(freq: Double, frameSize: Int, phaseStart: Double): Pair<FloatArray, Double> {
            val frame = FloatArray(frameSize)
            var t = phaseStart
            val dt = 1.0 / sampleRate.toDouble()
            for (i in 0 until frameSize) {
                frame[i] = (0.7 * sin(2.0 * PI * freq * t)).toFloat()
                t += dt
            }
            return frame to t
        }

        val baseHz = 440.0
        val frameSize = 2048
        var t = 0.0
        var ts = 0.0
        var lastStableCents: Double? = null

        // Warm up until stable.
        for (k in 0 until 25) {
            val (f, t2) = sineFrame(baseHz, frameSize, t)
            t = t2
            val res = engine.processFrame(f, ts, inputLevelDbfs = -10.0)
            ts += frameSize.toDouble() / sampleRate.toDouble()
            if (res?.isStable == true) lastStableCents = res.centsOffset
        }

        assertNotNull("Expected to reach stable", lastStableCents)

        // Apply tiny detune (~1 cent) oscillation; dead zone should keep cents steady when stable.
        val centToRatio = { cents: Double -> kotlin.math.exp((cents / 1200.0) * kotlin.math.ln(2.0)) }
        for (k in 0 until 10) {
            val cents = if (k % 2 == 0) 0.9 else -0.9
            val (f, t2) = sineFrame(baseHz * centToRatio(cents), frameSize, t)
            t = t2
            val res = engine.processFrame(f, ts, inputLevelDbfs = -10.0)
            ts += frameSize.toDouble() / sampleRate.toDouble()
            if (res?.isStable == true) {
                assertTrue(abs((res.centsOffset ?: 0.0) - (lastStableCents ?: 0.0)) < 0.5)
            }
        }
    }

    @Test
    fun adaptiveSmoothing_and_stabilityWindow_respondToVariance() {
        val sampleRate = 48_000
        val engine = PitchDetectionEngine(sampleRate = sampleRate)

        fun emit(freq: Double, frames: Int, startTs: Double): Double {
            val frameSize = 2048
            val frame = FloatArray(frameSize)
            var t = 0.0
            val dt = 1.0 / sampleRate.toDouble()
            var ts = startTs
            repeat(frames) {
                for (i in 0 until frameSize) {
                    frame[i] = (0.7 * sin(2.0 * PI * freq * t)).toFloat()
                    t += dt
                }
                engine.processFrame(frame, ts, inputLevelDbfs = -10.0)
                ts += frameSize.toDouble() / sampleRate.toDouble()
            }
            return ts
        }

        var ts = 0.0
        // Clean stable signal should allow lighter smoothing (higher alpha) + shorter window.
        ts = emit(110.0, frames = 20, startTs = ts)
        val alphaStable = engine.debugLastSmoothingAlpha
        val winStable = engine.debugLastRequiredStableWindowSec

        // Noisier / more variable signal (small random detunes) should push stronger smoothing + longer window.
        val rnd = java.util.Random(7)
        val frameSize = 2048
        val frame = FloatArray(frameSize)
        var t = 0.0
        val dt = 1.0 / sampleRate.toDouble()
        repeat(25) {
            val cents = (rnd.nextDouble() * 18.0) - 9.0 // ±9 cents
            val ratio = kotlin.math.exp((cents / 1200.0) * kotlin.math.ln(2.0))
            val fHz = 110.0 * ratio
            for (i in 0 until frameSize) {
                frame[i] = (0.7 * sin(2.0 * PI * fHz * t)).toFloat()
                t += dt
            }
            engine.processFrame(frame, ts, inputLevelDbfs = -10.0)
            ts += frameSize.toDouble() / sampleRate.toDouble()
        }

        val alphaNoisy = engine.debugLastSmoothingAlpha
        val winNoisy = engine.debugLastRequiredStableWindowSec

        assertTrue("Expected stronger smoothing (smaller alpha) when noisy", alphaNoisy <= alphaStable + 1e-9)
        assertTrue("Expected longer stability window when noisy", winNoisy >= winStable - 1e-9)
        assertTrue(winNoisy in TunerConfig.STABLE_WINDOW_MIN_SEC..TunerConfig.STABLE_WINDOW_MAX_SEC)
        assertTrue(alphaNoisy in 0.0..1.0)
    }
}

