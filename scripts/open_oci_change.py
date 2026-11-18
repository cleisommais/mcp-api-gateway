"""Open the GitLab/Bitbucket change requests for locally authored OCI test commits."""
import argparse
import json
from pathlib import Path
from urllib.parse import quote, urlencode

from test_live_lifecycle import GitLab
from bitbucket_fixture import BitbucketFixtureClient, PREFIX

ROOT = Path(__file__).resolve().parent.parent


def main(platform, campaign):
    manifest = json.loads((ROOT / campaign / "local-commits.json").read_text(encoding="utf-8-sig"))
    case = next(row for row in manifest if row["platform"] == platform)
    if not case.get("remoteHeadVerified") or len(case["commits"]) != 3:
        raise ValueError("The three local commits have not been pushed and verified")
    if platform == "gitlab":
        client = GitLab()
        branch = client.api(client.prefix + "/repository/branches/" + quote(case["branch"], safe=""))
        if branch["commit"]["id"] != case["headSha"]:
            raise ValueError("GitLab branch moved after local push")
        existing = client.api(client.prefix + "/merge_requests?" + urlencode({
            "state": "opened", "source_branch": case["branch"], "target_branch": case["target"]}))
        if len(existing) > 1:
            raise ValueError("More than one MR matches this isolated branch")
        pr = existing[0] if existing else client.api(client.prefix + "/merge_requests", {
            "source_branch": case["branch"], "target_branch": case["target"],
            "title": "OCI integration: three local commits",
            "description": "Integration fixture authored with three local Git commits and reviewed by the application deployed on OCI. Keep unmerged.",
            "remove_source_branch": False})
        number, url = pr["iid"], pr["web_url"]
    else:
        client = BitbucketFixtureClient()
        branch = client.branch(case["branch"])
        if branch["target"]["hash"] != case["headSha"]:
            raise ValueError("Bitbucket branch moved after local push")
        result = client.request(PREFIX + "/pullrequests?" + urlencode({
            "q": 'source.branch.name="' + case["branch"] + '"', "state": "OPEN", "pagelen": 20}))
        if len(result["values"]) > 1 or result.get("next"):
            raise ValueError("More than one PR matches this isolated branch")
        pr = result["values"][0] if result["values"] else client.request(PREFIX + "/pullrequests", {
            "title": "OCI integration: three local commits",
            "description": "Integration fixture authored with three local Git commits and reviewed by the application deployed on OCI. Keep unmerged.",
            "source": {"branch": {"name": case["branch"]}},
            "destination": {"branch": {"name": case["target"]}},
            "close_source_branch": False, "reviewers": []})
        number, url = pr["id"], pr["links"]["html"]["href"]
    output = {"name": case["name"], "platform": platform, "repository": case["repository"],
              "number": number, "url": url, "branch": case["branch"], "headSha": case["headSha"]}
    path = ROOT / campaign / (platform + "-pull-request.json")
    path.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("gitlab", "bitbucket"), required=True)
    parser.add_argument("--campaign-directory", default="reports/oci")
    args = parser.parse_args()
    main(args.platform, args.campaign_directory)
