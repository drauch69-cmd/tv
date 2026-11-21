package com.streamlink.tv

/** Changing a saved list's address (phone page "Address" button). Pure; SavedLists.setUrl saves the result. */
object ListAddress {
    /** The outcome: the new lists (null when refused) and, when refused, why. */
    data class Change(val lists: List<SavedList>?, val problem: String?)

    /**
     * Gives list [id] the address [url]; name, sports and rank stay. A name that was just the old site's name
     * follows the new site ([hostOf]). Refused: no such list, or another list already has that address.
     * The same address is not a change (same object back).
     */
    fun change(lists: List<SavedList>, id: String, url: String, hostOf: (String) -> String): Change {
        val l = lists.firstOrNull { it.id == id } ?: return Change(null, "No such list")
        if (l.url == url) return Change(lists, null)
        if (lists.any { it.id != id && it.url == url }) return Change(null, "Another list already uses that address")
        val name = if (l.name == hostOf(l.url)) hostOf(url) else l.name
        return Change(lists.map { if (it.id == id) it.copy(url = url, name = name) else it }, null)
    }
}
