#!/usr/bin/env python3
"""Warn when a pull request changes a Constitution section but no changed test names it.

The Constitution says what the app must do and tests prove it (`@Requirement("XII.4")` names the section a test guards).
When a section's text changes, some test in the same change should name that section: a new test, a retagged one, or an
edited one. This compares the sections touched in CONSTITUTION.md with the `@Requirement` ids in the test files changed in
the same pull request, and prints a GitHub warning for each section nothing names.

It only warns (exit 0), because a reworded sentence may need no new test; pass --strict to fail instead.
Usage: check_spec_tests.py [--base REF] [--strict]    (REF defaults to HEAD^1, the base of a pull request's merge commit)
"""
import re
import subprocess
import sys
from pathlib import Path

CONSTITUTION = "CONSTITUTION.md"
TEST_PREFIXES = ("app/src/test/", "app/src/testGithub/", "app/src/testPlay/")


def item_ids(lines: list) -> dict:
    """Maps each 1-based line number inside a numbered item of an Article to that item's id, such as `XII.4`."""
    ids = {}
    article = None
    item = None
    for number, line in enumerate(lines, start=1):
        if line.startswith("#"):
            if line.startswith("## "):
                heading = re.match(r"## Article ([IVXLC]+):", line)
                article = heading.group(1) if heading else None
            item = None  # any heading, including a ### subsection, ends the item above it
            continue
        if article is None:
            continue
        start = re.match(r"(\d+)\. ", line)
        if start:
            item = int(start.group(1))
        if item is not None:
            ids[number] = f"{article}.{item}"
    return ids


def changed_lines(diff: str) -> set:
    """New-file line numbers that a `git diff -U0` added or changed. A pure deletion adds none."""
    lines = set()
    for hunk in re.finditer(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@", diff, re.MULTILINE):
        start = int(hunk.group(1))
        count = int(hunk.group(2)) if hunk.group(2) is not None else 1
        lines.update(range(start, start + count))
    return lines


def changed_sections(constitution_lines: list, diff: str) -> list:
    ids = item_ids(constitution_lines)
    return sorted({ids[n] for n in changed_lines(diff) if n in ids})


def requirement_ids(text: str) -> set:
    """Every id quoted in a `@Requirement(...)` annotation, ignoring comments."""
    text = re.sub(r"/\*[\s\S]*?\*/", "", text)
    text = re.sub(r"//[^\n]*", "", text)
    found = set()
    for annotation in re.finditer(r"@Requirement\(([^)]*)\)", text):
        found.update(re.findall(r'"([^"]+)"', annotation.group(1)))
    return found


def uncovered(sections: list, test_texts: list) -> list:
    """The sections that no test text names, by its own id or by one of its clauses (`XVI.1` is named by `XVI.1.h`)."""
    named = set()
    for text in test_texts:
        named |= requirement_ids(text)
    return [s for s in sections if not any(i == s or i.startswith(s + ".") for i in named)]


def _git(*args: str) -> str:
    done = subprocess.run(["git", *args], capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or f"git {' '.join(args)} failed")
    return done.stdout


def main(argv: list) -> int:
    strict = "--strict" in argv
    base = argv[argv.index("--base") + 1] if "--base" in argv else "HEAD^1"
    try:
        files = _git("diff", "--name-only", base, "HEAD").splitlines()
        if CONSTITUTION not in files:
            print("The Constitution was not changed; nothing to check.")
            return 0
        diff = _git("diff", "-U0", base, "HEAD", "--", CONSTITUTION)
    except RuntimeError as problem:
        reason = (str(problem).splitlines() or ["unknown error"])[0]  # one line: a GitHub annotation ends at a newline
        print(f"::warning::Could not compare with {base} ({reason}); skipping the Constitution-versus-tests check.")
        return 0
    sections = changed_sections(Path(CONSTITUTION).read_text().splitlines(), diff)
    tests = [Path(f).read_text() for f in files if f.startswith(TEST_PREFIXES) and f.endswith(".kt") and Path(f).is_file()]
    missing = uncovered(sections, tests)
    for section in missing:
        print(
            f"::warning file={CONSTITUTION}::Constitution {section} changed, but no test changed in this pull request names it "
            f'with @Requirement("{section}"). Add or retag a test, or say in the description why none is needed.'
        )
    print(f"Constitution sections changed: {', '.join(sections) or 'none'}; with no test naming them: {', '.join(missing) or 'none'}.")
    return 1 if (missing and strict) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
