# StreamLink

An Android TV app: send a web link from your phone, and the TV finds the video on that page and plays it fullscreen in a built-in player.

## Install on the TV

1. On the TV, install the free **Downloader** app (by AFTVnews) from the Play Store.
2. Allow it to install apps: **Settings → System → About → Android TV OS build**, click it 7 times to turn on Developer options, then **Settings → Apps → Security & restrictions → Unknown sources → Downloader → on**.
3. In Downloader, enter:
   `https://github.com/drauch69-cmd/tv/releases/download/latest/StreamLink.apk`
4. Install it. For updates, repeat step 3. It installs over the old version.

## Use it

1. Open **StreamLink** on the TV. It shows a QR code and the TV's address.
2. Scan it with your phone (same Wi-Fi), paste a link, and tap **Play on TV**.
3. On the TV: arrow keys show the controls, OK pauses, Back returns to the link screen.
   Recent links are listed too; press OK to replay one, hold OK to remove it.

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
