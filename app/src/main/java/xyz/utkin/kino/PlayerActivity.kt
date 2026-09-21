package xyz.utkin.kino

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.DebugTextViewHelper
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView
import okhttp3.Credentials

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
    private lateinit var url: String

    // Nullable, not lateinit: these exist only between onStart and onStop, and
    // `isInitialized` would still be true for a released player.
    private var player: ExoPlayer? = null
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
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to basicAuth()))
            .setAllowCrossProtocolRedirects(true)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
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
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback failed for $url", error)
                val detail = error.cause?.message ?: error.message
                Toast.makeText(this@PlayerActivity, "${error.errorCodeName}: $detail", Toast.LENGTH_LONG).show()
                finish()
            }
        })

        val resumeMs = prefs.getLong(positionKey(), 0L)
        Log.i(TAG, "play $url from ${resumeMs}ms; ${sinkSummary()}")
        p.setMediaItem(MediaItem.fromUri(url), resumeMs)
        p.playWhenReady = true
        p.prepare()
    }

    private fun releasePlayer() {
        val p = player ?: return
        savePosition()
        debug?.stop()
        debug = null
        playerView.player = null
        p.release()
        player = null
    }

    private inner class SinkAwareDebug(p: ExoPlayer, v: TextView) : DebugTextViewHelper(p, v) {
        override fun getDebugString() = super.getDebugString() + "\n" + sinkSummary()
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
     * INFO toggles the debug readout. Subtitles and audio tracks live in the player
     * controller, not here. For remotes with no INFO key: `adb shell input keyevent 165`.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO) {
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
