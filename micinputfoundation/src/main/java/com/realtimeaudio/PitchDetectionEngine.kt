package com.realtimeaudio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

class PitchDetectionEngine(val sampleRate: Int) {

    var calibrationA4Hz: Double = 440.0
        private set

    var smoothingAlpha: Double = 0.15
    var yinThreshold: Double = 0.15
    private val minFreqHz: Double = 50.0

    /**
     * Optional debug values updated each processed frame (no allocations).
     * Use these for troubleshooting (e.g. via debugger / on-screen dev panel).
     */
    @Volatile var debugLastTau: Int = 0
        private set
    @Volatile var debugLastCmndMin: Double = 1.0
        private set
    @Volatile var debugLastConfidence: Double = 0.0
        private set

    private val maxTau: Int = max(2, (sampleRate / minFreqHz).toInt())

    // We target enough samples to reliably cover maxTau (YIN uses N-tau; a common rule of thumb is N >= 2*maxTau).
    private val analysisSize: Int = clampInt(maxTau * 2, 1024, 8192)

    // Ring buffer for accumulating short frames without per-frame allocations.
    private val ring: FloatArray = FloatArray(analysisSize * 2)
    private var ringWrite: Int = 0
    private var ringCount: Int = 0

    // Scratch buffers reused each frame.
    private val analysisBuffer: FloatArray = FloatArray(analysisSize)
    private val difference: DoubleArray = DoubleArray(maxTau + 1)
    private val cmnd: DoubleArray = DoubleArray(maxTau + 1)

    private val stability = StabilityLogic()

    private var hasSmoothed: Boolean = false
    private var smoothedFrequencyHz: Double = 0.0

    fun setCalibrationA4(hz: Double) {
        calibrationA4Hz = hz.coerceIn(1.0, 20000.0)
    }

    fun reset() {
        ringWrite = 0
        ringCount = 0
        stability.reset()
        resetSmoothing()
    }

    fun processFrame(
        frame: FloatArray,
        timestampSec: Double,
        inputLevelDbfs: Double? = null
    ): PitchResult? {
        return processFloatFrame(
            frame = frame,
            count = frame.size,
            timestampSec = timestampSec,
            inputLevelDbfs = inputLevelDbfs
        )
    }

    private fun processFloatFrame(
        frame: FloatArray,
        count: Int,
        timestampSec: Double,
        inputLevelDbfs: Double?
    ): PitchResult? {
        val n = min(count, frame.size)
        appendToRing(frame, n)

        val levelDbfs = inputLevelDbfs ?: computeDbfs(frame, n)

        // Silence handling (must reset stability + smoothing).
        if (levelDbfs != null && levelDbfs < -50.0) {
            stability.reset()
            resetSmoothing()
            return PitchResult(
                detectedFrequency = 0.0,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = "silence"
            )
        }

        if (ringCount < analysisSize) {
            stability.reset()
            return PitchResult(
                detectedFrequency = 0.0,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = "unstable"
            )
        }

        readRingTailInto(analysisBuffer, analysisSize)

        val raw = detectPitchYin(analysisBuffer, analysisSize, levelDbfs)
        if (raw == null) {
            stability.reset()
            return PitchResult(
                detectedFrequency = 0.0,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = "unstable"
            )
        }

        val smoothedHz = smoothFrequency(raw.frequencyHz)
        val noteInfo = NoteMapper.map(smoothedHz, calibrationA4Hz)

        val status = stability.update(
            timestampSec = timestampSec,
            noteMidi = noteInfo.midi,
            centsOffset = noteInfo.centsOffset,
            inputLevelDbfs = levelDbfs
        )

        if (status.isSilence) {
            resetSmoothing()
            return PitchResult(
                detectedFrequency = 0.0,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = "silence"
            )
        }

        if (!status.isStable) {
            return PitchResult(
                detectedFrequency = 0.0,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = "unstable"
            )
        }

        val centsClamped = noteInfo.centsOffset.coerceIn(-50.0, 50.0)
        val tuningState = when {
            abs(centsClamped) <= 3.0 -> "inTune"
            abs(centsClamped) <= 10.0 -> "near"
            else -> "outOfTune"
        }

        return PitchResult(
            detectedFrequency = smoothedHz,
            noteName = noteInfo.noteName,
            octave = noteInfo.octave,
            centsOffset = centsClamped,
            confidence = raw.confidence.coerceIn(0.0, 1.0),
            inputLevel = levelDbfs,
            isStable = true,
            tuningState = tuningState
        )
    }

    fun processFrame(
        frame: ShortArray,
        frameCount: Int,
        timestampSec: Double,
        inputLevelDbfs: Double? = null
    ): PitchResult? {
        val count = min(frameCount, frame.size)
        // Convert without allocating: write directly into a temporary view of analysisBuffer-sized scratch.
        // Since we already have a ring buffer, we can stream-convert into it chunk by chunk.
        ensureShortConversionScratchCapacity(count)
        for (i in 0 until count) {
            shortToFloatScratch[i] = frame[i] / 32768.0f
        }
        return processFloatFrame(
            frame = shortToFloatScratch,
            count = count,
            timestampSec = timestampSec,
            inputLevelDbfs = inputLevelDbfs
        )
    }

    // ---- YIN implementation (difference + CMND + threshold + parabolic interpolation) ----

