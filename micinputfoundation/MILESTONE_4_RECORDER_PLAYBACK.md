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
- **Duration**: Emits real-time recording duration in seconds using `SystemClock.elapsedRealtime()` for monotonic accuracy.
- **Safety**: 
    - **Guarded Stop**: `MediaRecorder.stop()` is wrapped in logic to prevent crashes if recording is too short or already stopped.
    - **State Management**: Returns `null` on `stopRecording()` if no session is active.
    - Ensures `MediaRecorder` resources are released properly to prevent state corruption.

## 2. Playback Engine (`PlaybackEngineAndroid.kt`)

The `PlaybackEngineAndroid` provides a robust audio player using the `MediaPlayer` API with support for virtual trimming.

- **Core Features**: Load, Play, Pause, Stop, and Seek.
- **Virtual Trimming**: 
    - Supports `trimStart` and `trimEnd` metadata (passed during `load`).
    - **Sanitization**: Automatically validates trim bounds (e.g., `trimStart < trimEnd`, `trimEnd <= fileDuration`).
    - **Relative Progress**: All `currentTime` and `duration` emissions are relative to the virtual trim window (`currentTime = position - trimStart`).
    - **Logic**: On play, it seeks to `trimStart`. During playback, a polling mechanism checks if `currentTime >= trimEnd` and automatically pauses playback if reached.
- **Events**: Emits relative `currentTime` and `duration` every 150ms.
- **States**: Emits state changes: `loaded`, `playing`, `paused`, `stopped`, `completed`, `error`.
- **Interruption Handling**: `stop()` resets the position to `trimStart`.

## 3. TurboModule Integration (Split Architecture)

The integration layer bridges the native Kotlin engines to React Native using a split module architecture for better lifecycle management and parity with iOS.

### Recorder Module (`RecorderModuleAndroid.kt`)
- **Methods**:
    - `startRecording(filePath: String)`: Starts recording. Automatically stops any active playback.
    - `stopRecording()`: Returns a Promise resolving to the finalized file path (or `null`).
- **Events**:
    - `Recorder:onMeter`: `{ rmsDb, peakDb }`
    - `Recorder:onDuration`: `{ duration }`
    - `Recorder:onState`: `{ state }`
    - `Recorder:onError`: `{ message }`

### Playback Module (`PlaybackModuleAndroid.kt`)
- **Methods**:
    - `load(filePath: String, options: { trimStart, trimEnd })`: Prepares a file.
    - `play(options?)`: Starts/resumes playback (respecting trim bounds).
    - `pause()`: Pauses playback.
    - `stop()`: Stops and resets to `trimStart`.
    - `seek(positionInSeconds: Double)`: Jumps to a relative time within the trim window.
- **Events**:
    - `Playback:onPosition`: `{ currentTime, duration }`
    - `Playback:onState`: `{ state }`
    - `Playback:onError`: `{ message }`

### Orchestration Rules
- **Recording Priority**: If `startRecording` is called while playback is active, the playback engine is stopped immediately.
- **Audio Focus**: Modules handle `AudioManager` focus requests to ensure clean audio sessions.
- **Event Guards**: Uses `listenerCount` to avoid unnecessary bridge traffic when no JS listeners are active.

## Files Created/Modified
- `micinputfoundation/src/main/java/com/realtimeaudio/RecorderEngineAndroid.kt` [REFINED]
- `micinputfoundation/src/main/java/com/realtimeaudio/PlaybackEngineAndroid.kt` [REFINED]
- `micinputfoundation/src/main/java/com/realtimeaudio/RecorderModuleAndroid.kt` [NEW]
- `micinputfoundation/src/main/java/com/realtimeaudio/PlaybackModuleAndroid.kt` [NEW]
- `micinputfoundation/src/main/java/com/realtimeaudio/RealtimeAudioAnalyzerPackage.kt` [MODIFIED]
- `micinputfoundation/src/main/java/com/realtimeaudio/AudioModule.kt` [DELETED]
