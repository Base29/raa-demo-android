package com.realtimeaudio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// MARK: - Data Models

/**
 * Level data output from microphone processing
 */
data class LevelData(
    val rms: Float,
    val peak: Float,
    val timestamp: Long
)

// MARK: - Interface Definition

/**
 * Interface for real-time safe microphone processing
 */
interface MicProcessor {
    /**
     * Process audio buffer and return level data
     * @param samples Audio samples to process
     * @param count Number of samples to process
     * @return Level data containing RMS and peak values
     */
    fun processBuffer(samples: FloatArray, count: Int): LevelData
    
    /**
     * Configure smoothing parameters
     * @param smoothingEnabled Whether to enable smoothing
     * @param smoothingFactor Smoothing factor (0.0 to 1.0)
     */
    fun configure(smoothingEnabled: Boolean, smoothingFactor: Float)
    
    /**
     * Reset internal state
     */
    fun reset()
}

// MARK: - Testing and Validation Interface

/**
 * Interface for testing and validation capabilities
 * Requirement 6.1, 6.4: Clean interface for test audio data injection
 */
interface MicProcessorTestable {
    /**
     * Process test audio data from array for validation
     * @param testSamples Array of test audio samples
     * @return Level data for validation
     */
    fun processTestBuffer(testSamples: FloatArray): LevelData
    
    /**
     * Inject synthetic audio level sequence for smoothing engine testing
     * Requirement 6.3: Testable smoothing engine with synthetic sequences
     * @param rmsSequence Sequence of RMS values to inject
     * @param peakSequence Sequence of peak values to inject
     * @return Array of smoothed level data
     */
    fun processLevelSequence(rmsSequence: FloatArray, peakSequence: FloatArray): List<LevelData>
    
    /**
     * Get internal state for debugging and validation
     * Requirement 6.5: Expose internal state for debugging
     * @return Internal state snapshot
     */
    fun getInternalState(): MicProcessorState
    
    /**
     * Set internal state for deterministic testing
     * Requirement 6.2: Deterministic output for identical inputs
     * @param state State to set for deterministic testing
     */
    fun setInternalState(state: MicProcessorState)
}

/**
 * Internal state structure for debugging and validation
 */
data class MicProcessorState(
    val smoothingEnabled: Boolean,
    val smoothingFactor: Float,
    val smoothRms: Float,
    val smoothPeak: Float
)

// MARK: - Implementation

/**
 * Real-time safe microphone processor implementation
 * Calculates RMS and peak values without FFT dependencies
 * No memory allocations or logging in processing methods
 */
class RealtimeMicProcessor : MicProcessor, MicProcessorTestable {
    
    // MARK: - Private Properties
    
    private var smoothingEnabled: Boolean = false
    private var smoothingFactor: Float = 0.1f
    private var smoothRms: Float = 0.0f
    private var smoothPeak: Float = 0.0f
    
    // MARK: - MicProcessor Interface Implementation
    
