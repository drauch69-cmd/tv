# StreamLink — architecture and mechanisms (detail)

Reference for agents: read the section you need when working on that part of the app (search by class name).
Rules and how to work with the user are in `CLAUDE.md`; decisions in `docs/DECISIONS.md`.
Keep this file current when a mechanism changes (same commit).

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
   buttons is pressed. If ~5 s after the page loads (primary search only, not light runs) there is
   still no player (or only a player box with no frame) and no playlist, the sniffer presses source button 1
   (`SourcePress.kt`: `source-switch.js` `find`, then `click 0`; a coordinate tap if the click fails); ~10 s later,
   if still nothing, button 2 (max 2 presses). Report line: "No player on the page yet; pressing source button N of M".
   Tall/narrow frames (chat panels, width/height < 1.1) are never taken for the player (NUDGE_JS, source-switch.js,
   site player). The normal nudge/tap search then carries on with whatever player appears.
3. **Check.** `Verifier` fetches each candidate with up to 4 referers plus none, with Origin derived from the referer
   being tried (`Headers.originFor`). Each page referer is also tried as its bare origin with a trailing slash
   (`Headers.withOriginForms`). Referers from the page or the player frame's host go first (`RefererTrust.trustedFirst`), so ad/tracker frames never push them out of the 5 tries; a remembered referer on a blocked ad host is ignored. A 403 failure line lists the referers tried (hosts only). Only verified sources play. A check stops after 2 answers of 404/410 (the rest would say the same); a check cut off by the 10 s limit is judged on the answers it already got (a slow 404 = gone, not "too slow"), and checks that finish after the ranking returned write no report lines. A source whose every referer got an HTTP refusal (401/403/451) is marked "; refused on every referer" (`Refusal.isRefusedSource`) and is never played (dropped like a gone source): a search with only such candidates is treated like offline (`StreamSet.offline`; other sites' streams through the automatic switch, else the site player), line "The stream server refuses this stream (server said 403 to every referer); not playing it". Not yet tested on the TV.
4. **Play.** `PlayerActivity` (ExoPlayer). What it draws (loading cover, info overlay with buttons/tiles/speed pill, Audio & quality and
   Source panels) is `PlayerScreen`, which reads and acts through its `Host` interface; playback and recovery stay in
   `PlayerActivity` Decoder/tunneling/low-delay readings (stuck decoder, audio starving, stalls with video
   buffered, low delay running dry) are `PlaybackSigns`; the player acts on its verdicts. `UnwrapDataSource` strips disguises (PNG headers, pixel data, PNG text
   notes). If segments can't be decoded, fall back to the site's own player (`SitePlayerActivity`).
5. **Extra sources.** Nothing runs in the background while playing (the old link run that pressed each source
   button after 10 s of play was retired 2026-11-19). The Source panel lists, in order: the page's playable sources;
   the page's own numbered addresses ("<label> · this site", read once at the end of a successful primary search by
   `assets/page-sources.js` via `StreamSniffer.readPageSources` / `PageSources`: a <select> near the player whose change
   makes the page rewrite its address to the same path with another query (one option is pressed and the address
   watched up to 0.8 s; the rest are worked out from the query parameter that carries the option's value or position,
   max 3 presses; pinned players count), or links differing by one small-integer query value; with no pick-list near
   the player it answers at once, else within 3 s; report line "Page sources: N numbered address(es) on this page");
   and other sites' streams for the same game ("<list name> · other site", `Alternatives.forPage`: the sports folder
   holding this page, finished items skipped, by list rank, max 6, loaded on a background thread). Numbered and
   other-site entries are `Source.openPage` entries: choosing one runs `PlayerActivity.openLinkPage` ->
   `WaitingActivity.linkPageIntent` (full search, clean player, else site player). Numbered entries send the page as
   referer, other-site entries send none. The first player stays in the back stack; Back resumes at live.
   **Automatic switch** (`AutoSwitch`): when reconnects are used up (or a reconnect finds nothing), before the clean
   player, `tryAutoSwitch` moves to another verified playable source of the page, else the next openPage entry (cover
   "Stream went down, trying another source…", log "Stream went down; switching to <label> (<host>)"; this player
   finishes; `WaitingActivity` with `auto=true` tries the next candidate if no stream is found, else the site player).
   Max 3 switches per watch (reset when the user starts an item or link); pages/sources that went down are dead for
   5 min. Toast "Switched to <label>". Not yet tested on the TV.
   **Backups while watching** (`BackupKeeper.kt`, pure rules in `BackupLogic.kt`, `BackupLogicTest`; not yet tested on the TV).
   While a picture plays, `PlayerActivity` owns a `BackupKeeper` that keeps fresh addresses for the top 1-2 BACKUP sources
   of the same game (never the playing one, never another game): `BackupChoice.pick` = page entries (openPage, Source-list
   order, other sites ahead of this site's numbered addresses; dead ones skipped), then, only to fill the slots, other verified
   playable sources of the page (a different stream server first). A page backup gets a quiet light look (own `StreamSniffer`,
   `light`, `timed=false`, `quiet=true`: writes nothing to the report; hidden browser in a holder UNDER the video with the
   player view's background black; destroyed after every run; its page's referer as the Source list sends it); a playable
   source just a `Verifier.quickCheck`. One run at a time (`BackupPacing`); first run 30 s after the picture; only when
   healthy (`BackupTiming.healthy`: playing, no recovery, and >= 10 s of video ahead, or >= 60 s since the picture / last stall
   with >= 3 s ahead because low delay buffers little); interval from the server's learned lifetime (`BackupTiming.interval`:
   short = 2/3 of the oldest age that still worked, at least 3 min (kept light for the Onn); long/unknown = 10 min); a run that finds nothing doubles its
   wait (max 15 min); a run still going after 60 s is stopped. Paused (no run) during stalls, reconnects, renewals, quick switches
   and while away on a link page; resumes at the next picture. **Auto-off:** a stall that starts while a run is active or within 5 s
   after one ended turns it off for the rest of the watch (`BackupWatch.off`, reset with `AutoSwitch.reset()`, so it survives
   automatic switches), line "Backup refresh paused for this watch (it may have caused a stall)". One report line per refresh:
   "Backup ready: <label> (<host>), checked just now". Before each page refresh the previous result's address is re-checked in the
   background and (age, ok) is recorded as a lifetime sample.
   **Parallel recovery** (`PlayerActivity.recover` / `reconnect` / `switchToBackup`, `BackupRecovery.decide`, `RecoveryEpisode`).
   When a stream that had played drops: a ready backup that is fresh (younger than its server's lifetime allows, 2 min if unknown,
   30 min if long) is quick-checked (<= 2 s, `QuickRace`) and switched to at once: "Stream went down; switching to <label> (<host>),
   found N s ago" (counts toward the 3 automatic switches; a playable source plays in place, a page backup goes through
   `WaitingActivity.linkPageIntent(auto=true)` exactly like an automatic switch, with the found set handed over in `PreFound` so the
   loading screen skips its search; the quick-check failing logs "The saved backup no longer answers; reconnecting as usual" and
   the old path runs). Otherwise today's reconnect (page reload) runs AND, at the same time, the already known addresses (other
   verified playable sources of the page, ready backups) are quick-checked ("Reconnecting, and checking N known address(es) at the
   same time"); the first verified live answer claims the `RecoveryEpisode`, the other is ignored (reload result dropped /
   reload cancelled); a race win logs "A known address checked out first; using it (<label>, <host>)". Piece-only steps (engine
   restart, other engine) and playlist-gone are unchanged; the piece-only "switch source" step and the first recover() also try
   a fresh backup first. Access-code renewal still runs first for a 403 (unchanged).
   **Recovery state rules** (not yet tested on the TV; `PlayerActivity.cancelRecovery`, `BackupReady`, `RenewGuard`, `EmptyReload` in BackupLogic.kt).
   Leaving for a link page (`launchLinkPage`, `onStop`), picking a Source (`switchSource`) and a new session (`onNewIntent`) call `cancelRecovery`: bumps `linkGen`,
   drops the pending reload, cancels the page search, ends the episode, clears `renewing`/`fastChecking`; the reconnect's page-search callback also checks `linkGen`
   (so a source picked during "Reconnecting..." is not overwritten). `renewing` goes stale after 30 s (`RenewGuard`) and is cleared by reconnect/switchToBackup.
   While `fastChecking` (a ready backup is being quick-checked) the stall check and `handleError` do nothing. `restartEngineThenPlay` releases the old player before the
   engine reset. A reconnect whose reload finds NO stream no longer repeats the search: automatic switch, else the site player (never the clean player with the old set;
   log "No stream found while reconnecting"). After an empty access-code renewal the order is ready backup, automatic switch, then the page reload.
   `BackupKeeper.ready()` never offers the playing address; a reconnect that replaces the set drops the STREAM results (`dropStreamResults`). Report lines:
   "The saved backup no longer answers" (quick check failed) vs "The saved backup is no longer in the source list". "Stalled for Ns" prints the real wait
   (with the piece-still-arriving extensions); one stall is counted once ("Still buffering (stall #N)" when the check repeats the buffering line).
   **Offline streams (404/410).** `Verifier.check` marks a candidate that got at least one 404/410 answer and no other HTTP answer (a fetch that threw or timed out counts for nothing either way: `DeadStream.goneVerdict`) as gone (`DeadStream`,
   note "server said 404 (not found)"); `StreamSniffer` drops gone sources (and refused ones, see Check), so they are never played or fallen back to
   (`LinkOrder.firstPlayable/nextPlayable`, `AutoSwitch.order` also skip them), and a search with only such candidates is "no
   stream" with `StreamSet.offline` and the line "The site's stream for this page is offline (server said 404)". The loading
   screen then tries the game's other-site streams through the automatic switch ("Stream offline; switching to <label> (<host>)",
   cover text "The site's stream is offline. Trying other sources…"), else the site player (an item with no start time, e.g. a channel, gives up on the first offline verdict: no second search). In `reconnect`, when the reload hands
   back the address that just answered 404/410 (or the same path after a playlist-gone recovery) or is offline, the remaining tries
   are not spent: "The page still points to the same dead stream; trying other sources", then automatic switch, else site player.

