package com.realtimeaudio

import android.media.AudioRecord
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Lock-free atomic callback reference for thread-safe callback updates.
 *
 * This is used so the real-time capture thread never takes locks.
 */
internal class AtomicPCMCallback {
    private val callbackRef = AtomicReference<PCMCallback?>(null)

    fun set(callback: PCMCallback?) {
        callbackRef.set(callback)
    }

    fun invoke(samples: ShortArray, frameCount: Int) {
        callbackRef.get()?.invoke(samples, frameCount)
    }
}

/**
 * Android implementation of MicInput interface using AudioRecord.
 *
 * Capture-only:
 * - No DSP (no RMS/peak/FFT)
 * - No routing/timestamps/orchestration inside mic input
 * - No logging, allocations, locks, or blocking operations inside the real-time capture loop
 */
class MicInputAndroid : MicInput {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val atomicCallback = AtomicPCMCallback()

    @Volatile private var activeConfig: MicInputConfig? = null

    private var sampleRate: Int = 0
    private var channels: Int = 0
    private var bufferSize: Int = 0
    private var audioBuffer: ShortArray? = null

    private val errorHandler = MicInputErrorHandler()

    /**
     * Start microphone capture with the specified configuration and callback.
     *
     * @param config Audio capture configuration
     * @param onPCM Callback to receive raw PCM audio data
     * @throws MicInputException if configuration is invalid or capture cannot be started
     */
    override fun start(config: MicInputConfig, onPCM: PCMCallback) {
        if (isRecording.get()) {
            throw MicInputException(
                    MicInputErrorType.ALREADY_RUNNING,
                    "Microphone capture is already running"
            )
        }

        try {
            val result = errorHandler.createAudioRecordWithFallback(config)

            if (result.isFailure) {
                throw result.error
                        ?: MicInputException(
                                MicInputErrorType.PLATFORM_ERROR,
                                "Unknown error during AudioRecord creation"
                        )
            }

            val actualConfig = result.actualConfig!!
            this.sampleRate = actualConfig.sampleRate
            this.channels = actualConfig.channels
            this.bufferSize = actualConfig.bufferSize
            this.audioRecord = result.audioRecord
            this.activeConfig = actualConfig

            atomicCallback.set(onPCM)

            // Allocate capture buffer once (outside the real-time thread).
            // Buffer size is expressed in FRAMES in MicInputConfig, so multiply by channels for
            // samples.
            audioBuffer = ShortArray(bufferSize * channels)

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = thread(name = "MicInputAndroid-Recording") { recordingLoop() }
        } catch (e: MicInputException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            cleanup()
            throw MicInputException(
                    MicInputErrorType.PLATFORM_ERROR,
                    "Unexpected error during microphone start: ${e.message}",
                    e
            )
        }
    }

    /** Stop microphone capture. */
    override fun stop() {
        if (!isRecording.get()) {
            return // Already stopped
        }

        isRecording.set(false)

        // Wait briefly for thread to exit.
        recordingThread?.join(1000)

        cleanup()
    }

    /**
     * Query the current capture state.
     *
     * @return true if microphone capture is currently active, false otherwise
     */
    override fun isRunning(): Boolean {
        return isRecording.get()
    }

    /**
     * Update the PCM callback while capture is running.
     *
     * @param onPCM New callback to receive raw PCM audio data
     */
    override fun updateCallback(onPCM: PCMCallback) {
        atomicCallback.set(onPCM)
    }

    override fun getActiveConfig(): MicInputConfig? = activeConfig

    /**
     * Main recording loop that runs in a separate thread.
     *
     * Real-time constraints:
     * - No allocations
     * - No logging
     * - No locks
     * - No blocking operations other than AudioRecord.read()
     */
    private fun recordingLoop() {
        val audioBuffer = this.audioBuffer ?: return
        val audioRecord = this.audioRecord ?: return

        while (isRecording.get()) {
            // Read audio data (blocking call, but that's expected for capture).
            val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)

            if (readCount > 0) {
                // readCount is in SAMPLES for the ShortArray overload.
                val frameCount = readCount / channels
                atomicCallback.invoke(audioBuffer, frameCount)
            } else if (readCount < 0) {
                // No logging / no throwing on the real-time thread.
                break
            }
        }
    }

    /** Clean up resources. */
    private fun cleanup() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        audioRecord = null
        recordingThread = null
        audioBuffer = null
        activeConfig = null
        atomicCallback.set(null)
    }
}

/** Type aliases for Kotlin compatibility with TypeScript interfaces */
typealias PCMCallback = (samples: ShortArray, frameCount: Int) -> Unit

/** Data class representing microphone input configuration */
data class MicInputConfig(
        val sampleRate: Int,
        val channels: Int,
        val bufferSize: Int // frames per callback
)

/** Interface for microphone input implementations */
interface MicInput {
    fun start(config: MicInputConfig, onPCM: PCMCallback)
    fun stop()
    fun isRunning(): Boolean
    fun updateCallback(onPCM: PCMCallback)
    fun getActiveConfig(): MicInputConfig?
}
