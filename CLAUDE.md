# StreamLink — guide for agents

Read this before working in this repo. It is the source of truth for how the app works and how to work here.
Decisions, reversals and open items live in `docs/DECISIONS.md`. Read it too.

**Keep these files current.** A change that alters behavior, structure, commands or rules updates
this file, `docs/ARCHITECTURE.md` (mechanism detail) or `docs/DECISIONS.md` in the same commit. A decision the user makes, or an item that opens or
closes, goes into `docs/DECISIONS.md` before the session ends.

## What StreamLink is

A personal, sideloaded Android TV app for an Onn Google TV box. The user sends a web page from their phone; the TV
finds the page's livestream in a hidden browser and plays it in a clean native player. One person's own use; not
distributed.

- App in use: `com.streamlink.tv.sports` ("StreamLink Sports"; code package `com.streamlink.tv`). Kotlin, minSdk 26,
  targetSdk 34, compileSdk 36, Media3 1.11.1 (ExoPlayer, HLS, DASH, Cronet).
- Inputs: a link pasted on the phone page (served by the TV itself), or a saved "stream list" (an index page of
  one-time events, shown as Live now / Starting soon / Later).
- Product goal: fast start, no popups or redirects to other sites (pre-roll ads may stay), clean fullscreen player
  with remote-friendly controls.
- The user is a vibe coder: agents write all the code; the user tests on the TV and sends "What happened?"
  reports and Save sample zips.

## How to work with this user

1. **Do the work directly by default** (user, 2026-11-19, to save tokens). Helpers start cold and reload this file and
   the code, so they cost more than they save on ordinary tasks. Use a helper only for a big job that splits into
   independent parts in different areas of the code, or a long search the lead does not need to see.
2. **Check in first.** Before starting any task: lay out the diagnosis, options and a recommendation, then wait
   for "go" / "proceed".
3. **Plain words.** No file paths, tool names or jargon in replies unless asked. Say plainly what has and has not
   been tested on the TV.

In practice:
- When a helper is used: Haiku for small, precisely specified changes in one file, Sonnet for behavior changes across
  files. Every brief names: clone and branch, exact files and functions, expected behavior, tests to add, a short report
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
- `sports` is the main line (user, 2026-11-20): push it directly. Never push `main` without the user's OK.
- Never touch `cleaner/` or `.github/workflows/cleaner.yml` (a separate app).
- Commit trailers: `Co-Authored-By:` and `Claude-Session:` lines, using the values given in the current session's
  own attribution instructions.

## Branches, releases, CI

- `sports`: the main line, the "StreamLink Sports" app on the user's TV (`com.streamlink.tv.sports`, release
  `sports-latest`, https://github.com/drauch69-cmd/tv/releases/download/sports-latest/StreamLink-Sports.apk). Work on
  it directly and `git push origin sports` (fetch first; never force-push).
- `test`, `integrate`, `sports-int` and `agent/*` are retired (2026-11-20); don't use or revive them. `main` stays only
  because the TV Cleaner app builds from it (`cleaner.yml`, hands off); StreamLink's `latest`/`test-latest` releases are
  frozen.
- This session's GitHub access may only push to `sports`: deleting branches, tags, repo settings and Actions secrets
  are refused. Hand those to the user.
- CI (`.github/workflows/build.yml`): every push builds and runs unit tests; `sports` publishes `sports-latest`;
  versionCode = run number. Lint is separate and never blocks. Transient Maven 429: `gh run rerun <id> --failed`.
- **Docs-only commits: add `[skip ci]` to the commit message**, or a new build is released and the TV offers a
  pointless update.
- Signing: the release key lives in Actions secrets `SIGNING_KEYSTORE_B64` + `SIGNING_PASSWORD` (2026-11-20), not in
  the repo. CI fails if the APK's certificate changes (the TV would refuse the update). `main` still has the old file
  for the TV Cleaner build; leave it.

## Architecture in brief

Full detail (every mechanism, report line and rule) is in `docs/ARCHITECTURE.md`. **Read only the sections you
need** (search it by class name); do not load it whole. Keep this file short: new detail goes there, not here.

- Pipeline: `LinkServer` (phone page, port 8080/8081) or a saved list (`IndexReader`) -> `StreamSniffer` (hidden
  WebView finds playlists) -> `Verifier` (checks with referers) -> `PlayerActivity` (ExoPlayer, Cronet, recovery,
  backups, automatic switch) -> fallbacks `CleanPlayerActivity` / `SitePlayerActivity`.
- Lists are read by one app-wide reader, `ListReads` (one queue, runs in the newest screen's hidden holder); screens
  ask it and listen, they never own an `IndexReader`. List recipes for the user's TV go on `sports` (`recipes.json`).
- Screens: `MainActivity` (home: Live games row with RedZone first, Sports row, top bar Your lists / Send a link),
  `ListsActivity`, `ListActivity`, `SportActivity`, `StreamsActivity`, `WaitingActivity`; phone page `assets/phone.html`.
- Pure, unit-tested logic sits in small Kotlin files (`HomeModel`, `HomeFeed`, `Games`, `ScoreStatus`, `BackupLogic`...);
  page scripts in `assets/*.js`.

## Testing

- Unit tests run in CI on every push (the only Android build available; there is no local Android SDK).
- JS harnesses (run when an asset script changes; `NODE_PATH=/opt/npm-tools/node_modules`):
  `app/src/test/js/check-list-extract.js`, `app/src/test/js/start/check-player-start.js`,
  `app/src/test/js/links/check-source-switch.js`, `app/src/test/js/check-list-expand.js`,
  `app/src/test/js/links/check-page-sources.js`.
- Diagnostics (report lines, Timing line, Save sample, crash recorder): see `docs/ARCHITECTURE.md`.

## Saving tokens

- Don't read whole big files (`docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `MainActivity.kt`, `PlayerActivity.kt`);
  grep, then read only the lines you need. Keep command output short (`| head`, `--stat`, `-q`).
- Small changes: the lead does them directly; helpers only for large, independent jobs.
- Don't wait on CI inside a chat; check the run at the start of the next one.
