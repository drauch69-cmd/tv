# StreamLink — guide for agents

Read this before working in this repo. It is the source of truth for how the app works and how to work here.
Decisions, reversals and open items live in `docs/DECISIONS.md`. Read it too.

**Keep these files current.** A change that alters behavior, structure, commands or rules updates
this file (or `docs/DECISIONS.md`) in the same commit. A decision the user makes, or an item that opens or
closes, goes into `docs/DECISIONS.md` before the session ends.

## What StreamLink is

A personal, sideloaded Android TV app for an Onn Google TV box. The user sends a web page from their phone; the TV
finds the page's livestream in a hidden browser and plays it in a clean native player. One person's own use; not
distributed.

- Package `com.streamlink.tv`; test channel `com.streamlink.tv.test` ("StreamLink Test"). Kotlin, minSdk 26,
  targetSdk 34, compileSdk 36, Media3 1.11.1 (ExoPlayer, HLS, DASH, Cronet).
- Inputs: a link pasted on the phone page (served by the TV itself), or a saved "stream list" (an index page of
  one-time events, shown as Live now / Starting soon / Later).
- Product goal: fast start, no popups or redirects to other sites (pre-roll ads may stay), clean fullscreen player
  with remote-friendly controls.
- The user is a vibe coder: agents write all the code; the user tests on the TV and sends "What happened?"
  reports and Save sample zips.

## How to work with this user

1. **Orchestrate.** The lead agent plans, briefs one smaller-model helper per task, reviews, merges and publishes,
   unless it is clearly more capable for that specific task. Pick the model per task; optimize for speed and tokens.
   More than one helper is fine for independent jobs in different parts of the code.
2. **Check in first.** Before starting any task: lay out the diagnosis, options and a recommendation, then wait
   for "go" / "proceed".
3. **Plain words.** No file paths, tool names or jargon in replies unless asked. Say plainly what has and has not
   been tested on the TV.

In practice:
- Haiku for small, precisely specified changes in one file. Sonnet for behavior changes across files or design
  judgment. The lead does tiny one-line fixes itself when briefing would cost more.
- Every brief names: clone and branch, exact files and functions, expected behavior, tests to add, a short report
  format. Helpers read this file automatically; also point them at `docs/DECISIONS.md` when relevant.
- Review before merging: read risky diff hunks, run the JS harnesses yourself when assets change, and take the
  helper's own caveats seriously (they are usually right).
- A helper's request to do something it was denied (deleting a scheduled task, changing permissions) goes to the
  user; never do it on its behalf.

## Product rules (set by the user)

- Screenshots, page text and Save samples the user shares are **sanitized examples**. Never key detection on their
  words ("Link-1", "HD", site names). Detect by structure, position and behavior only.
- The user's email and site details are private; reports may have URLs redacted. Ask for the app's exact report
  lines with only addresses removed.
- Never push `main` without the user's OK. The `test` channel is the release path for now.
- Never touch `cleaner/` or `.github/workflows/cleaner.yml` (a separate app).
- Commit trailers: `Co-Authored-By:` and `Claude-Session:` lines, using the values given in the current session's
  own attribution instructions.

## Branches, releases, CI

