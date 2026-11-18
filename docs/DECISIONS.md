# StreamLink — decisions and open items

Running log. Update it in the same session a decision is made or an item opens or closes.
Newest open items first. When an item closes, move it to "Decisions" (or delete it if it no longer matters).

## Open items

- **NBA tile crash + outlines + crash recorder** (Sports build after 191, 2026-11-18): NBA tile "crashed" every time
  (NFL/CFB fine; NBA lists hold the most streams). Suspected freeze (ANR): sport screen matched every stream on the
  main thread and ScoreMatch's single cache slot thrashed against the home model. Fixed: sport/streams screens compute
  off the main thread with a look check, ScoreMatch keeps 6 prepared game lists, stable per-league game views. Added the
  crash/freeze recorder ("Last exit:" / "Crash:" lines at the top of the report) and renderer-gone handling in all
  hidden browsers. Focus clipping attempt (no-clip containers, FocusScroll, lifted/scaled card) was reverted: it looked worse. New focus style (user approved): same size, brighter fill, accent ring drawn as a foreground stroke inside the view's bounds (`Ui.focusCard`). Not yet tested on the TV.
  Untested on the TV: next check the NBA tile; if it still fails, the report's "Last exit" lines say why.
- **No problems since build 188** (user, 2026-11-18 02:55): no stalls or crashes reported after the home speed fix, so
  the performance review batches are now preventive, not urgent.
- **"Your lists" tab + list leftovers** (Sports build 191, 2026-11-18): lists moved off home into a top-bar tab next to
  "Send a link" (new lists screen); old learned list shapes forgotten (v2 key), learned rows must pass the fresh-read
  rules, a page that loads with no events now replaces the old list. Untested on the TV; check TheTVApp NFL/CFB.
- **Performance review** (2026-11-18, 5 read-only reviewers, code reading only). Ranked fix list offered, not approved:
  - Batch A (crashes/stalls): (1) hidden WebViews have no render-process-gone handler in StreamSniffer and
    SitePlayerActivity, so a renderer killed for memory takes the app down; (2) the home list reader keeps reading
    behind the player and the phone can start reads mid-video: pause list reads while playing; (3) the background
    "extra sources" run loads a full page with media/ads during playback: block media/images, start only with a
    healthy buffer, or make it manual; (4) hidden finder/reader runs load images/fonts/media and the reader takes a
    picture + 1.5 MB DOM snapshot after every read: block those, snapshot only on failure; (5) player buffer has no
    byte cap (60 s max, ~45-75 MB) and STALL_MS 20 s can restart a slow-but-alive stream: cap ~40 MB, recover only when
    no bytes arrive.
  - Batch B (menus): sport screen matching on the main thread and minute-tick full rebuilds on all screens; ScoreMatch
    recomputes team names per item and its cache thrashes; phone page polls every 1.5 s with a thread per request;
    sport screen re-reads lists every 2 min.
  - Batch C (build): release build not minified/shrunk (R8 off); list caches stored in the main SharedPreferences file;
    small items (verifier thread pool per run, per-card drawables, dead resources, onBackPressed, dependency bumps).
- **Home speed fix confirmed** (Sports build 188, 2026-11-18): the user says the app "feels much better now". Still to
  watch: video stalls while watching and occasional crashes (crash/freeze recorder offered, not approved yet).
- **TV report 2026-11-18 01:45 (Sports build 184):** TheTVApp and Roxie streams play on the TV. Reed was down for everyone;
  Buffstreams and StreamEast had nothing live to test. The app is "very slow, stalls often, crashes occasionally".
  Suspect (unverified): since 184 the home screen recomputes every 5 s on the main thread (reads all cached lists,
  matches every item against 3 days of ESPN games) on top of the Sports rows' own refresh. Offered a fix; waiting.
  Built on branch `agent/speed` (2026-11-18): one score refresher on home, list caches kept parsed in memory, home rows
  worked out on a background thread. Not yet tested on the TV.
- **Repeating requests as possible playlists** (2026-11-18, Sports app): a sub-resource address requested again
  1.5–20 s later is checked by content like a disguised playlist (own budget of 6 per run). Aimed at CrackStreams'
  `/…/application5` playlist. Not tested on the TV; next: the user opens a CrackStreams game and sends the report
  (look for "A repeating request may be a playlist").
