import unittest

from check_spec_tests import (
    changed_lines,
    changed_sections,
    item_ids,
    requirement_ids,
    uncovered,
)

CONSTITUTION = """\
# Title
- **Status:** Adopted v0.9 **[v0.9]**
## Amendment Record
| v0.9 | 2026-11-21 | Something changed (IV §2). |
---
## Article IV: Display
1. **First:** text one
2. **Second:** text two **(a)** x **(b)** y
## Article V: Lock
1. **Only:** text
   a continued line of the same item
2. **Other:** text
## Schedule A: Source Register
1. **Not an article item**
""".splitlines()

#                       1 Title ... 7 IV.1, 8 IV.2, 10 V.1, 11 V.1 (continued), 12 V.2, 14 (schedule)


class ItemIdsTest(unittest.TestCase):
    def test_maps_each_line_of_an_article_item_to_its_section_id(self):
        ids = item_ids(CONSTITUTION)
        self.assertEqual(ids[7], "IV.1")
        self.assertEqual(ids[8], "IV.2")
        self.assertEqual(ids[10], "V.1")
        self.assertEqual(ids[11], "V.1")  # a continued line belongs to the item above it
        self.assertEqual(ids[12], "V.2")

    def test_a_subheading_ends_the_item_above_it(self):
        lines = ["## Article IV: Display", "1. **First:** text", "### A subsection", "loose text under it", "2. **Second:** text"]
        ids = item_ids(lines)
        self.assertEqual(ids[2], "IV.1")
        self.assertNotIn(3, ids)
        self.assertNotIn(4, ids)
        self.assertEqual(ids[5], "IV.2")

    def test_the_header_the_amendment_log_and_the_schedules_have_no_section(self):
        ids = item_ids(CONSTITUTION)
        for number in (1, 2, 3, 4, 5, 6, 9, 13, 14):
            self.assertNotIn(number, ids, f"line {number}: {CONSTITUTION[number - 1]}")


class ChangedLinesTest(unittest.TestCase):
    def test_reads_added_and_changed_line_numbers_from_a_zero_context_diff(self):
        diff = "\n".join(
            [
                "diff --git a/CONSTITUTION.md b/CONSTITUTION.md",
                "--- a/CONSTITUTION.md",
                "+++ b/CONSTITUTION.md",
                "@@ -2 +2 @@",
                "-old",
                "+new",
                "@@ -7,0 +8,2 @@",
                "+added one",
                "+added two",
                "@@ -20,3 +21,0 @@",
                "-deleted",
            ]
        )
        self.assertEqual(changed_lines(diff), {2, 8, 9})

    def test_no_diff_means_no_lines(self):
        self.assertEqual(changed_lines(""), set())


class ChangedSectionsTest(unittest.TestCase):
    def test_only_article_sections_are_reported_once_each(self):
        diff = "@@ -2 +2 @@\n@@ -4 +4 @@\n@@ -8 +8 @@\n@@ -10,2 +10,2 @@\n@@ -14 +14 @@\n"
        self.assertEqual(changed_sections(CONSTITUTION, diff), ["IV.2", "V.1"])


class RequirementIdsTest(unittest.TestCase):
    def test_reads_every_quoted_id_of_every_annotation(self):
        text = '@Requirement("IV.2", "XVI.1.h")\nclass A\n@Requirement("V.1")\nfun b()'
        self.assertEqual(requirement_ids(text), {"IV.2", "XVI.1.h", "V.1"})

    def test_ids_in_comments_do_not_count(self):
        text = '// @Requirement("IV.2")\n/* @Requirement("V.1") */\n@Requirement("V.2")'
        self.assertEqual(requirement_ids(text), {"V.2"})


class UncoveredTest(unittest.TestCase):
    def test_a_section_is_covered_by_its_own_id_or_one_of_its_clauses(self):
        tests = ['@Requirement("XVI.1.h")', '@Requirement("IV.2")']
        self.assertEqual(uncovered(["IV.2", "XVI.1", "V.1"], tests), ["V.1"])

    def test_a_longer_section_number_is_not_a_clause(self):
        # "XII.10" must not count as covering "XII.1".
        self.assertEqual(uncovered(["XII.1"], ['@Requirement("XII.10")']), ["XII.1"])

    def test_nothing_changed_means_nothing_uncovered(self):
        self.assertEqual(uncovered([], []), [])


if __name__ == "__main__":
    unittest.main()
