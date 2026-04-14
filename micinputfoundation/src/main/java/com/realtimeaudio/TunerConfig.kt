package com.realtimeaudio

/**
 * Shared tuning parameters intended to match iOS behavior exactly.
 *
 * Keep all "core tuning" knobs centralized here so both platforms can stay in lockstep.
 */
object TunerConfig {
    // --- Detection range ---
    // Supports low bass notes (e.g., E1 ≈ 41 Hz) with headroom.
    const val MIN_DETECTABLE_FREQUENCY_HZ: Double = 30.0

    // --- YIN ---
    // iOS parity: threshold around 0.15 is the baseline.
    const val YIN_THRESHOLD: Double = 0.15

    // --- Stability window (adaptive) ---
    const val STABLE_WINDOW_MIN_SEC: Double = 0.080
    const val STABLE_WINDOW_MAX_SEC: Double = 0.150

    // Used by stability logic to define "stable" cents spread within window.
    const val STABLE_MAX_CENTS_VARIATION: Double = 3.0

    // --- Smoothing (adaptive) ---
    // Alpha is chosen dynamically each frame within these bounds.
    const val SMOOTHING_ALPHA_MIN: Double = 0.08
    const val SMOOTHING_ALPHA_MAX: Double = 0.35

    // --- Silence / noise floor ---
    // The absolute floor prevents thresholds from drifting too low in very quiet environments.
    const val NOISE_FLOOR_MIN_DBFS: Double = -90.0
    // Track background when no reliable pitch is present.
    const val NOISE_FLOOR_ALPHA: Double = 0.05
    // Consider "silence" when level is within this margin of the estimated noise floor.
    const val SILENCE_MARGIN_DB: Double = 10.0
    // Also keep a hard clamp for very loud environments to avoid treating real signal as silence.
    const val SILENCE_THRESHOLD_MAX_DBFS: Double = -25.0

    // --- Stable-mode dead zone ---
    // Suppress tiny jitter once stable.
    const val STABLE_DEAD_ZONE_CENTS: Double = 1.5

    // --- FFT verification ---
    // Only used as a lightweight confirmation layer.
    const val FFT_BIN_NEIGHBORHOOD: Int = 1
    const val FFT_SUPPORT_MIN_RATIO: Double = 1.25

    // --- Confidence composition weights (sum to 1.0) ---
    const val CONF_W_CMND: Double = 0.55
    const val CONF_W_ENERGY: Double = 0.20
    const val CONF_W_HARMONIC: Double = 0.15
    const val CONF_W_FFT: Double = 0.10
}

