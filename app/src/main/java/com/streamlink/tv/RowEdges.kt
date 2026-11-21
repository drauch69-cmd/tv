package com.streamlink.tv

/** D-pad at the ends of a home row: focus stays on the first/last item instead of leaving the row. */
object RowEdges {
    /** True when moving [left] (else right) from item [index] of [count] would leave the row. */
    fun blocked(index: Int, count: Int, left: Boolean): Boolean =
        count > 0 && index in 0 until count && (if (left) index == 0 else index == count - 1)
}
