# Parallax Moment

**Turn any photo into a living Android wallpaper.** Offline subject cutout, layered clock typography, static wallpaper application, and a native live wallpaper with depth-parallax motion.

## Live wallpaper features

- **Depth mesh parallax** — continuous depth-driven warp (no strip artifacts), combining a slow ambient drift with **device tilt**: the scene moves as you physically tilt the phone.
- **Clock behind the subject** — the subject cutout is drawn on top of the clock layer, iPhone-style.
- **Rich clock layer** — 12/24-hour format, seconds, AM/PM badge, date line, font/weight/color/opacity/stretch/tracking, glow and shadow, all driven by the editor's settings schema.

## Native Android build

The UI and offline AI remain in `www/`. A native Android WebView hosts that editor and owns photo picking, wallpaper application, and the live wallpaper service. No Node, npm, synchronization command, or cross-platform wrapper is required.

Requirements: **JDK 21** and Android SDK **35**.

```bash
cd android
./gradlew assembleDebug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Structure

```text
www/                         UI and bundled offline AI assets
android/                     Native Android project
  app/src/main/java/...      WebView bridge and live wallpaper service
  app/src/main/res/          Manifest, themes, icons, and wallpaper metadata
```

## Use

1. Choose a built-in wallpaper or import a photo.
2. Let the bundled AI separate the subject.
3. Adjust the clock and composition.
4. Choose Lock, Home, or Both and tap Apply.
5. Optionally set the ticking live wallpaper.

Selected images stay on the device and segmentation runs locally.



