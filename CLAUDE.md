# Kino

Minimal Android TV WebDAV Direct Play client. ~700 lines of Kotlin across three
source files, six dependencies, no layout XML.

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
- Every AAR carries an `aar-metadata.properties` declaring `minCompileSdk` **and**
  `minAndroidGradlePluginVersion` — two independent gates, both hard failures, and the
  AGP one cannot be suppressed. That pair is what dictates `compileSdk = 37` here
  (compose 1.12 demands 37 and AGP 9.1). Read the metadata rather than release notes:
  `unzip -p <artifact>.aar META-INF/com/android/build/gradle/aar-metadata.properties`
- **AGP 9 has Kotlin built in.** Applying `org.jetbrains.kotlin.android` alongside it
  is a hard error telling you to remove it. Only `org.jetbrains.kotlin.plugin.compose`
  is applied, and its version selects the Kotlin toolchain.
- AGP 9 addresses SDK platforms by **minor** version: it looks for
  `platforms;android-37.0`, not `android-37`, and a directory named the old way is
  simply not found. Its default build-tools is tied to the AGP version (36.0.0 for
  9.4.x), not to `compileSdk`.
- A hand-installed SDK package needs a `package.xml` beside its `source.properties`,
  or AGP reports it missing however correct the files are. `sdkmanager` writes that
  file; unzipping the archive yourself does not.
- `Text`, `Button`, `Surface`, `MaterialTheme` and `darkColorScheme` exist in **both**
  `androidx.tv.material3` and `androidx.compose.material3`. The TV ones are imported
  plainly; the other two are aliased `M3Theme` / `m3DarkColorScheme` so the collision
  cannot happen silently.
- `OutlinedTextField` comes from compose-material3 (tv-material has no text field) and
  reads **compose-material3's** `MaterialTheme`, not the TV one. The settings screen
  nests both themes for that reason — remove the nesting and the fields render in
  material3's stock palette.
- The other half of that trap: content passed *into* a compose-material3 component must
  also be compose-material3's. `androidx.tv.material3.LocalContentColor` defaults to
  **`Color.Black`**, and it is a different CompositionLocal from compose-material3's, so
  a tv-material `Text` in an `OutlinedTextField` label slot never sees the colour the
  field provides and renders black on the dark background. Hence `M3Text` for every
  label and placeholder.
- Nothing is focused by default in Compose, which on a TV means the D-pad does nothing
  at all. `FocusRequester` on the first row, re-fired per directory, is not optional.
- `LocalBringIntoViewSpec`, the CompositionLocal usually reached for to pivot the
  focused row away from the screen edge, does not exist in the foundation this
  resolves (1.12.1). `contentPadding` on the `LazyColumn` does
  that job here.
- **Compose skips on identity, not equality, for unstable parameter types.** `List` is
  unstable, so a list rebuilt during composition and passed down makes the callee and
  every row inside it recompose every time anything changes — which looks like the
  rows flashing. The `remember(entries, stack.size)` around `rows` in `MainActivity`
  is load-bearing, not tidiness.
- A `LaunchedEffect` body runs *after* composition, so clearing state inside one is
  always a frame late. The listing is tagged with the directory it came from and a
  mismatched tag reads as empty, which makes a stale listing impossible to render
  rather than merely brief.
- `AudioCapabilities.getCapabilities` has three public overloads and only the
  four-argument one is not deprecated. The fourth argument is
  `spatializerChannelMasks`; empty is what the deprecated overload passed, and it is
  right here anyway — the spatializer virtualises surround rather than passing a
  bitstream through.
- JDK 21. The machine default may be newer, and AGP rejects it.
- `HttpURLConnection.setRequestMethod("PROPFIND")` throws `ProtocolException`. That is
  the only reason OkHttp is a dependency — playback uses media3's own
  `DefaultHttpDataSource`.
- `PlayerView` paints only the video rectangle. Letterbox bars show whatever is behind
  it, hence the explicit black on the root view and the window.

## Build

```
./gradlew test assembleRelease
```

Release, not debug. Two independent things come with it: R8, which takes the APK from
15 MB to 3 MB, and `debuggable = false`, which is what makes Compose smooth on a TV.
Release is signed with the debug key — this is a sideloaded app, never published — so
it installs over a debug build.

`.github/workflows/build.yml` runs the same two tasks. It deletes the
`org.gradle.java.home` line from `gradle.properties` first, because that path exists on
one machine only and the runner's JDK 21 is already on `JAVA_HOME`.

`WebDavTest` is a plain JVM test. `parseMultistatus` uses `javax.xml` DOM specifically
so it runs with no Robolectric and no extra dependency — keep it that way. One of its
cases is built from the real server's dialect (non-default port, deep base path,
`<D:resourcetype></D:resourcetype>` as an open/close pair).

## Files

```
WebDav.kt                    PROPFIND + namespace-aware DOM parse. TAG lives here.
MainActivity.kt              Compose UI: browse list, settings form, directory stack
PlayerActivity.kt            player, resume, INFO debug overlay, sink readout
res/values/theme.xml         palette + the window theme
res/drawable/banner.xml      320x180, what the Leanback launcher shows
res/drawable/ic_launcher.xml square icon, for Settings only
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

Each is marked with a `ponytail:` comment where it lives, saying what the ceiling is
and what the upgrade would be: single server (`MainActivity`), no paging (`WebDav`),
unbounded resume-position preferences (`PlayerActivity`), extension-based video
detection (`WebDav`).
