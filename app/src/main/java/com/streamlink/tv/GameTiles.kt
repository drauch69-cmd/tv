package com.streamlink.tv

/**
 * The player menu's "other games" tiles (user, 2026-11-19): live games of the sport being watched, with scores,
 * in the top-right corner. A game with a stream in the saved lists can be picked to switch to it; its stream is
 * the one from the best-ranked list (folders are already built in rank order). Pure, unit-tested.
 */
data class GameTile(val game: Game, val stream: GameStream?, val hot: Boolean = false)

data class TileSet(val league: String, val tiles: List<GameTile>, val more: Int)

object GameTiles {
    /** Up to 6 game tiles, 2 columns (user, 2026-11-21); any further live games become a "+N more" tile. */
    const val MAX = 6

    /**
     * What is being watched: (league, game id or null). The game whose folder holds [itemUrl]; else a game the
     * [title] names (both teams, as on the list screens); else RedZone counts as NFL with no game. Null = not a game.
     */
    fun current(folders: List<SportFolders>, itemUrl: String?, title: String, games: List<Game>): Pair<String, String?>? {
        if (!itemUrl.isNullOrBlank()) {
            val key = GameFolders.urlKey(itemUrl)
            for (f in folders) for (g in f.games) if (g.streams.any { GameFolders.urlKey(it.item.url) == key }) return f.league to g.game.id
        }
        if (title.isNotBlank()) {
            ScoreMatch.find(title, null, games)?.let { return it.league to it.id }
            if (ChannelFilter.isRedZone(title)) return "nfl" to null
        }
        return null
    }

    /**
     * Tiles for [league], leaving out [currentId]: live games only; games with a stream first, then most exciting
     * ([rate]: FeaturedPick score, null = unrated, user 2026-11-21), then earliest start, then id. [hot] marks the
     * flame. Null when there is nothing to show (no other live game).
     */
    fun pick(
        folders: List<SportFolders>, league: String, currentId: String?, max: Int = MAX,
        rate: (GameFolder) -> Double? = { null }, hot: (GameFolder) -> Boolean = { false },
    ): TileSet? {
        val f = folders.firstOrNull { it.league == league } ?: return null
        val live = f.games.filter { it.game.state == "in" && it.game.id != currentId }
            .map { it to (rate(it) ?: Double.NEGATIVE_INFINITY) }
            .sortedWith(compareBy<Pair<GameFolder, Double>>({ if (it.first.streams.isEmpty()) 1 else 0 }, { -it.second },
                { it.first.game.startMillis }, { it.first.game.id }))
            .map { it.first }
        if (live.isEmpty()) return null
        val shown = live.take(max)
        return TileSet(league, shown.map { GameTile(it.game, it.streams.firstOrNull(), hot(it)) }, live.size - shown.size)
    }

    /** Top line: "BUF 17  KC 21" (away first, as ESPN lists them). */
    fun scoreLine(g: Game): String = "${tag(g.away)} ${pts(g.away)}  ${tag(g.home)} ${pts(g.home)}"

    /** Bottom line: "▶ Q3 4:12" when it can be switched to, else the clock alone. */
    fun statusLine(g: Game, switchable: Boolean): String {
        val s = ScoreText.rowLine(g)?.substringAfterLast(" · ")?.takeIf { it.isNotBlank() } ?: "Live"
        return if (switchable) "▶ $s" else s
    }

    /** Same tiles (games, scores, clocks, streams) as before: no redraw needed. */
    fun sameLook(a: TileSet?, b: TileSet?): Boolean {
        if (a == null || b == null) return a == b
        if (a.league != b.league || a.more != b.more || a.tiles.size != b.tiles.size) return false
        return a.tiles.indices.all { i ->
            val x = a.tiles[i]; val y = b.tiles[i]
            x.game.id == y.game.id && x.hot == y.hot && scoreLine(x.game) == scoreLine(y.game) &&
                statusLine(x.game, x.stream != null) == statusLine(y.game, y.stream != null) &&
                x.stream?.item?.url == y.stream?.item?.url
        }
    }

    private fun tag(s: TeamSide) = s.abbr.ifBlank { s.shortName }.ifBlank { s.displayName }.take(5)
    private fun pts(s: TeamSide) = s.score.ifBlank { "0" }
}
