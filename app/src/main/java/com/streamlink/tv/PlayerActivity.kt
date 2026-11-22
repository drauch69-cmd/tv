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
        /** A stall check that finds a piece still arriving waits this long and looks again, up to [MAX_STALL_EXTENSIONS] times. */
        private const val STALL_EXTENSION_MS = 10_000L
        private const val MAX_STALL_EXTENSIONS = 3
        private const val MAX_RECONNECTS = 3
        /** Expired access codes: one fresh-address try per this long, and this many empty tries in a row before giving up. */
        private const val RENEW_MIN_GAP_MS = 60_000L
        private const val MAX_RENEWALS_FAILED = 3
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
    private lateinit var screen: PlayerScreen
    /** Ping every few seconds while the player is open: is it the connection or the stream? */
    private val pings = PingStats()
    private val pinger = Pinger { ms -> pings.add(ms) }
    private lateinit var sniffer: StreamSniffer
    /** This screen's stream was left for a link's page (started from the Source list); Back there returns here. */
    private var awayOnLinkPage = false
    private var awayTitle = ""
    private var awayListId: String? = null
    private var awayListName = ""
    private var awayListItemUrl: String? = null

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
    /** Decoder, tunneling and low-delay readings (see [PlaybackSigns]). */
    private val signs = PlaybackSigns()
    private var stalls = 0
    /** The current stall was already counted and logged (the buffering event), so the stall check does not count it again. */
    private var stallLogged = false
    /** Chrome's network engine for this link; turned off if a stream never starts with it. */
    private var useChromeNet = true
    /** A server refused standard networking (403): keep Chrome's engine for this page load, never drop it again. */
    private var chromeForced = false
    /** Where this source's requests go (playlist host vs piece hosts) and how its pieces are doing; kept while the source stays the same. */
    private var route: NetRoute? = null
    private var stallExtensions = 0
    /** The playlist-gone recovery already started for this play (a later error for the same failure must not start another). */
    private var goneHandled = false
    /** The engine ("chrome"/"standard") this source's server was remembered to work with and the player started on; null when nothing was remembered or it was given up. */
    private var engineRemembered: String? = null
    /** Use the TV's software video decoder (slower, more forgiving) for this link. */
    private var preferSoftware = false
    /** Experiment for stuck streams: play with the audio switched off to see if it's the blocker. */
    private var audioOff = false
    private var audioOffTried = false
    /** Experiment for stuck streams: jump to where the downloaded video actually is. */
    private var jumpTried = false
    private var loadsLogged = 0
    /** What the stream needs, measured from its downloaded pieces (for the overlay's speed pill). */
    private val streamRate = StreamRate()
    private var firstFrame = false
    /** Bumped whenever a different link takes over, so a pending reconnect for the old one is dropped. */
    private var linkGen = 0
    /** Expiring access codes: when the last renewal started, how many in a row found nothing, and whether one is running. */
    private var lastRenewAt = 0L
    private var renewFailures = 0
    private var renewing = false
    /** Backups while watching (see [BackupKeeper]) and the recovery bookkeeping around them. */
    private var keeper: BackupKeeper? = null
    private val episode = RecoveryEpisode()
    private var fastChecking = false
    private var lastStallAt = 0L
    private var reloadPending: Runnable? = null
    /** The playing source's address answered 404/410 (or its playlist went away): a reload that hands back the same address is not used. */
    private var goneUrl: String? = null
    /** The playing source's video pieces were refused (403): a reload that hands back the same address is not used, nor the clean player (see [Refusal]). */
    private var refusedUrl: String? = null
    /** When the playing address was found (for the lifetime samples); reset when the address changes. */
    private var addressUrl: String? = null
    private var addressSince = 0L

    private val handler: (String, String?) -> Unit = { c, v -> onCommand(c, v) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        root = findViewById(R.id.root)
        screen = PlayerScreen(this, root, main, screenHost)
        screen.build()
        sniffer = StreamSniffer(this, findViewById<FrameLayout>(R.id.webHolder)).also { it.timed = false }
        // The backup keeper's hidden browser sits UNDER the video (an opaque black parent hides it in letterbox bars), never over it.
        val keeperHolder = FrameLayout(this)
        root.addView(keeperHolder, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerView.setBackgroundColor(android.graphics.Color.BLACK)
        keeper = BackupKeeper(this, keeperHolder, { set?.let { it to sourceIndex } }, { backupHealth(it) })
        Session.commandHandler = handler
        Session.extrasListener = extrasHandler
        loadSession()
        main.post(snapshotTick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Session.commandHandler = handler
        awayOnLinkPage = false
        cancelRecovery() // a reconnect still running for the old stream must not overwrite the new one
        loadSession()
    }

    override fun onStart() {
        super.onStart()
        pinger.start()
        if (!awayOnLinkPage) return
        // Back from a link's page: take the session back and resume this stream at live.
        awayOnLinkPage = false
        val s = set ?: return finish()
        Report.log("Back from the link's page; resuming at live")
        Session.current = s
        Session.commandHandler = handler
        Session.mode = "player"
        Session.title = awayTitle
        Session.listId = awayListId; Session.listName = awayListName; Session.listItemUrl = awayListItemUrl
        linkGen++
        reconnects = 0
        refererIndex = 0
        playingSince = 0
        renewing = false
        AppStatus.text = "Playing ${Session.title}"
        play()
    }

    /**
     * Stops every recovery step that could still act on this player (pending reload, running page search, access renewal, backup
     * quick check, engine restart): they all check [linkGen], so bumping it makes their callbacks drop out. Used when the viewer
     * leaves for a link page, picks another source, or a new session replaces this one.
     */
    private fun cancelRecovery() {
        linkGen++
        reloadPending?.let { main.removeCallbacks(it) }
        reloadPending = null
        sniffer.cancel()
        episode.end()
        renewing = false
        fastChecking = false
    }

    override fun onStop() {
        super.onStop()
        pinger.stop()
        pings.clear()
        noteAddressAge(true)
        cancelRecovery()
        release()
        // Away on a link's page: stay in the back stack so Back returns here.
        if (awayOnLinkPage) return
        finish()
    }

    override fun onDestroy() {
        pinger.stop()
        main.removeCallbacksAndMessages(null)
        keeper?.stop()
        sniffer.cancel()
        release()
        if (Session.extrasListener === extrasHandler) Session.extrasListener = null
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
        keeper?.onNewSet()
        episode.end()
        fastChecking = false
        goneUrl = null
        refusedUrl = null
        set = s
        lastRenewAt = 0L
        renewFailures = 0
        renewing = false
        countedStrikeThisPlay = false // one tunneling strike per stream, however many retries
        smoothThisRun = false
        signs.resetLowDelay()
        mainRefetches = 0
        signs.resetBehind()
        sourceIndex = 0
        refererIndex = 0
        reconnects = 0
        playingSince = 0
        stalls = 0
        route = null
        useChromeNet = true
        chromeForced = false
        applyRememberedEngine()
        preferSoftware = false
        audioOff = false
        audioOffTried = false
        jumpTried = false
        screen.hidePicker()
        Session.mode = "player"
        // From a saved list the event title (set by the waiting screen) stays; otherwise show the site.
        if (Session.listId == null || Session.listItemUrl == null || Session.title.isBlank()) Session.title = Report.host(s.pageUrl)
        if (s.sources.size > 1) {
            Toast.makeText(this, "${s.sources.size} sources found · OK, then Source to switch", Toast.LENGTH_LONG).show()
        }
        loadAlternatives(s)
        play()
    }

    private val extrasHandler: (String, List<Source>) -> Unit = { page, fresh -> addExtraSources(page, fresh) }

    /** Numbered addresses of the page read after the player started: appended to the Source list (dropped if this is another page now). */
    private fun addExtraSources(page: String, fresh: List<Source>) {
        if (isDestroyed) return
        val cur = set ?: return
        if (cur.pageUrl != page) return
        val merged = SourceMerge.insertExtras(cur.sources, fresh)
        if (merged.size == cur.sources.size) return
        set = cur.copy(sources = merged)
        publishSnapshot()
        screen.refreshOverlayIfShown()
    }

    /** Other sites' streams for the same game (sports folders), read from caches off the main thread and appended to the Source list. */
    private fun loadAlternatives(s: StreamSet) {
        val gen = linkGen
        val page = s.pageUrl
        val item = Session.listItemUrl
        val app = applicationContext
        kotlin.concurrent.thread(isDaemon = true, name = "alternatives") {
            val alts = Alternatives.forPage(app, page, item)
            if (alts.isEmpty()) return@thread
            main.post {
                if (gen != linkGen || isDestroyed) return@post
                val cur = set ?: return@post
                val merged = Alternatives.append(cur.sources, alts)
                if (merged.size == cur.sources.size) return@post
                val updated = cur.copy(sources = merged)
                set = updated
                if (Session.current === cur) Session.current = updated
                Report.log("Other sites: ${merged.size - cur.sources.size} stream(s) of the same game in the Source list")
                publishSnapshot()
                screen.refreshOverlayIfShown()
            }
        }
    }

    // ---- Playback ----

    /** Starts on the network engine this stream server worked with last time (see [ServerMemory]); call right after resetting useChromeNet/chromeForced. */
    private fun applyRememberedEngine() {
        engineRemembered = null
        val s = set ?: return
        val src = s.sources.getOrNull(sourceIndex)?.takeIf { !it.openPage }
            ?: s.sources.getOrNull(LinkOrder.firstPlayable(s.sources) ?: return) ?: return
        val host0 = Report.host(src.url)
        // A server known to refuse standard networking never starts on it, whatever else was remembered.
        val mem = ServerMemory.engineFor(host0).takeUnless { it == ServerMemory.STANDARD && ServerMemory.refusesStandard(host0) }
        val start = ServerMemory.engineStart(mem, ChromeNet.engine != null)
        useChromeNet = start.useChromeNet
        chromeForced = start.chromeForced
        if (start.note != null) {
            engineRemembered = mem
            Report.log(start.note)
        }
    }

    /** A remembered engine did not work (stall or refusal): forget it and let the normal fallbacks run. */
    private fun forgetEngine(host: String) {
        ServerMemory.forgetEngine(host)
        engineRemembered = null
    }

    private fun play() {
        release()
        val s = set ?: return finish()
        val src = s.sources.getOrNull(sourceIndex) ?: return openSitePlayer("no playable source")
        if (src.openPage) {
            // Never played directly: go to a playable source instead.
            sourceIndex = LinkOrder.firstPlayable(s.sources) ?: return openSitePlayer("no playable source")
            return play()
        }
        Timing.mark(Timing.PLAYER)
        if (addressUrl != src.url) { addressUrl = src.url; addressSince = System.currentTimeMillis() }

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
            Headers.originFor(referer, Headers.capturedOrigin(src.headers))?.let { headers["Origin"] = it }
        }
        Report.log(
            "Playing source ${sourceIndex + 1}/${s.sources.size} (${src.label}), " +
                if (referer.isEmpty()) "no referer" else "referer ${Report.short(referer)}"
        )

        // Fetch through Chrome's network engine when available, so servers that only serve real
        // browsers don't refuse the player (see ChromeNet).
        val engine = if (useChromeNet) ChromeNet.engine else null
        loadsLogged = 0
        streamRate.clear()
        Report.logOnce(
            "net-${engine != null}",
            "Network: " + if (engine != null) ChromeNet.describe() else "Android's standard networking",
        )
        // One downloader that splits by host: the playlist host keeps the captured headers and the chosen engine;
        // video pieces from other hosts get browser-like cross-site headers and their own engine (see SplitDataSource).
        val r = route?.takeIf { it.sourceUrl == src.url }
            ?: NetRoute(src.url, ServerMemory.key(Report.host(src.url)) ?: "") { ServerMemory.refusesStandard(it) }.also { route = it }
        r.playlistChrome = engine != null
        r.watch.resetCounters()
        stallExtensions = 0
        goneHandled = false

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

        val dataSource = UnwrapDataSourceFactory(SplitDataSourceFactory(r, headers, userAgent))

        // Bigger cushion: wait for 8s of video after a stall (not 5s) so one hiccup doesn't
        // turn into several stalls in a row.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 2_500, 8_000)
            .build()
        if (!smoothLiveAllowed() && !smoothThisRun && LowDelayMemory.wantsSmooth(this, src.url)) {
            smoothThisRun = true
            Report.log("Low delay ran out of video on this stream recently; starting on the smooth delay")
        }
        smoothLive = smoothLiveAllowed() || smoothThisRun
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(NoRetryOnBrokenTls()))
            .build()
        player = p
        playerView.player = p
        qualityOptions = emptyList()
        qualityChoice = -1

        val lower = src.url.lowercase()
        // A disguised address (no m3u8/.mpd in it) carries the content type the verifier saw.
        val isHls = lower.contains("m3u8") || src.mime == MimeTypes.APPLICATION_M3U8
        val isDash = !isHls && (lower.contains(".mpd") || src.mime == MimeTypes.APPLICATION_MPD)
        val item = MediaItem.Builder().setUri(src.url).apply {
            if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (isDash) setMimeType(MimeTypes.APPLICATION_MPD)
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
        screen.showCover(if (reconnects == 0) "Loading…" else "Reconnecting…", 2)
        if (isHls) {
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
            p.setMediaSource(HlsMediaSource.Factory(dataSource).setExtractorFactory(lenient).setLoadErrorHandlingPolicy(NoRetryOnBrokenTls()).createMediaSource(item))
        } else {
            p.setMediaItem(item)
        }
        main.postDelayed(startCheck, 12_000)
        main.postDelayed(earlyDecoderCheck, 6_000)
        UnwrapDataSource.undecodable.set(0)
        disguiseChecks = 0
        main.removeCallbacks(disguiseCheck)
        main.postDelayed(disguiseCheck, 1_000)
        signs.resetStarve()
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
                    screen.hideCover()
                    if (playingSince == 0L) {
                        playingSince = System.currentTimeMillis()
                        mainRefetches = 0
                        Report.log("Playback started")
                        MemStat.log(this@PlayerActivity, "playing")
                    }
                    AppStatus.text = "Playing ${Session.title}"
                }
                Player.STATE_BUFFERING -> {
                    stallExtensions = 0
                    stallLogged = false
                    if (player?.playWhenReady == true) main.postDelayed(stallCheck, STALL_MS)
                    if (playingSince != 0L) {
                        lastStallAt = System.currentTimeMillis()
                        keeper?.noteStall()
                        logStall()
                        tunnelStallCheck()
                        lowDelayCheck()
                    }
                }
                Player.STATE_ENDED -> {
                    if (player?.isCurrentMediaItemLive == true) {
                        Report.log("Live stream stopped unexpectedly")
                        logFail(Failure.OTHER, "live stream stopped", true)
                        recover("stream ended")
                    } else {
                        Report.log("Video finished")
                        AppStatus.text = "Finished: ${Session.title}"
                        screen.showCover("Finished\nPress Back for the link screen", finished = true)
                    }
                }
                else -> {}
            }
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
            episode.end()
            goneUrl = null
            refusedUrl = null
            keeper?.onPicture()
            Timing.mark(Timing.PICTURE)
            Timing.emit()
            main.removeCallbacks(firstFrameCheck)
            val cur = set
            val shown = cur?.sources?.getOrNull(sourceIndex)
            if (cur != null && shown != null && !shown.openPage) {
                // A picture appeared: remember the engine for this server and the address for this page.
                ServerMemory.recordEngine(Report.host(shown.url), if (useChromeNet && ChromeNet.engine != null) ServerMemory.CHROME else ServerMemory.STANDARD)
                if (shown.verified) try {
                    LastAddress.save(applicationContext, cur.requestedUrl, cur, shown)
                } catch (_: Exception) {}
            }
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
            if (data.dataType == C.DATA_TYPE_MEDIA && data.mediaStartTimeMs != C.TIME_UNSET && data.mediaEndTimeMs != C.TIME_UNSET) {
                streamRate.add(data.trackType, info.bytesLoaded, data.mediaEndTimeMs - data.mediaStartTimeMs)
            }
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
            // The media playlist itself is gone (404/410 twice in a row) after it had played: no point waiting for the stall
            // check or the timeout error; recover now. The player is released by the recovery, so the later error never arrives.
            val w = route?.watch
            if (!goneHandled && playingSince != 0L && data.dataType == C.DATA_TYPE_MANIFEST && w != null && w.playlistGone()) {
                goneHandled = true
                goneUrl = set?.sources?.getOrNull(sourceIndex)?.url
                w.clearGone()
                Report.log("The stream's playlist is gone (${(error as? HttpDataSource.InvalidResponseCodeException)?.let { "server said ${it.responseCode}" } ?: "no answer"}); getting a fresh one")
                logFail(Failure.GONE, "playlist gone", true)
                main.post { if (player != null) recover("playlist gone") }
            }
        }
    }

    /** 12s in: lots of video waiting but nothing started -> the format is the problem; don't wait 20s. */
    /** 6s in: frames already going into the decoder with no picture out -> switch decoders now. */
    private val earlyDecoderCheck = Runnable {
        if (playingSince == 0L && player != null && !tunnelEngaged() && signs.decoderStuck(player)) {
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
            logStall(fromCheck = true)
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

    private fun decodeProblem() {
        Report.log(signs.decoderStats(player))
        val p = player
        // Read the decoder counters first: they say which remedy fits, so we don't walk the
        // whole list of experiments (which can take over a minute).
        if (signs.decoderStuck(player)) {
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

    /** Tunneling was requested AND the player actually uses it for the current tracks. */
    private fun tunnelEngaged(): Boolean = tunneling && player?.isTunnelingEnabled == true

    private val stallCheck: Runnable = Runnable {
        // Slow but moving: a big piece is still arriving, so this is not a stall yet (recover only when no bytes come).
        if (fastChecking) return@Runnable // a ready backup is being checked; it decides
        val watch = route?.watch
        if (watch != null && stallExtensions < MAX_STALL_EXTENSIONS && watch.progressing(System.currentTimeMillis())) {
            stallExtensions++
            Report.logOnce("stall-wait", String.format(java.util.Locale.US,
                "No picture after %ds, but a video piece is still arriving (%,d bytes so far); waiting a little longer",
                STALL_MS / 1000, watch.bytesSoFar()))
            main.postDelayed(stallCheck, STALL_EXTENSION_MS)
            return@Runnable
        }
        Report.log("Stalled for ${(STALL_MS + stallExtensions * STALL_EXTENSION_MS) / 1000}s")
        logFail(Failure.STALLED, "", playingSince != 0L)
        logStall(fromCheck = true)
        val buffered = player?.let { (it.bufferedPosition - it.currentPosition) / 1000.0 } ?: 0.0
        if (tunnelEngaged() && !firstFrame) {
            retryWithoutTunneling("stalled before the first picture")
        } else if (playingSince == 0L && buffered >= 8.0) {
            // Plenty of video arrived but it never started: the stream's format (or a quirk in it)
            // is something this decoder can't handle. Reconnecting won't help; a browser often can.
            Report.log(String.format(java.util.Locale.US,
                "%.0fs of video arrived but it wouldn't start: the stream's format looks like the problem, not the network", buffered))
            decodeProblem()
        } else if (pieceOnlyProblem()) {
            // Handled: the pieces got the other engine, or another source was started (or a plain reconnect began).
        } else if (playlistSilentProblem()) {
            // Handled: Chrome's network engine is being restarted; the source starts again when it is done.
        } else if (playingSince == 0L && engineRemembered != null && set?.sources?.getOrNull(sourceIndex) != null &&
            (engineRemembered == ServerMemory.STANDARD && !useChromeNet || engineRemembered == ServerMemory.CHROME && chromeForced && !playlistRefusesStandard())
        ) {
            // The engine remembered for this server did not start the stream: forget it; the normal fallbacks run from here.
            val wasChrome = engineRemembered == ServerMemory.CHROME
            forgetEngine(Report.host(set!!.sources[sourceIndex].url))
            if (wasChrome && ChromeNet.engine != null) {
                chromeForced = false
                useChromeNet = false
                Report.log("No progress with Chrome's network engine; retrying with standard networking")
                play()
            } else if (!wasChrome && ChromeNet.engine != null) {
                // Remembered standard networking did not start it: try Chrome's engine once (and keep it).
                useChromeNet = true
                chromeForced = true
                Report.log("No progress with standard networking; retrying with Chrome's network engine")
                play()
            } else {
                recover("stalled")
            }
        } else if (playingSince == 0L && useChromeNet && !chromeForced && ChromeNet.engine != null && !playlistRefusesStandard()) {
            // Never started with Chrome's engine: some servers work badly with it. Try once without.
            useChromeNet = false
            Report.log("No progress with Chrome's network engine; retrying with standard networking")
            play()
        } else {
            if (playlistRefusesStandard() && playingSince == 0L && useChromeNet) {
                Report.logOnce("refuses-std", "This server refuses standard networking; not trying it")
            }
            recover("stalled")
        }
    }

    /** Logs [line], rebuilds Chrome's network engine off the main thread, then starts the source again. */
    private fun restartEngineThenPlay(line: String) {
        Report.log(line)
        // Let go of the old player first: while the engine is rebuilt it could only error or start a second recovery.
        release()
        val gen = linkGen
        Thread {
            val how = ChromeNet.reset()
            main.post {
                if (gen != linkGen || isDestroyed) return@post
                Report.log(if (how != null) "Chrome's network engine restarted ($how)" else "Chrome's network engine could not be restarted")
                play()
            }
        }.start()
    }

    /**
     * Before the first picture the playlist itself gets no answer at all (a timeout, no HTTP status) on Chrome's engine: a wedged
     * engine looks like this. Restart it once per source and try again before anything else (another source on the same host
     * would hit the same engine). True when it started the restart.
     */
    private fun playlistSilentProblem(): Boolean {
        val r = route ?: return false
        if (playingSince != 0L || firstFrame || !r.playlistChrome || ChromeNet.engine == null) return false
        if (!r.watch.playlistSilent() || r.watch.playlistRestarted() || !ChromeNet.canReset()) return false
        r.watch.markPlaylistRestarted()
        restartEngineThenPlay("The playlist gets no answer; restarting Chrome's network engine")
        return true
    }

    private fun playlistRefusesStandard(): Boolean {
        val u = set?.sources?.getOrNull(sourceIndex)?.url ?: return false
        return ServerMemory.refusesStandard(Report.host(u))
    }

    /**
     * The video pieces of [host] are refused by their server (401/403/451) before the first picture. The server decided who may
     * ask: other referers (already tried, or already refused in the finder's check), the other network engine, an engine restart
     * and the clean browser player fetch the same pieces the same way, so none of them is tried. Instead (see [Rescue.afterRefusedPieces]):
     * another playable source of the page, an automatic switch, one page reload only when the finder had not seen this host refuse,
     * else the site's own player. [corroborated]: the finder's check had already seen this host refuse every referer.
     */
    private fun refusedPieces(host: String, corroborated: Boolean, status: Int) {
        val s = set ?: return finish()
        if (fastChecking) return
        val src = s.sources.getOrNull(sourceIndex)
        RefusedHosts.record(host)
        refusedUrl = src?.url
        Report.log("Video pieces from $host are refused (server said $status); this stream can't play here")
        AutoSwitch.markDead(src?.url, System.currentTimeMillis())
        rescue(Rescue.afterRefusedPieces(corroborated), "video pieces refused")
    }

    /**
     * Playlists refresh fine but the video pieces from one host fail (see [PieceWatch.pieceOnlyHost]). Reloading the
     * page cannot help (the pieces come from the same host again) and switching the playlist's engine is wrong, so:
     * first the other engine for that host's pieces only, then another source. Returns true when it dealt with the
     * situation (including a plain reconnect when no other source exists); false when this is not a piece-only problem.
     */
    private fun pieceOnlyProblem(): Boolean {
        val r = route ?: return false
        val host = r.watch.pieceOnlyHost(1) ?: return false
        // A broken secure connection is not a wedged engine: neither a fresh engine nor the other one helps (both fail
        // the same way); go straight to another source.
        if (DeadHosts.isDead(host)) {
            Report.log("Video pieces from $host: its server is down; switching to another source instead of retrying")
            switchForPieces()
            return true
        }
        // Refused (not timed out): no engine change helps; see refusedPieces.
        if (playingSince == 0L && r.watch.pieceRefused(host)) {
            refusedPieces(host, RefusedHosts.isRefused(host), 403)
            return true
        }
        val nowChrome = r.usesChrome(host)
        val otherAllowed = if (nowChrome) !ServerMemory.refusesStandard(host) else ChromeNet.engine != null
        if (nowChrome && !otherAllowed && !r.watch.retried(host)) {
            Report.logOnce("refuses-std-$host", "This server refuses standard networking; not trying it")
        }
        // First a fresh Chrome engine (a wedged pooled connection to this host is the likeliest cause), once per host.
        // Only for pieces that got no answer at all: a piece the server answered (even with an error) is not a wedged engine.
        if (nowChrome && !r.watch.restarted(host) && !r.watch.pieceAnswered(host) && ChromeNet.canReset()) {
            r.watch.markRestarted(host)
            restartEngineThenPlay("Video pieces from $host keep timing out (no answer); restarting Chrome's network engine")
            return true
        }
        when (PieceRecovery.next(r.watch.retried(host), otherAllowed)) {
            PieceStep.RETRY_OTHER_ENGINE -> {
                r.setPieceEngine(host, !nowChrome)
                r.watch.markRetried(host)
                Report.log(
                    "Video pieces from $host time out but the playlist is fine; retrying the pieces with " +
                        if (nowChrome) "standard networking" else "Chrome's network engine"
                )
                play()
            }
            PieceStep.SWITCH_SOURCE -> {
                Report.log("Video pieces still failing; switching to another source instead of reloading the page")
                switchForPieces()
            }
        }
        return true
    }

    /** The video pieces can't be had from their server: another source (a ready backup first while playing), else the usual recovery. */
    private fun switchForPieces() {
        logFail(Failure.PIECES_FAILING, "", playingSince != 0L)
        rescue(Rescue.PIECES_FAILING, "video pieces failing")
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
    private fun logStall(fromCheck: Boolean = false) {
        Report.log(signs.decoderStats(player))
        // The buffering event and the 20 s check describe the same stall: count it once.
        val again = fromCheck && stallLogged
        if (!again) stalls++
        stallLogged = true
        val need = player?.videoFormat?.bitrate ?: -1
        val mbps = { b: Long -> String.format(java.util.Locale.US, "%.1f Mbps", b / 1_000_000.0) }
        val ahead = player?.let { (it.bufferedPosition - it.currentPosition) / 1000.0 } ?: 0.0
        Report.log(
            (if (again) "Still buffering (stall #$stalls)" else "Buffering (stall #$stalls)") + ": ${pings.summary()}" +
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
    private fun tunnelStallCheck() {
        if (!tunnelEngaged()) return
        val p = player ?: return
        val ahead = signs.bufferedStallRepeat(p, System.currentTimeMillis()) ?: return
        retryWithoutTunneling(String.format(java.util.Locale.US, "keeps stalling with %.0fs buffered", ahead))
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
    private val audioStarveCheck = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!tunnelEngaged()) return
            if (signs.audioStarved(p, System.currentTimeMillis())) {
                retryWithoutTunneling("the audio stopped while the video kept coming")
                return
            }
            main.postDelayed(this, 2_000)
        }
    }

    /** Low delay running dry (6 s pieces that take seconds to download): smooth delay for this play. */
    private fun lowDelayCheck() {
        if (smoothLive) return
        val p = player ?: return
        if (signs.lowDelayRanDry(p, System.currentTimeMillis())) {
            smoothThisRun = true
            set?.sources?.getOrNull(sourceIndex)?.let { LowDelayMemory.markDry(this, it.url) }
            Report.log("Low delay keeps running out of video; using the smooth delay for this stream (remembered for this stream)")
            play()
        }
    }

    /** The latest error was a broken secure connection to a server already known as down. */
    private var brokenAgain = false
    /** Main playlist re-fetched after a broken inner server, this play (reset when the picture starts or on a new link). */
    private var mainRefetches = 0

    private fun handleError(error: PlaybackException) {
        player ?: return
        if (fastChecking) return // a ready backup is being checked; it decides
        val failure = logFailure(error)
        val httpError = error.cause as? HttpDataSource.InvalidResponseCodeException
        val http = httpError?.responseCode
        val why = when {
            failure == Failure.BEHIND_LIVE -> "keeps falling behind the live point"
            http != null -> "server said $http"
            else -> error.errorCodeName
        }
        if (failure != Failure.BEHIND_LIVE && failure != Failure.TUNNEL_AUDIO) {
            httpError?.dataSpec?.uri?.let { Report.log("  …for ${Report.short(it.toString())}") }
            val detail = generateSequence(error.cause) { it.cause }.mapNotNull { it.message }.firstOrNull()?.take(200)
            Report.log("Playback error: $why" + if (detail != null) " ($detail)" else "")
        } else if (failure == Failure.TUNNEL_AUDIO) {
            Report.log("Playback error: ${error.errorCodeName}")
        }
        // Memory only (no decision): broken secure connections (DeadHosts, log only), dead addresses, link lifetimes.
        brokenAgain = false
        val mainHost = set?.sources?.getOrNull(sourceIndex)?.url?.let { Report.host(it) }
        if (failure == Failure.BROKEN_SECURE) {
            val now = System.currentTimeMillis()
            if (blockedOnArrival == null) {
                blockedOnArrival = NetBlock.isBlocked(mainHost, now)
                if (blockedOnArrival == true) Report.log("Network block: $mainHost's servers were blocked moments ago too; skipping re-asks and reloads")
            }
            NetBlock.record(mainHost, now)
        }
        if (failure == Failure.BROKEN_SECURE && ++blockedServers == Rescue.BLOCKS_BEFORE_NOTE && blockedOnArrival != true) {
            Report.log("Network block: $blockedServers blocked servers this watch (broken secure connection on both engines); the VPN usually fixes it")
            android.widget.Toast.makeText(applicationContext,
                "Your network is blocking this stream's servers. The VPN usually fixes it.", android.widget.Toast.LENGTH_LONG).show()
        }
        if (failure == Failure.BROKEN_SECURE) {
            BrokenTls.failingHost(error)?.let { h ->
                val again = !DeadHosts.record(h)
                brokenAgain = again && DeadHosts.ENFORCE
                Report.log(
                    if (!DeadHosts.ENFORCE) "$h's secure connection is broken (${DeadHostText.sslReason(error)})" + (if (again) ", again" else "") + "; noted (log only)"
                    else if (again) "$h is still down (secure connection broken)"
                    else "$h's secure connection is broken (${DeadHostText.sslReason(error)}); remembering it as down for a few minutes"
                )
            }
        }
        val src = set?.sources?.getOrNull(sourceIndex)
        val failHost = httpError?.dataSpec?.uri?.host?.let { ServerMemory.key(it) }
        if ((http == 404 || http == 410) && playingSince != 0L && src != null && failHost != null && failHost == route?.playlistHost) goneUrl = src.url
        if (http == 403 && playingSince != 0L && src != null && src.url == addressUrl && StreamUrls.hasQuery(src.url)) {
            ServerMemory.recordAge(Report.host(src.url), System.currentTimeMillis() - addressSince, false)
        }
        val again = failure == Failure.BEHIND_LIVE && playingSince != 0L && signs.behindLiveRepeated(System.currentTimeMillis())
        rescue(Rescue.order(failure, again), why, error, checkNet = Rescue.checksNetwork(failure))
    }

    /** Blocked servers seen in this watch (Rescue.BLOCKS_BEFORE_NOTE). */
    private var blockedServers = 0
    /** At this watch's first blocked server: was its main server already blocked (NetBlock)? null until then. */
    private var blockedOnArrival: Boolean? = null
    /** A spaced-out re-ask of the main playlist is waiting (Rescue.reaskDelayMs): no other rescue meanwhile. */
    private var reaskPending = false
    /** The player is waiting for the internet to come back (waitForNetwork). */
    private var netWaiting = false
    /** The internet check before a rescue is running; another failure (stall timer, start check) must not start a second rescue meanwhile. */
    private var netChecking = false

    /**
     * Lab: before running the order for a failure a dead connection also causes, check the internet (a quick connect to
     * 1.1.1.1 / 8.8.8.8). Down: show it on screen and wait for it to come back (at most [Rescue.NET_WAIT_MS]) instead of
     * using up reloads and other sites (2026-11-21 19:24: the connection dropped and every source was tried in vain).
     */
    private fun checkNetThen(why: String, go: () -> Unit) {
        val gen = linkGen
        netChecking = true
        kotlin.concurrent.thread(isDaemon = true, name = "net-check") {
            val up = Pinger.reachable()
            main.post {
                netChecking = false
                if (isDestroyed || gen != linkGen) return@post
                if (up) go() else waitForNetwork(why, System.currentTimeMillis(), gen, go)
            }
        }
    }

    private fun waitForNetwork(why: String, since: Long, gen: Int, go: () -> Unit) {
        if (!netWaiting) {
            netWaiting = true
            Report.log("Failure: internet connection down (no answer from 1.1.1.1 or 8.8.8.8); waiting for it to come back")
            Report.log("Rescue: wait for the internet")
            screen.showCover("Your internet connection looks down.\nWaiting for it to come back…")
            AppStatus.text = "Waiting for the internet…"
        }
        main.postDelayed({
            if (isDestroyed || gen != linkGen) { netWaiting = false; return@postDelayed }
            kotlin.concurrent.thread(isDaemon = true, name = "net-wait") {
                val up = Pinger.reachable()
                main.post {
                    if (isDestroyed || gen != linkGen) { netWaiting = false; return@post }
                    val waited = System.currentTimeMillis() - since
                    when {
                        up -> {
                            netWaiting = false
                            Report.log("Internet is back after ${waited / 1000} s; continuing ($why)")
                            reconnects = 0
                            go()
                        }
                        waited >= Rescue.NET_WAIT_MS -> {
                            netWaiting = false
                            Report.log("Still no internet after ${waited / 1000} s; running the usual rescue anyway")
                            go()
                        }
                        else -> waitForNetwork(why, since, gen, go)
                    }
                }
            }
        }, Rescue.NET_POLL_MS)
    }

    /**
     * Lab step 1b: runs the first step of [steps] (from [from]) that applies now. A step that hands off to something
     * asynchronous (a ready backup's check) continues with the steps after it when that comes back empty.
     */
    private fun rescue(steps: List<RescueStep>, why: String, error: PlaybackException? = null, from: Int = 0, checkNet: Boolean = false) {
        if (fastChecking || reaskPending || netWaiting || netChecking) return
        if (set == null) return finish()
        if (from == 0 && checkNet) return checkNetThen(why) { rescue(steps, why, error) }
        // It had been playing fine for a while: this is a fresh problem, not a repeat.
        if (from == 0 && playingSince != 0L && System.currentTimeMillis() - playingSince > 60_000) reconnects = 0
        for (i in from until steps.size) {
            if (runStep(steps[i], why, error) { rescue(steps, why, error, i + 1) }) return
        }
        Report.log("Rescue: nothing left to try")
        openSitePlayer(why)
    }

    /** One rescue step: false when it doesn't apply right now (nothing done). [next] continues the order later. */
    private fun runStep(step: RescueStep, why: String, error: PlaybackException?, next: () -> Unit): Boolean {
        val s = set ?: return false
        val src = s.sources.getOrNull(sourceIndex)
        val playing = playingSince != 0L
        val httpError = error?.cause as? HttpDataSource.InvalidResponseCodeException
        val http = httpError?.responseCode
        val failHost = httpError?.dataSpec?.uri?.host?.let { ServerMemory.key(it) }
        val r = route
        fun go(): Boolean { Report.log("Rescue: ${step.text}"); return true }
        return when (step) {
            RescueStep.JUMP_TO_LIVE -> {
                val p = player ?: return false
                go(); Report.log("Fell behind the live point; jumping back to live")
                p.seekToDefaultPosition(); p.prepare()
                return true
            }
            RescueStep.NO_TUNNEL -> { go(); retryWithoutTunneling(error?.errorCodeName ?: why); return true }
            RescueStep.REASK_MAIN -> {
                if (error == null || !DeadHostText.brokenSecureLink(error) || blockedOnArrival == true) return false
                val failing = BrokenTls.failingHost(error)
                val mainHost = src?.url?.let { Report.host(it) }
                if (failing == null || mainHost == null || failing.equals(mainHost, true) || mainRefetches >= Rescue.MAX_MAIN_REFETCHES) return false
                mainRefetches++
                go()
                val wait = Rescue.reaskDelayMs(mainRefetches)
                Report.log("$failing (named by the main playlist) has a broken secure connection; asking $mainHost again for another server " +
                    "in ${wait / 1000} s (try $mainRefetches of ${Rescue.MAX_MAIN_REFETCHES})")
                reaskPending = true
                val gen = linkGen
                main.postDelayed({
                    reaskPending = false
                    if (!isDestroyed && gen == linkGen && set != null) play()
                }, wait)
                return true
            }
            RescueStep.PIECE_ENGINE -> {
                if (http != 403 || r == null || failHost == null || failHost == r.playlistHost || r.usesChrome(failHost) || ChromeNet.engine == null) return false
                go()
                r.setPieceEngine(failHost, true)
                ServerMemory.recordRefusesStandard(failHost)
                Report.log("Video pieces from $failHost refused standard networking; using Chrome's network engine for them")
                play()
                return true
            }
            RescueStep.CHROME_ENGINE -> {
                if (http != 403 || useChromeNet || ChromeNet.engine == null) return false
                go()
                src?.let { ServerMemory.recordRefusesStandard(Report.host(it.url)) }
                useChromeNet = true
                chromeForced = true
                refererIndex = 0
                engineRemembered = null
                src?.let { ServerMemory.recordEngine(Report.host(it.url), ServerMemory.CHROME) }
                Report.log("Server refused standard networking; going back to Chrome's network engine")
                play()
                return true
            }
            RescueStep.RENEW_LINK -> {
                if (http != 403 || !playing || src == null || !StreamUrls.hasQuery(src.url) || !LinkOrder.canRenew(src)) return false
                if (!renewAccess(src)) return false
                Report.log("Rescue: ${step.text}")
                return true
            }
            RescueStep.NEXT_REFERER -> {
                if ((http != 401 && http != 403) || playing || src == null) return false
                val pieceRefused = Refusal.isRefusal(http) && r != null && failHost != null && failHost != r.playlistHost
                val hostKnown = pieceRefused && RefusedHosts.isRefused(failHost!!)
                if (!Refusal.cycleReferers(refererIndex + 1 < src.referers.size, hostKnown)) return false
                go()
                refererIndex++
                play()
                return true
            }
            RescueStep.REFUSED_PIECES -> {
                if (playing || src == null || r == null || failHost == null || failHost == r.playlistHost || !Refusal.isRefusal(http)) return false
                go()
                refusedPieces(failHost, RefusedHosts.isRefused(failHost), http ?: 403)
                return true
            }
            RescueStep.ENGINE_RESTART -> {
                if (playing || error == null || !playlistSilentProblem()) return false
                return go()
            }
            RescueStep.PIECE_FIX -> {
                if (playing || error == null || !pieceOnlyProblem()) return false
                return go()
            }
            RescueStep.NEXT_SOURCE -> {
                if (playing) return false
                val n = LinkOrder.nextPlayable(s.sources, sourceIndex) ?: return false
                go()
                AutoSwitch.markDead(src?.url, System.currentTimeMillis())
                sourceIndex = n
                refererIndex = 0
                Report.log("Trying the next source")
                play()
                return true
            }
            RescueStep.READY_BACKUP -> {
                if (!playing) return false
                if (!episode.isOpen) episode.begin()
                if (!fastBackup(why, next)) return false
                return go()
            }
            RescueStep.RELOAD_PAGE -> {
                if (brokenAgain && reconnects > 0) {
                    Report.log("Not reloading the page again: it hands back the same server, which is down")
                    return false
                }
                // Lab: a network block (Rescue.BLOCKS_BEFORE_NOTE) allows one reload at most; none if it was known on arrival.
                if (blockedOnArrival == true || (blockedServers >= Rescue.BLOCKS_BEFORE_NOTE && reconnects >= 1)) {
                    Report.log("Not reloading the page: your network blocks this stream's servers")
                    return false
                }
                if (reconnects >= MAX_RECONNECTS) return false
                go()
                reconnects++
                reconnect(why)
                return true
            }
            RescueStep.RELOAD_ONCE -> {
                if (reconnects > 0) return false
                go()
                reconnects++
                reconnect(why)
                return true
            }
            RescueStep.OTHER_SITE -> {
                if (!tryAutoSwitch(why)) return false
                return go()
            }
            RescueStep.LAST_RESORT -> {
                go()
                // The clean browser player would fetch the same refused pieces.
                if (brokenAgain || !Refusal.cleanPlayerCanHelp(refusedUrl != null)) openSitePlayer(why) else openCleanPlayer(why)
                return true
            }
        }
    }

    /** Lab step 1a: one failure name per error, logged before the usual rescue runs (Failure). */
    /** Logs a failure line and counts it for the weekly tally (FailureTally). */
    private fun logFail(f: Failure, detail: String, playing: Boolean) {
        Report.log(Failure.line(f, detail, playing))
        try { FailureTally.note(f, set?.pageUrl, set?.sources?.getOrNull(sourceIndex)?.url) } catch (_: Exception) { }
    }

    private fun logFailure(error: PlaybackException): Failure {
        return try {
            val audioOut = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            val tunnelSuspect = (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT && tunnelEngaged()) ||
                (tunneling && (!firstFrame || audioOut) && error.errorCode in tunnelSuspects)
            val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            val src = set?.sources?.getOrNull(sourceIndex)
            val signed = src != null && src.url == addressUrl && StreamUrls.hasQuery(src.url)
            val f = Failure.of(error.errorCode, http, DeadHostText.brokenSecureLink(error), tunnelSuspect, playingSince != 0L, signed)
            logFail(f, if (http != null) "server said $http" else error.errorCodeName, playingSince != 0L)
            f
        } catch (_: Exception) { Failure.OTHER }
    }

    /**
     * The playing source's address carries a short-lived access code and the server now refuses it. Quietly
     * look for a fresh address (a light sniff of the page, or of the link player the source came from, in the
     * idle page loader) and restart on it at the live point, without leaving the player. At most one try per
     * 60 s and 3 empty tries in a row; returns false (caller falls through to the normal recovery) when a try
     * is not allowed. While a try runs, returns true.
     */
    private fun renewAccess(old: Source): Boolean {
        val now = System.currentTimeMillis()
        if (RenewGuard.inFlight(renewing, lastRenewAt, now)) return true
        if (renewFailures >= MAX_RENEWALS_FAILED || now - lastRenewAt < RENEW_MIN_GAP_MS) return false
        val page = set?.pageUrl?.takeIf { it.startsWith("http") } ?: return false
        renewing = true
        lastRenewAt = now
        Report.log("Access code expired; getting a fresh address…")
        val gen = linkGen
        val target = old.playerUrl.ifEmpty { page }
        val ref = if (old.playerUrl.isNotEmpty()) page else null
        sniffer.sniff(target, referer = ref, light = true) { r ->
            if (gen != linkGen || isDestroyed) return@sniff
            renewing = false
            val ok = r.sources.filter { it.verified }
            val fresh = ok.firstOrNull { StreamUrls.sameStream(it.url, old.url) } ?: ok.firstOrNull()
            val cur = set
            val idx = cur?.sources?.indexOfFirst { it.url == old.url } ?: -1
            if (fresh == null || cur == null || idx < 0) {
                renewFailures++
                Report.log("No fresh address found ($renewFailures of $MAX_RENEWALS_FAILED)")
                recoverAfterEmptyRenewal("server said 403")
                return@sniff
            }
            renewFailures = 0
            Report.log("Fresh address found" + (if (StreamUrls.sameStream(fresh.url, old.url)) "" else " (a different stream)") + "; resuming at live")
            val list = cur.sources.toMutableList()
            list[idx] = fresh.copy(label = old.label, isDefaultLink = old.isDefaultLink, playerUrl = old.playerUrl)
            val updated = cur.copy(sources = list)
            set = updated
            if (Session.current === cur) Session.current = updated
            if (sourceIndex != idx) return@sniff // the viewer switched source meanwhile; only the address was refreshed
            refererIndex = 0
            playingSince = 0
            play()
        }
        return true
    }

    /** A stall, a stopped live stream or a gone playlist: the common rescue order (Rescue.AFTER_STALL). */
    private fun recover(why: String) {
        if (fastChecking) return // a ready backup is being checked; it decides
        if (set == null) return finish()
        rescue(Rescue.AFTER_STALL, why, checkNet = true)
    }

    /**
     * The page was just looked at (the renewal's light search) and showed nothing, so reloading it again is the last resort:
     * a ready backup first, then another source (automatic switch), and only when neither exists the usual reconnect.
     */
    private fun recoverAfterEmptyRenewal(why: String) {
        if (fastChecking) return
        if (set == null) return finish()
        rescue(Rescue.AFTER_EMPTY_RENEWAL, why)
    }

    private fun backupHealth(now: Long): BackupTiming.Health {
        val p = player
        val playing = p != null && p.isPlaying && playingSince != 0L && firstFrame
        val ahead = if (p != null) p.bufferedPosition - p.currentPosition else 0L
        return BackupTiming.Health(playing, ahead, now - maxOf(playingSince, lastStallAt),
            episode.isOpen || renewing || fastChecking || awayOnLinkPage)
    }

    /** What the playing address's age says about its server: it worked for this long (only when it is playing fine and nothing is being recovered). */
    private fun noteAddressAge(ok: Boolean) {
        val src = set?.sources?.getOrNull(sourceIndex) ?: return
        if (src.openPage || src.url != addressUrl || episode.isOpen || !firstFrame || player?.isPlaying != true) return
        ServerMemory.recordAge(Report.host(src.url), System.currentTimeMillis() - addressSince, ok)
    }

    private fun cand(s: Source) = StreamCandidate(s.url, s.headers, s.referers, s.label, s.isDefaultLink)

    /**
     * The stream just dropped. If a ready backup is fresh (see [BackupRecovery]), check its address quickly (2 s) and switch to it;
     * [onMiss] runs when it no longer answers. Returns false (nothing started) when there is no fresh backup or no automatic
     * switch is left, so the caller carries on as before.
     */
    private fun fastBackup(why: String, onMiss: () -> Unit): Boolean {
        val k = keeper ?: return false
        val now = System.currentTimeMillis()
        val d = BackupRecovery.decide(k.known(now), 0, AutoSwitch.canSwitch())
        if (d.plan != BackupRecovery.Plan.SWITCH_FAST) return false
        val best = k.ready(now).firstOrNull { it.url == d.url } ?: return false
        fastChecking = true
        main.removeCallbacks(stallCheck)
        k.pause()
        val gen = linkGen
        kotlin.concurrent.thread(isDaemon = true, name = "backup-switch") {
            val win = QuickRace.first(best.set.sources.take(3), 2000L) { Verifier.quickCheck(cand(it), 2000L) { } }
            main.post {
                fastChecking = false
                if (gen != linkGen || isDestroyed) return@post
                if (win == null) {
                    Report.log("The saved backup no longer answers; reconnecting as usual")
                    onMiss()
                } else if (!switchToBackup(best, win.second, true)) {
                    Report.log("The saved backup is no longer in the source list; reconnecting as usual")
                    onMiss()
                }
            }
        }
        return true
    }

    /**
     * Moves to a backup whose address just passed a quick check ([checked]). A playable source of this page: plays it in place.
     * A page-type backup: the same path as an automatic switch to another page (loading screen with auto=true, referer as the
     * Source list sends it, AutoSwitch bookkeeping, this player finishes) but the already found set is handed over through
     * [PreFound] so the loading screen skips its search. Counts as one automatic switch. False when it cannot be done.
     */
    private fun switchToBackup(best: BackupResult, checked: Source, withAge: Boolean): Boolean {
        val s = set ?: return false
        val now = System.currentTimeMillis()
        val idx = if (best.kind == BackupKind.STREAM) s.sources.indexOfFirst { it.url == best.url } else -1
        if (best.kind == BackupKind.STREAM && idx < 0) return false
        AutoSwitch.markDead(s.sources.getOrNull(sourceIndex)?.url, now)
        val rest = AutoSwitch.order(s.sources, sourceIndex, now).map { s.sources[it] }.filter { it.openPage && it.url != best.url }
        AutoSwitch.begin(rest)
        episode.end()
        reloadPending?.let { main.removeCallbacks(it) }
        sniffer.cancel()
        renewing = false
        val host = Report.host(checked.url)
        Report.log(
            if (withAge) "Stream went down; switching to ${best.label} ($host), found ${((now - best.foundAt) / 1000).coerceAtLeast(0)} s ago"
            else "A known address checked out first; using it (${best.label}, $host)"
        )
        if (best.kind == BackupKind.STREAM) {
            val list = s.sources.toMutableList()
            list[idx] = checked.copy(label = s.sources[idx].label, isDefaultLink = s.sources[idx].isDefaultLink)
            val updated = s.copy(sources = list)
            set = updated
            if (Session.current === s) Session.current = updated
            addressUrl = checked.url; addressSince = best.foundAt
            sourceIndex = idx
            refererIndex = 0
            reconnects = 0
            playingSince = 0
            play()
            return true
        }
        AutoSwitch.markDead(s.pageUrl, now)
        val ready = listOf(checked) + best.set.sources.filter { it.url != checked.url }
        PreFound.put(best.target, StreamSet(best.set.pageUrl, ready, requestedUrl = best.set.requestedUrl), now)
        screen.showCover("Stream went down, trying another source…")
        release()
        startActivity(WaitingActivity.linkPageIntent(this, best.target, best.label, best.referer, auto = true))
        finish()
        return true
    }

    /** At the start of a reconnect after a drop: check the addresses already known (other playable sources, ready backups) while the page reloads; the first verified answer wins ([RecoveryEpisode]). */
    private fun startRace(ep: Int) {
        val cur = set ?: return
        if (ep == 0 || !AutoSwitch.canSwitch()) return
        val now = System.currentTimeMillis()
        val items = ArrayList<BackupResult>()
        keeper?.ready(now)?.let { items.addAll(it) }
        cur.sources.forEachIndexed { i, src ->
            if (i != sourceIndex && !src.openPage && src.verified && !AutoSwitch.isDead(src.url, now) && items.none { it.url == src.url }) {
                items.add(BackupResult(src.url, BackupKind.STREAM, src.label, src.url, null, StreamSet(cur.pageUrl, listOf(src)), now))
            }
        }
        val pairs = items.take(5).flatMap { r -> r.set.sources.take(2).map { r to it } }
        if (pairs.isEmpty()) return
        Report.log("Reconnecting, and checking ${items.take(5).size} known address(es) at the same time")
        val gen = linkGen
        kotlin.concurrent.thread(isDaemon = true, name = "recovery-race") {
            val win = QuickRace.first(pairs, 6000L) { Verifier.quickCheck(cand(it.second), 3000L) { } }
            main.post {
                if (win == null || gen != linkGen || isDestroyed) return@post
                if (!episode.claim(ep)) return@post // the page reload got there first
                if (!switchToBackup(win.first.first, win.second, false)) episode.end()
            }
        }
    }

    /**
     * The stream went down and reconnecting did not help: move to another source by ourselves (see [AutoSwitch]):
     * another playable source of this page, else another page (this site's numbered address, then other sites),
     * which opens through the loading screen and replaces this player. At most 3 per watch. Returns true when
     * something was started.
     */
    private fun tryAutoSwitch(why: String): Boolean {
        val s = set ?: return false
        if (!AutoSwitch.canSwitch()) return false
        val now = System.currentTimeMillis()
        AutoSwitch.markDead(s.sources.getOrNull(sourceIndex)?.url, now)
        val order = AutoSwitch.order(s.sources, sourceIndex, now)
        val first = order.firstOrNull() ?: return false
        val src = s.sources[first]
        val rest = order.drop(1).map { s.sources[it] }.filter { it.openPage }
        AutoSwitch.begin(rest)
        episode.end()
        reloadPending?.let { main.removeCallbacks(it) }
        if (!src.openPage) {
            Report.log("Stream failed ($why); switching to ${src.label} (${Report.host(src.url)})")
            sourceIndex = first
            refererIndex = 0
            reconnects = 0
            playingSince = 0
            play()
            return true
        }
        AutoSwitch.markDead(s.pageUrl, now)
        val target = src.playerUrl.ifEmpty { src.url }
        Report.log("Stream failed ($why); switching to ${src.label} (${Report.host(target)})")
        screen.showCover("That stream didn't work, trying the next one…")
        release()
        startActivity(WaitingActivity.linkPageIntent(this, target, src.label, src.referers.firstOrNull { it.isNotBlank() }, auto = true))
        finish()
        return true
    }

    /** Reload the page invisibly to get a fresh stream link (they often expire), then resume at live. */
    private fun reconnect(why: String) {
        if (playingSince != 0L && !episode.isOpen) episode.begin()
        release()
        playingSince = 0
        renewing = false // this search takes over the page loader; a running renewal's answer will never arrive
        val page = set?.pageUrl ?: return finish()
        screen.showCover("Reconnecting…\n($why)")
        AppStatus.text = "Reconnecting to ${Session.title}…"
        Report.log("Reconnecting (try $reconnects of $MAX_RECONNECTS): reloading the page for a fresh stream link")
        val gen = linkGen
        val ep = episode.current()
        if (episode.markRaced()) startRace(ep)
        val go = Runnable {
            reloadPending = null
            if (gen != linkGen) return@Runnable
            sniffer.sniff(page, useRemembered = false) { fresh ->
                if (gen != linkGen || isDestroyed) return@sniff // the viewer moved on (another source, a link page, a new link)
                val dead = goneUrl
                val verified = fresh.sources.filter { it.verified && !it.openPage }
                val sameDead = dead != null && verified.isNotEmpty() && verified.all { DeadStream.samePath(it.url, dead) }
                if (Refusal.sameRefusedStream(refusedUrl, verified.map { it.url })) {
                    // The pieces of this stream were refused and the page hands back the very same stream.
                    Report.log("The page keeps giving the same refused stream; trying other sources")
                    refusedUrl?.let { AutoSwitch.markDead(it, System.currentTimeMillis()) }
                    reconnects = MAX_RECONNECTS
                    if (!tryAutoSwitch(why)) openSitePlayer("video pieces refused")
                    return@sniff
                }
                if (fresh.offline || sameDead) {
                    // The page still leads to the stream that is gone: do not spend the remaining tries on it.
                    if (sameDead) Report.log("The page still points to the same dead stream; trying other sources")
                    dead?.let { AutoSwitch.markDead(it, System.currentTimeMillis()) }
                    reconnects = MAX_RECONNECTS
                    if (!tryAutoSwitch(why)) openSitePlayer("the site's stream is offline")
                } else if (fresh.sources.isEmpty()) {
                    // One empty reload is enough: the page has nothing to give, and the clean player would only get the old, dead set.
                    reconnects = MAX_RECONNECTS
                    Report.log("No stream found while reconnecting")
                    if (EmptyReload.next(AutoSwitch.canSwitch()) != EmptyReload.Step.AUTO_SWITCH || !tryAutoSwitch(why)) {
                        openSitePlayer("no stream found when reconnecting")
                    }
                } else {
                    if (!episode.claim(ep)) return@sniff // a known address checked out first
                    episode.end()
                    // The numbered and other-site entries of the old list stay (the fresh read may not repeat them).
                    val old = set
                    val kept = if (old == null) fresh else fresh.copy(sources = Alternatives.append(fresh.sources, old.sources.filter { it.openPage }))
                    set = kept
                    Session.current = kept
                    keeper?.dropStreamResults() // their addresses belonged to the old set
                    sourceIndex = 0
                    refererIndex = 0
                    // A fresh page load often gives a different server: start again on Chrome's engine.
                    useChromeNet = true
                    chromeForced = false
                    applyRememberedEngine()
                    play()
                }
            }
        }
        reloadPending = go
        main.postDelayed(go, 1500L * reconnects.coerceAtLeast(1))
    }

    /**
     * A new link arrived while this screen is showing. Find its stream with this screen's own
     * page loader: the link screen is in the background, where pages often won't start a video.
     */
    private fun openLink(url: String) {
        linkGen++
        AutoSwitch.reset() // the user started something new: a new watch
        release()
        val site = Report.host(url)
        // A direct link arriving now is not part of any saved list.
        Session.listId = null; Session.listName = ""; Session.listItemUrl = null
        Session.title = site
        screen.showCover("Finding the video on $site…", 1)
        AppStatus.text = "Finding the video on $site…"
        sniffer.timed = true // a new link in front of the player is a new attempt
        sniffer.sniff(url, lateExtras = true) { found ->
            sniffer.timed = false
            if (found.sources.isEmpty()) {
                Timing.emit(Timing.PATH_SITE_PLAYER)
                openSitePlayer(if (found.offline) "the site's stream is offline" else "no direct stream found", found.pageUrl, tryNext = false)
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
        episode.end()
        release()
        startActivity(CleanPlayerActivity.intent(this, sourceIndex))
        finish()
    }

    /**
     * This stream can't play here. Never the site's player by itself (user, 2026-11-20): first the next stream
     * ([tryAutoSwitch]); with none left, the loading screen tries the game's other streams and else offers the site's
     * player as a button. [tryNext] false: a new direct link failed, so the old stream's sources are not used.
     */
    private fun openSitePlayer(why: String, pageUrl: String? = null, tryNext: Boolean = true) {
        if (tryNext && tryAutoSwitch(why)) return
        val page = pageUrl ?: set?.pageUrl ?: return finish()
        Report.log("Stream failed ($why): looking for another stream")
        episode.end()
        release()
        startActivity(WaitingActivity.failedIntent(this, page, Session.title, why, Session.listItemUrl))
        finish()
    }

    /** Only when asked for (phone page): the site's own player. */
    private fun forceSitePlayer(why: String) {
        val page = set?.pageUrl ?: return finish()
        Report.log("Switching to the site's own player ($why)")
        episode.end()
        release()
        startActivity(SitePlayerActivity.intent(this, page))
        finish()
    }

    private fun release() {
        keeper?.pause()
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
            "site" -> forceSitePlayer("requested from phone")
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
        val chosen = s.sources[i]
        cancelRecovery() // a reload still running would overwrite this choice when it lands
        if (chosen.openPage) return openLinkPage(chosen)
        val go = {
            Report.log("Switching to source ${i + 1}")
            sourceIndex = i
            refererIndex = 0
            reconnects = 0
            playingSince = 0
            play()
        }
        if (!useReadyBackup(chosen, go) { best, win ->
                // A playable source of this page: play it in place on the address the keeper just re-checked.
                val cur = set ?: return@useReadyBackup go()
                val idx = cur.sources.indexOfFirst { it.url == chosen.url }
                if (idx < 0) return@useReadyBackup go()
                val list = cur.sources.toMutableList()
                list[idx] = win.copy(label = cur.sources[idx].label, isDefaultLink = cur.sources[idx].isDefaultLink)
                val updated = cur.copy(sources = list)
                set = updated
                if (Session.current === cur) Session.current = updated
                addressUrl = win.url; addressSince = best.foundAt
                Report.log("Switching to source ${idx + 1}")
                sourceIndex = idx
                refererIndex = 0
                reconnects = 0
                playingSince = 0
                play()
            }) go()
    }

    private var pickingBackup = false

    /**
     * The user chose [chosen] in the Source panel. When the keeper holds a fresh result for exactly that source (same freshness rule as
     * [BackupRecovery]: younger than the server's lifetime allows, 2 min if unknown, 30 min if long), quick-check it (2 s, off the main
     * thread) and call [onWin] with the result and the verified source; [onMiss] runs when the quick check fails. Returns false (nothing
     * started) when there is no fresh result, so the caller carries on as before. The user's own choice never counts toward the
     * automatic-switch limit.
     */
    private fun useReadyBackup(chosen: Source, onMiss: () -> Unit, onWin: (BackupResult, Source) -> Unit): Boolean {
        val k = keeper ?: return false
        if (pickingBackup) return true
        val now = System.currentTimeMillis()
        if (BackupRecovery.freshFor(k.known(now), chosen.url) == null) return false
        val best = k.ready(now).firstOrNull { it.url == chosen.url } ?: return false
        pickingBackup = true
        k.pause()
        val gen = linkGen
        kotlin.concurrent.thread(isDaemon = true, name = "backup-pick") {
            val win = QuickRace.first(best.set.sources.take(3), 2000L) { Verifier.quickCheck(cand(it), 2000L) { } }
            main.post {
                pickingBackup = false
                if (gen != linkGen || isDestroyed) return@post
                if (win == null) {
                    onMiss()
                } else {
                    Report.log("Using the backup found ${((System.currentTimeMillis() - best.foundAt) / 1000).coerceAtLeast(0)} s ago for ${chosen.label}")
                    onWin(best, win.second)
                }
            }
        }
        return true
    }

    /**
     * An "open page" entry was chosen: run the normal page flow (loading screen, stream search, clean player,
     * else the site player) on the link's player page, claiming the original page as referer. This screen stays
     * in the back stack (see [onStop]) so Back returns to the stream, which then resumes at live. A fresh ready backup
     * for that page is handed to the loading screen so it skips its search ([PreFound]); the loading screen logs the opening.
     */
    private fun openLinkPage(src: Source) {
        if (set == null) return
        val go = { launchLinkPage(src) }
        if (!useReadyBackup(src, go) { best, win ->
                val ready = listOf(win) + best.set.sources.filter { it.url != win.url }
                PreFound.put(best.target, StreamSet(best.set.pageUrl, ready, requestedUrl = best.set.requestedUrl), System.currentTimeMillis())
                launchLinkPage(src)
            }) go()
    }

    private fun launchLinkPage(src: Source) {
        val target = src.playerUrl.ifEmpty { src.url }
        awayTitle = Session.title
        awayListId = Session.listId; awayListName = Session.listName; awayListItemUrl = Session.listItemUrl
        awayOnLinkPage = true
        cancelRecovery()
        release()
        // A page of another site never gets our page as referer; this site's own numbered addresses do.
        startActivity(WaitingActivity.linkPageIntent(this, target, src.label, src.referers.firstOrNull { it.isNotBlank() }))
    }

    private val snapshotTick = object : Runnable {
        override fun run() {
            publishSnapshot()
            main.postDelayed(this, 1000)
        }
    }

    private fun publishSnapshot() {
        if (awayOnLinkPage) return // the link's page owns the phone status meanwhile
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


    // ---- What the screen (PlayerScreen) reads and asks for ----

    private val screenHost = object : PlayerScreen.Host {
        override val player get() = this@PlayerActivity.player
        override val streamSet get() = this@PlayerActivity.set
        override val sourceIndex get() = this@PlayerActivity.sourceIndex
        override val qualityChoice get() = this@PlayerActivity.qualityChoice
        override val tunneling get() = this@PlayerActivity.tunneling
        override val smoothLive get() = this@PlayerActivity.smoothLive
        override fun qualityLabels() = this@PlayerActivity.qualityLabels()
        override fun setQuality(i: Int) = this@PlayerActivity.setQuality(i)
        override fun setTunneling(on: Boolean) = this@PlayerActivity.setTunneling(on)
        override fun setSmoothLive(on: Boolean) = this@PlayerActivity.setSmoothLive(on)
        override fun switchSource(i: Int) = this@PlayerActivity.switchSource(i)
        override fun reloadFromTv() { reconnects = 1; reconnect("requested from the TV") }
        override fun tryAnotherPlayer() = openCleanPlayer("chosen from the player menu")
        override fun switchToGame(t: GameTile) = this@PlayerActivity.switchToGame(t)
        override fun pingReading() = pings.reading()
        override fun streamBps() = streamRate.bps()
    }

    /**
     * Switch to another game: this player and its backup browser stop first (never two streams at once), then the
     * game's best-ranked stream opens on the loading screen, as if picked from its list. Back then goes to the list.
     */
    private fun switchToGame(t: GameTile) {
        val s = t.stream ?: return
        Report.log("Switching game from the player menu: ${ListText.displayTitle(s.item)} (${s.listName})")
        screen.hideOverlayNow()
        noteAddressAge(true)
        cancelRecovery()
        keeper?.stop()
        release()
        startActivity(WaitingActivity.intent(this, s.item, s.listId, s.listName))
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val up = event.action == KeyEvent.ACTION_UP
        if (screen.pickerShown) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (up) screen.hidePicker()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (screen.coverShown) return super.dispatchKeyEvent(event)

        // Media keys always work.
        val media = when (k) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            else -> null
        }
        if (media != null) {
            if (up) { onCommand(media, null); screen.showOverlay() }
            return true
        }

        if (screen.overlayShown) {
            screen.scheduleHide()
            return super.dispatchKeyEvent(event)
        }

        // Overlay hidden: any of these opens the info overlay (focus on Pause/Play).
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (up) screen.showOverlay()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
