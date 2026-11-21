import os
import tempfile
import time
import unittest
from pathlib import Path

from stop_gate import (
    DOCS_CHECK,
    NOT_RUN,
    PYTHON_CHECK,
    changed_paths,
    decide,
    failed_tests,
    fingerprint,
    plan,
    runner,
)

DOMAIN_MAIN = "app/src/main/java/com/sunloom/app/domain/solar/SolarCalculator.kt"
DOMAIN_TEST = "app/src/test/java/com/sunloom/app/domain/renderer/CropGeometryPropertyTest.kt"
UI_MAIN = "app/src/main/java/com/sunloom/app/ui/MainActivity.kt"
UI_TEST = "app/src/test/java/com/sunloom/app/ui/AccessibilityTest.kt"


def gradle_tasks(commands):
    """The Gradle task words of every command, flattened, so a test can ask 'is detekt in there?'."""
    return [word for argv in commands if argv and argv[0].endswith("gradlew") for word in argv[1:]]


class ChangedPathsTest(unittest.TestCase):
    def test_reads_modified_added_untracked_renamed_and_oddly_named_files_from_nul_separated_output(self):
        # `git status --porcelain -z`: entries end in NUL, paths are never quoted, and a rename is followed by its old name.
        porcelain = (
            " M app/src/main/java/A.kt\0"
            "A  app/src/test/java/BTest.kt\0"
            "?? scripts/new.py\0"
            "R  docs/new name.md\0old/name.md\0"
            "D  gone with space.kt\0"
            "?? docs/caf\u00e9.md\0"
        )
        self.assertEqual(
            changed_paths(porcelain),
            [
                "app/src/main/java/A.kt", "app/src/test/java/BTest.kt", "scripts/new.py",
                "docs/new name.md", "gone with space.kt", "docs/caf\u00e9.md",
            ],
        )

    def test_empty_status_means_no_changes(self):
        self.assertEqual(changed_paths(""), [])
        self.assertEqual(changed_paths("\0"), [])


class PlanTest(unittest.TestCase):
    def test_nothing_changed_means_nothing_to_run(self):
        self.assertEqual(plan([]), [])

    def test_private_notes_and_build_output_are_never_checked(self):
        self.assertEqual(plan([".notes/plan.md", "build/x.txt", "app/build/y.txt"]), [])

    def test_a_domain_source_change_runs_the_domain_tests_and_detekt(self):
        tasks = gradle_tasks(plan([DOMAIN_MAIN]))
        self.assertIn(":app:testDomain", tasks)
        self.assertIn("detekt", tasks)

    def test_a_domain_test_change_runs_the_domain_tests_but_not_detekt(self):
        tasks = gradle_tasks(plan([DOMAIN_TEST]))
        self.assertIn(":app:testDomain", tasks)
        self.assertNotIn("detekt", tasks)

    def test_a_ui_source_change_runs_only_detekt_to_stay_quick(self):
        tasks = gradle_tasks([argv for argv in plan([UI_MAIN]) if argv != DOCS_CHECK])
        self.assertEqual(tasks, ["-q", "detekt"])

    def test_any_test_file_or_the_constitution_runs_the_documentation_and_traceability_tests(self):
        self.assertIn(DOCS_CHECK, plan([UI_TEST]))
        self.assertIn(DOCS_CHECK, plan(["CONSTITUTION.md"]))
        self.assertIn(DOCS_CHECK, plan(["docs/USER_GUIDE.md"]))
        self.assertIn(DOCS_CHECK, plan(["app/coverage-floors.csv"]))

    def test_the_documentation_check_names_exactly_those_two_test_classes(self):
        joined = " ".join(DOCS_CHECK)
        self.assertIn("com.sunloom.app.DocumentationTest", joined)
        self.assertIn("com.sunloom.app.ConstitutionTraceabilityTest", joined)

    def test_a_script_change_runs_the_python_tests(self):
        self.assertIn(PYTHON_CHECK, plan(["scripts/stop_gate.py"]))

    def test_build_files_run_everything_quick(self):
        commands = plan(["app/build.gradle.kts"])
        tasks = gradle_tasks(commands)
        self.assertIn(":app:testDomain", tasks)
        self.assertIn("detekt", tasks)
        self.assertIn(DOCS_CHECK, commands)

    def test_the_domain_tests_run_before_the_slower_documentation_check_and_each_check_appears_once(self):
        commands = plan(["CONSTITUTION.md", DOMAIN_MAIN, DOMAIN_TEST, UI_TEST])
        self.assertEqual(len(commands), len({tuple(c) for c in commands}))
        first_gradle = next(c for c in commands if c[0].endswith("gradlew"))
        self.assertIn(":app:testDomain", first_gradle)


