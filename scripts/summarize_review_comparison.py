"""Publish only the current, paired all-repository evaluation (standard library only)."""

from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo

from local_tools import assess

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def identity(case):
    return case["platform"], case["repository"], case["number"]


def validate_pins(local, gpt, count):
    if len(local) != count or len(gpt) != count:
        raise ValueError("Incomplete paired cohort")
    reference = {identity(case): case for case in local}
    if len(reference) != count or len({identity(case) for case in gpt}) != count:
        raise ValueError("Repeated resource identity")
    local_ids = {case["jobId"] for case in local}
    gpt_ids = {case["jobId"] for case in gpt}
    if len(local_ids) != count or len(gpt_ids) != count or local_ids & gpt_ids:
        raise ValueError("Provider cohorts reused job identities")
    for case in local + gpt:
        if case["attempts"] != 0 or case["state"] != "queued" or case["reportIsNull"] is not True:
            raise ValueError("Evaluation did not begin with a fresh unanalysed job")
    for case in gpt:
        before = reference.get(identity(case))
        if before is None or any(case[key] != before[key] for key in ("baseSha", "headSha", "baseBranch")):
            raise ValueError("Commits differ between providers")


def load_run(directory, provider, campaign):
    rows = read(directory / (provider + "-results.json"))
    jobs = {identity(job): job for job in read(directory / (provider + "-jobs.json"))}
    expected_model = campaign["localModel" if provider == "local" else "gptModel"]
    expected_provider = "ollama" if provider == "local" else "openai"
    results = []
    for row in rows:
        job = jobs[identity(row)]
        report_path = directory / provider / "reports" / ("job-" + row["jobId"] + ".json")
        report = read(report_path) if report_path.exists() else {}
        source = {**job.get("fixture", job), **{key: job[key] for key in ("platform", "repository", "number", "jobId")}}
        status = {"state": row["state"], "report": report, "failureCode": row["failureCode"],
                  "commentUrl": row["commentUrl"], "publicationState": row["publicationState"]}
        checked = assess(source, status)
        if report:
            if report["promptVersion"] != campaign["promptVersion"] or report["model"] != expected_model:
                raise ValueError("Actual prompt/model differs from campaign settings")
            primary = [entry for entry in report["inference"]["providers"] if entry["role"] == "primary"]
            if len(primary) != 1 or primary[0]["provider"] != expected_provider:
                raise ValueError("Actual inference provider differs from campaign settings")
            if report["contextBudget"]["requestedTokens"] != campaign["contextTokens"]:
                raise ValueError("Context differs between providers")
            metadata = primary[0]["metadata"]
            if metadata["outputTokenBudget"] != campaign["outputTokens"]:
                raise ValueError("Output budget differs between providers")
            reasoning = metadata.get("thinking", metadata.get("reasoning"))
            if reasoning != campaign["localReasoning" if provider == "local" else "gptReasoning"]:
                raise ValueError("Reasoning setting differs from campaign settings")
        else:
            metadata = {}
        if bool(checked["qualityPassed"]) != bool(row["qualityPassed"]):
            raise ValueError("PowerShell/Bash rubric disagreement")
        results.append({
            "platform": row["platform"], "repository": row["repository"], "number": row["number"],
            "jobId": row["jobId"], "state": row["state"], "failureCode": row["failureCode"],
            "baseSha": source["baseSha"], "headSha": source["headSha"],
            "model": report.get("model"), "provider": expected_provider,
            "promptVersion": report.get("promptVersion"),
            "expectedCount": row["expectedCount"], "expectedSeverity": row["expectedSeverity"],
            "findings": report.get("findings", []), "detectionPassed": checked["defectDetected"],
            "severityPassed": checked["severityPassed"], "strictRubricPassed": row["qualityPassed"],
            "snapshotPassed": row["snapshotPassed"], "expectedFiles": report.get("expectedFiles"),
            "enumeratedFiles": report.get("enumeratedFiles"), "analysisErrors": report.get("errors", []),
            "usage": report.get("usage", {}), "inferenceAttempts": report.get("inference", {}).get("attempts", []),
            "apiCalls": metadata.get("attempts", []), "commentUrl": row["commentUrl"],
            "remoteCommentVerified": row["remoteCommentVerified"],
        })
    if len(results) != campaign["cases"] or len({identity(case) for case in results}) != campaign["cases"]:
        raise ValueError("Missing/repeated model results")
    return {"model": expected_model, "provider": expected_provider,
            "queueSchema": campaign["localSchema" if provider == "local" else "gptSchema"],
            "cases": len(results), "strictPassed": sum(case["strictRubricPassed"] for case in results),
            "detectionPassed": sum(case["detectionPassed"] for case in results),
            "publishedVerified": sum(case["state"] == "succeeded" and case["remoteCommentVerified"] for case in results),
            "results": results}