**Checks that get no answer** (not yet tested on the TV; `NoAnswer` in BackupLogic.kt, `Verifier.rankWithRestart`). A check whose every try
   threw or timed out has note "no answer from the server" (a candidate cut off by the 10 s limit: "too slow to answer"). When every candidate of a
   run (at least one; possible playlists left out) ended that way on Chrome's engine, `rankWithRestart` runs `ChromeNet.reset()` (rate limited,
   on the check thread) and checks them once more (`rank(fresh = true)` skips the saved early checks). Lines: "No answer from the stream
   server; restarting Chrome's network engine and checking again", "Chrome's network engine restarted (...)", then "The stream server
   answers after the restart" or "Still no answer from the stream server after the restart". Still silent = marked `NoAnswer.FINAL`; the sniffer
   then treats the run as no stream ("The stream server gives no answer; not playing an unchecked stream", site player etc.) instead of playing
   an unchecked source. Other unchecked sources keep the old behavior. `Verifier.precheck` now keeps failed results too (same 20 s age rule), so
   `rank` neither fetches nor logs the same failure twice. In `PlayerActivity`, a playlist request that got no HTTP answer before the first picture
   on Chrome's engine (`PieceWatch.playlistSilent`) restarts the engine once per source ("The playlist gets no answer; restarting Chrome's network
   engine", shared `restartEngineThenPlay` with the piece-only step) and plays again, ahead of the stall fallbacks, "Trying the next source" and
   reconnect (checked at the stall check and in `handleError`).
   **Source panel uses a ready backup** (not yet tested on the TV; `PlayerActivity.useReadyBackup`, `BackupRecovery.freshFor`). When the user picks a Source
   entry the keeper holds a fresh result for (same freshness rule as parallel recovery), it is quick-checked (<= 2 s): a page entry hands the found set
   to the loading screen through `PreFound` (no search), a playable source plays in place on the re-checked address; line "Using the backup found N s
   ago for <label>". A failed quick check falls back to the normal search. Not counted toward the 3 automatic switches. The "Opening <label> as its own
   page" line is written once, by `WaitingActivity` (the player used to write it too, which looked like a double open; the page was only opened once).