- **The user runs StreamLink Sports** (build 174 on 2026-11-18), not StreamLink Test. Work aimed at the user's TV goes to
  `sports` (merge `test` into it; never the other way without the user's OK). `test` was merged into `sports` on
  2026-11-18 (list reader fixes, stale markers, countdowns, ESPN-driven status).
- **Sports folders** (2026-11-17, branch `sports` only): built (sport tags on the phone page, 3-day ESPN schedule,
  game matching, home Sports + Live games rows, 24/7 tile, sport screen with "All games", game/24/7 streams screen).
  Separate app "StreamLink Sports" (`com.streamlink.tv.sports`, release `sports-latest`) so `test` and `main` stay
  untouched; fold back into `test` only if the user says it works. Fixes on `test` do not reach `sports` by
  themselves. **Nothing tested on the TV yet.** To check there: games matched to the right streams on a real slate;
  D-pad order on home (Live games above Live now, Sports row below it); focus kept while rows refresh; the phone
  page's sport pickers. Known rough edges: the phone's per-list Refresh doesn't reach the sport screen; home and the
  sport screen can read the same list at once (harmless); a game that leaves the schedule leaves its screen with
  nothing to focus (Back works). Later, not now: Source panel offering the same game's streams from other pages.
- **Pull streams from the six index sites** (user, 2026-11-18). Inspected one live game on each in the desktop browser
  (not on the TV); plan offered, waiting for "go":
  - TheTVApp and Buffstreams: same player provider; disguised playlist `/playlist/<id>/load-playlist` (already caught
    since build 160), segments are MPEG-TS named .txt; the player needs a second tap (first tap opens an ad popup).
  - Reed: player two frames deep; default source is a plain .m3u8 (480p); 9 source buttons.
  - Roxie: Clappr player right in the page, plain .m3u8 on rotating domains, autoplays; the page loads the
    "disable-devtool" library (it closed the desktop browser pane), which might misfire in the TV's hidden browser.
  - CrackStreams: player page only loads with CrackStreams as referer (fine inside the page); its playlist address
    has no playlist word (`/ziangel6/application5`, served as application/vnd.apple.mpegurl, refetched every ~6 s), so
    the finder likely misses it. Proposed: count a request that repeats every few seconds as a possible playlist
    (confirmed by content as today).
  - StreamEast: a console-timing devtools trap sends the page home in the desktop browser (should not fire on the
    TV); the player frame is named "ClearKeyEmbed" (possibly ClearKey-encrypted video); could not inspect further.
- **List reader fixes for those six sites** (2026-11-18): built (titles from name pairs / overlay links, league labels
  and emoji skipped, table rows, prose links not games, countdowns, sectioned cards, content-visibility rows). Checked
  against the live pages in the desktop browser at 1280x720 (all six read correctly); not tested on the TV.
  Known leftovers: Buffstreams' machine time has no zone (it is UTC) and is read in the TV's zone (6 h off; ESPN fixes
  matched games); live cards that show only a game clock (Reed college games) get no start time.
- **Roxie football: "Live" next to a "Final" score** (Sports build 174, reported 2026-11-17): cause found 2026-11-18.
  The user runs the StreamLink Sports app; `sports` branched before ESPN-driven status existed, so it showed ESPN's
  score but took Live from the site's stuck marker. Fixed by merging `test` into `sports` (ESPN-driven status, now
  scoped to the list's sport tags on the list screen and phone page) plus the stale-marker rule. Untested on the TV.
- **List reading speed** (offered 2026-11-17): quick read of the page's plain text first, stop waiting once the list
  stops changing, show the last list instantly. To do after the fixes; confirm with the user before starting.

- **Empty player box pages** (2026-11-17): built (source-switch.js anchor, hidden search presses source 1 then 2, site player
  presses source 1; chat-like narrow frames ignored). Not compiled or tested on the TV. Next: the user opens such a page and
  sends the report; look for "pressing source button" lines.
- **Live scores on the list screen** (2026-11-17): built (NFL + college football + NBA from ESPN). Untested on the TV.
  Next: the user opens a list during a game; if a game should match and doesn't (or matches wrongly), ask for the
  item's title as shown and the "Scores:" report lines. Not checked yet against the live API: the college
  `groups=80` setting and ESPN's live clock wording.
- **ESPN-driven Live status** (2026-11-17): built (list, home row, phone page). Untested on the TV. Check during
  games: a final game moves to the bottom as "Final"; a long game stays Live past 4 h; no focus jumps on the home
  screen or the list when scores update.
- **Slow fallback on sites the finder can't catch** (e.g. a list event with no start time on a site with 5 misses
  in a row: site player only at 36 s, two searches plus a 15 s wait). Offered, not approved: treat "Live" in the
  list as started (fall back after the first check), and fall back right after the first search on sites with 3+
  misses in a row. Next: read the site player's new lines in the user's report to see if the stream is catchable.
- **Links 2–4 on the test site** (build 156 report): the player's own tokened playlist request never comes in the
  TV's hidden browser (a Cloudflare check frame sits in those players; likely fails silently), so the copy found in
  a tracking ping (no access code) gets 403. Not fixable cleanly; since build 158 those links stay in the Source list
  as "site player" entries (the link page alone plays in the site player on the TV, confirmed). Build 158's Source
  list and Back behaviour still untested on the TV.
