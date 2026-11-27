# Ambient Recorder

Agent-generated project.

Ambient Recorder records nearby sound while the phone is locked. It runs on
Android 10 or later and stores recordings in private app storage.

[Download the latest signed APK](https://github.com/AndreDon0/ambient-recorder/releases/latest).

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

## Updates

**Updates** shows the installed version, download progress, and release notes.
The app checks its GitHub update feed while open. Downloads default to Wi-Fi and
resume when the app and an eligible connection return. Recording files remain
on the phone. Stop recording before choosing **Install**, then confirm the
upgrade in Android's installer.

## Publish a version

The app reads its version from `version.properties`. For subsequent releases,
run `python3 scripts/release.py bump patch` (or `minor` or `major`), commit the
version change, and push `main`.

```sh
./gradlew :app:assembleRelease
python3 scripts/release.py sign app/build/outputs/apk/release/app-release-unsigned.apk \
  dist/Ambient-Recorder-1.1.0.apk --keystore "$HOME/.android/debug.keystore"
python3 scripts/release.py publish dist/Ambient-Recorder-1.1.0.apk --notes-file updates/1.1.0.md
```

Set `ANDROID_HOME` to the SDK path and authenticate GitHub CLI before publishing.
The signing command requires the original key used for Ambient Recorder 1.0. It
prompts locally for the password. You can also set `AMBIENT_STORE_PASSWORD` and
`AMBIENT_KEY_PASSWORD` for noninteractive builds. The keystore stays local.

The publisher verifies the APK, creates a versioned GitHub Release with the APK
and its checksum, and pushes the update feed after the assets are available.
