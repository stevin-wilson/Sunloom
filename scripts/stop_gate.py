#!/usr/bin/env python3
"""A Claude Code Stop hook: run the quick checks before an agent says it is finished.

Registered in .claude/settings.json. When an agent stops with uncommitted changes, this works out which of the project's
quick checks those changes call for, runs them, and, if one fails, tells the agent to fix it before finishing. It never
blocks more than once in a row, never blocks on a timeout, and skips work it has already verified.

Skip it for one session with SUNLOOM_SKIP_STOP_GATE=1. The full set of checks is still what CI runs (CONTRIBUTING.md).
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree
from pathlib import Path

# The documentation and Constitution-traceability tests: quick, and the ones a Constitution, docs or test edit can break.
DOCS_CHECK = [
    "./gradlew", "-q", "testGithubDebugUnitTest",
    "--tests", "com.sunloom.app.DocumentationTest",
    "--tests", "com.sunloom.app.ConstitutionTraceabilityTest",
]
PYTHON_CHECK = ["python3", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"]

# One deadline for the whole run, shorter than the hook's own timeout in .claude/settings.json, so this script (which
# explains itself) is the one that gives up, not the harness (which would kill it silently).
TOTAL_SECONDS = 900
NOT_RUN = "not-run"  # a command that could not be started: neither a pass nor a failure
IGNORED_PREFIXES = (".notes/", "build/", "app/build/", ".gradle/", ".idea/", ".kotlin/", "__pycache__/")
BUILD_FILES = {"build.gradle.kts", "app/build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml"}
DOC_SUFFIXES = (".md", ".yml", ".yaml", ".csv")
TEST_PREFIXES = ("app/src/test/", "app/src/testGithub/", "app/src/testPlay/")
DOMAIN_MAIN_PREFIX = "app/src/main/java/com/sunloom/app/domain/"


def changed_paths(porcelain_z: str) -> list:
    """The file paths in `git status --porcelain -z` output (the new name of a rename), without duplicates.

    With -z git ends each entry with NUL and never quotes a path, so names with spaces or accents arrive as they are;
    a rename or copy is followed by one extra field holding its old name.
    """
    paths = []
    fields = porcelain_z.split("\0")
    i = 0
    while i < len(fields):
        entry = fields[i]
        i += 1
        if len(entry) < 4:
            continue
        status, path = entry[:2], entry[3:]
        if "R" in status or "C" in status:
            i += 1  # skip the old name
        if path not in paths:
            paths.append(path)
    return paths


def plan(paths: list) -> list:
    """The checks (as argument lists) that these changed files call for, quickest and most specific first."""
    paths = [p for p in paths if not p.startswith(IGNORED_PREFIXES)]
    if not paths:
        return []
    build = any(p in BUILD_FILES or p.startswith(("config/detekt/", "gradle/")) for p in paths)
    in_tests = [p for p in paths if p.startswith(TEST_PREFIXES)]
    domain_code = any(p.startswith(DOMAIN_MAIN_PREFIX) for p in paths)
    domain_tests = any("/domain/" in p for p in in_tests) or any(
        p.startswith("app/src/test/java/com/sunloom/app/Property") or p.startswith("app/src/test/resources/golden/") for p in paths
    )
    main_kotlin = any(p.startswith("app/src/main/java/") and p.endswith(".kt") for p in paths)
    docs = any(p.endswith(DOC_SUFFIXES) or p.startswith(("docs/", ".github/", ".claude/")) for p in paths)

    commands = []
    if any(p.startswith("scripts/") for p in paths):
        commands.append(PYTHON_CHECK)
    tasks = []
    if domain_code or domain_tests or build:
        tasks.append(":app:testDomain")
    if main_kotlin or build:
        tasks.append("detekt")
    if tasks:
        commands.append(["./gradlew", "-q"] + tasks)
    if in_tests or docs or build:
        commands.append(DOCS_CHECK)
    return commands


def _stat(path: str):
    info = os.stat(path)
    return info.st_size, info.st_mtime_ns


def fingerprint(paths: list, stat=_stat) -> str:
    """A short hash of which files are changed and how, so unchanged work is not checked twice."""
    parts = []
    for path in sorted(paths):
        try:
            size, mtime = stat(path)
            parts.append(f"{path}\t{size}\t{mtime}")
        except OSError:
            parts.append(f"{path}\tdeleted")
    return hashlib.sha256("\n".join(parts).encode()).hexdigest()


def failed_tests(root: Path, since: float) -> list:
    """`Class > test: message` for every test that failed in a Gradle report written since [since].

    Gradle's quiet output says only how many tests failed; the reports have the names and the assertion messages.
    """
    found = []
    for report in sorted((root / "app" / "build" / "test-results").glob("*/TEST-*.xml")):
        try:
            if report.stat().st_mtime < since:
                continue
            suite = ElementTree.parse(report).getroot()
        except (OSError, ElementTree.ParseError):
            continue
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                short_class = (case.get("classname") or suite.get("name") or "").rsplit(".", 1)[-1]
                message = " ".join((problem.get("message") or problem.get("type") or "").split())
                found.append(f"{short_class} > {case.get('name')}: {message[:300]}")
    return found


def _tail(output: str, lines: int = 40) -> str:
    return "\n".join(output.strip().splitlines()[-lines:])


def decide(event: dict, env: dict, paths: list, run, last_ok: str, current: str):
    """What the hook should print, and the fingerprint to remember as verified (or None).

    `run(argv)` returns (exit code, output); an exit code of None means it timed out.
    """
    if env.get("SUNLOOM_SKIP_STOP_GATE") == "1":
        return None, None
    commands = plan(paths)
    if not commands or (current and current == last_ok):
        return None, None
    for argv in commands:
        code, output = run(argv)
        command = " ".join(argv)
        if code == NOT_RUN:
            return {"systemMessage": f"Stop gate: `{command}` could not be started, so your changes were not verified."}, None
        if code is None:
            return {"systemMessage": f"Stop gate: `{command}` timed out, so your changes were not verified."}, None
        if code != 0:
            detail = _tail(output)
            if event.get("stop_hook_active"):
                return {"systemMessage": f"Stop gate: `{command}` is still failing; not blocking again.\n{detail}"}, None
            return {
                "decision": "block",
                "reason": (
                    f"The quick checks failed after your changes: `{command}`. Fix this before you finish "
                    f"(set SUNLOOM_SKIP_STOP_GATE=1 to skip the gate if it is wrong). End of the output:\n{detail}"
                ),
            }, None
    return None, current


def runner(root: Path, total_seconds: float = TOTAL_SECONDS):
    """Runs commands in [root], all of them within one shared deadline of [total_seconds]."""
    deadline = time.monotonic() + total_seconds

    def run(argv):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None, ""
        started = time.time() - 1
        try:
            done = subprocess.run(argv, cwd=root, capture_output=True, text=True, timeout=remaining)
        except subprocess.TimeoutExpired:
            return None, ""
        except OSError:
            return NOT_RUN, ""  # no gradlew or python3 here: say so, and do not remember these changes as verified
        output = (done.stdout or "") + (done.stderr or "")
        if done.returncode != 0:
            failures = failed_tests(root, started)
            if failures:
                # At the end: the message shows only the end of the output.
                output = output + "\n\nFailed tests:\n" + "\n".join(failures[:15])
        return done.returncode, output

    return run


def main() -> int:
    raw = "" if sys.stdin.isatty() else sys.stdin.read()
    try:
        event = json.loads(raw) if raw.strip() else {}
    except ValueError:
        event = {}
    root = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parent.parent)
    os.chdir(root)
    status = subprocess.run(["git", "status", "--porcelain", "-z", "-uall"], capture_output=True, text=True)
    paths = changed_paths(status.stdout if status.returncode == 0 else "")
    state = root / "build" / "stop-gate-ok"
    last_ok = state.read_text().strip() if state.is_file() else ""
    current = fingerprint(paths)
    output, verified = decide(event, os.environ, paths, runner(root), last_ok, current)
    if verified:
        state.parent.mkdir(parents=True, exist_ok=True)
        state.write_text(verified)
    if output:
        print(json.dumps(output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
