# Kino

Minimal Android TV client for a WebDAV share. Browse, Direct Play, nothing else.

No transcoding, no library, no metadata. Video goes to the hardware decoder; Dolby and
DTS audio go to the soundbar as an untouched bitstream.

## Requirements

- Android TV, minSdk 24. Developed against Android 14 on a TCL Smart TV Pro.
- Android SDK `platforms;android-36` and `build-tools;35.0.0`.
- JDK 21 — AGP 8.10 rejects newer. Pinned in `gradle.properties`.

## Build

```
./gradlew test assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use `assembleRelease`, not `assembleDebug`. R8 only runs on release, and it is the
difference between a 15 MB APK that stutters on a TV and a 3 MB one that does not.
Release is signed with the debug key, so it installs over a debug build.

`.github/workflows/build.yml` runs the same two tasks on every push and attaches the
APK to the run. Its APKs are signed with a keystore the runner generates fresh each
time, so they install over each other but not over a locally built one.

## Use

First launch asks for the WebDAV URL, username and password. Leave the credentials
blank if the server needs none. The first row of the root listing, `⚙ Server settings`,
reopens that dialog.

Compose UI on Material 3 for TV, with its own dark theme rather than the panel's. D-pad
navigates — the focused row scales and glows — OK opens, Back goes up one directory.
Coming back out of a folder returns focus to the row you left. Playback position is remembered
per file and reset once a file is watched to the end.

## Checking passthrough

**INFO** toggles an overlay — or `adb shell input keyevent 165`, since most TV remotes
have no INFO button. It shows the video and audio format, the decoder in use, and what
the TV and soundbar actually advertise:

```
sink 8ch | passthrough: AC3 EAC3 JOC TrueHD
```

A format missing from that line cannot be fixed in the app. It is the TV's (e)ARC
capability report, not a player setting.

```
adb logcat -s Kino:V EventLogger:V
```

In the `EventLogger` output, **no audio decoder means passthrough is working**. A
decoder name means the track was decoded to PCM instead.

## Known limits

| Limit | Why |
| --- | --- |
| PGS / VobSub subtitles ignored | Media3 decodes text subtitles only — SRT, WebVTT, SSA/ASS, TTML. Bitmap subtitles would need an FFmpeg decoder. |
| Dolby Vision in MKV not used | Media3 extracts DV from MP4 only. DV MKVs play their HDR10 base layer, which is correct for profile 7 and 8.1. |
| AV1 may not play | No software decoder is bundled; the TV needs an AV1 hardware decoder. |
| TrueHD and DTS-HD MA need eARC | ARC lacks the bandwidth. AC-3 and E-AC-3, including Atmos, pass over plain ARC. |
| Cleartext HTTP allowed app-wide | The server URL is user-configurable, so the domain cannot be pinned in a network security config. |
