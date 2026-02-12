package com.realtimeaudio

/**
 * Integration Example
 * This file demonstrates how AudioBufferRouter would be integrated into the existing audio processing pipeline
 */

/**
 * Example integration of AudioBufferRouter with existing AudioEngine
 * This shows how the router decouples audio data distribution from processing logic
 */
class AudioBufferRouterIntegrationExample {
    
    // MARK: - Components
    
    private val router = RealtimeAudioBufferRouter()
    private val micProcessor = RealtimeMicProcessor()
    private val processors = mutableListOf<AudioProcessor>()
    
    // MARK: - Configuration
    
    private var onLevelData: ((LevelData) -> Unit)? = null
    private var onFFTData: ((FFTProcessorAdapter.FFTData) -> Unit)? = null
    
    // MARK: - Initialization
    
    init {
        setupProcessors()
    }
    
    // MARK: - Setup
    
    private fun setupProcessors() {
        // Create MicProcessor adapter
        val micAdapter = MicProcessorAdapter(micProcessor) { levelData ->
            onLevelData?.invoke(levelData)
        }
        
        // Create FFT processor adapter (placeholder for future FFT engine)
        val fftAdapter = FFTProcessorAdapter { fftData ->
            onFFTData?.invoke(fftData)
        }
        
        // Register processors with router
        processors.clear()
        processors.add(micAdapter)
        processors.add(fftAdapter)
    }
    
    // MARK: - Configuration Methods
    
    fun setLevelDataCallback(callback: (LevelData) -> Unit) {
        onLevelData = callback
    }
    
    fun setFFTDataCallback(callback: (FFTProcessorAdapter.FFTData) -> Unit) {
        onFFTData = callback
    }
    
    fun configureMicProcessor(smoothingEnabled: Boolean, smoothingFactor: Float) {
        micProcessor.configure(smoothingEnabled, smoothingFactor)
    }
    
    // MARK: - Audio Processing Integration
    
    /**
     * This method would replace the existing processAudio logic in AudioEngine
     * It demonstrates how the router decouples audio distribution from processing
     */
    fun processAudioWithRouter(samples: FloatArray, count: Int, timestamp: Long) {
        // REAL-TIME SAFE: No logging, no memory allocations, no I/O operations
        
        // The router handles distribution to all processors independently
        // Each processor receives the same audio data but processes it independently
        // This eliminates coupling between MicProcessor and FFT processing
        router.routeSamples(samples, count, timestamp, processors)
        
        // Note: The actual data emission (React Native events, etc.) would be handled
        // by the individual processor callbacks, maintaining real-time safety
    }
    
    // MARK: - Processor Management
    
    /**
     * Add a new processor to the routing system
     * This demonstrates the extensibility of the router approach
     */
    fun addProcessor(processor: AudioProcessor) {
        processors.add(processor)
    }
    
    /**
     * Remove a processor from the routing system
     */
    fun removeProcessor(processor: AudioProcessor) {
        processors.remove(processor)
    }
    
    /**
     * Enable/disable specific processors without affecting others
     * This demonstrates the independence achieved by the router
     */
    fun setProcessorEnabled(processorType: ProcessorType, enabled: Boolean) {
        when (processorType) {
            ProcessorType.MICROPHONE -> {
                if (enabled) {
                    // Ensure mic processor is in the list
                    if (processors.none { it is MicProcessorAdapter }) {
                        val micAdapter = MicProcessorAdapter(micProcessor) { levelData ->
                            onLevelData?.invoke(levelData)
                        }
                        processors.add(micAdapter)
                    }
                } else {
                    // Remove mic processor from the list
                    processors.removeAll { it is MicProcessorAdapter }
                }
            }
            ProcessorType.FFT -> {
                if (enabled) {
                    // Ensure FFT processor is in the list
                    if (processors.none { it is FFTProcessorAdapter }) {
                        val fftAdapter = FFTProcessorAdapter { fftData ->
                            onFFTData?.invoke(fftData)
                        }
                        processors.add(fftAdapter)
                    }
                } else {
                    // Remove FFT processor from the list
                    processors.removeAll { it is FFTProcessorAdapter }
                }
            }
        }
    }
    
    enum class ProcessorType {
        MICROPHONE,
        FFT
    }
}

/*
 Benefits of the AudioBufferRouter approach:
 
 1. DECOUPLING: MicProcessor and FFT engine are completely independent
    - MicProcessor can work without FFT dependencies
    - FFT can be disabled without affecting microphone processing
    - Each processor can be tested in isolation
 
 2. REAL-TIME SAFETY: Router maintains real-time safety
    - No memory allocations in routing methods
    - No logging or I/O operations
    - Minimal overhead for audio distribution
 
 3. EXTENSIBILITY: Easy to add new processors
    - New processors just implement AudioProcessor interface
    - No changes needed to existing processors
    - Router handles distribution automatically
 
 4. TESTABILITY: Each component can be tested independently
    - Mock processors can be injected for testing
    - Router behavior can be verified in isolation
    - Individual processors can be unit tested
 
 5. CONFIGURATION FLEXIBILITY: Processors can be enabled/disabled dynamically
    - FFT can be completely disabled without affecting levels
    - Different processor combinations can be used
    - Runtime configuration changes are safe
 
 This satisfies Requirement 3.3: "THE Audio_Callback SHALL route audio data to both 
 Mic_Processor and FFT_Engine independently"
 */