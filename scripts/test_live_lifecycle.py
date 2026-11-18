"""Live iterative GitLab/Bitbucket fixture reviews; real remote commits and comments.

Only the configured synthetic repository is writable. Never changes webhooks,
merges, force-pushes, or changes existing baseline/fixture branches.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, build_opener

from bitbucket_fixture import BitbucketFixtureClient, NoRedirect, PREFIX, REPOSITORY

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reports/lifecycle"
SOURCE = "src/demo/LifecycleTotals.java"
DOC = "docs/lifecycle-notes.txt"


def code(broken, revision):
    # The final fix remains a nonempty diff from the baseline in every iteration.
    loop = ("        for (int i = 0; i <= values.length; i++) {\n"
            "            total += values[i];") if broken else (
            "        for (int value : values) {\n            total += value;")
    return ("package demo;\n\npublic final class LifecycleTotals {\n"
            "    private LifecycleTotals() {}\n\n"
            f"    // Iterative review fixture revision {revision}.\n"
            "    public static int sum(int[] values) {\n        int total = 0;\n"
            + loop + "\n        }\n        return total;\n    }\n}\n")


class GitLab:
    platform = "gitlab"
    base_branch = "f5/reference"
    prefix = "projects/88936485"

    def __init__(self):
        self.token = (ROOT / "secrets/gitlab-publish-token").read_text(encoding="utf-8-sig").strip()
        self.http = build_opener(NoRedirect())

    def api(self, path, body=None):
        if not (path == self.prefix or path.startswith(self.prefix + "/")):
            raise ValueError("GitLab request outside fixture project")
        data = None if body is None else json.dumps(body).encode()
        request = Request("https://gitlab.com/api/v4/" + path, data=data,
                          headers={"PRIVATE-TOKEN": self.token, "Content-Type": "application/json"})
        try:
            with self.http.open(request, timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"GitLab HTTP {error.code} at {path.split('?')[0]}") from None

    def prepare(self, branch):
        project = self.api(self.prefix)
        if project["path_with_namespace"] != REPOSITORY:
            raise RuntimeError("GitLab fixture identity mismatch")
        base = self.api(self.prefix + "/repository/branches/" + quote(self.base_branch, safe=""))
        self.base = base["commit"]["id"]
        self.branch = branch
        # Refuse to reuse a branch; each campaign has its own explicit identity.
        self.api(self.prefix + "/repository/branches", {"branch": branch, "ref": self.base})

    def commit(self, files, message, new_paths=()):
        actions = [{"action": "create" if path in new_paths else "update", "file_path": path,
                    "content": content} for path, content in files.items()]
        return self.api(self.prefix + "/repository/commits", {
            "branch": self.branch, "commit_message": message, "actions": actions})["id"]

    def open(self):
        pr = self.api(self.prefix + "/merge_requests", {"source_branch": self.branch,
                      "target_branch": self.base_branch, "title": "Fixture: iterative PR review lifecycle",
                      "description": "Synthetic bug/fix/regression acceptance fixture. Keep unmerged.",
                      "remove_source_branch": False})
        self.number, self.url = pr["iid"], pr["web_url"]

    def snapshot(self):
        return self.api(self.prefix + f"/merge_requests/{self.number}")

    def current(self, sha):
        pr = self.snapshot()
        refs = pr.get("diff_refs") or {}
        return pr.get("sha") == sha and refs.get("head_sha") == sha

    def current_head(self):
        return self.snapshot()["sha"]

    def ensure_files(self, sha, files):
        for path, expected in files.items():
            remote = self.api(self.prefix + "/repository/files/" + quote(path, safe="")
                              + "?" + urlencode({"ref": sha}))
            if base64.b64decode(remote["content"]).decode() != expected:
                raise RuntimeError("Unrecorded GitLab revision differs from expected fixture files")

    def comments(self):
        return self.api(self.prefix + f"/merge_requests/{self.number}/notes?per_page=100&sort=asc")

    def text(self, comment):
        return comment["body"]

    def marker(self):
        return f"<!-- mcp-review-poc:gitlab:88936485:{self.number} -->"


class Bitbucket:
    platform = "bitbucket"
    base_branch = "main"

    def __init__(self):
        self.client = BitbucketFixtureClient()

    def prepare(self, branch):
        repo = self.client.request(PREFIX)
        if repo["full_name"] != REPOSITORY:
            raise RuntimeError("Bitbucket fixture identity mismatch")
        if self.client.branch(branch):
            raise RuntimeError("Refusing to overwrite existing branch")
        self.base = self.client.branch("main")["target"]["hash"]
        self.head = self.base
        self.branch = branch

    def commit(self, files, message, new_paths=()):
        self.head = self.client.commit(self.branch, files, message, self.head)
        return self.head

    def open(self):
        pr = self.client.request(PREFIX + "/pullrequests", {
            "title": "Fixture: iterative PR review lifecycle",
            "description": "Synthetic bug/fix/regression acceptance fixture. Keep unmerged.",
            "source": {"branch": {"name": self.branch}},
            "destination": {"branch": {"name": "main"}}, "close_source_branch": False,
            "reviewers": []})
        self.number, self.url = pr["id"], pr["links"]["html"]["href"]

    def snapshot(self):
        return self.client.request(PREFIX + f"/pullrequests/{self.number}")

    def current(self, sha):
        return self.client.full_sha(self.snapshot()["source"]["commit"]) == sha

    def current_head(self):
        return self.client.full_sha(self.snapshot()["source"]["commit"])

    def ensure_files(self, sha, files):
        self.client.ensure_files(sha, files)

    def comments(self):
        result = self.client.request(PREFIX + f"/pullrequests/{self.number}/comments?pagelen=100")
        if result.get("next"):
            raise RuntimeError("Unexpected comment pagination in dedicated lifecycle PR")
        return result["values"]

    def text(self, comment):
        return comment["content"]["raw"]

    def marker(self):
        return f"<!-- mcp-review-poc:bitbucket:{REPOSITORY}:{self.number} -->"


class ReviewApi:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = (ROOT / "secrets/api-token").read_text(encoding="utf-8-sig").strip()
        self.http = build_opener(NoRedirect())

    def request(self, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.base + path, data=data, headers={
            "Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        try:
            with self.http.open(request, timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"Review API HTTP {error.code} at {path}") from None

    def submit(self, fixture):
        return self.request("/api/reviews", {"platform": fixture.platform,
                            "repository": REPOSITORY, "number": fixture.number})

    def wait(self, receipt):
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            status = self.request(receipt["statusUrl"])
            if status["state"] not in ("queued", "running", "publishing"):
                return status
            time.sleep(2)
        raise RuntimeError("Timed out waiting for real review")


def validate(fixture, status, sha, expected, previous_id, expected_files):
    report = status.get("report") or {}
    findings = report.get("findings", [])
    checks = {
        "jobSucceeded": status["state"] == "succeeded",
        "published": status["publicationState"] == "published",
        "currentCommit": report.get("headSha") == sha and report.get("current") is True,
        "fullPrInventory": report.get("expectedFiles") == expected_files
            and report.get("enumeratedFiles") == expected_files,
        "completeCoverage": report.get("status") == "complete" and report.get("enumerationComplete") is True
            and report.get("enumeratedFiles") == report.get("expectedFiles") and not report.get("errors")
            and all(c["status"] in ("analyzed", "excluded") for c in report.get("coverage", [])),
        "expectedFindings": len(findings) == expected,
        "sameComment": previous_id is None or status["commentId"] == previous_id,
    }
    if expected and findings:
        checks["bugLocationAndExplanation"] = (findings[0]["file"] == SOURCE and findings[0]["line"] == 9
                and any(word in findings[0]["explanation"].lower() for word in ("bounds", "exception", "crash")))
    comments = [c for c in fixture.comments() if fixture.text(c).startswith(fixture.marker())]
    checks["oneRemoteSummary"] = len(comments) == 1
    remote = comments[0] if len(comments) == 1 else None
    body = fixture.text(remote) if remote else ""
    checks["remoteCommit"] = sha in body and remote["id"] == status["commentId"] if remote else False
    checks["remoteResult"] = (("No validated issues found in the current PR revision" in body)
                              if expected == 0 else SOURCE in body and "No validated issues found" not in body)
    return checks, body


def campaign(platform, base, resume=False):
    fixture = GitLab() if platform == "gitlab" else Bitbucket()
    api = ReviewApi(base)
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / (platform + "-campaign.json")
    retry = None
    if resume:
        results = json.loads(path.read_text(encoding="utf-8"))
        fixture.branch, fixture.number, fixture.url = results["branch"], results["number"], results["url"]
        fixture.base = results["revisions"][0]["status"]["report"]["baseSha"]
        if isinstance(fixture, Bitbucket):
            fixture.head = fixture.client.full_sha(fixture.snapshot()["source"]["commit"])
        if results["revisions"] and not all(results["revisions"][-1]["checks"].values()):
            retry = results["revisions"].pop()
            results.setdefault("failedAttempts", []).append(retry)
    else:
        if path.exists():
            raise RuntimeError("Campaign evidence already exists; use --resume or archive explicitly")
        branch = "test/review-lifecycle-" + uuid.uuid4().hex[:12]
        fixture.prepare(branch)
        results = {"platform": platform, "repository": REPOSITORY, "branch": branch,
                   "trigger": "review-api", "revisions": []}

    def save():
        path.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")

    stages = [("initial", True, 1), ("unrelated", True, 1), ("fixed", False, 0),
              ("regression", True, 1), ("fixed-again", False, 0)]
    comment_id = (results["revisions"][-1]["status"].get("commentId") if results["revisions"]
                  else retry["status"].get("commentId") if retry else None)
    for index, (label, broken, expected) in enumerate(stages, 1):
        existing = next((row for row in results["revisions"] if row["label"] == label), None)
        if existing is not None:
            if existing.get("duplicateJobVerified") is not True:
                if not fixture.current(existing["headSha"]):
                    raise RuntimeError("Cannot verify an interrupted duplicate check after the remote head changed")
                duplicate = api.submit(fixture)
                if duplicate["jobId"] != existing["status"]["jobId"] or duplicate.get("duplicate") is not True:
                    raise RuntimeError("Interrupted stage did not reuse its existing review job")
                existing["duplicateJobVerified"] = True
                save()
            continue
        if label == "unrelated":
            files, new = {DOC: "Unrelated documentation; original bounds bug remains.\n"}, [DOC]
        else:
            files, new = {SOURCE: code(broken, index)}, [SOURCE] if index == 1 else []
        if retry and retry["label"] == label:
            sha = retry["headSha"]
        elif resume and results["revisions"] and fixture.current_head() != results["revisions"][-1]["headSha"]:
            # A previous process may have stopped after the remote commit and before saving its result.
            sha = fixture.current_head()
            fixture.ensure_files(sha, files)
        else:
            sha = fixture.commit(files, "test: lifecycle " + label, new)
        if index == 1 and not resume:
            fixture.open()
            results.update(number=fixture.number, url=fixture.url)
        print(f"{platform} {label}: committed {sha}; {fixture.url}", flush=True)
        results["pendingRevision"] = {"label": label, "headSha": sha}
        save()
        # GitLab updates MR diff_refs asynchronously; never submit an obsolete snapshot.
        deadline = time.monotonic() + 90
        while not fixture.current(sha):
            if time.monotonic() > deadline:
                raise RuntimeError("Platform PR metadata did not reach committed head")
            time.sleep(2)
        receipt = api.submit(fixture)
        status = api.wait(receipt)
        checks, body = validate(fixture, status, sha, expected, comment_id, 1 if index == 1 else 2)
        comment_id = status.get("commentId")
        row = {"label": label, "headSha": sha, "expectedFindings": expected,
               "checks": checks, "status": status, "remoteBody": body}
        results["revisions"].append(row)
        results.pop("pendingRevision", None)
        save()
        print(f"{platform} {label}: state={status['state']}; findings={len((status.get('report') or {}).get('findings', []))}; checks={checks}", flush=True)
        if not all(checks.values()):
            raise RuntimeError(f"Live lifecycle check failed at {platform}/{label}; inspect saved evidence")
        duplicate = api.submit(fixture)
        if duplicate["jobId"] != receipt["jobId"] or duplicate.get("duplicate") is not True:
            raise RuntimeError("Same committed revision was not deduplicated")
        row["duplicateJobVerified"] = True
        save()
    latest = results["revisions"][-1]
    if not fixture.current(latest["headSha"]):
        raise RuntimeError("Remote PR moved after the completed campaign")
    status = api.wait({"statusUrl": "/api/reviews/" + latest["status"]["jobId"]})
    checks, body = validate(fixture, status, latest["headSha"], 0, comment_id, 2)
    duplicate = api.submit(fixture)
    checks["duplicateJob"] = duplicate["jobId"] == status["jobId"] and duplicate.get("duplicate") is True
    results["latestVerification"] = {"checkedAtEpoch": time.time(), "checks": checks,
                                     "status": status, "remoteBody": body}
    save()
    if not all(checks.values()):
        raise RuntimeError("Final direct platform read-back or duplicate check failed")
    print(f"{platform}: all five real revisions passed; comment={comment_id}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("gitlab", "bitbucket"), required=True)
    parser.add_argument("--base-url", default="http://host.docker.internal:18081")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    try:
        campaign(args.platform, args.base_url, args.resume)
    except Exception as error:
        print(f"Live lifecycle failed: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
