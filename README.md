# Kino

Minimal Android TV client for a WebDAV share. Browse, Direct Play, nothing else.

No transcoding, no library, no metadata. Video goes to the hardware decoder; Dolby and
DTS audio go to the soundbar as an untouched bitstream.

## Requirements

- Android TV, minSdk 24. Developed against Android 14 on a TCL Smart TV Pro.
- Android SDK `platforms;android-37.0` and `build-tools;36.0.0`. AGP 9 addresses
  platforms by minor version, so the directory really is `android-37.0`.
- JDK 17 or newer. Built and tested here on 21 and 26.

## Build

```
./gradlew test assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use `assembleRelease`, not `assembleDebug`. The release build type flips two
independent switches: R8, which takes the APK from 16 MB to 2.3 MB, and
`debuggable = false`, which is what makes Compose smooth on a TV. Judging Compose
performance from a debug build measures neither. Release is signed with the debug key,
so it installs over a debug build.

`.github/workflows/build.yml` runs the same two tasks on every push and attaches the
APK to the run. Its APKs are signed with a keystore the runner generates fresh each
time, so they install over each other but not over a locally built one.

## Use

Launching lands on the list of servers. `+ Add server` asks for a WebDAV URL, a
username and a password — leave the credentials blank if the server needs none — and
the server then appears as a row. **Long-press** a server row to edit it, or to remove
it, which forgets its credentials but keeps the playback positions — re-add it and it
picks up where you left off. Each server keeps its own credentials, and they are only
ever sent to that server.

Compose UI on Material 3 for TV, with its own dark theme rather than the panel's.
D-pad navigates — the focused row scales and glows — OK opens, Back goes up one
directory and then back to the server list. Focus starts on the first row of every
listing, including after going back.
A listing still loading after 250ms shows a bar under the path. Playback position is
remembered per file and reset once a file is watched to the end. Leaving the player —
Home, or another app — releases the decoder; coming back resumes from where it was.

In the player, left and right on the seek bar step 10s back and 30s forward — the same
amount on every press and every repeat, which is the pair the Plex TV app uses. If the
file carries Matroska chapters and one is named like an intro or credits (`OP`, `ED`,
`Intro`, `Opening`, `Ending`, `Credits`, `Outro`, `Recap`, `Preview`), a **Skip** button
sits in the corner for as long as that chapter runs and jumps to the end of it. Files
without such chapters never show it.

## Checking passthrough

**INFO** toggles an overlay — or `adb shell input keyevent 165`, since most TV remotes
have no INFO button. Above media3's own readout (video and audio format, decoder in
use) it adds three lines:

```
buffer 31s | 44/170MB | 0 stalls
net 38.1 Mbps down | 284.6 Mbps est | 34.2 Mbps file
sink 10ch | passthrough: AC3 EAC3 JOC TrueHD DTS DTS-HD AC4
```

- `buffer` — seconds of playback already fetched, bytes held against the target, and
  rebuffers since playback started (seeks excluded). Loading stops at 50s or at the
  byte target, whichever is hit first; the one nearer its limit is the one in charge.
- `net` — `down` is live throughput and falls toward zero when the buffer is full,
  which is correct rather than broken. `est` is media3's own capacity estimate, frozen
  at whatever the link last managed, hence *estimated*. `file` is the file's overall
  bitrate: what `down` has to beat on average. Starving looks like `down` sitting under
  `file` while `buffer` drains.
- `sink` — what this TV and soundbar advertise. A format missing from that line cannot
  be fixed in the app. It is the (e)ARC capability report, not a player setting.

```
adb logcat -s Kino:V EventLogger:V
```

In the `EventLogger` output, **no audio decoder means passthrough is working**. A
decoder name means the track was decoded to PCM instead.

## Known limits

| Limit | Why |
| --- | --- |
| PGS / VobSub subtitles ignored | Media3 decodes text subtitles only — SRT, WebVTT, SSA/ASS, TTML. Bitmap subtitles would need an FFmpeg decoder. |
| Dolby Vision is the TV's call, not the app's | Media3 1.11.1 reads the DV configuration from MKV and MP4 alike. Profile 8.1 plays as DV on a DV panel and falls back to its HDR10 base layer otherwise. Profile 7 (dual-layer Blu-ray rips) always plays as HDR10 — nothing on Android composes the enhancement layer. Profile 5 has no compatible base layer, so on a non-DV panel its colours will be wrong. |
| AV1 may not play | No software decoder is bundled; the TV needs an AV1 hardware decoder. |
| TrueHD and DTS-HD MA need eARC | ARC lacks the bandwidth. AC-3 and E-AC-3, including Atmos, pass over plain ARC. |
| Cleartext HTTP allowed app-wide | The server URL is user-configurable, so the domain cannot be pinned in a network security config. |