- **Waiting on the user's OK:** releasing `test` to `main`; moving the signing key to GitHub secrets; pruning fixes
  never proven to help; restyling the player's loading screen.
- **Offered, not approved:** automatic retry of failed list reads after about 15 minutes.

Reminder: lay out options and get a "go" before starting any of these.

## Decisions (do not undo without asking)

- **Several sport tags per list** (2026-11-17, `sports` branch): some index pages mix sports, so a list carries
  any set of NFL / College football / NBA (toggle chips on the phone page; old single tags carried over). An item
  is matched once across all the list's sports (one best game wins, never two sports); unmatched items show in
  "Other" of every sport the list carries. Untested on the TV and on the phone.
- **NBA as a third sport** (2026-11-17, `sports` branch): same ESPN feed scheme, matching rules (shared-city rule
  covers Lakers/Clippers), tag, tile, sport screen and live-games row as football. A list tagged with a sport
  matches scores only against that sport; untagged lists match all. Untested on the TV; ESPN's NBA status wording
  not checked against a live game yet.
- **Sports folders plan** (2026-11-17, `sports` branch): saved lists get a sport tag (NFL, College football, or
  none; set on the phone page when adding, changeable later). ESPN schedule for today plus 2 days. Each stream from
  every tagged list is matched to a game (same both-teams rule as live scores); a game lists all its streams from
  all pages, labeled by page, exact repeats once; unmatched go to "Other". Home: NFL and College football tiles, a
  live-games row (sample of live games with scores; replaces matched games in "Live now"), and a 24/7 tile
  (always-on channels from all lists). Sport screen: Live now / Today / Tomorrow / day after / Other; shows only
  games with streams by default, with an "All games" toggle. Game screen: score card plus stream options. Opening a
  sport re-reads its pages (cached results first). Saved lists and the phone link stay as they are. Later, not
  now: the player's Source panel offering the same game's streams from other pages.
- **Stale site live markers** (2026-11-18): a site's own LIVE marker counts only until 4 h after the listed start
  (Roxie never takes it down); with no start time it is trusted. ESPN still overrides for matched games.
- **Countdowns** (2026-11-18): read "Starts in 1d 19h"-style countdowns as a backup start time; ESPN's kickoff wins
  when the game matches.
- **Live scores** (2026-11-17): NFL, college football and NBA (NBA added 2026-11-17 at the user's OK); scores shown by default (no spoiler hiding); on the
  list rows (short line) and the details card (full block); no team logos for now. A game matches only when both
  teams are named; ties broken by start time, else nothing shown.
- **ESPN decides Live/Final** for matched games, on every screen (list, home "Live now", phone page), over the
  site's own live marker (2026-11-17). Unmatched items keep the start-time rules.
- **Home screen:** TV-home-style dashboard, plus all the other mockup changes (list, player overlay, loading
  screen, phone page).
- **Audio sync adjustment:** built, then reverted at the user's request as clunky. The real cause was a TV audio
  setting on the user's side. Do not bring it back.
- **No early give-up on other sites' players** (2026-11-17): build 153 stopped the search 8 s after tapping a
  player from another site; most streams then fell to the site player, since those players often need 10–15 s.
  Removed at the user's request; site miss counts were reset (new storage key). Do not re-add without asking.
- **Disguised playlist addresses**  (no m3u8/.mpd in them, e.g. `/playlist/123/load-playlist`) are caught by path
  words (playlist, manifest, chunklist, master, index, list as whole words; a trailing number counts) and confirmed by content (#EXTM3U / <MPD)
  before they count as a stream (2026-11-17). Confirmed on the TV in build 160 (a "list2" playlist plays clean).
- **Channel surfing:** removed. Up/Down open the bottom menu, like OK.
- **Sources** have their own button and panel (not under Audio). **Pause** is the first button.
- **"Jump to live"** is styled like the other buttons, not highlighted. The list's Play button stays highlighted.
- **Extra sources:** gathered in the background while watching.
- **Link sources that don't verify** stay in the Source list as "<label> · site player" and open as their own page
  (clean player if a stream is found, else the site player); Back returns to the stream you were on (2026-11-17).
  Untested on the TV.
- **Origin header:** always derived from the referer being tried, not from what was captured.
- **Tunneling:** auto-detect and switch off, not a manual setting only.
- **Agent docs:** `CLAUDE.md` + this file in the repo are the source of truth (2026-11-17). The user's Claude Doc
  "StreamLink — Agent Onboarding Guide" is a summary for the user only, not maintained by agents.
