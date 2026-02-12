package com.realtimeaudio

// MARK: - Interface Definitions

/**
 * Interface for audio processors that can receive audio data
 */
interface AudioProcessor {
    /**
     * Process audio samples with timestamp
     * @param samples Audio samples to process
     * @param count Number of samples
     * @param timestamp Timestamp of the audio data
     */
    fun processAudio(samples: FloatArray, count: Int, timestamp: Long)
}

/**
 * Interface for routing audio buffers to multiple processors
 */
interface AudioBufferRouter {
    /**
     * Route audio samples to registered processors
     * @param samples Audio samples to route
     * @param count Number of samples
     * @param timestamp Timestamp of the audio data
     * @param processors List of processors to receive the data
     */
    fun routeSamples(samples: FloatArray, count: Int, timestamp: Long, processors: List<AudioProcessor>)
}

// MARK: - Implementation

/**
 * Real-time safe audio buffer router implementation
 * Routes audio data to multiple processors without introducing coupling
 * No memory allocations or logging in routing methods
 */
class RealtimeAudioBufferRouter : AudioBufferRouter {
    
    override fun routeSamples(samples: FloatArray, count: Int, timestamp: Long, processors: List<AudioProcessor>) {
        // Early return for invalid input - no logging to maintain real-time safety
        if (count <= 0 || samples.isEmpty()) {
            return
        }
        
        val actualCount = kotlin.math.min(count, samples.size)
        
        // Route to each processor independently
        // Each processor receives the same data but processes it independently
        for (processor in processors) {
            processor.processAudio(samples, actualCount, timestamp)
        }
    }
}

// MARK: - Adapter Classes

/**
 * Adapter to make MicProcessor compatible with AudioProcessor interface
 */
class MicProcessorAdapter(
    private val micProcessor: MicProcessor,
    private val onLevelData: (LevelData) -> Unit
) : AudioProcessor {
    
    override fun processAudio(samples: FloatArray, count: Int, timestamp: Long) {
        val levelData = micProcessor.processBuffer(samples, count)
        onLevelData(levelData)
    }
}

/**
 * Adapter for FFT processing using the decoupled FFT engine
 */
class FFTProcessorAdapter(
    private val fftEngine: FFTEngine,
    private val onFFTData: (FFTData) -> Unit
) : AudioProcessor {
    
    override fun processAudio(samples: FloatArray, count: Int, timestamp: Long) {
        // Process audio through the decoupled FFT engine
        fftEngine.processBuffer(samples, count)?.let { fftData ->
            onFFTData(fftData)
        }
    }
}