class FingerprintTest(unittest.TestCase):
    def test_same_files_with_same_size_and_time_give_the_same_fingerprint(self):
        stat = lambda p: (10, 1)
        self.assertEqual(fingerprint(["a", "b"], stat), fingerprint(["b", "a"], stat))

    def test_an_edit_or_a_new_file_changes_it(self):
        base = fingerprint(["a"], lambda p: (10, 1))
        self.assertNotEqual(base, fingerprint(["a"], lambda p: (11, 1)))
        self.assertNotEqual(base, fingerprint(["a"], lambda p: (10, 2)))
        self.assertNotEqual(base, fingerprint(["a", "b"], lambda p: (10, 1)))

    def test_a_deleted_file_is_still_part_of_it(self):
        def stat(p):
            if p == "gone":
                raise FileNotFoundError(p)
            return (10, 1)

        self.assertNotEqual(fingerprint(["a"], stat), fingerprint(["a", "gone"], stat))


REPORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.sunloom.app.domain.safety.SafetyGateTest" tests="3" skipped="0" failures="1" errors="1">
  <testcase name="passes" classname="com.sunloom.app.domain.safety.SafetyGateTest" time="0.01"/>
  <testcase name="a limit is inclusive" classname="com.sunloom.app.domain.safety.SafetyGateTest" time="0.01">
    <failure message="java.lang.AssertionError: 4096 bytes was refused &amp; should not be" type="java.lang.AssertionError">stack</failure>
  </testcase>
  <testcase name="a crash" classname="com.sunloom.app.domain.safety.SafetyGateTest" time="0.01">
    <error message="Cannot round NaN value." type="java.lang.IllegalArgumentException">stack</error>
  </testcase>
