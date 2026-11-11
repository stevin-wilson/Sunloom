# Changelog

All notable changes to Sunloom are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/). The release workflow publishes the section that matches
the release's version as its notes.

## [Unreleased]

The first public release. Sunloom 1.x was a private build; this is the version that anyone can download.

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

[Unreleased]: https://github.com/stevin-wilson/Sunloom/commits/main
