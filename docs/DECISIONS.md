# StreamLink — decisions and open items

Running log. Update it in the same session a decision is made or an item opens or closes.
Newest open items first. When an item closes, move it to "Decisions" (or delete it if it no longer matters).

## Open items

- **The user runs StreamLink Sports** (2026-11-18), not StreamLink Test. Tonight's `test` work was merged into `sports`
  (Sports build 184), which also fixed Roxie's "Live" next to a "Final" score (`sports` predated ESPN-driven status).
  Work aimed at the user's TV goes to `sports`.
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
