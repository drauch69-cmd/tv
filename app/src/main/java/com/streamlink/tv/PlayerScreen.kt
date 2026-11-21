package com.streamlink.tv

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Everything the player screen draws: the loading cover, the info overlay (buttons, progress, speed pill, other-game
 * tiles) and the Audio & quality / Source panels. Moved out of [PlayerActivity] unchanged (2026-11-20 cleanup);
 * playback state and actions come from [Host], which the player implements.
 */
class PlayerScreen(private val a: Activity, private val root: FrameLayout, private val main: Handler, private val host: Host) {

    /** What the screen reads from and asks of the player. */
    interface Host {
        val player: ExoPlayer?
        val streamSet: StreamSet?
        val sourceIndex: Int
        val qualityChoice: Int
        val tunneling: Boolean
        val smoothLive: Boolean
        fun qualityLabels(): List<String>
        fun setQuality(i: Int)
        fun setTunneling(on: Boolean)
        fun setSmoothLive(on: Boolean)
        fun switchSource(i: Int)
        fun reloadFromTv()
        fun tryAnotherPlayer()
        fun switchToGame(t: GameTile)
        fun pingReading(): PingStats.Reading
        fun streamBps(): Long
    }

    val coverShown get() = cover.visibility == View.VISIBLE
    val pickerShown get() = picker.visibility == View.VISIBLE
    val overlayShown get() = overlay.visibility == View.VISIBLE

    fun build() {
        buildCover()
        buildOverlay()
        buildPicker()
    }

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
    /** Connection line: ping, what the stream needs, dropped frames (PlayerStats). */
    private lateinit var ovNet: TextView
    private lateinit var btnLive: TextView
    private lateinit var btnOptions: TextView
    private lateinit var btnOther: TextView
    private lateinit var btnPause: TextView
    private lateinit var btnSource: TextView

    // ---- Screen: loading cover ----

