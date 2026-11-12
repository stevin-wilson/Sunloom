# Architecture

This is a map for people changing the code. The rules behind it are in the [Constitution](../CONSTITUTION.md); article
numbers below point there.

## The nine modules (Article XIII)

Packages under `com.sunloom.app`. Each has one job and talks to the others through plain types.

| Module | Package | Job |
|---|---|---|
| Source adapters | `data/sources` | Ask one public collection for candidates. Fixed host list, request caps, no keys. `SourceRegistry` is the list of admitted sources. |
| Safety gate | `domain/safety` | Decide whether a candidate may be used (layers below). |
| Image evaluator | `domain/evaluator` | Measure a decoded picture: brightness, glare, clutter, where the subject is. |
| Matcher | `domain/matcher` | Choose the next picture for the moment (light or dark), the person's taste and variety. |
| Renderer | `domain/renderer` | Crop and scale one picture for all connected displays (`EnvelopePlanner`, `CropRenderer`). |
| Scheduler | `platform/scheduler` | WorkManager workers, and a backstop alarm, that change the wallpaper at the right time. |
| Platform adapter | `platform` | The only code that touches `WallpaperManager`, displays and system settings. |
| Local library | `data/repository`, `data/cache` | The SQLite store, the files on disk, imports, eviction. |
| User interface | `ui` | Compose screens, the view model and the gateway between them and the library. |

Also: `domain/model` (shared types), `domain/solar` (sunrise and sunset), `domain/attribution`, `labels` (the words for
each choice), `diagnostics` (crash log and report), `update` (GitHub flavor only, see below).

Rules of the road: `domain/*` imports nothing from Android (it works on plain pixel grids and numbers); `ui` reaches the library only through
`SunloomGateway`; only `platform` calls `WallpaperManager`.

## Where a picture comes from, and what stops it (Article VII)

```
adapter ─▶ candidate ─▶ L1 eligibility ─▶ L2 metadata ─▶ L3 payload ─▶ L4 rendition ─▶ library ─▶ matcher ─▶ renderer ─▶ system
(network)               admitted source,  denylist,       magic bytes,    real decoded     approved   picks one   crop for     wallpaper
                        licence, credit   safe domain,    size, header    size, crop,      pictures                all screens
                                          blocklist       bounds, SHA     glare, dHash     only
                                                                                           ▲
                          L5: "Dislike" adds the picture's ID, SHA-256 and dHash to the permanent blocklist ─┘
```

Any layer can refuse; none can approve alone. A missing or unclear answer is a refusal (fail closed). Unapproved bytes
live only in memory or `noBackupFilesDir/quarantine` and are deleted as soon as the gate finishes. Each stored picture
records the gate version and a per-layer trace, and is re-checked when the gate changes.

Photos the person adds follow the same path from L3 on (L1 and L2 do not apply to their own files).

## Time: who changes the wallpaper, and when (Articles IX and X)

- **`ChangeWorker`** is a one-time WorkManager job set for the next sunrise, sunset or chosen hour. It needs no network.
  When it finishes it schedules the next one, so there is always exactly one pending.
- **`RefreshWorker`** runs every 3 hours and tops the Reserve up from the sources. It checks, in order, whether the
  device is low on storage, the network and Data Saver rules, the 300 MB daily budget, and whether the Reserve needs
  anything at all; if not it makes no request.
- **Follow system theme** has no transition time, so it polls every 15 minutes and ends at once when nothing changed.
- **Change every** (optional, 3, 6 or 12 hours) shortens the wait to the earlier of the next transition and the end of
  the interval. The wait counts from the last change of any kind, or from the moment the interval was chosen, and a
  change attempt restarts it even if it fails (`ChangeCountdown`, `RefreshSchedulePolicy.changeDelayMs`). A Follow-OS
  poll also changes the wallpaper when the interval is due. It adds no download work: the 3-hour refresh and the matcher
  (which reuses the least recently shown picture when nothing unseen is left) are unchanged.
- **Daytime / Evening wallpaper** (`UserPreferences.daytimePickId` / `eveningPickId`, `ChosenWallpaper`): the change run
  asks `ChosenWallpaper.choose` once. A usable chosen picture is applied when the period starts (the side differs from
  `last_target_seen`) and the run does nothing for that side inside the period. The apply path takes `chosen = true`, which
  skips only the resolution-quality verdicts and falls back to the blurred frame fill; rejection, safety and unreadable
  sizes still refuse. The side is recorded as handled once something reached the screen, so a chosen picture that fails
  is replaced for that period instead of being retried at every look; if nothing reached the screen the period stays open.
  Follow-OS polls that end early also record the side. Chosen ids are kept with the current and previous picture by cache eviction and the daily
  clean-up, `ReserveBuffer.needsTopUp` ignores a side that holds one, and Dislike/Remove clear the slot when committed.
- **Recovery:** a time or time-zone change, a restart (`RECEIVE_BOOT_COMPLETED`) and opening the app all re-arm the
  schedule. If the database cannot open, automation pauses and a light *store-watch* job retries every 6 hours.
- Every background run ends by folding the database's write-ahead log into the main file so a backup taken while the
  app is closed holds the latest data.

## Where things are stored (Articles VIII, XIV)

| What | Where | Backed up |
|---|---|---|
| Settings | `shared_prefs/sunloom_preferences.xml` | yes |
| Library, blocklist, rejection log | `databases/sunloom_library.db` (versioned schema, non-destructive migrations) | yes |
| Downloaded and added pictures | `files/wallpapers/` | no |
| Quarantine | `noBackupFilesDir/quarantine/` | no |
| Rendered offline seeds, previews | `cacheDir` (the system may reclaim it) | no |
| Interface choices (grid or list, single-key shortcuts) | `shared_prefs/sunloom_ui.xml` | no |
| Worked-out state (what a Follow-OS look verified, when the Change every wait started, the side last handled for the Daytime/Evening wallpapers) | `shared_prefs/sunloom_state.xml` | no |
| Crash log (last five) | app private storage | no |

After a restore the database is back but the pictures are not; at start-up, rows whose file is missing are marked
evicted (Article IX §5) and the next sync fetches them again. Added photos cannot be fetched again, so they are marked
gone and the person is told once.

## The screens

`MainActivity` creates `MainViewModel`, which holds one `SunloomUiState` and sends every change through
`SunloomGateway` (`AndroidSunloomGateway` in production, fakes in tests). The gateway reads and writes the library and
calls the platform adapter; it returns a fresh state. `SunloomScreens.kt` lays out the five sections (Home, Reserve,
Sources, Schedule, Storage & About) as a bottom bar, a rail or a wide rail depending on width. Words come from
`strings.xml`; `labels/Labels.kt` turns each enum into its words.

## Two flavors (Article XVIII §1)

`github` and `play` share one application ID and one signing key. Only `src/github/` contains the update check
(`update/GithubUpdateChecker`, its own two-host list in `UpdateHosts`); `src/main/` sees it only through the
`UpdateCheckFeature` interface, and the Play flavor's tests prove none of its classes are present.

## Tests mirror the structure

JVM and Robolectric tests sit under `app/src/test` in the same packages; `app/src/androidTest` holds the emulator
checks. `@Requirement("X.Y")` ties a test to a Constitution section, and `ConstitutionTraceabilityTest` keeps the two in
step. See [CONTRIBUTING.md](../CONTRIBUTING.md).
