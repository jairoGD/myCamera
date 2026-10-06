# my Camera

A personal Android camera app built with Java and Camera2. QR code detection uses Google ML Kit. Requires Android 10 (API 29) or newer.

## Download

**[Download my Camera APK](https://github.com/jairoGD/myCamera/releases/download/v1.0.0-debug/myCamera-v1.0.0-debug.apk)** · [Release notes](https://github.com/jairoGD/myCamera/releases/tag/v1.0.0-debug)

This is a debug-signed build for personal testing. Download the APK on your Android device and open it to install. The SHA-256 checksum is `4994F406255E544E4733303BE43FB41D1AF451D850BDB826C669E5D32599296B`.

## Run it

Open this folder in Android Studio, let Gradle sync, and run the `app` configuration on a phone. Grant camera permission. Photos appear in the gallery under `Pictures/my Camera`; videos appear under `Movies/my Camera`. Microphone permission is requested when you first start recording. If declined, video still records without audio.

The main screen follows the supplied mockup: a camera preview above a black bottom bar, with a circular shutter button and a three-line menu button. The supplied flash and camera-switch icons sit in the upper-left corner at half opacity when off and solid white when on. Pinch the preview with two fingers to zoom. Tap the menu button to show a translucent adjustment layer over the lower part of the preview. Choose Light, Tone, Color, or View on the bottom bar, and use the arrows beside the slider to switch properties. Tap the menu button again to hide the controls. A circular reset button appears to the left of the shutter whenever the selected property differs from its default. The reset icon comes from the supplied SVG, and each successful capture plays the supplied `shot.mp3` once.

The app scans the live preview for QR codes containing HTTP or HTTPS links. When one is found, a tappable link appears below the top icons for a few seconds. The browser opens only after you tap it. The QR model is bundled in the APK, so scanning does not need a model download on the phone.

Tap the video icon beside the camera-switch icon to enter video mode. The capture button becomes a red circle; tap it to start recording. During recording the video icon turns red and the capture button becomes a white square; tap the square to stop. Video uses H.264 in an MP4 file, up to 1080p at 30 fps, with AAC audio when microphone access is granted. Video records the camera stream directly: Tone, Color, and digital exposure filters currently apply to the preview and JPEG photos, but are not encoded into videos. Hardware controls such as lens focus, white balance, zoom, flash, and supported exposure compensation apply to video.

- **Light:** exposure from −5 to +5 EV (default 0) and white balance presets. The app uses hardware exposure compensation up to the camera's supported limit, then digital gain in the preview and saved JPEG. Extreme digital exposure can clip highlights or lose shadow detail.
- **Tone:** brightness and contrast.
- **Color:** saturation, warmth, and magenta/green tint.
- **View:** composition grid, manual preview rotation, and manual focus. Focus starts in Auto at the center of a −200% to +200% display scale. Moving its slider selects a manual lens position within the device's physical focus range; resetting that property returns to Auto. Manual focus is disabled on cameras that do not support it. The default preview rotation corrects the previous 90° clockwise view. Rotation affects only the preview, not saved photos.

The Tone and Color adjustments use the same color filter for the preview and saved JPEG. Camera exposure, white balance, flash, and maximum zoom depend on device hardware. JPEG processing limits the longest side to about 3000 pixels to control memory usage; this is not a RAW camera.

## Build

Run `gradlew.bat assembleDebug` on Windows. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app source is in `app/src/main/java/com/jairo/minhacamera/MainActivity.java`.

The launcher icon uses the supplied source at `design/icon.svg`; its adaptive foreground is generated at `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`.
