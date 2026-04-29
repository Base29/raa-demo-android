# Milestone 4: Native Android Recorder and Playback Implementation

This document details the native Kotlin implementation for the audio recorder and playback engines for Android, designed for use via a React Native TurboModule bridge.

## 1. Recorder Engine (`RecorderEngineAndroid.kt`)

The `RecorderEngineAndroid` handles high-quality mono audio recording using the `MediaRecorder` API.

- **Audio Format**: AAC (MPEG-4 AAC)
- **Container**: `.m4a`
- **Sample Rate**: 44.1 kHz
- **Channels**: 1 (Mono)
- **Bitrate**: 128 kbps
- **Metering**: 
    - Emits `rmsDb` and `peakDb` values every 100ms.
    - Uses `maxAmplitude` to derive dB values relative to full scale (dBFS).
- **Duration**: Emits real-time recording duration in seconds.
- **Safety**: 
    - Handles safe file finalization on manual stop or unexpected errors.
    - Ensures `MediaRecorder` resources are released properly to prevent state corruption.

## 2. Playback Engine (`PlaybackEngineAndroid.kt`)

The `PlaybackEngineAndroid` provides a robust audio player using the `MediaPlayer` API with support for virtual trimming.

- **Core Features**: Load, Play, Pause, Stop, and Seek.
- **Virtual Trimming**: 
    - Supports `trimStart` and `trimEnd` metadata (passed during `load` or `play`).
    - **No Modification**: The original file is never modified, sliced, or re-encoded.
    - **Logic**: On play, it seeks to `trimStart`. During playback, a polling mechanism checks if `currentTime >= trimEnd` and automatically pauses/stops playback if reached.
- **Events**: Emits `currentTime` and `duration` every 150ms.
- **States**: Emits state changes: `loaded`, `playing`, `paused`, `stopped`, `completed`, `error`.
- **Interruption Handling**: Safely handles stop and reset operations.

## 3. TurboModule Integration (`AudioModule.kt`)

The integration layer bridges the native Kotlin engines to React Native using the TurboModule architecture.

### JS Methods Exposed
- **Recorder**:
    - `startRecording(filePath: String)`: Starts recording to the specified path. Automatically stops any active playback.
    - `stopRecording()`: Returns a Promise resolving to the finalized file path.
- **Playback**:
    - `load(filePath: String, options: { trimStart, trimEnd })`: Prepares a file for playback.
    - `play(options?)`: Starts or resumes playback (respecting trim bounds).
    - `pause()`: Pauses playback.
    - `stop()`: Stops playback and resets position to zero.
    - `seek(positionInSeconds: Double)`: Jumps to a specific time.

### Events Emitted to JS
- `onRecorderMeter`: `{ rmsDb: Double, peakDb: Double }`
- `onRecorderDuration`: `{ duration: Double }`
- `onPlaybackPosition`: `{ currentTime: Double, duration: Double }`
- `onPlaybackState`: `{ state: String }`

### Orchestration Rules
- **Recording Priority**: If `startRecording` is called while playback is active, the playback engine is stopped immediately to prevent audio feedback or session conflicts.
- **Single Instance**: Engines are managed as instances within the module, ensuring consistent state across the application.

## Files Created/Modified
- `micinputfoundation/src/main/java/com/realtimeaudio/RecorderEngineAndroid.kt` [NEW]
- `micinputfoundation/src/main/java/com/realtimeaudio/PlaybackEngineAndroid.kt` [NEW]
- `micinputfoundation/src/main/java/com/realtimeaudio/AudioModule.kt` [NEW]
- `micinputfoundation/src/main/java/com/realtimeaudio/RealtimeAudioAnalyzerPackage.kt` [MODIFIED]
