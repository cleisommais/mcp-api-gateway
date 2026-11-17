"""Standard-library helpers for the macOS/Linux Bash entrypoints (Python 3.9+)."""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent.parent
ACTIVE_STATES = {"queued", "running", "publishing"}


def initialize():
    secret_root = ROOT / "secrets"
    secret_root.mkdir(mode=0o700, exist_ok=True)
    (ROOT / "reports").mkdir(exist_ok=True)
    env_path = ROOT / ".env"
    if not env_path.exists():
        with env_path.open("x", encoding="utf-8") as output:
            output.write((ROOT / ".env.example").read_text(encoding="utf-8"))
        env_path.chmod(0o600)
    # File-backed Compose secrets retain host ownership on Linux. Run Java as
    # that owner so credentials can stay private and reports remain writable.
    settings = env_path.read_text(encoding="utf-8")
    additions = []
    for key, value in (("LOCAL_UID", os.getuid()), ("LOCAL_GID", os.getgid())):
        if not re.search(r"^\s*" + key + r"\s*=", settings, re.MULTILINE):
            additions.append(f"{key}={value}")
    if additions:
        with env_path.open("a", encoding="utf-8") as output:
            output.write(("" if settings.endswith("\n") else "\n") + "\n".join(additions) + "\n")
    generated = {"database-password", "mcp-gateway-token", "github-webhook-secret", "api-token"}
    for name in sorted(generated | {"gitlab-read-token", "gitlab-publish-token", "bitbucket-read-token", "bitbucket-publish-token", "openai-api-key", "github-app.pem"}):
        path = secret_root / name
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            continue
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(secrets.token_urlsafe(32) if name in generated else "")
    print("Credential files initialized in secrets/; existing files preserved.")


class ReviewClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.token = (ROOT / "secrets/api-token").read_text(encoding="utf-8-sig").strip()
        if not self.token:
            raise ValueError("secrets/api-token is empty; run the initializer first")

    def request(self, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers={
            "Authorization": "Bearer " + self.token, "Content-Type": "application/json",
        })
        with urlopen(request, timeout=30) as response:
            return json.load(response)


def review(args):
    client = ReviewClient(args.base_url)
    created = client.request("/api/reviews", {
        "platform": args.platform, "repository": args.repository, "number": args.number,
    })
    print(f"Job {created['jobId']}; duplicate={created['duplicate']}", flush=True)
    deadline = time.monotonic() + args.timeout_seconds
    while True:
        status = client.request(created["statusUrl"])
        if status["state"] not in ACTIVE_STATES:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Timed out waiting for {created['jobId']}")
        time.sleep(3)
    print(json.dumps(status, indent=2))
    if status["state"] != "succeeded" or not status.get("commentUrl"):
        raise RuntimeError(f"Review did not complete with publication: {status['state']}; {status.get('failureCode')}")


def assess(job, status):
    if job["platform"] == "bitbucket":
        return assess_bitbucket(job, status)
    findings = [finding for finding in (status.get("report") or {}).get("findings", []) if finding is not None]
    gitlab = job["platform"] == "gitlab"
    number = job["number"]
    severity = "MEDIUM" if gitlab or number == 3 else None if number == 2 else "HIGH"
    count = 1 if severity else 0
    quality = len(findings) == count
    severity_passed = True
    if count == 1 and len(findings) == 1:
        finding = findings[0]
        file = "src/demo/ShippingCalculator.java" if gitlab else "src/demo/Checkout.java" if number == 3 else "src/demo/ArrayTotals.java"
        line = 10 if not gitlab and number == 3 else 9 if not gitlab and number == 4 else 8
        severity_passed = finding.get("severity") == severity
        quality = quality and finding.get("file") == file and finding.get("line") == line
        explanation = finding.get("explanation", "").replace(",", "")
        if gitlab:
            quality = quality and "5000" in explanation and "500" in explanation
        elif number == 3:
            quality = quality and bool(re.search(r"\b12\b", explanation)) and "1250" in explanation
        else:
            quality = quality and bool(re.search(r"bounds|crash|exception", explanation, re.IGNORECASE))
    report = status.get("report") or {}
    expected_files = report.get("expectedFiles", -1)
    snapshot_passed = (report.get("current") is True and report.get("enumerationComplete") is True
                       and isinstance(expected_files, int) and expected_files >= 0
                       and report.get("enumeratedFiles") == expected_files and not report.get("errors")
                       and all(not job.get(key) or job[key] == report.get(key) for key in ("baseSha", "headSha")))
    return {**{key: job[key] for key in ("platform", "repository", "number", "jobId")},
            "state": status["state"], "failureCode": status.get("failureCode"),
            "findings": len(findings), "actualSeverities": [f.get("severity") for f in findings],
            "model": report.get("model"), "promptVersion": report.get("promptVersion"),
            "analysisErrors": report.get("errors") or [], "expectedCount": count,
            "expectedSeverity": severity, "defectDetected": bool(quality and snapshot_passed),
            "severityPassed": severity_passed, "snapshotPassed": snapshot_passed,
            "qualityPassed": bool(quality and severity_passed and snapshot_passed),
            "commentUrl": status.get("commentUrl"), "publicationState": status.get("publicationState")}


