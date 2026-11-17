"""Populate the explicitly allowlisted private Bitbucket test repo with independent PRs.

Uses the source-commit REST API, never force-pushes or merges, and preserves existing
matching fixture branches/PRs. Write credentials stay in ignored secret files.
"""

import base64
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, quote
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = "cleisommais/pr-test-repo"
API = "https://api.bitbucket.org/2.0/"
PREFIX = "repositories/" + REPOSITORY
FIXTURE_ROOT = ROOT / "fixtures/bitbucket"
MANIFEST = ROOT / "reports/bitbucket/fixture-manifest.json"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def fixture_files():
    baseline = {path.relative_to(FIXTURE_ROOT / "base").as_posix(): path.read_text(encoding="utf-8")
                for path in sorted((FIXTURE_ROOT / "base").rglob("*")) if path.is_file()}
    cases = json.loads((FIXTURE_ROOT / "cases.json").read_text(encoding="utf-8"))
    for case in cases:
        changes = {}
        for path, replacements in case["replacements"].items():
            content = baseline[path]
            for before, after in replacements:
                if content.count(before) != 1:
                    raise ValueError(f"Fixture replacement must match once: {case['id']} {path}")
                content = content.replace(before, after, 1)
            changes[path] = content
        for number in range(1, case.get("markerRecords", 0) + 1):
            name = f"Marker{number:02d}"
            changes[f"src/demo/markers/{name}.java"] = f"package demo.markers;\n\npublic record {name}(int value) {{}}\n"
        case["files"] = changes
    return baseline, cases


