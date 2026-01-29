package com.realtimeaudio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Enhanced error handling for Android MicInput implementation.
 *
 * Capture-only: this class is responsible for creating AudioRecord and mapping platform failures
 * into MicInputException types.
 */
class MicInputErrorHandler {

    companion object {
        /** Supported sample rates in order of preference (highest quality first) */
        private val PREFERRED_SAMPLE_RATES = listOf(48000, 44100, 22050, 16000, 8000)

        /** Supported channel configurations in order of preference */
        private val PREFERRED_CHANNEL_CONFIGS =
                listOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO)

        /** Supported audio formats in order of preference */
        private val PREFERRED_AUDIO_FORMATS = listOf(AudioFormat.ENCODING_PCM_16BIT)
    }

    /** Result of attempting to create an AudioRecord with error handling and fallback. */
    data class AudioRecordResult(
            val audioRecord: AudioRecord?,
            val actualConfig: MicInputConfig?,
            val error: MicInputException?
    ) {
        val isSuccess: Boolean
            get() = audioRecord != null && actualConfig != null
        val isFailure: Boolean
            get() = !isSuccess
    }

    /**
     * Create AudioRecord with comprehensive error handling and graceful degradation.
     *
     * This method attempts to create an AudioRecord with the requested configuration. If the exact
     * configuration fails, it tries fallback configurations in order of preference to find a
     * working setup.
     *
     * @param requestedConfig The desired audio configuration
     * @return AudioRecordResult containing the created AudioRecord and actual config, or error
     */
    fun createAudioRecordWithFallback(requestedConfig: MicInputConfig): AudioRecordResult {
        // First, try the exact requested configuration
        val exactResult = tryCreateAudioRecord(requestedConfig)
        if (exactResult.isSuccess) {
            return exactResult
        }

        // If exact config failed, try fallback configurations
        val fallbackConfigs = generateFallbackConfigurations(requestedConfig)

        for (fallbackConfig in fallbackConfigs) {
            val fallbackResult = tryCreateAudioRecord(fallbackConfig)
            if (fallbackResult.isSuccess) {
                return fallbackResult
            }
        }

        // All configurations failed - return the original error with enhanced details
        return AudioRecordResult(
                audioRecord = null,
                actualConfig = null,
                error =
                        MicInputException(
                                errorType = MicInputErrorType.HARDWARE_UNAVAILABLE,
                                message =
                                        "Failed to initialize AudioRecord with requested configuration and all fallbacks. " +
                                                "Original error: ${exactResult.error?.message}",
                                cause = exactResult.error
                        )
        )
    }

    /**
     * Attempt to create AudioRecord with a specific configuration.
     *
     * @param config The audio configuration to try
     * @return AudioRecordResult with success/failure information
     */
    private fun tryCreateAudioRecord(config: MicInputConfig): AudioRecordResult {
        try {
            // Convert config to Android audio parameters
            val channelConfig =
                    if (config.channels == 1) {
                        AudioFormat.CHANNEL_IN_MONO
                    } else {
                        AudioFormat.CHANNEL_IN_STEREO
                    }

            // Try different audio formats in order of preference
            for (audioFormat in PREFERRED_AUDIO_FORMATS) {
                val result = tryCreateWithFormat(config, channelConfig, audioFormat)
                if (result.isSuccess) {
                    return result
                }
            }

            return AudioRecordResult(
                    audioRecord = null,
                    actualConfig = null,
                    error =
                            MicInputException(
                                    errorType = MicInputErrorType.FORMAT_NOT_SUPPORTED,
                                    message =
                                            "No supported audio format found for configuration: $config"
                            )
            )
        } catch (e: SecurityException) {
            return AudioRecordResult(
                    audioRecord = null,
                    actualConfig = null,
                    error =
                            MicInputException(
                                    errorType = MicInputErrorType.PERMISSION_DENIED,
                                    message = "Microphone permission not granted",
                                    cause = e
                            )
            )
        } catch (e: IllegalArgumentException) {
            return AudioRecordResult(
                    audioRecord = null,
                    actualConfig = null,
                    error =
                            MicInputException(
                                    errorType = MicInputErrorType.INVALID_CONFIG,
                                    message = "Invalid audio configuration: ${e.message}",
                                    cause = e
                            )
            )
        } catch (e: Exception) {
            return AudioRecordResult(
                    audioRecord = null,
                    actualConfig = null,
                    error =
                            MicInputException(
                                    errorType = MicInputErrorType.PLATFORM_ERROR,
                                    message =
                                            "Platform-specific error during AudioRecord creation: ${e.message}",
                                    cause = e
                            )
            )
        }
    }

    /**
     * Try to create AudioRecord with a specific audio format.
     *
     * @param config The audio configuration
     * @param channelConfig Android channel configuration
     * @param audioFormat Android audio format
     * @return AudioRecordResult with success/failure information
     */
    private fun tryCreateWithFormat(
            config: MicInputConfig,
            channelConfig: Int,
            audioFormat: Int
    ): AudioRecordResult {
        try {
            // Check if format is supported
            val minBufferSize =
                    AudioRecord.getMinBufferSize(config.sampleRate, channelConfig, audioFormat)

            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR
            ) {
                return AudioRecordResult(
                        audioRecord = null,
                        actualConfig = null,
                        error =
                                MicInputException(
                                        errorType = MicInputErrorType.FORMAT_NOT_SUPPORTED,
                                        message =
                                                "Audio format not supported by hardware: " +
                                                        "sampleRate=${config.sampleRate}, channels=${config.channels}, format=$audioFormat"
                                )
                )
            }

            // Calculate actual buffer size
            val bytesPerSample = 2 // PCM 16-bit only

            val requestedBufferBytes = config.bufferSize * config.channels * bytesPerSample
            val actualBufferSize = maxOf(requestedBufferBytes, minBufferSize)

            // Create AudioRecord
            val audioRecord =
                    AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            config.sampleRate,
                            channelConfig,
                            audioFormat,
                            actualBufferSize
                    )

            // Check if AudioRecord was initialized successfully
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                return AudioRecordResult(
                        audioRecord = null,
                        actualConfig = null,
                        error =
                                MicInputException(
                                        errorType = MicInputErrorType.HARDWARE_UNAVAILABLE,
                                        message =
                                                "AudioRecord failed to initialize. State: ${audioRecord.state}"
                                )
                )
            }

            // Success - return the working AudioRecord and actual configuration
            return AudioRecordResult(
                    audioRecord = audioRecord,
                    actualConfig = config, // Configuration worked as requested
                    error = null
            )
        } catch (e: Exception) {
            return AudioRecordResult(
                    audioRecord = null,
                    actualConfig = null,
                    error =
                            MicInputException(
                                    errorType = MicInputErrorType.PLATFORM_ERROR,
                                    message =
                                            "Failed to create AudioRecord with format $audioFormat: ${e.message}",
                                    cause = e
                            )
            )
        }
    }

    /**
     * Generate fallback configurations to try if the requested configuration fails.
     *
     * Fallbacks are generated in order of preference:
     * 1. Same sample rate, different buffer sizes
     * 2. Different sample rates, same channels
     * 3. Different sample rates, mono only
     *
     * @param requestedConfig The original requested configuration
     * @return List of fallback configurations to try
     */
    private fun generateFallbackConfigurations(
            requestedConfig: MicInputConfig
    ): List<MicInputConfig> {
        val fallbacks = mutableListOf<MicInputConfig>()

        // Try different buffer sizes with same sample rate and channels
        val bufferSizes =
                listOf(256, 512, 1024, 2048, 4096).filter { it != requestedConfig.bufferSize }
        for (bufferSize in bufferSizes) {
            fallbacks.add(requestedConfig.copy(bufferSize = bufferSize))
        }

        // Try different sample rates with same channels
        val sampleRates = PREFERRED_SAMPLE_RATES.filter { it != requestedConfig.sampleRate }
        for (sampleRate in sampleRates) {
            fallbacks.add(requestedConfig.copy(sampleRate = sampleRate))
        }

        // Try mono if stereo was requested
        if (requestedConfig.channels == 2) {
            for (sampleRate in PREFERRED_SAMPLE_RATES) {
                fallbacks.add(MicInputConfig(sampleRate, 1, requestedConfig.bufferSize))
            }
        }

        return fallbacks.distinct() // Remove duplicates
    }

    /**
     * Handle AudioRecord runtime errors during recording.
     *
     * @param readResult The result from AudioRecord.read()
     * @return Error information if an error occurred, null if successful
     */
    fun handleRecordingError(readResult: Int): MicInputException? {
        return when (readResult) {
            AudioRecord.ERROR_INVALID_OPERATION ->
                    MicInputException(
                            errorType = MicInputErrorType.NOT_RUNNING,
                            message =
                                    "AudioRecord is not properly initialized or recording has not started"
                    )
            AudioRecord.ERROR_BAD_VALUE ->
                    MicInputException(
                            errorType = MicInputErrorType.INVALID_CONFIG,
                            message = "Invalid parameters passed to AudioRecord.read()"
                    )
            AudioRecord.ERROR_DEAD_OBJECT ->
                    MicInputException(
                            errorType = MicInputErrorType.HARDWARE_UNAVAILABLE,
                            message =
                                    "AudioRecord object is no longer valid - hardware may have been disconnected"
                    )
            AudioRecord.ERROR ->
                    MicInputException(
                            errorType = MicInputErrorType.PLATFORM_ERROR,
                            message = "Generic AudioRecord error during read operation"
                    )
            else -> {
                if (readResult < 0) {
                    MicInputException(
                            errorType = MicInputErrorType.PLATFORM_ERROR,
                            message = "Unknown AudioRecord error: $readResult"
                    )
                } else {
                    null // Success
                }
            }
        }
    }
}

/** Error types specific to Android MicInput implementation */
enum class MicInputErrorType {
    INVALID_CONFIG,
    PERMISSION_DENIED,
    HARDWARE_UNAVAILABLE,
    FORMAT_NOT_SUPPORTED,
    ALREADY_RUNNING,
    NOT_RUNNING,
    PLATFORM_ERROR
}

/** Exception class for Android MicInput errors */
class MicInputException(
        val errorType: MicInputErrorType,
        message: String,
        cause: Throwable? = null
) : Exception(message, cause)
