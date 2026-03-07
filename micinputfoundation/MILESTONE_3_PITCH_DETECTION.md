# Android Milestone 3 - Pitch Detection

This document briefly explains the native pitch detection implementation for Milestone 3 on Android and how it matches the tuner spec.

## What was done (implementation summary)

- **Native pitch engine (YIN-based)** implemented in `PitchDetectionEngine.kt`, fully DSP-side (no UI computation).
- **Stability logic + tuning state** implemented in `StabilityLogic.kt`, enforcing note validity and stability windows.
- **Note mapping with calibration** implemented in `NoteMapper.kt`, supporting adjustable A4 (e.g. 432–446 Hz).
- **Bridge wiring**: `AudioEngine.kt` now computes pitch from PCM and passes a `PitchResult` into `RealtimeAudioAnalyzerModule.kt`, which emits a dedicated JS pitch event.

## Core components and behavior

### 1) Pitch detection engine (YIN + smoothing)

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/PitchDetectionEngine.kt`

- Uses a **YIN-style algorithm**:
  - Difference function and **cumulative mean normalized difference (CMND)**.
  - Threshold search for the first CMND dip below `yinThreshold` (default ≈ 0.15).
  - **Parabolic interpolation** around the CMND minimum to refine `tau` and get sub-bin frequency.
- Works on a preallocated **ring buffer** plus scratch arrays (`difference`, `cmnd`, analysis buffer) to avoid per-frame allocations.
- Exposes:
  - `setCalibrationA4(hz: Double)` to change reference A4 (default 440 Hz).
  - `processFrame(frame: FloatArray, timestampSec: Double, inputLevelDbfs: Double? = null): PitchResult?`
  - `processFrame(frame: ShortArray, frameCount: Int, timestampSec: Double, inputLevelDbfs: Double? = null): PitchResult?`
- Applies **engine-level exponential smoothing** (\(\alpha \approx 0.15\)) on `detectedFrequency` **before** note mapping and emission, with:
  - Reset when entering silence.
  - Reset when there is a large jump (e.g. > 100 cents) to avoid excessive lag on note changes.

### 2) Stability logic and tuning rules

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/StabilityLogic.kt`

- Tracks a **candidate MIDI note**, a **window start time**, and **min/max cents** seen within the window.
- Applies the spec rules:
  - **Silence**: if `inputLevelDbfs < -50 dBFS`, all state resets and the engine reports `tuningState = "silence"` with no note.
  - **Note validity**: if `|centsOffset| > 50`, the note is considered unstable and the window resets.
  - **Stability**: note is stable only if:
    - Same MIDI note has been observed for **≥ 120 ms**, and
    - The cents range in that window is within **±3 cents**.
- When stable, `PitchDetectionEngine` returns a `PitchResult` with:
  - `detectedFrequency` (smoothed),
  - `noteName`, `octave`,
  - **centsOffset clamped to [-50, +50]**,
  - `confidence` (derived from CMND minimum vs threshold),
  - `inputLevel`,
  - `isStable = true`,
  - `tuningState` in `"inTune" | "near" | "outOfTune"`.
- When unstable (but not silent), `tuningState` is `"unstable"` and note-related fields are `null`.

### 3) Note mapping with calibration A4

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/NoteMapper.kt`

- Implements 12‑TET with adjustable A4:
  - \( \text{midi} = 69 + 12 \cdot \log_2(\frac{f}{A4}) \)
  - `nearest = round(midi)`
  - `centsOffset = 100 * (midi - nearest)`
  - `noteName` from `nearest % 12`, `octave = (nearest / 12) - 1`
- Calibration **only affects mapping**, not the raw frequency detection:
  - `map(freqHz: Double, a4Hz: Double): NoteInfo`

## Data flow and JS bridge integration

### 1) Mic input → native pitch engine

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/AudioEngine.kt`

On the background processing path (`processAudioData(...)`):

- Mic PCM is converted to `FloatArray` samples as part of the existing pipeline.
- `RealtimeMicProcessor` computes **RMS/peak**.
- An input level in **dBFS** is derived from RMS and passed into `PitchDetectionEngine.processFrame(...)`.
- The resulting `PitchResult` is attached to `AudioEngine.AudioData.pitch`.

### 2) Native → JS bridge (React Native)

**Location:** `micinputfoundation/src/main/java/com/realtimeaudio/RealtimeAudioAnalyzerModule.kt`

- Existing `"RealtimeAudioAnalyzer:onData"` event remains unchanged (FFT, time data, levels).
- A new event **`"RealtimeAudioAnalyzer:onPitch"`** is emitted whenever the engine has a `PitchResult`:
  - `detectedFrequency`
  - `noteName` (or `null` when unstable/silence)
  - `octave` (or `null`)
  - `centsOffset` (clamped \[-50, +50\], or `null`)
  - `confidence` \([0, 1]\)
  - `inputLevel` (dBFS, or `null`)
  - `isStable`
  - `tuningState` (`"inTune" | "near" | "outOfTune" | "unstable" | "silence"`)

The **UI never computes pitch**; it only consumes these emitted values to drive the tuner needle and status indicators.

## Tests and verification helpers

- **Note mapping tests:** `NoteMapperTest.kt`
  - A4=440 Hz maps to A4 with ~0 cents.
  - ~261.63 Hz maps to C4 within a few cents.
- **Offline YIN sanity check:** `PitchDetectionEngineOfflineTest.kt`
  - Generates sine waves at **E2 (82.41 Hz)**, **A2 (110 Hz)**, and **A4 (440 Hz)**.
  - Verifies detected fundamental frequencies are within a small error band of the expected values.

These pieces together satisfy Milestone 3 requirements: low-latency native pitch detection (YIN-based), engine-side smoothing and stability logic, calibration-aware note mapping, and a clean RN bridge emitting stable pitch data for the UI. 

