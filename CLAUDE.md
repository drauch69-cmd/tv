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
   (`assets/player-start.js`), taps the player (NUDGE_JS), blocks popups and redirects, and watches network requests
   for playlists (`StreamUrls.classify`, judged by path; addresses hidden inside other requests are extracted).
3. **Check.** `Verifier` fetches each candidate with up to 4 referers plus none, with Origin derived from the referer
   being tried (`Headers.originFor`). Only verified sources play.
4. **Play.** `PlayerActivity` (ExoPlayer). `UnwrapDataSource` strips disguises (PNG headers, pixel data, PNG text
   notes). If segments can't be decoded, fall back to the site's own player (`SitePlayerActivity`).
5. **Extra sources.** After 10 s of stall-free play, a background run finds the page's other link buttons and sniffs
   each player (light mode); verified ones appear in the Source panel.

Screens: `MainActivity` (TV-home-style dashboard; "Live now" row from `HomeFeed`, sections from `ListSections`),
`Ui.kt` (shared UI kit), `WaitingActivity` (loading screen), `assets/phone.html` (phone page: Live now, Lists,
Remote tabs; prefetch toggle).

## Key mechanisms

- **Finder speed-ups.** `SiteMemory`: after 2 failed finds on a site, its search shortens to 10 s. `Prefetcher` gives
  Live/Soon items a 2 s background head start (quiet: `Report.muted`). Light sniffs (25 s timeout) block images,
  fonts and similar.
- **Unwrapping.** `UnwrapDataSource`: PNG header strip, pixel decoding, and `PngText` (iTXt/zTXt/tEXt notes ordered
  by the keyword's trailing number, joined, base64-decoded). A counter of undecodable segments triggers the
  site-player fallback.
- **Source links.** `assets/source-switch.js` finds the page's switcher by structure, never by words. Links mode
  clicks each link and records each new player frame; once a playlist is seen, everything else is blocked.
  `SourceLinks.kt` (`LinkOrder`) decides order, labels and which players to open.
- **Lists.** `assets/list-extract.js` reads index pages with learned shapes (`LearnedShapes`), loose rows and
  stragglers. `ListStatus`: Live if started under 4 h ago, Finished 4–24 h, always-on channel past 24 h; real live
  events sort before always-on ones. `DateGuess` reads ET/CT/MT/PT/UTC/GMT/offset labels against the system clock.
- **Player overlay** buttons, in order: Pause/Play (first, focused), Jump to live, Source, Back to list,
  Audio & quality, Try another player. Up/Down/OK open the overlay; no channel surfing. Tunneling switches off
  automatically after audio-track errors, timeouts, or repeated stalls with a full buffer (saved after 2 strikes).
  Low-delay mode switches to smooth after 2 empty stalls in 60 s.
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
- **Save sample:** zip with list page and stream page snapshots plus the log. May be sanitized; words are examples only.
- **Built-in browser:** with the user's permission, inspect a live site's frames and network requests from the
  desktop app's browser pane.