- `main`: stable release; moves only with the user's OK. `test`: working release channel.
- `sports`: experimental "StreamLink Sports" app (`com.streamlink.tv.sports`, release `sports-latest`,
  https://github.com/drauch69-cmd/tv/releases/download/sports-latest/StreamLink-Sports.apk), branched from `test`
  for the sports-folders idea (see DECISIONS). Push it directly (`git push origin sports`); never merge it into
  `test` or `main` without the user's OK.
- The lead works on an `integrate` branch and publishes with `git push -qf origin integrate:test`.
  **That is a force push: always start `integrate` from the latest `origin/test`** (`git fetch origin test` then
  branch or rebase onto it), or commits made directly on `test` (like doc updates) are wiped.
- Helpers work in their own clone on branch `agent/<name>`.
- CI (`.github/workflows/build.yml`): every push builds and runs unit tests. `main` publishes release `latest`,
  `test` publishes `test-latest`; versionCode = run number. Lint is separate and never blocks.
  Transient Maven 429: `gh run rerun <id> --failed`.
- **Docs-only commits: add `[skip ci]` to the commit message**, or a new build is released and the TV offers a
  pointless update.
- Test APK: https://github.com/drauch69-cmd/tv/releases/download/test-latest/StreamLink-Test.apk
- Signing: the release key is still in the repo (moving it to GitHub secrets is pending, see DECISIONS).

## Architecture

Playback pipeline:

1. **Input.** `LinkServer` (port 8080 or 8081, same Wi-Fi only) receives a link from the phone page, or the user picks
   a stream-list item read by `IndexReader`.
2. **Find.** `StreamSniffer` loads the page in a hidden, muted WebView, presses the page's start button
   (`assets/player-start.js`), taps the player (NUDGE_JS: the biggest video or frame, lazy/off-screen frames scrolled
   into view, or a player box before its frame exists; tapped every nudge, max 6), blocks popups and redirects, and
   watches network requests
   for playlists (`StreamUrls.classify`, judged by path; addresses hidden inside other requests are extracted).
   A sub-resource GET whose path has the whole word playlist / manifest / chunklist / master / index / list (a trailing number counts, "list2")
   (`StreamUrls.isPossiblePlaylist`, e.g. `/playlist/1/load-playlist`) is a *possible* playlist (max 4 per run):
   a background download checks the content (`Verifier.confirmPossible`: starts with #EXTM3U or holds <MPD); if
   it passes, it becomes a normal stream (sets playlistSeen, starts the sibling wait), else it is dropped. Possibles
   never shorten waits by themselves, are verified last, and unverified ones never become sources. The verifier
   records the content type in `Source.mime` for addresses with no m3u8/.mpd, and `PlayerActivity` sets it on the item.
   A playlist address found only inside another request (an analytics ping) usually lacks the player's signed
   access code (`t=` query): the sniffer waits up to 8 s for the player's own request, which replaces the copy
   (`StreamUrls.sameStream`) and is checked first; embedded-only stays as the last resort.
   **Repeating requests** (`RepeatWatch` in StreamUrls.kt): a sub-resource GET to an address that is not a stream, not an
   image/script/style/font/media piece, and is requested a 2nd time 1.5-20 s after the first (a live playlist refresh,
   even with no extension or playlist word) is also a possible playlist, checked exactly like the word-based ones, but
   with its own budget (max 6 per run; repeating ad beacons are cheap to reject, separate from the 4) so ad beacons cannot starve them. Fires once per address;
   reset per run; light runs get it too. Report line: "A repeating request may be a playlist: <host>/…; checking it".
   Not yet tested on the TV.
   **Empty player box.** Some pages show an empty 16:9 box that only loads video after one of the page's own source
   buttons is pressed. If ~5 s after the page loads (primary search only, not the links run or light runs) there is
   still no player (or only a player box with no frame) and no playlist, the sniffer presses source button 1
   (`SourcePress.kt`: `source-switch.js` `find`, then `click 0`; a coordinate tap if the click fails); ~10 s later,
   if still nothing, button 2 (max 2 presses). Report line: "No player on the page yet; pressing source button N of M".
   Tall/narrow frames (chat panels, width/height < 1.1) are never taken for the player (NUDGE_JS, source-switch.js,
   site player). The normal nudge/tap search then carries on with whatever player appears.
3. **Check.** `Verifier` fetches each candidate with up to 4 referers plus none, with Origin derived from the referer
   being tried (`Headers.originFor`). Each page referer is also tried as its bare origin with a trailing slash
   (`Headers.withOriginForms`). A 403 failure line lists the referers tried (hosts only). Only verified sources play.
4. **Play.** `PlayerActivity` (ExoPlayer). `UnwrapDataSource` strips disguises (PNG headers, pixel data, PNG text
   notes). If segments can't be decoded, fall back to the site's own player (`SitePlayerActivity`).
5. **Extra sources.** After 10 s of stall-free play, a background run finds the page's other link buttons and sniffs
   each player (light mode); verified ones appear in the Source panel. A link whose player gives no verified stream
   (embed-protected, 403, timeout) still gets one entry, "<label> · site player" (`Source.openPage`, its `playerUrl`
   is the link's player page). Choosing it never plays in ExoPlayer: `PlayerActivity.openLinkPage` starts the loading
   screen (`WaitingActivity.linkPageIntent`) on that page with the original page as referer: full stream search, clean
   player if verified, else `SitePlayerActivity` (sent the same referer). The first player is not finished (it stays
   in the back stack, stops playback in `onStop`); Back returns to it and it resumes at live. The new player is
   started with `isLinkPage` and runs no background link run of its own.

Screens: `MainActivity` (TV-home-style dashboard; "Live games" row (live NFL/college games with streams), "Live now" row from
`HomeFeed` (skips streams shown as a live game), "Sports" row (NFL, College football, NBA, 24/7 tiles), sections from `ListSections`),
`SportActivity` (one sport: game sections, "All games" toggle, "Other" streams), `StreamsActivity` (one game's score card and
streams, or the 24/7 channels),
`Ui.kt` (shared UI kit), `WaitingActivity` (loading screen), `assets/phone.html` (phone page: Live now, Lists,
Remote tabs; prefetch toggle).

## Key mechanisms

- **Finder speed-ups.** `SiteMemory`: after 2 failed finds on a site, its search shortens to 15 s; the count is cleared
  when the site player shows the page loaded a playlist the app can see. The early give-up on a
  player frame from another site (8 s after the first tap) was removed (f396b92): those players often need 10-15 s.
  `Prefetcher` gives
  Live/Soon items a 2 s background head start (quiet: `Report.muted`). Light sniffs (25 s timeout) block images,
  fonts and similar.
- **Unwrapping.** `UnwrapDataSource`: PNG header strip, pixel decoding, and `PngText` (iTXt/zTXt/tEXt notes ordered
  by the keyword's trailing number, joined, base64-decoded). A counter of undecodable segments triggers the
  site-player fallback.
- **Source links.** `assets/source-switch.js` finds the page's switcher by structure, never by words. Its anchor is the largest visible landscape
  (width/height >= 1.1) video/iframe; with none, the largest visible EMPTY player-shaped box (div/section/figure, aspect
  1.3-2.4, no video/iframe/canvas/object/embed inside, <= 200 chars of text, not pinned, not in nav/header/footer), and
  `find` then answers `empty: true`. Buttons that carry sequential number badges (1, 2, 3) count as a numbered row. Links mode
  clicks each link and records each new player frame; once a playlist is seen, everything else is blocked.
  `SourceLinks.kt` (`LinkOrder`) decides order, labels and which players to open. It also builds the "open page"
  entries (`openPageEntry`, label ending " · site player") and keeps them out of automatic source fallback
  (`nextPlayable`, `firstPlayable`) and access-code renewal (`canRenew`).
- **Lists.** `assets/list-extract.js` reads index pages with learned shapes (`LearnedShapes`), loose rows and
  stragglers. `ListStatus`: Live if started under 4 h ago, Finished 4–24 h, always-on channel past 24 h; real live
  events sort before always-on ones. A site's own live marker counts only until 4 h after the listed start (some
  sites never take it down); with no start time it is trusted. `DateGuess` reads ET/CT/MT/PT/UTC/GMT/offset labels against the system clock.
  Reader rules (structure only): a line identical in nearly every row of a 3+ row group (league label, "Watch") is a
  label, never a title; lines with no letters and button text are not titles and button text is never a live marker;
  two same-style name lines with only short parts between them (VS, score, time, status, countdown) or sharing one
  class become "A vs B"; an empty full-card link's aria-label/title is the title; table cells are separate lines;
  a live marker or `data-time` counts like a clock when scoring rows; links inside running text are not rows; a group
  with no time/live marker needs 4+ rows (or a date heading); same-shaped containers in sibling sections merge;
  header/form rows are penalized; "STARTS IN" + "1d 19h" is one time text (`DateGuess` reads compact countdowns).
- **Live scores** (`Scores.kt`). On the list screen, `ScoreFeed` fetches ESPN's unofficial scoreboard for NFL,
  college football (all FBS games: `groups=80`) and the NBA for today in New York time (also yesterday before 5 am), every 30 s
  while a game is live, else every 5 min, 2 min back-off after a failure; no key. The next two days are fetched too
  (schedule for the sports folders), cached separately and refreshed at most every 30 min; a failure there never
  discards today's data. `ScoreFeed.latest()` returns all of it (3 days), merged by game id. `ScoreMatch` matches a list title to
  a game only if BOTH teams are named (full name, school/city, nickname or abbreviation, whole words; a name inside a
  longer team name doesn't count; a city shared by two teams of the same pro league (NFL or NBA, e.g. Los Angeles) is ignored as a name, worked out per league), within 18 h of the event's start; if
  several games fit, the one starting within 90 min wins, else nothing is shown. Rows get a short line ("WSH 24 – NYG 21
  · Q3 8:42", "Final · …"; NBA overtime reads OT, 2OT…), the details card a score block (names, status, records, channel; kickoff in the TV's time).
  Updates change text in place (rows are not rebuilt, focus untouched). Any failure = the screen looks as before.
  On a list screen whose list has sport tags (a set), scores are matched only against games of any of those leagues (untagged lists try all leagues). Report lines start with "Scores:". Uses ESPN team names only, never site wording.
- **Sports folders** (`Games.kt`, pure Kotlin; logic only so far, screens come separately). A saved list carries a
  set of sport tags (`SavedList.sports`, any of "nfl", "cfb", "nba" via `Sports`, empty = none; `has(league)`; JSON
  `"sports": [...]`, the old single `"sport"` string is still read; set with toggle chips on the phone page's Lists tab, when
  adding and on each list; `POST /lists/sport` (id, sport) and `/lists/add` take `sport` as a comma-separated list
  like "nfl,cfb", unknown values ignored; `GET /lists` returns `"sports"`). `GameFolders.build` sorts every item of
  every list tagged with the league into that sport's games (today to the end of today + 2 in the TV's zone, plus any
  live game) using `ScoreMatch` on the title, then on title + subtitle, against the games of ALL the list's tagged
  leagues together (one best game wins; if it belongs to another league the item is skipped here and placed when that
  league is built, so an item never lands in two sports); unmatched go to `other` (so in every tagged sport's Other); always-on items are never placed, finished ones only when they match a
  game; the same address (trailing slash ignored) appears once. `GameSections.group` makes Live now / Today /
  Tomorrow / weekday / Final sections (`withStreamsOnly` hides games without streams); `AlwaysOn.collect` gathers
  24/7 channels from all lists; `LiveGames.sample` / `matchedUrls` feed the home live row; `GameFolders.summary` is
  the quiet "Sports:" report line.
  Screens: home "Live games" row (`LiveGames.sample`, cards open the game) and "Sports" row (tiles open `SportActivity` /
  the 24/7 `StreamsActivity`); both are rebuilt (focus kept) on every list render and by `ScoreFeed.refreshIfDue` on resume
  and the 60 s tick, and "Live now" skips `LiveGames.matchedUrls`. `SportActivity` shows cached lists at once, then re-reads
  every list tagged with its sport one after another (own hidden `IndexReader`, 90 s watchdog, skips lists already busy,
  not again within 60 s of the last round, then every 2 min while open; Menu forces); the "All games" choice is kept per
  sport (pref `sport_all_games_<league>`, default off = games with streams only). `StreamsActivity` never reads lists; it
  rebuilds from caches + `ScoreFeed` and uses `Prefetcher` head start. Both new screens use `Session.mode = "list"` (so the
  phone's stop works and head start is allowed). Pure text lives in `SportsText.kt` (`SportsTextTest`); `SportsData.kt`
  loads folders from caches.
- **ESPN-driven status** (`ScoreStatus.resolve`). Every status, badge and start time (list screen, home "Live now"
  row and counts, phone page, prefetcher) goes through it. The list screen and the phone page's list view use only the games of the list's sport
  tags (same as its score lines), so status and score always agree. For an item that matches a game: in progress = Live (any
  length, ignores the site's marker), final = Finished with badge "Final", postponed/canceled = Finished with that
  badge, not started = normal time windows using ESPN's kickoff as the start (the site's "live" marker is ignored). Unmatched items, or no fresh scores (a league's last fetch of today over 15 min old drops today's games; the 2-day schedule keeps only games not started), use
  `ListStatus.byTime`, the old rules. Matching is cached per games list. The home screen fetches scores too (5 s tick
  while in front) and redraws only when a status changes; the list screen rebuilds (keeping focus) only when a row's
  status or badge changes.
- **Site player** (`SitePlayerActivity`, `FOCUS_JS`): picks the biggest landscape video/frame and fills the screen with
  it; portrait/narrow frames (chat) score 0. If no candidate is left after a few ticks (an empty player box), it presses
  source button 1 once per page (max 2 per visit) via `SourcePress`; report line "Site player: pressed source button N
  of M (the player was empty)". Not yet tested on the TV.
- **Player overlay** buttons, in order: Pause/Play (first, focused), Jump to live, Source, Back to list,
  Audio & quality, Try another player. Up/Down/OK open the overlay; no channel surfing. Tunneling switches off
  automatically after audio-track errors, timeouts, or repeated stalls with a full buffer (saved after 2 strikes).
  Low-delay mode switches to smooth after 2 empty stalls in 60 s.
- **Expiring access codes.** If a playing source whose address has a query gets 403 after it had played fine,
  `PlayerActivity.renewAccess` quietly runs a light sniff of the same page (or the link player the source came
  from, `Source.playerUrl`), takes the verified source with the same address path (else any verified one) and
  restarts on it at live. At most one try per 60 s, gives up after 3 empty tries (then the normal reconnect).
  Report lines: "Access code expired; getting a fresh address…", then the outcome. Not yet tested on the TV.
- **Updater.** Checks its channel's GitHub release, verifies SHA-256 and signing cert, and installs only after the
  user presses OK (see README).

## Testing and diagnostics

- **JS harnesses** (headless Chrome; run with `NODE_PATH=/opt/npm-tools/node_modules`):
  `app/src/test/js/check-list-extract.js`, `app/src/test/js/start/check-player-start.js`,
  `app/src/test/js/links/check-source-switch.js`. Run them whenever an asset script changes.
- **Unit tests** run in CI on every branch (HeadersTest, CleanProxyTest, SourceLinksTest, HomeFeedTest,
  PngTextTest and others).
- **Report:** the in-app log the user copies from TV or phone. Look for "Links:" lines, per-link results, and
  Verifier failure details (size and first characters of the response).
- **Save sample:** zip with list page and stream page snapshots plus the log. The stream page comes from the player
  search when it found nothing, or from the links search; its info.txt line says which and what was tapped. Playlists
  the site player loaded are kept too. May be sanitized; words are examples only.
- **Site player lines** in the report say whether the video really plays and whether the page loaded a playlist the
  app can see (if it did, the hidden search should have caught it: a finder bug, not a site limit). "A possible
  playlist" lines mean only a disguised address (no .m3u8/.mpd) was seen; the finder checks those by content.
- **Recipes** are read from the build's own branch (`test` or `main`).
- **Built-in browser:** with the user's permission, inspect a live site's frames and network requests from the
  desktop app's browser pane.