def bitbucket_cases():
    manifest = json.loads((ROOT / "reports/bitbucket/fixture-manifest.json").read_text(encoding="utf-8-sig"))
    definitions = {case["id"]: case for case in json.loads((ROOT / "fixtures/bitbucket/cases.json").read_text())}
    cases = manifest["cases"]
    if (manifest["repository"] != "cleisommais/pr-test-repo" or manifest["baseBranch"] != "main"
            or len(cases) != len(definitions) or {case["id"] for case in cases} != definitions.keys()):
        raise ValueError("Run Initialize-BitbucketFixture first; the fixture manifest is incomplete or outside scope")
    for case in cases:
        if (case["platform"] != "bitbucket" or case["repository"] != manifest["repository"]
                or not isinstance(case["number"], int) or case["number"] < 1
                or not all(re.fullmatch(r"[a-f0-9]{40}", case[key]) for key in ("baseSha", "headSha"))):
            raise ValueError("Invalid Bitbucket fixture identity")
        case["expected"] = definitions[case["id"]]["expected"]
    return cases


def assess_bitbucket(job, status):
    report = status.get("report") or {}
    findings = report.get("findings") or []
    expected = job["expected"]
    detected = len(findings) == expected["count"]
    severity_passed = True
    if detected and expected["count"]:
        finding = findings[0]
        explanation = finding.get("explanation", "").replace(",", "")
        detected = (finding.get("file") == expected["file"] and finding.get("line") == expected["line"]
                    and all(re.search(pattern, explanation, re.IGNORECASE) for pattern in expected["explanationPatterns"]))
        severity_passed = finding.get("severity") == expected["severity"]
    snapshot_passed = (report.get("headSha") == job["headSha"] and report.get("baseSha") == job["baseSha"]
                       and report.get("current") is True and report.get("enumerationComplete") is True
                       and report.get("enumeratedFiles") == expected["files"] and not report.get("errors"))
    return {**{key: job[key] for key in ("platform", "repository", "number", "jobId")},
            "case": job["id"], "state": status["state"], "failureCode": status.get("failureCode"),
            "findings": len(findings), "actualSeverities": [finding.get("severity") for finding in findings],
            "model": report.get("model"), "promptVersion": report.get("promptVersion"),
            "analysisErrors": report.get("errors") or [], "expectedCount": expected["count"],
            "expectedSeverity": expected["severity"], "defectDetected": bool(detected and snapshot_passed),
            "severityPassed": severity_passed,
            "snapshotPassed": snapshot_passed, "qualityPassed": bool(detected and severity_passed and snapshot_passed),
            "commentUrl": status.get("commentUrl"), "publicationState": status.get("publicationState")}


