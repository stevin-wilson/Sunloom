# Releasing Sunloom

For maintainers. The rules are in Constitution Articles XVII and XVIII.

## Versions

A release is a git tag `vMAJOR.MINOR.PATCH` (final) or `vMAJOR.MINOR.PATCH-rc.N` (release candidate, N from 1 to 8).
`scripts/release_version.py` turns the tag into the version name and the Android version code:

```
versionCode = MAJOR*100000 + MINOR*1000 + PATCH*10 + (N, or 9 for a final)
```

So `v2.0.0-rc.1` is 200001, `v2.0.0-rc.2` is 200002 and `v2.0.0` is 200009: every candidate sorts below its final, and
Android accepts the final as an upgrade. The Play build will use the same sequence. Never reuse a tag.

## The signing key

One key signs every release, on both distribution paths (GitHub now, Google Play later via "use my own key"). If it is
lost, nobody can update in place. **Keep an offline copy**, and write the passwords down separately from the file.

Make a key (once):

```
keytool -genkeypair -v -keystore sunloom-release.jks -alias sunloom -keyalg RSA -keysize 4096 -validity 10000
```

Print its fingerprint to publish in the release notes and check it matches what the APK reports:

```
keytool -list -v -keystore sunloom-release.jks -alias sunloom | grep SHA256
```

Never commit a key, a password or `keystore.properties` (they are in `.gitignore`).

## Secrets

The release workflow reads four secrets: `SUNLOOM_UPLOAD_KEYSTORE_BASE64` (`base64 -w0 sunloom-release.jks`),
`SUNLOOM_UPLOAD_STORE_PASSWORD`, `SUNLOOM_UPLOAD_KEY_ALIAS`, `SUNLOOM_UPLOAD_KEY_PASSWORD`.

- Real key: store them in the **`release` environment** (Settings → Environments), with yourself as required reviewer,
  so a tag push waits for your approval. A private repository on a free plan cannot protect environments, which is why
  the repository goes public before the first release candidate.
- Rehearsal: before then, put a **throwaway** key in repository-level secrets and run the workflow by hand (below).
  Delete those secrets when the real ones are in.

## Making a release

1. Make sure `main` is green (CI, and *Emulator tests* run by hand).
2. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [X.Y.Z]` (or `## [X.Y.Z-rc.N]`) with the date, and start a new
   empty `[Unreleased]`. The workflow uses that section as the release notes.
3. Tag and push: `git tag vX.Y.Z-rc.1 && git push origin vX.Y.Z-rc.1`.
4. Approve the run in the `release` environment. It builds `assembleGithubRelease`, checks the version, runs
   `apksigner verify` (v2 and v3 signatures required), writes `SHA256SUMS`, keeps R8's mapping as an artifact, and
   creates a **draft** release. Candidates are marked pre-release.
5. Download the draft's APK, check `sha256sum -c`, install it, and work through the checklist below.
6. Add the certificate fingerprint to the release notes, then publish the draft. It is the latest release only once
   it is a final.

Rehearsal without a tag: *Actions → Release → Run workflow* with a tag name; it uses repository-level secrets and no
environment, and still only drafts.

## Manual QA on a Googlebook (before every final)

Uninstall any older build first: a build signed with a different key cannot be updated in place, and Auto Backup will
not restore the library across a key change.

- [ ] Fresh install with no network: the app starts, offline pictures appear, a wallpaper can be set.
- [ ] Go online: the Reserve fills; the sources you enabled each contribute; NASA stays off until switched on.
- [ ] Setup: **Start with defaults**; also **Customize** through all four steps and Back.
- [ ] Each schedule: sunrise & sunset, a custom schedule, manual, follow system theme (flip the system theme).
- [ ] Sunset timing with the device idle: `adb shell dumpsys deviceidle force-idle`, then check the change happens.
- [ ] Plug in and unplug a second monitor; the wallpaper is refitted once, not repeatedly.
- [ ] Memory during an apply at your largest monitor: `adb shell dumpsys meminfo com.sunloom.app` peaks near 130 MB for a 24 MP photo on a 4K screen (Constitution XII §2); note the figure.
- [ ] Add a photo (picker), drop one on the window, share one from the file manager, open one with Sunloom.
- [ ] Keep, Dislike (with Undo), Remove several at once; a Disliked picture never returns after a sync.
- [ ] Metered network and Data Saver: background fetches stop; Refresh warns once.
- [ ] Reset Sunloom, then set up again.
- [ ] Check for updates: from an older release candidate it says a newer version is available and opens its page.
- [ ] Obtainium tracks the repository and offers the new release.
- [ ] TalkBack spot check on Home, Reserve and Schedule; 200 percent font; a 400 dp window.
- [ ] Back up and restore (`adb shell bmgr backupnow com.sunloom.app`, reinstall): the library returns, pictures are
      fetched again, a message says how many of your own photos could not be restored.
- [ ] `apksigner verify --print-certs` shows the published fingerprint.

## Google Play, later

The Play flavor is the same app signed with the same key, built as a bundle, without the update check. Before the
first Play upload: enrol the app in Play App Signing with your own key, fill in the Data safety form from the
[privacy policy](PRIVACY_POLICY.md) (no data collected or shared), declare the permissions listed in the
[README](../README.md), and host the policy at a stable address. No Play account or listing exists yet.