    private fun dp(v: Float) = Ui.dp(a, v)

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        Ui.text(a, label, 12f, if (primary) Ui.ON_ACCENT else Ui.TEXT, bold = true).apply {
            if (primary) Ui.primaryCard(this) else Ui.focusCard(this, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun gap(h: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(h) }

    private fun buildCover() {
        cover = FrameLayout(a).apply {
            setBackgroundColor(Ui.BG)
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        coverSub = Ui.text(a, "", 12f, Ui.TEXT_2).apply { gravity = Gravity.CENTER }
        coverTitle = Ui.text(a, "", 30f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40f), 0, dp(40f), 0)
        }
        coverSteps = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        coverHint = Ui.text(a, "Usually ready in under 10 seconds", 12f, Ui.TEXT_3)
        coverText = Ui.text(a, "", 14f, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(40f), 0, dp(40f), 0)
        }
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        coverCancel = button("Cancel") { a.finish() }
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
                    View(a).apply { setBackgroundColor(if (i <= active) Ui.ACCENT else 0xFF2E3A4D.toInt()) },
                    LinearLayout.LayoutParams(dp(52f), dp(2f)).apply { bottomMargin = dp(22f) },
                )
            }
            val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val circle = Ui.text(a, if (i < active) "✓" else "", 13f, Ui.ON_ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = when {
                    i < active -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
                    i == active -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2f), Ui.ACCENT) }
                    else -> GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2f), 0xFF2E3A4D.toInt()) }
                }
            }
            c.addView(circle, LinearLayout.LayoutParams(dp(25f), dp(25f)))
            c.addView(Ui.text(a, n, 12f, Ui.TEXT, bold = i == active).apply { gravity = Gravity.CENTER }, gap(7f))
            coverSteps.addView(c, LinearLayout.LayoutParams(dp(128f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun listLabel(): String {
        val site = host.streamSet?.let { Report.host(it.pageUrl) } ?: ""
        return if (Session.listId != null && Session.listName.isNotBlank()) Session.listName else site
    }

    /** The technical status text now sits behind "Show details" (coverText). */
    fun showCover(text: String, stage: Int = 2, finished: Boolean = false) {
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

    fun hideCover() {
        if (cover.visibility != View.VISIBLE) return
        cover.visibility = View.GONE
        coverText.visibility = View.GONE
        coverDetails.text = "Show details"
        if (picker.visibility != View.VISIBLE) showOverlay()
    }

    // ---- Screen: info overlay ----

    private val hideOverlayRunnable = Runnable { hideOverlayNow() }
    private var overlayTicks = 0
    private val overlayTick = object : Runnable {
        override fun run() {
            if (overlay.visibility != View.VISIBLE) return
            refreshProgress()
            if (++overlayTicks % 20 == 0) ScoreFeed.refreshIfDue { refreshTiles() } // every 10 s; fetches only when due (30 s live)
            main.postDelayed(this, 500)
        }
    }

    private fun buildOverlay() {
        overlay = FrameLayout(a).apply { visibility = View.GONE }
        // darkening gradient behind the text
        overlay.addView(View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF50E1116.toInt(), 0x000E1116))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(195f), Gravity.BOTTOM))

        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val meta = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ovBadge = Ui.badge(a, "LIVE", live = true, sp = 10.5f)
        ovSub = Ui.text(a, "", 12f, 0xFFC9CFD8.toInt())
        meta.addView(ovBadge)
        meta.addView(ovSub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(9f) })
        ovTitle = Ui.text(a, "", 30f, Ui.TEXT, bold = true).apply { maxLines = 2 }

        val progress = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ovTrack = FrameLayout(a).apply { background = Ui.rounded(context, 0xFF2E3A4D.toInt(), 2f) }
        ovFill = View(a).apply { background = Ui.rounded(context, Ui.ACCENT, 2f) }
        ovTrack.addView(ovFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        ovStatus = Ui.text(a, "", 11.5f, 0xFFC9CFD8.toInt())
        progress.addView(ovTrack, LinearLayout.LayoutParams(0, dp(4.5f), 1f))
        progress.addView(ovStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(10f) })

        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btnLive = button("Jump to live") { host.player?.seekToDefaultPosition(); scheduleHide() }
        btnOptions = button("Audio & quality") { showOptions() }
        btnOther = button("Try another player") { host.tryAnotherPlayer() }
        btnPause = button("Pause") { host.player?.let { it.playWhenReady = !it.playWhenReady }; refreshProgress(); scheduleHide() }
        btnSource = button("Source") { showSource() }
        row.gravity = Gravity.CENTER_VERTICAL
        for (b in listOf(btnPause, btnOptions, btnSource, btnLive, btnOther)) {
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(9f) })
        }
        // Connection pill, bottom right: speed vs what the stream needs, colored by how much room there is.
        row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        // Same shape, padding and text size as the buttons beside it, so it lines up with them; not focusable.
        ovNet = Ui.text(a, "", 11f, Ui.TEXT, bold = true).apply {
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            visibility = View.GONE
            contentDescription = "Connection speed and what the stream needs"
        }
        row.addView(ovNet)
        col.addView(meta)
        col.addView(ovTitle, gap(12f))
        col.addView(progress, gap(12f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
        col.addView(row, gap(12f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
        overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { marginStart = dp(42f); marginEnd = dp(42f); bottomMargin = dp(30f) })

        tilesBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; visibility = View.GONE }
        overlay.addView(tilesBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { topMargin = dp(24f); marginEnd = dp(32f) })

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---- Overlay: other live games of the same sport (GameTiles) ----

    private lateinit var tilesBox: LinearLayout
    private var tilesShown: TileSet? = null
    private var tilesBusy = false
    private var tilesAgain = false

    /** Works out the tiles off the main thread (cached lists and scores only, no network) and redraws if they changed. */
    private fun refreshTiles() {
        if (tilesBusy) { tilesAgain = true; return }
        tilesBusy = true
        val itemUrl = Session.listItemUrl
        val title = Session.title
        val ctx = a.applicationContext
        kotlin.concurrent.thread(isDaemon = true, name = "game-tiles") {
            val next = try {
                val folders = SportsData.both(ctx)
                GameTiles.current(folders, itemUrl, title, ScoreFeed.latest())?.let { (lg, id) -> GameTiles.pick(folders, lg, id) }
            } catch (_: Exception) { null }
            main.post {
                tilesBusy = false
                if (a.isFinishing || a.isDestroyed) return@post
                if (!GameTiles.sameLook(tilesShown, next)) drawTiles(next)
                if (tilesAgain) { tilesAgain = false; refreshTiles() }
            }
        }
    }

    /** Two rows of up to 4 small tiles, top right. Switchable games are focusable; Down from the last row goes to Pause. */
    private fun drawTiles(set: TileSet?) {
        val focusedId = (a.currentFocus?.tag as? String)?.takeIf { a.currentFocus?.parent?.parent === tilesBox }
        tilesShown = set
        tilesBox.removeAllViews()
        if (set == null || set.tiles.isEmpty()) {
            tilesBox.visibility = View.GONE
            btnRowUpTarget(View.NO_ID)
            if (focusedId != null && overlay.visibility == View.VISIBLE) btnPause.requestFocus()
            return
        }
        if (btnPause.id == View.NO_ID) btnPause.id = View.generateViewId()
        val views = ArrayList<View>()
        for (t in set.tiles) views.add(tileView(t))
        if (set.more > 0) views.add(moreView(set.more))
        val rows = views.chunked(4)
        rows.forEachIndexed { r, rowViews ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            rowViews.forEachIndexed { i, v ->
                if (r == rows.lastIndex) v.nextFocusDownId = btnPause.id
                if (i == rowViews.lastIndex) v.nextFocusRightId = v.id
                row.addView(v, LinearLayout.LayoutParams(dp(112f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(6f) })
            }
            tilesBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (r > 0) topMargin = dp(6f) })
        }
        tilesBox.visibility = View.VISIBLE
        // Up from the buttons goes to the first tile of the bottom row that can be picked (else any that can).
        val target = rows.last().firstOrNull { it.isFocusable } ?: views.firstOrNull { it.isFocusable }
        btnRowUpTarget(target?.id ?: View.NO_ID)
        if (focusedId != null) {
            val again = views.firstOrNull { it.tag == focusedId && it.isFocusable }
            if (again != null) again.requestFocus() else btnPause.requestFocus()
        }
    }

    private fun btnRowUpTarget(id: Int) {
        for (b in listOf(btnPause, btnOptions, btnSource, btnLive, btnOther)) b.nextFocusUpId = id
    }

    private fun tileView(t: GameTile): View {
        val can = t.stream != null
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            id = View.generateViewId()
            tag = t.game.id
            setPadding(dp(9f), dp(5f), dp(9f), dp(6f))
            if (can) {
                Ui.focusCard(this, 0xD9171B22.toInt(), 0xF21B2230.toInt(), 7f)
                isFocusable = true; isClickable = true
                setOnClickListener { host.switchToGame(t) }
            } else {
                background = Ui.rounded(context, 0x99171B22.toInt(), 7f)
                alpha = 0.6f
            }
        }
        box.addView(Ui.text(a, GameTiles.scoreLine(t.game), 11.5f, Ui.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        box.addView(Ui.text(a, GameTiles.statusLine(t.game, can), 10.5f, Ui.TEXT_2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        return box
    }

    private fun moreView(n: Int): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        id = View.generateViewId()
        gravity = Gravity.CENTER
        alpha = 0.6f
        background = Ui.rounded(context, 0x99171B22.toInt(), 7f)
        setPadding(dp(9f), dp(5f), dp(9f), dp(6f))
        addView(Ui.text(a, "+$n", 11.5f, Ui.TEXT, bold = true))
        addView(Ui.text(a, "more live", 10.5f, Ui.TEXT_2))
    }

    private fun currentListItem(): ListItem? {
        val id = Session.listId ?: return null
        return SavedLists.cached(a, id)?.result?.items?.firstOrNull { it.url == Session.listItemUrl }
    }

    fun showOverlay() {
        if (cover.visibility == View.VISIBLE || picker.visibility == View.VISIBLE) return
        val first = overlay.visibility != View.VISIBLE
        val live = host.player?.isCurrentMediaItemLive == true
        val site = host.streamSet?.let { Report.host(it.pageUrl) } ?: ""
        val started = currentListItem()?.startMillis?.let { System.currentTimeMillis() - it }?.takeIf { it > 0 }
        ovBadge.visibility = if (live) View.VISIBLE else View.GONE
        ovSub.text = listOfNotNull(listLabel().takeIf { it.isNotBlank() }, started?.let { startedText(it) }).joinToString(" · ")
        ovTitle.text = Session.title.ifBlank { site }
        btnLive.visibility = if (live) View.VISIBLE else View.GONE
        btnSource.visibility = if (sourceButtonVisible()) View.VISIBLE else View.GONE
        refreshProgress()
        overlay.visibility = View.VISIBLE
        if (first) {
            btnPause.requestFocus()
            main.removeCallbacks(overlayTick)
            main.post(overlayTick)
            refreshTiles()
            ScoreFeed.refreshIfDue { refreshTiles() }
        }
        scheduleHide()
    }

    private fun sourceButtonVisible() = (host.streamSet?.sources?.size ?: 0) > 1

    /** Re-evaluate the overlay (e.g. the Source button) when the Source list changes. */
    fun refreshOverlayIfShown() {
        if (::overlay.isInitialized && overlay.visibility == View.VISIBLE) showOverlay()
    }

    fun scheduleHide() {
        main.removeCallbacks(hideOverlayRunnable)
        main.postDelayed(hideOverlayRunnable, 5_000)
    }

    fun hideOverlayNow() {
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
        val p = host.player
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
        if (p == null) ovNet.visibility = View.GONE else {
            // What the stream needs: what it announces if it does, else what its pieces measure.
            val announced = (p.videoFormat?.bitrate ?: -1).toLong()
            val line = PlayerStats.line(
                host.pingReading(),
                if (announced > 0) announced else host.streamBps(),
                p.videoDecoderCounters?.droppedBufferCount ?: -1,
            )
            val (fill, ink) = when (line.level) {
                // Button-dark fills with only a hint of the level's color; the numbers carry the color.
                PlayerStats.Level.GOOD -> 0xFF1E2E28.toInt() to 0xFF8EE3B5.toInt()
                PlayerStats.Level.TIGHT -> 0xFF302B1E.toInt() to Ui.WARN
                PlayerStats.Level.SLOW -> 0xFF33212A.toInt() to Ui.LIVE_TEXT
                PlayerStats.Level.MEASURING -> Ui.CHIP to Ui.TEXT_2
            }
            ovNet.text = line.text
            ovNet.setTextColor(ink)
            ovNet.background = Ui.rounded(a, fill, 8f)
            ovNet.visibility = View.VISIBLE
        }
        val w = ovTrack.width
        ovFill.layoutParams = (ovFill.layoutParams as FrameLayout.LayoutParams).apply {
            width = (w * fraction.coerceIn(0f, 1f)).toInt()
        }
    }


    // ---- Screen: options panel (sources, quality, tunneling, live delay, reload) ----

    private fun buildPicker() {
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, 0xF2171B22.toInt(), 14f, Ui.BORDER, 1f)
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            visibility = View.GONE
        }
        panel.addView(Ui.text(a, "Audio & quality", 18f, Ui.TEXT, bold = true).apply { tag = "pickerTitle" })
        pickerList = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { isFillViewport = false }
        scroll.addView(pickerList)
        panel.addView(scroll, gap(10f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT; height = dp(300f) })
        picker = panel
        root.addView(panel, FrameLayout.LayoutParams(dp(390f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun showOptions() {
        host.streamSet ?: return
        hideOverlayNow()
        pickerList.removeAllViews()
        picker.findViewWithTag<TextView>("pickerTitle")?.text = "Audio & quality"
        var focusRow: View? = null
        fun label(t: String) = pickerList.addView(Ui.sectionLabel(a, t), gap(10f))
        fun row(t: String, current: Boolean, action: () -> Unit): TextView {
            val v = Ui.text(a, (if (current) "✓  " else "") + t, 13f, Ui.TEXT, bold = current).apply {
                Ui.focusCard(this, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                isFocusable = true
                isClickable = true
                setOnClickListener { hidePicker(); action() }
            }
            pickerList.addView(v, gap(5f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
            if (current && focusRow == null) focusRow = v
            return v
        }
        if (host.qualityLabels().isNotEmpty()) {
            label("Quality")
            row("Auto", host.qualityChoice < 0) { host.setQuality(-1) }
            host.qualityLabels().forEachIndexed { i, l -> row(l, host.qualityChoice == i) { host.setQuality(i) } }
        }
        label("Playback")
        row("Tunneled playback: ${if (host.tunneling) "on" else "off"} (tap to turn ${if (host.tunneling) "off" else "on"})", false) { host.setTunneling(!host.tunneling) }
        row("Live delay: ${if (host.smoothLive) "smooth (~20 s behind)" else "low delay"} (tap to change)", false) { host.setSmoothLive(!host.smoothLive) }
        row("Reload the stream", false) { host.reloadFromTv() }
        picker.visibility = View.VISIBLE
        (focusRow ?: pickerList.getChildAt(1) ?: pickerList.getChildAt(0))?.requestFocus()
    }

    /** The Source panel: all sources by label (✓ on the playing one); same look as Audio & quality. */
    private fun showSource() {
        val s = host.streamSet ?: return
        hideOverlayNow()
        pickerList.removeAllViews()
        picker.findViewWithTag<TextView>("pickerTitle")?.text = "Source"
        var focusRow: View? = null
        s.sources.forEachIndexed { i, src ->
            val current = i == host.sourceIndex
            val v = Ui.text(a, (if (current) "✓  " else "") + "${i + 1}. ${src.label}", 13f, Ui.TEXT, bold = current).apply {
                Ui.focusCard(this, Ui.CHIP, Ui.SURFACE_FOCUS, 8f)
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                isFocusable = true
                isClickable = true
                setOnClickListener { hidePicker(); host.switchSource(i) }
            }
            pickerList.addView(v, gap(5f).apply { width = ViewGroup.LayoutParams.MATCH_PARENT })
            if (current && focusRow == null) focusRow = v
        }
        picker.visibility = View.VISIBLE
        (focusRow ?: pickerList.getChildAt(0))?.requestFocus()
    }

    fun hidePicker() {
        picker.visibility = View.GONE
    }
}
