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
    private val onStateChange: (state: PlaybackState) -> Unit,
    private val onError: (message: String) -> Unit
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
                val fileDuration = duration.toDouble() / 1000.0
                this@PlaybackEngineAndroid.duration = fileDuration
                
                // Fix 4: Sanitize trimStart / trimEnd
                var sanitizedStart = if (trimStart >= 0) trimStart else 0.0
                val sanitizedEnd = if (trimEnd > 0 && trimEnd <= fileDuration) trimEnd else fileDuration
                
                if (sanitizedStart >= sanitizedEnd) {
                    sanitizedStart = 0.0
                }
                
                this@PlaybackEngineAndroid.trimStart = sanitizedStart
                this@PlaybackEngineAndroid.trimEnd = sanitizedEnd
                
                setOnCompletionListener {
                    handleCompletion()
                }
                
                setOnErrorListener { _, what, extra ->
                    val msg = "MediaPlayer error: what=$what, extra=$extra"
                    Log.e(TAG, msg)
                    onError(msg)
                    onStateChange(PlaybackState.ERROR)
                    true
                }
            }
            
            onStateChange(PlaybackState.LOADED)
            // Report initial position (relative to trim)
            onPositionUpdate(0.0, this.trimEnd - this.trimStart)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audio file", e)
            onError("Failed to load: ${e.message}")
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
                // Fix 3: Reset to trimStart, not 0
                this.seekTo((trimStart * 1000).toInt())
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaPlayer", e)
            }
        }
        onStateChange(PlaybackState.STOPPED)
        // Relative progress: 0.0 at trimStart
        onPositionUpdate(0.0, trimEnd - trimStart)
    }

    fun seek(positionInSeconds: Double) {
        val player = mediaPlayer ?: return
        try {
            // Fix 2: Clamp and make relative to trim
            val clampedPos = positionInSeconds.coerceIn(0.0, trimEnd - trimStart)
            val absolutePos = trimStart + clampedPos
            
            player.seekTo((absolutePos * 1000).toInt())
            onPositionUpdate(clampedPos, trimEnd - trimStart)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seek", e)
            onError("Seek failed: ${e.message}")
        }
    }

    private fun updatePosition() {
        mediaPlayer?.let { player ->
            try {
                val currentPos = player.currentPosition / 1000.0
                val relativePos = (currentPos - trimStart).coerceAtLeast(0.0)
                val relativeDuration = trimEnd - trimStart
                
                // Virtual trimming check
                if (trimEnd > 0 && currentPos >= trimEnd) {
                    player.pause()
                    player.seekTo((trimEnd * 1000).toInt()) // Snap to end
                    isPlaying = false
                    
                    // Fix 5: Emit final relative progress before "completed"
                    onPositionUpdate(relativeDuration, relativeDuration)
                    onStateChange(PlaybackState.COMPLETED)
                    handler.removeCallbacks(updateRunnable)
                } else {
                    // Fix 1: Progress must be relative to trim
                    onPositionUpdate(relativePos, relativeDuration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating position", e)
            }
        }
    }

    private fun handleCompletion() {
        isPlaying = false
        handler.removeCallbacks(updateRunnable)
        // Fix 5: Emit final relative progress before "completed"
        onPositionUpdate(trimEnd - trimStart, trimEnd - trimStart)
        onStateChange(PlaybackState.COMPLETED)
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
