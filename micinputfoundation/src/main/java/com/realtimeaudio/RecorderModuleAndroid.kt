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

    init {
        instance = this
    }

    override fun getName(): String = NAME

    override fun startRecording(filePath: String) {
        try {
            // Requirement: Recording start stops playback immediately
            PlaybackModuleAndroid.getInstance()?.stop()
            
            // Handle Audio Focus
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            
            recorderEngine.startRecording(filePath)
        } catch (e: Exception) {
            Log.e(NAME, "Failed to start recording", e)
        }
    }

    override fun stopRecording(promise: Promise) {
        try {
            val path = recorderEngine.stopRecording()
            audioManager.abandonAudioFocus(null)
            promise.resolve(path)
        } catch (e: Exception) {
            promise.reject("E_STOP_REC", "Failed to stop recording", e)
        }
    }

    private fun emit(eventName: String, payload: WritableMap) {
        if (listenerCount > 0 && reactApplicationContext.hasActiveReactInstance()) {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, payload)
        }
    }

    override fun addListener(eventName: String) {
        listenerCount++
    }

    override fun removeListeners(count: Double) {
        listenerCount -= count.toInt()
        if (listenerCount < 0) listenerCount = 0
    }

    override fun invalidate() {
        super.invalidate()
        recorderEngine.stopRecording()
        instance = null
    }
}
