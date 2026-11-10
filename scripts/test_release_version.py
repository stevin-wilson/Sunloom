import unittest

from release_version import parse_tag


class ParseTagTest(unittest.TestCase):
    def test_final_release(self):
        self.assertEqual(parse_tag("v2.0.0"), ("2.0.0", 200009))

    def test_release_candidate_sorts_below_final(self):
        name, code = parse_tag("v2.0.0-rc.1")
        self.assertEqual((name, code), ("2.0.0-rc.1", 200001))
        self.assertLess(code, parse_tag("v2.0.0")[1])

    def test_rc8_is_last_allowed(self):
        self.assertEqual(parse_tag("v2.0.0-rc.8")[1], 200008)

    def test_rc9_rejected_because_it_would_collide_with_final(self):
        with self.assertRaises(ValueError):
            parse_tag("v2.0.0-rc.9")

    def test_rc0_rejected(self):
        with self.assertRaises(ValueError):
            parse_tag("v2.0.0-rc.0")

    def test_codes_increase_across_versions(self):
        tags = ["v2.0.0-rc.1", "v2.0.0", "v2.0.1-rc.1", "v2.0.1", "v2.1.0", "v3.0.0"]
        codes = [parse_tag(t)[1] for t in tags]
        self.assertEqual(codes, sorted(codes))
        self.assertEqual(len(set(codes)), len(codes))

    def test_every_code_exceeds_current_code_13(self):
        self.assertGreater(parse_tag("v1.8.0-rc.1")[1], 13)

    def test_rejects_malformed_tags(self):
        for bad in ["2.0.0", "v2.0", "v2.0.0-beta", "v2.0.0-rc", "v2.0.0-rc.1.2", "vx.y.z", "", "v2.0.0 "]:
            with self.assertRaises(ValueError, msg=bad):
                parse_tag(bad)

    def test_rejects_components_that_overflow_their_digits(self):
        for bad in ["v1.100.0", "v1.0.100", "v100000.0.0"]:
            with self.assertRaises(ValueError, msg=bad):
                parse_tag(bad)


if __name__ == "__main__":
    unittest.main()