    internal fun detectPitchYin(samples: FloatArray, count: Int, inputLevelDbfs: Double?): PitchRaw? {
        val n = count
        if (n < 32) return null

        val localMaxTau = min(maxTau, n / 2)
        if (localMaxTau < 2) return null

        // Difference function.
        difference[0] = 0.0
        for (tau in 1..localMaxTau) {
            var sum = 0.0
            var i = 0
            val limit = n - tau
            while (i < limit) {
                val delta = samples[i] - samples[i + tau]
                sum += (delta * delta).toDouble()
                i++
            }
            difference[tau] = sum
        }

        // Cumulative mean normalized difference.
        cmnd[0] = 1.0
        var runningSum = 0.0
        for (tau in 1..localMaxTau) {
            runningSum += difference[tau]
            cmnd[tau] = if (runningSum == 0.0) 1.0 else difference[tau] * tau.toDouble() / runningSum
        }

        // Threshold search.
        val threshold = yinThreshold.coerceIn(0.05, 0.30)
        var tau = 2
        while (tau < localMaxTau && cmnd[tau] >= threshold) {
            tau++
        }
        if (tau >= localMaxTau) {
            debugLastTau = 0
            debugLastCmndMin = 1.0
            debugLastConfidence = 0.0
            return null
        }

        // Find local minimum around the first threshold crossing.
        while (tau + 1 < localMaxTau && cmnd[tau + 1] < cmnd[tau]) {
            tau++
        }

        val cmndMin = cmnd[tau]
        val refinedTau = parabolicInterpolateTau(cmnd, tau, localMaxTau)
        val freqHz = sampleRate.toDouble() / refinedTau

        // Confidence: how far below threshold we got (0..1).
        val confidence = ((threshold - cmndMin) / threshold).coerceIn(0.0, 1.0)

        debugLastTau = tau
        debugLastCmndMin = cmndMin
        debugLastConfidence = confidence

        if (!freqHz.isFinite() || freqHz <= 0.0) return null

        return PitchRaw(
            frequencyHz = freqHz,
            confidence = confidence,
            inputLevelDbfs = inputLevelDbfs
        )
    }

    private fun parabolicInterpolateTau(cmnd: DoubleArray, tau: Int, maxTauInclusive: Int): Double {
        val x0 = max(1, tau - 1)
        val x2 = min(maxTauInclusive, tau + 1)
        if (x0 == tau || x2 == tau) return tau.toDouble()

        val s0 = cmnd[x0]
        val s1 = cmnd[tau]
        val s2 = cmnd[x2]
        val denom = (2.0 * (s0 - 2.0 * s1 + s2))
        if (denom == 0.0) return tau.toDouble()

        val delta = (s0 - s2) / denom
        val refined = tau.toDouble() + delta
        return refined.coerceIn(1.0, maxTauInclusive.toDouble())
    }

    // ---- Smoothing ----

    private fun resetSmoothing() {
        hasSmoothed = false
        smoothedFrequencyHz = 0.0
    }

    private fun smoothFrequency(newHz: Double): Double {
        if (!hasSmoothed) {
            hasSmoothed = true
            smoothedFrequencyHz = newHz
            return newHz
        }

        val prev = smoothedFrequencyHz
        if (prev <= 0.0 || !prev.isFinite()) {
            smoothedFrequencyHz = newHz
            return newHz
        }

        // Reset on large discontinuities to avoid "lagging" behind note changes.
        val centsJump = abs(1200.0 * (ln(newHz / prev) / LN2))
        if (centsJump > 100.0) {
            smoothedFrequencyHz = newHz
            return newHz
        }

        val a = smoothingAlpha.coerceIn(0.0, 1.0)
        smoothedFrequencyHz = prev + (newHz - prev) * a
        return smoothedFrequencyHz
    }

    // ---- Ring buffer ----

    private fun appendToRing(samples: FloatArray, count: Int) {
        val n = min(count, samples.size)
        var i = 0
        while (i < n) {
            ring[ringWrite] = samples[i]
            ringWrite++
            if (ringWrite >= ring.size) ringWrite = 0
            ringCount = min(ringCount + 1, ring.size)
            i++
        }
    }

    private fun readRingTailInto(dst: FloatArray, count: Int) {
        val n = min(count, dst.size)
        val start = ((ringWrite - n) % ring.size + ring.size) % ring.size
        val firstPart = min(n, ring.size - start)
        java.lang.System.arraycopy(ring, start, dst, 0, firstPart)
        if (firstPart < n) {
            java.lang.System.arraycopy(ring, 0, dst, firstPart, n - firstPart)
        }
    }

    // ---- Level helpers ----

    private fun computeDbfs(samples: FloatArray, count: Int): Double? {
        if (count <= 0) return null
        var sumSquares = 0.0
        val n = min(count, samples.size)
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        val rms = kotlin.math.sqrt(sumSquares / n.toDouble())
        val eps = 1e-12
        return 20.0 * log10(max(eps, rms))
    }

    // ---- Short conversion scratch (reused) ----

    private var shortToFloatScratch: FloatArray = FloatArray(0)

    private fun ensureShortConversionScratchCapacity(required: Int) {
        if (shortToFloatScratch.size < required) {
            shortToFloatScratch = FloatArray(required)
        }
    }

    private fun clampInt(v: Int, lo: Int, hi: Int): Int = min(hi, max(lo, v))

    private companion object {
        private const val LN2 = 0.6931471805599453
    }
}
