# Parallax Moment: Native Android build

This is a standard Android project with a native WebView host, JavaScript bridge, live wallpaper service, and fully offline depth-estimation path. The editor and offline AI assets are packaged directly into the APK.

## Requirements

- JDK 17
- Android SDK 35
- Internet access during the first build to fetch the official MiDaS release
- Android Studio Ladybug or newer, optional
- A real Android device for `connectedDebugAndroidTest`

## Build

```bash
cd android
./gradlew clean assembleDebug
```

`prepareMidasModel` downloads the official MiDaS v2.1 `model_opt.tflite` from GitHub Releases into the generated build assets, validates its size and TFLite header, and prints its SHA-256. Later builds reuse the generated file. The stale 133-byte Git LFS pointer in the source tree is excluded and can never be packaged.

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Offline MiDaS integration

The app never downloads the model at runtime. `MidasDepthEstimator.kt` loads the model bundled by Gradle and uses the official model's `[-1, 1]` RGB preprocessing. Its output adapter accepts either `[1,256,256]` or `[1,256,256,1]` as long as the tensor contains exactly 65,536 float32 values.

To use another direct mirror during the build:

```bash
./gradlew assembleDebug -PmidasModelUrl=https://example.com/model.tflite
```

or set `MIDAS_MODEL_URL`. Any mirror must provide the same tensor contract.

Run the device verification with:

```bash
./gradlew connectedDebugAndroidTest
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

Selected photos remain on the device. The app does not upload them. Depth estimation is best-effort and may use a safer motion path for difficult edges.