</testsuite>
"""


class FailedTestsTest(unittest.TestCase):
    def write(self, folder, name, text, age_seconds=0):
        path = Path(folder) / "app" / "build" / "test-results" / "testGithubDebugUnitTest" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        stamp = time.time() - age_seconds
        os.utime(path, (stamp, stamp))

    def test_names_each_failing_test_with_its_message_and_skips_passing_ones(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "TEST-com.sunloom.app.domain.safety.SafetyGateTest.xml", REPORT)
            found = failed_tests(Path(root), since=time.time() - 60)
        self.assertEqual(len(found), 2)
        self.assertIn("SafetyGateTest > a limit is inclusive: java.lang.AssertionError: 4096 bytes was refused & should not be", found)
        self.assertIn("SafetyGateTest > a crash: Cannot round NaN value.", found)

    def test_reports_from_before_this_run_are_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, "TEST-old.xml", REPORT, age_seconds=3600)
            self.assertEqual(failed_tests(Path(root), since=time.time() - 60), [])

    def test_a_missing_results_folder_or_a_broken_report_is_not_an_error(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(failed_tests(Path(root), since=0), [])
            self.write(root, "TEST-broken.xml", "<testsuite><oops", age_seconds=0)
            self.assertEqual(failed_tests(Path(root), since=0), [])


class RunnerTest(unittest.TestCase):
    def test_one_deadline_covers_every_command_not_one_per_command(self):
        run = runner(Path("."), total_seconds=0.5)
        started = time.monotonic()
        code, _ = run(["python3", "-c", "import time; time.sleep(30)"])
        self.assertIsNone(code)  # timed out
        # The deadline has passed, so the next command is not started at all.
        code, _ = run(["python3", "-c", "print('never runs')"])
        self.assertIsNone(code)
        self.assertLess(time.monotonic() - started, 10)

    def test_a_command_that_exits_reports_its_code_and_output(self):
        run = runner(Path("."), total_seconds=30)
        self.assertEqual(run(["python3", "-c", "print('hello')"]), (0, "hello\n"))
        code, output = run(["python3", "-c", "import sys; sys.stderr.write('oops'); sys.exit(3)"])
        self.assertEqual(code, 3)
        self.assertIn("oops", output)

    def test_a_command_that_does_not_exist_is_not_run_rather_than_passed(self):
        run = runner(Path("."), total_seconds=30)
        self.assertEqual(run(["definitely-not-a-real-program-xyz"]), (NOT_RUN, ""))


class DecideTest(unittest.TestCase):
    def setUp(self):
        self.calls = []

    def runner(self, result):
        def run(argv):
            self.calls.append(argv)
            return result

        return run

    def test_no_relevant_changes_runs_nothing_and_says_nothing(self):
        out, ok = decide({}, {}, [".notes/x"], self.runner((0, "")), "", "fp")
        self.assertIsNone(out)
        self.assertEqual(self.calls, [])

    def test_the_skip_switch_runs_nothing(self):
        out, ok = decide({}, {"SUNLOOM_SKIP_STOP_GATE": "1"}, [DOMAIN_MAIN], self.runner((1, "boom")), "", "fp")
        self.assertIsNone(out)
        self.assertEqual(self.calls, [])

    def test_work_already_verified_is_not_run_again(self):
        out, ok = decide({}, {}, [DOMAIN_MAIN], self.runner((1, "boom")), "same", "same")
        self.assertIsNone(out)
        self.assertEqual(self.calls, [])

    def test_passing_checks_say_nothing_and_remember_the_state(self):
        out, ok = decide({}, {}, [DOMAIN_MAIN], self.runner((0, "fine")), "", "fp")
        self.assertIsNone(out)
        self.assertEqual(ok, "fp")
        self.assertTrue(self.calls)

    def test_a_failure_blocks_the_stop_once_and_shows_the_end_of_the_output(self):
        output = "\n".join(f"line {i}" for i in range(200)) + "\nSafetyGateTest > a test FAILED"
        out, ok = decide({}, {}, [DOMAIN_MAIN], self.runner((1, output)), "", "fp")
        self.assertEqual(out["decision"], "block")
        self.assertIn("SafetyGateTest > a test FAILED", out["reason"])
        self.assertIn("SUNLOOM_SKIP_STOP_GATE", out["reason"])
        self.assertNotIn("line 0\n", out["reason"])
        self.assertIsNone(ok)

    def test_after_one_block_it_warns_but_lets_the_agent_stop(self):
        out, ok = decide({"stop_hook_active": True}, {}, [DOMAIN_MAIN], self.runner((1, "still broken")), "", "fp")
        self.assertNotIn("decision", out)
        self.assertIn("still broken", out["systemMessage"])
        self.assertIsNone(ok)

    def test_a_check_that_could_not_start_is_reported_never_blocks_and_is_never_remembered_as_verified(self):
        out, ok = decide({}, {}, [DOMAIN_MAIN], self.runner((NOT_RUN, "")), "", "fp")
        self.assertNotIn("decision", out)
        self.assertIn("could not be started", out["systemMessage"])
        self.assertIsNone(ok)

    def test_a_timeout_never_blocks(self):
        out, ok = decide({}, {}, [DOMAIN_MAIN], self.runner((None, "")), "", "fp")
        self.assertNotIn("decision", out)
        self.assertIn("timed out", out["systemMessage"])
        self.assertIsNone(ok)

    def test_the_first_failing_check_stops_the_rest(self):
        out, ok = decide({}, {}, [DOMAIN_MAIN, "CONSTITUTION.md"], self.runner((1, "boom")), "", "fp")
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(out["decision"], "block")


if __name__ == "__main__":
    unittest.main()
