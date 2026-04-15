package com.realtimeaudio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

class PitchDetectionEngine(val sampleRate: Int) {

    var calibrationA4Hz: Double = 440.0
        private set

    // Retained for backward-compat; smoothing is now adaptive but this serves as a baseline clamp.
    var smoothingAlpha: Double = 0.15

    // Exposed for tuning; default matches shared config.
    var yinThreshold: Double = TunerConfig.YIN_THRESHOLD

    private val minFreqHz: Double = TunerConfig.MIN_DETECTABLE_FREQUENCY_HZ

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
    @Volatile var debugLastCompositeConfidence: Double = 0.0
        private set
    @Volatile var debugLastNoiseFloorDbfs: Double = TunerConfig.NOISE_FLOOR_MIN_DBFS
        private set
    @Volatile var debugLastSmoothingAlpha: Double = 0.0
        private set
    @Volatile var debugLastRequiredStableWindowSec: Double = TunerConfig.STABLE_WINDOW_MAX_SEC
        private set
    @Volatile var debugLastChosenDivisor: Int = 1
        private set

    var debugEnabled: Boolean = false

    private val maxTau: Int = max(2, (sampleRate / minFreqHz).toInt())

    // We target enough samples to reliably cover maxTau (YIN uses N-tau; a common rule of thumb is N >= 2*maxTau).
    private val analysisSize: Int = clampInt(maxTau * 2, 1024, 16384)

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

    // Dynamic noise floor tracking (in dBFS).
    private var noiseFloorDbfs: Double = TunerConfig.NOISE_FLOOR_MIN_DBFS

    // Small cents history for adaptive logic (fixed size, no allocations).
    private val centsHistory: DoubleArray = DoubleArray(8)
    private var centsHistoryCount: Int = 0
    private var centsHistoryWrite: Int = 0

    // Stable-mode dead zone memory.
    private var lastStableMidi: Int? = null
    private var lastStableCents: Double = 0.0

    fun setCalibrationA4(hz: Double) {
        calibrationA4Hz = hz.coerceIn(1.0, 20000.0)
    }

    fun reset() {
        ringWrite = 0
        ringCount = 0
        ring.fill(0.0f)
        stability.reset()
        resetSmoothing()
        noiseFloorDbfs = TunerConfig.NOISE_FLOOR_MIN_DBFS
        centsHistory.fill(0.0)
        centsHistoryCount = 0
        centsHistoryWrite = 0
        lastStableMidi = null
        lastStableCents = 0.0
        debugLastTau = 0
        debugLastCmndMin = 1.0
        debugLastConfidence = 0.0
        debugLastCompositeConfidence = 0.0
        debugLastNoiseFloorDbfs = noiseFloorDbfs
        debugLastSmoothingAlpha = 0.0
        debugLastRequiredStableWindowSec = TunerConfig.STABLE_WINDOW_MAX_SEC
        debugLastChosenDivisor = 1
    }

    private fun resetForReacquisition() {
        // Keep noise floor (environment estimate) but clear pitch/stability history so reacquisition is clean.
        stability.reset()
        resetSmoothing()
        centsHistory.fill(0.0)
        centsHistoryCount = 0
        centsHistoryWrite = 0
        lastStableMidi = null
        lastStableCents = 0.0
        debugLastSmoothingAlpha = 0.0
        debugLastRequiredStableWindowSec = TunerConfig.STABLE_WINDOW_MAX_SEC
        debugLastChosenDivisor = 1
    }

    fun processFrame(
        frame: FloatArray,
        timestampSec: Double,
        inputLevelDbfs: Double? = null,
        // Optional FFT magnitudes for verification (bins = fftSize/2, linear magnitude).
        fftMagnitudes: FloatArray? = null,
        fftSize: Int? = null
    ): PitchResult? {
        return processFloatFrame(
            frame = frame,
            count = frame.size,
            timestampSec = timestampSec,
            inputLevelDbfs = inputLevelDbfs,
            fftMagnitudes = fftMagnitudes,
            fftSize = fftSize
        )
    }

