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
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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

    private lateinit var prefs: SharedPreferences
    private lateinit var player: ExoPlayer
    private lateinit var debugView: TextView
    private lateinit var debug: DebugTextViewHelper
    private lateinit var url: String
    private var debugOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to basicAuth()))
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        // PlayerView defaults to a SurfaceView, which is what HDR and tunneling want.
        // Subtitle cues (SubRip / WebVTT / SSA / TTML) render themselves.
        val playerView = PlayerView(this)
        playerView.player = player
        playerView.keepScreenOn = true

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
        debug = SinkAwareDebug(player, debugView)

        // Without this the app is silent in logcat: EventLogger is what prints the
        // chosen decoder, the audio sink configuration and every format change.
        player.addAnalyticsListener(EventLogger())

        val resumeMs = prefs.getLong(positionKey(), 0L)
        Log.i(TAG, "play $url from ${resumeMs}ms; ${sinkSummary()}")
        player.setMediaItem(MediaItem.fromUri(url), resumeMs)
        player.playWhenReady = true
        player.prepare()
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
        val caps = AudioCapabilities.getCapabilities(this)
        val supported = PASSTHROUGH_ENCODINGS.filterValues(caps::supportsEncoding).keys
        return "sink ${caps.maxChannelCount}ch | passthrough: " +
            if (supported.isEmpty()) "none (PCM only)" else supported.joinToString(" ")
    }

    /**
     * INFO (or MENU) toggles the readout; `adb shell input keyevent 165` works too, for
     * remotes with no INFO button. The tell for passthrough is the *absence* of an audio
     * decoder: a decoder name there means the track was decoded to PCM instead of
     * reaching the soundbar as a bitstream.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            debugOn = !debugOn
            debugView.visibility = if (debugOn) View.VISIBLE else View.GONE
            if (debugOn) debug.start() else debug.stop()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        savePosition()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::player.isInitialized) return
        savePosition()
        debug.stop()
        player.release()
    }

    private fun savePosition() {
        val duration = player.duration
        val position = player.currentPosition
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
