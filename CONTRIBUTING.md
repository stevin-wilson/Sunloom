# Contributing to Sunloom

Thank you for helping. Sunloom is a small project with a strict habit: **the [Constitution](CONSTITUTION.md) says what
the app must do, tests prove it, and the code follows.** Read the Constitution's "How to read this" note first; you
rarely need more than the article your change touches.

## Build and run

You need JDK 17 and the Android SDK (API 36). Then:

```
./gradlew assembleGithubDebug          # the GitHub flavor (has the update check)
./gradlew assemblePlayDebug            # the Play flavor (no update code at all)
```

Install `app/build/outputs/apk/github/debug/` on a Googlebook, a device, or an emulator. Ask a maintainer before
changing `targetSdk`.

## The checks CI runs

```
./gradlew testGithubDebugUnitTest testPlayDebugUnitTest lintGithubDebug lintPlayDebug detekt
./gradlew :app:minifyGithubReleaseWithR8 :app:minifyPlayReleaseWithR8     # a bad keep rule fails here
./gradlew :app:coverageFloors                                               # CI only
python3 -m unittest discover -s scripts -p 'test_*.py'                      # the release script
```

- `coverageFloors` compares each package's line coverage with `app/coverage-floors.csv`. It runs in CI only: on
  arm64 machines the tests that need real SQLite or WorkManager are skipped, so local numbers sit lower. **Raise a
  floor in the same PR that raises a package's coverage; never lower one to make CI pass.**
- Lint warnings are errors. Detekt has a baseline (`app/detekt-baseline.xml`); don't add to it. If you change a
  composable's signature its baseline entry changes: run `./gradlew detektBaseline` and check the diff only removes or
  renames entries.
- `androidTest` (an emulator) is run by hand from GitHub Actions (*Emulator tests*); it checks what Robolectric
  cannot: real SQLite, the real wallpaper service, on-device image decoding.

## How we work

1. **Write the failing test first.** Then the smallest change that passes it. Then tidy.
2. **Name the rule.** Every test class that guards a Constitution rule carries `@Requirement("XII.1")` (article and
   section). `ConstitutionTraceabilityTest` fails if a rule has no test or a test names a rule that does not exist.
3. **Amend the Constitution in the same PR** when behavior changes: add a row to the amendment log, bump the version,
   tag the changed sentence `**[v0.NN]**`. Keep the `## Article X:` headings and section numbers unchanged.
4. **Words go in `strings.xml`.** No sentence-like literal in `ui/` (`HardcodedMessageScanTest`); names of choices are
   read through `labels/Labels.kt`. Plain words, not engineering terms (`PlainLanguageTest`).
5. **Look after everyone.** Every control has a name and a 48 dp hit area, text meets contrast, and the screen works at
   400 dp and 200 percent text (`Accessibility*Test`, `DesignSystemTest`).
6. **Nothing leaves the device.** A new host needs an entry in `SourceRegistry` (with its terms and review date), the
   privacy policy, and `DocumentationTest` will check you did.
7. **Small PRs, honest descriptions.** Say what you ran. If something is untested, say so.

## Reporting bugs and ideas

Use the [issue forms](https://github.com/stevin-wilson/Sunloom/issues/new/choose). For a bug, paste the output of
Storage & About → Copy diagnostics. Security problems go through [SECURITY.md](SECURITY.md), privately.

## Licence

By contributing you agree your work is released under GPL-3.0-or-later, the licence of the project.
