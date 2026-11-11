# Sunloom Privacy Policy

_Last updated: 2026-11-11. This policy covers the Sunloom app (`com.sunloom.app`) as published at
<https://github.com/stevin-wilson/Sunloom>._

Sunloom is a wallpaper app. **Its developer collects no personal data.** There are no accounts, no analytics, no
crash-reporting service, no advertising identifiers and no servers run by the developer. Everything Sunloom keeps stays
on your device, and nothing it keeps is sent to the developer.

## What stays on your device

Your settings, schedule, chosen city or coordinates, wallpaper history, the pictures you Keep or Dislike, the list of
pictures you rejected for good, the photos you add, and the pictures Sunloom downloaded are all stored in the app's
private storage. Sunrise and sunset are worked out on the device from the city or coordinates you choose. The city is
first guessed from your device's time zone, without using location. Sunloom never asks for GPS or any location
permission.

If the app crashes, a short crash report (the error and its stack trace, no personal data) is kept on the device, up to
the last five. It is shown only inside the app, and it leaves the device only if you choose **Copy diagnostics** and
paste it somewhere yourself.

## Network requests

When picture sources are switched on, your device contacts them directly. They receive what any network request
carries, such as your IP address and the app's User-Agent, `Sunloom/<version> (+https://github.com/stevin-wilson/Sunloom)`.
Sunloom sends no account, device identifier or personal information, and the developer receives none of it.

| Source | Hosts contacted |
|---|---|
| Wikimedia Commons (Featured Pictures) | `commons.wikimedia.org`, `upload.wikimedia.org` |
| NASA Image and Video Library (off until you switch it on) | `images-api.nasa.gov`, `images-assets.nasa.gov`, `images.nasa.gov` |
| Art Institute of Chicago | `api.artic.edu`, `www.artic.edu` |
| The Metropolitan Museum of Art | `collectionapi.metmuseum.org`, `images.metmuseum.org`, `www.metmuseum.org` |
| Cleveland Museum of Art | `openaccess-api.clevelandart.org`, `openaccess-cdn.clevelandart.org`, `www.clevelandart.org`, `clevelandart.org` |
| SMK, National Gallery of Denmark | `api.smk.dk`, `iip.smk.dk`, `open.smk.dk`, `www.smk.dk` |

Background downloads happen only on a network Android reports as unmetered (you can allow metered networks in
Sources; even then nothing downloads in the background while Data Saver is on), and total at most 300 MB a day. If you tap Refresh yourself, Sunloom warns you once on a
metered network and then goes ahead.

Opening a "source page" or licence link, or **Report a problem**, opens your browser. The page you reach is governed by
that site's own privacy policy. **Report a problem** only opens a GitHub issue form with the app version, build and Android version
filled in; nothing is sent until you review it and press Submit.

## Checking for updates (the GitHub build only)

The build you download from GitHub has **Check for updates** in Storage & About. When you tap it, the app asks
`api.github.com` for the project's list of published releases. GitHub receives your IP address and the User-Agent above; nothing
else is sent. If you turn on "Check for a new version when I open Sunloom" in setup, the same request is made at most
once a week, only when you open the app, never in the background. It is off unless you choose it. Nothing is downloaded
or installed: if a newer version exists the app shows its number and a link that opens in your browser. (A build that is itself
a release candidate is also told about newer candidates; a normal release is told only about normal releases.)

A future Google Play build will have no update check and will never contact GitHub.

## Files you add or export

Photos you add (the picker, drag-and-drop, Share or Open with) are copied into the app's private storage and never
uploaded. **Save as…** writes a wallpaper to a place you choose; Creative Commons pictures are saved with their credit.

## Backups

Android Auto Backup may store your settings and your library's database (the list of pictures, your Keeps and
Dislikes) with your Google account, under your device's backup settings. Downloaded pictures and photos you added are
**not** backed up; after a restore Sunloom downloads its pictures again and tells you how many of your own photos could
not be restored.

## Erasing everything

**Storage & About → Reset Sunloom** erases everything Sunloom keeps on the device: the library, the photos you added,
your settings and the crash reports. Uninstalling the app removes them too. Data already in a Google backup is managed
in your device's backup settings.

## Children

Sunloom shows only safe-for-work pictures and collects no data from anyone.

## Changes and contact

Material changes will be listed here with a new date. To ask a question or report a problem, open an issue at
<https://github.com/stevin-wilson/Sunloom/issues>. To report a security problem privately, see
[SECURITY.md](../SECURITY.md).