class BitbucketFixtureClient:
    def __init__(self):
        settings = dict(line.split("=", 1) for line in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines()
                        if line and not line.startswith("#") and "=" in line)
        email = os.environ.get("BITBUCKET_EMAIL", settings.get("BITBUCKET_EMAIL", "")).strip().strip("\"'")
        token = (ROOT / "secrets/bitbucket-publish-token").read_text(encoding="utf-8-sig").strip()
        if not token:
            raise ValueError("Configure secrets/bitbucket-publish-token with repository write and PR write scopes")
        self.authorization = ("Basic " + base64.b64encode((email + ":" + token).encode()).decode()
                              if email else "Bearer " + token)
        self.http = build_opener(NoRedirect())

    def request(self, path, body=None, form=False, raw=False):
        if not (path == PREFIX or path.startswith(PREFIX + "/")):
            raise ValueError("Fixture API request is outside the configured test repository")
        data = None if body is None else (urlencode(body).encode() if form else json.dumps(body).encode())
        headers = {"Authorization": self.authorization, "Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        try:
            with self.http.open(Request(API + path, data=data, headers=headers), timeout=60) as response:
                payload = response.read(2_000_001)
                if len(payload) > 2_000_000:
                    raise RuntimeError("Fixture API response exceeded byte limit")
                return payload.decode() if raw else json.loads(payload) if payload else {}
        except HTTPError as error:
            # Only structured validation messages; never auth headers or raw bodies.
            detail = ""
            if error.code == 400:
                try:
                    problem = json.loads(error.read(8192)).get("error", {})
                    detail = "; " + json.dumps({key: problem[key] for key in ("message", "fields") if key in problem})
                except (ValueError, OSError):
                    pass
            raise RuntimeError(f"Bitbucket fixture HTTP {error.code} for {path.split('?')[0]}{detail}") from None

    def branch(self, name):
        response = self.request(PREFIX + "/refs/branches?" + urlencode({"q": f'name="{name}"', "pagelen": 100}))
        entries = response["values"]
        if len(entries) > 1 or response.get("next"):
            raise RuntimeError("Unexpected branch enumeration")
        return entries[0] if entries else None

    def ensure_files(self, sha, files):
        for path, expected in files.items():
            content = self.request(PREFIX + "/src/" + sha + "/" + quote(path, safe="/"), raw=True)
            if content != expected:
                raise RuntimeError(f"Existing fixture content differs at {path}; refusing to overwrite")

    def full_sha(self, commit):
        ref = commit["hash"]
        resolved = ref if len(ref) == 40 else self.request(PREFIX + "/commit/" + ref)["hash"]
        if len(resolved) != 40 or not resolved.startswith(ref):
            raise RuntimeError("Bitbucket PR commit resolution mismatch")
        return resolved

    def commit(self, branch, files, message, parent=None):
        fields = {"branch": branch, "message": message, **{"/" + path: content for path, content in files.items()}}
        if parent:
            fields["parents"] = parent
        response = self.request(PREFIX + "/src", fields, form=True)
        # Bitbucket can acknowledge a source commit with 201 and an empty body.
        # Resolve the branch and verify its contents instead of repeating the write.
        branch_state = self.branch(branch)
        if not branch_state:
            raise RuntimeError("Source commit acknowledged but branch is unavailable; inspect before retrying")
        sha = branch_state["target"]["hash"]
        if response.get("hash") and response["hash"] != sha:
            raise RuntimeError("Source commit and branch head differ")
        self.ensure_files(sha, files)
        return sha


def seed():
    baseline, cases = fixture_files()  # Validate every proposed change before a remote write.
    client = BitbucketFixtureClient()
    repository = client.request(PREFIX)
    if repository.get("full_name") != REPOSITORY or repository.get("is_private") is not True:
        raise RuntimeError("Expected the authorized private test repository")
    main = client.branch("main")
    if main:
        base = main["target"]["hash"]
        client.ensure_files(base, baseline)
    else:
        existing = client.request(PREFIX + "/refs/branches?pagelen=1")
        if existing["values"]:
            raise RuntimeError("Repository has branches but no fixture main; refusing to initialize")
        base = client.commit("main", baseline, "Initialize synthetic Java review baseline")
    manifest = {"repository": REPOSITORY, "baseBranch": "main", "baseSha": base, "cases": []}
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)

    def save():
        MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    save()
    print(f"Baseline main: {base}", flush=True)
    for case in cases:
        branch = client.branch(case["branch"])
        if branch:
            head = branch["target"]["hash"]
            client.ensure_files(head, {**baseline, **case["files"]})
        else:
            head = client.commit(case["branch"], case["files"], case["title"], base)
        query = urlencode({"q": 'source.branch.name="' + case["branch"] + '"', "state": "OPEN", "pagelen": 20})
        existing = client.request(PREFIX + "/pullrequests?" + query)
        prs = existing["values"]
        if len(prs) > 1 or existing.get("next"):
            raise RuntimeError("Multiple fixture PRs; reconcile before continuing")
        if prs:
            pr = prs[0]
            if (client.full_sha(pr["source"]["commit"]) != head or pr["destination"]["branch"]["name"] != "main"
                    or client.full_sha(pr["destination"]["commit"]) != base):
                raise RuntimeError("Existing fixture PR no longer matches the intended snapshot")
        else:
            pr = client.request(PREFIX + "/pullrequests", {
                "title": case["title"], "description": "Synthetic Java change for the local PR-review learning exercise.",
                "source": {"branch": {"name": case["branch"]}}, "destination": {"branch": {"name": "main"}},
                "close_source_branch": False, "reviewers": [],
            })
        manifest["cases"].append({"id": case["id"], "platform": "bitbucket", "repository": REPOSITORY,
                                  "number": pr["id"], "branch": case["branch"], "baseSha": base, "headSha": head,
                                  "url": pr["links"]["html"]["href"], "expected": case["expected"]})
        save()
        print(f"PR #{pr['id']}: {pr['links']['html']['href']}", flush=True)


if __name__ == "__main__":
    try:
        seed()
    except (OSError, ValueError, RuntimeError, URLError) as error:
        print(f"Fixture setup failed: {error}", file=sys.stderr)
        sys.exit(1)
