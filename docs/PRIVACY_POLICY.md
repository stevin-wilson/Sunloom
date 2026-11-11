# Sunloom Privacy Policy (draft for hosting)

_Last updated: 2026-11-06. Host this page at a stable public HTTPS URL and enter that URL in the Play Console (App content → Privacy policy). Replace the contact line before publishing._

Sunloom (`com.sunloom.app`) is a wallpaper app. **The developer collects no personal data.** There are no accounts, no analytics, no crash-reporting service, no advertising identifiers and no developer-operated servers.

## What stays on your device
Your preferences, schedule, chosen city or coordinates, wallpaper history, favourites, the "Reject forever" blocklist and downloaded images are stored only on your device. Sunrise and sunset are computed on the device from the city or coordinates you enter. Sunloom never requests GPS or location permission.

## Network requests
When catalog sync is enabled, your device contacts the public image sources below directly. These hosts receive the device's normal network request data (such as your IP address and the app's User-Agent, `Sunloom/<version> (+https://github.com/stevin-wilson/Sunloom)`). Sunloom sends them no account, device identifier or personal information, and the developer receives none of it.

| Source | Hosts contacted |
|---|---|
| Wikimedia Commons (Featured Pictures) | `commons.wikimedia.org`, `upload.wikimedia.org` |
| NASA Image and Video Library | `images-api.nasa.gov`, `images-assets.nasa.gov`, `images.nasa.gov` |
| Art Institute of Chicago | `api.artic.edu`, `www.artic.edu` |
| The Metropolitan Museum of Art | `collectionapi.metmuseum.org`, `images.metmuseum.org`, `www.metmuseum.org` |
| Cleveland Museum of Art | `openaccess-api.clevelandart.org`, `openaccess-cdn.clevelandart.org`, `www.clevelandart.org`, `clevelandart.org` |
| SMK, National Gallery of Denmark | `api.smk.dk`, `iip.smk.dk`, `open.smk.dk`, `www.smk.dk` |

Opening a "source page" link in your browser contacts that site under its own privacy policy.

## Checking for updates (the GitHub build only)
The build you download from GitHub has a **Check for updates** button in Storage & About. Only when you tap it, the app asks `api.github.com` for the project's latest release. GitHub receives your IP address and the same User-Agent as above; nothing else is sent. Nothing is downloaded or installed: if a newer version exists the app shows its version and a link that opens in your browser. The Google Play build has no update check (the store updates it) and never contacts GitHub.

## Files you import or export
Photos you add (Photo Picker, drag-and-drop, Share, or Open with) are copied into the app's private storage and never uploaded. "Save as…" writes a wallpaper to a location you choose; Creative Commons images are saved together with their credit.

## Backups
Android Auto Backup may store your preferences and the library database with your Google account under your device's backup settings. Downloaded images and the quarantine folder are excluded.

## Children
Sunloom shows only safe-for-work content and collects no data from anyone.

## Changes and contact
Material changes will be listed here with a new date. Contact: open an issue at <https://github.com/stevin-wilson/Sunloom/issues>. That page is also the contact in the app's User-Agent.
