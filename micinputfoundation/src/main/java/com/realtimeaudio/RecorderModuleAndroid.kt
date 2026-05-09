package com.realtimeaudio

import android.content.Context
import android.media.AudioManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.util.Log

class RecorderModuleAndroid(
    reactContext: ReactApplicationContext
) : NativeRecorderModuleSpec(reactContext) {

    companion object {
        const val NAME = "RecorderModuleAndroid"
        private var instance: RecorderModuleAndroid? = null
        fun getInstance(): RecorderModuleAndroid? = instance
    }

    private var listenerCount = 0
    private var isInvalidated = false
    private val recorderEngine = RecorderEngineAndroid(
        onMeterUpdate = { rmsDb, peakDb -> emit("Recorder:onMeter", Arguments.createMap().apply {
            putDouble("rmsDb", rmsDb)
            putDouble("peakDb", peakDb)
        }) },
        onDurationUpdate = { duration -> emit("Recorder:onDuration", Arguments.createMap().apply {
            putDouble("duration", duration)
        }) },
        onStateChange = { state -> emit("Recorder:onState", Arguments.createMap().apply {
            putString("state", state)
        }) },
        onError = { message -> emit("Recorder:onError", Arguments.createMap().apply {
            putString("message", message)
        }) }
    )

    private val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Requirement 2: Stop recording safely and emit "interrupted"
            recorderEngine.handleInterruption()
        }
    }

    init {
        instance = this
    }

    override fun getName(): String = NAME

    override fun startRecording(filePath: String, promise: Promise) {
        try {
            // Requirement 4: Recording start stops playback immediately using internal method
            PlaybackModuleAndroid.getInstance()?.stopInternal()
            
            // Requirement 2: Handle Audio Focus result
            val result = audioManager.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                promise.reject("E_FOCUS_DENIED", "Audio focus denied")
                emit("Recorder:onError", Arguments.createMap().apply {
                    putString("message", "Audio focus denied")
                })
                return
            }
            
            val success = recorderEngine.startRecording(filePath)
            if (success) {
                // Requirement 1: Resolve with filePath on success
                promise.resolve(filePath)
            } else {
                audioManager.abandonAudioFocus(afChangeListener)
                promise.reject("E_START_REC", "Failed to start recording")
            }
        } catch (e: Exception) {
            Log.e(NAME, "Failed to start recording", e)
            audioManager.abandonAudioFocus(afChangeListener)
            // Requirement 3: Reject the Promise and emit Recorder:onError
            promise.reject("E_START_REC", "Failed to start recording: ${e.message}", e)
            emit("Recorder:onError", Arguments.createMap().apply {
                putString("message", "Failed to start recording: ${e.message}")
            })
        }
    }

    override fun stopRecording(promise: Promise) {
        try {
            val path = recorderEngine.stopRecording()
            audioManager.abandonAudioFocus(afChangeListener)
            if (path != null) {
                promise.resolve(path)
            } else {
                promise.reject("E_NO_ACTIVE_RECORDING", "No active recording to stop")
            }
        } catch (e: Exception) {
            promise.reject("E_STOP_REC", "Failed to stop recording", e)
        }
    }

    @Synchronized
    private fun emit(eventName: String, payload: WritableMap) {
        if (isInvalidated) return
        if (listenerCount > 0 && reactApplicationContext.hasActiveReactInstance()) {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, payload)
        }
    }

    @Synchronized
    override fun addListener(eventName: String) {
        listenerCount++
    }

    @Synchronized
    override fun removeListeners(count: Double) {
        listenerCount -= count.toInt()
        if (listenerCount < 0) listenerCount = 0
    }

    @Synchronized
    override fun invalidate() {
        isInvalidated = true
        recorderEngine.stopRecording()
        // Requirement 5: Also abandon audio focus after stopping
        audioManager.abandonAudioFocus(afChangeListener)
        instance = null
        super.invalidate()
    }
}
