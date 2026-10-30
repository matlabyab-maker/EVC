# EVC — Version 0.1.0

First incremental build: video selection and playback only.

## Included
- Android video picker using the system document picker (`video/*`).
- Video playback with Android `VideoView` and built-in media controls.
- Custom EVC launcher icon.
- GitHub Actions workflow based on the supplied Android workflow pattern, producing an `EVC-debug-apk` artifact.

## Build
Open this folder as an Android project in Android Studio, or push the project files to a new GitHub repository named `EVC`. GitHub Actions runs on push and manual dispatch.

No `.git` directory is included: initialize a fresh repository for EVC so the old EsCut remote/history is not accidentally reused.

## Scope of this first version
This version does not cut video yet. The only app capability is selecting and playing a video. Build and device playback still need verification in GitHub Actions / on the target device.
