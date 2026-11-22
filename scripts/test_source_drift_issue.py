import unittest

from source_drift_issue import MAX_BODY, MAX_LINE, build_body, decide_action, parse_result, trim_report


class DecideActionTest(unittest.TestCase):
    def test_a_failed_result_opens_an_issue_or_adds_to_the_one_already_open(self):
        self.assertEqual("open", decide_action("FAILED", None))
        self.assertEqual("update", decide_action("FAILED", 12))

    def test_only_a_passed_result_closes_an_open_issue(self):
        self.assertEqual("close", decide_action("PASSED", 12))
        self.assertEqual("none", decide_action("PASSED", None))

    def test_a_run_that_could_not_judge_every_source_changes_nothing_so_a_drifted_source_cannot_hide_behind_a_timeout(self):
        for result in ("PARTIAL", "INCONCLUSIVE", "", "weird"):
            self.assertEqual("none", decide_action(result, None), result)
            self.assertEqual("none", decide_action(result, 12), result)


class ParseResultTest(unittest.TestCase):
    def test_the_result_line_is_read_whatever_the_case_or_spacing(self):
        self.assertEqual("FAILED", parse_result("## Live source check\n\nResult: FAILED\n\nrest"))
        self.assertEqual("PASSED", parse_result("Result:passed"))

    def test_no_report_or_no_result_line_means_the_check_never_judged_anything(self):
        self.assertEqual("", parse_result(None))
        self.assertEqual("", parse_result("(the check wrote no report)"))
        self.assertEqual("", parse_result("Resulting in nothing"))


class BodyTest(unittest.TestCase):
    def test_long_lines_are_cut_so_a_response_snippet_cannot_flood_a_public_issue(self):
        trimmed = trim_report("short\n" + "x" * 5000 + "\nlast")
        lines = trimmed.splitlines()
        self.assertEqual("short", lines[0])
        self.assertEqual(MAX_LINE, len(lines[1]))
        self.assertTrue(lines[1].endswith("…"))
        self.assertEqual("last", lines[2])

    def test_the_whole_report_is_kept_to_a_size_an_issue_accepts(self):
        self.assertLessEqual(len(trim_report("\n".join(["y" * 300] * 1000))), MAX_BODY)

    def test_the_body_carries_the_heading_the_report_and_the_run_link(self):
        body = build_body("| a | b |", "https://example.test/run/1", "Heading")
        self.assertTrue(body.startswith("Heading\n\n| a | b |"))
        self.assertIn("[Workflow run](https://example.test/run/1)", body)
        self.assertNotIn("Workflow run", build_body("x", "", "Heading"))


if __name__ == "__main__":
    unittest.main()
