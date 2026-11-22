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
./gradlew :app:mutationDomain      # mutation report for the domain layer (minutes; a report to read, not a gate)
SUNLOOM_LIVE_CHECK=1 ./gradlew :app:testGithubDebugUnitTest --tests '*LiveSourceContractCheck*' --rerun   # real catalogs, polite; CI runs it weekly
```

Run one class with `--tests 'com.sunloom.app.domain.safety.SafetyGateTest'`.

`mutationDomain` (PIT) changes the compiled domain classes one small way at a time and re-runs the domain tests; a
change no test notices ("survived") is a missing or weak assertion. The report is
`app/build/reports/pitest/domain/index.html`. Narrow it while working on one class:
`-Psunloom.mutation.classes='com.sunloom.app.domain.safety.SafetyGate*' -Psunloom.mutation.tests='com.sunloom.app.domain.safety.*'`.
Not every survivor is a gap (a mutation that changes no behavior is an "equivalent mutant"); read the line before
writing a test, and never write one that only mirrors the code. CI does not run it.

## Rules that are easy to miss

- **Failing test first**, then the smallest change, then tidy. Say in the PR what you ran and what you did not.
- **Name the rule.** A test class that guards a Constitution rule carries `@Requirement("XII.1")`.
  `ConstitutionTraceabilityTest` fails when a rule has no test or a test names a rule that does not exist.
  A section split into clauses (`**(a)**`, `**(b)**` ... in `CONSTITUTION.md`, ids like `XVI.1.a`) needs a test for
  **each clause**; a test naming only `XVI.1` does not cover them. A clause can instead be proved by a marker in a
  workflow file (`workflowEvidence` in that test) or listed in `unverifiable` with the reason.
- **Behavior change means amend the Constitution in the same PR**: version bump, a row in the amendment log, and a
  `**[v0.NN]**` tag on the changed sentence. Never renumber an article or section.
- **Coverage floors only go up** (`app/coverage-floors.csv`, line coverage and, for domain packages, branch coverage).
  Raise a floor in the PR that raises the coverage, one point under what CI measured. The arm64 dev box skips the
  real-SQLite and WorkManager tests, so a local `:app:coverageFloors` fails for `platform.scheduler`; CI is the source
  of truth. To get CI's real numbers, download the `reports` artifact of a CI run (`gh run download <id> -n reports`).
- **Do not add to `app/detekt-baseline.xml`.** Changing a composable's signature changes its entry: run
  `./gradlew detektBaseline` and check the diff only removes or renames entries. Lint warnings are errors.
- **Words belong in `strings.xml`** in plain language (`HardcodedMessageScanTest`, `PlainLanguageTest`).
- **Nothing leaves the device.** A new host needs a `SourceRegistry` entry, the privacy policy and the README
  (`DocumentationTest` checks).
- **Two flavors.** Only `src/github/` may contain update-check code (`NoUpdateCodeTest`). Do not change `targetSdk`
  or remove `abiFilters` without asking.
- **Rules that must hold for every input are properties.** `Property.forAll("name") { case -> ... }`
  (`app/src/test/java/com/sunloom/app/Property.kt`) runs a check against many random inputs from a fixed seed, so a run is
  the same every time. Describe the input in `case.input`; a failure reports the case, the seed to replay it alone
  (`cases = 1, seed = ...`) and that input. There is no shrinking, so keep inputs small. Take the rule from the
  Constitution, not from the code, or the test only mirrors what the code does (see `CropGeometryPropertyTest`).
- **A Stop hook runs the quick checks when you finish** (`.claude/settings.json` runs `scripts/stop_gate.py`). With
  uncommitted changes it runs what they call for: `:app:testDomain` and `detekt` for domain or main code, the
  documentation and Constitution-traceability tests for docs, tests or Constitution edits, the Python tests for
  `scripts/`. If one fails it blocks you once and shows the failing test and its message: fix it. It remembers what it
  already verified and never blocks on a timeout. `SUNLOOM_SKIP_STOP_GATE=1` skips it. It is a subset of CI (lint,
  R8, the Play flavor, coverage floors and the emulator tests stay CI's job).
- **CI warns when a Constitution section changes and no test in the same PR names it** (`scripts/check_spec_tests.py`,
  warning only). Add or retag a test with `@Requirement`, or say in the PR why none is needed.
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
