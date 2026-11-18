"""Validate captured real OCI reviews and export credential-free lifecycle evidence."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CAMPAIGN = ROOT / "reports/oci/webhooks"
RESULTS = CAMPAIGN / "results"
OUT = ROOT / "docs/evaluation/oci"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    manifest = read(CAMPAIGN / "local-commits.json")
    deliveries = read(RESULTS / "fixed-again-delivery-evidence.json")
    cases = []
    for case in manifest:
        pr = read(CAMPAIGN / (case["name"] + "-pull-request.json"))
        stages = []
        comment_id = None
        for stage, head_key, expected in (("initial", "headSha", 1), ("fixed", "fixHeadSha", 0),
                                          ("regression", "regressionHeadSha", 1),
                                          ("fixed-again", "finalFixHeadSha", 0)):
            result = read(RESULTS / (stage + "-" + case["name"] + ".json"))
            report = result["report"]
            assert result["state"] == "succeeded" and result["publicationState"] == "published"
            assert report["headSha"] == case[head_key] and report["current"]
            assert report["status"] == "complete" and report["enumerationComplete"]
            assert report["enumeratedFiles"] == report["expectedFiles"] == 2
            assert not report["errors"] and report["model"] == "gpt-6-luna"
            attempts = report["inference"]["attempts"]
            assert attempts and all(row["provider"] == "openai" for row in attempts)
            verified = (RESULTS / (stage + "-" + case["name"] + "-readback.log")).read_text(encoding="utf-8-sig")
            assert "Verified remote comment: " + result["commentUrl"] in verified
            if comment_id is None:
                comment_id = result["commentId"]
            assert result["commentId"] == comment_id
            events = [row for row in deliveries if row["id"] == result["jobId"]]
            assert events and all(row["event"] != "manual_review" for row in events)
            matched = len(report["findings"]) == expected
            if stage in ("regression", "fixed-again"):
                assert matched and report["cleanReviewAudit"]["version"] == "clean-review-audit-v2"
            if not matched:
                assert case["name"] == "bitbucket" and stage == "fixed"
                assert "overflow" in report["findings"][0]["explanation"].lower()
            if expected:
                assert len(report["findings"]) == 1
                assert report["findings"][0]["file"] == "src/demo/OciTotals.java"
                assert report["findings"][0]["line"] == 8
            stages.append({"stage": stage, "headSha": report["headSha"], "jobId": result["jobId"],
                           "expectedFindings": expected, "findings": report["findings"],
                           "expectedResultMatched": matched, "status": report["status"],
                           "provider": "openai", "model": report["model"],
                           "auditVersion": report["cleanReviewAudit"]["version"],
                           "commentId": comment_id, "commentUrl": result["commentUrl"],
                           "remoteReadBackVerified": True,
                           "deliveries": [{k: row[k] for k in ("delivery_id", "event", "action", "received_at")}
                                          for row in events]})
        cases.append({"name": case["name"], "platform": case["platform"],
                      "repository": case["repository"], "url": pr["url"], "branch": case["branch"],
                      "initialLocalCommits": case["commits"], "stages": stages})
    output = {"date": "2026-11-18", "deployment": "OCI us-chicago-1 Docker Compose",
              "trigger": "Native provider webhooks; no remote repository polling or manual review POST",
              "finalPolicyChecks": 8, "finalPolicyPassed": 8,
              "earlierFailedCheckRetained": "Bitbucket first fix: unsupported generic integer-overflow finding",
              "primaryPrompt": "review-original-v3", "finalAudit": "clean-review-audit-v2", "cases": cases}
    replay = read(ROOT / "reports/oci/replay-confirmed.json")
    assert replay["redelivery"] and replay["status_code"] == 200
    output["githubReplay"] = replay
    contract = read(ROOT / "reports/oci/contract-results.json")
    assert len(contract) == 9 and all(row["schemaValid"] for row in contract)
    output["httpsContractChecks"] = contract
    output["inboxAtCompletion"] = read(ROOT / "reports/oci/final-inbox.json")
    assert all(row["state"] == "complete" for row in output["inboxAtCompletion"])
    output["deployedJarSha256"] = "d81876564baaa7a9025822ad8156738668f15c396df0f71f576469712d39c8b9"
    output["budget"] = {"gptConservativeReservationsUsd": "0.0980705000",
                        "gptReservationCapUsd": "0.20", "overallTestAuthorizationUsd": "1.00",
                        "plannedStopUtc": "2026-11-18T19:27:00Z"}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "results.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print("Validated 8/8 final-policy real webhook checks; retained the earlier failed check.")


if __name__ == "__main__":
    main()