    private fun processFloatFrame(
        frame: FloatArray,
        count: Int,
        timestampSec: Double,
        inputLevelDbfs: Double?,
        fftMagnitudes: FloatArray?,
        fftSize: Int?
    ): PitchResult? {
        val n = min(count, frame.size)
        appendToRing(frame, n)

        val levelDbfs = inputLevelDbfs ?: computeDbfs(frame, n)

        // Update dynamic noise floor when pitch is not reliable (done below) but we need
        // a provisional silence decision early to preserve current behavior.
        val silenceNow = isSilence(levelDbfs)
        if (silenceNow) {
            resetForReacquisition()
            return PitchResult(
                detectedFrequency = null,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = TuningState.SILENCE,
                debug = if (debugEnabled) buildDebug(raw = null, inputLevelDbfs = levelDbfs, rawFrequencyHz = null) else null
            )
        }

        if (ringCount < analysisSize) {
            resetForReacquisition()
            return PitchResult(
                detectedFrequency = null,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = TuningState.UNSTABLE,
                debug = if (debugEnabled) buildDebug(raw = null, inputLevelDbfs = levelDbfs, rawFrequencyHz = null) else null
            )
        }

        readRingTailInto(analysisBuffer, analysisSize)

        val raw = detectPitchYin(analysisBuffer, analysisSize, levelDbfs, fftMagnitudes, fftSize)
        if (raw == null) {
            updateNoiseFloor(levelDbfs)
            resetForReacquisition()
            return PitchResult(
                detectedFrequency = null,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = TuningState.UNSTABLE,
                debug = if (debugEnabled) buildDebug(raw = null, inputLevelDbfs = levelDbfs, rawFrequencyHz = null) else null
            )
        }

        val compositeConfidence = raw.confidence.coerceIn(0.0, 1.0)
        debugLastCompositeConfidence = compositeConfidence

        // Adaptive smoothing based on recent cents variance and confidence.
        val adaptiveAlpha = computeAdaptiveSmoothingAlpha(compositeConfidence)
        debugLastSmoothingAlpha = adaptiveAlpha

        val smoothedHz = smoothFrequency(raw.frequencyHz, adaptiveAlpha)
        var noteInfo = NoteMapper.map(smoothedHz, calibrationA4Hz)

        // Track cents history for adaptive decisions.
        recordCents(noteInfo.centsOffset)

        val requiredStableWindowSec = computeAdaptiveStableWindowSec(compositeConfidence)
        debugLastRequiredStableWindowSec = requiredStableWindowSec

        val status = stability.update(
            timestampSec = timestampSec,
            noteMidi = noteInfo.midi,
            centsOffset = noteInfo.centsOffset,
            isSilence = false,
            requiredStableWindowSec = requiredStableWindowSec
        )

        if (status.isSilence) {
            resetSmoothing()
            lastStableMidi = null
            return PitchResult(
                detectedFrequency = null,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = TuningState.SILENCE,
                debug = if (debugEnabled) buildDebug(raw = raw, inputLevelDbfs = levelDbfs, rawFrequencyHz = raw.frequencyHz) else null
            )
        }

        if (!status.isStable) {
            // While unstable, allow noise floor to adapt if confidence is poor.
            if (compositeConfidence < 0.35) updateNoiseFloor(levelDbfs)
            lastStableMidi = null
            return PitchResult(
                detectedFrequency = null,
                noteName = null,
                octave = null,
                centsOffset = null,
                confidence = 0.0,
                inputLevel = levelDbfs,
                isStable = false,
                tuningState = TuningState.UNSTABLE,
                debug = if (debugEnabled) buildDebug(raw = raw, inputLevelDbfs = levelDbfs, rawFrequencyHz = raw.frequencyHz) else null
            )
        }

        // Stable dead zone: suppress tiny jitter once stable.
        val prevStableMidi = lastStableMidi
        if (prevStableMidi != null && prevStableMidi == noteInfo.midi) {
            val delta = abs(noteInfo.centsOffset - lastStableCents)
            if (delta < TunerConfig.STABLE_DEAD_ZONE_CENTS) {
                noteInfo = noteInfo.copy(centsOffset = lastStableCents)
            } else {
                lastStableCents = noteInfo.centsOffset
            }
        } else {
            lastStableMidi = noteInfo.midi
            lastStableCents = noteInfo.centsOffset
        }

        val centsClamped = noteInfo.centsOffset.coerceIn(-50.0, 50.0)
        val tuningState = when {
            abs(centsClamped) <= 3.0 -> TuningState.IN_TUNE
            abs(centsClamped) <= 10.0 -> TuningState.NEAR
            else -> TuningState.OUT_OF_TUNE
        }

        return PitchResult(
            detectedFrequency = smoothedHz,
            noteName = noteInfo.noteName,
            octave = noteInfo.octave,
            centsOffset = centsClamped,
            confidence = compositeConfidence,
            inputLevel = levelDbfs,
            isStable = true,
            tuningState = tuningState,
            debug = if (debugEnabled) buildDebug(raw = raw, inputLevelDbfs = levelDbfs, rawFrequencyHz = raw.frequencyHz) else null
        )
    }

