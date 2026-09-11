# Parallax Moment

<p align="center">
  <strong>Turn any photo into a living lock screen.</strong><br>
  AI-powered subject cutouts, layered clock typography, and a real-time Android live wallpaper.
</p>

<p align="center">
  <a href="https://github.com/SaeedN504/Parallax-Moment/actions"><img src="https://img.shields.io/github/actions/workflow/status/SaeedN504/Parallax-Moment/android.yml?style=for-the-badge&label=build" alt="Build status"></a>
  <a href="https://github.com/SaeedN504/Parallax-Moment/releases"><img src="https://img.shields.io/github/v/release/SaeedN504/Parallax-Moment?style=for-the-badge&label=release" alt="Latest release"></a>
  <a href="https://github.com/SaeedN504/Parallax-Moment/blob/main/LICENSE"><img src="https://img.shields.io/github/license/SaeedN504/Parallax-Moment?style=for-the-badge" alt="License"></a>
  <a href="https://github.com/SaeedN504/Parallax-Moment/stargazers"><img src="https://img.shields.io/github/stars/SaeedN504/Parallax-Moment?style=for-the-badge" alt="Stars"></a>
</p>

<p align="center">
  <img src="docs/screenshots/hero.png" alt="Parallax Moment preview" width="280">
</p>

## What it does

Parallax Moment creates an iOS-style depth composition from your own photos. The bundled AI separates the foreground subject locally on-device, then places a customizable clock behind it so the wallpaper feels designed around the image instead of pasted on top.

- **Private by default:** subject cutout runs offline with the bundled ISNet model
- **Make it yours:** choose clock font, size, color, position, layout, date, and 12/24-hour format
- **Use your own photos:** import an image from your gallery or start with the built-in vault
- **Apply instantly:** set the result on the lock screen, home screen, or both
- **Actually live:** optional Android live wallpaper keeps the clock ticking in real time
- **No server required:** the editor and AI assets ship inside the app

## Screenshots

<p align="center">
  <img src="docs/screenshots/vault.png" alt="Wallpaper vault" width="220">
  <img src="docs/screenshots/editor.png" alt="Wallpaper editor" width="220">
  <img src="docs/screenshots/preview.png" alt="Wallpaper preview" width="220">
</p>

> Screenshot files live in [`docs/screenshots`](docs/screenshots). Replace the sample images with fresh captures from the current build before publishing the store listing.

## Quick start

### Build the Android app locally

Requirements: **Node 18+**, **JDK 21**, Android SDK **35**, and Android Studio Ladybug or newer.

```bash
npm install
npx cap sync android
cd android
./gradlew assembleDebug
```

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

Every push and pull request runs the Android build. Open the **Actions** tab to watch it, then download the APK from the workflow run's **Artifacts** section.

## How to use it

1. Open the app and choose a built-in wallpaper or tap **+** to import a photo.
2. Let the offline AI scan the image and separate the subject.
3. Adjust the clock, date, color, font, position, and layout.
4. Open **Preview**, choose lock screen, home screen, or both.
5. Tap **Apply**, or choose **Set as Live Wallpaper** for a ticking clock.

## Project structure

```text
www/                         Web editor and bundled offline AI assets
  index.html                 Single-file editor
  imgly/                     ISNet model, ONNX runtime, and resource map
  samples/                   Built-in sample photos and cutouts
android/                     Capacitor Android project
  app/src/main/java/...      Native wallpaper bridge and live service
  app/src/main/res/xml/      Live wallpaper descriptor and file paths
.github/workflows/           Automated Android build
```

## Privacy

Parallax Moment does not need an account or a backend to create wallpapers. Images selected for editing stay on the device, and the bundled subject-segmentation model runs locally in the app.

## Known Android behavior

Android controls the final wallpaper crop differently across manufacturers and launchers. The editor renders a 1080×2340 portrait composition, while the system may apply its own parallax or crop behavior on the home screen.

## Contributing

Issues and pull requests are welcome. If a device-specific wallpaper issue appears, include the Android version, manufacturer/launcher, screen target, and a short screen recording if possible.

## License

Choose and add a license before publishing. **MIT** is the simplest option for an open-source app, but do not claim the bundled model, fonts, sample images, or third-party runtime assets are covered by your app license without checking their individual terms.
