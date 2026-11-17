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
    for name in sorted(generated | {"gitlab-read-token", "gitlab-publish-token", "openai-api-key", "github-app.pem"}):
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
    findings = [finding for finding in (status.get("report") or {}).get("findings", []) if finding is not None]
    gitlab = job["platform"] == "gitlab"
    number = job["number"]
    severity = "MEDIUM" if gitlab or number == 3 else None if number == 2 else "HIGH"
    count = 1 if severity else 0
    quality = len(findings) == count
    if count == 1 and len(findings) == 1:
        finding = findings[0]
        file = "src/demo/ShippingCalculator.java" if gitlab else "src/demo/Checkout.java" if number == 3 else "src/demo/ArrayTotals.java"
        line = 10 if not gitlab and number == 3 else 9 if not gitlab and number == 4 else 8
        quality = quality and finding.get("severity") == severity and finding.get("file") == file and finding.get("line") == line
        explanation = finding.get("explanation", "").replace(",", "")
        if gitlab:
            quality = quality and "5000" in explanation and "500" in explanation
        elif number == 3:
            quality = quality and bool(re.search(r"\b12\b", explanation)) and "1250" in explanation
        else:
            quality = quality and bool(re.search(r"bounds|crash|exception", explanation, re.IGNORECASE))
    report = status.get("report") or {}
    return {**{key: job[key] for key in ("platform", "repository", "number", "jobId")},
            "state": status["state"], "failureCode": status.get("failureCode"),
            "findings": len(findings), "actualSeverities": [f.get("severity") for f in findings],
            "model": report.get("model"), "promptVersion": report.get("promptVersion"),
            "analysisErrors": report.get("errors") or [], "expectedCount": count,
            "expectedSeverity": severity, "qualityPassed": bool(quality),
            "commentUrl": status.get("commentUrl"), "publicationState": status.get("publicationState")}


def save_report(name, data):
    directory = ROOT / "reports"
    directory.mkdir(exist_ok=True)
    (directory / name).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def live(args):
    client = ReviewClient(args.base_url)
    jobs = []
    cases = [{"platform": "github", "repository": repo, "number": number}
             for repo in ("cleisommais/pr-test-repo", "cleisommais/pr-test-repo-private")
             for number in range(1, 5)]
    cases.append({"platform": "gitlab", "repository": "cleisommais/pr-test-repo", "number": 1})
    for case in cases:
        response = client.request("/api/reviews", case)
        jobs.append({**case, "jobId": response["jobId"], "statusUrl": response["statusUrl"]})
        save_report("live-jobs.json", jobs)
        print(f"{case['platform']} {case['repository']} #{case['number']}: {response['state']}", flush=True)
    deadline = time.monotonic() + args.timeout_seconds
    while True:
        results = [assess(job, client.request(job["statusUrl"])) for job in jobs]
        save_report("live-results.json", results)
        if not any(result["state"] in ACTIVE_STATES for result in results):
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Live reviews timed out; inspect reports/live-results.json")
        time.sleep(5)
    for result in results:
        result["remoteCommentVerified"] = bool(result["commentUrl"]) and subprocess.run([
            "docker", "compose", "exec", "-T", "agent-worker", "java", "-cp",
            "/app/mcp-api-gateway.jar:/app/lib/*", "com.prreview.tools.QueueAdminCli", "verify", result["jobId"],
        ], cwd=ROOT, check=False).returncode == 0
    save_report("live-results.json", results)
    print(json.dumps(results, indent=2))
    if any(result["state"] != "succeeded" or not result["commentUrl"]
           or not result["qualityPassed"] or not result["remoteCommentVerified"] for result in results):
        raise RuntimeError("Live validation failed; inspect reports/live-results.json")


def positive_integer(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("initialize")
    review_parser = commands.add_parser("review")
    review_parser.add_argument("--platform", choices=("github", "gitlab"), default="github")
    review_parser.add_argument("--repository", default="cleisommais/pr-test-repo")
    review_parser.add_argument("--number", type=positive_integer, default=1)
    live_parser = commands.add_parser("live")
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
