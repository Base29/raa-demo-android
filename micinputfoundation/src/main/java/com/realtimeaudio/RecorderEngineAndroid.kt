package com.realtimeaudio

import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.log10

class RecorderEngineAndroid(
    private val onMeterUpdate: (rmsDb: Double, peakDb: Double) -> Unit,
    private val onDurationUpdate: (duration: Double) -> Unit
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

            currentFilePath = filePath
            isRecording = true
            startTime = System.currentTimeMillis()
            handler.post(updateRunnable)
            Log.d(TAG, "Started recording to $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            throw e
        }
    }

    fun stopRecording(): String? {
        if (!isRecording) return currentFilePath

        val finalizedPath = currentFilePath
        cleanup()
        Log.d(TAG, "Stopped recording. File: $finalizedPath")
        return finalizedPath
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
                
                // For MediaRecorder, we don't have easy access to RMS. 
                // We'll use peakDb as rmsDb for V1 or a slightly offset value.
                // To keep it simple and deterministic:
                val rmsDb = peakDb 

                onMeterUpdate(rmsDb, peakDb)

                val duration = (System.currentTimeMillis() - startTime) / 1000.0
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
                stop()
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