def save_report(name, data):
    path = ROOT / "reports" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def live(args):
    client = ReviewClient(args.base_url)
    jobs = []
    cases = [{"platform": "github", "repository": repo, "number": number}
             for repo in ("cleisommais/pr-test-repo", "cleisommais/pr-test-repo-private")
             for number in range(1, 5)]
    cases.append({"platform": "gitlab", "repository": "cleisommais/pr-test-repo", "number": 1})
    platform = getattr(args, "platform", "existing")
    if platform in ("bitbucket", "all"):
        bitbucket = bitbucket_cases()
        cases = cases + bitbucket if platform == "all" else bitbucket
    directory = "bitbucket/" if platform == "bitbucket" else "current/" if platform == "all" else ""
    prefix = directory + "live"
    if args.label:
        prefix = directory + args.label
    if args.resume:
        jobs = json.loads((ROOT / "reports" / (prefix + "-jobs.json")).read_text(encoding="utf-8-sig"))
        jobs = [{**job.get("fixture", {}), **job} for job in jobs]
        identity = lambda case: (case["platform"], case["repository"], case["number"])
        if len(jobs) != len(cases) or {identity(job) for job in jobs} != {identity(case) for case in cases}:
            raise ValueError("Saved jobs do not match the requested fixture cohort")
        for job in jobs:
            if not re.fullmatch(r"[a-f0-9-]{36}", job["jobId"]) or job["statusUrl"] != "/api/reviews/" + job["jobId"]:
                raise ValueError("Invalid saved review job identity")
            if job["platform"] == "bitbucket":
                job["expected"] = next(case["expected"] for case in cases if identity(case) == identity(job))
    else:
        for case in cases:
            response = client.request("/api/reviews", {key: case[key] for key in ("platform", "repository", "number")})
            if args.require_fresh and response["duplicate"]:
                raise ValueError("Existing job returned; use a fresh evaluation queue schema")
            jobs.append({**case, "jobId": response["jobId"], "statusUrl": response["statusUrl"]})
            save_report(prefix + "-jobs.json", jobs)
            print(f"{case['platform']} {case['repository']} #{case['number']}: {response['state']}", flush=True)
    if args.submit_only:
        return
    deadline = time.monotonic() + args.timeout_seconds
    while True:
        results = [assess(job, client.request(job["statusUrl"])) for job in jobs]
        if args.expected_prompt_version:
            for result in results:
                result["promptProfilePassed"] = result["promptVersion"] == args.expected_prompt_version
                result["qualityPassed"] = result["qualityPassed"] and result["promptProfilePassed"]
        for result in results:
            result["modelPassed"] = not args.expected_model or result["model"] == args.expected_model
            result["qualityPassed"] = result["qualityPassed"] and result["modelPassed"]
        save_report(prefix + "-results.json", results)
        if not any(result["state"] in ACTIVE_STATES for result in results):
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Live reviews timed out; inspect reports/{prefix}-results.json")
        time.sleep(5)
    for result in results:
        command = ["docker", "compose", "exec", "-T"]
        if args.database_schema:
            command.extend(["-e", "DATABASE_SCHEMA=" + args.database_schema])
        result["remoteCommentVerified"] = bool(result["commentUrl"]) and subprocess.run(command + [
            "agent-worker", "java", "-cp",
            "/app/mcp-api-gateway.jar:/app/lib/*", "com.prreview.tools.QueueAdminCli", "verify", result["jobId"],
        ], cwd=ROOT, check=False).returncode == 0
    save_report(prefix + "-results.json", results)
    print(json.dumps(results, indent=2))
    if any(result["state"] != "succeeded" or not result["commentUrl"]
           or not result["qualityPassed"] or not result["remoteCommentVerified"] for result in results):
        raise RuntimeError(f"Live validation failed; inspect reports/{prefix}-results.json")


def positive_integer(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def run_label(value):
    if not re.fullmatch(r"[a-z0-9-]{1,40}", value):
        raise argparse.ArgumentTypeError("label must contain 1-40 lowercase letters, digits or hyphens")
    return value


def database_schema(value):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
        raise argparse.ArgumentTypeError("invalid database schema")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("initialize")
    review_parser = commands.add_parser("review")
    review_parser.add_argument("--platform", choices=("github", "gitlab", "bitbucket"), default="github")
    review_parser.add_argument("--repository", default="cleisommais/pr-test-repo")
    review_parser.add_argument("--number", type=positive_integer, default=1)
    live_parser = commands.add_parser("live")
    live_parser.add_argument("--platform", choices=("existing", "bitbucket", "all"), default="existing")
    live_parser.add_argument("--label", type=run_label)
    live_parser.add_argument("--database-schema", type=database_schema)
    live_parser.add_argument("--expected-prompt-version", choices=("review-original-v3", "review-calibrated-v2"))
    live_parser.add_argument("--expected-model")
    mode = live_parser.add_mutually_exclusive_group()
    mode.add_argument("--submit-only", action="store_true")
    mode.add_argument("--resume", action="store_true")
    live_parser.add_argument("--require-fresh", action="store_true")
    for command, timeout in ((review_parser, 1800), (live_parser, 3600)):
        command.add_argument("--base-url", default="http://localhost:18081")
        command.add_argument("--timeout-seconds", type=positive_integer, default=timeout)
    args = parser.parse_args()
    if args.command == "initialize":
        initialize()
    elif args.command == "review":
        review(args)
    else:
        live(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, HTTPError, URLError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
