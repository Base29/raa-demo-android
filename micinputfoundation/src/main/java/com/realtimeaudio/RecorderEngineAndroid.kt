package com.realtimeaudio

import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.log10

class RecorderEngineAndroid(
    private val onMeterUpdate: (rmsDb: Double, peakDb: Double) -> Unit,
    private val onDurationUpdate: (duration: Double) -> Unit,
    private val onStateChange: (state: String) -> Unit,
    private val onError: (message: String) -> Unit
) {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFilePath: String? = null
    private var startTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                updateMeterAndDuration()
                handler.postDelayed(this, 100)
            }
        }
    }

    fun startRecording(filePath: String) {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        try {
            val file = File(filePath)
            file.parentFile?.mkdirs()

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(filePath)
                prepare()
                start()
            }

            // Requirement 3: Ensure recorder fully started before setting isRecording = true
            isRecording = true
            currentFilePath = filePath
            startTime = SystemClock.elapsedRealtime()
            onStateChange("recording")
            handler.post(updateRunnable)
            Log.d(TAG, "Started recording to $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            // Requirement 4: Avoid double error flow (do not both throw and call onError)
            onError("Failed to start recording: ${e.message}")
            cleanup()
        }
    }

    fun stopRecording(): String? {
        if (!isRecording) return null

        val finalizedPath = currentFilePath
        finalizeRecording()
        cleanup()
        onStateChange("stopped")
        Log.d(TAG, "Stopped recording. File: $finalizedPath")
        return finalizedPath
    }

    // Requirement 2: Add interruption handling
    fun handleInterruption() {
        if (!isRecording && mediaRecorder == null) return
        
        Log.i(TAG, "Handling recording interruption")
        finalizeRecording()
        cleanup()
        onStateChange("interrupted")
    }

    private fun finalizeRecording() {
        // Requirement 1: Use mediaRecorder != null instead of isRecording check
        if (mediaRecorder == null) return
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.stop() failed. Recording might be too short or already stopped.", e)
            // We don't throw here as we want cleanup() to proceed
        }
    }

    private fun updateMeterAndDuration() {
        mediaRecorder?.let { recorder ->
            try {
                val maxAmplitude = recorder.maxAmplitude
                // Convert amplitude (0-32767) to dBFS
                val peakDb = if (maxAmplitude > 0) {
                    20 * log10(maxAmplitude.toDouble() / 32767.0)
                } else {
                    -100.0
                }
                
                // Requirement 5: For MediaRecorder, we don't have easy access to RMS. 
                // RMS = peak (ok for V1 but document it)
                val rmsDb = peakDb 

                onMeterUpdate(rmsDb, peakDb)

                val duration = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                onDurationUpdate(duration)
            } catch (e: Exception) {
                Log.e(TAG, "Error during meter update", e)
            }
        }
    }

    private fun cleanup() {
        isRecording = false
        handler.removeCallbacks(updateRunnable)
        
        try {
            mediaRecorder?.apply {
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder", e)
        }
        mediaRecorder = null
    }

    fun isRecording(): Boolean = isRecording

    companion object {
        private const val TAG = "RecorderEngineAndroid"
    }
}
