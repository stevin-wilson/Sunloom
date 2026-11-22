#!/usr/bin/env python3
"""Keep one GitHub issue in step with the weekly live-source check (`.github/workflows/source-drift.yml`).

The check (`LiveSourceContractCheck`) calls the real catalogs and writes a Markdown report whose `Result:` line is FAILED, PASSED,
PARTIAL or INCONCLUSIVE. FAILED (a source changed) opens one issue labelled `source-drift`, or adds the new report to the one
already open. PASSED (every source could be judged and none had changed) says so and closes it. PARTIAL and INCONCLUSIVE (some
or all sources could not be reached) leave everything as it is, and so does a missing report: a build or network failure is not
a source changing. The workflow turns such a run red separately.

Usage: source_drift_issue.py --report PATH [--run-url URL]
Needs the `gh` command and GH_TOKEN (the workflow supplies both), and only calls `gh` when there is something to do. The
repository is public, so the issue holds only the report table, with long lines cut.
"""
import argparse
import subprocess
import sys
from pathlib import Path

LABEL = "source-drift"
TITLE = "Live source check: a source has changed"
MAX_LINE = 400
MAX_BODY = 60_000


def parse_result(report) -> str:
    """The `Result:` of the report, or "" when there is no report or it states none (the check did not get as far as judging)."""
    for line in (report or "").splitlines():
        if line.startswith("Result:"):
            return line.split(":", 1)[1].strip().upper()
    return ""


def decide_action(result: str, open_issue):
    """What to do about the issue: open, update, close, or nothing. [open_issue] is the open issue's number, or None."""
    if result == "FAILED":
        return "update" if open_issue else "open"
    if result == "PASSED":
        return "close" if open_issue else "none"
    return "none"  # PARTIAL, INCONCLUSIVE, or no verdict at all: nothing was learned about a change


def trim_report(report: str) -> str:
    """The report with each long line cut and the whole kept to a size an issue accepts."""
    lines = [line if len(line) <= MAX_LINE else line[: MAX_LINE - 1] + "…" for line in report.splitlines()]
    text = "\n".join(lines)
    return text if len(text) <= MAX_BODY else text[: MAX_BODY - 1] + "…"


def build_body(report: str, run_url: str, heading: str) -> str:
    link = f"\n\n[Workflow run]({run_url})" if run_url else ""
    return f"{heading}\n\n{trim_report(report)}{link}\n"


def _gh(*args: str, check: bool = True) -> str:
    done = subprocess.run(["gh", *args], capture_output=True, text=True)
    if check and done.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed: {done.stderr.strip()[:300]}")
    return done.stdout.strip()


def find_open_issue():
    number = _gh("issue", "list", "--label", LABEL, "--state", "open", "--json", "number", "--jq", ".[0].number // empty")
    return int(number) if number else None


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True)
    parser.add_argument("--run-url", default="")
    args = parser.parse_args(argv)

    report_path = Path(args.report)
    report = report_path.read_text(encoding="utf-8") if report_path.is_file() else None
    result = parse_result(report)
    if result not in ("FAILED", "PASSED"):
        print(f"result={result or 'none'}: nothing to do")  # no gh call: it needs neither the issue nor the network
        return 0
    issue = find_open_issue()
    action = decide_action(result, issue)
    print(f"result={result} open issue={issue} action={action}")
    if action == "none":
        return 0
    _gh("label", "create", LABEL, "--force", "--color", "D93F0B", "--description", "A live source changed and its adapter needs a look")
    if action == "open":
        _gh("issue", "create", "--title", TITLE, "--label", LABEL, "--body", build_body(report, args.run_url, "The weekly live-source check found drift."))
    elif action == "update":
        _gh("issue", "comment", str(issue), "--body", build_body(report, args.run_url, "Still failing in the latest weekly run."))
    elif action == "close":
        _gh("issue", "comment", str(issue), "--body", build_body(report, args.run_url, "The latest weekly run passed, with every source judged."))
        _gh("issue", "close", str(issue))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
