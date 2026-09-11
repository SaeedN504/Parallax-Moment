# Parallax Moment: Native Android build

This is a standard Android project with a native WebView host, JavaScript bridge, and live wallpaper service. The editor and offline AI assets are packaged directly from `www/`.

## Requirements

- JDK 17
- Android SDK 35
- Android Studio Ladybug or newer, optional

## Build

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Install it with:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

- `www/index.html`: editor UI
- `www/imgly/`: offline segmentation model and runtime
- `MainActivity.java`: WebView, photo picker, bridge, and static wallpaper application
- `DepthLiveWallpaperService.java`: ticking live wallpaper renderer
- `android/app/build.gradle`: packages `www/` directly as Android assets

Edit `www/`, commit, then build. No Node installation or synchronization step is needed.
