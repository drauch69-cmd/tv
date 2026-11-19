package com.streamlink.tv

import android.content.Context
import java.time.ZoneId

/** Loads the sports folders from the saved lists' caches and the cached scores (no network). */
object SportsData {
    /** Every saved list with the events last read from it (empty when never read). */
    fun lists(ctx: Context): List<Pair<SavedList, List<ListItem>>> =
        SavedLists.all(ctx).map { l -> l to (SavedLists.cached(ctx, l.id)?.result?.items ?: emptyList()) }

    fun folders(
        ctx: Context, league: String, now: Long = System.currentTimeMillis(),
        lists: List<Pair<SavedList, List<ListItem>>> = lists(ctx),
    ): SportFolders = try {
        GameFolders.build(league, ScoreFeed.latest(), lists, now, ZoneId.systemDefault(), Preferred.idOf(lists.map { it.first }))
    } catch (_: Exception) {
        SportFolders(league, emptyList(), emptyList())
    }

    /** All sports, in [Sports.ALL] order. */
    fun both(ctx: Context, now: Long = System.currentTimeMillis()): List<SportFolders> {
        val ls = lists(ctx)
        return Sports.ALL.map { folders(ctx, it, now, ls) }
    }

    fun taggedCount(ctx: Context, league: String): Int = SavedLists.all(ctx).count { it.has(league) }
}

/** Shared pieces of the sports screens. Text from pages is shown as plain text only. */
object SportsUi {
    /** One stream row (title, "list name · when", status badge). Tag = the stream's address key. */
    fun streamRow(ctx: Context, s: GameStream, now: Long, onOpen: () -> Unit, onFocus: (Boolean) -> Unit, preferred: Boolean = false): android.view.View {
        val item = s.item
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            Ui.focusCard(this)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
            isFocusable = true; isClickable = true
            tag = GameFolders.urlKey(item.url)
            setOnClickListener { onOpen() }
            setOnFocusChangeListener { _, has -> onFocus(has) }
        }
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, ListText.displayTitle(item), 16f, Ui.TEXT, bold = true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val meta = listOf(if (preferred) "★ Preferred" else "", s.listName, ListSections.meta(item, now)).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (meta.isNotEmpty()) col.addView(Ui.text(ctx, meta, 11f, Ui.TEXT_2).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, Ui.dp(ctx, 3), 0, 0)
        })
        row.addView(col, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(ctx, 14) })
        val b = ListSections.badge(item, now)
        row.addView(if (b.pill) Ui.badge(ctx, b.text, b.live) else Ui.text(ctx, b.text, 11f, Ui.TEXT_2, bold = true))
        return row
    }

    fun rowParams(ctx: Context) =
        android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Ui.dp(ctx, 7) }

    fun labelParams(ctx: Context, first: Boolean) =
        android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = if (first) 0 else Ui.dp(ctx, 8); bottomMargin = Ui.dp(ctx, 6) }
}
