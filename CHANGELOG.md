# Changelog

All notable changes to Sunloom are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/). The release workflow publishes the section that matches
the release's version as its notes.

## [Unreleased]

### Added
- **Report an inappropriate picture.** *Dislike and report…* (right-click menu and ⋮ More) and *Report this image…* (in
  *About this image* and on Reserve cards) remove the picture for you and open a pre-filled GitHub issue so the maintainer
  can review it and block it in a later release. The form never contains the picture or anything about your device, and
  it is not offered for photos you added yourself.

### Changed
- **The safety screen now catches plural forms of some words** it missed (for example the plural of a hate-group name or of
  a swimwear or selfie term). Pictures already in your reserve are checked again against the new list the next time the
  app runs, and any that now fail are removed.
- **Pictures whose size cannot be read are refused** before they are decoded. Sunloom reads the size from the file
  header and, for an unusual layout, asks the system's decoder; a picture is refused only when neither can say how big it
  is, and one the decoder sizes is held to the same limits as any other.

### Fixed
- **Sunrise and sunset no longer stop working after damaged location settings.** A settings file holding an impossible
  longitude or latitude made the schedule fail; such a value is now read as zero, and the schedule keeps running.
- **A sunrise or sunset change could be missed.** If the app was opened (or the schedule re-armed) in the 30 seconds before
  sunrise or sunset, that change was skipped and the wallpaper kept its old look until the next one, up to a day later.
  It is now scheduled a minute out instead.
- **Wikimedia pictures download at the size Sunloom asks for again.** Wikimedia now serves most thumbnails from its
  `thumb.wikimedia.org` address, which Sunloom refused, so it fell back to the full-size original (often tens of megabytes)
  or skipped the picture. That address is now allowed, and listed in the privacy policy.

## [2.0.0-rc.3] - 2026-11-20

