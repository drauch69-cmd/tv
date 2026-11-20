# StreamLink — decisions and open items

Running log. Update it in the same session a decision is made or an item opens or closes.
Newest open items first. When an item closes, move it to "Decisions" (or delete it if it no longer matters).

## Open items

- **Player pill: ping instead of download speed** (user's go, 2026-11-20 03:58; on `sports`, CI only, NOT tested on the TV).
  The old ↓ number (ExoPlayer's bandwidth estimate) froze when the stream dropped and only measured this stream's server.
  The pill's only job is "is it me or the stream?", so ↓ became ⇄ ping (quick handshake to Cloudflare/Google every 3 s,
  a few KB/hour); ▸ needs and dropped frames unchanged; color now from the ping alone. Stall report lines show the ping.
  Rejected: speed tests (data use), Wi‑Fi link speed (only the TV↔router hop), timing ESPN calls (too small to measure
  speed). Check on the TV: pill reads a sensible ping (~10–60 ms) and turns yellow/red when the connection struggles.

- **Referer order + habit taps** (user's go, 2026-11-20 03:08; on `sports`, CI only, NOT tested on the TV). Trigger: CrackStreams
  NFL Network report (build 248): video pieces 403 on every referer, but every referer tried was an ad/tracker frame
  (crwdcntrl, yieldz); the page itself never fit in the 5 tries. Stream was also dead in desktop Chrome, so this may
  not make it play. Fixed: page/player-frame referers first; crwdcntrl.net and yieldz.gs blocked as ad hosts; a
  remembered ad-host referer is ignored (closes the "old tracker referer stays remembered" item). Habit: a player-spot
  habit now fires when the player shows (was waiting ~4 s for ads to finish loading, so a normal tap went first) and its
  taps are counted (the saved count was off). Check on the TV: a report's 403 line lists the site's own host first.

- **App logo** (user's go, 2026-11-20 04:48): the user's interlocking-S mark, traced to a vector, blue (#4DA3FF) on
  dark: TV home tile (mark + "StreamLink"), app icon, home top bar, phone page title and tab icon. Untested on the TV.
- **Polished look** (user's go, 2026-11-20 02:27; mockups on the "StreamLink UI mockups" canvas). Built on `sports`,
  unit-tested in CI only, NOT tested on the TV: team logos from ESPN, dark-fade cards, stream count chip ("0" greyed),
  featured live game over a per-sport photo with a blue Watch button, roomier home spacing, sport-screen rows with logos
  and the score in the middle, player overlay line with download speed vs what the stream needs. Photos: the user's own,
  uploaded from the phone (Lists tab, "Home photos") with tap-to-frame; kept on the TV only because the repo is public.
  Skipped on purpose (performance on the Onn box): blur, big shadows, video previews, side menu, grow-on-focus. FPS
  counter / speed test on home: not wanted (player line instead). Check on the TV: logos appear and stay on redraws,
  home scrolls smoothly, Watch plays, photo crop matches the phone's dashed box, speed line looks right while playing.
  User TV feedback (2026-11-20 02:53): logos OK; home not seen yet (no games). Follow-up built: speed line became a
  colored pill at the bottom right with a measured "needs" number (streams rarely announce it); sport rows left-align
  both teams' names (user let the agent choose names over 3-letter codes) and drop the TV channel. Untested on the TV.

- **List reading performance A+B+C + recipe zone** (user's go, 2026-11-19 23:28; Sports build 244, unit-tested locally and in
  CI, NOT tested on the TV). One shared list reader for the app (`ListReads`); fresh lists skipped (2.5 min on open);
  re-reads every 5 min, 90 s near start times; list rows updated in place; reader blocks pictures/fonts/media, starts
  looking at first paint and finishes when the events stop changing; Save-sample snapshot only after failed reads.
  Recipes: a site entry may hold only `"zone"` (times with no label read in that zone); the saved recipe copy is used at
  once and refreshed in the background after 10 min. Check on the TV: a sport screen with 4+ lists scrolls smoothly
  while reading; a list screen left open 10 min doesn't flicker or move focus; lists show the same events as before;
  after a failed read Save sample still has the list page; most "List: read N events … in X s" lines well under 10 s.
  Not built from the site-recipes handoff (2026-11-19): sports already has `SiteHabits` (source button per site) and
  `ServerMemory` (referer per server), so the playbook's button/referer parts would duplicate them; "remember which
  player played" deferred (user may approve later). Buffstreams' UTC times need a recipe entry with its host + `"zone": "UTC"`.
- **Finding speed 1, 2 and 4** (user's go, 2026-11-19 23:52; Sports build 245, unit-tested, NOT tested on the TV). (1) and
  (2) were already built in Sports build 224 (early finish when an adaptive stream passes its check; page sources read
  after the hand-over); the offer was made from the older build-211 timing. Left as is: single-quality streams still
  wait the 1.2 s for siblings (could pick a lower quality if shortened; small gain). (4) built: shipped habits in
  `recipes.json` (see ARCHITECTURE "Shipped habits"); copy the report's "Habit for recipes.json" line into "sites".
  Check: play a site twice, confirm the line appears; after a recipe ships, a reinstall still shows "Using this
  site's recipe".

- **Other games tiles in the player menu + new button order** (user's go, 2026-11-19 22:25; on `sports`, CI only, NOT
  tested on the TV). Top-right tiles of other live games of the same sport with scores; pick one with a stream to
  switch (best-ranked list's stream; Back afterwards goes to the list). Up to 8 tiles (2 rows of 4), more = 7 + "+N".
  Games with no stream shown dimmed. No preloading of other games while watching (performance on the Onn box).
  Buttons now: Pause/Play, Audio & quality, Source, Jump to live, Try another player ("Back to list" removed; the
  remote's Back key does it). To check on the TV: tile look and size on a busy Sunday, D-pad Up/Down into and out of
  the tiles, switch speed, no stutter when the menu opens.

- **Live games rework (2026-11-19, on `sports`):** user reports it works on the TV (21:27). Still to see: the RedZone
  card on a Sunday. Open: the "2+ NFL games live" rule for RedZone was the lead's choice, not yet confirmed by the user.
- **Rename lists (user's go, 2026-11-19 21:27; on `sports`, CI only, NOT tested on the TV):** "✎ Rename" chip on each list
  on the phone's Lists tab (prompt; blank/Cancel = no change), `POST /lists/rename` (id, name; same-origin only),
  `SavedLists.rename` (max 60 chars, false for unknown id/empty name); TV redraws home and "Your lists". No rename on
  the TV itself (typing with the remote). To check: rename on the phone, name updates on the TV tiles and cards.
- **Build 234 on the TV (2026-11-19 13:41):** the broken CrackStreams NFL Network stream (pieces 403) gave up in
  15 s and handed to the site player (was ~3 min). Confirmed working. Offered: treat same-named 24/7 channels in
  other lists as backups/automatic-switch targets; **user declined** (rarely uses 24/7 streams). Closed 2026-11-20: the old
  tracker referer is now ignored (see "Referer order + habit taps").
- **Build 234 on the TV (19:48), ~6 h uptime:** NFL game on ntv played at 9.7 s, no engine wedge after ~6 h open (the
  wedge earlier showed after ~3.5 h on build 227); "Other sites: 6 stream(s)" offered as backups. Seen: the ntv list's
  start time read as "Tomorrow 1:15 AM" for a 7:15 PM CT kickoff (the site's time is likely UTC with no label; ESPN
  status still governs Live) -- open, not yet looked at.
- **Build 234 on the TV (13:50), off-air channel:** offline verdict at 15.6 s (was a 90 s reload loop). Fixed in Sports build 235 (user's
  go 13:59, NOT tested on the TV): a channel (no start time) gives up on the first offline verdict instead of searching
  again (~30 s saved); an offline/refused stream no longer counts as a learned-habit miss (the habit was wrongly dropped).
- **Build 234 on the TV (13:45):** confirmed working: early finish + learned habit (picture at 10.8 s on ntv), backup
  ready 62 s after start, and picking that Source used the ready backup with no search (picture ~2 s after the pick;
  user: "source switching was crisp").
- **Review round after build 229, released as Sports build 234** (user's go, 2026-11-19 13:18; on `sports`, compiled and unit-tested in CI, NOT tested
  on the TV). Trigger: a broken CrackStreams stream (pieces 403, site's own player fails too) took ~3 min to give up.
  Refused pieces now stop at once (no engine restart, no referer cycling, no reload of the same refused stream; other
  sources, else site player; ~15 s); only page/player-frame referers are remembered. A fresh reviewer (read-only) then
  found 15 interaction bugs; fixed as small changes: engine restart no longer kills a playing stream (downloaders rebuild
  on the new engine; light/quiet runs never restart), old engine shutdown retried, mid-watch piece wedge gets the restart,
  silence right after a restart is final; stuck renewal flag, recovery cancelled when leaving/switching, no double
  recovery during a backup check, one empty reload then switch/site player, empty renewal tries backups first, backup
  list excludes the playing address, stall wording; checks stop after 2 not-found answers and judge cut-off checks on
  answers so far (slow 404 = offline), streams refused on every referer are not played (shown as offline on screen),
  site memory misses only when nothing was seen, lifetime samples only for real refusals.
  Deferred (bigger design, after TV confirmation): one failure classifier + one recovery ladder in the player; checking
  one video piece in the Verifier before playing; a lease-based owner for the shared Chrome engine.
- **Fixes after build 227 TV reports** (user's go, 2026-11-19 12:33; on `sports`, compiled and unit-tested in CI, NOT
  tested on the TV): offline verdict ignores fetches that threw (an off-air channel was played and reloaded 3x instead of
  "offline"); failed early checks are reused (no double check); when every check gets no answer on Chrome's engine (the
  wedged-engine signature after hours of uptime) the engine is restarted and the checks repeated once, and silent sources
  are no longer played unchecked; same restart for a silent playlist before the first picture; a Source the user picks
  uses the keeper's fresh backup (quick-check, no search); the doubled "Opening … as its own page" line was only logged
  twice (removed). Site habits are per site (domain), not per page (user asked).
- **Backups while watching + parallel recovery + offline streams, Sports build 227** (user's go, 2026-11-19 08:27/08:34; on `sports`,
  compiled and unit-tested in CI, NOT tested on the TV): per-server address lifetime learning (SHORT/LONG/unknown);
  BackupKeeper refreshes up to 2 backups of the watched game (other-site entries first) with light quiet runs only while
  playback is healthy (>= 10 s ahead, or 60 s calm with >= 3 s ahead), every 10 min (long/unknown) or 2/3 of the learned
  lifetime, min 3 min (lead raised the helper's 90 s to keep it light), auto-off for the watch if a stall follows a run;
  on a drop a fresh backup is quick-checked and switched to, else reload + known-address checks race (first live answer
  wins). A 404/410 address is never played; a page whose only stream is 404 is "offline" (other sources, else site
  player); a reload that returns the same dead address stops reconnecting. First TV check: the keeper's hidden browser sits
  under the video (black player background) -- look for any page showing over or around the picture. Open: a 403 drop on an
  expiring-code server still renews first (no instant switch).
- **Batch after build 211, released as Sports build 224** (user's go, 2026-11-19; on `sports`, compiled and unit-tested in CI, NOT tested on the TV):
  (1) finder: early finish when an adaptive/DASH stream passes its check, page sources read after the player starts,
  head start uses remembered addresses, list reads paused while a stream is found or plays; (2) player network:
  per-host split downloader (pieces from another host get only cross-site headers, 30 s read), piece-only failure
  rule (restart Chrome's engine with QUIC off, then pieces on the other engine, then switch source without reload),
  servers that refuse standard networking are remembered, playlist 404/410 twice = recover at once, stall waits
  while a piece is still arriving; (3) learned site habits (tap spot, source button) replayed right after load;
  (4) "Live now" narrowing + "Other" games only, per the user's plan (see the next item).
  Cause found for "every ntv channel stuck on the loading screen until a force close": the shared Chrome network
  engine kept a dead connection to the video-piece CDN (playlists fine, pieces timed out); a force close fixed it.
  Next: the user plays ntv channels and sends reports; look for the new piece/engine lines and "Timing:".
- **"RedZone" is a deliberate exception** to "never key on page words" (user, 2026-11-19): the user's own viewing
  choice for the Live games row, not detection. (The earlier editable "Live now" word list was removed the same day.)
- **Next step (agreed in principle, 2026-11-19, build after Sports build 224):** recovery both ways at once (keep the
  stream trying while backups are readied; first picture wins); learn per server how long saved addresses last (ntv codes
  die within minutes; skip the shortcut where they always fail); while watching, keep fresh addresses for the BACKUP
  sources of the same game (user, 08:16: the page's other sources and other sites' streams for that game, not the
  playing one): light runs, one at a time, top 1-2 backups only (best-ranked list first, a different site/CDN preferred),
  interval from the learned lifetime, only with a healthy buffer, auto-off for the watch if it ever causes a stall; on a
  drop, switch to the freshest backup in 1-2 s. No idle background refresh of other games.

- **Speed plan, step 1 built** (user's go, 2026-11-19; on `sports`, compiled and unit-tested in CI, NOT tested on the TV).
  Diagnosis: most of the wait is the site itself (page scripts, player taps, other sites' players needing 10-15 s),
  then the Onn's slow processor running the page; network speed matters least. Built: (1) one "Timing:" report line
  per play (page loaded / tapped / stream seen / checked / picture, path, first-browser cost); (2) last working
  address per page, re-checked in parallel with the page load (3 s limit), plays at once if still live; (3) per
  stream server, the referer and network engine that worked are tried first. Released as Sports build 211. First TV reports (build 211, ntv.st 24/7 channels, 2026-11-19): remembered address worked on one server (picture at 1.9 s vs ~11 s full search) and failed on two others whose path codes change per page load (403 on all referers, no time lost: it runs beside the page load). Full search split: page loaded ~1 s, tap 1.5-4.4 s, stream seen 6.5-7.3 s, ~1.2 s sibling wait before checking, check <1 s, ~1.5 s page-sources read before the player starts, picture ~0.8 s after the player. Browser creation ~0 s (engine already warm), so warming it and skipping the outer page would save little on this site. Offered next: check the first stream at once, read page sources after the picture, remembered address for head starts. Next: the user plays 3-4 games
  (reopen one) and sends reports; read the Timing lines.
- **Speed plan, step 2 (waiting on step 1's timing reports):** site recipes learned automatically from successful
  searches (frame depth, source button by position, taps needed, ad-popup first tap, usual seconds), replayed on the
  same page load with the normal search carrying on if they fail; one recipe setting "open the player frame
  directly" (outer page as referer; ~8 s then full page; 2 failures = never for that site). Build direct-frame only
  if Timing lines show the outer page costs several seconds. Also discussed, not planned: keeping the browser engine
  loaded at app start (small, safe); reusing one hidden browser (rejected: wrong-stream and stuck-browser risks for
  ~1 s); pre-finding only the single most likely game (big gain, memory cost); a PC-side finder (addresses may be
  tied to the browser/session that found them).

- **College basketball + Offline marks** (2026-11-19, Sports): built, unit-tested in CI; NOT tested on the TV. Check:
  tag a list "College basketball" on the phone; a live college game shows H1/H2 scores and ESPN decides its Live/Final;
  the Live games row orders NFL, college football, NBA, college basketball; an event whose stream is dead shows
  "Offline" for 15 min (report line "Marked … Offline"). Not done: "Started" instead of "Live" for unchecked sports
  (option C, not chosen).

- **Ranked lists** (2026-11-19, Sports): built, unit-tested in CI, phone picker checked in headless Chrome; NOT tested
  on the TV. Check: the phone Lists tab "Rank" picker; tiles and game rows show "#N"; streams in rank order.

- **Sources rework, show-more, preferred list** (user, 2026-11-19; built on `sports`, compiled and unit-tested in CI,
  page scripts checked in headless Chrome and against the live ntv.st page in the desktop browser; NOT tested on the TV).
  What to check on the TV:
  - ntv.st list: games past the first 12 per sport now appear (report line "List: pressed N 'show more' button(s)").
  - ntv.st game: the Source panel shows "Source 2 … · this site" (line "Page sources: 1 numbered address(es)"), and
    other sites' streams for the same game ("<list> · other site"), preferred list first.
  - Automatic switch when a stream dies ("Stream went down; switching to …"); max 3 per watch.
  - "★ Preferred" chip on the phone Lists tab and the TV list screen; preferred streams first on game screens.
  Known gaps: after an automatic switch the overlay says "Back" instead of "Back to list"; items not in a sports
  folder (no matched game) only get their page's own numbered sources; in-page sources reachable only by buttons
  with no address (e.g. Reed's 9 buttons) are no longer gathered.

- **403 mid-play after a "standard networking" fallback** (2026-11-19, investigated in the desktop browser, not on
  the TV). Report: NHL Network page; a `bharat` stream played ~20 s, then every playlist refresh got 403; the
  `netanyahu` server got 403 on every referer. In the desktop browser the same stream (address
  `/secure/<code>/0/<expiry>/NHLN/...`, expiry ~24 h ahead, no query) refreshed fine every ~7 s for 30 s with
  only the page's origin as referer: the code does NOT expire quickly. Likely cause: after the first server
  stalled, `PlayerActivity` set `useChromeNet = false`, and that sticks for the whole session (only reset when a
  new stream starts, not on `reconnect`). The checker still uses Chrome's engine (`ChromeNet.open`), so checks
  pass and "Player reused the checked playlist" plays, then the player's own refresh over standard networking gets
  403 (server likely refuses non-Chrome clients). Also: `renewAccess` can never fire here (`hasQuery` is false for
  path-coded addresses), and 403s with all 6 referers waste ~20 s per source. Fix built (user's go,
  2026-11-19): a fresh page load on reconnect resets to Chrome's engine; a 403 on standard networking switches back
  to Chrome's engine (report line "Server refused standard networking; going back to Chrome's network engine") and
  keeps it for that page load instead of cycling referers. Not tested on the TV. Next: the user plays a stream
  from this site and sends the report. Not done: `renewAccess` for path-coded addresses (low value; codes last ~24 h).
- **NBA tile fixed** (user, 2026-11-18 03:59, Sports build 197): opens and works. The focus outline change in 197 looked
  worse; the user chose a clean focus style: no growing, a crisp ring drawn on top of the card's content (inside its
  bounds), brighter fill; the 197 clipping changes are reverted. In progress.
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
    picture + 1.5 MB DOM snapshot after every read: block those, snapshot only on failure [reader part built 2026-11-19]; (5) player buffer has no
    byte cap (60 s max, ~45-75 MB) and STALL_MS 20 s can restart a slow-but-alive stream: cap ~40 MB, recover only when
    no bytes arrive.
  - Batch B (menus): sport screen matching on the main thread and minute-tick full rebuilds on all screens; ScoreMatch
    recomputes team names per item and its cache thrashes; phone page polls every 1.5 s with a thread per request;
    sport screen re-reads lists every 2 min. [list-screen rebuilds and the sport re-reads built 2026-11-19]
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
- **List reading speed** (offered 2026-11-17): built 2026-11-19 as part C of the list performance batch (see the top
  open item); the "plain text first" idea was not built.

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

- **Scores load today first, in parallel** (user, 2026-11-19, option A, `sports` branch): today's NFL, college and
  NBA are fetched at once and each draws as it lands; the next two days follow. Each refresh writes a "Scores
  timing" line (per league and total) to the What happened? report, kept across plays. Untested on the TV.
- **Lead works directly; helpers only for big split jobs** (user, 2026-11-19, to save tokens). Replaces "brief a
  smaller-model helper per task".
- **CLAUDE.md kept short to save tokens** (user, 2026-11-19 21:17): it loads on every step, so mechanism detail moved
  to `docs/ARCHITECTURE.md` (read by section on demand). New detail goes there, not into CLAUDE.md.
- **Home "Live now" row removed; one "Live games" row** (user, 2026-11-19 ~20:05; replaces the build 224 "Live now
  narrowing" and the channel-word list). RedZone is the only channel that gets a Live games card (first, while live);
  other always-on channels live in the 24/7 tile. NFL games come before other sports in Live games.
- **Sources come from the app, not from pressing buttons** (2026-11-19): the background link run is retired. The Source
  panel = this page's playable sources, its own numbered addresses (`?source=N`-style, read from the page), and other
  sites' streams for the same game. A stream that goes down switches automatically (after reconnects fail, max 3 per
  watch, dead sources skipped for 5 min). No blind `?source=N` guessing on pages that don't show it.
- **College basketball** (user, 2026-11-19): ESPN scores, its own Sports tile; lowest in the Live games row
  (NFL, college football, NBA, college basketball).
- **Offline marks** (user, 2026-11-19): an event whose stream is dead at the source after its start shows "Offline" and
  leaves the Live rows for 15 min, so it isn't picked again; opening it still searches.
- **Ranked lists** (user, 2026-11-19, replaces "one preferred list"): each saved list can have a rank (#1 best);
  streams follow it everywhere (game screens, home rows, 24/7, phone Live now, the player's other-site sources and
  automatic backup switching). Unranked lists come after all ranked ones, in saved order; new lists start unranked; a
  taken rank pushes the others down. Ranks are set only on the phone (user: no TV control needed); the TV shows them.
  The old preferred list became #1. The playing page's own numbered sources stay first in the Source panel. Never a
  site name in code.
- **"Show more" pressing on index pages** (2026-11-19): only on pages that have such a button, max 4 presses; category
  tabs are not pressed.

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
- **Lint (2026-11-20, sports):** Android lint stays non-blocking. Fixed the real items (obsolete version checks,
  unused files, library bumps: Cronet 18.1.1, ZXing 3.5.4, WebKit 1.18.0, test org.json), turned off the
  translation-text checks (SetTextI18n, HardcodedText), and marked the 9 StaticFieldLeak warnings as reviewed
  (application context only; screens detach in onDestroy). Left as-is: fixed landscape orientation, target 34,
  small layout warnings. ktlint (~7,900 formatting-only items) was not mass-applied on purpose, to keep Sports/Test
  diffs readable. The manual Lint workflow now reads the right report file. Untested on the TV.