    private fun buildDebug(raw: PitchRaw?, inputLevelDbfs: Double?, rawFrequencyHz: Double?): PitchDebugInfo {
        return PitchDebugInfo(
            rawFrequencyHz = rawFrequencyHz,
            compositeConfidence = debugLastCompositeConfidence,
            cmndMin = raw?.cmndMin ?: debugLastCmndMin,
            harmonicConsistency = raw?.harmonicConsistency ?: 0.0,
            fftSupport = raw?.fftSupport ?: 0.0,
            chosenDivisor = raw?.chosenDivisor ?: debugLastChosenDivisor,
            inputLevelDbfs = inputLevelDbfs,
            noiseFloorDbfs = noiseFloorDbfs,
            smoothingAlpha = debugLastSmoothingAlpha,
            requiredStableWindowSec = debugLastRequiredStableWindowSec,
            lastTau = debugLastTau
        )
    }

    fun processFrame(
        frame: ShortArray,
        frameCount: Int,
        timestampSec: Double,
        inputLevelDbfs: Double? = null,
        fftMagnitudes: FloatArray? = null,
        fftSize: Int? = null
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
            inputLevelDbfs = inputLevelDbfs,
            fftMagnitudes = fftMagnitudes,
            fftSize = fftSize
        )
    }

    // ---- YIN implementation (difference + CMND + threshold + parabolic interpolation) ----

    internal fun detectPitchYin(
        samples: FloatArray,
        count: Int,
        inputLevelDbfs: Double?,
        fftMagnitudes: FloatArray? = null,
        fftSize: Int? = null
    ): PitchRaw? {
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
        val candidateFreqHz = sampleRate.toDouble() / refinedTau

        // Base (CMND) confidence: how far below threshold we got (0..1).
        val cmndConfidence = ((threshold - cmndMin) / threshold).coerceIn(0.0, 1.0)

        debugLastTau = tau
        debugLastCmndMin = cmndMin
        debugLastConfidence = cmndConfidence

        if (!candidateFreqHz.isFinite() || candidateFreqHz <= 0.0) return null

        // Anti-harmonic/subharmonic verification: consider candidate/2 and candidate/3 when evidence supports it.
        val verified = verifySubharmonics(
            candidateFreqHz = candidateFreqHz,
            refinedTau = refinedTau,
            localMaxTau = localMaxTau,
            cmnd = cmnd,
            cmndMin = cmndMin,
            fftMagnitudes = fftMagnitudes,
            fftSize = fftSize
        )

        val energyScore = computeEnergyScore(inputLevelDbfs)
        val fftSupport = verified.fftSupport
        val harmonicConsistency = verified.harmonicConsistency

        val composite = (
            TunerConfig.CONF_W_CMND * cmndConfidence +
                TunerConfig.CONF_W_ENERGY * energyScore +
                TunerConfig.CONF_W_HARMONIC * harmonicConsistency +
                TunerConfig.CONF_W_FFT * fftSupport
            ).coerceIn(0.0, 1.0)

        debugLastChosenDivisor = verified.chosenDivisor

        return PitchRaw(
            frequencyHz = verified.frequencyHz,
            confidence = composite,
            inputLevelDbfs = inputLevelDbfs,
            cmndMin = cmndMin,
            harmonicConsistency = harmonicConsistency,
            fftSupport = fftSupport,
            chosenDivisor = verified.chosenDivisor
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

    private fun smoothFrequency(newHz: Double, alpha: Double): Double {
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

        val a = alpha.coerceIn(0.0, 1.0)
        smoothedFrequencyHz = prev + (newHz - prev) * a
        return smoothedFrequencyHz
    }

    // ---- Adaptive smoothing / stability helpers ----

    private fun recordCents(cents: Double) {
        centsHistory[centsHistoryWrite] = cents
        centsHistoryWrite = (centsHistoryWrite + 1) % centsHistory.size
        if (centsHistoryCount < centsHistory.size) centsHistoryCount++
    }

    private fun computeRecentCentsVariance(): Double {
        val n = centsHistoryCount
        if (n <= 1) return 0.0
        var mean = 0.0
        var m2 = 0.0
        // Iterate in insertion order doesn't matter for variance.
        for (i in 0 until n) {
            val x = centsHistory[i]
            val delta = x - mean
            mean += delta / (i + 1).toDouble()
            m2 += delta * (x - mean)
        }
        return m2 / (n - 1).toDouble()
    }

    private fun computeAdaptiveSmoothingAlpha(confidence: Double): Double {
        val varCents = computeRecentCentsVariance()
        // Map variance (cents^2) to [0..1] "instability".
        val instability = (varCents / 36.0).coerceIn(0.0, 1.0) // ~6 cents std dev => 1.0
        val conf = confidence.coerceIn(0.0, 1.0)

        // Higher instability and lower confidence => stronger smoothing (smaller alpha).
        val target = TunerConfig.SMOOTHING_ALPHA_MAX -
            (TunerConfig.SMOOTHING_ALPHA_MAX - TunerConfig.SMOOTHING_ALPHA_MIN) * (0.65 * instability + 0.35 * (1.0 - conf))

        // Also respect any external clamp from the legacy smoothingAlpha knob.
        val legacy = smoothingAlpha.coerceIn(0.0, 1.0)
        return min(target, max(TunerConfig.SMOOTHING_ALPHA_MIN, legacy))
    }

    private fun computeAdaptiveStableWindowSec(confidence: Double): Double {
        val varCents = computeRecentCentsVariance()
        val instability = (varCents / 49.0).coerceIn(0.0, 1.0) // ~7 cents std dev => 1.0
        val confPenalty = (1.0 - confidence.coerceIn(0.0, 1.0))
        val t = (0.70 * instability + 0.30 * confPenalty).coerceIn(0.0, 1.0)
        return TunerConfig.STABLE_WINDOW_MIN_SEC + (TunerConfig.STABLE_WINDOW_MAX_SEC - TunerConfig.STABLE_WINDOW_MIN_SEC) * t
    }

    // ---- Silence / noise floor ----

    private fun updateNoiseFloor(levelDbfs: Double?) {
        if (levelDbfs == null || !levelDbfs.isFinite()) return
        // Only track downward/upward slowly to avoid "chasing" real signal.
        val alpha = TunerConfig.NOISE_FLOOR_ALPHA
        noiseFloorDbfs = (noiseFloorDbfs + (levelDbfs - noiseFloorDbfs) * alpha)
            .coerceAtLeast(TunerConfig.NOISE_FLOOR_MIN_DBFS)
        debugLastNoiseFloorDbfs = noiseFloorDbfs
    }

    private fun isSilence(levelDbfs: Double?): Boolean {
        if (levelDbfs == null || !levelDbfs.isFinite()) return false
        val adaptive = (noiseFloorDbfs + TunerConfig.SILENCE_MARGIN_DB)
            .coerceAtMost(TunerConfig.SILENCE_THRESHOLD_MAX_DBFS)
        return levelDbfs < adaptive
    }

    private fun computeEnergyScore(levelDbfs: Double?): Double {
        if (levelDbfs == null || !levelDbfs.isFinite()) return 0.0
        // Score is relative to adaptive silence threshold to stay robust in noisy rooms.
        val silence = (noiseFloorDbfs + TunerConfig.SILENCE_MARGIN_DB)
            .coerceAtMost(TunerConfig.SILENCE_THRESHOLD_MAX_DBFS)
        val hi = -12.0
        return ((levelDbfs - silence) / (hi - silence)).coerceIn(0.0, 1.0)
    }

    // ---- Harmonic / FFT verification ----

    private data class VerifiedPitch(
        val frequencyHz: Double,
        val harmonicConsistency: Double,
        val fftSupport: Double,
        val chosenDivisor: Int
    )

    private fun verifySubharmonics(
        candidateFreqHz: Double,
        refinedTau: Double,
        localMaxTau: Int,
        cmnd: DoubleArray,
        cmndMin: Double,
        fftMagnitudes: FloatArray?,
        fftSize: Int?
    ): VerifiedPitch {
        var bestFreq = candidateFreqHz
        var chosenDiv = 1

        val baseTau = refinedTau
        val tau2 = (baseTau * 2.0)
        val tau3 = (baseTau * 3.0)

        // CMND evidence for subharmonics: if CMND is also low at 2τ/3τ, candidate may be a harmonic.
        val cmndAt2 = cmndValueAtTau(cmnd, tau2, localMaxTau)
        val cmndAt3 = cmndValueAtTau(cmnd, tau3, localMaxTau)

        // Harmonic consistency: how plausible is the fundamental vs detected harmonic.
        // We do NOT always prefer lower; only switch if evidence improves enough.
        val baseScore = harmonicScore(cmndMin, cmndAt2, cmndAt3, preferDiv = 1)
        val div2Score = harmonicScore(cmndMin, cmndAt2, cmndAt3, preferDiv = 2)
        val div3Score = harmonicScore(cmndMin, cmndAt2, cmndAt3, preferDiv = 3)

        var harmonicConsistency = baseScore

        // FFT confirmation when available.
        val fftSupportBase = fftSupportFor(candidateFreqHz, fftMagnitudes, fftSize)
        val fftSupport2 = fftSupportFor(candidateFreqHz / 2.0, fftMagnitudes, fftSize)
        val fftSupport3 = fftSupportFor(candidateFreqHz / 3.0, fftMagnitudes, fftSize)

        var fftSupport = fftSupportBase

        // Decide switch using combined evidence.
        // Require: sub candidate has clearly better harmonic score AND has spectral support relative to base.
        val switchTo2 =
            (div2Score > baseScore + 0.12) &&
                (fftSupport2 >= 0.15) &&
                (fftSupport2 >= (fftSupportBase / TunerConfig.FFT_SUPPORT_MIN_RATIO))

        val switchTo3 =
            (div3Score > baseScore + 0.16) &&
                (fftSupport3 >= 0.15) &&
                (fftSupport3 >= (fftSupportBase / TunerConfig.FFT_SUPPORT_MIN_RATIO))

        when {
            switchTo3 && (!switchTo2 || div3Score > div2Score + 0.05) -> {
                bestFreq = candidateFreqHz / 3.0
                chosenDiv = 3
                harmonicConsistency = div3Score
                fftSupport = fftSupport3
            }
            switchTo2 -> {
                bestFreq = candidateFreqHz / 2.0
                chosenDiv = 2
                harmonicConsistency = div2Score
                fftSupport = fftSupport2
            }
        }

        return VerifiedPitch(
            frequencyHz = bestFreq,
            harmonicConsistency = harmonicConsistency.coerceIn(0.0, 1.0),
            fftSupport = fftSupport.coerceIn(0.0, 1.0),
            chosenDivisor = chosenDiv
        )
    }

    private fun cmndValueAtTau(cmnd: DoubleArray, tau: Double, localMaxTau: Int): Double {
        if (!tau.isFinite()) return 1.0
        val i = tau.toInt()
        if (i < 1 || i >= localMaxTau) return 1.0
        val frac = tau - i.toDouble()
        val a = cmnd[i]
        val b = cmnd[min(localMaxTau, i + 1)]
        return (a + (b - a) * frac).coerceIn(0.0, 1.0)
    }

    private fun harmonicScore(cmndMin: Double, cmndAt2: Double, cmndAt3: Double, preferDiv: Int): Double {
        // Lower CMND at a larger tau suggests true fundamental is lower.
        // Construct a normalized score that rewards consistent minima at the implied tau.
        val base = (1.0 - cmndMin).coerceIn(0.0, 1.0)
        return when (preferDiv) {
            2 -> (0.55 * base + 0.45 * (1.0 - cmndAt2).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
            3 -> (0.55 * base + 0.45 * (1.0 - cmndAt3).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
            else -> base
        }
    }

    private fun fftSupportFor(freqHz: Double, magnitudes: FloatArray?, fftSize: Int?): Double {
        if (magnitudes == null || fftSize == null || fftSize <= 0) return 0.0
        if (!freqHz.isFinite() || freqHz <= 0.0) return 0.0
        val bin = ((freqHz / sampleRate.toDouble()) * fftSize.toDouble()).toInt()
        val maxBin = magnitudes.size - 1
        if (bin <= 0 || bin >= maxBin) return 0.0

        val neigh = TunerConfig.FFT_BIN_NEIGHBORHOOD
        val lo = max(1, bin - neigh)
        val hi = min(maxBin, bin + neigh)
        var peak = 0.0
        for (i in lo..hi) {
            val v = magnitudes[i].toDouble()
            if (v > peak) peak = v
        }

        // Local "noise" estimate: average a slightly wider band around bin, excluding immediate peak region.
        val band = 6
        val lo2 = max(1, bin - band)
        val hi2 = min(maxBin, bin + band)
        var sum = 0.0
        var c = 0
        for (i in lo2..hi2) {
            if (i in lo..hi) continue
            sum += magnitudes[i].toDouble()
            c++
        }
        val noise = if (c > 0) sum / c.toDouble() else 0.0
        val denom = noise + 1e-12
        val snr = peak / denom

        // Map SNR-ish ratio to [0..1] support.
        return ((snr - 1.0) / 8.0).coerceIn(0.0, 1.0)
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
