package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ScrollView
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
        const val TUNNEL_STRIKES = 2

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
    private lateinit var root: FrameLayout
    private lateinit var cover: FrameLayout
    private lateinit var coverSub: TextView
    private lateinit var coverTitle: TextView
    private lateinit var coverSteps: LinearLayout
    private lateinit var coverHint: TextView
    private lateinit var coverText: TextView
    private lateinit var coverCancel: TextView
    private lateinit var coverDetails: TextView
    private lateinit var picker: View
    private lateinit var pickerList: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var ovBadge: TextView
    private lateinit var ovSub: TextView
    private lateinit var ovTitle: TextView
    private lateinit var ovTrack: FrameLayout
    private lateinit var ovFill: View
    private lateinit var ovStatus: TextView
    private lateinit var btnLive: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnOptions: TextView
    private lateinit var btnOther: TextView
    private lateinit var btnPause: TextView
    private lateinit var hintBox: LinearLayout
    private lateinit var hintHeader: TextView
    private lateinit var hintUp: TextView
    private lateinit var hintDown: TextView
    private var surfPrev: ListItem? = null
    private var surfNext: ListItem? = null
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
    /** Low delay kept running dry: smooth delay for the rest of this play. */
    private var smoothThisRun = false
    private val emptyStallTimes = ArrayList<Long>()
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
        root = findViewById(R.id.root)
        buildCover()
        buildOverlay()
        buildPicker()
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
        countedStrikeThisPlay = false // one tunneling strike per stream, however many retries
        smoothThisRun = false
        emptyStallTimes.clear()
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
        // From a saved list the event title (set by the waiting screen) stays; otherwise show the site.
        if (Session.listId == null || Session.listItemUrl == null || Session.title.isBlank()) Session.title = Report.host(s.pageUrl)
        if (s.sources.size > 1) {
            Toast.makeText(this, "${s.sources.size} sources found · OK, then Audio & quality to switch", Toast.LENGTH_LONG).show()
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
        smoothLive = smoothLiveAllowed() || smoothThisRun
        Report.logOnce("delay-$smoothLive", "Live delay: ${if (smoothLive) "smooth (~20s behind live)" else "low delay"}")

        // Decoders: hardware first as usual (fast), falling back to another decoder if one fails to
        // start. After a stream "arrives but won't play", software decoders are tried first.
        AudioSync.load(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink? =
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { AudioSync.Sink(it) }
        }.setEnableDecoderFallback(true)
        if (AudioSync.offsetMs != 0) Report.logOnce("sync-${AudioSync.offsetMs}", "Audio sync: ${AudioSync.describe(AudioSync.offsetMs)}")
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
        showCover(if (reconnects == 0) "Loading…" else "Reconnecting…", 2)
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
        main.postDelayed(earlyDecoderCheck, 6_000)
        UnwrapDataSource.undecodable.set(0)
        disguiseChecks = 0
        main.removeCallbacks(disguiseCheck)
        main.postDelayed(disguiseCheck, 1_000)
        starveSamples.clear()
        main.removeCallbacks(audioStarveCheck)
        main.postDelayed(audioStarveCheck, 2_000)
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
                    if (playingSince != 0L) {
                        logStall()
                        tunnelStallCheck()
                        lowDelayCheck()
                    }
                }
                Player.STATE_ENDED -> {
                    if (player?.isCurrentMediaItemLive == true) {
                        Report.log("Live stream stopped unexpectedly")
                        recover("stream ended")
                    } else {
                        Report.log("Video finished")
                        AppStatus.text = "Finished: ${Session.title}"
                        showCover("Finished\nPress Back for the link screen", finished = true)
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
    /** 6s in: frames already going into the decoder with no picture out -> switch decoders now. */
    private val earlyDecoderCheck = Runnable {
        if (playingSince == 0L && player != null && !tunnelEngaged() && decoderStuck()) {
            Report.log("Early check: frames reach the video decoder but no picture comes out")
            main.removeCallbacks(startCheck)
            decodeProblem()
        }
    }

    /**
     * The video pieces are pictures hiding the video in a way the app can't unpack: waiting for stalls
     * only wastes time, and the clean player uses the same unpacking. Go to the site's own player,
     * whose page knows how to read its pieces.
     */
    private var disguiseChecks = 0
    private val disguiseCheck = object : Runnable {
        override fun run() {
            if (player == null || playingSince != 0L) return
            if (UnwrapDataSource.undecodable.get() >= 2) {
                Report.log("The video pieces are disguised as pictures in a way the app can't unpack yet")
                openSitePlayer("disguised video the app can't read")
                return
            }
            if (++disguiseChecks < 40) main.postDelayed(this, 1_000)
        }
    }

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

    /**
     * Tunneled playback that keeps "buffering" with plenty of video buffered: on this box the audio clock
     * stalls (audio frames stop arriving while video keeps coming) and the picture waits for it.
     * Two such stalls within 30 s, each with >= 6 s buffered, and it's tunneling, not the network.
     */
    private var bufferedStallTimes = ArrayList<Long>()
    private fun tunnelStallCheck() {
        if (!tunnelEngaged()) return
        val p = player ?: return
        val ahead = (p.bufferedPosition - p.currentPosition) / 1000.0
        if (ahead < 6.0) return
        val now = System.currentTimeMillis()
        bufferedStallTimes.add(now)
        bufferedStallTimes.removeAll { now - it > 30_000 }
        if (bufferedStallTimes.size >= 2) {
            bufferedStallTimes.clear()
            retryWithoutTunneling(String.format(java.util.Locale.US, "keeps stalling with %.0fs buffered", ahead))
        }
    }

    private fun retryWithoutTunneling(why: String) {
        tunnelOffThisRun = true
        main.removeCallbacks(audioStarveCheck)
        Report.log("Tunneled playback misbehaved ($why); retrying with it off")
        // Twice in separate plays: this TV and tunneling don't get along. Save it as off (the phone
        // or the TV's Audio & quality panel turns it back on).
        val prefs = getSharedPreferences("streamlink", MODE_PRIVATE)
        if (!countedStrikeThisPlay) {
            countedStrikeThisPlay = true
            val strikes = prefs.getInt("tunnel_strikes", 0) + 1
            if (strikes >= TUNNEL_STRIKES && prefs.getBoolean("tunnel_on", true)) {
                prefs.edit().putBoolean("tunnel_on", false).putInt("tunnel_strikes", 0).apply()
                Report.log("Tunneled playback caused problems in $strikes plays: turned off for good (turn it back on from the phone)")
                Toast.makeText(this, "Tunneled playback turned off: it caused problems on this TV", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putInt("tunnel_strikes", strikes).apply()
            }
        }
        play()
    }

    private var countedStrikeThisPlay = false

    /**
     * Tunneled playback's failure on this box, caught early: video keeps arriving but the audio stops
     * (and the picture then waits for the frozen audio clock). Checked every 2 s; over the last 4 s,
     * 40+ new video frames and at most 2 audio frames means tunneling, not the stream.
     */
    private val starveSamples = ArrayList<LongArray>()
    private val audioStarveCheck = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!tunnelEngaged()) return
            val v = p.videoDecoderCounters
            val a = p.audioDecoderCounters
            if (v != null && a != null && p.playWhenReady) {
                v.ensureUpdated(); a.ensureUpdated()
                val now = System.currentTimeMillis()
                starveSamples.add(longArrayOf(now, v.queuedInputBufferCount.toLong(), a.queuedInputBufferCount.toLong()))
                starveSamples.removeAll { now - it[0] > 4_500 }
                val first = starveSamples.first()
                if (now - first[0] >= 3_500 && starveSamples.last()[1] - first[1] >= 40 && starveSamples.last()[2] - first[2] <= 2 &&
                    p.isCurrentMediaItemLive
                ) {
                    starveSamples.clear()
                    retryWithoutTunneling("the audio stopped while the video kept coming")
                    return
                }
            }
            main.postDelayed(this, 2_000)
        }
    }

    /** Low delay running dry (6 s pieces that take seconds to download): smooth delay for this play. */
    private fun lowDelayCheck() {
        if (smoothLive) return
        val p = player ?: return
        if ((p.bufferedPosition - p.currentPosition) > 1_000) return
        val now = System.currentTimeMillis()
        emptyStallTimes.add(now)
        emptyStallTimes.removeAll { now - it > 60_000 }
        if (emptyStallTimes.size >= 2) {
            emptyStallTimes.clear()
            smoothThisRun = true
            Report.log("Low delay keeps running out of video; using the smooth delay for this stream")
            play()
        }
    }

    private fun adjustSync(v: String) {
        val ms = when (v) {
            "later", "+" -> AudioSync.offsetMs + AudioSync.STEP_MS
            "earlier", "-" -> AudioSync.offsetMs - AudioSync.STEP_MS
            "reset", "0" -> 0
            else -> v.toIntOrNull() ?: return
        }
        AudioSync.set(this, ms)
        Report.log("Audio sync: ${AudioSync.describe(AudioSync.offsetMs)}")
        if (tunnelEngaged() && AudioSync.offsetMs != 0) {
            Toast.makeText(this, "Audio sync only works with tunneled playback off", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Audio sync: ${AudioSync.describe(AudioSync.offsetMs)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            Report.log("Fell behind the live point; jumping back to live")
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        // Audio output errors under tunneling: the box's tunneled audio path refuses some streams (e.g.
        // low sample rates) even after the picture started. Retry this same source with tunneling off.
        val audioOut = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        // "Stuck playing with no progress": on this box, tunneled playback whose audio clock froze.
        val stuck = error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT && tunnelEngaged()
        if (stuck || (tunneling && (!firstFrame || audioOut) && error.errorCode in tunnelSuspects)) {
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
        // A direct link arriving now is not part of any saved list.
        Session.listId = null; Session.listName = ""; Session.listItemUrl = null
        Session.title = site
        showCover("Finding the video on $site…", 1)
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
        main.removeCallbacks(earlyDecoderCheck)
        main.removeCallbacks(disguiseCheck)
        main.removeCallbacks(audioStarveCheck)
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
            "sync" -> v?.let { adjustSync(it) }
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


    // ---- Screen: loading cover ----

    private fun dp(v: Float) = Ui.dp(this, v)

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        Ui.text(this, label, 12f, if (primary) Ui.ON_ACCENT else Ui.TEXT, bold = true).apply {
            background = if (primary) Ui.primaryButton(context) else Ui.focusable(context, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            Ui.growOnFocus(this)
        }

    private fun gap(h: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(h) }

    private fun buildCover() {
        cover = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        coverSub = Ui.text(this, "", 12f, Ui.TEXT_2).apply { gravity = Gravity.CENTER }
        coverTitle = Ui.text(this, "", 30f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40f), 0, dp(40f), 0)
        }
        coverSteps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        coverHint = Ui.text(this, "Usually ready in under 10 seconds", 12f, Ui.TEXT_3)
        coverText = Ui.text(this, "", 14f, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(40f), 0, dp(40f), 0)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        coverCancel = button("Cancel") { finish() }
        coverDetails = button("Show details") {
            val show = coverText.visibility != View.VISIBLE
            coverText.visibility = if (show) View.VISIBLE else View.GONE
            coverDetails.text = if (show) "Hide details" else "Show details"
        }
        buttons.addView(coverCancel)
        buttons.addView(coverDetails, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(9f) })
        col.addView(coverSub)
        col.addView(coverTitle, gap(4f))
        col.addView(coverSteps, gap(26f))
        col.addView(coverHint, gap(26f))
        col.addView(buttons, gap(26f))
        col.addView(coverText, gap(18f))
        cover.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** [active] is the step in progress (0 = Opening page, 1 = Finding the stream, 2 = Starting video); earlier ones are checked. */
    private fun renderSteps(active: Int) {
        coverSteps.removeAllViews()
        val names = listOf("Opening page", "Finding the stream", "Starting video")
        names.forEachIndexed { i, n ->
            if (i > 0) {
                coverSteps.addView(
                    View(this).apply { setBackgroundColor(if (i <= active) Ui.ACCENT else 0xFF2E3A4D.toInt()) },
                    LinearLayout.LayoutParams(dp(52f), dp(2f)).apply { bottomMargin = dp(22f) },
                )
            }
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val circle = Ui.text(this, if (i < active) "✓" else "", 13f, Ui.ON_ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = when {
                    i < active -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
                    i == active -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2f), Ui.ACCENT) }
                    else -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2f), 0xFF2E3A4D.toInt()) }
                }
            }
            c.addView(circle, LinearLayout.LayoutParams(dp(25f), dp(25f)))
            c.addView(Ui.text(this, n, 12f, Ui.TEXT, bold = i == active).apply { gravity = Gravity.CENTER }, gap(7f))
            coverSteps.addView(c, LinearLayout.LayoutParams(dp(128f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun listLabel(): String {
        val host = set?.let { Report.host(it.pageUrl) } ?: ""
        return if (Session.listId != null && Session.listName.isNotBlank()) Session.listName else host
    }

    /** The technical status text now sits behind "Show details" (coverText). */
    private fun showCover(text: String, stage: Int = 2, finished: Boolean = false) {
        coverText.text = text
        coverSub.text = listLabel()
        coverTitle.text = Session.title.ifBlank { listLabel() }
        coverSteps.visibility = if (finished) View.GONE else View.VISIBLE
        coverHint.visibility = if (finished) View.GONE else View.VISIBLE
        coverDetails.visibility = if (finished) View.GONE else View.VISIBLE
        if (finished) coverText.visibility = View.VISIBLE
        coverCancel.text = if (finished) "Back" else "Cancel"
        if (!finished) renderSteps(stage)
        hideOverlayNow()
        val was = cover.visibility == View.VISIBLE
        cover.visibility = View.VISIBLE
        // Not "Cancel" while loading: an impatient OK press must not stop the stream. ("Back" when finished.)
        if (!was || finished) (if (finished) coverCancel else coverDetails).requestFocus()
    }

    private fun hideCover() {
        if (cover.visibility != View.VISIBLE) return
        cover.visibility = View.GONE
        coverText.visibility = View.GONE
        coverDetails.text = "Show details"
        if (picker.visibility != View.VISIBLE) showOverlay()
    }

    // ---- Screen: info overlay ----

    private val hideOverlayRunnable = Runnable { hideOverlayNow() }
    private val overlayTick = object : Runnable {
        override fun run() {
            if (overlay.visibility != View.VISIBLE) return
            refreshProgress()
            main.postDelayed(this, 500)
        }
    }

    private fun buildOverlay() {
        overlay = FrameLayout(this).apply { visibility = View.GONE }
        // darkening gradient behind the text
        overlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF50E1116.toInt(), 0x000E1116))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(195f), Gravity.BOTTOM))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ovBadge = Ui.badge(this, "LIVE", live = true, sp = 10.5f)
        ovSub = Ui.text(this, "", 12f, 0xFFC9CFD8.toInt())
        meta.addView(ovBadge)
        meta.addView(ovSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(9f) })
        ovTitle = Ui.text(this, "", 30f, Ui.TEXT, bold = true).apply { maxLines = 2 }

        val progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ovTrack = FrameLayout(this).apply { background = Ui.rounded(context, 0xFF2E3A4D.toInt(), 2f) }
        ovFill = View(this).apply { background = Ui.rounded(context, Ui.ACCENT, 2f) }
        ovTrack.addView(ovFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        ovStatus = Ui.text(this, "", 11.5f, 0xFFC9CFD8.toInt())
        progress.addView(ovTrack, LinearLayout.LayoutParams(0, dp(4.5f), 1f))
        progress.addView(ovStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(10f) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnLive = button("Jump to live", primary = true) { player?.seekToDefaultPosition(); scheduleHide() }
        btnBack = button("Back") { backToList() }
        btnOptions = button("Audio & quality") { showOptions() }
        btnOther = button("Try another player") { openCleanPlayer("chosen from the player menu") }
        btnPause = button("Pause") { player?.let { it.playWhenReady = !it.playWhenReady }; refreshProgress(); scheduleHide() }
        for (b in listOf(btnLive, btnBack, btnOptions, btnOther, btnPause)) {
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(9f) })
        }
        col.addView(meta)
        col.addView(ovTitle, gap(12f))
        col.addView(progress, gap(12f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
        col.addView(row, gap(12f))
        overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { marginStart = dp(42f); marginEnd = dp(42f); bottomMargin = dp(30f) })

        // "UP NEXT LIVE IN <LIST>" box (channel surfing hint)
        hintBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, 0xE00E1116.toInt(), 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            visibility = View.GONE
        }
        hintHeader = Ui.text(this, "", 10f, Ui.TEXT_2, bold = true).apply { letterSpacing = 0.08f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        hintUp = Ui.text(this, "", 12f, Ui.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        hintDown = Ui.text(this, "", 12f, Ui.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        hintBox.addView(hintHeader)
        hintBox.addView(hintUp, gap(6f))
        hintBox.addView(hintDown, gap(6f))
        overlay.addView(hintBox, FrameLayout.LayoutParams(dp(225f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { topMargin = dp(30f); marginEnd = dp(36f) })

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun currentListItem(): ListItem? {
        val id = Session.listId ?: return null
        return SavedLists.cached(this, id)?.result?.items?.firstOrNull { it.url == Session.listItemUrl }
    }

    /** The previous (▲) and next (▼) LIVE event in the playing list, wrapping around; null when there is none other. */
    private fun computeSurf(): Pair<ListItem?, ListItem?> {
        val id = Session.listId ?: return null to null
        val now = System.currentTimeMillis()
        val items = ListJson.visible(SavedLists.cached(this, id)?.result?.items ?: return null to null, now)
        val cur = items.indexOfFirst { it.url == Session.listItemUrl }
        val n = items.size
        fun find(step: Int): ListItem? {
            val start = if (cur >= 0) cur else if (step > 0) -1 else n
            var i = start
            repeat(n) {
                i = ((i + step) % n + n) % n
                val cand = items[i]
                if (cand.url != Session.listItemUrl && ListStatus.of(cand, now) == ListStatus.LIVE) return cand
            }
            return null
        }
        if (n == 0) return null to null
        return find(-1) to find(1)
    }

    private fun showOverlay() {
        if (cover.visibility == View.VISIBLE || picker.visibility == View.VISIBLE) return
        val first = overlay.visibility != View.VISIBLE
        val live = player?.isCurrentMediaItemLive == true
        val host = set?.let { Report.host(it.pageUrl) } ?: ""
        val started = currentListItem()?.startMillis?.let { System.currentTimeMillis() - it }?.takeIf { it > 0 }
        ovBadge.visibility = if (live) View.VISIBLE else View.GONE
        ovSub.text = listOfNotNull(listLabel().takeIf { it.isNotBlank() }, started?.let { startedText(it) }).joinToString(" · ")
        ovTitle.text = Session.title.ifBlank { host }
        btnLive.visibility = if (live) View.VISIBLE else View.GONE
        btnBack.text = if (Session.listId != null) "Back to list" else "Back"
        val (prev, next) = computeSurf()
        surfPrev = prev; surfNext = next
        if (prev != null || next != null) {
            hintHeader.text = "UP NEXT LIVE IN ${Session.listName.ifBlank { "LIST" }}".uppercase()
            hintUp.text = "▲  " + (prev?.let { ListText.displayTitle(it) } ?: "")
            hintDown.text = "▼  " + (next?.let { ListText.displayTitle(it) } ?: "")
            hintUp.visibility = if (prev != null) View.VISIBLE else View.GONE
            hintDown.visibility = if (next != null) View.VISIBLE else View.GONE
            hintBox.visibility = View.VISIBLE
        } else {
            hintBox.visibility = View.GONE
        }
        refreshProgress()
        overlay.visibility = View.VISIBLE
        if (first) {
            (if (live) btnLive else btnBack).requestFocus()
            main.removeCallbacks(overlayTick)
            main.post(overlayTick)
        }
        scheduleHide()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideOverlayRunnable)
        main.postDelayed(hideOverlayRunnable, 5_000)
    }

    private fun hideOverlayNow() {
        main.removeCallbacks(hideOverlayRunnable)
        main.removeCallbacks(overlayTick)
        if (::overlay.isInitialized) overlay.visibility = View.GONE
    }

    private fun startedText(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just started"
            min < 60 -> "started $min min ago"
            else -> "started ${min / 60} h ${min % 60} min ago"
        }
    }

    private fun mmss(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return if (t >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60)
        else String.format(java.util.Locale.US, "%d:%02d", t / 60, t % 60)
    }

    private fun refreshProgress() {
        val p = player
        btnPause.text = if (p?.playWhenReady == false) "Play" else "Pause"
        var fraction = 1f
        if (p == null) {
            ovStatus.text = ""
        } else if (p.isCurrentMediaItemLive) {
            val off = p.currentLiveOffset
            ovStatus.text = if (off == C.TIME_UNSET) "Live" else if (off < 1000) "At live" else "${off / 1000} s behind live"
            val dur = p.duration
            fraction = if (dur != C.TIME_UNSET && dur > 0) (p.currentPosition.toFloat() / dur) else if (off != C.TIME_UNSET) 1f - (off / 600_000f) else 1f
        } else {
            val dur = p.duration
            ovStatus.text = if (dur != C.TIME_UNSET && dur > 0) "${mmss(p.currentPosition)} / ${mmss(dur)}" else mmss(p.currentPosition)
            fraction = if (dur != C.TIME_UNSET && dur > 0) p.currentPosition.toFloat() / dur else 0f
        }
        val w = ovTrack.width
        ovFill.layoutParams = (ovFill.layoutParams as FrameLayout.LayoutParams).apply {
            width = (w * fraction.coerceIn(0f, 1f)).toInt()
        }
    }

    private fun backToList() {
        val id = Session.listId
        release()
        if (id != null) startActivity(ListActivity.intent(this, id))
        finish()
    }

    /** Channel surfing: stop this event and open the other one the same way the home screen does. */
    private fun surfTo(item: ListItem) {
        val id = Session.listId ?: return
        Report.log("Channel surfing to ${Report.short(item.url)}")
        release()
        startActivity(WaitingActivity.intent(this, item, id, Session.listName))
        finish()
    }

    // ---- Screen: options panel (sources, quality, tunneling, live delay, reload) ----

    private fun buildPicker() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, 0xF2171B22.toInt(), 14f, Ui.BORDER, 1f)
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            visibility = View.GONE
        }
        panel.addView(Ui.text(this, "Audio & quality", 18f, Ui.TEXT, bold = true))
        pickerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = false }
        scroll.addView(pickerList)
        panel.addView(scroll, gap(10f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT; height = dp(300f) })
        picker = panel
        root.addView(panel, FrameLayout.LayoutParams(dp(390f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun showOptions() {
        val s = set ?: return
        hideOverlayNow()
        pickerList.removeAllViews()
        var focusRow: View? = null
        fun label(t: String) = pickerList.addView(Ui.sectionLabel(this, t), gap(10f))
        fun row(t: String, current: Boolean, action: () -> Unit): TextView {
            val v = Ui.text(this, (if (current) "✓  " else "") + t, 13f, Ui.TEXT, bold = current).apply {
                background = Ui.focusable(context, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                isFocusable = true
                isClickable = true
                setOnClickListener { hidePicker(); action() }
            }
            pickerList.addView(v, gap(5f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
            if (current && focusRow == null) focusRow = v
            return v
        }
        if (s.sources.size > 1) {
            label("Source")
            s.sources.forEachIndexed { i, src -> row("${i + 1}. ${src.label}", i == sourceIndex) { switchSource(i) } }
        }
        if (qualityOptions.isNotEmpty()) {
            label("Quality")
            row("Auto", qualityChoice < 0) { setQuality(-1) }
            qualityLabels().forEachIndexed { i, l -> row(l, qualityChoice == i) { setQuality(i) } }
        }
        label("Playback")
        row("Tunneled playback: ${if (tunneling) "on" else "off"} (tap to turn ${if (tunneling) "off" else "on"})", false) { setTunneling(!tunneling) }
        row("Live delay: ${if (smoothLive) "smooth (~20 s behind)" else "low delay"} (tap to change)", false) { setSmoothLive(!smoothLive) }
        label("Audio sync (${AudioSync.describe(AudioSync.offsetMs)})")
        row("Sound comes after the picture: +${AudioSync.STEP_MS} ms", false) { adjustSync("later"); showOptions() }
        row("Sound comes before the picture: −${AudioSync.STEP_MS} ms", false) { adjustSync("earlier"); showOptions() }
        if (AudioSync.offsetMs != 0) row("Reset audio sync", false) { adjustSync("reset"); showOptions() }
        row("Reload the stream", false) { reconnects = 1; reconnect("requested from the TV") }
        picker.visibility = View.VISIBLE
        (focusRow ?: pickerList.getChildAt(1) ?: pickerList.getChildAt(0))?.requestFocus()
    }

    private fun hidePicker() {
        picker.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val up = event.action == KeyEvent.ACTION_UP
        if (picker.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (up) hidePicker()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (cover.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)

        // Media keys always work.
        val media = when (k) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            else -> null
        }
        if (media != null) {
            if (up) { onCommand(media, null); showOverlay() }
            return true
        }

        if (overlay.visibility == View.VISIBLE) {
            scheduleHide()
            return super.dispatchKeyEvent(event)
        }

        // Overlay hidden.
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val (prev, next) = computeSurf()
                val target = if (k == KeyEvent.KEYCODE_DPAD_UP) prev else next
                if (target != null) {
                    if (up) surfTo(target)
                    return true
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP && (set?.sources?.size ?: 0) > 1) {
                    if (up) showOptions()
                    return true
                }
                if (up) showOverlay()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (up) showOverlay()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
