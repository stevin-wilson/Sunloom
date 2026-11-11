#!/usr/bin/env python3
"""Derive versionName and versionCode from a release tag (Constitution Article XVII).

    vMAJOR.MINOR.PATCH[-rc.N]  ->  versionCode = MAJOR*100000 + MINOR*1000 + PATCH*10 + (N or 9)

A release candidate always sorts below its final release (rc.1..rc.8 -> ...1..8, final -> ...9), so Android
accepts the final as an upgrade from any candidate. Both flavors (github, play) use the same sequence.
"""
import re
import sys

_TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?\Z")
_MAX_INT32 = 2_100_000_000


def parse_tag(tag: str) -> tuple[str, int]:
    match = _TAG.match(tag)
    if not match:
        raise ValueError(f"not a release tag (expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc.N): {tag!r}")
    major, minor, patch = (int(g) for g in match.groups()[:3])
    rc = match.group(4)
    if minor > 99 or patch > 99:
        raise ValueError(f"minor and patch must be at most 99: {tag!r}")
    if rc is None:
        suffix = 9
    else:
        suffix = int(rc)
        if not 1 <= suffix <= 8:
            raise ValueError(f"release candidate number must be 1..8: {tag!r}")
    code = major * 100000 + minor * 1000 + patch * 10 + suffix
    if code > _MAX_INT32:
        raise ValueError(f"version too large for an Android versionCode: {tag!r}")
    name = tag[1:]
    return name, code


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: release_version.py vMAJOR.MINOR.PATCH[-rc.N]")
    try:
        version_name, version_code = parse_tag(sys.argv[1])
    except ValueError as error:
        sys.exit(str(error))
    print(f"versionName={version_name}")
    print(f"versionCode={version_code}")
