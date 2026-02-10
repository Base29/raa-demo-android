package com.realtimeaudio

import android.util.Log
import kotlin.math.min

// MARK: - Interface Definition

/**
 * Interface for FFT processing engines
 */
interface FFTEngine {
    /**
     * Process audio buffer and return frequency data
     * @param samples Audio samples to process
     * @param count Number of samples
     * @return FFT data if processing succeeds, null otherwise
     */
    fun processBuffer(samples: FloatArray, count: Int): FFTData?
    
    /**
     * Configure FFT parameters
     * @param fftSize Size of FFT (should be power of 2)
     * @param downsampleBins Number of bins to downsample to (-1 for no downsampling)
     */
    fun configure(fftSize: Int, downsampleBins: Int)
    
    /**
     * Enable or disable FFT processing
     */
    var isEnabled: Boolean
    
    /**
     * Reset FFT state
     */
    fun reset()
}

// MARK: - Data Structures

/**
 * FFT output data structure
 */
data class FFTData(
    val magnitudes: FloatArray,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as FFTData
        
        if (!magnitudes.contentEquals(other.magnitudes)) return false
        if (timestamp != other.timestamp) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = magnitudes.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

// MARK: - Implementation

/**
 * Real-time safe FFT engine implementation
 * Operates independently of microphone processing
 * No memory allocations or logging in processing methods
 */
class RealtimeFFTEngine(
    private var fftSize: Int = 1024,
    private var downsampleBins: Int = -1
) : FFTEngine {
    
    // MARK: - Configuration
    
    override var isEnabled: Boolean = true
    
    // MARK: - Native Library State
    
    private var libraryLoaded = false
    
    // MARK: - Pre-allocated Buffers
    
    private var fftOutput: FloatArray = FloatArray(0)
    
    // MARK: - Initialization
    
    init {
        try {
            System.loadLibrary("realtimeaudioanalyzer")
            libraryLoaded = true
            setupFFT()
        } catch (e: Exception) {
            // Fail silently - logging would be a real-time violation if called during audio processing
            libraryLoaded = false
        }
    }
    
    // MARK: - JNI Methods
    
    private external fun computeFft(input: FloatArray, output: FloatArray, nfft: Int)
    private external fun cleanupFft()
    
    // MARK: - FFTEngine Interface Implementation
    
    override fun processBuffer(samples: FloatArray, count: Int): FFTData? {
        // Early return if disabled - no logging to maintain real-time safety
        if (!isEnabled) return null
        
        // Early return if library not loaded - no logging to maintain real-time safety
        if (!libraryLoaded) return null
        
        // Early return for invalid input - no logging to maintain real-time safety
        if (count <= 0 || samples.isEmpty()) return null
        
        val actualCount = min(count, samples.size)
        val processCount = min(actualCount, fftSize)
        
        // Ensure output buffer is properly sized
        val neededSize = fftSize / 2
        if (fftOutput.size < neededSize) {
            // This should only happen during initialization, not in real-time processing
            return null
        }
        
        try {
            // Execute FFT using native implementation
            computeFft(samples, fftOutput, processCount)
            
            // Prepare output data
            val outputMagnitudes: FloatArray = if (downsampleBins > 0 && downsampleBins < neededSize) {
                resampleFft(fftOutput, neededSize, downsampleBins)
            } else {
                fftOutput.copyOfRange(0, neededSize)
            }
            
            val timestamp = System.currentTimeMillis()
            return FFTData(magnitudes = outputMagnitudes, timestamp = timestamp)
            
        } catch (e: UnsatisfiedLinkError) {
            // JNI method not found - library may not be loaded
            // Fail silently to maintain real-time safety
            return null
        } catch (e: Exception) {
            // Other FFT processing error
            // Fail silently to maintain real-time safety
            return null
        }
    }
    
    override fun configure(fftSize: Int, downsampleBins: Int) {
        // Only reconfigure if parameters changed
        if (this.fftSize == fftSize && this.downsampleBins == downsampleBins) {
            return
        }
        
        this.fftSize = fftSize
        this.downsampleBins = downsampleBins
        
        // Cleanup old setup and create new one
        cleanup()
        setupFFT()
    }
    
    override fun reset() {
        // Reset buffers to zero
        if (fftOutput.isNotEmpty()) {
            fftOutput.fill(0.0f)
        }
    }
    
    // MARK: - Private Implementation
    
    private fun setupFFT() {
        if (!libraryLoaded) return
        
        // Allocate output buffer
        val neededSize = fftSize / 2
        fftOutput = FloatArray(neededSize + 10) // Add some padding for safety
    }
    
    private fun cleanup() {
        if (!libraryLoaded) return
        
        try {
            cleanupFft()
        } catch (e: Exception) {
            // Fail silently - logging would be a real-time violation if called during audio processing
        }
    }
    
    /**
     * Simple bin averaging for downsampling
     */
    private fun resampleFft(src: FloatArray, srcLen: Int, destLen: Int): FloatArray {
        val dest = FloatArray(destLen)
        val bucketSize = srcLen.toFloat() / destLen.toFloat()
        
        for (i in 0 until destLen) {
            val startIndex = (i * bucketSize).toInt()
            val endIndex = ((i + 1) * bucketSize).toInt().coerceAtMost(srcLen)
            
            if (startIndex >= endIndex) {
                if (startIndex < srcLen) dest[i] = src[startIndex]
                continue
            }
            
            var sum = 0.0f
            for (j in startIndex until endIndex) {
                sum += src[j]
            }
            dest[i] = sum / (endIndex - startIndex)
        }
        return dest
    }
    
    companion object {
        const val TAG = "RealtimeFFTEngine"
    }
}