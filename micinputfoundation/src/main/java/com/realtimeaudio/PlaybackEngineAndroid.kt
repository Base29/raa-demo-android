package com.realtimeaudio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException

enum class PlaybackState(val value: String) {
    LOADED("loaded"),
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
    COMPLETED("completed"),
    ERROR("error")
}

class PlaybackEngineAndroid(
    private val onPositionUpdate: (currentTime: Double, duration: Double) -> Unit,
    private val onStateChange: (state: PlaybackState) -> Unit
) {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var trimStart: Double = 0.0
    private var trimEnd: Double = 0.0
    private var duration: Double = 0.0
    
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                updatePosition()
                handler.postDelayed(this, 150)
            }
        }
    }

    fun load(filePath: String, trimStart: Double = 0.0, trimEnd: Double = 0.0) {
        stop() // Reset existing player
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                this@PlaybackEngineAndroid.duration = duration.toDouble() / 1000.0
                this@PlaybackEngineAndroid.trimStart = trimStart
                this@PlaybackEngineAndroid.trimEnd = if (trimEnd > 0) trimEnd else this@PlaybackEngineAndroid.duration
                
                setOnCompletionListener {
                    handleCompletion()
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    onStateChange(PlaybackState.ERROR)
                    true
                }
            }
            
            onStateChange(PlaybackState.LOADED)
            // Report initial position
            onPositionUpdate(trimStart, duration)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audio file", e)
            onStateChange(PlaybackState.ERROR)
        }
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (isPlaying) return

        try {
            // If we are at the beginning or past trimEnd, seek to trimStart
            val currentPos = player.currentPosition / 1000.0
            if (currentPos < trimStart || currentPos >= trimEnd) {
                player.seekTo((trimStart * 1000).toInt())
            }

            player.start()
            isPlaying = true
            onStateChange(PlaybackState.PLAYING)
            handler.post(updateRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play", e)
            onStateChange(PlaybackState.ERROR)
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (!isPlaying) return

        try {
            player.pause()
            isPlaying = false
            onStateChange(PlaybackState.PAUSED)
            handler.removeCallbacks(updateRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(updateRunnable)
        isPlaying = false
        
        mediaPlayer?.apply {
            try {
                if (this.isPlaying) {
                    this.stop()
                }
                this.seekTo(0)
                // We don't release here unless we want to "load" again, 
                // but usually stop means back to start.
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaPlayer", e)
            }
        }
        onStateChange(PlaybackState.STOPPED)
        onPositionUpdate(0.0, duration)
    }

    fun seek(positionInSeconds: Double) {
        val player = mediaPlayer ?: return
        try {
            player.seekTo((positionInSeconds * 1000).toInt())
            onPositionUpdate(positionInSeconds, duration)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seek", e)
        }
    }

    private fun updatePosition() {
        mediaPlayer?.let { player ->
            try {
                val currentPos = player.currentPosition / 1000.0
                
                // Virtual trimming check
                if (trimEnd > 0 && currentPos >= trimEnd) {
                    player.pause()
                    player.seekTo((trimEnd * 1000).toInt()) // Snap to end
                    isPlaying = false
                    onStateChange(PlaybackState.COMPLETED)
                    handler.removeCallbacks(updateRunnable)
                    onPositionUpdate(trimEnd, duration)
                } else {
                    onPositionUpdate(currentPos, duration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating position", e)
            }
        }
    }

    private fun handleCompletion() {
        isPlaying = false
        handler.removeCallbacks(updateRunnable)
        onStateChange(PlaybackState.COMPLETED)
        onPositionUpdate(duration, duration)
    }

    fun release() {
        stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "PlaybackEngineAndroid"
    }
}
