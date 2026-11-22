package com.streamlink.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast

/** Tells the open "Your lists" screen that lists changed, and lets it ask the home screen (which owns the reader) to act. */
object ListEvents {
    /** Set by the "Your lists" screen while it is open; called on the main thread when lists, busy marks or scores changed. */
    @Volatile var onChanged: (() -> Unit)? = null
    /** Set by the home screen: re-reads one list in the background. Safe to call from any thread. */
    @Volatile var refresh: ((String) -> Unit)? = null
    /** The "Your lists" screen's add tile asks the home screen to show the phone code when it comes back. */
    @Volatile var showQrOnHome = false
    fun changed() { onChanged?.invoke() }
}

/** The list tiles (name, counts, read state, add tile) shared by the "Your lists" screen. */
object ListTiles {
    const val ADD_TAG = "add-list"
    const val SITE_TAG = "site:"

    fun dashedFocusable(a: Activity): StateListDrawable {
        val normal = GradientDrawable().apply {
            setColor(0)
            cornerRadius = Ui.dp(a, 11).toFloat()
            setStroke(Ui.dp(a, 2), Ui.BORDER, Ui.dp(a, 6).toFloat(), Ui.dp(a, 5).toFloat())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(a, Ui.SURFACE_FOCUS, 11f))
            addState(intArrayOf(), normal)
        }
    }

    /**
     * Redraws [row] with one tile per list plus the add tile, keeping focus on the same tile (or the first one if
     * its list went away). [counts] are only there for lists that have been read.
     */
    /** What the Lists screen shows on a site's heading (SiteHealth). */
    data class SiteNote(val movedFrom: String? = null, val notWorking: Boolean = false)

    /**
     * Lists grouped by site (lab, 2026-11-22): a small heading per site (its domain, "Moved from …" or "Not working"),
     * then that site's tiles; sites in the order of their best-ranked list. A "Not working" heading can be focused:
     * OK runs [onSite] (remove or keep). In a GridLayout the heading spans the whole row.
     */
    fun render(
        a: Activity, row: ViewGroup, lists: List<SavedList>, cachedById: Map<String, CachedList>,
        counts: Map<String, HomeFeed.Counts>, onOpen: (SavedList) -> Unit, onRemove: (SavedList) -> Unit, onAdd: () -> Unit,
        notes: Map<String, SiteNote> = emptyMap(), onSite: (String, Int) -> Unit = { _, _ -> },
    ) {
        val focusedId = row.focusedChild?.tag as? String
        val addFocused = row.focusedChild?.tag == ADD_TAG
        row.removeAllViews()
        val now = System.currentTimeMillis()
        var focusTarget: View? = null
        for ((site, group) in SiteMove.groups(Ranking.sorted(lists))) { // best-ranked first, unranked after in saved order
            val note = notes[site] ?: SiteNote()
            val head = SpannableStringBuilder(site)
            if (note.movedFrom != null) {
                val st = head.length
                head.append("   Moved from ").append(note.movedFrom)
                head.setSpan(ForegroundColorSpan(Ui.ACCENT), st, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (note.notWorking) {
                val st = head.length
                head.append("   Not working: press OK to remove or keep")
                head.setSpan(ForegroundColorSpan(Ui.WARN), st, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val heading = Ui.text(a, head, 12.5f, Ui.TEXT_2, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 6))
                tag = SITE_TAG + site
                if (note.notWorking) {
                    Ui.focusRing(this, 8f)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { onSite(site, group.size) }
                }
            }
            val hlp: ViewGroup.MarginLayoutParams = if (row is GridLayout) {
                GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, row.columnCount.coerceAtLeast(1))).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            } else ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            hlp.bottomMargin = Ui.dp(a, 6)
            row.addView(heading, hlp)
            if (focusedId == SITE_TAG + site && note.notWorking) focusTarget = heading
            for (l in group) {
                val c = cachedById[l.id]
                val tile = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    Ui.focusCard(this, radiusDp = 11f)
                    setPadding(Ui.dp(a, 15), Ui.dp(a, 13), Ui.dp(a, 15), Ui.dp(a, 13))
                    isFocusable = true
                    isClickable = true
                    tag = l.id
                    setOnClickListener { onOpen(l) }
                    setOnLongClickListener { onRemove(l); true }
                }
                tile.addView(Ui.text(a, l.name, 15f, Ui.TEXT, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (l.rank != null) tile.addView(Ui.text(a, "Rank ${Ranking.label(l.rank)}", 10.5f, Ui.ACCENT, bold = true).apply { setPadding(0, Ui.dp(a, 3), 0, 0) })
                val cn = counts[l.id]
                val line = SpannableStringBuilder()
                if (cn == null) {
                    line.append(if (SavedLists.isBusy(l.id)) "Reading…" else "Not read yet")
                } else {
                    val t = HomeFeed.countsText(cn)
                    line.append(t)
                    if (cn.live > 0) {
                        val end = t.indexOf(" live") + 5
                        line.setSpan(ForegroundColorSpan(Ui.LIVE_TEXT), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        line.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                tile.addView(Ui.text(a, line, 11.5f, Ui.TEXT_2).apply {
                    maxLines = 1
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dp(a, 6)
                    setPadding(0, Ui.dp(a, 7), 0, 0)
                })
                val err = SavedLists.lastError(l.id)
                val foot = when {
                    SavedLists.isBusy(l.id) && c != null -> "Reading…"
                    err != null -> "Couldn't read: $err"
                    c != null -> "Read ${ListText.ago(c.readAt, now)}"
                    else -> ""
                }
                tile.addView(Ui.text(a, foot, 10.5f, if (err != null && !SavedLists.isBusy(l.id)) Ui.WARN else Ui.TEXT_3).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, Ui.dp(a, 6), 0, 0)
                })
                val lp = ViewGroup.MarginLayoutParams(Ui.dp(a, 200), ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = Ui.dp(a, 15)
                lp.bottomMargin = Ui.dp(a, 15)
                row.addView(tile, lp)
                if (l.id == focusedId) focusTarget = tile
            }
        }
        val add = Ui.text(a, "Add a list from your phone", 13f, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER
            background = dashedFocusable(a)
            Ui.focusRing(this, 11f)
            setPadding(Ui.dp(a, 15), Ui.dp(a, 13), Ui.dp(a, 15), Ui.dp(a, 13))
            isFocusable = true
            isClickable = true
            tag = ADD_TAG
            minHeight = Ui.dp(a, 70)
            setOnClickListener { onAdd() }
        }
        row.addView(add, ViewGroup.MarginLayoutParams(Ui.dp(a, 200), Ui.dp(a, 70)).apply { bottomMargin = Ui.dp(a, 15) })
        if (addFocused) focusTarget = add
        if (focusedId != null && focusTarget == null) focusTarget = (0 until row.childCount).map { row.getChildAt(it) }.firstOrNull { it.isFocusable } // its list was removed
        focusTarget?.requestFocus()
    }

    /** "Remove this list?" dialog (Cancel focused). [after] runs once the list is removed. */
    fun confirmRemove(a: Activity, l: SavedList, after: () -> Unit) {
        val d = AlertDialog.Builder(a)
            .setTitle("Remove “${l.name}”?")
            .setMessage("This removes the saved stream list. The website is not affected.")
            .setPositiveButton("Remove") { _, _ ->
                SavedLists.remove(a, l.id)
                Toast.makeText(a, "Removed", Toast.LENGTH_SHORT).show()
                after()
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }
}
