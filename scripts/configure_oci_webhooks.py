"""Operator-only setup for the fixture GitHub App and Bitbucket repository callbacks.

Credentials are read locally, never printed or mounted in the application image.
GitLab's review token cannot administer hooks; its hook is configured manually.
"""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, build_opener

from bitbucket_fixture import BitbucketFixtureClient, NoRedirect, PREFIX

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reports/oci"
SECRETS = OUT / "bundle/secrets"
HTTP = build_opener(NoRedirect())


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def github(method, path, body=None):
    if path not in ("app", "app/hook/config", "app/hook/deliveries") and not (
            path.startswith("app/hook/deliveries/") and path.rsplit("/", 1)[-1].isdigit()):
        raise ValueError("Unexpected GitHub App administration endpoint")
    settings = dict(line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines()
                    if line and not line.startswith("#") and "=" in line)
    now = int(time.time())
    signed = b64(b'{"alg":"RS256","typ":"JWT"}') + "." + b64(json.dumps(
        {"iat": now - 60, "exp": now + 540, "iss": settings["GITHUB_APP_ID"].strip('"\'')}).encode())
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign",
                                str(ROOT / "secrets/github-app.pem")],
                               input=signed.encode(), capture_output=True, check=True).stdout
    token = signed + "." + b64(signature)
    data = None if body is None else json.dumps(body).encode()
    request = Request("https://api.github.com/" + path, data=data, method=method,
                      headers={"Authorization": "Bearer " + token,
                               "Accept": "application/vnd.github+json",
                               "Content-Type": "application/json",
                               "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with HTTP.open(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"GitHub App HTTP {error.code} at {path}") from None


def main(platform, apply):
    state = json.loads((OUT / "host-state.json").read_text(encoding="utf-8-sig"))
    url = "https://" + state["publicHost"] + "/webhooks/" + platform
    if platform == "github":
        app = github("GET", "app")
        print(json.dumps({"app": app["slug"], "events": app["events"], "settings":
                          "https://github.com/settings/apps/" + app["slug"]}), flush=True)
        try:
            current = github("GET", "app/hook/config")
        except RuntimeError as error:
            if "HTTP 404" not in str(error):
                raise
            current = {"url": "", "configurationReadStatus": 404}
        backup = OUT / "github-webhook-before.json"
        if not backup.exists():
            backup.write_text(json.dumps(current, indent=2))
        result = current
        if apply:
            if "pull_request" not in app["events"]:
                raise RuntimeError("Enable Pull request events on the GitHub App before configuring delivery")
            result = github("PATCH", "app/hook/config", {
                "url": url, "content_type": "json", "insecure_ssl": "0",
                "secret": (SECRETS / "github-webhook-secret").read_text().strip()})
            result = github("GET", "app/hook/config")
            if result["url"] != url or result["insecure_ssl"] != "0":
                raise RuntimeError("GitHub callback configuration mismatch")
        print(json.dumps({"platform": platform, "app": app["slug"], "events": app["events"],
                          "url": result["url"], "applied": apply}), flush=True)
    else:
        client = BitbucketFixtureClient()
        current = client.request(PREFIX + "/hooks")
        backup = OUT / "bitbucket-webhook-before.json"
        if not backup.exists():
            backup.write_text(json.dumps(current, indent=2))
        matches = [row for row in current["values"] if row.get("url") == url]
        if len(matches) > 1 or current.get("next"):
            raise RuntimeError("Ambiguous Bitbucket hook inventory")
        if apply and not matches:
            result = client.request(PREFIX + "/hooks", {
                "description": "PR Review Agent OCI integration test", "url": url, "active": True,
                "events": ["pullrequest:created", "pullrequest:updated", "repo:push"],
                "secret": (SECRETS / "bitbucket-webhook-secret").read_text().strip()})
            (OUT / "bitbucket-webhook-created.json").write_text(json.dumps(result, indent=2))
        configured = client.request(PREFIX + "/hooks")
        print(json.dumps({"platform": platform, "applied": apply,
                          "hooks": [{k: row.get(k) for k in ("uuid", "url", "events", "active")}
                                    for row in configured["values"]]}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("github", "bitbucket"), required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    main(args.platform, args.apply)
