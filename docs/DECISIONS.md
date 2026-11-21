# StreamLink — decisions and open items

Running log. Update it in the same session a decision is made or an item opens or closes.
Newest open items first. When an item closes, move it to "Decisions" (or delete it if it no longer matters).

## Open items

Trimmed 2026-11-20 (cleanup): the long per-build notes were cut to one line each. The full older text is in this
file's git history (commit 3680843 and earlier).

### Built, still to check on the TV
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
- Live cards that show only a game clock (Reed college games) get no start time.
- Items with no matched game only get their own page's numbered sources; address-less source buttons (Reed's 9) are
  not gathered.
- Links 2–4 on the old test site: the player's signed request never arrives (Cloudflare frame); they stay as
  "site player" entries.
- `renewAccess` doesn't cover path-coded addresses (low value; those codes last ~24 h).

### Offered, not approved
- Player design: one failure classifier + one recovery ladder; check one video piece before playing; one owner for the
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
