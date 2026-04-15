package com.realtimeaudio

/**
 * Explicit analyzer configuration to avoid ambiguous "bufferSize == fftSize" coupling.
 *
 * - bufferSizeFrames: capture/processing chunk size (AudioRecord read size)
 * - fftSize: spectral analysis size (verification/visualization only)
 * - callbackRateHz: update cadence to JS/consumers
 * - enableFft: when false, FFT work is skipped entirely ("pitch-only" mode)
 */
data class AnalyzerConfig(
    val bufferSizeFrames: Int = 1024,
    val sampleRateHz: Int = 48_000,
    val fftSize: Int = 1024,
    val callbackRateHz: Int = 30,
    val enableFft: Boolean = true,
    val enableTimeData: Boolean = true,
    val calibrationA4Hz: Double? = null,
    val debugPitch: Boolean = false
) {
    fun validated(): AnalyzerConfig {
        val buf = bufferSizeFrames.coerceIn(256, 8192)
        val sr = sampleRateHz.coerceIn(8_000, 48_000)
        val rate = callbackRateHz.coerceIn(5, 60)
        val fft = if (enableFft) fftSize.coerceIn(256, 16384).let { pow2AtOrAbove(it) } else 0
        return copy(bufferSizeFrames = buf, sampleRateHz = sr, callbackRateHz = rate, fftSize = fft, calibrationA4Hz = calibrationA4Hz)
    }

    private fun pow2AtOrAbove(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }
}

