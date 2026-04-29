package com.realtimeaudio

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.util.Log

class AudioModule(
    reactContext: ReactApplicationContext
) : NativeAudioModuleSpec(reactContext) {

    companion object {
        const val NAME = "AudioModule"
    }

    private val recorderEngine = RecorderEngineAndroid(
        onMeterUpdate = { rmsDb, peakDb -> emitRecorderMeter(rmsDb, peakDb) },
        onDurationUpdate = { duration -> emitRecorderDuration(duration) }
    )

    private val playbackEngine = PlaybackEngineAndroid(
        onPositionUpdate = { currentTime, duration -> emitPlaybackPosition(currentTime, duration) },
        onStateChange = { state -> emitPlaybackState(state) }
    )

    override fun getName(): String = NAME

    // --- Recorder Methods ---

    override fun startRecording(filePath: String) {
        try {
            // Requirement: Recording start stops playback immediately
            playbackEngine.stop()
            recorderEngine.startRecording(filePath)
        } catch (e: Exception) {
            Log.e(NAME, "Failed to start recording", e)
        }
    }

    override fun stopRecording(promise: Promise) {
        try {
            val path = recorderEngine.stopRecording()
            promise.resolve(path)
        } catch (e: Exception) {
            promise.reject("E_STOP_REC", "Failed to stop recording", e)
        }
    }

    // --- Playback Methods ---

    override fun load(filePath: String, options: ReadableMap?) {
        val trimStart = options?.let { if (it.hasKey("trimStart")) it.getDouble("trimStart") else 0.0 } ?: 0.0
        val trimEnd = options?.let { if (it.hasKey("trimEnd")) it.getDouble("trimEnd") else 0.0 } ?: 0.0
        playbackEngine.load(filePath, trimStart, trimEnd)
    }

    override fun play(options: ReadableMap?) {
        playbackEngine.play()
    }

    override fun pause() {
        playbackEngine.pause()
    }

    override fun stop() {
        playbackEngine.stop()
    }

    override fun seek(positionInSeconds: Double) {
        playbackEngine.seek(positionInSeconds)
    }

    // --- Event Emission ---

    private fun emitRecorderMeter(rmsDb: Double, peakDb: Double) {
        if (!reactApplicationContext.hasActiveReactInstance()) return
        
        val payload = Arguments.createMap().apply {
            putDouble("rmsDb", rmsDb)
            putDouble("peakDb", peakDb)
        }
        
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onRecorderMeter", payload)
    }

    private fun emitRecorderDuration(duration: Double) {
        if (!reactApplicationContext.hasActiveReactInstance()) return
        
        val payload = Arguments.createMap().apply {
            putDouble("duration", duration)
        }
        
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onRecorderDuration", payload)
    }

    private fun emitPlaybackPosition(currentTime: Double, duration: Double) {
        if (!reactApplicationContext.hasActiveReactInstance()) return
        
        val payload = Arguments.createMap().apply {
            putDouble("currentTime", currentTime)
            putDouble("duration", duration)
        }
        
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onPlaybackPosition", payload)
    }

    private fun emitPlaybackState(state: PlaybackState) {
        if (!reactApplicationContext.hasActiveReactInstance()) return
        
        val payload = Arguments.createMap().apply {
            putString("state", state.value)
        }
        
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onPlaybackState", payload)
    }

    override fun addListener(eventName: String) {}
    override fun removeListeners(count: Double) {}
}
