# Stable update feed

`stable.json` is updated by `python3 scripts/release.py publish` after a
signed release is online. The updater checks the APK's SHA-256, size, package,
version, Android requirement, and signing certificate before opening the installer.
