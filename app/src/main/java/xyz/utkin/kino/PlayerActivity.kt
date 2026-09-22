package xyz.utkin.kino

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.util.DebugTextViewHelper
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import okhttp3.Credentials
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Probed against the sink so the overlay can name what this TV + soundbar will take. */
private val PASSTHROUGH_ENCODINGS = linkedMapOf(
    "AC3" to C.ENCODING_AC3,
    "EAC3" to C.ENCODING_E_AC3,
    "JOC" to C.ENCODING_E_AC3_JOC,
    "TrueHD" to C.ENCODING_DOLBY_TRUEHD,
    "DTS" to C.ENCODING_DTS,
    "DTS-HD" to C.ENCODING_DTS_HD,
    "DTS:X" to C.ENCODING_DTS_UHD_P2,
    "AC4" to C.ENCODING_AC4,
)

/**
 * Chapter titles worth offering to skip. Matroska chapter names are free text, so this
 * is a guess at what encoders actually write; the word boundaries stop "OP" matching
 * "Stop". A file with no chapters, or none named like these, never shows the button.
 */
private val SKIPPABLE_CHAPTER = Regex(
    """\b(op|ed|intro|opening|ending|credits|outro|recap|preview)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Stock ExoPlayer, stock renderers, stock audio sink.
 *
 * That is the whole Dolby passthrough story: [ExoPlayer.Builder] gives us
 * DefaultRenderersFactory -> MediaCodecAudioRenderer (bypass) -> DefaultAudioSink
 * built with the platform's AudioCapabilities -> AudioTrack(ENCODING_E_AC3 / _AC3 /
 * _DOLBY_TRUEHD / _DTS) -> HDMI. Atmos rides inside E-AC-3 and TrueHD, so there is
 * nothing to configure for it. Every line we do not write here is one that cannot
 * get between the bitstream and the soundbar.
 */
// media3's UnstableApi uses androidx.annotation.RequiresOptIn, not Kotlin's, so this
// must be androidx's OptIn with the named `markerClass` attribute.
@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** Within this of the end, treat the file as finished and restart it next time. */
        private const val FINISHED_SLACK_MS = 5_000L

        /** How often the skip button re-checks where playback is. */
        private const val SKIP_POLL_MS = 500L

        // What the Plex TV app does, from its SeekbarView.onKeyDown: +30s on right,
        // -10s on left, the same on every press and every repeat. Asymmetric because
        // the two directions are different jobs -- skipping ahead past something, and
        // backing up over a line you missed.
        private const val SEEK_FORWARD_MS = 30_000L
        private const val SEEK_BACK_MS = 10_000L

        // Share of the app's heap to spend on the media buffer. A third, where the Plex
        // TV app takes a fifth -- Plex keeps poster caches and a browse UI resident, this
        // app keeps a stopped Compose tree and an okhttp client.
        //
        // No upper clamp: the allocator grows on demand rather than reserving, and
        // DefaultLoadControl stops at DEFAULT_MAX_BUFFER_MS (50s) regardless, so a
        // generous ceiling costs nothing on a file that never reaches it and an absolute
        // cap would only bind on the large-heap devices that can best afford it. The
        // floor is roughly what media3 would have picked anyway, so a small-heap box is
        // never made worse than the default.
        private const val BUFFER_HEAP_FRACTION = 3
        private const val BUFFER_MIN_MB = 32

        /** Gap after which the download-rate baseline is treated as stale, not a sample. */
        private const val SAMPLE_STALE_MS = 3_000L

        /** Buffering this soon after a seek is the seek's doing, not a starved buffer. */
        private const val SEEK_SETTLE_MS = 1_000L
    }

    /**
     * One instance, used twice on purpose: the player renders with these, and
     * [sinkSummary] queries the sink with the same ones. Asking what the output
     * accepts under different attributes than playback uses would be asking the
     * wrong question.
     */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    private lateinit var prefs: SharedPreferences
    private lateinit var playerView: PlayerView
    private lateinit var debugView: TextView
    private lateinit var skipButton: Button
    private lateinit var url: String

    private var timeBar: DefaultTimeBar? = null
    private var skips: List<Skip> = emptyList()
    private var skipToMs = 0L

    // Nullable, not lateinit: these exist only between onStart and onStop, and
    // `isInitialized` would still be true for a released player.
    private var player: ExoPlayer? = null

    // Built here rather than left to DefaultLoadControl so the INFO overlay can ask it
    // how full it is. 64KB segments is what media3 and Plex both use.
    private var allocator: DefaultAllocator? = null
    private var targetBufferBytes = 0

    /**
     * Everything that comes off the socket, which [sampleRates] differences to get the
     * download rate. Written on the loader thread and read on the main one, hence the
     * atomic.
     */
    private val bytesTransferred = AtomicLong()
    private var contentLengthBytes = 0L

    // Baseline for the rate sample, and the two smoothed results.
    private var sampledAtMs = 0L
    private var sampledBytes = 0L
    private var downBps = 0L

    // Cumulative, so a stall that happened while you were watching the film rather than
    // the overlay still shows up afterwards.
    private var stalls = 0
    private var stalledMs = 0L
    private var stalledSinceMs = 0L
    private var wasReady = false
    private var seekedAtMs = 0L

    private val byteCounter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, spec: DataSpec, isNetwork: Boolean) {
            if (contentLengthBytes == 0L) contentLengthBytes = totalLength(source)
        }
        override fun onTransferEnd(source: DataSource, spec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, spec: DataSpec, isNetwork: Boolean, count: Int) {
            bytesTransferred.addAndGet(count.toLong())
        }
    }
    private var debug: DebugTextViewHelper? = null
    private var debugOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // PlayerView defaults to a SurfaceView, which is what HDR and tunneling want.
        // Subtitle cues (SubRip / WebVTT / SSA / TTML) render themselves.
        playerView = PlayerView(this)
        playerView.keepScreenOn = true
        // The controller already offers an audio-track selector; this puts subtitles
        // beside it. Hidden by default, and media3 disables it when a file has no text
        // tracks, so there is nothing to handle for that case.
        playerView.setShowSubtitleButton(true)
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        // DefaultTimeBar's default is keyCountIncrement = 20, so one D-pad press moves
        // a twentieth of the file -- six minutes into a two-hour film. setKeyTimeIncrement
        // replaces that with a fixed step; dispatchKeyEvent picks which one per press.
        timeBar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress)
        timeBar?.setKeyTimeIncrement(SEEK_FORWARD_MS)

        skipButton = Button(this).apply {
            visibility = View.GONE
            setOnClickListener {
                player?.seekTo(skipToMs)
                hideSkip()
            }
        }

        debugView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }

        // PlayerView paints only the video rectangle, so anything letterboxed (21:9 on a
        // 16:9 panel) shows whatever is behind it — by default DeviceDefault's grey.
        // Black here covers the bars; black on the window covers the launch transition.
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(
                    debugView,
                    FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START),
                )
                addView(
                    skipButton,
                    FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                        val density = resources.displayMetrics.density
                        marginEnd = (48 * density).toInt()
                        // Clear of media3's control bar, so the two never overlap when
                        // the controller happens to be up during an intro.
                        bottomMargin = (120 * density).toInt()
                    },
                )
            },
        )
    }

    /**
     * The player lives between onStart and onStop, not for the life of the activity.
     * A MediaCodec instance and an AudioTrack are scarce on a TV — holding them while
     * backgrounded can stop another app getting a decoder, and keeps the (e)ARC link
     * claimed. The resume position is what carries across, exactly as it does between
     * separate launches.
     */
    override fun onStart() {
        super.onStart()
        // onCreate finishes early when EXTRA_URL is missing, and onStart still runs.
        if (::url.isInitialized) openPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun openPlayer() {
        // The Authorization header is a *default* request property, so it is sent on
        // every connection this data source opens — including a redirect target. Cross
        // protocol redirects are off for that reason: allowing them lets an https URL
        // redirect to http and carry the password in clear. A same-protocol redirect to
        // another host still takes the header with it; stripping that would need a
        // custom DataSource, which is exactly what this project does not do.
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to basicAuth()))
            .setAllowCrossProtocolRedirects(false)
            .setTransferListener(byteCounter)

        bytesTransferred.set(0)
        contentLengthBytes = 0L
        sampledAtMs = 0L
        downBps = 0L
        stalls = 0
        stalledMs = 0L
        stalledSinceMs = 0L
        wasReady = false
        seekedAtMs = 0L
        targetBufferBytes = targetBufferBytes()
        val bufferAllocator = DefaultAllocator(true, 64 * 1024)
        allocator = bufferAllocator

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setAllocator(bufferAllocator)
                    .setTargetBufferBytes(targetBufferBytes)
                    .build(),
            )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            // The controller's rewind/fast-forward buttons default to 5s/15s; match the
            // seek bar so the two controls do not disagree about what a skip is.
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player = p
        playerView.player = p

        debug = SinkAwareDebug(p, debugView)
        if (debugOn) debug?.start()

        // Without this the app is silent in logcat: EventLogger is what prints the
        // chosen decoder, the audio sink configuration and every format change.
        p.addAnalyticsListener(EventLogger())

        // Without this a codec the TV cannot decode, or a dead connection, is just a
        // black screen forever. Same reasoning as the listing failure in MainActivity.
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                skips = skipsFrom(tracks, p.duration)
                Log.i(TAG, "skippable chapters: ${skips.map(Skip::label)}")
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek buffers by definition; that is not the network failing to keep
                // up. Recorded as a time rather than a flag because a seek that lands
                // inside the buffer never changes playback state, so a flag would stay
                // set and swallow the next real stall.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAtMs = SystemClock.elapsedRealtime()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                val atMs = SystemClock.elapsedRealtime()
                val fromSeek = atMs - seekedAtMs < SEEK_SETTLE_MS
                if (state == Player.STATE_BUFFERING && wasReady && !fromSeek) {
                    stalls++
                    stalledSinceMs = atMs
                }
                if (state == Player.STATE_READY && stalledSinceMs > 0) {
                    stalledMs += atMs - stalledSinceMs
                    stalledSinceMs = 0L
                }
                wasReady = state == Player.STATE_READY
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback failed for $url", error)
                val detail = error.cause?.message ?: error.message
                Toast.makeText(this@PlayerActivity, "${error.errorCodeName}: $detail", Toast.LENGTH_LONG).show()
                finish()
            }
        })

        val resumeMs = prefs.getLong(positionKey(), 0L)
        Log.i(TAG, "play $url from ${resumeMs}ms; buffer ${targetBufferBytes shr 20}MB; ${sinkSummary()}")
        p.setMediaItem(MediaItem.fromUri(url), resumeMs)
        p.playWhenReady = true
        p.prepare()

        skipButton.post(skipTick)
    }

    private fun releasePlayer() {
        val p = player ?: return
        skipButton.removeCallbacks(skipTick)
        hideSkip()
        skips = emptyList()
        savePosition()
        debug?.stop()
        debug = null
        playerView.player = null
        p.release()
        player = null
        allocator = null
    }

    /**
     * ponytail: a 500ms poll rather than an ExoPlayer position message. Two of those
     * (enter and leave) per skippable chapter, re-armed on every seek, is more moving
     * parts than a timer that costs one list scan twice a second.
     */
    private val skipTick = object : Runnable {
        override fun run() {
            updateSkip()
            skipButton.postDelayed(this, SKIP_POLL_MS)
        }
    }

    private fun updateSkip() {
        val position = player?.currentPosition ?: return
        val skip = skips.firstOrNull { position >= it.startMs && position < it.endMs }
        if (skip == null) {
            hideSkip()
            return
        }
        skipToMs = skip.endMs
        if (skipButton.visibility != View.VISIBLE) {
            skipButton.text = "Skip ${skip.label}"
            skipButton.visibility = View.VISIBLE
            // One OK press should skip, so take focus -- but not out from under the
            // controller, where the user is already driving something else.
            if (!playerView.isControllerFullyVisible) skipButton.requestFocus()
        }
    }

    private fun hideSkip() {
        if (skipButton.visibility == View.VISIBLE) {
            skipButton.visibility = View.GONE
            playerView.requestFocus()
        }
    }

    private inner class SinkAwareDebug(p: ExoPlayer, v: TextView) : DebugTextViewHelper(p, v) {
        override fun getDebugString() =
            super.getDebugString() + "\n" + bufferSummary() + "\n" + netSummary() +
                "\n" + sinkSummary()
    }

    /**
     * How far ahead playback is covered, in both of the units that can stop the loading:
     * seconds against DefaultLoadControl's 50s target, and bytes against [targetBufferBytes].
     * Whichever is closer to its limit is the one holding the buffer back.
     */
    private fun bufferSummary(): String {
        val ahead = player?.totalBufferedDuration ?: 0L
        // Bytes the allocator holds, which lags what is actually in use until it trims.
        val used = allocator?.totalBytesAllocated ?: 0
        val stalled = if (stalledMs > 0) " ${stalledMs / 1000}s" else ""
        return "buffer ${ahead / 1000}s | ${used shr 20}/${targetBufferBytes shr 20}MB | " +
            "$stalls stalls$stalled"
    }

    /**
     * `down` is real throughput: bytes over wall time, so idle intervals count against
     * it and it falls toward zero when the buffer is full. That is not a broken reading
     * -- the loader only goes flat out when the buffer needs filling, which is exactly
     * when the number matters.
     *
     * `est` is media3's own capacity estimate, and it is labelled estimated because it
     * is not live: DefaultBandwidthMeter recomputes only inside onTransferEnd, and only
     * once a sample passes its thresholds, so with a full buffer there is nothing to
     * measure and it holds its last value. It is what the link managed when last asked.
     *
     * `file` is what the link has to beat: the whole file over its whole duration, the
     * number mediainfo calls overall bitrate. It needs the size, so it is absent from a
     * server that sends no length.
     */
    private fun netSummary(): String {
        sampleRates()
        val estimate = DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate
        val durationMs = player?.duration ?: 0L
        val file = if (contentLengthBytes > 0 && durationMs > 0) {
            " | ${mbps(contentLengthBytes * 8_000 / durationMs)} file"
        } else {
            ""
        }
        return "net ${mbps(downBps)} down | ${mbps(estimate)} est$file"
    }

    /**
     * Called once per overlay refresh, which is DebugTextViewHelper's one second. A
     * hidden overlay leaves a stale baseline, so that gap is dropped rather than turned
     * into a wrong number.
     */
    private fun sampleRates() {
        val atMs = SystemClock.elapsedRealtime()
        val bytes = bytesTransferred.get()
        val elapsedMs = atMs - sampledAtMs
        if (sampledAtMs > 0 && elapsedMs in 1L..SAMPLE_STALE_MS) {
            downBps = smooth(downBps, (bytes - sampledBytes) * 8_000 / elapsedMs)
        }
        sampledAtMs = atMs
        sampledBytes = bytes
    }

    /** A one second window on VBR video swings too hard to read without this. */
    private fun smooth(current: Long, sample: Long) =
        if (current == 0L) sample else (current * 3 + sample) / 4

    private fun mbps(bitsPerSecond: Long) =
        String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)

    /**
     * Total size of the file. A resumed file opens with a Range request, where
     * Content-Length is only the remainder and Content-Range carries the real total, so
     * that one wins when it is present. Header names are matched case-insensitively
     * rather than trusting the map's comparator.
     */
    private fun totalLength(source: DataSource): Long {
        val headers = (source as? HttpDataSource)?.responseHeaders ?: return 0L
        fun header(name: String) = headers.entries
            .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
            ?.value?.firstOrNull()
        header("Content-Range")?.substringAfterLast('/', "")?.toLongOrNull()?.let { return it }
        return header("Content-Length")?.toLongOrNull() ?: 0L
    }

    /**
     * media3 picks a buffer profile from the URI scheme, and `http://` is not in its
     * LOCAL_PLAYBACK_SCHEMES, so this gets the streaming one: about 19MB for video plus
     * 13MB for audio. Bytes run out before the 50s duration target does, which on a
     * 60 Mbps remux is roughly four seconds of cover. Raising the ceiling lets the
     * duration target be what stops the loading instead.
     *
     * Sized from the heap rather than fixed because DefaultAllocator allocates on the
     * Java heap, and read from largeMemoryClass because the manifest asks for the large
     * heap -- on a TV that is usually 512MB against a 192MB ordinary one, and buffering
     * a 60 Mbps remux is the case the large heap exists for. The flag is checked rather
     * than assumed: largeMemoryClass reports the bigger number whether or not the app
     * actually asked for it.
     */
    private fun targetBufferBytes(): Int {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val largeHeap = applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0
        val heapMb = if (largeHeap) activityManager.largeMemoryClass else activityManager.memoryClass
        return (heapMb / BUFFER_HEAP_FRACTION).coerceAtLeast(BUFFER_MIN_MB) shl 20
    }

    /**
     * What the platform says this TV + soundbar will accept as a bitstream, queried the
     * same way the player's own audio sink queries it. A format missing from this line
     * cannot be fixed in the app: it is the TV's (e)ARC capability report. ARC has no
     * bandwidth for TrueHD or DTS-HD MA; eARC does.
     */
    private fun sinkSummary(): String {
        // The four-argument overload is the only one not deprecated. It is routed-device
        // aware, so it answers for the output actually in use rather than in general:
        // null routedDevice means "whatever is currently routed", which is what we want.
        // The last argument is spatializerChannelMasks — empty is exactly what the
        // deprecated three-argument overload passed, and it is right here regardless,
        // since the spatializer virtualises surround rather than passing a bitstream.
        val caps = AudioCapabilities.getCapabilities(
            this,
            audioAttributes,
            /* routedDevice = */ null,
            /* spatializerChannelMasks = */ emptyList<Int>(),
        )
        val supported = PASSTHROUGH_ENCODINGS.filterValues(caps::supportsEncoding).keys
        return "sink ${caps.maxChannelCount}ch | passthrough: " +
            if (supported.isEmpty()) "none (PCM only)" else supported.joinToString(" ")
    }

    /**
     * DefaultTimeBar has one increment for both directions and negates it going left,
     * so the only way to get Plex's asymmetric pair is to set it per press. It reads
     * the increment fresh on every key event, so this is enough -- the bar still does
     * all the scrubbing.
     *
     * dispatchKeyEvent rather than onKeyDown: the focused time bar consumes these keys,
     * so the activity would never see them.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> timeBar?.setKeyTimeIncrement(SEEK_FORWARD_MS)
                KeyEvent.KEYCODE_DPAD_LEFT -> timeBar?.setKeyTimeIncrement(SEEK_BACK_MS)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * INFO toggles the debug readout. Subtitles and audio tracks live in the player
     * controller, not here. For remotes with no INFO key: `adb shell input keyevent 165`.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // debugView is only set once onCreate has got past the missing-URL check, and
        // a finished activity still receives events until it is torn down.
        if (keyCode == KeyEvent.KEYCODE_INFO && ::debugView.isInitialized) {
            debugOn = !debugOn
            debugView.visibility = if (debugOn) View.VISIBLE else View.GONE
            if (debugOn) debug?.start() else debug?.stop()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun savePosition() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition
        // ponytail: one long per URL in SharedPreferences. No watched flag, no schema,
        // no pruning. Add a Room table if this ever needs to sync or expire.
        val remember = if (duration > 0 && position > duration - FINISHED_SLACK_MS) 0L else position
        prefs.edit().putLong(positionKey(), remember).apply()
    }

    /** Prefixed so a media URL can never collide with the "url"/"user"/"pass" keys. */
    private fun positionKey() = "pos:$url"

    private fun basicAuth() = Credentials.basic(
        prefs.getString("user", "").orEmpty(),
        prefs.getString("pass", "").orEmpty(),
    )
}

/** A chapter we are willing to jump over, with the end time already resolved. */
private class Skip(val label: String, val startMs: Long, val endMs: Long)

/**
 * Matroska chapters arrive as [Chapter] entries on every track's `Format.metadata` --
 * the extractor attaches a chapter to one track when it names a track UID, and to all
 * of them when it does not, hence the `distinct`.
 */
private fun skipsFrom(tracks: Tracks, durationMs: Long): List<Skip> {
    val all = tracks.groups.asSequence()
        .flatMap { group -> (0 until group.length).asSequence().map(group::getTrackFormat) }
        .mapNotNull { it.metadata }
        .flatMap { metadata -> (0 until metadata.length()).asSequence().map(metadata::get) }
        .filterIsInstance<Chapter>()
        .distinct()
        .sortedBy { it.startTimeMs }
        .toList()

    return all.mapIndexedNotNull { i, chapter ->
        val label = chapter.title?.value.orEmpty()
        if (chapter.isHidden || !SKIPPABLE_CHAPTER.containsMatchIn(label)) return@mapIndexedNotNull null
        // ChapterTimeEnd is optional in Matroska, and most muxers leave it out: a
        // chapter that has none runs until the next one starts.
        val end = chapter.endTimeMs
            .takeIf { it > chapter.startTimeMs }
            ?: all.getOrNull(i + 1)?.startTimeMs
            ?: durationMs
        Skip(label, chapter.startTimeMs, end).takeIf { it.endMs > it.startMs }
    }
}
