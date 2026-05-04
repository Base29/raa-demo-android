package com.realtimeaudio

import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class RealtimeAudioAnalyzerPackage : TurboReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return when (name) {
      RealtimeAudioAnalyzerModule.NAME -> RealtimeAudioAnalyzerModule(reactContext)
      RecorderModuleAndroid.NAME -> RecorderModuleAndroid(reactContext)
      PlaybackModuleAndroid.NAME -> PlaybackModuleAndroid(reactContext)
      else -> null
    }
  }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
    return ReactModuleInfoProvider {
      mapOf(
        RealtimeAudioAnalyzerModule.NAME to ReactModuleInfo(
          RealtimeAudioAnalyzerModule.NAME,
          RealtimeAudioAnalyzerModule.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // hasConstants
          false, // isCxxModule
          true   // isTurboModule
        ),
        RecorderModuleAndroid.NAME to ReactModuleInfo(
          RecorderModuleAndroid.NAME,
          RecorderModuleAndroid.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // hasConstants
          false, // isCxxModule
          true   // isTurboModule
        ),
        PlaybackModuleAndroid.NAME to ReactModuleInfo(
          PlaybackModuleAndroid.NAME,
          PlaybackModuleAndroid.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // hasConstants
          false, // isCxxModule
          true   // isTurboModule
        )
      )
    }
  }
}