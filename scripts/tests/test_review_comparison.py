"""Check comparison integrity independently of provider quality or publication success."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("comparison", SCRIPTS / "summarize_review_comparison.py")
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)
sys.path.pop(0)


class ReviewComparisonTest(unittest.TestCase):
    def test_paired_commits_must_be_identical_and_jobs_must_be_fresh(self):
        local = {"jobId": "local-job", "platform": "github", "repository": "owner/repo", "number": 1,
                 "baseSha": "a" * 40, "headSha": "b" * 40, "baseBranch": "main", "attempts": 0,
                 "state": "queued", "reportIsNull": True}
        gpt = {**local, "jobId": "gpt-job"}
        comparison.validate_pins([local], [gpt], 1)
        for field, value in (("headSha", "c" * 40), ("attempts", 1), ("reportIsNull", False), ("jobId", "local-job")):
            changed = {**gpt, field: value}
            with self.assertRaises(ValueError):
                comparison.validate_pins([local], [changed], 1)

    def test_severity_failure_does_not_hide_detection_or_successful_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            reports = directory / "local/reports"
            reports.mkdir(parents=True)
            source = {"platform": "github", "repository": "cleisommais/pr-test-repo", "number": 1,
                      "baseSha": "a" * 40, "headSha": "b" * 40}
            job = {**source, "jobId": "local-job", "fixture": source}
            report = {"model": "gpt-oss:20b", "promptVersion": "review-original-v3", "status": "complete",
                      "baseSha": source["baseSha"], "headSha": source["headSha"], "current": True,
                      "enumerationComplete": True, "expectedFiles": 1, "enumeratedFiles": 1, "errors": [],
                      "findings": [{"file": "src/demo/ArrayTotals.java", "line": 8, "severity": "LOW",
                                    "explanation": "Array bounds exception", "evidence": "bad boundary"}],
                      "contextBudget": {"requestedTokens": 8192},
                      "inference": {"providers": [{"role": "primary", "provider": "ollama",
                                                   "metadata": {"outputTokenBudget": 4096, "thinking": "low"}}]}}
            status = {"state": "succeeded", "failureCode": None, "commentUrl": "https://fixture.invalid/comment",
                      "publicationState": "published", "report": report}
            row = comparison.assess(job, status)
            row["remoteCommentVerified"] = True
            (directory / "local-jobs.json").write_text(json.dumps([job]))
            (directory / "local-results.json").write_text(json.dumps([row]))
            (reports / "job-local-job.json").write_text(json.dumps(report))
            campaign = {"cases": 1, "localModel": "gpt-oss:20b", "localReasoning": "low",
                        "promptVersion": "review-original-v3", "contextTokens": 8192, "outputTokens": 4096,
                        "localSchema": "fixture_local"}
            result = comparison.load_run(directory, "local", campaign)
            self.assertEqual(0, result["strictPassed"])
            self.assertEqual(1, result["detectionPassed"])
            self.assertEqual(1, result["publishedVerified"])
            # Both native PowerShell (nested) and Bash (flat) saved-job layouts work.
            flat = {key: value for key, value in job.items() if key != "fixture"}
            (directory / "local-jobs.json").write_text(json.dumps([flat]))
            other = comparison.load_run(directory, "local", campaign)
            self.assertEqual(result, other)


if __name__ == "__main__":
    unittest.main()
