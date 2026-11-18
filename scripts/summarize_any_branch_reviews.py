"""Export real any-destination webhook evidence, including the user's failed PRs."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "reports/oci/any-branch"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    deliveries = read(SOURCE / "deliveries.json")
    output = []
    for case in read(SOURCE / "cases.json"):
        result = read(SOURCE / (case["name"] + ".json"))
        report = result["report"]
        assert result["state"] == "succeeded" and result["publicationState"] == "published"
        assert report["status"] == "complete" and report["current"]
        assert report["baseBranch"] == case["baseBranch"]
        assert report["headSha"].startswith(case["headSha"])
        assert report["enumerationComplete"] and report["enumeratedFiles"] == report["expectedFiles"]
        assert not report["errors"]
        assert "Verified remote comment: " + result["commentUrl"] in (
            SOURCE / (case["name"] + "-readback.log")).read_text(encoding="utf-8-sig")
        events = [row for row in deliveries if row["id"] == result["jobId"]]
        assert events and all(row["event"] != "manual_review" for row in events)
        if case["oldJobId"]:
            previous = next(row for row in deliveries if row["id"] == case["oldJobId"])
            assert previous["base_sha"] == report["baseSha"] and previous["head_sha"] == report["headSha"]
            assert result["jobId"] != case["oldJobId"]
        if case["expectedFindings"] >= 0:
            assert len(report["findings"]) == case["expectedFindings"]
        output.append({"name": case["name"], "platform": case["platform"], "number": case["number"],
                       "baseBranch": report["baseBranch"], "baseSha": report["baseSha"],
                       "headSha": report["headSha"], "jobId": result["jobId"],
                       "previousJobId": case["oldJobId"], "findings": report["findings"],
                       "commentUrl": result["commentUrl"], "remoteReadBackVerified": True,
                       "events": [{k: row[k] for k in ("delivery_id", "event", "action")} for row in events]})
    assert len(output) == 6
    next_result = read(SOURCE / "bitbucket-user-10-next.json")
    next_report = next_result["report"]
    assert next_result["state"] == "succeeded" and next_result["publicationState"] == "published"
    assert next_report["status"] == "complete" and next_report["current"] and not next_report["errors"]
    assert next_report["baseBranch"] == "oci-e2e/20261118/bitbucket"
    assert next_report["headSha"] != output[-1]["headSha"]
    assert next_result["commentId"] == read(SOURCE / "bitbucket-user-10.json")["commentId"]
    assert "Verified remote comment: " + next_result["commentUrl"] in (
        SOURCE / "bitbucket-user-10-next-readback.log").read_text(encoding="utf-8-sig")
    next_events = [row for row in deliveries if row["id"] == next_result["jobId"]]
    assert next_events and all(row["event"] != "manual_review" for row in next_events)
    duplicate = read(SOURCE / "manual-duplicate.json")
    assert duplicate["duplicate"] and duplicate["jobId"] == next_result["jobId"]
    output.append({"name": "bitbucket-user-10-next", "platform": "bitbucket", "number": 10,
                   "baseBranch": next_report["baseBranch"], "headSha": next_report["headSha"],
                   "jobId": next_result["jobId"], "findings": next_report["findings"],
                   "commentUrl": next_result["commentUrl"], "remoteReadBackVerified": True,
                   "sameCommentAsPreviousRevision": True, "manualApiReturnedDuplicate": True,
                   "events": [{k: row[k] for k in ("delivery_id", "event", "action")} for row in next_events]})
    destination = ROOT / "docs/evaluation/oci/any-branch.json"
    destination.write_text(json.dumps({"date": "2026-11-18", "checks": 7, "passed": 7,
                                       "branchNameFilter": False, "cases": output}, indent=2) + "\n")
    print("Validated 7/7 real any-destination webhook reviews, including the user's next PR #10 commit.")


if __name__ == "__main__":
    main()
