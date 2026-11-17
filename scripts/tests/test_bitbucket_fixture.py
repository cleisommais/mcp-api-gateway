"""Regression checks for the source API behavior observed during live fixture setup."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("bitbucket_fixture", SCRIPTS / "bitbucket_fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class BitbucketFixtureTest(unittest.TestCase):
    def test_empty_201_commit_response_is_resolved_without_repeating_the_write(self):
        client = fixture.BitbucketFixtureClient.__new__(fixture.BitbucketFixtureClient)
        client.request = Mock(return_value={})
        client.branch = Mock(return_value={"target": {"hash": "a" * 40}})
        client.ensure_files = Mock()
        files = {"README.md": "baseline"}
        self.assertEqual("a" * 40, client.commit("main", files, "Initialize baseline"))
        self.assertEqual(1, client.request.call_count)
        client.ensure_files.assert_called_once_with("a" * 40, files)

    def test_abbreviated_pr_refs_resolve_and_mismatches_are_rejected(self):
        client = fixture.BitbucketFixtureClient.__new__(fixture.BitbucketFixtureClient)
        client.request = Mock(return_value={"hash": "a" * 40})
        self.assertEqual("a" * 40, client.full_sha({"hash": "a" * 12}))
        client.request.return_value = {"hash": "b" * 40}
        with self.assertRaises(RuntimeError):
            client.full_sha({"hash": "a" * 12})

    def test_all_fixture_transformations_are_validated_before_any_remote_write(self):
        baseline, cases = fixture.fixture_files()
        self.assertEqual(5, len(cases))
        self.assertEqual(22, len(cases[1]["files"]))
        self.assertIn("i < values.length", baseline["src/demo/ArrayTotals.java"])
        for case in cases:
            expected = case["expected"]
            self.assertEqual(expected["files"], len(case["files"]))
            if expected["count"]:
                self.assertTrue(case["files"][expected["file"]].splitlines()[expected["line"] - 1].strip())


if __name__ == "__main__":
    unittest.main()
