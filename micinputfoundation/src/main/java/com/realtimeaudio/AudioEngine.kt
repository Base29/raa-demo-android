package com.realtimeaudio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AudioEngine(private val onDataCallback: (AudioData) -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var processingThread: Thread? = null

    // DSP Configuration
    private var bufferSize = 1024
    private var sampleRate = 48000  // Prefer 48kHz
    private var callbackRateHz = 30
    private var emitFft = true
    private var emitTimeData = true
    private var smoothingEnabled = true
    private var smoothingFactor = 0.5f
    private var fftSize = 1024
    private var downsampleBins = -1

    // DSP processing components
    // Real-time-safe microphone level processor (RMS + peak with optional smoothing)
    private val micProcessor: RealtimeMicProcessor = RealtimeMicProcessor()
    // Real-time-safe FFT engine (KissFFT via JNI)
    private val fftEngine: RealtimeFFTEngine = RealtimeFFTEngine(fftSize = fftSize, downsampleBins = downsampleBins)

    // Single reusable background worker for non-real-time DSP work
    private val processingExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AudioEngine-Worker").apply {
                priority = Thread.NORM_PRIORITY
            }
        }

    data class AudioData(
        val timestamp: Double,
        val rms: Double,
        val peak: Double,
        val fft: FloatArray?,
        val timeData: FloatArray?,
        val sampleRate: Int,
        val bufferSize: Int
    )

    private var libraryLoaded = false

    init {
        setupComponents()
    }

    private fun setupComponents() {
        // Configure DSP components with the current settings.
        // This keeps the capture loop simple while still providing analyzers.
        micProcessor.configure(smoothingEnabled, smoothingFactor)
        fftEngine.configure(fftSize, downsampleBins)
    }

    fun start(
        bufferSize: Int,
        sampleRate: Int,
        callbackRateHz: Int,
        emitFft: Boolean,
        emitTimeData: Boolean = true
    ) {
        if (isRunning) {
            rtLogger.warning("Audio engine already running")
            return
        }

        this.bufferSize = bufferSize
        this.sampleRate = sampleRate
        this.callbackRateHz = callbackRateHz
        this.emitFft = emitFft
        this.emitTimeData = emitTimeData
        this.fftSize = bufferSize // Default FFT size to buffer size

        // Setup components with new configuration
        setupComponents()

        // Ensure safe buffer size with fallback sample rate logic
        var actualSampleRate = sampleRate
        var minBufferSize = AudioRecord.getMinBufferSize(
            actualSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // If 48kHz fails, try 44.1kHz fallback
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            rtLogger.warning("48kHz not supported, falling back to 44.1kHz")
            actualSampleRate = 44100
            minBufferSize = AudioRecord.getMinBufferSize(
                actualSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }
        
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw Exception("Invalid parameter for AudioRecord")
        }
        
        // Update actual sample rate
        this.sampleRate = actualSampleRate
        
        // We might need a larger internal buffer than the requested processing bufferSize
        val recordBufferSize = kotlin.math.max(minBufferSize, bufferSize * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // Tuned for voice/audio analysis
                actualSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize
            )
        } catch (e: SecurityException) {
            throw Exception("Permission denied")
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw Exception("AudioRecord initialization failed")
        }

        isRunning = true
        audioRecord?.startRecording()

        processingThread = Thread {
            processAudio()
        }
        processingThread?.priority = Thread.MAX_PRIORITY
        processingThread?.start()
    }

    fun stop() {
        isRunning = false
        
        // Wait for processing thread to finish
        try {
            processingThread?.join(1000) // Wait up to 1 second
            if (processingThread?.isAlive == true) {
                rtLogger.warning("Processing thread did not terminate gracefully")
                processingThread?.interrupt()
            }
        } catch (e: InterruptedException) {
            rtLogger.warning("Interrupted while waiting for processing thread: ${e.message}")
            Thread.currentThread().interrupt()
        }
        processingThread = null

        // Stop and release AudioRecord
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            rtLogger.warning("Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        
        // Cleanup components
        cleanupComponents()
    }

    private fun cleanupComponents() {
        // Reset DSP components; they are real-time safe and allocation-free during processing.
        micProcessor.reset()
        fftEngine.reset()
        rtLogger.stop()
    }

    fun isRecording(): Boolean {
        return isRunning
    }

    fun setSmoothing(enabled: Boolean, factor: Float) {
        // Configure microphone level smoothing.
        this.smoothingEnabled = enabled
        this.smoothingFactor = factor.coerceIn(0.0f, 1.0f)
        micProcessor.configure(this.smoothingEnabled, this.smoothingFactor)
    }

    fun setFftConfig(size: Int, bins: Int) {
        // Update FFT configuration and reconfigure the FFT engine.
        this.fftSize = size
        this.downsampleBins = bins
        fftEngine.configure(this.fftSize, this.downsampleBins)
    }

    fun setTimeDataConfig(enabled: Boolean) {
        this.emitTimeData = enabled
    }

    private fun processAudio() {
        val readBuffer = ShortArray(bufferSize)
        val floatBuffer = FloatArray(bufferSize) // For processing
        
        // Use monotonic clock for audio timing
        var lastCallbackTimeNs = 0L
        val updateIntervalNs = 1_000_000_000L / callbackRateHz

        while (isRunning) {
            val record = audioRecord ?: break
            val readCount = record.read(readBuffer, 0, bufferSize)

            // REAL-TIME SAFE: Handle errors without logging
            if (readCount < 0) {
                // Error reading audio - break silently to avoid blocking
                break
            }

            if (readCount > 0) {
                // Convert to float [-1.0, 1.0]
                for (i in 0 until readCount) {
                    floatBuffer[i] = readBuffer[i] / 32768.0f
                }

                val nowNs = System.nanoTime()
                if (lastCallbackTimeNs == 0L || (nowNs - lastCallbackTimeNs) >= updateIntervalNs) {
                    // Convert monotonic nanoseconds to milliseconds for downstream consumers
                    val timestampMs = nowNs / 1_000_000.0

                    // Offload non-real-time DSP work to a single reusable background worker
                    processingExecutor.execute {
                        processAudioData(
                            timestamp = timestampMs,
                            sampleRate = sampleRate,
                            bufferSize = readCount,
                            timeData = if (emitTimeData) floatBuffer.copyOfRange(0, readCount) else null,
                            rawSamples = floatBuffer.copyOfRange(0, readCount)
                        )
                    }

                    lastCallbackTimeNs = nowNs
                }
            }
        }
    }
    
    // MARK: - Non-Real-Time Data Processing
    
    private fun processAudioData(
        timestamp: Double,
        sampleRate: Int,
        bufferSize: Int,
        timeData: FloatArray?,
        rawSamples: FloatArray
    ) {
        // This runs on a background thread - safe to allocate and log.
        // Here we perform non-real-time DSP: RMS/peak calculation and FFT analysis.

        // --- Microphone Level Analysis (RMS + Peak with optional smoothing) ---
        val levelData = micProcessor.processBuffer(rawSamples, bufferSize)
        val rms = levelData.rms.toDouble()
        val peak = levelData.peak.toDouble()

        // --- FFT Spectrum Analysis (KissFFT via JNI) ---
        // Respect the emitFft flag; if disabled or FFT engine fails, fftMagnitudes will be null.
        val fftMagnitudes: FloatArray? = if (emitFft) {
            fftEngine.processBuffer(rawSamples, bufferSize)?.magnitudes
        } else {
            null
        }

        val data = AudioData(
            timestamp = timestamp,
            rms = rms,
            peak = peak,
            fft = fftMagnitudes,
            timeData = timeData,
            sampleRate = sampleRate,
            bufferSize = bufferSize
        )

        onDataCallback(data)
    }

    companion object {
        const val TAG = "AudioEngine"
    }
}
