@echo off
setlocal EnableExtensions

rem Parallax Moment: build and install debug APK on a connected Android device.
rem Usage from the repository root: build-install.bat

set "ROOT=%~dp0"
set "ANDROID_DIR=%ROOT%android"
set "APK=%ANDROID_DIR%\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%ANDROID_DIR%\gradlew.bat" (
  echo [ERROR] Could not find android\gradlew.bat.
  echo Run this script from a complete Parallax-Moment checkout.
  exit /b 1
)

where adb >nul 2>&1
if errorlevel 1 (
  echo [ERROR] adb was not found on PATH.
  echo Add your Android SDK platform-tools folder to PATH, then retry.
  exit /b 1
)

echo [1/3] Building debug APK...
pushd "%ANDROID_DIR%"
call gradlew.bat clean assembleDebug --stacktrace --no-daemon
if errorlevel 1 (
  popd
  echo [ERROR] Gradle build failed.
  exit /b 1
)
popd

if not exist "%APK%" (
  echo [ERROR] Build completed but APK was not found:
  echo %APK%
  exit /b 1
)

echo [2/3] Checking for an authorized Android device...
adb start-server >nul 2>&1
set "DEVICE="
for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
  if "%%B"=="device" if not defined DEVICE set "DEVICE=%%A"
)

if not defined DEVICE (
  echo [ERROR] No authorized Android device found.
  echo Enable USB debugging, connect the phone, accept the RSA prompt, and retry.
  adb devices
  exit /b 1
)

echo [3/3] Installing on %DEVICE%...
adb -s "%DEVICE%" install -r "%APK%"
if errorlevel 1 (
  echo [ERROR] APK installation failed.
  exit /b 1
)

adb -s "%DEVICE%" shell monkey -p com.depth.live.wallpaper 1 >nul 2>&1
if errorlevel 1 (
  echo [WARN] APK installed, but Windows could not launch it automatically.
) else (
  echo App launched.
)

echo.
echo Done: %APK%
exit /b 0
