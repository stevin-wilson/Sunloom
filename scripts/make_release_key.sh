#!/usr/bin/env bash
# Makes Sunloom's release signing key and puts it in GitHub, so the release workflow can sign (docs/RELEASING.md).
#
#   scripts/make_release_key.sh                 make the key, upload the four secrets to the `release` environment
#   scripts/make_release_key.sh --no-upload     make the key only (you upload it yourself)
#
# The password is typed here, never put on a command line, never printed, and never stored anywhere except inside the
# key file. The key lives OUTSIDE the repository, in $SUNLOOM_KEY_DIR (default ~/sunloom-release-key). Back it up
# yourself afterwards: if it is lost, nobody can update Sunloom in place.
set -euo pipefail

upload=1
[ "${1:-}" = "--no-upload" ] && upload=0

dir="${SUNLOOM_KEY_DIR:-$HOME/sunloom-release-key}"
store="$dir/sunloom-release.jks"
alias_name="sunloom"

command -v keytool >/dev/null || { echo "keytool not found: install a JDK (17 or newer)." >&2; exit 1; }
if [ "$upload" = 1 ]; then
  command -v gh >/dev/null || { echo "gh (GitHub CLI) not found, or use --no-upload." >&2; exit 1; }
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in: run 'gh auth login' first." >&2; exit 1; }
fi
[ -e "$store" ] && { echo "$store already exists. Refusing to overwrite a key." >&2; exit 1; }

# SUNLOOM_KEY_PASSWORD is for automated tests of this script only; people type the password.
if [ -n "${SUNLOOM_KEY_PASSWORD:-}" ]; then
  pw="$SUNLOOM_KEY_PASSWORD"
else
  read -rsp "Choose a password for the key (at least 12 characters, kept in your password manager): " pw; echo
  read -rsp "Type it again: " pw2; echo
  [ "$pw" = "$pw2" ] || { echo "The two passwords differ." >&2; exit 1; }
fi
[ "${#pw}" -ge 12 ] || { echo "Use at least 12 characters." >&2; exit 1; }

umask 077
mkdir -p "$dir"
export SUNLOOM_PW="$pw"
keytool -genkeypair -keystore "$store" -storetype PKCS12 -alias "$alias_name" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Sunloom, O=Sunloom authors" \
  -storepass:env SUNLOOM_PW -keypass:env SUNLOOM_PW >/dev/null 2>&1 \
  || { rm -f "$store"; echo "keytool failed." >&2; exit 1; }
chmod 600 "$store"

fingerprint=$(keytool -list -v -keystore "$store" -alias "$alias_name" -storepass:env SUNLOOM_PW 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -1)

if [ "$upload" = 1 ]; then
  repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
  echo "Uploading the four secrets to the 'release' environment of $repo ..."
  base64 -w0 "$store" | gh secret set SUNLOOM_UPLOAD_KEYSTORE_BASE64 --env release --repo "$repo"
  printf %s "$pw" | gh secret set SUNLOOM_UPLOAD_STORE_PASSWORD --env release --repo "$repo"
  printf %s "$alias_name" | gh secret set SUNLOOM_UPLOAD_KEY_ALIAS --env release --repo "$repo"
  printf %s "$pw" | gh secret set SUNLOOM_UPLOAD_KEY_PASSWORD --env release --repo "$repo"
  gh secret list --env release --repo "$repo"
fi
unset pw pw2 SUNLOOM_PW

cat <<DONE

Key made: $store
Certificate SHA-256 (public; goes in the release notes and the README):
  $fingerprint

NOW BACK IT UP, before you do anything else:
  1. Copy $store to at least two places that are not this computer (a USB stick kept somewhere safe, an encrypted
     cloud folder). Check each copy opens:  keytool -list -keystore <copy>
  2. Put the password in your password manager. A key file without its password is useless.
DONE
