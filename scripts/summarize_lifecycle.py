"""Validate and publish compact evidence from completed real-platform lifecycle runs."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "reports/lifecycle"
DESTINATION = ROOT / "docs/evaluation/lifecycle/results.json"
STAGES = ("initial", "unrelated", "fixed", "regression", "fixed-again")
COUNTS = (1, 1, 0, 1, 0)


def read(name):
    return json.loads((SOURCE / name).read_text(encoding="utf-8-sig"))


def record(label, status, expected, file, line):
    report = status["report"]
    findings = report["findings"]
    if (status["state"] != "succeeded" or status["publicationState"] != "published"
            or report["status"] != "complete" or report["current"] is not True
            or report["enumerationComplete"] is not True or report["errors"]
            or len(findings) != expected or report["enumeratedFiles"] != (1 if label == "initial" else 2)
            or report["expectedFiles"] != report["enumeratedFiles"]
            or any(c["status"] not in ("analyzed", "excluded") for c in report["coverage"])):
        raise ValueError(f"Incomplete/incorrect real review: {label} {status['jobId']}")
    if expected and (findings[0]["file"] != file or findings[0]["line"] != line
                     or findings[0]["severity"] != "HIGH"):
        raise ValueError("Live defect finding does not match the fixed acceptance rubric")
    audit = next((p["metadata"] for p in report["inference"]["providers"]
                  if p["role"] == "clean-audit"), None)
    return {"label": label, "jobId": status["jobId"], "baseSha": report["baseSha"],
            "headSha": report["headSha"], "findings": findings, "enumeratedFiles": report["enumeratedFiles"],
            "state": status["state"], "coverage": report["status"], "commentId": status["commentId"],
            "commentUrl": status["commentUrl"], "model": report["model"],
            "promptVersion": report["promptVersion"], "cleanReviewAudit": report.get("cleanReviewAudit"),
            "auditThinking": audit["thinking"] if audit else None,
            "auditContextTokens": audit["contextRequested"] if audit else None,
            "auditOutputTokens": audit["outputTokenBudget"] if audit else None,
            "completedAt": status["updatedAt"], "remoteReadBackVerified": True}


def main():
    targets = []
    for prefix, repository, file in (
            ("", "cleisommais/pr-test-repo", "src/demo/ArrayTotals.java"),
            ("private-", "cleisommais/pr-test-repo-private", "src/demo/LifecycleTotals.java")):
        rows = [record(label, read(prefix + label + "-status.json"), expected, file, 8)
                for label, expected in zip(STAGES, COUNTS)]
        final = rows[-1]
        comments = read(("private" if prefix else "public") + "-final-comments.json")
        if (len(comments) != 1 or comments[0]["id"] != final["commentId"]
                or final["headSha"] not in comments[0]["body"]
                or "No validated issues found in the current PR revision" not in comments[0]["body"]):
            raise ValueError("Final GitHub read-back does not match the accepted revision")
        receipt = read(prefix + "fixed-again-receipt.json")
        if receipt.get("duplicate") is not True or receipt["jobId"] != final["jobId"]:
            raise ValueError("Final GitHub duplicate request was not verified")
        targets.append({"platform": "github", "repository": repository, "number": 5,
                        "revisions": rows, "finalRemoteBody": comments[0]["body"],
                        "duplicateVerified": True})
    for platform in ("gitlab", "bitbucket"):
        campaign = read(platform + "-campaign.json")
        if [r["label"] for r in campaign["revisions"]] != list(STAGES):
            raise ValueError("Live campaign has missing/reordered revisions")
        rows = []
        for row, expected in zip(campaign["revisions"], COUNTS):
            if not all(row["checks"].values()) or row.get("duplicateJobVerified") is not True:
                raise ValueError("Live campaign contains a failed or unverified revision")
            status = campaign["latestVerification"]["status"] if row["label"] == "fixed-again" else row["status"]
            rows.append(record(row["label"], status, expected, "src/demo/LifecycleTotals.java", 9))
        if not all(campaign["latestVerification"]["checks"].values()):
            raise ValueError("Final platform read-back failed")
        failures = [{"label": r["label"], "jobId": r["status"]["jobId"],
                     "headSha": r["headSha"], "state": r["status"]["state"],
                     "failedChecks": [key for key, value in r["checks"].items() if not value],
                     "findings": r["status"]["report"]["findings"],
                     "errors": r["status"]["report"]["errors"],
                     "inferenceAttempts": r["status"]["report"]["inference"]["attempts"]}
                    for r in campaign.get("failedAttempts", [])]
        targets.append({"platform": platform, "repository": campaign["repository"],
                        "number": campaign["number"], "url": campaign["url"], "revisions": rows,
                        "priorFailedAttempts": failures, "duplicateVerified": True,
                        "finalRemoteBody": campaign["latestVerification"]["remoteBody"]})
    for target in targets:
        if len({r["commentId"] for r in target["revisions"]}) != 1:
            raise ValueError("A revision created an extra summary instead of updating the owned comment")
        if target["revisions"][-1]["auditThinking"] != "medium":
            raise ValueError("Final head was not verified using the final audit configuration")
        if (target["revisions"][-1]["auditContextTokens"] != 16384
                or target["revisions"][-1]["auditOutputTokens"] != 8192):
            raise ValueError("Final head was not verified using the corrected audit budgets")
    evidence = {"completedDate": "2026-11-18", "timezone": "America/Chicago", "livePlatforms": 3,
                "repositories": 4, "acceptedRevisions": 20, "trigger": "review-api; initial public GitHub revision used a signed replay",
                "mockedResponses": False, "webhookConfigurationChanged": False, "targets": targets}
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    DESTINATION.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("Validated 20 real review revisions, four stable summary identities and four duplicate checks.")


if __name__ == "__main__":
    main()
