"""Validate OpenAPI and actual HTTPS boundary responses; no fake repository server."""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from jsonschema import Draft202012Validator
from openapi_spec_validator import validate
import yaml

ROOT = Path(__file__).resolve().parent.parent
spec = yaml.safe_load((ROOT / "open-api.yaml").read_text(encoding="utf-8"))
validate(spec)
state = json.loads((ROOT / "reports/oci/host-state.json").read_text(encoding="utf-8-sig"))
base = "https://" + state["publicHost"]
token = (ROOT / "reports/oci/bundle/secrets/api-token").read_text().strip()
evidence = []
checks = [("get", "/health", "/health", 200, False)]
unknown = "/api/reviews/00000000-0000-0000-0000-000000000000"
checks += [("get", unknown, "/api/reviews/{jobId}", 401, False),
           ("get", unknown, "/api/reviews/{jobId}", 404, True)]
for provider in ("github", "gitlab", "bitbucket"):
    path = "/webhooks/" + provider
    checks += [("post", path, path, 403, False), ("get", path, path, 405, False)]
for method, path, contract_path, expected, authorized in checks:
    headers = {"Content-Type": "application/json"}
    if authorized:
        headers["Authorization"] = "Bearer " + token
    request = Request(base + path, method=method.upper(), headers=headers,
                      data=b"{}" if method == "post" else None)
    try:
        response = urlopen(request, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        status = response.status
        payload = json.load(response)
    assert status == expected, (method, path, status, expected)
    # Wrong-method requests exercise the documented POST operation's 405 response.
    operation = method if method in spec["paths"][contract_path] else "post"
    response_spec = spec["paths"][contract_path][operation]["responses"][str(status)]
    if "$ref" in response_spec:
        response_spec = spec["components"]["responses"][response_spec["$ref"].split("/")[-1]]
    schema = response_spec["content"]["application/json"]["schema"]
    if "$ref" in schema:
        schema = spec["components"]["schemas"][schema["$ref"].split("/")[-1]]
    Draft202012Validator(schema).validate(payload)
    evidence.append({"method": method.upper(), "path": path, "status": status,
                     "response": payload, "schemaValid": True})
output = ROOT / "reports/oci/contract-results.json"
output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(f"OpenAPI valid; {len(evidence)} actual HTTPS response checks passed with certificate validation.")
