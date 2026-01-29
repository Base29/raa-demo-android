## Mic Input Foundation (Milestone 1)

This repo contains:

- `:micinputfoundation`: Android library module with **capture-only** microphone input using `AudioRecord`
- `:demoapp`: Demo Android app that starts/stops capture and shows a **frames received** counter

### Open in Android Studio

- Open Android Studio
- Select **Open**
- Choose the folder `raa-demo-android`

### Run

- Select the `demoapp` run configuration
- Run on a device/emulator with a microphone

### Permission notes

- The app requests `RECORD_AUDIO` at runtime.
- If you deny the permission, the UI shows **Permission: denied** and **Start stays disabled**.

### Expected behavior

- Tap **Start**
  - mic capture starts
  - the UI updates every ~250ms
  - `totalFramesReceived` increases while capture is running
- Tap **Stop**
  - mic capture stops
  - the UI timer stops

### Build validation

From the repo root:

```bash
./gradlew assembleDebug
```

