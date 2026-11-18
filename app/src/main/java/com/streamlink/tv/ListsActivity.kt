package com.streamlink.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * "Your lists": the saved stream lists as tiles (opened from the top bar of the home screen). OK opens a list,
 * hold OK removes it, Menu re-reads all of them. The home screen owns the background reader; this screen redraws
 * whenever it reports a change ([ListEvents]).
 */
class ListsActivity : Activity() {

    private lateinit var grid: GridLayout
    private lateinit var subtitle: TextView
    private val main = Handler(Looper.getMainLooper())
    private val calc = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "lists-calc").apply { isDaemon = true } }
    private var gen = 0

    private val handler: (String, String?) -> Unit = { c, _ -> if (c == "stop") finish() }

    private val tick = object : Runnable {
        override fun run() {
            assertSession()
            render()
            main.postDelayed(this, 60_000L) // "Read 5 min ago", live counts follow the clock
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        assertSession()
    }

    override fun onResume() {
        super.onResume()
        ListEvents.onChanged = { if (!isFinishing && !isDestroyed) render() }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        if (ListEvents.onChanged != null) ListEvents.onChanged = null
        super.onPause()
    }

    override fun onStop() {
        releaseSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        gen++
        releaseSession()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val lists = SavedLists.all(this)
            if (lists.isEmpty()) {
                Toast.makeText(this, "No lists yet: add one from the phone page", Toast.LENGTH_SHORT).show()
            } else {
                lists.forEach { ListEvents.refresh?.invoke(it.id) }
                Toast.makeText(this, "Refreshing ${lists.size} list${if (lists.size == 1) "" else "s"}…", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun assertSession() {
        if (Session.commandHandler !== handler || Session.mode != "list") {
            Session.commandHandler = handler
            Session.mode = "list"
            Session.title = "Your lists"
            Session.snapshot = null
            AppStatus.text = "Your lists"
        }
    }

    private fun releaseSession() {
        if (Session.commandHandler === handler) {
            Session.commandHandler = null
            Session.mode = "idle"
            AppStatus.text = "Waiting for a link"
        }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@ListsActivity, 42), Ui.dp(this@ListsActivity, 30), Ui.dp(this@ListsActivity, 42), Ui.dp(this@ListsActivity, 22))
        }
        content.addView(Ui.text(this, "StreamLink", 12f, Ui.TEXT_3))
        content.addView(Ui.text(this, "Your lists", 27f, Ui.TEXT, bold = true).apply { setPadding(0, Ui.dp(this@ListsActivity, 3), 0, 0) })
        subtitle = Ui.text(this, "", 12f, Ui.TEXT_3).apply { setPadding(0, Ui.dp(this@ListsActivity, 4), 0, Ui.dp(this@ListsActivity, 14)) }
        content.addView(subtitle)
        grid = GridLayout(this).apply { columnCount = 4 }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(Ui.dp(this@ListsActivity, 4), Ui.dp(this@ListsActivity, 6), Ui.dp(this@ListsActivity, 4), 0)
            addView(grid)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(Ui.text(this, "OK  Open     Menu  Refresh all lists     Hold OK  Remove     Back  Home", 12f, Ui.TEXT_3).apply {
            setPadding(0, Ui.dp(this@ListsActivity, 8), 0, 0)
        })
        setContentView(content)
    }

    /** Works out the counts on a background thread, then redraws the tiles (focus kept). */
    private fun render() {
        val my = ++gen
        val app = applicationContext
        calc.execute {
            val lists = SavedLists.all(app)
            val byId = HashMap<String, CachedList>()
            for (l in lists) SavedLists.cached(app, l.id)?.let { byId[l.id] = it }
            val now = System.currentTimeMillis()
            val games = ScoreFeed.latest()
            val counts = HashMap<String, HomeFeed.Counts>()
            try {
                for (l in lists) byId[l.id]?.let { c ->
                    counts[l.id] = HomeFeed.counts(c.result.items, now, games)
                }
            } catch (_: Exception) {}
            main.post { if (my == gen && !isFinishing && !isDestroyed) draw(lists, byId, counts) }
        }
    }

    private fun draw(lists: List<SavedList>, byId: Map<String, CachedList>, counts: Map<String, HomeFeed.Counts>) {
        subtitle.text = if (lists.isEmpty()) "No lists yet" else "${lists.size} saved list${if (lists.size == 1) "" else "s"}"
        ListTiles.render(this, grid, lists, byId, counts,
            onOpen = { l -> startActivity(ListActivity.intent(this, l.id)) },
            onRemove = { l -> ListTiles.confirmRemove(this, l) { render() } },
            onAdd = { ListEvents.showQrOnHome = true; finish() })
    }

    companion object {
        fun intent(ctx: Context) = Intent(ctx, ListsActivity::class.java)
    }
}
