# Android module

This directory contains Photos App 1.7.1 (`com.photosapp`, version code 10).

Read the [repository README](../README.md) for the full feature guide, Google OAuth setup, installation, multi-account storage model, recovery, privacy, troubleshooting and limitations.

## Build

Open this directory in Android Studio, configure Android SDK Platform 35, and allow Gradle to sync. The wrapper uses Gradle 9.5.0 / AGP 9.3.0. The daemon criterion selects Java 25; source compatibility is Java 21. A JDK 21 launcher plus the provisioned Java 25 daemon was used for the verified Windows build.

Windows / PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

macOS / Linux (wrapper included; not verified on those platforms):

```sh
./gradlew :app:assembleDebug :app:lintDebug --console=plain
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Lint report: `app/build/reports/lint-results-debug.html`.

Register your signing certificate's SHA-1 and package name in an Android OAuth client in the Google Cloud project with Drive API enabled. Run `:app:signingReport` to find the fingerprint. The current Drive connection does not require Firebase, a web backend or an embedded client secret.

## Latest viewer changes

Version 1.7.1 replaces the native video controller's grey scrim/black timeline with app-owned white controls: play/pause, elapsed/total time, seeking, mute/unmute, Save as photo and full screen. The header and Share/Edit/Add to/Bin actions use the neutral photo-viewer theme. Tapping media switches between white controls and black immersive viewing.

The update retains Room schema version 8, existing accounts, original-byte uploads, resumable sessions, backup notifications and the library features described in the main README.

## Validation

Debug assembly and lint passed locally on Windows: 0 lint errors, 205 warnings. Phone appearance, live uploads and cross-device recovery remain unverified without a connected device. Project rules require ARTEMIS device exploration before authoring executable UI tests.