def summarize():
    directory = ROOT / "reports/current"
    campaign = read(directory / "campaign.json")
    validate_pins(read(directory / "local-pins.json"), read(directory / "gpt-pins.json"), campaign["cases"])
    for provider in ("local", "gpt"):
        final_jobs = read(directory / (provider + "-final-jobs.json"))
        expected_ids = {job["jobId"] for job in read(directory / (provider + "-pins.json"))}
        if {job["jobId"] for job in final_jobs} != expected_ids or len(final_jobs) != campaign["cases"]:
            raise ValueError("Final queue jobs differ from submitted cohort")
        if any(job["attempts"] != 1 or job["state"] != "succeeded" or job["reportPresent"] is not True
               or not job["publicationId"] for job in final_jobs):
            raise ValueError("A cohort reused analysis or did not finish publication")
    local = load_run(directory, "local", campaign)
    gpt = load_run(directory, "gpt", campaign)
    gpt_by_identity = {identity(case): case for case in gpt["results"]}
    for case in local["results"]:
        peer = gpt_by_identity[identity(case)]
        if case["commentUrl"] != peer["commentUrl"]:
            raise ValueError("Provider publication did not reuse the same summary comment")
    targets = read(ROOT / "config/repositories.json")["repositories"]
    groups = []
    for target in targets:
        platform = target.get("platform", "github")
        row = {"platform": platform, "repository": target["name"]}
        for label, run in (("local", local), ("gpt", gpt)):
            cases = [case for case in run["results"] if case["platform"] == platform and case["repository"] == target["name"]]
            row[label] = {"cases": len(cases), "strictPassed": sum(case["strictRubricPassed"] for case in cases),
                          "detectionPassed": sum(case["detectionPassed"] for case in cases),
                          "publishedVerified": sum(case["state"] == "succeeded" and case["remoteCommentVerified"] for case in cases)}
        if row["local"]["cases"] != row["gpt"]["cases"] or not row["local"]["cases"]:
            raise ValueError("A configured repository is missing from the comparison")
        groups.append(row)
    journal = read(ROOT / "reports/bitbucket/openai-budget.json")
    baseline = campaign["apiJournalInitialAttemptIds"]
    if [entry["id"] for entry in journal["attempts"][:len(baseline)]] != baseline:
        raise ValueError("Existing API journal was reset or rewritten")
    new_attempts = journal["attempts"][len(baseline):]
    api_calls = [call for case in gpt["results"] for call in case["apiCalls"]]
    started = datetime.strptime(campaign["runId"], "%Y%m%d_%H%M%S").replace(tzinfo=timezone.utc)
    artifact = {
        "runId": campaign["runId"], "startedAt": started.isoformat(),
        "collectedAt": datetime.now(timezone.utc).isoformat(), "timezone": "America/Chicago",
        "sourceImage": campaign["image"], "promptVersion": campaign["promptVersion"],
        "contextTokens": campaign["contextTokens"], "outputTokens": campaign["outputTokens"],
        "localReasoning": campaign["localReasoning"], "gptReasoning": campaign["gptReasoning"],
        "freshJobsVerified": True, "sameCommitPairsVerified": True, "oneAnalysisPerProviderPerCase": True,
        "sameSummaryCommentsVerified": True,
        "targets": groups, "local": local, "gpt": gpt,
        "apiUsage": {"calls": len(new_attempts), "outcomes": [entry["outcome"] for entry in new_attempts],
                     "inputTokens": sum(call["usage"]["input_tokens"] for call in api_calls),
                     "outputTokens": sum(call["usage"]["output_tokens"] for call in api_calls),
                     "estimatedUsd": str(sum(Decimal(call["estimatedUsd"]) for call in api_calls)),
                     "newReservedUsd": str(sum(Decimal(entry["reservedUsd"]) for entry in new_attempts)),
                     "cumulativeJournalReservedUsd": str(sum(Decimal(entry["reservedUsd"]) for entry in journal["attempts"])),
                     "reservationLimitUsd": campaign["apiBudgetLimitUsd"],
                     "costScope": "Configured POC rate estimate, not an invoice; existing reservations retained"},
    }
    checks_path = directory / "checks.json"
    if checks_path.exists():
        artifact["checks"] = read(checks_path)
    runtime_path = directory / "runtime-checks.json"
    if runtime_path.exists():
        artifact["runtime"] = read(runtime_path)
    fingerprint = directory / "prompt-sha256.txt"
    if fingerprint.exists():
        prompt_hash = fingerprint.read_text().strip()
        if not re.fullmatch(r"[0-9a-f]{64}", prompt_hash):
            raise ValueError("Invalid common prompt fingerprint")
        artifact["commonSystemPromptSha256"] = prompt_hash
    output = ROOT / "docs/evaluation/current"
    output.mkdir(parents=True, exist_ok=True)
    (output / "comparison.json").write_text(json.dumps(artifact, indent=2) + "\n", encoding="utf-8")
    (directory / "comparison.json").write_text(json.dumps(artifact, indent=2) + "\n", encoding="utf-8")
    date = started.astimezone(ZoneInfo("America/Chicago")).isoformat()
    lines = ["# Current all-repository comparison", "", f"Fresh campaign started {date} (America/Chicago).", "",
             "One new analysis per provider for each of the 14 fixture PRs/MRs. Both providers used `review-original-v3`, the same pinned base/head commits, an 8192-token context and a 4096-token output budget. Local inference used `gpt-oss:20b` with low reasoning; the GPT API used `gpt-6-luna` with medium reasoning. No prompt tuning, result selection, or model-quality rerun is included in this dataset.", "",
             "| Target | Cases | Local strict rubric | GPT strict rubric | Local detection/clean checks | GPT detection/clean checks |",
             "| --- | --- | --- | --- | --- | --- |"]
    for group in groups:
        count = group["local"]["cases"]
        lines.append(f"| {group['platform']} `{group['repository']}` | {count} | {group['local']['strictPassed']}/{count} | {group['gpt']['strictPassed']}/{count} | {group['local']['detectionPassed']}/{count} | {group['gpt']['detectionPassed']}/{count} |")
    lines += [f"| **Total** | **{campaign['cases']}** | **{local['strictPassed']}/{local['cases']}** | **{gpt['strictPassed']}/{gpt['cases']}** | **{local['detectionPassed']}/{local['cases']}** | **{gpt['detectionPassed']}/{gpt['cases']}** |", "",
              f"Publication and exact remote-comment read-back: local **{local['publishedVerified']}/{local['cases']}**, GPT **{gpt['publishedVerified']}/{gpt['cases']}**.", "",
              "Detection/clean checks require the expected finding count, file, added-line location, explanation patterns and complete current coverage. The strict rubric additionally requires the expected severity and verified model/prompt. Job completion and publication are counted separately from model correctness. File inventory checks use the source-reported changed-file count; the public and private GitHub clean fixtures have different inventories.", "",
              "## Per-PR results", "", "| Target / PR | Expected findings / severity | Local findings / severity | GPT findings / severity | Local strict | GPT strict |", "| --- | --- | --- | --- | --- | --- |"]
    peer = {identity(case): case for case in gpt["results"]}
    for case in local["results"]:
        other = peer[identity(case)]
        expected = str(case["expectedCount"]) + (" / " + case["expectedSeverity"] if case["expectedSeverity"] else "")
        actual = lambda item: str(len(item["findings"])) + (" / " + ", ".join(finding["severity"] for finding in item["findings"]) if item["findings"] else "")
        lines.append(f"| {case['platform']} `{case['repository']}` #{case['number']} | {expected} | {actual(case)} | {actual(other)} | {'Pass' if case['strictRubricPassed'] else 'Fail'} | {'Pass' if other['strictRubricPassed'] else 'Fail'} |")
    usage = artifact["apiUsage"]
    lines += ["", "## Collection and reproducibility", "",
              "Each provider used a separate newly created PostgreSQL schema. Every job was checked for zero attempts and no saved report before inference. The two cohorts' commit pairs were compared before enabling GPT inference. The common runtime image and prompt fingerprint are recorded in the JSON evidence. Full findings, errors, usage, job IDs and verified comment URLs are included for every case.", "",
              "Previous active reports and the superseded result tables were backed up under ignored `reports/archive/`. This page and `comparison.json` contain only the current paired campaign. Credentials, fixture definitions and cumulative API budget journals were preserved. The API allowlist was limited to the four configured repositories. Normal local services remain configured with external inference disabled. Later provider runs update the same owned summary comments; each provider's read-back was completed before the next provider began publication.", "",
              f"This campaign made {usage['calls']} API calls with {usage['inputTokens']} input and {usage['outputTokens']} output tokens. Recorded cost estimate: USD {usage['estimatedUsd']}; new conservative reservations: USD {usage['newReservedUsd']}. The cumulative journal retains previous reservations and uses a USD {usage['reservationLimitUsd']} cap for this collection. Cost figures use the registered POC rate profile and are not an invoice.", "",
              "Raw current results are in `reports/current/`. Regrade or verify saved jobs with `Test-LiveReviews` using `-Platform all -Resume` (PowerShell) or `--platform all --resume` (Bash), the matching label, API base URL, queue schema, expected model and expected prompt version. `-SubmitOnly -RequireFresh` / `--submit-only --require-fresh` permits checking commit pins before starting a provider worker. To collect another independent cohort, create new queue schemas rather than resuming these jobs.", "",
              "[Machine-readable current evidence](comparison.json). These synthetic fixtures are a small sample and do not establish general model accuracy.", ""]
    if "checks" in artifact:
        checks = artifact["checks"]
        lines += [f"Fresh automated checks: {checks['javaTests']} Java tests with real PostgreSQL, {checks['javaSkipped']} skipped; {checks['scriptTests']} script/fixture/comparison tests passed.", ""]
    (output / "README.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({"targets": groups, "apiUsage": usage}, indent=2))


if __name__ == "__main__":
    summarize()
