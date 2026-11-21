# Sunloom: guide for coding agents

Sunloom (`com.sunloom.app`) is a wallpaper app for Googlebook and Android. The [Constitution](CONSTITUTION.md) says what
it must do, tests prove it, and the code follows. [CONTRIBUTING.md](CONTRIBUTING.md) is the full contributor guide; this
file is the part you cannot work out from the code.

## Commands

```
./gradlew :app:testDomain          # quick loop: only the domain tests, plain JVM (seconds, not minutes)
./gradlew testGithubDebugUnitTest testPlayDebugUnitTest lintGithubDebug lintPlayDebug detekt   # what CI runs; run before a PR
./gradlew :app:minifyGithubReleaseWithR8 :app:minifyPlayReleaseWithR8                          # a bad keep rule fails here
./gradlew :app:coverageFloors      # CI's gate; local numbers are lower (see below)
```

Run one class with `--tests 'com.sunloom.app.domain.safety.SafetyGateTest'`.

## Rules that are easy to miss

- **Failing test first**, then the smallest change, then tidy. Say in the PR what you ran and what you did not.
- **Name the rule.** A test class that guards a Constitution rule carries `@Requirement("XII.1")`.
  `ConstitutionTraceabilityTest` fails when a rule has no test or a test names a rule that does not exist.
- **Behavior change means amend the Constitution in the same PR**: version bump, a row in the amendment log, and a
  `**[v0.NN]**` tag on the changed sentence. Never renumber an article or section.
- **Coverage floors only go up** (`app/coverage-floors.csv`). Raise a floor in the PR that raises the coverage.
  The arm64 dev box skips the real-SQLite and WorkManager tests, so local coverage sits below CI's: CI is the
  source of truth for those packages.
- **Do not add to `app/detekt-baseline.xml`.** Changing a composable's signature changes its entry: run
  `./gradlew detektBaseline` and check the diff only removes or renames entries. Lint warnings are errors.
- **Words belong in `strings.xml`** in plain language (`HardcodedMessageScanTest`, `PlainLanguageTest`).
- **Nothing leaves the device.** A new host needs a `SourceRegistry` entry, the privacy policy and the README
  (`DocumentationTest` checks).
- **Two flavors.** Only `src/github/` may contain update-check code (`NoUpdateCodeTest`). Do not change `targetSdk`
  or remove `abiFilters` without asking.
- **The domain layer imports nothing from `android.*`** and its tests have no `@RunWith`; keep it that way.
- Plans and working notes go in `.notes/` (gitignored), not in the repository.

## Which article to read

Read Article I and the article for the part you are changing; you rarely need more.

- Article I: core product promise
- Article II: user sovereignty (the person decides)
- Article III: time and appearance (solar matching)
- Article IV: display awareness and image quality (crops, sizes)
- Article V: lock and workspace screens
- Article VI: image sources, rights and attribution (Schedule A lists the sources)
- Article VII: content safety and personal taste (Schedule B holds the safety acceptance tests)
- Article VIII: privacy and data minimization
- Article IX: offline reliability and reserve management
- Article X: resource restraint and network budgets
- Article XI: matching, personalization and explainability
- Article XII: onboarding and user interface conduct (accessibility, plain words)
- Article XIII: modular architecture (what may depend on what)
- Article XIV: schema evolution (database migrations)
- Article XV: strict parsing (untrusted input)
- Article XVI: automated verification (how rules are tested; coverage floors)
- Article XVII: release integrity (signing, versions, the release workflow)
- Article XVIII: platform and distribution (Googlebook, flavors, permissions)

Layout: `app/src/main/java/com/sunloom/app/{domain,data,platform,ui,diagnostics,update}`; tests mirror it under
`app/src/test`. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the modules.
