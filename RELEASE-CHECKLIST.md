# Parallax Moment APK release checklist

Use this checklist before publishing a downloadable APK or GitHub Release.

## 1. Product and app identity

- [ ] Confirm the app name is **Parallax Moment** everywhere.
- [ ] Confirm the application ID is `com.depth.live.wallpaper`.
- [ ] Confirm the version name and version code are updated in `android/app/build.gradle`.
- [ ] Confirm the launcher icon, app label, and live wallpaper label are correct.
- [ ] Confirm the release notes describe the real changes and known limitations.

## 2. Source and assets

- [ ] Confirm the `www/` editor opens without console errors.
- [ ] Confirm bundled AI assets exist under `www/imgly/`.
- [ ] Confirm sample images and cutouts load from `www/samples/`.
- [ ] Confirm no Capacitor config, package files, or `node_modules` instructions remain.
- [ ] Confirm model, font, sample-image, and runtime licenses are documented before distribution.
- [ ] Confirm no secrets, keystores, tokens, or private files are committed.

## 3. Functional smoke test on a real Android device

- [ ] Install the APK on a clean device running the minimum supported Android version.
- [ ] Launch the app from the home screen.
- [ ] Open a built-in sample wallpaper.
- [ ] Import a photo from the device picker.
- [ ] Verify offline subject scanning works with network disabled.
- [ ] Adjust clock font, size, color, position, date, and 12/24-hour format.
- [ ] Apply to **Home**, **Lock**, and **Both** targets.
- [ ] Confirm the applied wallpaper matches the preview.
- [ ] Open **Set as Live Wallpaper** and confirm the system picker appears.
- [ ] Confirm the live wallpaper renders the photo and ticking clock.
- [ ] Rotate or relaunch the app if supported by the product decision, then verify state is stable.
- [ ] Test back navigation, cancellation, empty states, and an invalid or unsupported image.

## 4. Build the release APK

```bash
cd android
./gradlew clean assembleRelease
```

- [ ] Confirm the build completes without warnings that affect packaging.
- [ ] Confirm the output exists at `android/app/build/outputs/apk/release/app-release.apk`.
- [ ] Confirm the APK is signed with the release keystore, not a debug key.
- [ ] Confirm the signing key is stored securely and is not committed.
- [ ] Confirm the APK installs over the previous release without losing user data.
- [ ] Confirm the APK can be uninstalled and reinstalled cleanly.

## 5. Verify the artifact

```bash
apksigner verify --verbose android/app/build/outputs/apk/release/app-release.apk
sha256sum android/app/build/outputs/apk/release/app-release.apk
```

- [ ] Save the SHA-256 checksum in the release notes.
- [ ] Check the APK file size is reasonable for the bundled model and assets.
- [ ] Confirm the package name, version code, and version name with Android build tools.
- [ ] Scan the artifact with the available security tooling.
- [ ] Install and test the exact APK that will be uploaded, not a different local build.

## 6. Publish the download

- [ ] Create a GitHub Release from the intended commit or tag.
- [ ] Upload `app-release.apk` as a release asset.
- [ ] Upload a checksum file, for example `app-release.apk.sha256`.
- [ ] Add a short install note: enable installation from this source only when Android asks.
- [ ] Add supported Android version, permissions, and known device limitations.
- [ ] Link the release from the repository README.
- [ ] Verify the public download works in a private browser session.
- [ ] Verify the checksum shown in the release matches the uploaded APK.

## 7. Rollback plan

- [ ] Keep the previous known-good APK available.
- [ ] Record the release commit, version code, checksum, and signing key used.
- [ ] If a critical issue appears, mark the release as unavailable and point users to the previous release.
- [ ] Document the issue and add a regression test or smoke-test step before republishing.

## Release record

- Release version: `v___.___.___`
- Version code: `___`
- Commit or tag: `________________`
- APK SHA-256: `________________`
- Published date: `________________`
- Tested device and Android version: `________________`
- Known issues: `________________`
