# StreamLink — decisions and open items

Running log. Update it in the same session a decision is made or an item opens or closes.
Newest open items first. When an item closes, move it to "Decisions" (or delete it if it no longer matters).

## Open items

- **Slow fallback on sites the finder can't catch** (e.g. a list event with no start time on a site with 5 misses
  in a row: site player only at 36 s, two searches plus a 15 s wait). Offered, not approved: treat "Live" in the
  list as started (fall back after the first check), and fall back right after the first search on sites with 3+
  misses in a row. Next: read the site player's new lines in the user's report to see if the stream is catchable.
- **Links 2–4 on the test site.** Their players only work when embedded; their playlists are found (inside a
  tracking request) but refused with 403. Build 150 sends Origin and Referer from the frame referers; waiting on the
  user's report. If it still fails: try the inner embed frame as the referer, then other headers.
  Build after 150 (agent/token): prefers the player's own tokened request over the copy inside the tracking
  request (waits up to 8 s), also tries each referer as a bare origin with trailing slash, lists referers tried
  on a 403, and renews an expired access code during play. Untested on the TV; waiting on the user's report.
- **Waiting on the user's OK:** releasing `test` to `main`; moving the signing key to GitHub secrets; pruning fixes
  never proven to help; restyling the player's loading screen.
- **Offered, not approved:** automatic retry of failed list reads after about 15 minutes.

Reminder: lay out options and get a "go" before starting any of these.

## Decisions (do not undo without asking)

- **Home screen:** TV-home-style dashboard, plus all the other mockup changes (list, player overlay, loading
  screen, phone page).
- **Audio sync adjustment:** built, then reverted at the user's request as clunky. The real cause was a TV audio
  setting on the user's side. Do not bring it back.
- **No early give-up on other sites' players** (2026-11-17): build 153 stopped the search 8 s after tapping a
  player from another site; most streams then fell to the site player, since those players often need 10–15 s.
  Removed at the user's request; site miss counts were reset (new storage key). Do not re-add without asking.
- **Channel surfing:** removed. Up/Down open the bottom menu, like OK.
- **Sources** have their own button and panel (not under Audio). **Pause** is the first button.
- **"Jump to live"** is styled like the other buttons, not highlighted. The list's Play button stays highlighted.
- **Extra sources:** gathered in the background while watching.
- **Origin header:** always derived from the referer being tried, not from what was captured.
- **Tunneling:** auto-detect and switch off, not a manual setting only.
- **Agent docs:** `CLAUDE.md` + this file in the repo are the source of truth (2026-11-17). The user's Claude Doc
  "StreamLink — Agent Onboarding Guide" is a summary for the user only, not maintained by agents.