**Early finish and late page sources** (not yet tested on the TV). On a primary run each direct stream is checked the moment it is
   found (`Verifier.precheck`, in the background, while the 1.2 s sibling wait runs; `rank` reuses or waits for that check, so an
   address is never fetched twice). When one passes AND is an adaptive (master) playlist or DASH, `EarlyFinish.keep` ends the search at once (a single-quality pass waits out the normal window), keeping
   only streams whose check already passed (still-running ones are dropped); copies found only inside other requests and
   unverified possible playlists never trigger it, so the 8 s embedded wait is intact. Report line "A stream checked out; not
   waiting for more". Page sources are read AFTER the hand-over (`lateExtras = true` for the home screen, the waiting screen and a
   link opened in front of the player): the sniffer gives its page to a late read (`finishThenRead`, destroyed when the read
   ends or after `PAGE_SOURCES_MAX_MS`), and `Session.addExtras` adds the numbered entries to `Session.current` and to the player's
   Source list (`SourceMerge.insertExtras`: before the first open-page entry). The waiting screen stays open until the late read
   ends (`onLateDone`). Prefetcher, reconnects and renewals still read page sources before finishing. Prefetcher now also uses the
   remembered address (`useRemembered`, quiet). **List reads pause** while `Session.mode` is waiting/finding/player/clean/site
   (`ListReadGate`; a read already running may finish; queued reads, including phone requests, start again on
   `ListReads`' own 5 s tick); log lines "List reads paused while a video plays" / "List reads resumed".
Screens: `MainActivity` (TV-home-style dashboard; "Live games" row (NFL RedZone first while it airs, then live games with streams, NFL games first; see "Live games rules"
below), "Sports" row (NFL, College football, NBA, 24/7 tiles), sections from `ListSections`; the top bar has two focusable
items side by side, "Your lists" and "Send a link", and Recent follows the rows; the lists are no longer a home row),
`ListsActivity` ("Your lists", opened from the top bar: list tiles in a 4-column grid built by `ListTiles.kt`; OK opens
`ListActivity`, hold OK removes, Menu re-reads all, the add tile returns home and shows the phone code; the home screen
asks the app's one list reader, `ListReads`, through the home screen's `ListEvents.refresh`; Session.mode "list"),
`SportActivity` (one sport: game sections, "All games" toggle, "Other" streams), `StreamsActivity` (one game's score card and
streams, or the 24/7 channels),
`Ui.kt` (shared UI kit; focus look = `Ui.focusCard` / `Ui.primaryCard` / `Ui.focusRing`: the focused view never changes size (no scaling); its fill brightens (SURFACE_FOCUS) and an accent ring is a `View.foreground` stroke drawn inside the view's own bounds, so nothing can clip it; default Android focus highlight is off), `WaitingActivity` (loading screen), `assets/phone.html` (phone page: Live now, Lists,
Remote tabs; prefetch toggle).

## Key mechanisms

**No automatic site player (2026-11-20; overrides every "else the site player" below).** Every place that used to open
`SitePlayerActivity` by itself now calls `PlayerActivity.openSitePlayer` (= `tryAutoSwitch`, else
`WaitingActivity.failedIntent`), `CleanPlayerActivity.openSite` (= `failedIntent`) or `WaitingActivity.tryAlternatives`
(other streams of the same game via `Alternatives.forPage`, max `AutoSwitch.MAX_SWITCHES`). With nothing left,
`WaitingActivity.showFailed` shows "No working stream" with a focused "Use site's player" button (also on the loading
screen at all times). The site player opens only from that button, the phone's "site" command or the list's own button.
A list event that has started and has no stream on the first check moves to the next stream at once instead of waiting.
Loading screen shows "Trying stream n of m" (`AutoSwitch.progress`). **Prewarm:** 4 s into the first search
(`PREWARM_AFTER_MS`) a second quiet sniffer searches the next candidate (`AutoSwitch.peekNext` or the first alternative)
in `warmHolder`; a stream it finds goes to `PreFound`, so the switch to it starts instantly. Lost if this screen ends first.
**Game pick:** OK on a game (home Live games tiles, featured Watch, sport rows, sport side card) plays `GamePick.best`
(first stream not Offline / not dead); hold OK opens `StreamsActivity`. **Running log:** `ReportLog` keeps each play's
report (`Report.start` archives the previous one; the current one is saved every 10 s) in `report-history.txt`, last 25
plays / 1.5 MB (`ReportHistory`); GET `/reports` downloads all, POST `/reports/clear` empties it.
**Sport screen side card:** `FeaturedPick.choose` on that sport only, its own `State` per sport; else `GamePick.nextUp`.
Home featured card: the photo covers the whole card (260 dp tall, about 3.3:1 on the Onn; 2026-11-21), text on top; `Ui.photoFade` runs from the card's left edge. The TV reports the card's real width/height (`SportPhotos.cardAspect`, `aspect` in GET /photos) and the phone page frames photos and previews (with stand-in text) at that shape.


- **Finder speed-ups.** `SiteMemory`: after 2 failed finds on a site (a miss counts only when the search saw no candidate at all and the stream was not offline/refused; never from light, backup or reconnect searches: `SiteMemory.outcome`), its search shortens to 15 s, except every 4th miss in a row gets the full search (`SiteMemory.FULL_EVERY`); the count is cleared
  when the site player shows the page loaded a playlist the app can see. The early give-up on a
  player frame from another site (8 s after the first tap) was removed (f396b92): those players often need 10-15 s.
  `Prefetcher` gives
  Live/Soon items a 2 s background head start (quiet: `Report.muted`). Light sniffs (25 s timeout) block every
  request except playlists once a playlist was seen (no separate image/font rule; the list reader has one, see "Lists").
- **Learned habits per site** (`SiteHabits.kt`, pure helpers unit tested in `SiteHabitsTest`; not the hand-written list recipes).
  A player-spot habit is applied at the first nudge that sees the player (else at page load), replacing that nudge's tap; habit
  taps count toward the run's tap total, so the learned tap count is the real one (2026-11-20, not yet tested on the TV).
  **Shipped habits** (2026-11-19, `RecipeHabitTest`, not yet tested on the TV): a `recipes.json` site entry may carry
  `"habit": {"tap","x","y","taps","button" (from 1),"buttons","secs"}` (`SiteHabits.fromRecipe`; an entry may hold only
  host + habit). `SiteHabits.get` uses the learned habit when usable, else the shipped one (`pick`; from the saved
  recipes copy only, `ListRecipes.saved`, which never waits for the network and refreshes in the background after 10 min);
  2 shipped misses = not used until the app restarts. Log: "Using this site's recipe: …"; every newly learned habit
  logs a copy-ready "Habit for recipes.json: {…}" line.
  Pref `site_habits_1`, per host (lower case, no www.), max 80 (LRU), 14-day expiry. Recorded in `StreamSniffer.finish` (via
  `habitFinished`) after a primary search (not light, not click-only, not the remembered-address path) ends with a verified stream:
  the last tap made before the first stream was seen (position as a FRACTION of the hidden browser's size, kind player-spot or
  page-middle, 1 or 2+ taps; `noteTap` in `tapStep`), the source button pressed before it (index of count, from `pressSource` or the
  habit's own press), and seconds from page loaded to stream seen (report only). Nothing to do on the page = nothing recorded (a habit
  that was used and worked then only clears its misses). Used by `applyHabit` right after `onLoaded` (no FIRST_NUDGE_MS / SOURCE_FIRST_MS
  wait): taps the remembered spot (2+ taps: the second ~1 s later) and presses the remembered source button through `SourcePress.press`
  (`expectedCount`: only if the page shows the same number of buttons, and it does not skip the active one); `sourceNext` is moved
  past the pressed button so the normal search does not press it again. The normal nudge/tap/source search keeps running untouched
  beside it (worst case = today). No stream within the habit's usual seconds + 5 s, or a run that ends with no stream ever seen = one miss (a stream that was seen but is offline/refused is not a miss); 2 misses
  in a row drop the habit. Prefetcher runs (`timed == false`) may use and record habits but never count misses (background runs are
  often cut short or compete for the network, so a miss there would make habits flap). Light runs never use or record. Report lines:
  "Using what worked on this site last time: tap at player spot|middle of the page, 1 tap|2 taps" and/or "... source button N of M",
  "The learned habit worked (stream after X s)", "The learned habit stopped working; forgetting it". Not yet tested on the TV.
- **Fast-start memories** (`FastStart.kt`; pure helpers unit tested in `FastStartTest`). `LastAddress`: when a stream first
  renders (`onRenderedFirstFrame`, verified, not an openPage entry) the whole set is saved per page address (the address
  the search was asked to open, `StreamSet.requestedUrl`, and the final page if different; normalized: no click fragment,
  lower-case host, no trailing slash), played source first, openPage entries kept, max 8 sources, 50 pages (least
  recently saved dropped), 24 h expiry; never for click-only list events or links that are a video file. In
  `StreamSniffer.sniff` (foreground runs only: not light, not click-only; Prefetcher and PlayerActivity's reconnect pass
  `useRemembered = false`) a background thread re-checks only the first stored source (`Verifier.quickCheck`, 3 s, must
  be a live non-ad HLS/DASH playlist) while the page loads as usual. If it passes before the page search reached its own
  check and the run is still current, `finish` plays the stored set (this cancels the page load); if it fails the entry is
  dropped and the search goes on. Report lines: "Trying the last address that worked (saved N min ago)…", then "The last
  address still works; playing it" or "The last address no longer works; searching the page". `ServerMemory`: per stream
  server host (lower case, no www., max 100, LRU, 7 days) the referer it accepted (`Verifier.check` tries it first, then the
  usual list, max 5 real ones; recorded on a pass; line "Trying the referer this server accepted last time") and the
  network engine that gave a picture ("chrome"/"standard"; recorded at first frame and when a 403 on standard
  networking sends the player back to Chrome). `PlayerActivity.applyRememberedEngine` (new link and reconnect): "standard"
  starts with standard networking ("Using standard networking for this server (it worked last time)"); "chrome" sets
  chromeForced ("Staying on Chrome's network engine for this server (it worked last time)"); if the remembered engine then
  stalls or gets 403, it is forgotten and the other engine is tried once (remembered chrome -> standard; remembered
  standard -> Chrome's engine, kept), then the normal recovery. Not yet tested on the TV.
  **Which referers are remembered** (`RefererTrust`, `StreamCandidate.rememberable`): `Verifier.check` records a passing referer in
  `ServerMemory` only when it is one the sniffer marked rememberable, decided by structure: its host is the same site as the page the
  search opened (or where it ended, or the page that sent the user there) or as the player frame the nudge picked (biggest landscape
  frame, host `fh`). The Referer a request carried on its own (its initiator, often a tracker/pixel frame) and other sub-frame
  addresses are tried as before but never remembered. Candidates built elsewhere (backups, quick checks) remember nothing.
  **Address lifetime per server** (`ServerMemory` samples, JSON "life", max 12 per host, 7 days): each re-check of an address records
  (age since found, ok/refused; a quick re-check that fails counts as refused only when it got a 401/403 answer, a timeout / non-live / ad-like / 404 records nothing: `ServerMemory.recordCheck`, `Verifier.quickCheckStatus`): the remembered-address shortcut, the keeper's re-check of its previous backup result, a playing
  address refused with 403 (fail) and one that played fine until the screen was left (ok). `lifetimeEstimate`: SHORT = at least 2
  refusals at an age of 20 s to 11 min and no success older than the youngest of them (oldest success below it, else half its age,
  is the max-ok age); LONG = a success of 10 min or more and no short failure; else unknown. The remembered-address shortcut is skipped
  for a SHORT server when the saved entry is older than its max-ok age ("Saved addresses from this server expire quickly; skipping
  the shortcut"). Not yet tested on the TV.
- **Unwrapping.** `UnwrapDataSource`: PNG header strip, pixel decoding, and `PngText` (iTXt/zTXt/tEXt notes ordered
  by the keyword's trailing number, joined, base64-decoded). A counter of undecodable segments triggers the
  site-player fallback.
- **Source links.** `assets/source-switch.js` finds the page's own row of source buttons by structure, never by
  words, for the empty-box presses only (`SourcePress`). Its anchor is the largest visible landscape
  (width/height >= 1.1) video/iframe; with none, the largest visible EMPTY player-shaped box (div/section/figure,
  aspect 1.3-2.4, no video/iframe/canvas/object/embed inside, <= 200 chars of text, not pinned, not in
  nav/header/footer), and `find` then answers `empty: true`. Buttons with sequential number badges count as a
  numbered row. `SourceLinks.kt` (`LinkOrder`) parses the answer and keeps openPage entries out of automatic
  fallback (`nextPlayable`, `firstPlayable`) and access-code renewal (`canRenew`).
- **Lists.** `assets/list-extract.js` reads index pages with learned shapes (`LearnedShapes`), loose rows and
  stragglers.
  **Data-file recipes** (`FeedRecipe.kt`, `FeedRecipeTest`; user, 2026-11-20): a recipe with "feed" (no "item" needed)
  makes `IndexReader.read` download the site's own JSON (`QuickFetch.download(json = true, referer = list page)`, 8 s
  limit `FEED_MS`) and map it to events: `items` (dotted path to the array), `filter` (field = value), `link`/`title`/
  `subtitle`/`section`/`start`/`end`/`live` fields; templates `{path:N}` (list address part, -1 last), `{field}`,
  `{x|fallback}`. Live = the live field or now between start and end. Failure / no events = the normal (quick or full)
  read, logged "List: data file failed for <host> (…); reading the page". Uses the saved recipes copy
  (`ListRecipes.saved`, never waits). Entries on `sports`: reedstreams (one file for all sports, filtered by the list
  address's last part) and ntv. Line "List: read N events from the site's data file for <host> in X s".
  **Quick reads** (`QuickRead.kt`, `QuickLogicTest`; user, 2026-11-20): `IndexReader.read` first asks the per-site verdict
  (`QuickReads`, own settings file `quick_reads`). Unknown site (or due a re-check: quick 24 h, browser 7 days) = TEST: the
  page's HTML is downloaded (`QuickFetch`, browser's name + cookies), scripts and meta refresh stripped
  (`QuickLogic.stripScripts`), loaded with `loadDataWithBaseURL` (only style sheets may load), extracted once, kept aside;
  then the normal full read runs and its result is used. `QuickLogic.matches` (90% of events with the same start time and
  live mark, few extra rows) decides the verdict. QUICK sites: quick half only; nothing found / failed / 10 s limit
  (`QUICK_MS`) = full read and the site goes back to the browser. Quick halves never follow frames, press "show more",
  scroll or snapshot. Report lines: "List: loading <host> the quick way", "read N events … (automatic, quick way)",
  "the quick way finds the same events on <host>…" / "misses events…" / "didn't work (…)".
  Quick-read test is ESPN-aware (2026-11-20): events `ScoreMatch.find` matches only need their link; other events also
  need the same time and Live mark. A quick read with only click rows (section headings) counts as nothing. A test where
  both halves find nothing saves "browser" (no re-test on every read).
  **Which reads happen** (`ListReads.request`, 2026-11-20): non-manual requests skip a list on back-off (`ListBackoff`,
  file `list_backoff`: bad reads in a row = failed or no events; `ListReadLogic.backoffMs`: 30 min after 3, then 2 h;
  line "List: holding <host> …"). TIMER requests also skip a list with nothing on (`ListReadLogic.worthReading`: none
  of its saved events live/soon or starting within 2 h, and no game of its sport tags live or starting within 2 h by
  ESPN; still re-read every 2 h; line "List: skipped <host> (nothing on now or soon …)"). MANUAL always reads.
  **One reader for the app** (`ListReads.kt`, pure rules in `ListReadLogic.kt`, unit tested in `ListReadLogicTest`;
  2026-11-19, not yet tested on the TV): one queue, one read at a time, run in the hidden holder of the newest attached
  screen (home, `ListActivity`, `SportActivity` attach in onResume, detach in onDestroy; a read in a screen that goes away
  is cancelled and queued again first). Screens `request(id, reason)` and listen; duplicates merge. Reasons: MANUAL
  (Menu, refresh notes, phone Refresh, a new list) always reads; OPEN (a screen opening) skips a list read in the last
  2.5 min; TIMER skips within 60 s ("List: skipped <host> (read N s ago)", once per skip). One 90 s watchdog; `ListReadGate`
  inside. Re-read timing on the list and sport screens: `nextReadDelay` = 5 min, 90 s while an event starts within 15 min
  or started within 10 min (min 60 s), counted from the last read or try ("List: next read in N s"); home keeps its
  10 min `STALE_MS`. The list screen rebuilds rows only when the look (sections, links, titles, statuses) changes;
  the 30 s refresh updates meta lines, badges and scores in place.
  **Reader speed** (`IndexReader`): pictures, fonts, audio and video are answered empty (`ListReadLogic.isHeavy`, by
  Accept header or extension; never the main frame; "List: blocked N heavy files"); `list-extract.js imageOf` keeps an
  unloaded, unsized picture only as a last resort (it cannot be measured). Settle polling starts at first paint
  (`onPageCommitVisible`) or 2 s after the load began; nothing is read while `readyState` is "loading". From 3 s of
  polling the extractor itself runs at each poll (~1.5 s apart, at most 4 looks): two equal non-empty event keys
  (links, titles, time texts) finish the read ("List: events stable after N s (M looks)"); otherwise the old rule
  (unchanged twice / 15 s). The Save-sample snapshot (picture + page copy) is taken only after a failed or empty read.
  Learned shapes (pref `list_shapes_v2`) are applied with the same quality rules as a fresh read (no chrome, no
  links inside running text, a timeless group needs 4+ rows) and are only remembered when at least half the rows of
  the read had a time or live marker (`shapeTimed`). A read that loaded but found no events (`ListResult.noEvents`)
  replaces the cache with an empty list (`ListCachePolicy.shouldSave`); real load failures keep the old cache. `ListStatus`: Live if started under 4 h ago, Finished 4–24 h, always-on channel past 24 h; real live
  events sort before always-on ones. A site's own live marker counts only until 4 h after the listed start (some
  sites never take it down); with no start time it is trusted. `DateGuess` reads ET/CT/MT/PT/UTC/GMT/offset labels against the system clock.
  Reader rules (structure only): a line identical in nearly every row of a 3+ row group (league label, "Watch") is a
  label, never a title; lines with no letters and button text are not titles and button text is never a live marker;
  two same-style name lines with only short parts between them (VS, score, time, status, countdown) or sharing one
  class become "A vs B"; an empty full-card link's aria-label/title is the title; table cells are separate lines;
  a live marker or `data-time` counts like a clock when scoring rows; links inside running text are not rows; a group
  with no time/live marker needs 4+ rows (or a date heading); same-shaped containers in sibling sections merge;
  header/form rows are penalized; "STARTS IN" + "1d 19h" is one time text (`DateGuess` reads compact countdowns).
  **Show-more expansion** (`assets/list-expand.js`, run by `IndexReader.expandThenExtract` once per page load, after
  the page settles and before extracting): presses up to 4 "expander" controls found by structure only: a `<button>`,
  `[role=button]` or non-navigating `<a>` with short text, directly after/next to a group of 5+ same-shaped clickable
  rows; never in nav/header/footer/tablist/menu/toolbar, never tabs, already-expanded controls, form submits, or a
  button with 2+ same-kind siblings (tabs, filters, pagination); never a link with a real href. Report line
  "List: pressed N 'show more' button(s)"; then waits for the page to settle again (500 ms polls, unchanged twice or
  4 s). Nothing pressed = no delay; retry paths never press again; a press that changes the address is not repeated.
  Category tabs are never pressed (an index's default view usually shows every category). Not yet tested on the TV.
- **Live scores** (`Scores.kt`). On the list screen, `ScoreFeed` fetches ESPN's unofficial scoreboard for NFL,
  college football (all FBS games: `groups=80`), the NBA and men's college basketball (league "cbb", all Division I:
  `groups=50&limit=500`; halves read H1/H2, then OT, 2OT; only "Halftime" is the break) for today in New York time (also yesterday before 5 am), every 30 s
  while a game is live, else every 5 min, 2 min back-off after a failure; no key. The next two days are fetched too
  (schedule for the sports folders), cached separately and refreshed at most every 30 min; a failure there never
  discards today's data. Today's fetch runs in waves (`ScoreFeed.todayWaves`, 2026-11-20): NFL, college football and NBA
  together, then college basketball (`LATE_LEAGUES`, the biggest answer), so the first draw isn't slowed. `ScoreFeed.latest()` returns all of it (3 days), merged by game id. `ScoreMatch` matches a list title to
  a game only if BOTH teams are named (full name, school/city, nickname or abbreviation, whole words; a name inside a
  longer team name doesn't count; a city shared by two teams of the same pro league (NFL or NBA, e.g. Los Angeles) is ignored as a name, worked out per league), within 18 h of the event's start; if
  several games fit, the one starting within 90 min wins, else nothing is shown. Rows get a short line ("WSH 24 – NYG 21
  · Q3 8:42", "Final · …"; NBA overtime reads OT, 2OT…), the details card a score block (names, status, records, channel; kickoff in the TV's time).
  Updates change text in place (rows are not rebuilt, focus untouched). Any failure = the screen looks as before.
  **Matches survive score updates** (2026-11-20): answers are stored per games *shape* (`ScoreMatch.shapeOf`: ids,
  leagues, start times, team names; never scores/clocks) as game ids, and handed back as the game object of the list
  asked with, so a new scores list with the same games rematches nothing (home redo was 3-7 s on the Onn). The home
  screen also waits `SCORE_SETTLE_MS` (1.5 s) after a league lands, so one score round = one redo (while the loading
  screen shows, the first answer starts a home redo at once; the screen hides once a redo built from non-empty scores is
  drawn, or after 15 s). Report line "Home: worked out in X s (N games, M list events)" for the
  first 3 calculations and any over 1.5 s.
  Matching is cheap and thread-safe: `ScoreMatch` keeps a small LRU (6) of prepared entries per games-list instance (names per team, answers per title), and `GameViews` (Games.kt; `ScoreFeed.forLeagues`) hands out the same filtered/windowed list instance per (source instance, filter), so screens and the home calculation no longer evict each other's cache.
  On a list screen whose list has sport tags (a set), scores are matched only against games of any of those leagues (untagged lists try all leagues). Report lines start with "Scores:". Uses ESPN team names only, never site wording.
- **Live games order** (user, 2026-11-19): by sport, NFL, college football, NBA, college basketball
  (`LiveGames.leaguePriority`), then earliest start. College basketball has its own Sports tile and phone chip.
- **Offline marks** (`Offline.kt`, user 2026-11-19). When the loading screen's check finds an event's stream dead at the
  source (`StreamSet.offline`: gone or refused on every referer) after its start time, `OfflineMarks` marks it for 15 min
  (kept in prefs): `ScoreStatus.resolve` then gives Finished with badge "Offline" everywhere (lists, Live rows, phone, game
  folders, automatic switching). A found stream (`WaitingActivity.found`) or video playing in the site player clears it.
  Report line: `Marked "<title>" Offline for 15 min …`. Not yet tested on the TV.
- **Live games rules** (user, 2026-11-19; `LiveGames.sample`, `HomeFeed.redZone`, `ChannelFilter`, `HomeModel`). The home
  "Live now" row and the user's channel-word list (`LiveWords`, `/livenow/words`, the phone editor) were removed. Home shows
  one "Live games" row: (1) an NFL RedZone card first, while it airs: the first list item (best-ranked list first)
  whose title is a channel (`ChannelFilter.isMatchup` false) holding "RedZone"/"Red Zone" (`ChannelFilter.isRedZone`), shown
  only while at least 2 NFL games are live by ESPN (`HomeFeed.REDZONE_MIN_NFL`, RedZone only airs in multi-game windows) and
  the item's own status is live or unknown; one card even when several lists carry it; it plays the list item (head start on
  focus); (2) live games with streams, NFL games first, then the others by start time (college before NBA as a tiebreak).
  "RedZone" is a deliberate, user-owned exception to "never key on page words" (the user's viewing choice, not detection).
  Always-on channels (ESPN, NFL Network...) are in the 24/7 tile; unmatched two-sided games in each sport's "Other". The
  phone's Live now tab keeps items whose `liveNow` flag (`ListJson.listForPhone`: matched to a game, or RedZone) is not false
  and whose status is live or soon. Not yet tested on the TV.
- **Sports folders** (`Games.kt`, pure Kotlin; logic only so far, screens come separately). A saved list carries a
  set of sport tags (`SavedList.sports`, any of "nfl", "cfb", "nba" via `Sports`, empty = none; `has(league)`; JSON
  `"sports": [...]`, the old single `"sport"` string is still read; set with toggle chips on the phone page's Lists tab, when
  adding and on each list; `POST /lists/sport` (id, sport) and `/lists/add` take `sport` as a comma-separated list
  like "nfl,cfb", unknown values ignored; `GET /lists` returns `"sports"`). `GameFolders.build` sorts every item of
  every list tagged with the league into that sport's games (today to the end of today + 2 in the TV's zone, plus any
  live game) using `ScoreMatch` on the title, then on title + subtitle, against the games of ALL the list's tagged
  leagues together (one best game wins; if it belongs to another league the item is skipped here and placed when that
  league is built, so an item never lands in two sports); unmatched go to `other` (so in every tagged sport's Other) only when the title is a matchup (`ChannelFilter.isMatchup`: two-sided; channels and shows are dropped, they stay reachable in their list and the 24/7 tile); always-on items are never placed, finished ones only when they match a
  game; the same address (trailing slash ignored) appears once. `GameSections.group` makes Live now / Today /
  Tomorrow / weekday / Final sections (`withStreamsOnly` hides games without streams); `AlwaysOn.collect` gathers
  24/7 channels from all lists; `LiveGames.sample` feeds the home Live games row (`matchedUrls` is kept for tests); `GameFolders.summary` is
  the quiet "Sports:" report line.
  **List ranking.** Each saved list has an optional rank (`SavedList.rank`, 1 = best, null = unranked; JSON "rank";
  old JSON's "preferred": true reads as rank 1). `Ranking` in SavedLists.kt: ranks are always 1..k with no gaps or ties
  (`normalize`, also on every read); giving a list a taken rank moves the others down (`set`); `order` = ranked by rank,
  then unranked in saved order; `sort` groups any items by that order. Set only on the phone page's Lists tab (a
  "Rank" picker per list: Unranked, Rank #1..; `POST /lists/rank` id + rank, empty = unranked); `GET /lists` returns
  "rank" and lists best first. The TV list screen shows "Rank #N" read-only; "Your lists" tiles are drawn best first
  with "Rank #N". **Rename:** "✎ Rename" chip per list on the phone's Lists tab ->
  `POST /lists/rename` (id, name) -> `SavedLists.rename` (cleaned, max 60 chars; false = 404) -> home `renderLists()`. Code asks
  `SavedLists.rankOrder(ctx)`; no site is ever named in code. Wherever a game has several streams they follow the ranking:
  `GameFolders.build(..., listOrder)` (lists handled in rank order, so a better rank also wins a duplicate address and
  leads "Other"), `AlwaysOn.collect`, `HomeModel` (RedZone card), the phone's "Live now", the Source panel's other-site
  entries (`Alternatives.pick`) and automatic switching. Game-screen rows show "#N" in their meta line. `HomeModel.look`
  and the `StreamsActivity` look include the ranking. Not yet tested on the TV.
  Screens: home "Live games" row (`LiveGames.sample`, cards open the game) and "Sports" row (tiles open `SportActivity` /
  the 24/7 `StreamsActivity`); both are rebuilt (focus kept) on every list render and by `ScoreFeed.refreshIfDue` on resume
  and the 60 s tick. `SportActivity` works out folders/sections on one background thread (`sport-calc`, generation counter, stale results dropped), draws on the main thread only when the computed look (sections, games, scores, stream counts, statuses) changed or the "All games" chip toggled, and shows a quiet "Loading games…" row first; its 1 s beat only checks the read watchdog (score refresh check every 5 s, re-render every 30 s). `StreamsActivity` does the same (`streams-calc`). `SportActivity` shows cached lists at once, then re-reads
  every list tagged with its sport through `ListReads` (on open: lists read in the last 2.5 min are skipped; then every
  5 s it asks for lists due by `ListReadLogic.nextReadDelay`; Menu and the refresh note force all); the "All games" choice is kept per
  sport (pref `sport_all_games_<league>`, default off = games with streams only). `StreamsActivity` never reads lists; it
  rebuilds from caches + `ScoreFeed` and uses `Prefetcher` head start. Both new screens use `Session.mode = "list"` (so the
  phone's stop works and head start is allowed). Pure text lives in `SportsText.kt` (`SportsTextTest`); `SportsData.kt`
  loads folders from caches.
- **Home screen refresh and list cache.** `SavedLists.cached` / `all` keep the parsed lists in memory (`CacheMemo`; the same
  object until a read is saved or a list removed, so no screen re-parses JSON on every draw). `MainActivity` has ONE score
  refresher: `scoreTick` (5 s) asks `ScoreFeed.refreshIfDue`; when data changed it calls `requestHome(false)`. Everything
  on home that depends on lists or scores (Live games row incl. the RedZone card, Sports tiles, list tile counts) comes from one
  `HomeModel` (`HomeModel.kt`, pure) built on a single background thread (`home-calc`) and drawn on the main thread;
  results from older requests are dropped (`homeGen`). A non-forced request (new scores) redraws only if `HomeModel.look`
  changed (look covers statuses, counts, live-game score lines, sport tile lines); forced requests (`renderLists()`:
  list changes, busy marks, the 60 s clock tick, resume) always redraw. First draw and list removal use `renderHomeNow()`
  (synchronous). Rows still keep focus as before. Not yet tested on the TV.
- **ESPN-driven status** (`ScoreStatus.resolve`). Every status, badge and start time (list screen, home RedZone card
  and counts, phone page, prefetcher) goes through it. The list screen and the phone page's list view use only the games of the list's sport
  tags (same as its score lines), so status and score always agree. For an item that matches a game: in progress = Live (any
  length, ignores the site's marker), final = Finished with badge "Final", postponed/canceled = Finished with that
  badge, not started = normal time windows using ESPN's kickoff as the start (the site's "live" marker is ignored). Unmatched items, or no fresh scores (a league's last fetch of today over 15 min old drops today's games; the 2-day schedule keeps only games not started), use
  `ListStatus.byTime`, the old rules. Matching is cached per games list. The home screen fetches scores too (5 s tick
  while in front) and redraws only when a status changes; the list screen rebuilds (keeping focus) only when a row's
  status or badge changes.
- **Throwaway popups** (`SafeChromeClient.onCreateWindow`, every web view): a popup gets an invisible, never-shown
  web view (no scripts, every request answered empty), destroyed after 5 s; at most 3 live per page, past that refused
  ("Blocked a popup window"). Reason: a refused popup returns null, which players (ntv) read as a sandboxed frame and
  refuse to play; a JS-only stand-in (build 281) missed tests run from fresh blank frames. Report line: "Gave a popup
  an empty hidden window". Popups without a tap still get null (javaScriptCanOpenWindowsAutomatically = false).
- **Renderer crashes** (`SafeWebViewClient.onRenderProcessGone`, always answers true): calls the open hook `onRendererGone(view,
  didCrash)`; default removes and destroys the view (`SafeWeb.dispose`). `StreamSniffer` logs "The hidden browser was shut down by
  the system" and ends the run as a failure (reason "web view crashed"); `SitePlayerActivity` logs, shows a short message and
  finishes. `IndexReader` and `CleanPlayerActivity` override `onRenderProcessGone` with their own handling. Not yet tested on the TV.
- **Site player** (`SitePlayerActivity`, `FOCUS_JS`): picks the biggest landscape video/frame and fills the screen with
  it; portrait/narrow frames (chat) score 0. If no candidate is left after a few ticks (an empty player box), it presses
  source button 1 once per page (max 2 per visit) via `SourcePress`; report line "Site player: pressed source button N
  of M (the player was empty)". Not yet tested on the TV.
- **Polished look** (user, 2026-11-20; mockups on the "StreamLink UI mockups" canvas, "Polish pass" row). Pure rules in
  `LookRules.kt` (`LogoUrls`, `PhotoFrame`, `SportPhotoRules`, `PlayerStats`; `LookRulesTest`).
  - Cards (home live games, sport tiles, RedZone, sport-screen game rows) use `Ui.fadeCard`: a subtle dark fade, a
    shade lighter when focused, accent ring on top. Stream counts are `Ui.streamChip` (broadcast mark + number; grey
    outlined "0" when none).
  - Team logos: `TeamSide.logo` from ESPN's scoreboard (https only). `Logos` downloads each logo once at the size it is
    drawn (tries ESPN's dark-background logo, then the normal one, through ESPN's resizer), saves it in the cache folder,
    keeps 4 MB in memory; until it arrives the circle shows the team abbreviation. Failures aren't retried until restart.
  - Featured card (home, top): `FeaturedPick.choose` (2026-11-20). Live games with a stream get
    score = (100 × doubt × timing + drama) × booster + streams. Doubt = 1 − |2p − 1| from ESPN's live win
    probability (`Game.homeWinProb`), else estimated from margin and time left (σ NFL 13.5, CFB 16, NBA 12, CBB 11);
    a one-score game in its final minute or overtime always counts as 1. Timing = 0.2 + 0.8 × played^1.5 (halftime
    × 0.6). Drama (cap 25): crunch +15, comeback +10 (14/15+ lead cut to one score within 15 min, from
    `FeaturedPick.State` score history; scores that go backwards are ignored), upset +8/+12 (college: only vs a ranked
    team or a 7+ favorite), red zone +5 (2nd half). Booster: NFL quality/≥.500/division/spread (max 1.5); NBA
    quality/national TV/spread (max 1.5); college rank (both .4, one .15, top-10 +.1 each), winning records, national
    TV .15, +.05 per Big Ten team, spread (max 2.0). Streams: min(5, 2.5 × log2 n). College games with no ranked team
    are used only when no other live game has a stream, except Big Ten vs Big Ten. Stickiness: 5 min hold; a challenger
    needs ×1.2 and +10 (+20 in its first half); never while the card is focused; an undecided crunch game is kept,
    except (after 1 min) another undecided crunch game with an NFC North team (CHI/DET/GB/MIN) takes over from one
    without; if both or neither have one, a tied/overtime final minute beats a 4+ lead; a game that ends, or a fallback game when a top game
    appears, is replaced at once; blowout (<10 vs ≥30) is replaced at once. Report line "Featured: … (reason, score
    …)" on each change. Tuned by replaying ESPN play-by-play (NFL weeks 8-10, college 2026-11-14); basketball weights
    are untested guesses. It is left out of the Live games row. The same ratings (`FeaturedPick.shared`, one app-wide
    State: card, score history, latest ratings) order the Live games row within each sport (sport order kept), and put a
    flame ("heating up": crunch time, comeback or upset brewing) on Live games cards, the featured card's status and
    live sport-screen rows. Sport-screen rows (user's mockup, 2026-11-20):
    full width, mirrored around the time/score pill: "Name [logo] 7:00 PM [logo] Name", first team's name and record
    right-aligned against its logo, second team's left-aligned; a spacer as wide as the stream chip on the left keeps the
    middle centred; no TV channel. "No events" notice: a rounded yellow warning triangle and the address in yellow, no box, under the title. Watch (accent pill, first focus) plays the game's first stream;
    the stream chip opens the game's stream list. Photo box 510x196 dp on the right (2.6:1), dark scrim on the left.
  - Sport photos (`SportPhotos`): uploaded per sport from the phone page's Lists tab ("Home photos"; the phone shrinks
    to 1600 px JPEG), stored only on the TV (`filesDir/sport-photos`, never in the repo). The user taps the spot to keep
    in view (focus point, 0..1 fractions); `PhotoFrame.place` covers the box with that spot as central as the edges
    allow, and the phone draws the same crop as a dashed box. Routes: `GET /photos`, `GET /photo?league=`,
    `POST /photo/upload?league=` (raw bytes, 3 MB max, same-origin), `POST /photo/focus`, `POST /photo/remove`.
    Report line: "Photos: saved a N KB photo for <sport>". No photo: the card shows without one.
  - City photos (`CityPhotos`, rules in `CityPhotoRules`; user 2026-11-20, choice A): the featured card and the sport
    screen's side card show a photo of the game's city (ESPN `competitions[0].venue.address` city/state/country, read
    into `Game.venueCity/venueState/venueCountry`) ahead of the sport photo. Unsplash search "<city>" (was "<city> skyline" until 2026-11-21; older saves are searched again), then
    "<state name or country> landscape"; landscape, 10 results, widest first. Saved per city (prefs `city_photos`,
    images in `filesDir/city-photos`) and reused; nothing found = retried in 7 days, failure = 30 min; at most 40 API
    calls an hour (demo key: 50). Default framing x 0.5, y 0.6. The download address is called once per photo used
    (Unsplash guideline); the card shows "Photo: <name> / Unsplash" bottom-right (home card only). The sport's fade
    applies (`POST /photo/fade` no longer needs an uploaded photo). Key: Actions secret `UNSPLASH_ACCESS_KEY` ->
    `BuildConfig.UNSPLASH_KEY`; empty = city photos off. Phone (Lists tab, "City photos"): framing tap and "Next photo"
    per city. Routes: `GET /cityphotos`, `GET /cityphoto?key=`, `POST /cityphoto/next`, `POST /cityphoto/focus`.
    Report lines: "City photos: N for "<query>"", "nothing found for <place>", "Unsplash answered <code>", "hourly limit".
  - Demo games (`DemoGames`, user 2026-11-20): phone checkbox (`GET /demo`, `POST /demo/set`) adds 7 made-up games in
    real stadium cities plus a made-up list carrying them, home screen only (`MainActivity.computeHome`); in memory, so
    off after a restart. Watch on one shows a "Demo game" toast.
  - Top bar "Leagues" (was "Sports"; user 2026-11-21): scrolls home to the Leagues row and focuses its first tile. "Options"
    (was "Send a link") opens the panel with Your lists, Check for updates, Head start, Close and the QR code.
  - Sport screen side card (2026-11-21): photo behind the whole card (`Ui.photoFadeUp`), 300 dp tall; next up = a week ahead.
- **Player connection pill** (overlay, right end of the button row, refreshed every 0.5 s; same shape, padding and text
  size as the buttons, not focusable; user 2026-11-20): "⇄ 24 ms   ▸ 6.2", "  N dropped" added only when frames were
  dropped, "…" for an unknown number; button-dark fill with a hint of the level color. Purpose: is it the connection or
  the stream? ⇄ = ping: `Pinger` times a TCP handshake to 1.1.1.1:443 (8.8.8.8 if no answer, 2 s timeout) every 3 s
  while the player is open (stopped in onStop); `PingStats` keeps the last 5, shows the median of replies, "lost" when
  the newest got no reply. Needs (▸, Mbps) = the playing quality's announced bit rate, else `StreamRate` (measured from
  finished media pieces: bytes over media seconds, last 8 per track kind, kinds added); reference only.
  Colors (`PlayerStats.Level`, from the ping alone): green ≤100 ms and none lost, yellow over 100 ms or 1 lost, red over
  300 ms, 2+ lost or the last two lost, grey while measuring. Stall report line: "Buffering (stall #N): ping 24 ms
  (0 of 5 lost), this quality needs ≈ X Mbps, Ys of video was buffered".
- **Player overlay** buttons, in order: Pause/Play (first, focused), Audio & quality, Source, Jump to live, Try another player (no Back button: the remote's Back key; user, 2026-11-19). Up/Down/OK open the overlay; no channel surfing. Tunneling switches off
  automatically after audio-track errors, timeouts, or repeated stalls with a full buffer (saved after 2 strikes).
  Low-delay mode switches to smooth after 2 empty stalls in 60 s.
- **Other games tiles** (`GameTiles`, PlayerActivity "Overlay: other live games"): when the menu opens, live games of the
  sport being watched (the game whose folder holds the playing item; else a title naming both teams; RedZone = NFL)
  show top right, 2 rows of up to 4 (7 + "+N more" past 8). Streams first, then start time. A tile with a stream ("▶")
  switches: this player and its backup browser stop, then the best-ranked list's stream opens on the loading screen.
  Worked out off the main thread from cached lists and scores (no list reads); scores refresh while the menu is open
  (ScoreFeed: 30 s when live). Redrawn only when a tile changes; focus kept. Up from the buttons enters the tiles,
  Down from the bottom row returns to Pause. Report line "Switching game from the player menu: …".
- **Network engine.** The player fetches through Chrome's network engine (Cronet). If a stream never starts with it
  (stall), it retries once with Android's standard networking. A 403 on standard networking switches back to Chrome's
  engine for that page load (many stream servers refuse non-Chrome clients; the checker always uses Chrome's engine,
  so a passing check then a 403 on refresh is the tell). A reconnect's fresh page load starts on Chrome's engine again.
  **Split by host** (`SplitDataSource.kt`, pure logic in `NetRoute.kt`, `NetRouteTest`): `PlayerActivity.play` gives the
  player one `SplitDataSourceFactory` (under `UnwrapDataSource`). Requests to the playlist's own host keep the captured
  headers and the engine `useChromeNet` says; requests to any other host (video pieces from a CDN) get only Referer,
  Origin, Accept-Language, the three low-detail `sec-ch-ua*` hints and a cross-site Sec-Fetch set if the capture had
  one (`NetHeaders.forOtherHost`); Cookie, Authorization and every other captured header stay with the playlist host.
  The `NetRoute` (kept while the source address stays the same) holds the playlist engine and a per-host engine for piece
  hosts. Pieces read with a 30 s read timeout (connect stays 15 s so a dead host fails before the 20 s stall check);
  playlists 15/15. A stall check that finds piece bytes arrived in the last 8 s waits 10 s and looks again (max 3 times,
  line "No picture after 20s, but a video piece is still arriving (N bytes so far); waiting a little longer"): recover
  only when no bytes come.
  **Piece-only failures** (`PieceWatch`): for one host, pieces failed, none from it completed since the first of those failures (counted since the host's last success, so a wedge in mid-watch counts; `PieceWatch.resetCounters` at every `play()` clears the counts but keeps the per-host restarted/retried flags), and a playlist refresh
  completed after the first failed piece began with no playlist failure since. Checked at the stall check (1 failure)
  and when the player gives up with an error before the first picture. Steps, each once per host: (1) restart Chrome's
  engine (`ChromeNet.reset`, off the main thread: a fresh engine with QUIC off built from the provider's builder, falling
  back to the default builder; each `SplitDataSource` rebuilds its Chrome downloader at the next request when `ChromeNet.engine` changed (so a player already running moves to the fresh engine); old one shut down 60 s
  later (retried every 60 s, 4 tries; line "The old network engine could not be shut down"); at most once per minute; "Video pieces keep timing out; restarting Chrome's network engine", then "Chrome's network
  engine restarted (...)"); (2) the other engine for that host's pieces only ("Video pieces from <host> time out but the
  playlist is fine; retrying the pieces with standard networking" / "...with Chrome's network engine"); (3) automatic
  switch without reloading the page ("Video pieces still failing; switching to another source instead of reloading the
  page"), else the normal reconnect. A 403 on a piece host's standard networking sends only that host's pieces back to
  Chrome ("Video pieces from <host> refused standard networking; ...").
  **Playlist gone**: when the media playlist answers 404/410 on 2 consecutive requests (no success between) after the
  stream had played, `PlayerActivity` recovers at once (the normal order: reconnect, then automatic switch) instead of
  waiting for the stall check or the timeout error; line "The stream's playlist is gone (server said 404); getting a
  fresh one"; once per play (`goneHandled`), and the recovery releases the player so the later error never fires.
  **Failed piece line** (max 4 per source): "  Piece from <host> failed (<engine>): no bytes after 15.0s | N bytes, first
  after X s, failed at Y s[, server said NNN], <exception>". The protocol (h2/h3) is not exposed by Media3's Cronet data
  source, so it is not logged.
  **Refuses standard** (`ServerMemory`, per host, same 7 day expiry, JSON "rsAt"): set when a 403 hits standard
  networking. Such a host never gets standard networking for its playlist (stall branches skip it, line "This server
  refuses standard networking; not trying it") nor for its pieces, and a remembered "standard" engine is ignored for it.
  Not yet tested on the TV.
  **Dead servers (broken TLS)** (`DeadHosts.kt`, `BrokenTls.kt`, `DeadHostsTest`; build after 261, not yet tested on the TV). A server
  whose secure connection fails (Cronet `net::ERR_SSL_*`, or an `SSLException` on standard networking; `DeadHostText.brokenSecureLink`)
  is remembered in `DeadHosts` for 5 min (preferences, survives restarts). Many sites share piece servers (e.g. one `a.newvegan…`
  server behind three sites), so: `Verifier.check` fails a candidate at once when its host is dead ("its server is down (secure
  connection broken)"), stops trying other referers on a broken handshake, and after a pass probes one level down (first quality
  level of a master, then the first 16 KB of the first piece, 1.5 s timeouts); only a broken handshake fails it ("its video comes from
  <host>, which is down…"), a timeout or HTTP error leaves it to the player. `StreamSniffer` drops such sources
  (`DeadNotes.isDownSource`; "Every stream here uses a server that is down"). The player's `NoRetryOnBrokenTls` load-error policy
  doesn't retry broken handshakes (Media3 default retried 3× over ~3 s). `handleError` records the failing host ("<host>'s secure
  connection is broken…" / "<host> is still down…"); `pieceOnlyProblem` skips the engine restart and other-engine retry for a dead
  piece host and switches source; `recoverSlow` stops reloading when the same dead server comes back after a reload ("Not reloading
  the page again…") and auto-switches. Hosts seen in checks in the last 30 min (max 30) get one HEAD handshake when `MainActivity`
  opens (`DeadHosts.recheckOnStart`; "Start-up server check: N down, M back up").
  When every candidate in a `Verifier.rank` is on a remembered-down server (`DeadHostText.allRemembered`), the memory is
  ignored for that run (prechecked results dropped, playlist + probes fetched for real): "Every stream here is on a server
  remembered as down; trying them once anyway" (2026-11-21, not yet tested on the TV). Failure lines carry
  `DeadHostText.sslReason`: "the TV doesn't trust its certificate [ERR_CERT_…]", "no security version the TV and server
  share", or "the secure handshake failed [code]".
  **Refused pieces (403)** (`Refusal.kt`, `RefusalTest`; not yet tested on the TV). A refusal (401/403/451) is a server decision, not a
  timeout: `PieceWatch` counts refused and HTTP-answered failures per piece host (`pieceFailed(h, start, status)`, `pieceRefused`,
  `pieceAnswered`). The finder's check (`Verifier.check`) records a host in `RefusedHosts` (memory, 10 min) when EVERY referer got an HTTP
  refusal (a try that timed out means no record). Before the first picture, a refused piece from a host other than the playlist's:
  `Refusal.cycleReferers` stops the per-error referer cycling when `RefusedHosts` already knows the host (otherwise the referers are still
  cycled, then the same stop); `PlayerActivity.refusedPieces` then logs "Video pieces from <host> are refused (server said 403); this
  stream can't play here", marks the source dead, remembers the host and `refusedUrl`, and follows `Refusal.afterRefusal`: the next
  playable source ("Trying the next source"), else the automatic switch (other sites' streams, backups), else ONE page reload only when
  the finder had not seen the host refuse, else "Not reloading the page: its stream would be refused the same way" and the site player.
  No engine restart (the restart line is only for pieces with no HTTP answer at all, and now names the host: "Video pieces from <host>
  keep timing out (no answer); restarting Chrome's network engine"), no standard networking and no clean browser player
  (`Refusal.cleanPlayerCanHelp`; `recoverSlow` goes to the site player when `refusedUrl` is set). In `reconnect`, a reload that hands back
  only the same stream (same address ignoring the query, `Refusal.sameRefusedStream`) logs "The page keeps giving the same refused
  stream; trying other sources" and goes to the automatic switch, else the site player. Pieces that were refused only AFTER the stream
  had played (`playingSince != 0`) keep the old recovery (reconnect / renewal). `refusedUrl` is reset at a new session and at the first picture.
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
  `app/src/test/js/links/check-source-switch.js`, `app/src/test/js/check-list-expand.js`,
  `app/src/test/js/links/check-page-sources.js`. Run them whenever an asset script changes.
- **Unit tests** run in CI on every branch (HeadersTest, CleanProxyTest, SourceLinksTest, HomeFeedTest,
  PngTextTest and others).
- **Crash and freeze recorder** (`StreamLinkApp`, `CrashRecorder`, pure text in `CrashText`). An uncaught-exception handler
  writes a compact record (exception, cause chain, first ~25 frames, all `com.streamlink` frames) to a file at once. On the
  next start the app reads Android's own exit record (API 30+: crash, native crash, ANR, low memory, killed...) and pins
  lines at the top of the report (they survive a new play): "Last exit: <reason> at <time>, was <importance> (<description>)",
  for an ANR the main thread's stack (`  at ...`), then "Crash: <exception>: <message>", "Crash thread: <name>, at <time>",
  stack lines `  at ...` and "Caused by: ...". Each exit is reported once (last timestamp kept); the crash file is deleted after
  reporting. Also "Memory: system is low (level N)" (once per level per minute, from level 10 up, not UI-hidden).
  Not yet tested on the TV.
- **Report:** the in-app log the user copies from TV or phone. Look for "Page sources:", "Stream went down", "List: pressed" lines, and
  Verifier failure details (size and first characters of the response).
- **Timing line** (`Timing.kt`, pure formatting tested in `TimingTest`): one "Timing: ..." report line per foreground play
  attempt showing seconds since Play for: browser ready (and how long creating it took; notes "first browser since the
  app opened" for the first hidden browser of the app process), page loaded, tapped, stream seen, checking, checked,
  player, picture, then "path: full search / head start / remembered address / site player". Reset by `Report.start`;
  each mark keeps its first time; written once (on the first picture, or when handing off to the site player). Only
  foreground runs mark: Prefetcher runs, light runs and the player's reconnect/renew sniffs do not (`StreamSniffer.timed`);
  a new link opened while the player is in front is a new attempt and does mark. Marks never set are left out.
  Not yet tested on the TV.
- **Save sample:** zip with list page and stream page snapshots plus the log. The stream page comes from the player
  search when it found nothing, or from the links search; its info.txt line says which and what was tapped. Playlists
  the site player loaded are kept too. May be sanitized; words are examples only.
- **Site player lines** in the report say whether the video really plays and whether the page loaded a playlist the
  app can see (if it did, the hidden search should have caught it: a finder bug, not a site limit). "A possible
  playlist" lines mean only a disguised address (no .m3u8/.mpd) was seen; the finder checks those by content.
- **Recipes** are read from the build's own branch (`sports` for the app in use).
- **Built-in browser:** with the user's permission, inspect a live site's frames and network requests from the
  desktop app's browser pane.

**Engine restart rules (not yet tested on the TV).** `Verifier.rankWithRestart(allowRestart)`: light/quiet runs (backup keeper, access-code renewal, prefetcher) never restart Chrome's engine (`allowRestart = !light && !quiet` in `StreamSniffer`). When all candidates are silent and the engine was restarted less than a minute ago, they are marked final at once (no unchecked play; line "Still no answer from the stream server (the engine was restarted a moment ago)").
