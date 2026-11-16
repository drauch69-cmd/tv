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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
        set = s
        sourceIndex = 0
        refererIndex = 0
        reconnects = 0
        playingSince = 0
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
                k.equals("Referer", true) || k.equals("Origin", true) -> {}
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

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
        userAgent?.let { http.setUserAgent(it) }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(UnwrapDataSourceFactory(http)))
            .build()
        player = p
        playerView.player = p
        qualityOptions = emptyList()
        qualityChoice = -1

        val lower = src.url.lowercase()
        val item = MediaItem.Builder().setUri(src.url).apply {
            if (lower.contains("m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (lower.contains(".mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()

        p.addListener(listener)
        showCover(if (reconnects == 0) "Loading…" else "Reconnecting…")
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            main.removeCallbacks(stallCheck)
            when (playbackState) {
                Player.STATE_READY -> {
                    hideCover()
                    if (playingSince == 0L) {
                        playingSince = System.currentTimeMillis()
                        Report.log("Playback started")
                    }
                    AppStatus.text = "Playing ${Session.title}"
                }
                Player.STATE_BUFFERING -> main.postDelayed(stallCheck, STALL_MS)
                Player.STATE_ENDED -> {
                    Report.log("Stream ended")
                    recover("stream ended")
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onTracksChanged(tracks: Tracks) {
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

    private val stallCheck = Runnable {
        Report.log("Stalled for ${STALL_MS / 1000}s")
        recover("stalled")
    }

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            Report.log("Fell behind the live point; jumping back to live")
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val why = if (http != null) "server said $http" else error.errorCodeName
        Report.log("Playback error: $why")

        val src = set?.sources?.getOrNull(sourceIndex)
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && playingSince == 0L &&
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
        openSitePlayer(why)
    }

    /** Reload the page invisibly to get a fresh stream link (they often expire), then resume at live. */
    private fun reconnect(why: String) {
        release()
        playingSince = 0
        val page = set?.pageUrl ?: return finish()
        showCover("Reconnecting…\n($why)")
        AppStatus.text = "Reconnecting to ${Session.title}…"
        Report.log("Reconnecting (try $reconnects of $MAX_RECONNECTS): reloading the page for a fresh stream link")
        main.postDelayed({
            sniffer.sniff(page) { fresh ->
                if (fresh.sources.isEmpty()) {
                    Report.log("No stream found while reconnecting")
                    if (reconnects < MAX_RECONNECTS) {
                        reconnects++
                        reconnect(why)
                    } else {
                        openSitePlayer(why)
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

    private fun openSitePlayer(why: String) {
        val page = set?.pageUrl ?: return finish()
        Report.log("Switching to the site's own player ($why)")
        release()
        startActivity(SitePlayerActivity.intent(this, page))
        finish()
    }

    private fun release() {
        main.removeCallbacks(stallCheck)
        playerView.player = null
        player?.removeListener(listener)
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
