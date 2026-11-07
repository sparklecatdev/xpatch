import json
import re
import signal
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from tests.test_patcher import apk_bytes


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.process = subprocess.Popen(
            [sys.executable, "-m", "xpatch", "--data", cls.temp.name,
             "serve", "--port", "0", "--no-browser"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        cls.url = cls.process.stdout.readline().strip().removeprefix("XPatch: ")
        if not cls.url.startswith("http://127.0.0.1:"):
            cls.process.terminate()
            raise AssertionError("Local server did not start")
        with urllib.request.urlopen(cls.url, timeout=5) as response:
            cls.token = re.search(r'name="xpatch-token" content="([^"]+)"', response.read().decode())[1]

    @classmethod
    def tearDownClass(cls):
        cls.process.send_signal(signal.SIGINT)
        cls.process.communicate(timeout=10)
        cls.temp.cleanup()

    def request(self, path, data=None, headers=None, authenticated=True):
        headers = dict(headers or {})
        if authenticated:
            headers["X-XPatch-Token"] = self.token
        request = urllib.request.Request(self.url + path, data=data, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            return response.status, response.read()

    def test_session_host_and_origin_checks(self):
        self.assertEqual(self.request("/api/state", authenticated=False)[0], 403)
        self.assertEqual(self.request("/api/state", headers={"Host": "attacker.invalid"})[0], 403)
        self.assertEqual(self.request("/api/settings", b"{}", {"Origin": "https://attacker.invalid"})[0], 403)
        self.assertEqual(self.request("/api/state")[0], 200)

    def test_upload_validation_and_no_overwrite(self):
        original = apk_bytes()
        self.assertEqual(self.request("/api/upload?name=wrong.apk", apk_bytes(package="other.package"))[0], 400)
        self.assertEqual(self.request("/api/upload?name=../escape.apk", apk_bytes())[0], 400)
        self.assertEqual(self.request("/api/upload?name=twitter.apk", original)[0], 200)
        self.assertEqual(self.request("/api/upload?name=twitter.apk", apk_bytes("2.0"))[0], 400)
        inbox = Path(self.temp.name) / "inbox"
        self.assertEqual([path.name for path in inbox.iterdir()], ["twitter.apk"])
        self.assertEqual((inbox / "twitter.apk").read_bytes(), original)

    def test_settings_and_download_paths(self):
        self.assertEqual(self.request("/api/settings", b'{"watch":"true"}')[0], 400)
        status, body = self.request("/api/settings", b'{"watch":false}')
        self.assertEqual((status, json.loads(body)), (200, {"ok": True}))
        self.assertEqual(self.request("/download/..%2Fprofile.apk?token=" + self.token)[0], 404)


if __name__ == "__main__":
    unittest.main()
