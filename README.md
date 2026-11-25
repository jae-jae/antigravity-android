# Antigravity Android

English | [简体中文](./README.zh-CN.md)

![Antigravity Android Edition](./assets/banner.jpg)

A lightweight, native-feeling unofficial Android client for [Google Antigravity](https://antigravity.google.com).

## Features

- **Launch & Go**: Built-in browser container that opens Antigravity directly on startup, supporting Google sign-in, OAuth popups, and persistent cookies.
- **Native Launch Splash**: Dark logo splash screen loads the page silently in the background and fades out smoothly once ready.
- **Immersive Adaptive Status Bar**: Samples the webpage header's actual rendered background color in real time and automatically adjusts status bar icon contrast.
- **Native System Notifications**: Bridges in-page Web `Notification` calls directly to Android system notifications while the app is alive (supports Android 13+ permission prompts and tap-to-focus).
- **Complete Mobile UX**: Supports file uploads, system download manager integration, back-button history navigation, and double-back to exit.

## Download

Get the latest `Antigravity-v1.0.5-release.apk` (Android 7.0+) from [Releases](https://github.com/jae-jae/antigravity-android/releases).

## Build

```bash
./gradlew assembleRelease
```

The built APK will be in `app/build/outputs/apk/release/` (provide your own `app/keystore/antigravity-release.jks` or run `./gradlew assembleDebug`).

## License & Disclaimer

Open-sourced under the [MIT License](./LICENSE). This is an unofficial community wrapper and is not affiliated with Google.
