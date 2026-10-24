package id.my.doncorleonez.multiview.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import id.my.doncorleonez.multiview.core.FailKind
import id.my.doncorleonez.multiview.core.Log
import id.my.doncorleonez.multiview.core.PlayerPort
import id.my.doncorleonez.multiview.core.Redact

/** Player events back to the slot engine. */
interface PortEvents {
    fun onReady(gen: Int)
    fun onBuffering(gen: Int)
    fun onError(gen: Int, kind: FailKind)
    fun onEnded(gen: Int)
}

/**
 * One ExoPlayer per visible tile (§6). A new player is built for every start and fully released on
 * stop, so a provider connection is never held by a tile that isn't playing.
 */
@OptIn(UnstableApi::class)
class Media3Port(
    private val context: Context,
    private val slot: Int,
    private val view: PlayerView,
    private val userAgent: String,
) : PlayerPort {

    lateinit var events: PortEvents
    private var player: ExoPlayer? = null
    private var gen = -1

    override fun start(gen: Int, url: String, hls: Boolean, audible: Boolean) {
        check(player == null) { "previous player not released" }
        this.gen = gen
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(loadControl)
            .build()
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        applyAudio(p, audible)
        p.addListener(Listener(gen, p))
        p.addAnalyticsListener(Metrics(slot))
        view.player = p
        p.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
                .build()
        )
        p.prepare()
        p.playWhenReady = true
        player = p
        Log.i(TAG, "slot ${slot + 1} start ${if (hls) "HLS" else "TS"} host=${Redact.hostOf(url)}")
    }

    override fun setAudible(audible: Boolean) {
        player?.let { applyAudio(it, audible) }
    }

    override fun release() {
        val p = player ?: return
        player = null
        view.player = null
        p.release()
    }

    /** Quality = what the channel sends; audio decoded only on the highlighted tile (§6.2, §6.3). */
    private fun applyAudio(p: ExoPlayer, audible: Boolean) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(true)
            .clearViewportSizeConstraints()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
            .build()
        p.volume = if (audible) 1f else 0f
    }

    private inner class Listener(private val g: Int, private val p: ExoPlayer) : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (player !== p) return
            when (playbackState) {
                Player.STATE_READY -> events.onReady(g)
                Player.STATE_BUFFERING -> events.onBuffering(g)
                Player.STATE_ENDED -> events.onEnded(g)
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player !== p) return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            val kind = classify(error)
            Log.w(TAG, "slot ${slot + 1} error ${error.errorCodeName} → $kind", error)
            events.onError(g, kind)
        }
    }

    private class Metrics(private val slot: Int) : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            Log.i(TAG, "slot ${slot + 1} dropped=$droppedFrames in ${elapsedMs}ms")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG, "slot ${slot + 1} decoder=$decoderName init=${initializationDurationMs}ms")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(TAG, "slot ${slot + 1} video ${format.width}x${format.height} ${format.sampleMimeType} ${format.codecs ?: ""} fps=${format.frameRate} br=${format.bitrate}")
        }
    }

    companion object {
        private const val TAG = "Player"

        fun classify(e: PlaybackException): FailKind {
            var c: Throwable? = e
            while (c != null) {
                if (c is HttpDataSource.InvalidResponseCodeException) return httpKind(c.responseCode)
                c = c.cause
            }
            return when (e.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> FailKind.DECODER
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> FailKind.UNSUPPORTED
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> FailKind.PARSE
                else -> FailKind.NETWORK
            }
        }

        /** Provider refusals (connection limit, expired, blocked) vs. transient errors. */
        fun httpKind(code: Int): FailKind = when {
            code == 401 || code == 403 || code == 429 || code == 458 || code == 509 || code == 884 -> FailKind.REJECTED
            code in 450..499 -> FailKind.REJECTED
            else -> FailKind.NETWORK // 404 offline channel, 5xx, etc. → reconnect loop
        }
    }
}
