#include "kiss_fft/kiss_fftr.h"
#include <cmath>
#include <cstring>
#include <jni.h>
#include <vector>

// Global state for reuse
static kiss_fftr_cfg cfg = nullptr;
static int current_nfft = 0;
static std::vector<float> window;
static std::vector<kiss_fft_cpx> fft_out;
static std::vector<float> fft_in; // To hold windowed input

extern "C" JNIEXPORT void JNICALL Java_com_realtimeaudio_AudioEngine_computeFft(
    JNIEnv *env, jobject thiz, jfloatArray input, jfloatArray output,
    jint nfft) {
  if (nfft <= 0)
    return;

  // 1. Reallocate configuration if size changed
  if (cfg == nullptr || current_nfft != nfft) {
    if (cfg)
      free(cfg);
    cfg = kiss_fftr_alloc(nfft, 0, nullptr, nullptr);
    if (cfg == nullptr) {
      // FFT allocation failed
      return;
    }
    current_nfft = nfft;

    // Precompute Hann Window
    window.resize(nfft);
    fft_in.resize(nfft);
    fft_out.resize(nfft / 2 + 1); // Real FFT output size

    for (int i = 0; i < nfft; ++i) {
      window[i] = 0.5f * (1.0f - cosf(2.0f * M_PI * i / (nfft - 1)));
    }
  }

  // 2. Get input data
  jfloat *inData = env->GetFloatArrayElements(input, nullptr);
  jfloat *outData = env->GetFloatArrayElements(output, nullptr);
  
  if (inData == nullptr || outData == nullptr) {
    if (inData != nullptr) env->ReleaseFloatArrayElements(input, inData, 0);
    if (outData != nullptr) env->ReleaseFloatArrayElements(output, outData, 0);
    return;
  }
  
  if (cfg == nullptr) {
    env->ReleaseFloatArrayElements(input, inData, 0);
    env->ReleaseFloatArrayElements(output, outData, 0);
    return;
  }

  // 3. Apply Window
  for (int i = 0; i < nfft; ++i) {
    fft_in[i] = inData[i] * window[i];
  }

  // 4. Perform FFT
  kiss_fftr(cfg, fft_in.data(), fft_out.data());

  // 5. Compute Magnitude (and normalize)
  // Output size is expected to be sufficient (usually passed as nfft/2 in Java,
  // but here we just fill what we can) The user requested: "normalize FFT
  // output to range 0.0 – 1.0" For normalization, usually we divide by N/2, but
  // we also want generic 0-1 range. Let's assume standard magnitude
  // normalization.

  // We only output the first nfft/2 bins (Nyquist excluded usually or just
  // nfft/2) The JNI signature implies we fill 'output'.
  jsize outSize = env->GetArrayLength(output);
  int bins = std::min((int)outSize, (int)(nfft / 2));

  for (int i = 0; i < bins; ++i) {
    float re = fft_out[i].r;
    float im = fft_out[i].i;
    float mag = sqrtf(re * re + im * im);

    // Normalize
    // Simple normalization: mag / (nfft / 2)
    // Note: KissFFT is unnormalized. Forward transform sums.
    outData[i] = mag / (float)(nfft / 2);
  }

  // 6. Release arrays
  env->ReleaseFloatArrayElements(input, inData, 0);
  env->ReleaseFloatArrayElements(output, outData, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_realtimeaudio_AudioEngine_cleanupFft(JNIEnv *env, jobject thiz) {
  if (cfg) {
    free(cfg);
    cfg = nullptr;
  }
  current_nfft = 0;
}
