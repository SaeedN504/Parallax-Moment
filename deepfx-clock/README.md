# DeepFX-Clock

Standalone Android app (`com.deepfx.clock`) in the Parallax-Moment repository. The original `android/` app and `www/` editor are unchanged.

## Status

Initial implementation, not a verified release. Check the **DeepFX-Clock APK** Actions run for compilation, lint, unit tests and emulator pixel-test results. Offline AI quality, physical-device installation, gestures, battery behavior and launcher compatibility still require device testing. Do not interpret source availability as a successful APK build.

## Build

Requires JDK 17, Android SDK 35, and the complete repository including its existing models and Gradle wrapper. Dependency downloads happen at build time only.

```sh
cd deepfx-clock
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The launcher reuses `../android/gradlew` (Gradle 8.7) with this standalone project. Android Gradle Plugin 8.5.2 is used for compatibility with that wrapper. SDK 35 is beyond AGP 8.5's officially tested compile SDK; the warning is explicitly suppressed, not a build error. A later toolchain upgrade should update both AGP and Gradle together.

APK output: `app/build/outputs/apk/debug/app-debug.apk`. The workflow uploads `DeepFX-Clock-debug-apk` with a SHA-256 checksum after all checks pass. It is debug-signed for testing, not a Play Store release.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest
```

No existing Parallax-Moment installation is replaced because this app has its own package ID.

## Implementation

| Component | Behavior |
|---|---|
| Photo import | System photo picker on Android 13+, document picker on older supported versions; no broad storage permission |
| Segmentation | Existing imgly IS-Net quantized ONNX chunks assembled at build time, run with native ONNX Runtime |
| Depth | Existing checksum-pinned MiDaS TFLite model, offline inference |
| Editor | Local WebView controls plus native interactive preview; external WebView requests are blocked |
| Preview | Uses the exact same native renderer as the live wallpaper |
| Layer order | Depth-moving background, anchored clock, anchored RGBA subject cutout |
| Motion | 64 vertical strips with depth averaged over the full height, horizontal/vertical/diagonal drift |
| Clock | Drag and pinch, condensed/7-segment digits, position, opacity, glow, letter spacing, color, 12/24h |
| Date and AM/PM | Independently draggable anchors and X/Y sliders |
| Mask refinement | Threshold and smooth feather transition; not a manual brush editor |
| Persistence | Versioned image directories and atomic `settings.json` commit marker |
| Live updates | Wallpaper checks settings on a worker once per second while visible |
| Rendering lifecycle | Choreographer capped at 24–30 fps; callbacks removed when wallpaper is hidden |
| Static path | Saves app-private `depth-wallpaper.jpg` and applies one frozen frame to the home screen |

## Deliberate differences from the initial specification

Segmentation runs natively rather than in onnxruntime-web. The UI is still WebView-based, but the preview is native rather than a JavaScript canvas. Both decisions avoid duplicating rendering behavior and reuse the existing model without runtime downloads. Source photos are downsampled to at most approximately 1600 pixels on the long edge to control memory. Background and subject share a 10% overscan for safe drift margins.

The original full photo still includes the subject; moving that background underneath a fixed cutout can expose a duplicate edge at higher amplitudes. This is not inpainting or true 3D reconstruction. Keep drift subtle. Vertical strips approximate the depth field and can show seams. Segmentation model input normalization and output quality must be validated on real portraits before calling this production-ready.

There is no INTERNET permission, cloud inference, analytics, or photo upload. System backups are disabled. Model and dependency license compliance must be reviewed before public distribution; this implementation does not grant new rights to bundled assets.

## Device acceptance checks

| Check | Expected result |
|---|---|
| Airplane-mode import | Subject and depth complete with no downloads |
| Portrait preview | Subject covers clock digits at the overlap |
| Clock drag/pinch | Position and scale persist after reopening |
| Date/AM-PM drag | Each selected anchor moves independently |
| Live apply | System picker opens and the selected wallpaper runs |
| Settings update | Color/format/position change without reselecting the image |
| Hidden wallpaper | No wallpaper frame callbacks while not visible |
| Static apply | Home wallpaper is the three-layer composite with frozen time |
| Low-memory handling | Processing failure is surfaced, old committed scene remains available |

Live lock-screen support depends on the Android version and launcher. The app cannot override that platform limitation. A static wallpaper cannot display a ticking clock.
