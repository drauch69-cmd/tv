package com.streamlink.tv

/** Pure helper: where late numbered addresses go in a Source list. */
object SourceMerge {
    /**
     * Adds [extras] (open-page entries read after the player started) not already present (same address): they go
     * right after the playable sources, before the first open-page entry (so before "other site" entries), else last.
     */
    fun insertExtras(sources: List<Source>, extras: List<Source>): List<Source> {
        val known = sources.map { it.url }.toSet()
        val fresh = extras.filter { it.url !in known }.distinctBy { it.url }
        if (fresh.isEmpty()) return sources
        val at = sources.indexOfFirst { it.openPage }.let { if (it < 0) sources.size else it }
        return sources.subList(0, at) + fresh + sources.subList(at, sources.size)
    }
}
