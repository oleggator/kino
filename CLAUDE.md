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
- `compileSdk = 36` is required by media3 1.11.1. Do **not** move the Compose BOM past
  `2026.03.00` (compose 1.10.5): from compose 1.11 on, the artifacts demand
  `compileSdk = 37` and AGP 9.1, and the build fails outright.
- The Compose compiler version is not a free choice — `org.jetbrains.kotlin.plugin.compose`
  must equal the Kotlin version. Since Kotlin 2.0 there is no `composeOptions` block.
- `Text`, `Button`, `Surface`, `MaterialTheme` and `darkColorScheme` exist in **both**
  `androidx.tv.material3` and `androidx.compose.material3`. The TV ones are imported
  plainly; the other two are aliased `M3Theme` / `m3DarkColorScheme` so the collision
  cannot happen silently.
- `OutlinedTextField` comes from compose-material3 (tv-material has no text field) and
  reads **compose-material3's** `MaterialTheme`, not the TV one. The settings screen
  nests both themes for that reason — remove the nesting and the fields render in
  material3's stock palette.
- Nothing is focused by default in Compose, which on a TV means the D-pad does nothing
  at all. `FocusRequester` on the first row, re-fired per directory, is not optional.
- `LocalBringIntoViewSpec` — the old pivot-scrolling hook — was removed in foundation
  1.12.1. `contentPadding` on the `LazyColumn` is what keeps the focused row off the
  screen edge now.
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

`assembleRelease` is worth running after dependency changes: R8 is on for release and
takes the APK from 15 MB to 3 MB, which is what pays for Compose.

`WebDavTest` is a plain JVM test. `parseMultistatus` uses `javax.xml` DOM specifically
so it runs with no Robolectric and no extra dependency — keep it that way. One of its
cases is built from the real server's dialect (non-default port, deep base path,
`<D:resourcetype></D:resourcetype>` as an open/close pair).

## Files

```
WebDav.kt          PROPFIND + namespace-aware DOM parse. TAG and PREFS live here.
MainActivity.kt    Compose UI: browse list, settings form, directory stack
PlayerActivity.kt  player, resume, INFO debug overlay, sink capability readout
res/values/theme.xml   palette + the window theme
```

The UI is **Compose with `androidx.tv:tv-material`** (Material 3 for TV). The point of
being on it is `ListItem`: focus scale, border and glow come from the component, so
none of that is hand-rolled. Never `Modifier.clickable` here — it gives no focus state
on a D-pad. Use a clickable tv-material component.

`PlayerActivity` stays on Views on purpose. Its UI *is* media3's `PlayerView`; wrapping
that in `AndroidView` would add a layer and change nothing.

`theme.xml` is not a layout — it is the window theme, and it earns its place with one
line: `windowBackground` is what the TV paints before Compose's first frame, so without
it the app opens on a white flash. The two colours live there because `banner.xml`
references them too.

No ViewModel: `rememberSaveable` already carries the one piece of state that has to
survive recreation (the directory stack), for zero extra dependencies.

## Deliberate shortcuts

Marked with `ponytail:` comments at their sites: single server, no paging, unbounded
resume-position preferences, extension-based video detection.
