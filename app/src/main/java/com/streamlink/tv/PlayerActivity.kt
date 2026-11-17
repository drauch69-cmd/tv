package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.LenientHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

/**
 * Fullscreen built-in player.
 *
 * Remote: arrows show the controls, OK pauses/plays, ▲ (controls hidden) picks another source,
 * Back returns to the link screen.
 *
 * When something goes wrong it heals itself, in this order:
 *  1. server refused (403 etc.) before playing -> retry claiming the next candidate page as referer
 *  2. never played and other sources exist -> try the next source
 *  3. otherwise -> reload the page invisibly for a fresh stream link (up to 3 times)
 *  4. still failing -> open the site's own player instead of showing an error
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        private const val STALL_MS = 20_000L
        private const val MAX_RECONNECTS = 3
        private const val FIRST_FRAME_MS = 8_000L

        /** Set when tunneled playback misbehaved; stays off until the app restarts (never saved). */
        @Volatile private var tunnelOffThisRun = false

        /** Decoder/audio errors that point at tunneled playback rather than the stream. */
        private val tunnelSuspects = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        )

        fun intent(ctx: Context): Intent = Intent(ctx, PlayerActivity::class.java)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var playerView: PlayerView
    private lateinit var cover: View
    private lateinit var coverText: TextView
    private lateinit var picker: View
    private lateinit var pickerList: LinearLayout
    private lateinit var sniffer: StreamSniffer

    private var player: ExoPlayer? = null
    private var set: StreamSet? = null
    private var sourceIndex = 0
    private var refererIndex = 0
    private var reconnects = 0
    private var playingSince = 0L
    private var qualityOptions: List<Pair<Tracks.Group, Int>> = emptyList()
    private var qualityChoice = -1
    private var tunneling = false
    private var smoothLive = true
    private var stalls = 0
    /** Chrome's network engine for this link; turned off if a stream never starts with it. */
    private var useChromeNet = true
    /** Use the TV's software video decoder (slower, more forgiving) for this link. */
    private var preferSoftware = false
    /** Experiment for stuck streams: play with the audio switched off to see if it's the blocker. */
    private var audioOff = false
    private var audioOffTried = false
    /** Experiment for stuck streams: jump to where the downloaded video actually is. */
    private var jumpTried = false
    private var loadsLogged = 0
    private var firstFrame = false
    /** Bumped whenever a different link takes over, so a pending reconnect for the old one is dropped. */
    private var linkGen = 0

    private val handler: (String, String?) -> Unit = { c, v -> onCommand(c, v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        cover = findViewById(R.id.cover)
        coverText = findViewById(R.id.coverText)
        picker = findViewById(R.id.picker)
        pickerList = findViewById(R.id.pickerList)
        sniffer = StreamSniffer(this, findViewById<FrameLayout>(R.id.webHolder))
        Session.commandHandler = handler
        loadSession()
        main.post(snapshotTick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Session.commandHandler = handler
        loadSession()
    }

    override fun onStop() {
        super.onStop()
        sniffer.cancel()
        release()
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        sniffer.cancel()
        release()
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            Session.snapshot = null
            AppStatus.text = "Waiting for a link"
        }
        super.onDestroy()
    }

    private fun loadSession() {
        val s = Session.current ?: return finish()
        linkGen++
        set = s
        sourceIndex = 0
        refererIndex = 0
        reconnects = 0
        playingSince = 0
        stalls = 0
        useChromeNet = true
        preferSoftware = false
        audioOff = false
        audioOffTried = false
        jumpTried = false
        hidePicker()
        Session.mode = "player"
        Session.title = Report.host(s.pageUrl)
        if (s.sources.size > 1) {
            Toast.makeText(this, "${s.sources.size} sources found · press ▲ to switch", Toast.LENGTH_LONG).show()
        }
        play()
    }

    // ---- Playback ----

    private fun play() {
        release()
        val s = set ?: return finish()
        val src = s.sources.getOrNull(sourceIndex) ?: return openSitePlayer("no playable source")

        val headers = HashMap<String, String>()
        var userAgent: String? = null
        for ((k, v) in src.headers) {
            when {
                k.equals("User-Agent", true) -> userAgent = v
                k.equals("Referer", true) || k.equals("Origin", true) || k.equals("X-Requested-With", true) -> {}
                else -> headers[k] = v
            }
        }
        val referer = src.referers.getOrNull(refererIndex) ?: ""
        if (referer.isNotEmpty()) {
            headers["Referer"] = referer
            Net.origin(referer).takeIf { it.isNotEmpty() }?.let { headers["Origin"] = it }
        }
        Report.log(
            "Playing source ${sourceIndex + 1}/${s.sources.size} (${src.label}), " +
                if (referer.isEmpty()) "no referer" else "referer ${Report.short(referer)}"
        )

        // Fetch through Chrome's network engine when available, so servers that only serve real
        // browsers don't refuse the player (see ChromeNet).
        val engine = if (useChromeNet) ChromeNet.engine else null
        loadsLogged = 0
        Report.logOnce(
            "net-${engine != null}",
            "Network: " + if (engine != null) ChromeNet.describe() else "Android's standard networking",
        )
        val http: HttpDataSource.Factory = if (engine != null) {
            CronetDataSource.Factory(engine, ChromeNet.executor)
                .setConnectionTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(headers)
                .apply { userAgent?.let { setUserAgent(it) } }
        } else {
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(headers)
                .apply { userAgent?.let { setUserAgent(it) } }
        }

        // Tunneled playback: the video chip syncs audio/video and draws frames itself, bypassing
        // Android's normal video path. Smoother and less work for the processor on TV boxes.
        tunneling = tunnelingAllowed()
        firstFrame = false
        val selector = DefaultTrackSelector(this)
        selector.setParameters(
            selector.buildUponParameters()
                .setTunnelingEnabled(tunneling)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioOff)
        )
        Report.logOnce("tunnel-$tunneling", "Tunneled playback: ${if (tunneling) "on" else "off"}")

        val dataSource = UnwrapDataSourceFactory(http)

        // Bigger cushion: wait for 8s of video after a stall (not 5s) so one hiccup doesn't
        // turn into several stalls in a row.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 2_500, 8_000)
            .build()
        smoothLive = smoothLiveAllowed()
        Report.logOnce("delay-$smoothLive", "Live delay: ${if (smoothLive) "smooth (~20s behind live)" else "low delay"}")

        // Decoders: hardware first as usual (fast), falling back to another decoder if one fails to
        // start. After a stream "arrives but won't play", software decoders are tried first.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        if (preferSoftware) {
            renderers.setMediaCodecSelector { mime, secure, tunneled ->
                MediaCodecUtil.getDecoderInfos(mime, secure, tunneled).sortedBy { it.hardwareAccelerated }
            }
        }

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        player = p
        playerView.player = p
        qualityOptions = emptyList()
        qualityChoice = -1

        val lower = src.url.lowercase()
        val item = MediaItem.Builder().setUri(src.url).apply {
            if (lower.contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (lower.contains(".mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
            if (smoothLive) {
                // Play ~20s behind live (a few seconds more than usual) so there's room to buffer, and never speed up to catch up
                // (that drains the cushion); only slow down imperceptibly to rebuild it.
                setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(20_000)
                        .setMinOffsetMs(12_000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.0f)
                        .build()
                )
            }
        }.build()

        p.addListener(listener)
        p.addAnalyticsListener(downloads)
        showCover(if (reconnects == 0) "Loading…" else "Reconnecting…")
        if (lower.contains("m3u8")) {
            // Be as forgiving as a browser with how the stream is put together. Many relayed
            // streams never send the "IDR" keyframe Android's player waits for before starting
            // (it would sit buffering forever), or don't mark where each frame begins.
            // LenientHlsExtractorFactory = Media3's own, plus an H.264 reader that can start streams
            // with no keyframes at all ("intra refresh"), the way browsers do.
            val lenient = LenientHlsExtractorFactory(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                true,
            )
            p.setMediaSource(HlsMediaSource.Factory(dataSource).setExtractorFactory(lenient).createMediaSource(item))
        } else {
            p.setMediaItem(item)
        }
        main.postDelayed(startCheck, 12_000)
        p.prepare()
        p.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            main.removeCallbacks(stallCheck)
            when (playbackState) {
                Player.STATE_READY -> {
                    if (audioOff && playingSince == 0L) {
                        Report.log("Experiment result: the video starts with the audio off, so the audio is what's holding it back. Turning the audio back on in 3s")
                        main.postDelayed(audioBackOn, 3_000)
                    }
                    if (tunneling && !firstFrame) {
                        main.removeCallbacks(firstFrameCheck)
                        main.postDelayed(firstFrameCheck, FIRST_FRAME_MS)
                    }
                    hideCover()
                    if (playingSince == 0L) {
                        playingSince = System.currentTimeMillis()
                        Report.log("Playback started")
                    }
                    AppStatus.text = "Playing ${Session.title}"
                }
                Player.STATE_BUFFERING -> {
                    if (player?.playWhenReady == true) main.postDelayed(stallCheck, STALL_MS)
                    if (playingSince != 0L) logStall()
                }
                Player.STATE_ENDED -> {
                    if (player?.isCurrentMediaItemLive == true) {
                        Report.log("Live stream stopped unexpectedly")
                        recover("stream ended")
                    } else {
                        Report.log("Video finished")
                        AppStatus.text = "Finished: ${Session.title}"
                        showCover("Finished\nPress Back for the link screen")
                    }
                }
                else -> {}
            }
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
            main.removeCallbacks(firstFrameCheck)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A paused player isn't stalled; restart the clock when playback resumes.
            main.removeCallbacks(stallCheck)
            if (playWhenReady && player?.playbackState == Player.STATE_BUFFERING) main.postDelayed(stallCheck, STALL_MS)
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onTracksChanged(tracks: Tracks) {
            // For the report: was tunneling only requested, or did the selected tracks really use it?
            player?.takeIf { !tracks.isEmpty }?.let {
                Report.logOnce(
                    "tunnel-engaged-$tunneling-${it.isTunnelingEnabled}",
                    "Tunneled playback: requested ${if (tunneling) "on" else "off"}, " +
                        "engaged: ${if (it.isTunnelingEnabled) "yes" else "no"}",
                )
            }
            // For the report: what audio the stream has, and whether this device can play it.
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
                (0 until g.length).map { i ->
                    val f = g.getTrackFormat(i)
                    val codec = f.sampleMimeType ?: f.codecs ?: "unknown"
                    val ok = if (g.isTrackSupported(i)) "plays here" else "NOT supported on this device"
                    val sel = if (g.isTrackSelected(i)) ", selected" else ""
                    "$codec ${if (f.channelCount > 0) "${f.channelCount}ch " else ""}" +
                        "${if (f.sampleRate > 0) "${f.sampleRate}Hz " else ""}($ok$sel)"
                }
            }
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { g ->
                (0 until g.length).map { i ->
                    val f = g.getTrackFormat(i)
                    val codec = f.sampleMimeType ?: f.codecs ?: "unknown"
                    val size = if (f.width > 0) "${f.width}x${f.height} " else ""
                    val ok = if (g.isTrackSupported(i)) "plays here" else "NOT supported on this device"
                    "$codec $size($ok${if (g.isTrackSelected(i)) ", selected" else ""})"
                }
            }
            Report.logOnce("video-${video.hashCode()}",
                if (video.isEmpty()) "Video: the stream has no video track" else "Video: " + video.take(6).joinToString("; "))
            Report.logOnce("audio-${audio.hashCode()}",
                if (audio.isEmpty()) "Audio: the stream has no audio track" else "Audio: " + audio.joinToString("; "))

            val opts = ArrayList<Pair<Tracks.Group, Int>>()
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) if (g.isTrackSupported(i)) opts.add(g to i)
            }
            qualityOptions = opts.sortedWith(
                compareByDescending<Pair<Tracks.Group, Int>> { it.first.getTrackFormat(it.second).height }
                    .thenByDescending { it.first.getTrackFormat(it.second).bitrate }
            )
        }
    }

    /** Logs the first downloads of each attempt (what, how big, how long) and any download errors. */
    private val downloads = object : AnalyticsListener {
        private fun kind(d: MediaLoadData) = when (d.dataType) {
            C.DATA_TYPE_MANIFEST -> "playlist"
            C.DATA_TYPE_MEDIA -> when (d.trackType) {
                C.TRACK_TYPE_VIDEO -> "video chunk"
                C.TRACK_TYPE_AUDIO -> "audio chunk"
                else -> "chunk"
            }
            C.DATA_TYPE_MEDIA_INITIALIZATION -> "init chunk"
            else -> "data"
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            Report.logOnce("vdec-$decoderName", "Video decoder: $decoderName")
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            Report.logOnce("adec-$decoderName", "Audio decoder: $decoderName")
        }

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
            if (loadsLogged >= 12) return
            loadsLogged++
            val secs = info.loadDurationMs / 1000.0
            val rate = if (info.loadDurationMs > 0) info.bytesLoaded * 8.0 / info.loadDurationMs / 1000.0 else 0.0
            Report.log(
                String.format(
                    java.util.Locale.US, "  got %s %s: %,d bytes in %.1fs (%.1f Mbps)",
                    kind(data), Report.short(info.uri.toString()), info.bytesLoaded, secs, rate,
                )
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            info: LoadEventInfo,
            data: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            val why = (error as? HttpDataSource.InvalidResponseCodeException)?.let { "server said ${it.responseCode}" }
                ?: (error.message ?: error.javaClass.simpleName).take(120)
            Report.log("  failed ${kind(data)} ${Report.short(info.uri.toString())}: $why")
        }
    }

    /** 12s in: lots of video waiting but nothing started -> the format is the problem; don't wait 20s. */
    private val startCheck = Runnable {
        val p = player ?: return@Runnable
        val buffered = (p.bufferedPosition - p.currentPosition) / 1000.0
        if (playingSince == 0L && !tunnelEngaged() && buffered >= 8.0) {
            logStall()
            Report.log(String.format(java.util.Locale.US,
                "%.0fs of video arrived but it wouldn't start: the stream's format looks like the problem, not the network", buffered))
            decodeProblem()
        }
    }

    /** Video arrived but won't play: try the software decoder once, then the site's own player. */
    /** After the audio-off experiment started the video: bring the audio back in mid-play. */
    private val audioBackOn = Runnable {
        val p = player ?: return@Runnable
        audioOff = false
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
        Report.log("Audio switched back on while playing")
    }

    /**
     * For the report: per decoder, how many frames came in, how many were shown/played, how many
     * were dropped as too late. "Received but none shown, many dropped late" = a timing problem.
     */
    private fun decoderStats(): String {
        val p = player ?: return "Decoders: no player"
        val v = p.videoDecoderCounters
        val a = p.audioDecoderCounters
        v?.ensureUpdated()
        a?.ensureUpdated()
        val vs = if (v == null) "video decoder never started" else
            "video frames received ${v.queuedInputBufferCount}, shown ${v.renderedOutputBufferCount}, " +
                "dropped late ${v.droppedBufferCount}, skipped ${v.skippedOutputBufferCount + v.skippedInputBufferCount}"
        val aus = if (a == null) "audio decoder never started" else
            "audio frames received ${a.queuedInputBufferCount}, played ${a.renderedOutputBufferCount}, skipped ${a.skippedInputBufferCount}"
        return String.format(java.util.Locale.US, "Decoders: %s; %s; position %.1fs, buffered to %.1fs",
            vs, aus, p.currentPosition / 1000.0, p.bufferedPosition / 1000.0)
    }

    private fun decodeProblem() {
        Report.log(decoderStats())
        val p = player
        // Read the decoder counters first: they say which remedy fits, so we don't walk the
        // whole list of experiments (which can take over a minute).
        if (decoderStuck()) {
            if (tunnelEngaged()) {
                retryWithoutTunneling("the video decoder takes frames but shows none")
            } else if (!preferSoftware) {
                preferSoftware = true
                Report.log("Diagnosis: the video decoder takes frames but shows none; switching to the software video decoder")
                play()
            } else {
                openCleanPlayer("even the software video decoder shows no picture")
            }
            return
        }
        if (!jumpTried && p != null && p.bufferedPosition - p.currentPosition > 4_000) {
            // The player may be waiting at a moment the downloaded video doesn't cover (the
            // playlist's timing disagrees with the video's own). Browsers jump to the video.
            jumpTried = true
            val target = p.bufferedPosition - 3_000
            Report.log(String.format(java.util.Locale.US,
                "Experiment: jumping from %.1fs to %.1fs, where the downloaded video is",
                p.currentPosition / 1000.0, target / 1000.0))
            p.seekTo(target)
            main.removeCallbacks(startCheck)
            main.postDelayed(startCheck, 10_000)
            return
        }
        if (jumpTried && playingSince == 0L) Report.log("Experiment result: jumping ahead didn't help")
        if (!audioOffTried) {
            audioOffTried = true
            audioOff = true
            Report.log("Experiment: retrying with the audio switched off, to see if the audio is what's holding it back")
            play()
            return
        }
        if (audioOff) Report.log("Experiment result: still stuck with the audio off, so the audio isn't the blocker")
        audioOff = false
        // No frames ever reached the decoder, so a different decoder can't help; the browser's
        // more forgiving player usually can.
        openCleanPlayer("video arrived but never reached the decoder")
    }

    /** Frames go into the video decoder but no picture ever comes out. */
    private fun decoderStuck(): Boolean {
        val v = player?.videoDecoderCounters ?: return false
        v.ensureUpdated()
        return v.queuedInputBufferCount >= 5 && v.renderedOutputBufferCount == 0
    }

    /** Tunneling was requested AND the player actually uses it for the current tracks. */
    private fun tunnelEngaged(): Boolean = tunneling && player?.isTunnelingEnabled == true

    private val stallCheck = Runnable {
        Report.log("Stalled for ${STALL_MS / 1000}s")
        logStall()
        val buffered = player?.let { (it.bufferedPosition - it.currentPosition) / 1000.0 } ?: 0.0
        if (tunnelEngaged() && !firstFrame) {
            retryWithoutTunneling("stalled before the first picture")
        } else if (playingSince == 0L && buffered >= 8.0) {
            // Plenty of video arrived but it never started: the stream's format (or a quirk in it)
            // is something this decoder can't handle. Reconnecting won't help; a browser often can.
            Report.log(String.format(java.util.Locale.US,
                "%.0fs of video arrived but it wouldn't start: the stream's format looks like the problem, not the network", buffered))
            decodeProblem()
        } else if (playingSince == 0L && useChromeNet && ChromeNet.engine != null) {
            // Never started with Chrome's engine: some servers work badly with it. Try once without.
            useChromeNet = false
            Report.log("No progress with Chrome's network engine; retrying with standard networking")
            play()
        } else {
            recover("stalled")
        }
    }

    /** Playing, but no picture ever appeared: a known tunneling bug on some boxes. */
    private val firstFrameCheck = Runnable {
        if (tunneling && !firstFrame && player?.isPlaying == true) {
            retryWithoutTunneling("no picture after ${FIRST_FRAME_MS / 1000}s")
        }
    }

    /**
     * On by default. The saved choice only changes from the phone remote; if tunneling misbehaves
     * the automatic fallback only turns it off until the app is restarted.
     */
    private fun tunnelingAllowed(): Boolean {
        if (tunnelOffThisRun) return false
        return getSharedPreferences("streamlink", MODE_PRIVATE).getBoolean("tunnel_on", true)
    }

    /** For the report: is it the connection or the stream? */
    private fun logStall() {
        Report.log(decoderStats())
        stalls++
        val have = DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate
        val need = player?.videoFormat?.bitrate ?: -1
        val mbps = { b: Long -> String.format(java.util.Locale.US, "%.1f Mbps", b / 1_000_000.0) }
        val ahead = player?.let { (it.bufferedPosition - it.currentPosition) / 1000.0 } ?: 0.0
        Report.log(
            "Buffering (stall #$stalls): connection ≈ ${mbps(have)}" +
                (if (need > 0) ", this quality needs ≈ ${mbps(need.toLong())}" else "") +
                String.format(java.util.Locale.US, ", %.1fs of video was buffered", ahead)
        )
    }

    private fun smoothLiveAllowed(): Boolean =
        getSharedPreferences("streamlink", MODE_PRIVATE).getBoolean("smooth_live", true)

    private fun setSmoothLive(on: Boolean) {
        getSharedPreferences("streamlink", MODE_PRIVATE).edit().putBoolean("smooth_live", on).apply()
        Report.log("Live delay set to ${if (on) "smooth" else "low delay"} from the phone")
        play()
    }

    private fun setTunneling(on: Boolean) {
        tunnelOffThisRun = false
        getSharedPreferences("streamlink", MODE_PRIVATE).edit()
            .putBoolean("tunnel_on", on).remove("tunnel_strikes").apply()
        Report.log("Tunneled playback turned ${if (on) "on" else "off"} from the phone")
        play()
    }

    private fun retryWithoutTunneling(why: String) {
        // Only for this app run: the saved preference is changed only by the phone remote switch.
        tunnelOffThisRun = true
        Report.log("Tunneled playback misbehaved ($why); retrying with it off")
        play()
    }

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            Report.log("Fell behind the live point; jumping back to live")
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        if (tunneling && !firstFrame && error.errorCode in tunnelSuspects) {
            Report.log("Playback error: ${error.errorCodeName}")
            retryWithoutTunneling(error.errorCodeName)
            return
        }
        val httpError = error.cause as? HttpDataSource.InvalidResponseCodeException
        val http = httpError?.responseCode
        val why = if (http != null) "server said $http" else error.errorCodeName
        httpError?.dataSpec?.uri?.let { Report.log("  …for ${Report.short(it.toString())}") }
        val detail = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.firstOrNull()?.take(200)
        Report.log("Playback error: $why" + if (detail != null) " ($detail)" else "")

        val src = set?.sources?.getOrNull(sourceIndex)
        // Only "not allowed" answers can be fixed by claiming a different page; "not found" can't.
        if ((http == 401 || http == 403) && playingSince == 0L &&
            src != null && refererIndex + 1 < src.referers.size
        ) {
            refererIndex++
            play()
            return
        }
        recover(why)
    }

    private fun recover(why: String) {
        val s = set ?: return finish()
        val now = System.currentTimeMillis()
        // It had been playing fine for a while: this is a fresh problem, not a repeat.
        if (playingSince != 0L && now - playingSince > 60_000) reconnects = 0

        if (playingSince == 0L && sourceIndex + 1 < s.sources.size) {
            sourceIndex++
            refererIndex = 0
            Report.log("Trying the next source")
            play()
            return
        }
        if (reconnects < MAX_RECONNECTS) {
            reconnects++
            reconnect(why)
            return
        }
        openCleanPlayer(why)
    }

    /** Reload the page invisibly to get a fresh stream link (they often expire), then resume at live. */
    private fun reconnect(why: String) {
        release()
        playingSince = 0
        val page = set?.pageUrl ?: return finish()
        showCover("Reconnecting…\n($why)")
        AppStatus.text = "Reconnecting to ${Session.title}…"
        Report.log("Reconnecting (try $reconnects of $MAX_RECONNECTS): reloading the page for a fresh stream link")
        val gen = linkGen
        main.postDelayed({
            if (gen != linkGen) return@postDelayed
            sniffer.sniff(page) { fresh ->
                if (fresh.sources.isEmpty()) {
                    Report.log("No stream found while reconnecting")
                    if (reconnects < MAX_RECONNECTS) {
                        reconnects++
                        reconnect(why)
                    } else {
                        openCleanPlayer(why)
                    }
                } else {
                    set = fresh
                    Session.current = fresh
                    sourceIndex = 0
                    refererIndex = 0
                    play()
                }
            }
        }, 1500L * reconnects.coerceAtLeast(1))
    }

    /**
     * A new link arrived while this screen is showing. Find its stream with this screen's own
     * page loader: the link screen is in the background, where pages often won't start a video.
     */
    private fun openLink(url: String) {
        linkGen++
        release()
        val site = Report.host(url)
        showCover("Finding the video on $site…")
        AppStatus.text = "Finding the video on $site…"
        sniffer.sniff(url) { found ->
            if (found.sources.isEmpty()) {
                openSitePlayer("no direct stream found", found.pageUrl)
            } else {
                Session.current = found
                loadSession()
            }
        }
    }

    /** Playback failed: next try is the clean browser player (hls.js), which falls back to the site itself. */
    private fun openCleanPlayer(why: String) {
        val s = set
        if (s == null || s.sources.isEmpty()) return openSitePlayer(why)
        Report.log("Switching to the clean browser player ($why)")
        release()
        startActivity(CleanPlayerActivity.intent(this, sourceIndex))
        finish()
    }

    private fun openSitePlayer(why: String, pageUrl: String? = null) {
        val page = pageUrl ?: set?.pageUrl ?: return finish()
        Report.log("Switching to the site's own player ($why)")
        release()
        startActivity(SitePlayerActivity.intent(this, page))
        finish()
    }

    private fun release() {
        main.removeCallbacks(audioBackOn)
        main.removeCallbacks(startCheck)
        main.removeCallbacks(stallCheck)
        main.removeCallbacks(firstFrameCheck)
        playerView.player = null
        player?.removeListener(listener)
        player?.removeAnalyticsListener(downloads)
        player?.release()
        player = null
    }

    // ---- Phone remote ----

    private fun onCommand(c: String, v: String?) {
        val p = player
        when (c) {
            "toggle" -> p?.let { it.playWhenReady = !it.playWhenReady }
            "play" -> p?.playWhenReady = true
            "pause" -> p?.playWhenReady = false
            "live" -> p?.seekToDefaultPosition()
            "reload" -> {
                reconnects = 1
                reconnect("requested from phone")
            }
            "stop" -> finish()
            "quality" -> setQuality(v?.toIntOrNull() ?: -1)
            "source" -> v?.toIntOrNull()?.let { switchSource(it) }
            "site" -> openSitePlayer("requested from phone")
            "clean" -> openCleanPlayer("requested from phone")
            "open" -> v?.let { openLink(it) }
            "tunnel" -> setTunneling(v == "on")
            "delay" -> setSmoothLive(v != "low")
        }
        publishSnapshot()
    }

    private fun setQuality(i: Int) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (i < 0) {
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            val (g, idx) = qualityOptions.getOrNull(i) ?: return
            params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, idx))
        }
        p.trackSelectionParameters = params.build()
        qualityChoice = i
        Report.log("Quality set to ${if (i < 0) "Auto" else qualityLabels().getOrNull(i)}")
    }

    private fun qualityLabels(): List<String> = qualityOptions.map { (g, i) ->
        val f = g.getTrackFormat(i)
        val res = if (f.height > 0) "${f.height}p" else "Video"
        if (f.bitrate > 0) "$res · ${String.format(java.util.Locale.US, "%.1f", f.bitrate / 1_000_000.0)} Mbps" else res
    }

    private fun switchSource(i: Int) {
        val s = set ?: return
        if (i !in s.sources.indices) return
        Report.log("Switching to source ${i + 1}")
        sourceIndex = i
        refererIndex = 0
        reconnects = 0
        playingSince = 0
        play()
    }

    private val snapshotTick = object : Runnable {
        override fun run() {
            publishSnapshot()
            main.postDelayed(this, 1000)
        }
    }

    private fun publishSnapshot() {
        val p = player
        Session.snapshot = PlayerSnapshot(
            playing = p?.isPlaying == true,
            buffering = p?.playbackState == Player.STATE_BUFFERING,
            live = p?.isCurrentMediaItemLive == true,
            qualities = qualityLabels(),
            quality = qualityChoice,
            sources = set?.sources?.map { it.label } ?: emptyList(),
            source = sourceIndex,
            tunneling = tunneling,
            smoothLive = smoothLive,
        )
    }

    // ---- Screen ----

    private fun showCover(text: String) {
        coverText.text = text
        cover.visibility = View.VISIBLE
    }

    private fun hideCover() {
        cover.visibility = View.GONE
    }

    private fun showPicker() {
        val s = set ?: return
        pickerList.removeAllViews()
        val density = resources.displayMetrics.density
        s.sources.forEachIndexed { i, src ->
            val item = TextView(this).apply {
                text = "${i + 1}. ${src.label}" + if (i == sourceIndex) "   (playing)" else ""
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setBackgroundResource(R.drawable.recent_item)
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    hidePicker()
                    switchSource(i)
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (8 * density).toInt()
            pickerList.addView(item, lp)
        }
        picker.visibility = View.VISIBLE
        pickerList.getChildAt(sourceIndex)?.requestFocus()
    }

    private fun hidePicker() {
        picker.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (picker.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) hidePicker()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && (set?.sources?.size ?: 0) > 1 &&
            !playerView.isControllerFullyVisible
        ) {
            if (event.action == KeyEvent.ACTION_UP) showPicker()
            return true
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }
}
