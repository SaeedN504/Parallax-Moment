# Parallax Moment: Native Android build

This is a standard Android project with a native WebView host, JavaScript bridge, live wallpaper service, and fully offline depth-estimation path. The editor and offline AI assets are packaged directly into the APK.

## Requirements

- JDK 17
- Android SDK 35
- Internet access during the first build only if the local MiDaS model is absent
- Android Studio Ladybug or newer, optional
- A real Android device for `connectedDebugAndroidTest`

## Build

```bash
cd android
./gradlew clean assembleDebug
```

Before compilation, `verifyMidasModel` searches for `android/app/src/main/assets/midas_small_256_fp16.tflite`, rejects the old 133-byte LFS pointer, and computes a SHA-256 checksum. If the local file is missing or invalid, Gradle downloads the official MiDaS v2.1 `model_opt.tflite`, validates its size and TFLite header, and then checksums it. The verified copy is placed in generated build assets, never downloaded at runtime.

To enforce a known checksum in CI or on your machine:

```bash
./gradlew assembleDebug -PmidasModelSha256=PASTE_64_HEX_CHAR_SHA256_HERE
```

You can also use `MIDAS_MODEL_SHA256` and `MIDAS_MODEL_URL` environment variables. The generated checksum is written to `android/app/build/generated/midasAssets/midas_small_256_fp16.tflite.sha256`.

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Offline MiDaS integration

The app never downloads the model at runtime. `MidasDepthEstimator.kt` loads only the generated, verified model and uses the official model's `[-1, 1]` RGB preprocessing. Its output adapter accepts either `[1,256,256]` or `[1,256,256,1]` as long as the tensor contains exactly 65,536 float32 values.

Run device verification with:

```bash
./gradlew connectedDebugAndroidTest
```

See [`DEPTH-BENCHMARK.md`](DEPTH-BENCHMARK.md) for the model contract, device tiers, and result table.
