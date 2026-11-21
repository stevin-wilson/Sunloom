## What and why

<!-- What does this change, and why? Link the issue if there is one. -->

## How it was checked

<!-- The failing test you wrote first, the commands you ran, what you tried by hand. Say what you did NOT test. -->

- [ ] I wrote the failing test first
- [ ] `./gradlew testGithubDebugUnitTest testPlayDebugUnitTest lintGithubDebug lintPlayDebug detekt` passes
- [ ] The Constitution sections or clauses I touched are named by a test in this PR (`@Requirement`); CI warns if not
- [ ] Coverage went up: I raised the floor in `app/coverage-floors.csv` in this PR
- [ ] Behavior changed: I amended [the Constitution](../CONSTITUTION.md) (version, log row, `[v0.NN]` tag) and the tests carry `@Requirement`
- [ ] Words are in `strings.xml`, in plain language
- [ ] A new host or permission is in the privacy policy and the README
- [ ] The changelog's `[Unreleased]` section mentions this, if a user would notice
