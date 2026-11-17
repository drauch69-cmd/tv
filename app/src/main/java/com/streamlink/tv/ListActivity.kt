package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One stream list: shows the cached events at once, reads the index page again, and re-reads every
 * 2 minutes while open. OK on an event plays it (and waits for it if it has not started yet).
 */
class ListActivity : Activity() {

    private lateinit var list: SavedList
    private lateinit var reader: IndexReader
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var subtitle: TextView
    private lateinit var errorView: TextView
    private lateinit var refreshBtn: TextView
    private val main = Handler(Looper.getMainLooper())
    private var cached: CachedList? = null
    private var reading = false
    private var readStartedAt = 0L
    private var nextReadAt = 0L
    private var beats = 0
    private var errorText: String? = null

    private val handler: (String, String?) -> Unit = { c, v ->
        when (c) {
            "stop" -> finish()
            "refresh" -> if (v == null || v == list.id) refresh()
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            assertSession()
            beats++
            val now = System.currentTimeMillis()
            if (reading && now - readStartedAt > READ_WATCHDOG_MS) {
                // The reader never answered; drop it and try again at the next slot.
                reader.cancel()
                finishRead(ListResult(list.url, "", emptyList(), "automatic", "the page took too long to answer"))
            }
            if (!reading && now >= nextReadAt) refresh()
            if (beats % 15 == 0) render() // keep badges and "updated … ago" current
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val l = id?.let { SavedLists.get(this, it) }
        if (l == null) { finish(); return }
        list = l
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        cached = SavedLists.cached(this, list.id)
        errorText = SavedLists.lastError(list.id)
        render()
    }

    override fun onStart() {
        super.onStart()
        openId = list.id
        assertSession()
        main.removeCallbacks(beat)
        main.post(beat)
    }

    override fun onStop() {
        if (openId == list.id) openId = null
        main.removeCallbacks(beat)
        reader.cancel()
        if (reading) { reading = false; SavedLists.setBusy(list.id, false) }
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        reader.cancel()
        SavedLists.setBusy(list.id, false)
        releaseSession()
        super.onDestroy()
    }

    // ---- Session (phone status) ----

    private fun assertSession() {
        if (Session.commandHandler !== handler || Session.mode != "list") {
            Session.commandHandler = handler
            Session.mode = "list"
            Session.title = list.name
            Session.snapshot = null
            AppStatus.text = "Stream list: ${list.name}"
        }
    }

    private fun releaseSession() {
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
    }

    // ---- Reading ----

    private fun refresh() {
        if (reading) return
        reading = true
        readStartedAt = System.currentTimeMillis()
        SavedLists.setBusy(list.id, true)
        updateHeader()
        reader.read(list.url) { result -> finishRead(result) }
    }

    private fun finishRead(result: ListResult) {
        reading = false
        SavedLists.setBusy(list.id, false)
        val now = System.currentTimeMillis()
        nextReadAt = now + REREAD_MS
        errorText = SavedLists.applyResult(this, list, result, now)
        cached = SavedLists.cached(this, list.id)
        render()
    }

    // ---- UI ----

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0D1220.toInt())
        // Hidden page loader for the reader, behind the content.
        val holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        reader = IndexReader(this, holder)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1220.toInt())
            setPadding(dp(48), dp(32), dp(48), dp(24))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(list.name, 30f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        subtitle = text("", 16f, 0xFFB8C4E0.toInt()).apply { setPadding(0, dp(4), 0, 0) }
        titles.addView(subtitle)
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshBtn = text("Refresh", 18f, 0xFFFFFFFF.toInt(), bold = true).apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setBackgroundResource(R.drawable.recent_item)
            isFocusable = true; isClickable = true
            setOnClickListener { nextReadAt = 0; refresh() }
        }
        top.addView(refreshBtn)
        content.addView(top)
        errorView = text("", 16f, 0xFFFF8A94.toInt()).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        content.addView(errorView)

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        content.addView(text("OK plays an event. If it has not started, StreamLink waits for it. Back returns.", 14f, 0xFF7F8DAD.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun updateHeader() {
        val c = cached
        val now = System.currentTimeMillis()
        val ago = if (c == null) "not read yet" else "updated ${ListText.ago(c.readAt, now)}"
        subtitle.text = if (reading) "Refreshing…  ($ago)" else ago.replaceFirstChar { it.uppercase() }
        val e = errorText
        errorView.visibility = if (e == null) View.GONE else View.VISIBLE
        errorView.text = e ?: ""
    }

    private fun render() {
        updateHeader()
        val now = System.currentTimeMillis()
        // Keep the highlighted event highlighted across rebuilds (matched by link).
        val focusedUrl = (currentFocus?.tag as? String)
        val hadFocusInRows = currentFocus?.parent === rows
        val items = cached?.let { ListJson.visible(it.result.items, now) } ?: emptyList()
        rows.removeAllViews()
        if (items.isEmpty()) {
            val msg = when {
                reading && cached == null -> "Reading events…"
                cached == null -> "No events yet."
                else -> "No upcoming events found on this page."
            }
            rows.addView(text(msg, 20f, 0xFFB8C4E0.toInt()).apply { setPadding(0, dp(12), 0, 0) })
            if (!refreshBtn.hasFocus()) refreshBtn.requestFocus()
            return
        }
        var focusTarget: View? = null
        for (item in items) {
            val row = row(item, now)
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            if (focusedUrl != null && item.url == focusedUrl && focusTarget == null) focusTarget = row
        }
        when {
            focusTarget != null -> focusTarget.requestFocus()
            hadFocusInRows || currentFocus == null -> rows.getChildAt(0)?.requestFocus()
        }
    }

    private fun row(item: ListItem, now: Long): View {
        val status = ListStatus.of(item, now)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.recent_item)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = true; isClickable = true
            tag = item.url
            setOnClickListener { play(item) }
        }
        val color = when (status) {
            ListStatus.LIVE -> 0xFFE5384A.toInt()
            ListStatus.SOON -> 0xFFE08A1E.toInt()
            ListStatus.UPCOMING -> 0xFF3A5FA8.toInt()
            else -> 0xFF4A5470.toInt()
        }
        val badge = text(ListText.badge(item, now), 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ListText.displayTitle(item), 20f, Color.WHITE, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val meta = listOf(item.subtitle.trim(), item.section.trim()).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (meta.isNotEmpty()) col.addView(text(meta, 15f, 0xFFB8C4E0.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun play(item: ListItem) {
        startActivity(WaitingActivity.intent(this, item, list.id, list.name))
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s // plain text, never HTML: titles come from untrusted pages
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "id"
        /** The list whose screen is in front right now, so the phone's Refresh goes through it. */
        @Volatile var openId: String? = null
        const val REREAD_MS = 120_000L
        private const val READ_WATCHDOG_MS = 90_000L
        fun intent(ctx: Context, id: String) = Intent(ctx, ListActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