    override fun processBuffer(samples: FloatArray, count: Int): LevelData {
        // Early return for invalid input - no logging to maintain real-time safety
        if (count <= 0 || samples.isEmpty()) {
            return LevelData(rms = 0.0f, peak = 0.0f, timestamp = getCurrentTimestamp())
        }
        
        val actualCount = min(count, samples.size)
        
        // PERFORMANCE OPTIMIZATION: Single-pass calculation for both RMS and peak
        // This reduces memory access and improves cache efficiency
        var sumSquares = 0.0f
        var peak = 0.0f
        
        // Unrolled loop for better performance (process 4 samples at a time when possible)
        val unrolledCount = actualCount and 0xFFFFFFFC // Round down to multiple of 4
        var i = 0
        
        // Process 4 samples at a time for better CPU pipeline utilization
        while (i < unrolledCount) {
            val s0 = samples[i]
            val s1 = samples[i + 1]
            val s2 = samples[i + 2]
            val s3 = samples[i + 3]
            
            // Update peak values
            val abs0 = abs(s0)
            val abs1 = abs(s1)
            val abs2 = abs(s2)
            val abs3 = abs(s3)
            
            if (abs0 > peak) peak = abs0
            if (abs1 > peak) peak = abs1
            if (abs2 > peak) peak = abs2
            if (abs3 > peak) peak = abs3
            
            // Accumulate squares for RMS
            sumSquares += s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3
            
            i += 4
        }
        
        // Process remaining samples
        while (i < actualCount) {
            val sample = samples[i]
            val absSample = abs(sample)
            
            if (absSample > peak) {
                peak = absSample
            }
            
            sumSquares += sample * sample
            i++
        }
        
        // Calculate RMS
        val rms = sqrt(sumSquares / actualCount)
        
        // Apply smoothing if enabled - optimized for minimal branching
        val finalRms: Float
        val finalPeak: Float
        
        if (smoothingEnabled) {
            // Exponential smoothing with fused multiply-add
            smoothRms = smoothRms + (rms - smoothRms) * smoothingFactor
            smoothPeak = smoothPeak + (peak - smoothPeak) * smoothingFactor
            finalRms = smoothRms
            finalPeak = smoothPeak
        } else {
            // No smoothing - update internal state for consistency
            smoothRms = rms
            smoothPeak = peak
            finalRms = rms
            finalPeak = peak
        }
        
        return LevelData(
            rms = finalRms,
            peak = finalPeak,
            timestamp = getCurrentTimestamp()
        )
    }
    
    override fun configure(smoothingEnabled: Boolean, smoothingFactor: Float) {
        this.smoothingEnabled = smoothingEnabled
        // Clamp smoothing factor to valid range without logging
        this.smoothingFactor = max(0.0f, min(1.0f, smoothingFactor))
    }
    
    override fun reset() {
        smoothRms = 0.0f
        smoothPeak = 0.0f
    }
    
    // MARK: - Private Helpers
    
    /**
     * Get current timestamp in nanoseconds converted to milliseconds
     * PERFORMANCE OPTIMIZATION: Use System.nanoTime() for high precision
     */
    private fun getCurrentTimestamp(): Long {
        return System.nanoTime() / 1_000_000 // Convert to milliseconds
    }
    
    // MARK: - MicProcessorTestable Interface Implementation
    
    override fun processTestBuffer(testSamples: FloatArray): LevelData {
        // Requirement 6.1, 6.4: Clean interface for test audio data injection
        return processBuffer(testSamples, testSamples.size)
    }
    
    override fun processLevelSequence(rmsSequence: FloatArray, peakSequence: FloatArray): List<LevelData> {
        // Requirement 6.3: Testable smoothing engine with synthetic sequences
        if (rmsSequence.size != peakSequence.size) {
            return emptyList()
        }
        
        val results = mutableListOf<LevelData>()
        val baseTimestamp = getCurrentTimestamp()
        
        for (i in rmsSequence.indices) {
            val rms = rmsSequence[i]
            val peak = peakSequence[i]
            
            // Apply smoothing if enabled (same logic as processBuffer)
            val finalRms: Float
            val finalPeak: Float
            
            if (smoothingEnabled) {
                smoothRms = smoothRms + (rms - smoothRms) * smoothingFactor
                smoothPeak = smoothPeak + (peak - smoothPeak) * smoothingFactor
                finalRms = smoothRms
                finalPeak = smoothPeak
            } else {
                smoothRms = rms
                smoothPeak = peak
                finalRms = rms
                finalPeak = peak
            }
            
            results.add(LevelData(
                rms = finalRms,
                peak = finalPeak,
                timestamp = baseTimestamp + i // Simulate 1ms intervals
            ))
        }
        
        return results
    }
    
    override fun getInternalState(): MicProcessorState {
        // Requirement 6.5: Expose internal state for debugging
        return MicProcessorState(
            smoothingEnabled = smoothingEnabled,
            smoothingFactor = smoothingFactor,
            smoothRms = smoothRms,
            smoothPeak = smoothPeak
        )
    }
    
    override fun setInternalState(state: MicProcessorState) {
        // Requirement 6.2: Deterministic output for identical inputs
        this.smoothingEnabled = state.smoothingEnabled
        this.smoothingFactor = state.smoothingFactor
        this.smoothRms = state.smoothRms
        this.smoothPeak = state.smoothPeak
    }
}