The third release candidate. The second one was never published, so everything in it is included here (see below). Please
try it, especially setting up with no network and then going online, and following the system theme with a chosen
Daytime or Evening picture. [Tell us what you find](https://github.com/stevin-wilson/Sunloom/issues/new/choose).

**Check your download.** Every Sunloom release is signed with one key. Its certificate SHA-256 fingerprint is
`28:67:7F:15:15:29:90:DB:45:BD:49:8B:5D:B0:32:FE:73:45:71:58:C5:5C:11:27:3C:C9:C5:BA:8D:90:34:9F`.
`apksigner verify --print-certs` must show the same value (without the colons), and `SHA256SUMS` must match the APK.

### Fixed
- Finishing setup now sets a wallpaper straight away, in every mode and even with no network. Before, setup only showed a
  preview and the first real change came about 15 minutes later. If the system refuses the picture, setup says so.
- Following the system theme with a chosen Daytime or Evening picture: switching the theme no longer flashes another picture
  first. The chosen picture is the only one shown.
- Setting up with no network, then going online: Sunloom now fetches its first pictures by itself as soon as a suitable
  network is available, instead of waiting hours or for you to press Next.

### Changed
- Updated the build tools and libraries underneath: Gradle 9.9, the Android Gradle Plugin 9.5, WorkManager 2.12 and
  the GitHub Actions that build releases. Nothing should look different; if something does, please report it.

### Known limitations
- Turning on a source such as NASA does not fetch from it by itself; press **Sync Catalog Now** to bring its pictures in.
- *Custom schedule* has one set of hours; separate start times for the daytime and the evening are not possible yet.

## [2.0.0-rc.2] - 2026-11-17 (never published; included in rc.3)

The second release candidate. It adds scheduling choices and changes a lot of code underneath, so a full try-out is
welcome, especially on a second monitor and across a sunrise or sunset. Please
[tell us what you find](https://github.com/stevin-wilson/Sunloom/issues/new/choose).

**Check your download.** Every Sunloom release is signed with one key. Its certificate SHA-256 fingerprint is
`28:67:7F:15:15:29:90:DB:45:BD:49:8B:5D:B0:32:FE:73:45:71:58:C5:5C:11:27:3C:C9:C5:BA:8D:90:34:9F`.
`apksigner verify --print-certs` must show the same value (without the colons), and `SHA256SUMS` must match the APK.

### Added
- **Change every** (Schedule): Off, 3, 6 or 12 hours. Sunloom also changes the wallpaper during the day and the evening,
  about that often, in addition to the sunrise, sunset or hours you chose. Off to begin with, so nothing changes unless
  you choose it.
- **Daytime wallpaper** and **Evening wallpaper** (Schedule): pick a picture for each. Right-click any picture in
  Reserve or on Today and choose *Use as daytime wallpaper* or *Use as evening wallpaper*. It appears when that period
  starts and stays until the next one; Next and Previous still work. A small picture is shown with a blurred background
  instead of being refused, and can look soft. Disliking or removing the picture sets its slot back to Automatic.

### Changed
- New installs follow the system theme by default instead of switching at sunrise and sunset. Anyone who already chose a
  schedule keeps it.
- Inside the app, the screens and the wallpaper-apply code were split into smaller parts. Nothing should look or behave
  differently; if something does, that is a bug worth reporting.
- "Follow system theme" remembers which side it last handled, and stops retrying a chosen picture that cannot be used.

### Fixed
- **Check for updates** now works on a release candidate: it reads GitHub's list of releases and offers the highest newer
  version. A release-candidate build hears about the next candidate and the final release; a normal release hears only
  about normal releases. Before, it said "Couldn't check" while only candidates existed.

## [2.0.0-rc.1] - 2026-11-11

The first release candidate of the first public release. Sunloom 1.x was a private build; this is the version that
anyone can download. Please try it and [tell us what you find](https://github.com/stevin-wilson/Sunloom/issues/new/choose).

**Check your download.** Every Sunloom release is signed with one key. Its certificate SHA-256 fingerprint is
`28:67:7F:15:15:29:90:DB:45:BD:49:8B:5D:B0:32:FE:73:45:71:58:C5:5C:11:27:3C:C9:C5:BA:8D:90:34:9F`.
`apksigner verify --print-certs` must show the same value (without the colons), and `SHA256SUMS` must match the APK.

### Added
- **Share it:** a GitHub Releases APK (`github` build) with a checksum, plus a `play` build for a later Play release
  that has no update code. Free software under GPL-3.0-or-later.
- **About Sunloom** in Storage & About: version, build, licence, privacy policy, source, open-source licences, and
  (GitHub build) Check for updates, with an optional once-a-week check when you open the app.
- **A friendlier first run:** four short steps with progress, plain explanations, one-click **Start with defaults**,
  and a city guessed from your time zone.
- **Reset Sunloom:** erase everything and start again.
- **Report a problem** and **Copy diagnostics**, and a local crash log (the last five), so a bug report can say what
  happened.
- An **Automatic switching** switch and a way back into Setup, so no choice made at setup is permanent.
- **Feedback you can act on:** long messages open when tapped, Try again after a failed fetch, progress while photos
  are checked, **Apply now** after Add Photo, and a banner that describes the schedule you actually use.
- **Accessibility:** WCAG-level contrast in both themes, single-key shortcuts that can be switched off, a visible More
  menu, names for icon-only navigation, a Select action for screen readers, and layouts that hold at 400 dp and 200
  percent text.
- A complete design language (every colour role, shape and spacing scale) and all text in `strings.xml`.

### Changed
- Downloads are gentler: only on unmetered networks by default, never with Data Saver on, at most 300 MB a day in the
  background, streamed to disk, and nothing is requested when the Reserve is already full.
- Applying a wallpaper uses less memory and never undersamples; the app gives memory back when the system asks.
- Plain words on screen instead of engineering terms; one word for each action (Keep, Dislike, Remove).
- "Follow system theme" does no work when nothing has changed, and reacts at once while the app is open.
- Adding a photo no longer changes the wallpaper on its own.

### Fixed
- Automatic switching could not be turned back on after setup.
- A restored backup left phantom entries for pictures that were not restored; they are now cleaned up and you are told
  how many of your own photos were lost.
- Backups missed recent changes made while the app was closed.
- Database upgrades no longer hide real failures.
- Orphaned image files and very old forgotten entries are now cleaned up.
- Imported photos could be given the same ID when several arrived together.

### Security
- No secrets in the package; `Log` calls below warning level are removed from release builds.

[Unreleased]: https://github.com/stevin-wilson/Sunloom/compare/v2.0.0-rc.2...main
[2.0.0-rc.2]: https://github.com/stevin-wilson/Sunloom/compare/v2.0.0-rc.1...v2.0.0-rc.2
[2.0.0-rc.1]: https://github.com/stevin-wilson/Sunloom/releases/tag/v2.0.0-rc.1
