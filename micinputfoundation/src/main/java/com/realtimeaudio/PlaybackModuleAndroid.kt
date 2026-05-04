package com.realtimeaudio

import android.content.Context
import android.media.AudioManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.util.Log

class PlaybackModuleAndroid(
    reactContext: ReactApplicationContext
) : NativePlaybackModuleSpec(reactContext) {

    companion object {
        const val NAME = "PlaybackModuleAndroid"
        private var instance: PlaybackModuleAndroid? = null
        fun getInstance(): PlaybackModuleAndroid? = instance
    }

    private var listenerCount = 0
    private val playbackEngine = PlaybackEngineAndroid(
        onPositionUpdate = { currentTime, duration -> emit("Playback:onPosition", Arguments.createMap().apply {
            putDouble("currentTime", currentTime)
            putDouble("duration", duration)
        }) },
        onStateChange = { state -> emit("Playback:onState", Arguments.createMap().apply {
            putString("state", state.value)
        }) },
        onError = { message -> emit("Playback:onError", Arguments.createMap().apply {
            putString("message", message)
        }) }
    )

    private val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        instance = this
    }

    override fun getName(): String = NAME

    override fun load(filePath: String, options: ReadableMap?) {
        val trimStart = options?.let { if (it.hasKey("trimStart")) it.getDouble("trimStart") else 0.0 } ?: 0.0
        val trimEnd = options?.let { if (it.hasKey("trimEnd")) it.getDouble("trimEnd") else 0.0 } ?: 0.0
        playbackEngine.load(filePath, trimStart, trimEnd)
    }

    override fun play(options: ReadableMap?) {
        audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        playbackEngine.play()
    }

    override fun pause() {
        playbackEngine.pause()
        audioManager.abandonAudioFocus(null)
    }

    override fun stop() {
        playbackEngine.stop()
        audioManager.abandonAudioFocus(null)
    }

    override fun seek(positionInSeconds: Double) {
        playbackEngine.seek(positionInSeconds)
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
        playbackEngine.release()
        instance = null
    }
}
