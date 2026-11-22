package com.streamlink.tv

import android.view.View
import android.view.ViewGroup

/** D-pad at the ends of a home row: focus stays on the first/last item instead of leaving the row. */
object RowEdges {
    /** True when moving [left] (else right) from item [index] of [count] would leave the row. */
    fun blocked(index: Int, count: Int, left: Boolean): Boolean =
        count > 0 && index in 0 until count && (if (left) index == 0 else index == count - 1)

    /** Where focus should land after a rebuild when the focused row is gone: the same slot, or the last row if the list shrank. */
    fun nearest(oldIndex: Int, count: Int): Int = if (count <= 0 || oldIndex < 0) -1 else minOf(oldIndex, count - 1)

    /** Position of [v] among the focusable direct children of [rows] (section labels do not count), or -1. */
    fun slotOf(rows: ViewGroup, v: View?): Int =
        (0 until rows.childCount).map { rows.getChildAt(it) }.filter { it.isFocusable }.indexOf(v)

    /** The focusable child of [rows] nearest to old slot [slot] (see [nearest]), or null. */
    fun rowAtSlot(rows: ViewGroup, slot: Int): View? {
        val f = (0 until rows.childCount).map { rows.getChildAt(it) }.filter { it.isFocusable }
        return nearest(slot, f.size).let { if (it < 0) null else f[it] }
    }
}
