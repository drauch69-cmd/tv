package com.streamlink.tv

/**
 * "Demo games" (user, 2026-11-20): made-up games in real stadium cities, switched on from the phone page, so the
 * home screen (featured card, city photos, Live games, scrolling) can be tried when nothing is on. Home screen
 * only. Off again when the app restarts. They have no streams: Watch ends on the "No working stream" screen.
 * Pure (no Android), unit tested.
 */
object DemoGames {
    @Volatile var on: Boolean = false

    const val LIST_ID = "demo-games"
    private const val LOGO = "https://a.espncdn.com/i/teamlogos"

    private fun team(name: String, short: String, abbr: String, loc: String, nick: String, score: String, record: String, logo: String) =
        TeamSide(name, short, abbr, loc, nick, score, record, logo)

    private data class Spec(
        val league: String, val away: TeamSide, val home: TeamSide, val startMin: Int, val live: Boolean,
        val detail: String, val period: Int, val clock: String, val city: String, val state: String, val country: String,
        val winProb: Double?,
    )

    private fun specs(): List<Spec> = listOf(
        Spec("nfl",
            team("Green Bay Packers", "Packers", "GB", "Green Bay", "Packers", "17", "7-3", "$LOGO/nfl/500/gb.png"),
            team("Chicago Bears", "Bears", "CHI", "Chicago", "Bears", "20", "6-4", "$LOGO/nfl/500/chi.png"),
            -120, true, "8:42 - 4th", 4, "8:42", "Chicago", "IL", "USA", 0.58),
        Spec("nfl",
            team("Jacksonville Jaguars", "Jaguars", "JAX", "Jacksonville", "Jaguars", "10", "4-6", "$LOGO/nfl/500/jax.png"),
            team("Buffalo Bills", "Bills", "BUF", "Buffalo", "Bills", "13", "8-2", "$LOGO/nfl/500/buf.png"),
            -60, true, "2:05 - 2nd", 2, "2:05", "London", "", "England", 0.7),
        Spec("nfl",
            team("Dallas Cowboys", "Cowboys", "DAL", "Dallas", "Cowboys", "24", "5-5", "$LOGO/nfl/500/dal.png"),
            team("Philadelphia Eagles", "Eagles", "PHI", "Philadelphia", "Eagles", "21", "8-2", "$LOGO/nfl/500/phi.png"),
            -100, true, "11:10 - 3rd", 3, "11:10", "Philadelphia", "PA", "USA", 0.45),
        Spec("cfb",
            team("Boise State Broncos", "Boise State", "BSU", "Boise State", "Broncos", "28", "9-1", "$LOGO/ncaa/500/68.png"),
            team("Wyoming Cowboys", "Wyoming", "WYO", "Wyoming", "Cowboys", "24", "5-5", "$LOGO/ncaa/500/2751.png"),
            -110, true, "6:30 - 4th", 4, "6:30", "Laramie", "WY", "USA", 0.4),
        Spec("nba",
            team("Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers", "88", "10-5", "$LOGO/nba/500/lal.png"),
            team("Boston Celtics", "Celtics", "BOS", "Boston", "Celtics", "91", "11-4", "$LOGO/nba/500/bos.png"),
            -80, true, "4:12 - 4th", 4, "4:12", "Boston", "MA", "USA", 0.62),
        Spec("nfl",
            team("Kansas City Chiefs", "Chiefs", "KC", "Kansas City", "Chiefs", "", "9-1", "$LOGO/nfl/500/kc.png"),
            team("New England Patriots", "Patriots", "NE", "New England", "Patriots", "", "3-7", "$LOGO/nfl/500/ne.png"),
            150, false, "", 0, "", "Foxborough", "MA", "USA", null),
        Spec("cbb",
            team("Duke Blue Devils", "Duke", "DUKE", "Duke", "Blue Devils", "", "5-0", "$LOGO/ncaa/500/150.png"),
            team("North Carolina Tar Heels", "North Carolina", "UNC", "North Carolina", "Tar Heels", "", "4-1", "$LOGO/ncaa/500/153.png"),
            200, false, "", 0, "", "Chapel Hill", "NC", "USA", null),
    )

    /** The demo games, timed around [now]. */
    fun games(now: Long): List<Game> = specs().mapIndexed { i, s ->
        val start = now + s.startMin * 60_000L
        Game(
            id = "demo-$i", league = s.league, startMillis = start, state = if (s.live) "in" else "pre",
            shortDetail = s.detail, period = s.period, clock = s.clock,
            home = s.home, away = s.away, broadcast = "DEMO",
            statusName = if (s.live) "STATUS_IN_PROGRESS" else "STATUS_SCHEDULED",
            homeWinProb = s.winProb, networks = listOf("DEMO"),
            venueCity = s.city, venueState = s.state, venueCountry = s.country,
        )
    }

    /** A made-up stream list carrying one event per demo game, so the home rows show them like real ones. */
    fun list(): SavedList = SavedList(LIST_ID, "Demo games", "https://demo.invalid/", Sports.ALL.toSet())

    /** What the demo list "read" (as if it had just been read at [now]). */
    fun cached(games: List<Game>, now: Long): CachedList =
        CachedList(ListResult("https://demo.invalid/", "Demo games", items(games), "automatic", null), now)

    fun items(games: List<Game>): List<ListItem> = games.map { g ->
        ListItem(
            title = "${g.away.displayName} vs ${g.home.displayName}", subtitle = "Demo",
            url = "https://demo.invalid/${g.id}", startMillis = g.startMillis, timeText = "",
            section = "", liveHint = g.state == "in", imageUrl = null,
        )
    }
}
