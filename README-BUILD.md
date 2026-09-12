# Parallax Moment: Native Android build

This is a standard Android project with a native WebView host, JavaScript bridge, live wallpaper service, and fully offline depth-estimation path. The editor and offline AI assets are packaged directly from `www/` and Android assets.

## Requirements

- JDK 17
- Android SDK 35
- Android Studio Ladybug or newer, optional
- A real Android device for `connectedDebugAndroidTest`

## Build

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Offline MiDaS integration

The Kotlin adapter is `MidasDepthEstimator.kt`. Put the verified model at `android/app/src/main/assets/midas_small_256_fp16.tflite`. The model is loaded from the APK and never downloaded at runtime. `OfflineDepthBenchmark.kt` measures warm inference latency and memory delta. `MidasCompatibilityTest.kt` verifies model loading and finite output on a connected device.

```bash
cd android
./gradlew assembleDebug connectedDebugAndroidTest
```

See [`DEPTH-BENCHMARK.md`](DEPTH-BENCHMARK.md) for the model contract, device tiers, and result table.

## Architecture

- `www/index.html`: editor UI
- `www/imgly/`: offline subject-segmentation model and runtime
- `MidasDepthEstimator.kt`: offline relative-depth inference
- `OfflineDepthBenchmark.kt`: repeatable on-device timing harness
- `MidasCompatibilityTest.kt`: Android compatibility test
- `MainActivity.java`: WebView, photo picker, bridge, and wallpaper application
- `DepthLiveWallpaperService.java`: ticking live wallpaper renderer

Selected photos remain on the device. The app does not upload, classify, block, or censor photo content. Depth estimation is best-effort and may use a safer motion path for difficult edges.
