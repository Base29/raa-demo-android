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
    private var isInvalidated = false
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
    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Requirement 1: Stop safely and emit state/error
            playbackEngine.handleInterruption()
        }
    }

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
        if (!playbackEngine.isLoaded()) {
            emit("Playback:onError", Arguments.createMap().apply {
                putString("message", "Not loaded")
            })
            return
        }
        
        // Requirement 2: Check result of requestAudioFocus()
        val result = audioManager.requestAudioFocus(afChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            emit("Playback:onError", Arguments.createMap().apply {
                putString("message", "Audio focus denied")
            })
            return
        }
        playbackEngine.play()
    }

    override fun pause() {
        playbackEngine.pause()
        audioManager.abandonAudioFocus(afChangeListener)
    }

    override fun stop() {
        stopInternal()
    }

    // Requirement 4: Internal stop method for RecorderModuleAndroid
    fun stopInternal() {
        playbackEngine.stop()
        audioManager.abandonAudioFocus(afChangeListener)
    }

    override fun seek(positionInSeconds: Double) {
        playbackEngine.seek(positionInSeconds)
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
        super.invalidate()
        isInvalidated = true
        playbackEngine.release()
        // Requirement 3: Also abandon audio focus
        audioManager.abandonAudioFocus(afChangeListener)
        instance = null
    }
}
