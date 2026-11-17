"""Isolated Linux/macOS script checks; no Docker daemon or repository credentials used.

Run with: python3 -m unittest discover -s scripts/tests -v
Requires Bash and Python 3.9+.
"""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest


SOURCE = Path(__file__).resolve().parents[2]


class PortableScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="portable scripts ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "checkout with spaces"
        self.root.mkdir()
        shutil.copytree(SOURCE / "scripts", self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copyfile(SOURCE / ".env.example", self.root / ".env.example")
        self.bin = Path(self.temp.name) / "bin"
        self.bin.mkdir()
        self.log = Path(self.temp.name) / "docker.jsonl"
        docker = self.bin / "docker"
        docker.write_text("""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['DOCKER_LOG'], 'a') as output:
    output.write(json.dumps({'args': args, 'cwd': os.getcwd(), 'provider': os.environ.get('REGRESSION_PROVIDER')}) + '\\n')
if os.environ.get('DOCKER_FAIL') and os.environ['DOCKER_FAIL'] in ' '.join(args):
    sys.exit(7)
if args == ['compose', 'config', '--format', 'json']:
    print(json.dumps({'services': {'agent-worker': {'environment': {'OLLAMA_MODEL': 'custom model:tag'}}}}))
if 'show' in args and os.environ.get('MODEL_MISSING') == '1':
    sys.exit(1)
""", encoding="utf-8")
        docker.chmod(0o755)
        self.env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                    "DOCKER_LOG": str(self.log), "PYTHONDONTWRITEBYTECODE": "1"}

    def run_script(self, name, *args, ok=True):
        result = subprocess.run(["bash", str(self.root / "scripts" / (name + ".sh")), *args],
                                cwd=self.temp.name, env=self.env, text=True, capture_output=True, timeout=15)
        if ok:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def initialize(self):
        self.run_script("Initialize-LocalEnvironment")

    @contextmanager
    def api(self, state="succeeded", quality=True, comment=True):
        requests = []
        jobs = {}
        token = (self.root / "secrets/api-token").read_text().strip()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, payload):
                if self.headers.get("Authorization") != "Bearer " + token:
                    self.send_error(401)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(payload).encode())

            def do_POST(self):
                case = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                job_id = str(len(jobs) + 1)
                jobs[job_id] = case
                requests.append(case)
                self.respond({"jobId": job_id, "state": "queued", "duplicate": False, "statusUrl": "/jobs/" + job_id})

            def do_GET(self):
                case = jobs[self.path.rsplit("/", 1)[1]]
                gitlab = case["platform"] == "gitlab"
                number = case["number"]
                findings = []
                if gitlab or number != 2:
                    findings = [{"severity": "MEDIUM" if gitlab or number == 3 else "HIGH",
                                 "file": "src/demo/ShippingCalculator.java" if gitlab else "src/demo/Checkout.java" if number == 3 else "src/demo/ArrayTotals.java",
                                 "line": 10 if not gitlab and number == 3 else 9 if not gitlab and number == 4 else 8,
                                 "explanation": "5,000 becomes 500" if gitlab else "12 instead of 1,250" if number == 3 else "array bounds exception"}]
                    if not quality:
                        findings[0]["line"] = 999
                self.respond({"state": state, "commentUrl": "https://fixture.invalid/comment" if comment else None,
                              "report": {"findings": findings, "errors": [], "model": "fixture"}})

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}", requests
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_initialization_is_private_and_preserves_configuration(self):
        self.initialize()
        credentials = {path.name: path.read_bytes() for path in (self.root / "secrets").iterdir()}
        self.assertEqual(8, len(credentials))
        self.assertTrue(credentials["api-token"])
        self.assertEqual(b"", credentials["github-app.pem"])
        for path in (self.root / "secrets").iterdir():
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
        env = self.root / ".env"
        self.assertIn(f"LOCAL_UID={os.getuid()}", env.read_text())
        self.assertIn(f"LOCAL_GID={os.getgid()}", env.read_text())
        env.write_text(env.read_text() + "CUSTOM=preserved")
        before = env.read_bytes()
        self.initialize()
        self.assertEqual(before, env.read_bytes())
        self.assertEqual(credentials, {path.name: path.read_bytes() for path in (self.root / "secrets").iterdir()})

    def test_startup_downloads_resolved_model_and_honors_no_build(self):
        self.env["MODEL_MISSING"] = "1"
        self.run_script("Start-Local", "--no-build")
        calls = self.calls()
        self.assertFalse(any(call["args"] == ["compose", "build"] for call in calls))
        self.assertIn(["compose", "exec", "-T", "ollama", "ollama", "pull", "custom model:tag"], [c["args"] for c in calls])
        self.assertTrue(all(call["cwd"] == str(self.root) for call in calls))

    def test_startup_stops_on_build_failure_and_skips_present_model(self):
        self.env["DOCKER_FAIL"] = "compose build"
        self.run_script("Start-Local", ok=False)
        self.assertEqual(1, len(self.calls()))
        self.log.unlink()
        del self.env["DOCKER_FAIL"]
        self.run_script("Start-Local")
        self.assertFalse(any("pull" in call["args"] for call in self.calls()))

    def test_tools_preserve_override_and_provider(self):
        (self.root / "compose.override.yaml").touch()
        self.run_script("Test-Project")
        self.assertTrue((self.root / "reports/tests").is_dir())
        self.run_script("Test-ModelRegression", "--provider", "ollama", "--label", "label with spaces")
        calls = self.calls()
        for call in calls:
            self.assertEqual(["compose", "-f", "compose.yaml", "-f", "compose.override.yaml", "-f", "compose.tools.yaml"], call["args"][:7])
        self.assertEqual("ollama", calls[-1]["provider"])
        self.assertEqual("label with spaces", calls[-1]["args"][-1])

    def test_tools_work_without_override_and_stop_on_capture_failure(self):
        self.env["DOCKER_FAIL"] = "capture"
        self.run_script("Test-ModelRegression", "--label", "fixture", ok=False)
        self.assertEqual(1, len(self.calls()))
        self.assertNotIn("compose.override.yaml", self.calls()[0]["args"])

    def test_review_submits_requested_platform_and_requires_publication(self):
        self.initialize()
        with self.api() as (base_url, requests):
            self.run_script("Invoke-Review", "--base-url", base_url, "--platform", "gitlab", "--number", "3")
            self.assertEqual("gitlab", requests[0]["platform"])
            self.assertEqual(3, requests[0]["number"])
        for state, comment in (("failed", True), ("succeeded", False)):
            with self.api(state=state, comment=comment) as (base_url, _):
                self.run_script("Invoke-Review", "--base-url", base_url, ok=False)

    def test_review_reports_polling_timeout(self):
        self.initialize()
        with self.api(state="running") as (base_url, _):
            result = self.run_script("Invoke-Review", "--base-url", base_url, "--timeout-seconds", "1", ok=False)
            self.assertIn("Timed out", result.stderr)

    def test_live_checks_all_nine_fixtures_and_verifies_comments(self):
        self.initialize()
        with self.api() as (base_url, requests):
            self.run_script("Test-LiveReviews", "--base-url", base_url)
            self.assertEqual(9, len(requests))
        results = json.loads((self.root / "reports/live-results.json").read_text())
        self.assertEqual(9, len(results))
        self.assertTrue(all(result["qualityPassed"] and result["remoteCommentVerified"] for result in results))
        self.assertEqual(9, len(self.calls()))

    def test_live_fails_for_wrong_findings_or_remote_comment_mismatch(self):
        self.initialize()
        with self.api(quality=False) as (base_url, _):
            self.run_script("Test-LiveReviews", "--base-url", base_url, ok=False)
        self.env["DOCKER_FAIL"] = "verify"
        with self.api() as (base_url, _):
            self.run_script("Test-LiveReviews", "--base-url", base_url, ok=False)
        results = json.loads((self.root / "reports/live-results.json").read_text())
        self.assertFalse(any(result["remoteCommentVerified"] for result in results))

    def test_bad_arguments_fail_before_docker_or_http(self):
        for script, args in (("Start-Local", ["--unknown"]), ("Test-Project", ["--unknown"]),
                             ("Test-ModelRegression", ["--provider", "bad", "--label", "test"]),
                             ("Test-ModelRegression", ["--label"]), ("Invoke-Review", ["--number", "0"])):
            self.run_script(script, *args, ok=False)
        self.assertEqual([], self.calls())


if __name__ == "__main__":
    unittest.main()
