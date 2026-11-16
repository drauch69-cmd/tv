package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Fullscreen player. D-pad shows the controls, OK pauses/plays, Back returns to the link screen. */
class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private var player: ExoPlayer? = null

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_HEADER_KEYS = "hk"
        private const val EXTRA_HEADER_VALUES = "hv"

        fun intent(ctx: Context, s: FoundStream): Intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra(EXTRA_URL, s.url)
            .putExtra(EXTRA_HEADER_KEYS, s.headers.keys.toTypedArray())
            .putExtra(EXTRA_HEADER_VALUES, s.headers.values.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.playerView)
        message = findViewById(R.id.message)
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    @OptIn(UnstableApi::class)
    private fun start(i: Intent) {
        release()
        val url = i.getStringExtra(EXTRA_URL) ?: return finish()
        val keys = i.getStringArrayExtra(EXTRA_HEADER_KEYS) ?: emptyArray()
        val values = i.getStringArrayExtra(EXTRA_HEADER_VALUES) ?: emptyArray()
        val headers = HashMap<String, String>()
        var userAgent: String? = null
        keys.forEachIndexed { idx, k ->
            val v = values.getOrNull(idx) ?: return@forEachIndexed
            if (k.equals("User-Agent", true)) userAgent = v else headers[k] = v
        }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
        userAgent?.let { http.setUserAgent(it) }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player = p
        playerView.player = p

        val lower = url.lowercase()
        val item = MediaItem.Builder().setUri(url).apply {
            if (lower.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
            else if (lower.contains(".mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) message.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Fell behind the live edge: jump back to live.
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                val text = "Playback failed (${error.errorCodeName})"
                AppStatus.text = text
                message.text = "$text\nPress Back to try another link."
                message.visibility = View.VISIBLE
            }
        })

        message.text = "Loading…"
        message.visibility = View.VISIBLE
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        release()
        finish()
    }

    private fun release() {
        playerView.player = null
        player?.release()
        player = null
    }
}
