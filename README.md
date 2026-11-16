# StreamLink

An Android TV app: send a web link from your phone, and the TV finds the video on that page and plays it fullscreen in a built-in player.

## Install on the TV

1. On the TV, install the free **Downloader** app (by AFTVnews) from the Play Store.
2. Allow it to install apps: **Settings → System → About → Android TV OS build**, click it 7 times to turn on Developer options, then **Settings → Apps → Security & restrictions → Unknown sources → Downloader → on**.
3. In Downloader, enter:
   `https://github.com/drauch69-cmd/tv/releases/download/latest/StreamLink.apk`
4. Install it. For updates, repeat step 3. It installs over the old version.

## Use it

1. Open **StreamLink** on the TV. It shows a QR code.
2. Scan it with your phone (same Wi-Fi), paste a link, and tap **Play on TV**.
3. On the TV: arrow keys show the controls, OK pauses, Back returns to the link screen.
   Recent links are listed too; press OK to replay one, hold OK to remove it.

## How it handles popups

The page is loaded invisibly. Popups and new tabs are refused, alert boxes are dismissed,
navigation to any other site is blocked, and known ad and redirect networks are never loaded.
You only ever see the video in the app's own player.

## Building

Every push to `main` builds the APK on GitHub (see the **Actions** tab) and updates the `latest` release.
