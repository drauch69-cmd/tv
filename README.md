# StreamLink

An Android TV app: send a web link from your phone, and the TV finds the video on that page and plays it fullscreen in a built-in player.

## Install on the TV

1. On the TV, install the free **Downloader** app (by AFTVnews) from the Play Store.
2. Allow it to install apps: **Settings → System → About → Android TV OS build**, click it 7 times to turn on Developer options, then **Settings → Apps → Security & restrictions → Unknown sources → Downloader → on**.
3. In Downloader, enter:
   `https://github.com/drauch69-cmd/tv/releases/download/latest/StreamLink.apk`
4. Install it. For updates, repeat step 3. It installs over the old version.

## Updates

The app updates itself from its own GitHub release (the normal app from `latest`, the Test app from `test-latest`).
It checks when it starts and then at most every 12 hours (never while something is playing). The **Check for
updates** button on the home screen checks right away.

- Each release has a `version.json` next to the APK (`versionCode`, `channel`, `apk`, `sha256`).
- If the release is newer, the TV downloads it in the background and checks it: the SHA-256 must match, and the APK
  must be this same app (same package name, same signing certificate as the installed one). Otherwise it is thrown away.
- Then the home screen shows **Update ready (build N) — press OK to install**. Nothing is ever installed without you
  pressing OK there (or **Install update on TV** on the phone page, when the TV is on its home screen). Android then
  shows its own install confirmation.
- One-time setup: allow StreamLink to install apps: **Settings → Apps → Security & restrictions → Unknown sources →
  StreamLink**. The home screen explains this and has a button that opens the setting.
- Builds from before this feature have no updater: install the first updating build by hand (Downloader, as above).

## Use it

1. Open **StreamLink** on the TV. It shows a QR code and the TV's address.
2. Scan it with your phone (same Wi-Fi), paste a link, and tap **Play on TV**.
3. On the TV: arrow keys show the controls, OK pauses, Back returns to the link screen.
   Recent links are listed too; press OK to replay one, hold OK to remove it.

### Stream lists

Some sites have an index page that lists one-time events (each with its own page). Save that page once and
the TV reads it fresh every time you open it.

1. On the phone page, paste the index page's link, optionally type a name, and tap **Add as stream list**.
2. On the TV home screen the list appears under **Stream lists** (hold OK to remove it). Press OK to open it:
   you see the events at once (from the last read, with "updated X ago"), then they refresh, and again every 2 minutes.
   Each event has a badge: LIVE, Starts in 12 min, or its day and time. Finished events are hidden.
3. Press OK on an event to play it. The phone page also has a **Stream lists** section: tap a list to see its events
   (with a **Refresh** button) and tap an event to play it on the TV.

**Waiting for it to start:** if an event has no stream yet (it starts later, started less than 30 minutes ago, or has
no time on the page), the TV shows a waiting screen with the title, start time and a countdown, and looks again every
45 seconds. When the stream appears the player starts by itself. It gives up 3 hours after the start time (or after
3 hours of waiting when there is no time). Press Back, or **Stop waiting** on the phone, to cancel. The screen stays
awake while waiting. Links sent directly with **Play on TV** behave as before.

### Phone remote

Once something is playing, the phone page turns into a remote: Play/Pause, Jump to live,
Reconnect, Stop, a **Quality** picker (Auto or a fixed resolution), a **Source** picker when the page
has more than one stream, and **Use the site's own player**.

### Share menu shortcut (iPhone)

On the phone page, tap **Add “Play on TV” to your iPhone Share menu** for step-by-step instructions.
Afterwards you can tap **Share → Play on TV** on any page in Safari. Reserve the TV's address in
your router (DHCP reservation) so the shortcut keeps working.

### Multiple sources

Many pages offer several streams ("Server 1 / 2 / 3"). StreamLink checks every stream it finds,
ranks them, and plays the best one. Press **▲** on the remote (with the controls hidden) to pick
another source, or use the Source picker on the phone. If a source fails, the next one is tried.

### Automatic reconnect

Stream links on these sites often expire. When playback fails or freezes, StreamLink quietly reloads
the page for a fresh link and picks up at the live point. You'll see "Reconnecting…" briefly.

### Site player fallback

If no playable stream can be found, or the built-in player can't handle it, StreamLink shows the
site's own player fullscreen instead, still with popups and redirects blocked. OK plays/pauses.

### What happened?

The phone page has a **What happened?** panel: every stream found, how each server answered,
which popups and ad networks were blocked, and any reconnects. Tap **Copy report** to share it
when a site doesn't work.

## How it handles popups

The page is loaded invisibly. Popups and new tabs are refused, alert boxes are dismissed,
navigation to any other site is blocked, and known ad and redirect networks are never loaded.
You normally only see the video in the app's own player.

## Building

Every push to `main` builds the APK on GitHub (see the **Actions** tab) and updates the `latest` release.
Other branches only build and run the tests.

### Test app

Pushes to the `test` branch build a separate **StreamLink Test** app (`com.streamlink.tv.test`) that
installs next to the normal one, for trying changes safely. In Downloader, enter:
`https://github.com/drauch69-cmd/tv/releases/download/test-latest/StreamLink-Test.apk`

(Locally: `gradle assembleRelease -Pchannel=test`.) Tunneled playback is on by default; if it
misbehaves the player turns it off until the app restarts. Only the phone remote switch changes the saved setting.

---

# TV Cleaner

A second app in this repo (`cleaner/`): debloat the TV from the TV itself, with the remote. It uses
[Shizuku](https://github.com/thedjchi/Shizuku) for the rights to turn apps off, and the per-model lists from
[tellyscope-devices](https://github.com/man15h/tellyscope-devices) (based on cleanyourtv, MIT).

- **Suggested cleanup** finds your TV's tested list and pre-ticks the safe items (only when the list was tested on your Android version).
- **All apps** lets you turn any app off or back on, or restore one that was uninstalled.
- **History / undo** keeps every change and can turn everything back on.
- Apps the TV needs (Settings, Play services, keyboard, remote, Wi-Fi, home screen, updates, setup) are locked.
- Apps are turned off (disabled), never deleted.

## Install

1. Install Shizuku (TV version) and start it: in Downloader go to `github.com/thedjchi/Shizuku/releases`.
2. In Downloader, enter:
   `https://github.com/drauch69-cmd/tv/releases/download/cleaner-latest/TVCleaner.apk`
3. Open **TV Cleaner**, allow it in Shizuku, then go to **Suggested cleanup**.

Every push that touches `cleaner/` builds it on GitHub and updates the `cleaner-latest` release.
