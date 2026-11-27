# Ambient Recorder

Ambient Recorder records nearby sound while the phone is locked. It runs on
Android 10 or later and stores recordings in private app storage.

1. Open the app, tap **Start recording**, and grant microphone permission.
2. To end the session, tap **Stop recording** in the app or its notification.
3. Tap a recording to listen. Its menu lets you export the original audio or
   delete that recording after confirmation.

The app records continuously in segments of about five minutes. Each segment
appears when it finishes. A power change starts a new segment and changes the
format for subsequent audio.

| Power source | Format | Typical storage per hour at 48 kHz |
| --- | --- | ---: |
| External power | Mono PCM16 WAV | 346 MB |
| Battery | Mono AAC-LC at a target of 64 kbps | 29 MB |

Recordings stay on the phone until you delete them or uninstall the app. Export
anything you want to keep outside the app. Recording stops when free storage
falls below about 64 MiB. After restarting the phone, open the app and start a
new session. Calls and other apps can take priority over the microphone and
leave gaps in the timeline.

## Build

Open this folder in Android Studio, or use a JDK supported by Gradle 9.8
and an Android SDK with platform 35 and Build Tools 36.1.0.

```sh
./gradlew :app:assembleDebug
```

The development APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
