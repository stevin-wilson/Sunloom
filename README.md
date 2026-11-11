# Sunloom

Sunloom changes your Googlebook wallpaper with the sun: a bright picture by day, a dark one in the evening. The
pictures are landscapes, space, architecture and fine art from public-domain and Creative Commons collections. Each one
is checked for safety and fitted to your screen before it is used.

- **Follows the day.** Switch at sunrise and sunset for your city, at hours you choose, with your system's light and
  dark theme, or only when you tap Next.
- **Fits every screen.** One picture is cropped to suit your built-in display and any monitor you plug in, without
  stretching.
- **Works offline.** Sunloom draws its own pictures on first start, so it works before it has downloaded anything.
- **Keeps your taste.** Keep what you love, Dislike what you do not, and a Disliked picture never comes back.
- **Private.** No account, no analytics, no ads, no server of ours. Nothing about you leaves your device.
- **Free software.** GPL-3.0-or-later. Read the code, build it yourself, change it.

> Sunloom is built for Googlebook. It needs Android 8.0 or newer and runs best in a resizable window.

## Install

1. Download `Sunloom-<version>-github.apk` and `SHA256SUMS` from the
   [latest release](https://github.com/stevin-wilson/Sunloom/releases/latest).
2. Check the download is the one we published:
   ```
   sha256sum --check --ignore-missing SHA256SUMS
   ```
   For a stronger check, compare the signing certificate. Every Sunloom release is signed with the same key, whose
   SHA-256 fingerprint is
   ```
   28:67:7F:15:15:29:90:DB:45:BD:49:8B:5D:B0:32:FE:73:45:71:58:C5:5C:11:27:3C:C9:C5:BA:8D:90:34:9F
   ```
   and each release's notes repeat it. Yours must match what this prints (look for "certificate SHA-256 digest", which
   shows the same value without the colons, in lower case):
   ```
   apksigner verify --print-certs Sunloom-<version>-github.apk
   ```
3. Open the file on your Googlebook. The first time, Android asks you to allow installs from the app you opened it
   with (Files or your browser): allow it, then install.
4. Open Sunloom and follow the four short steps. **Start with defaults** finishes setup in one click.

To update, install the next release over the old one: your settings and library stay. The GitHub build can also tell
you when a new version is out (Storage & About → Check for updates).

### Obtainium

[Obtainium](https://obtainium.imranr.dev/) can install and update Sunloom straight from GitHub. Add the app with the
source URL `https://github.com/stevin-wilson/Sunloom`. Leave "include prereleases" off to get only final releases.

## What Sunloom asks for, and why

| Permission | Why |
|---|---|
| `SET_WALLPAPER` | To set your wallpaper. Without it Sunloom can only preview. |
| `INTERNET` | To download pictures from the sources you have switched on, and (GitHub build) to check for a new version when you ask. |
| `ACCESS_NETWORK_STATE` | To download only on a network that is not metered, and to respect Data Saver. |
| `RECEIVE_BOOT_COMPLETED` | To arm the next wallpaper change again after a restart. |
| `WAKE_LOCK`, `FOREGROUND_SERVICE` | Added by Android's WorkManager, which runs the scheduled wallpaper change and the download job. Sunloom shows no notification and starts no service of its own. |

There is no location, storage, camera, contacts or notification permission. (Installing also lists one permission
named `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`: Android's own library adds it, and only Sunloom itself can hold it.) Sunrise and sunset come from the city you
pick (guessed from your time zone at first), never from GPS. Photos you add are read through Android's picker, one at
a time, when you choose them.

## Privacy in one paragraph

Everything Sunloom keeps stays on your device. It contacts only the picture sources you have enabled, and, in the
GitHub build, GitHub when you ask for updates (or once a week when you open the app, if you opted in). Background
downloads wait for an unmetered network and stop at 300 MB a day. The full list of hosts, what is backed up and how to
erase everything is in the [privacy policy](docs/PRIVACY_POLICY.md).

## Guides

- [User guide](docs/USER_GUIDE.md): schedules, the Reserve, Keep / Dislike / Remove, sources, displays, your data.
- [Privacy policy](docs/PRIVACY_POLICY.md)
- [Contributing](CONTRIBUTING.md) and [architecture](docs/ARCHITECTURE.md) for developers.
- [Releasing](docs/RELEASING.md) for maintainers, and the [changelog](CHANGELOG.md).
- [Security policy](SECURITY.md): how to report a vulnerability.

## FAQ

**Which sources does it use?** Wikimedia Commons Featured Pictures, NASA's image library, and the open-access
collections of the Art Institute of Chicago, the Metropolitan Museum of Art, the Cleveland Museum of Art and SMK (the
National Gallery of Denmark). NASA is off until you switch it on. You can turn any source or category off.

**Why does it not download over mobile data?** Because a wallpaper is not worth your data plan. Sunloom downloads
only on a network Android reports as unmetered, and never while Data Saver is on. If you tap Refresh yourself it
warns you once and then goes ahead.

**Will it change my wallpaper behind my back?** Only on the schedule you chose. Adding a photo puts it in your Reserve
and offers "Apply now"; it does not change the wallpaper by itself.

**Where is the Play Store version?** Not yet. A Google Play build is planned; it will be the same app signed with the
same key, without the update check.

## Troubleshooting

- **Nothing changes at sunset.** Check Schedule: *Automatic switching* must be on and the schedule not paused. If your
  device stops background work for apps you have not opened for a while, open Sunloom once; it re-arms its schedule.
- **"System wallpaper changes are restricted".** Your device or work profile does not allow apps to set the wallpaper.
  You can still browse the Reserve.
- **Pictures are blurry on a big monitor.** Sunloom only uses pictures large enough for your sharpest screen. If few
  qualify, the Reserve fills slowly; turn on more sources or categories, or allow a blurred frame for smaller pictures
  (Schedule).
- **Something is broken.** Open Storage & About, choose **Copy diagnostics**, and
  [open an issue](https://github.com/stevin-wilson/Sunloom/issues/new/choose) with what you see. Diagnostics hold the
  version, your displays and recent errors: no photos, no location and no personal information.

## Building from source

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: JDK 17, the Android SDK, and `./gradlew assembleGithubDebug`.

## Licence

Sunloom is free software under the GNU General Public License, version 3 or (at your option) any later version. See
[LICENSE](LICENSE). The few pieces of other people's work it contains are credited in [NOTICE](NOTICE) and in the app
(Storage & About → Open-source licences). The pictures it downloads keep their own licences, shown with each picture.
