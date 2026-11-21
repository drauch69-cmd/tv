# StreamLink — decisions and open items

Running log. Update it in the same session a decision is made or an item opens or closes.
Newest open items first. When an item closes, move it to "Decisions" (or delete it if it no longer matters).

## Open items

Trimmed 2026-11-20 (cleanup): the long per-build notes were cut to one line each. The full older text is in this
file's git history (commit 3680843 and earlier).

### Built, still to check on the TV
- **City photos search the city name only** (2026-11-21, user): "Chicago" instead of "Chicago skyline"; cities saved
  under the old search are searched again once. Check: photos still look like the city, not random street shots.
- **Home fit + Options + Leagues** (2026-11-21, user's go): "Scan the code" line gone, tighter gaps, featured card 260 dp
  (~3.3:1; user's pick) with bigger logos 42, names 26, scores 30, LIVE/league/clock 13-15; Live games and league tiles
  222x136 (were 232x146) so Live games shows without scrolling. "Send a link" -> "Options" panel (Your lists, Check for
  updates, Head start, Close on the left; QR on the right); "Sports" -> "Leagues" (top bar, row, sport screen label).
  Sport screen side card: same look as home (photo behind the whole card, dark from the bottom, 300 dp tall) and "Next
  up" now looks a week ahead (it was 12 h, so Sunday's games showed no card on Friday night). Check all on the TV.
- **City photos + taller card + Sports button + demo games** (2026-11-20, user's go): the featured card shows a photo of
  the game's city from Unsplash (choice A: ahead of the sport photo; the sport's fade still applies), searched once per
  city, widest of 10 results, framed a little below the middle. Phone: tap to frame and "Next photo" per city. Card was
  345 dp tall (now 260, see above); top bar "Sports" jumps down to the Sports row; "Demo games"
  checkbox on the phone fills home with made-up games (off after restart). Key in Actions secret `UNSPLASH_ACCESS_KEY`
  (user added it); production review applied for, not needed. Check: photos appear for Chicago/London/Laramie demo
  games, framing, credit readable, Next photo, focus/scrolling on home, Sports button.
- **Start-up crash fixed** (2026-11-20): the data-file build crashed at start (a recipe placeholder pattern Android's
  regex engine refuses: unescaped "}"; JVM tests passed). Placeholders now read by hand; recipe/data-file errors are caught
  (Throwable) and fall back to the page read. Lesson: no regex with a literal "}" in app code.
- **Data-file recipes + live-card fix** (2026-11-20, user's go; handoff claude/reedstreams-handoff.md): reedstreams and
  ntv lists read from the sites' own data files (every reedstreams sport list, filtered by its address; ntv's 15 s browser
  read gone); page fallback if the file fails. Event finder: two same-class names beat a league label styled like them;
  a game clock ("0:55 - 3rd") or a "liveDot" class marks live. Check: "data file" lines well under 2 s, all games
  present, live college game shows both teams + Live + ESPN score, NFL vs college split right on reedstreams.
- **Home redo speed** (2026-11-20, user's go): build 271 showed featured at 11.8 s (was 20-33) but every home redo
  took 3-7 s, about every 10-30 s. Now matches are kept across score updates and one score round = one redo; a site
  that can't be opened either way is re-tested in a day (streameast was re-tested on every read). Check: "Home: worked
  out" lines well under 1 s after the first, featured card time, menus smoother while scores update.
- **List and home batch** (2026-11-20, user's go on all 5): home skips out-of-date redos + "Home: worked out in X s"
  line (featured card took ~20 s after scores); headings-only quick reads fall back to the browser (thetvapp's empty
  page showed 3 junk rows); lists failing/empty 3x in a row wait 30 min then 2 h; timer re-reads only when the list or
  its sport has something live or within 2 h (else every 2 h); quick-read test ignores time/Live for ESPN games (user:
  ESPN drives those), so roxiestreams should pass. Check: featured card time, "Home: worked out" lines, roxie passes,
  reedstreams/streameast "holding" lines, thetvapp empty page has no junk rows.
- **Quick reads, first TV results** (build 271 report): crackstreams 0.4–0.6 s, thetvapp 0.5–0.7 s, buffstreams pass;
  ntv needs the browser (list built by scripts, ~16 s).
- **Quick list reads** (2026-11-20, user's go on "plain download first"): each site is tested once (quick read + full
  read, compared); sites that match are read from their downloaded HTML only, with the browser as fallback. Check:
  "the quick way finds the same events" lines per site, quick reads well under 2 s, events and Live marks unchanged,
  home fills sooner after a fresh start. Offered, not built: hold re-reads ~30 s after start; back off failing sites
  (streameast SSL error retried 3x in 26 s); home timing line; skip sports no list covers.
- **College basketball scores load last** (2026-11-20, user's go; home took ~17 s to show games): NFL, college football
  and NBA download first, then college basketball. Works with the other session's saved scores + loading screen.
  Check: "Scores timing" shows two "today" lines and the loading screen clears sooner. Offered, not built: an always-on
  background service (advised against: RAM/CPU on the Onn); a 15-min background refresh.
- **Featured photo fills the whole card** (2026-11-20, user's choice over a mini-card preview with a photo beside the
  text): the photo covers the full card, game text sits on top, fade runs from the card's left edge. The TV reports the
  card's real shape to the phone (about 4:1 on the Onn, was 3:1), so the dashed box and the preview (now with stand-in
  text) match. Re-crop photos on the phone. Check on TV: home card matches the phone preview.
- **Home polish batch** (2026-11-20, user's go): fade sliders per photo on the phone (width + darkness, live preview;
  home card only, sport-screen side card keeps the fixed fade); last scores saved to disk and put back on start (today's
  up to 15 min old, coming days 3 h); "Loading games…" screen on a fresh start until first scores or 3 s (not on first
  run with no lists); Left/Right stops at row ends on home (no wrap); sport-screen side card lined up with the first
  game card; featured cards: live NFL/college football always beat NBA/college basketball. Check all on the TV.
- **Home opens on the featured card** (2026-11-20, user): focus went to the Sports row because the card was drawn
  after the first focus pick. Until the user presses a key, focus jumps to the featured card when it appears; keys
  are ignored while the loading screen shows. Loading logo doubled in size. Check on TV.
- **Loading screen waits for games** (2026-11-20, user's choice): it used to hide on the first scores answer (or 3 s),
  but the home redo after that takes ~3-4 s on the Onn, so an empty home showed. Now it hides once the home rows are
  drawn from non-empty scores, at most 15 s; logo + spinner, no text. Check on TV: no empty home on start.
- **Cut-off scores** (2026-11-20, user's go): home featured card showed "1" for 17. Both team lines now sized the
  same and every score spot (featured, side card, small cards) keeps room for 3 digits. Check on the TV.
- **Open: 17 s until home games show** (2026-11-20): cause found, not fixed (user will investigate later). Live games
  and the featured card only show games with a stream, so a cold start waits for the hidden page reader (one page at
  a time, ~8–10 s each) when saved pages are old. Options offered: show games from scores at once with "Finding
  streams…", read the likeliest page first, two readers at once (risky on the Onn), reading while closed.
- **Dead servers** (2026-11-20, user's go on 1, 2, 4): the stream check also tests the first quality level and video piece;
  servers with a broken secure connection are remembered 5 min across sites and re-checked when the app opens; the player
  doesn't retry them and stops reloading a page that hands back the same dead server. Not done (held): trying the other
  network engine first on SSL errors (both engines failed on the same server in the 2026-11-20 report).
- **UI + playing batch** (2026-11-20, user's go): home featured card (small dark area, soft photo fade, clock moved up,
  no stream pill, scores next to names); sport screen centered scores + side card (that sport's featured game, else
  next up); OK on a game plays the best stream, hold OK = all streams; failures move to the next stream (max 3
  switches, "Trying stream n of m"), never the site player by itself ("No working stream" screen with "Use site's
  player"; that button is also on the loading screen); next stream prewarmed 4 s into the first search; running log
  with "Download all logs" / "Clear saved logs" on the phone. Check: prewarm doesn't slow the first stream on the Onn,
  how many "Next stream warmed up" lines appear, photo crop at the new 3:1 shape (re-crop on the phone if off).
- **Featured card + Live games order by excitement, flame on games heating up** (2026-11-20): switch count on a busy
  Sunday/Saturday, focus never jumps, ESPN's live win probability actually arrives, basketball weights untested.
- **Player ping pill** (2026-11-20): reads ~10–60 ms, turns yellow/red when the connection struggles.
- **Referer order + habit taps** (2026-11-20): a 403 report line lists the site's own host first.
- **App logo** (2026-11-20) and **polished look** (2026-11-20): logos stay on redraws, home scrolls smoothly, Watch
  plays, home photo crop matches the phone's dashed box, speed pill and sport-row layout look right.
- **List reading performance + recipe zone** (build 244): a sport screen with 4+ lists scrolls smoothly while reading;
  a list screen left open 10 min doesn't flicker or move focus; "List: read N events … in X s" mostly under 10 s.
- **Shipped habits** (build 245): the "Habit for recipes.json" line appears after playing a site twice; a reinstall
  still shows "Using this site's recipe".
- **Other-games tiles in the player menu + new button order** (2026-11-19): look on a busy Sunday, D-pad into and out
  of the tiles, switch speed, no stutter when the menu opens.
- **RedZone card** on a Sunday. Its "2+ NFL games live" rule is the agent's choice, not yet confirmed by the user.
- **Rename lists** from the phone: the name updates on the TV tiles and cards.
- **Off-air channel** gives up on the first offline verdict; offline streams don't count as habit misses (build 235).
- **Backups while watching** (build 227): no hidden page showing around the picture; a drop switches to a backup.
  Known: a 403 drop on an expiring-code server still renews first (no instant switch).
- **Player network batch** (build 224): new piece/engine report lines and "Timing:" on ntv channels.
- **College basketball, Offline marks, ranked lists, sources rework / show-more / automatic switch (max 3)** (2026-11-19).
- **Standard networking 403 fix**: a reconnect goes back to Chrome's engine ("Server refused standard networking…").
- **Repeating requests as possible playlists** (CrackStreams; "A repeating request may be a playlist").
- **Empty player box pages** ("pressing source button" lines), **live scores** (college `groups=80` and ESPN's live
  clock wording unverified) and **ESPN-driven Live/Final** on every screen.
- **Crisp focus ring** and the **crash/freeze recorder** ("Last exit:" / "Crash:" lines).

### Known gaps (not scheduled)
- Site times with no zone label (Buffstreams, likely ntv: "Tomorrow 1:15 AM" for a 7:15 PM CT kickoff) are read in the
  TV's zone. Fix per site with a recipe entry: its host + `"zone": "UTC"`. ESPN still fixes matched games.
- Live cards that show only a game clock get no start time from the page (2026-11-20: the clock now marks them live and
  both team names are read, so ESPN supplies the time once matched; data-file sites get exact times).
- Items with no matched game only get their own page's numbered sources; address-less source buttons (Reed's 9) are
  not gathered.
- Links 2–4 on the old test site: the player's signed request never arrives (Cloudflare frame); they stay as
  "site player" entries.
- `renewAccess` doesn't cover path-coded addresses (low value; those codes last ~24 h).

### Offered, not approved
- Player design: one failure classifier + one recovery ladder; one owner for the
  shared Chrome engine.
- Speed: open the player frame directly (only if Timing lines show the outer page costs seconds); keep the browser
  engine loaded at app start; pre-find the most likely game; shorter sibling wait for single-quality streams;
  remember which player played.
- Performance leftovers: player buffer byte cap (~40 MB) and recover only when no bytes arrive; release shrinking
  (R8); list caches out of the main settings file; lighter phone-page polling.
- Faster fallback on sites the finder can't catch (treat "Live" as started; fall back after the first search at 3+
  misses in a row).
- Automatic retry of failed list reads after ~15 min; restyling the player's loading screen; pruning fixes never
  proven to help (needs TV reports, one at a time).

### Splitting the big files (2026-11-20, user's go: option B, one step per build, code moved as is)
- Step 1 done: the player's on-screen parts moved to `PlayerScreen`. Built in CI only; TV check needed: play a game,
  open the menu, Audio & quality, Source, D-pad through the tiles, loading screen and Show details.
- Step 2 skipped (user, 2026-11-20): the finder's habit and tapping code has no clean edge.
- Step 3 done: the decoder, tunneling and low-delay readings moved to `PlaybackSigns` (readings and verdicts only).
  The network/engine choices (pieces refused or timing out, engine restarts) stay in the player: they are recovery
  decisions, not checks, and need the recovery redesign ("Offered, not approved") to move. Built in CI only; TV check
  with step 1's.

### Cleanup (2026-11-20, user's go: 1 housekeeping, 2 dead code, 3 signing key) — finished
- Done: unused code removed (no behavior change); this file trimmed; docs say `sports` is the main line; signing key in secrets.
- Branches done by the user (checked 2026-11-20 18:56): default branch is `sports`; only `sports` and `main` remain.
- **Signing key moved to secrets** (user chose option A, same key, 2026-11-20): the user added the two Actions secrets;
  the build signs from them and checks the certificate is unchanged; the key file is gone from `sports` (still on
  `main` for the TV Cleaner, and in history). Untested on the TV: the next Sports update should install normally.

Reminder: lay out options and get a "go" before starting any of these.

## Decisions (do not undo without asking)

- **`sports` is the main line** (user, 2026-11-20). StreamLink Sports is the only app in use; `test` and the helper
  branches are retired. `main` stays only because the TV Cleaner app builds from it; StreamLink's own `main`/`test`
  releases are frozen.
- **Confirmed on the TV** (2026-11-18 to 11-19): Live games rework works; a broken stream gives up in ~15 s (was ~3 min);
  ntv game picture in 9.7 s, no engine wedge after ~6 h open; early finish + learned habit (picture 10.8 s); picking a
  ready backup Source is "crisp"; a remembered address played in 1.9 s; NBA tile opens; home speed fix "feels much
  better"; TheTVApp and Roxie streams play; disguised "list2" playlists play clean (build 160).
- **No 24/7 channel backups across lists** (user declined 2026-11-19; rarely uses 24/7 streams).
- **Site habits are per site (domain)**, not per page (user, 2026-11-19).

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
- **Never open the site's player by itself** (user, 2026-11-20): a failure moves to the next stream of the game,
  else a "No working stream" screen with a "Use site's player" button. **Picking a game plays its best stream**
  (user, 2026-11-20); hold OK for the stream list. The home Live games small cards keep their layout (user said fine).
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
