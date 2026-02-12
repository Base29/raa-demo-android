# Android Milestone 2

This document explains what was implemented and how it matches the expectations for Milestone 2 on Android.

## What was done (implementation summary)

The missing runtime wiring was implemented by updating `AudioEngine` so it performs actual:

- **RMS/peak level analysis** using `RealtimeMicProcessor`
- **FFT spectrum analysis** using `RealtimeFFTEngine` (KissFFT via JNI)

The results are packaged into `AudioEngine.AudioData` and delivered to React Native via `RealtimeAudioAnalyzerModule`, using the existing event `"RealtimeAudioAnalyzer:onData"`.

## Where to find each required component

### 1) Realtime analyzer (mic input → FFT engine connection)

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/AudioEngine.kt`

`AudioEngine` captures microphone PCM with `AudioRecord`, converts it to float samples, and (on the background processing path) runs:

- `RealtimeMicProcessor.processBuffer(...)` for RMS/peak
- `RealtimeFFTEngine.processBuffer(...)` for FFT magnitudes

**Key implementation points:**

- Capture + float conversion: `AudioEngine.processAudio()`
- Analysis (RMS/peak + FFT) and packaging into `AudioData`: `AudioEngine.processAudioData(...)`
- DSP components are held as fields on `AudioEngine`:
  - `private val micProcessor: RealtimeMicProcessor`
  - `private val fftEngine: RealtimeFFTEngine`
- Dynamic configuration routes through to the real analyzers:
  - `AudioEngine.setSmoothing(...)` → `micProcessor.configure(...)`
  - `AudioEngine.setFftConfig(...)` → `fftEngine.configure(...)`

**Result:** There is now a concrete, runtime mic → analyzer → FFT path (not just an integration example).

### 2) Refresh-rate–limited spectrum output

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/AudioEngine.kt`

Refresh-rate limiting happens in the audio capture loop:

- `callbackRateHz` is provided to `AudioEngine.start(...)` (currently 30 Hz from the RN module).
- The loop computes `updateIntervalMs = 1000 / callbackRateHz`.
- `processAudioData(...)` is only scheduled when `now - lastCallbackTime >= updateIntervalMs`.

Because FFT computation happens inside `processAudioData(...)`, the **FFT output is automatically rate-limited** to the same refresh rate before it ever reaches the JS bridge.

**Notes:**

- The FFT engine (`RealtimeFFTEngine`) is not responsible for throttling; it runs when called.
- Throttling is intentionally centralized in `AudioEngine` to limit **all** outbound updates (levels + spectrum) consistently.

### 3) Bridge / event exposed to the JS layer

**Locations:**

- `micinputfoundation/src/main/java/com/realtimeaudio/RealtimeAudioAnalyzerModule.kt`
- `micinputfoundation/src/main/java/com/realtimeaudio/RealtimeAudioAnalyzerPackage.kt`

`RealtimeAudioAnalyzerModule` is the React Native TurboModule bridge. It:

- starts/stops analysis by calling into `AudioEngine`
- emits `"RealtimeAudioAnalyzer:onData"` events to JS using `DeviceEventManagerModule.RCTDeviceEventEmitter`

**Event name:**

- `"RealtimeAudioAnalyzer:onData"`

**Payload fields (as emitted to JS):**

- `timestamp`
- `volume` (RMS)
- `peak`
- `sampleRate`
- `fftSize`
- `frequencyData` (FFT magnitudes)
- `timeData` (optional, if enabled)

## End-to-end data flow (now wired)

1. **JS calls** `startAnalysis(...)`  
   **File:** `RealtimeAudioAnalyzerModule.kt`  
   This calls `AudioEngine.start(...)` with:
   - `fftSize` (buffer size)
   - `sampleRate`
   - `callbackRateHz = 30` (refresh limiter)
   - `emitFft = true`
   - `emitTimeData` based on config

2. **Mic capture** (Android `AudioRecord`)  
   **File:** `AudioEngine.kt`  
   The capture loop reads PCM16 into a `ShortArray`, converts to `FloatArray` samples in \([-1, 1]\).

3. **Refresh-rate limiting**  
   **File:** `AudioEngine.kt`  
   Updates are limited by `callbackRateHz` before scheduling analysis.

4. **Analysis: RMS/peak + FFT**  
   **Files:**
   - `AudioEngine.kt` (orchestration)
   - `MicProcessor.kt` (`RealtimeMicProcessor`)
   - `FFTEngine.kt` (`RealtimeFFTEngine`, JNI KissFFT)

5. **Bridge event emission**  
   **File:** `RealtimeAudioAnalyzerModule.kt`  
   The module emits `"RealtimeAudioAnalyzer:onData"` to JS with both level and spectrum data.

## Why this satisfies expectations

- **Realtime analyzer present:** The runtime path now clearly shows microphone samples being processed by the FFT engine and level processor in `AudioEngine.processAudioData(...)`.
- **Refresh-rate–limited spectrum output:** The spectrum output is produced only on the refresh-limited cadence enforced by `AudioEngine` (30 Hz by default from the module).
- **JS bridge/event present:** The TurboModule exposes `startAnalysis/stopAnalysis` and emits `"RealtimeAudioAnalyzer:onData"` carrying `frequencyData` and `timeData`.

## Quick pointers for review

- **Mic → FFT wiring:** `AudioEngine.kt` (`processAudio()` → `processAudioData()` → `fftEngine.processBuffer(...)`)
- **Refresh limiter:** `AudioEngine.kt` (`callbackRateHz`, `updateIntervalMs`, `lastCallbackTime`)
- **JS event:** `RealtimeAudioAnalyzerModule.kt` (`emit("RealtimeAudioAnalyzer:onData", ...)`)

