# Kino

Minimal Android TV WebDAV Direct Play client. ~470 lines of Kotlin, three
dependencies, no layout XML.

## The design premise — do not "improve" this

Playback is **stock media3**: `ExoPlayer.Builder(context).build()` with the default
`RenderersFactory`. That is deliberate, and it is the whole point of the project.

Dolby and DTS passthrough work because `MediaCodecAudioRenderer` runs in bypass mode
and `DefaultAudioSink` opens an `AudioTrack` with `ENCODING_E_AC3` / `_AC3` /
`_DOLBY_TRUEHD` / `_DTS`. The app never builds IEC 61937 frames — the audio HAL does.
Atmos needs no code at all: it rides inside E-AC-3 (JOC) and TrueHD, so passing the
bitstream through untouched delivers it.

Do not add a custom `RenderersFactory`, a hand-built `AudioCapabilities`, or an FFmpeg
layer. Those exist in Plex to correct firmware that misreports its own capabilities on
specific devices. This app targets one modern TV and trusts the platform. Every line
not written here is one that cannot get between the bitstream and the soundbar.

## Android gotchas already paid for

- `DocumentBuilderFactory.setFeature()` **throws `ParserConfigurationException` for
  every feature on Android** — it is a stub, unlike the desktop JVM. The XXE guard is
  `isExpandEntityReferences = false` plus an `EntityResolver` returning an empty
  `InputSource`; both work on either platform. Pinned by the
  `external entities are not resolved` test.
- `compileSdk = 36` is required by media3 1.11.1. `targetSdk` stays **35** on purpose:
  36 enforces predictive back, which would break the `onBackPressed()` override.
- The Kotlin plugin must be at least the `kotlin-stdlib` version media3 and okhttp
  resolve to (2.2.10), or the compiler rejects their metadata.
- JDK 21. The machine default may be newer, and AGP 8.10 rejects it.
- `HttpURLConnection.setRequestMethod("PROPFIND")` throws `ProtocolException`. That is
  the only reason OkHttp is a dependency — playback uses media3's own
  `DefaultHttpDataSource`.
- `InputType` variation bits are inert without a class bit. Use
  `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI`, or the field is uneditable.
- `PlayerView` paints only the video rectangle. Letterbox bars show whatever is behind
  it, hence the explicit black on the root view and the window.

## Build

```
./gradlew test assembleDebug
```

`WebDavTest` is a plain JVM test. `parseMultistatus` uses `javax.xml` DOM specifically
so it runs with no Robolectric and no extra dependency — keep it that way. One of its
cases is built from the real server's dialect (non-default port, deep base path,
`<D:resourcetype></D:resourcetype>` as an open/close pair).

## Files

```
WebDav.kt          PROPFIND + namespace-aware DOM parse. TAG and PREFS live here.
MainActivity.kt    ListView browse, setup dialog, directory stack
PlayerActivity.kt  player, resume, INFO debug overlay, sink capability readout
```

No layout XML by design — each screen is a single view, built in code.

## Deliberate shortcuts

Marked with `ponytail:` comments at their sites: single server, no paging, unbounded
resume-position preferences, extension-based video detection.
