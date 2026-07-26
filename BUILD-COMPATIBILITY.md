# Compatibility and packaging

LanCast is implemented entirely with the Android framework and contains no JNI
libraries (`lib/*.so`). Each generated APK is therefore a universal APK that
runs on:

- ARM 32-bit (`armeabi-v7a`)
- ARM 64-bit (`arm64-v8a`)
- x86
- x86_64

Generating separate ABI APKs would produce identical application code and
would not improve compatibility.

## Android versions

| Module | Minimum | Tested build target | Intended runtime range |
|---|---:|---:|---|
| Receiver | Android 4.2 / API 17 | API 33 | Android 4.2–16 |
| Sender | Android 5.0 / API 21 | API 33 | Android 5.0–16 |

Android 4.x cannot be a screen-capture sender because the public
`MediaProjection` API was introduced in Android 5. It can still run Receiver
and accept DLNA or LanCast streams.

Internal audio capture requires Android 10 or newer on Sender. Applications may
disable capture for DRM or policy reasons.
