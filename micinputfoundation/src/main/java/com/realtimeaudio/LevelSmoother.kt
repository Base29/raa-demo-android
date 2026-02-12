package com.realtimeaudio

import kotlin.math.max
import kotlin.math.min

/**
 * Isolated smoothing engine for audio level data
 * Applies temporal filtering to RMS and peak values independently
 * Real-time safe with no memory allocations or logging
 */
class LevelSmoother {
    
    // MARK: - Private Properties
    
    private var smoothRms: Float = 0.0f
    private var smoothPeak: Float = 0.0f
    private var smoothingFactor: Float = 0.1f
    private var enabled: Boolean = false
    
    // MARK: - Public Interface
    
    /**
     * Apply smoothing to RMS and peak values
     * @param rms Current RMS value
     * @param peak Current peak value
     * @return Pair containing smoothed RMS and peak values
     * PERFORMANCE OPTIMIZATION: Minimal branching and optimized calculations
     */
    fun smooth(rms: Float, peak: Float): Pair<Float, Float> {
        return if (enabled) {
            // Exponential smoothing with optimized calculations
            // new_value = old_value + (current - old_value) * factor
            val rmsResult = smoothRms + (rms - smoothRms) * smoothingFactor
            val peakResult = smoothPeak + (peak - smoothPeak) * smoothingFactor
            
            // Update state
            smoothRms = rmsResult
            smoothPeak = peakResult
            
            Pair(rmsResult, peakResult)
        } else {
            // No smoothing - update internal state for consistency
            smoothRms = rms
            smoothPeak = peak
            Pair(rms, peak)
        }
    }
    
    /**
     * Configure smoothing parameters
     * @param enabled Whether smoothing is enabled
     * @param factor Smoothing factor (0.0 to 1.0)
     */
    fun configure(enabled: Boolean, factor: Float) {
        this.enabled = enabled
        // Clamp factor to valid range without logging
        this.smoothingFactor = max(0.0f, min(1.0f, factor))
    }
    
    /**
     * Reset smoothing state
     */
    fun reset() {
        smoothRms = 0.0f
        smoothPeak = 0.0f
    }
}