package com.realtimeaudio.demoapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.realtimeaudio.MicInputAndroid
import com.realtimeaudio.MicInputConfig
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private val totalFramesReceived = AtomicLong(0L)

    private var micInput: MicInputAndroid? = null
    private var activeConfig: MicInputConfig? = null
    private var permissionDenied: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTickMs = 250L
    private val uiRunnable =
            object : Runnable {
                override fun run() {
                    val frames = totalFramesReceived.get()
                    textTotalFrames.text = "totalFramesReceived: $frames"
                    uiHandler.postDelayed(this, uiTickMs)
                }
            }

    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button
    private lateinit var textPermissionStatus: TextView
    private lateinit var textSampleRate: TextView
    private lateinit var textChannels: TextView
    private lateinit var textBufferSize: TextView
    private lateinit var textTotalFrames: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)
        textPermissionStatus = findViewById(R.id.textPermissionStatus)
        textSampleRate = findViewById(R.id.textSampleRate)
        textChannels = findViewById(R.id.textChannels)
        textBufferSize = findViewById(R.id.textBufferSize)
        textTotalFrames = findViewById(R.id.textTotalFrames)

        buttonStart.setOnClickListener { onStartClicked() }
        buttonStop.setOnClickListener { onStopClicked() }

        renderConfig(null)
        renderPermissionUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUiTimer()
        micInput?.stop()
        micInput = null
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
        )
    }

    private fun renderPermissionUi() {
        val granted = hasRecordAudioPermission()
        textPermissionStatus.text =
                if (granted) {
                    "Permission: granted"
                } else if (permissionDenied) {
                    "Permission: denied"
                } else {
                    "Permission: required"
                }
        buttonStart.isEnabled =
                (granted && (micInput?.isRunning() != true)) || (!granted && !permissionDenied)
        buttonStop.isEnabled = micInput?.isRunning() == true
    }

    private fun renderConfig(config: MicInputConfig?) {
        if (config == null) {
            textSampleRate.text = "sampleRate: -"
            textChannels.text = "channels: -"
            textBufferSize.text = "bufferSize: -"
            return
        }
        textSampleRate.text = "sampleRate: ${config.sampleRate}"
        textChannels.text = "channels: ${config.channels}"
        textBufferSize.text = "bufferSize: ${config.bufferSize}"
    }

    private fun onStartClicked() {
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            renderPermissionUi()
            return
        }

        if (micInput?.isRunning() == true) return

        totalFramesReceived.set(0L)

        val config = MicInputConfig(sampleRate = 48_000, channels = 1, bufferSize = 1024)
        activeConfig = config
        renderConfig(config)

        val mic = MicInputAndroid()
        micInput = mic

        mic.start(config) { _, frameCount ->
            // STRICT: no allocations, no logging, no locks in callback.
            totalFramesReceived.addAndGet(frameCount.toLong())
        }

        startUiTimer()
        renderPermissionUi()
    }

    private fun onStopClicked() {
        stopUiTimer()
        micInput?.stop()
        micInput = null
        renderPermissionUi()
    }

    private fun startUiTimer() {
        uiHandler.removeCallbacks(uiRunnable)
        uiHandler.postDelayed(uiRunnable, uiTickMs)
    }

    private fun stopUiTimer() {
        uiHandler.removeCallbacks(uiRunnable)
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            permissionDenied = !hasRecordAudioPermission()
            renderPermissionUi()
        }
    }

    private companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }
}
