# Security policy

## Reporting a vulnerability

Please report a security problem **privately**, not in a public issue:

1. Open <https://github.com/stevin-wilson/Sunloom/security/advisories/new> (GitHub's private vulnerability report).
2. Say what you found, how to reproduce it, and which version you tested (Storage & About shows it).

You will get an answer within a week. Sunloom is a hobby project with one maintainer, so please allow time for a fix,
and keep the details private until a fixed version is out. Once it is, you are welcome to be credited.

## What counts

Anything that lets someone else run code in Sunloom, read or change its data, make it contact a host it should not,
install something through it, or put content on a person's wallpaper that its safety checks should have refused.

Sunloom's design limits the damage: it asks for almost no permissions (see the [README](README.md)), contacts a fixed
list of hosts (see the [privacy policy](docs/PRIVACY_POLICY.md)), downloads nothing it will run, and checks every
downloaded picture before use. The update check only opens a release page in your browser; it never installs anything.

## Supported versions

Only the latest release gets fixes.

## Verifying a download

Releases are signed with one key. The release notes give its SHA-256 fingerprint, and each release carries a
`SHA256SUMS` file. If a download does not match, do not install it, and tell us.
