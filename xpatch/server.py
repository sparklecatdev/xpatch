from __future__ import annotations

import json
import mimetypes
import os
import secrets
import shutil
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from socketserver import TCPServer

from .apk import MAX_APK, SUFFIXES, inspect_apk

STATIC = Path(__file__).parent / "static"


class LocalServer(ThreadingHTTPServer):
    def server_bind(self):
        TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address


def serve(manager, port=8765, open_browser=True):
    token = secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(60)

        def log_message(self, *_):
            pass

        def send_headers(self, status, kind, length=None):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
            if length is not None:
                self.send_header("Content-Length", str(length))
            self.end_headers()

        def reply(self, status, value):
            data = json.dumps(value).encode()
            self.send_headers(status, "application/json", len(data))
            self.wfile.write(data)

        def valid_host(self):
            return self.headers.get("Host") == f"127.0.0.1:{server.server_port}"

        def respond_file(self, path, download=False):
            size = path.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive" if download else
                             (mimetypes.guess_type(str(path))[0] or "application/octet-stream"))
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
            self.send_header("Referrer-Policy", "no-referrer")
            if download:
                self.send_header("Content-Disposition", f'attachment; filename="{path.name}"')
            self.end_headers()
            with path.open("rb") as source:
                shutil.copyfileobj(source, self.wfile, 1024 * 1024)

        def do_GET(self):
            try:
                self._get()
            except (BrokenPipeError, ConnectionResetError):
                pass
            except (OSError, ValueError) as exc:
                self.reply(400, {"error": str(exc)})

        def _get(self):
            if not self.valid_host():
                return self.reply(403, {"error": "Invalid host"})
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == "/":
                page = (STATIC / "index.html").read_text().replace("__TOKEN__", token).encode()
                self.send_headers(200, "text/html; charset=utf-8", len(page))
                return self.wfile.write(page)
            if parsed.path in {"/app.js", "/style.css"}:
                return self.respond_file(STATIC / parsed.path[1:])
            supplied = self.headers.get("X-XPatch-Token", "")
            if parsed.path.startswith("/download/"):
                supplied = urllib.parse.parse_qs(parsed.query).get("token", [""])[0]
            if not secrets.compare_digest(supplied, token):
                return self.reply(403, {"error": "Invalid session token; reload the app"})
            if parsed.path == "/api/state":
                return self.reply(200, manager.state())
            if parsed.path.startswith("/download/"):
                name = urllib.parse.unquote(parsed.path[len("/download/"):])
                path = (manager.root / "outputs" / name).resolve()
                if path.parent != manager.root / "outputs" or path.suffix != ".apk" or not path.is_file():
                    return self.reply(404, {"error": "Output not found"})
                return self.respond_file(path, download=True)
            self.reply(404, {"error": "Not found"})

        def do_POST(self):
            try:
                self._post()
            except (BrokenPipeError, ConnectionResetError):
                pass
            except (ValueError, OSError) as exc:
                self.reply(400, {"error": str(exc)})

        def _post(self):
            if not self.valid_host() or not secrets.compare_digest(self.headers.get("X-XPatch-Token", ""), token):
                return self.reply(403, {"error": "Invalid session token or host; reload the app"})
            origin = self.headers.get("Origin")
            if origin and origin != f"http://127.0.0.1:{server.server_port}":
                return self.reply(403, {"error": "Cross-origin request rejected"})
            try:
                length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                raise ValueError("Invalid request length") from None
            if self.headers.get("Transfer-Encoding"):
                raise ValueError("Chunked requests are not supported")
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == "/api/upload":
                if not 0 < length <= MAX_APK:
                    raise ValueError("APK must be between 1 byte and 1 GiB")
                name = urllib.parse.parse_qs(parsed.query).get("name", [""])[0]
                if Path(name).name != name or not name or len(name) > 180 or "\\" in name or any(ord(c) < 32 for c in name):
                    raise ValueError("Invalid file name")
                if Path(name).suffix.lower() not in SUFFIXES:
                    raise ValueError("Unsupported APK format")
                destination = manager.root / "inbox" / name
                if destination.exists():
                    raise ValueError("That file is already in the inbox; rename it before uploading")
                temporary = destination.parent / (secrets.token_hex(16) + ".upload")
                try:
                    with temporary.open("xb") as target:
                        remaining = length
                        while remaining:
                            chunk = self.rfile.read(min(1024 * 1024, remaining))
                            if not chunk:
                                raise ValueError("Upload interrupted")
                            target.write(chunk)
                            remaining -= len(chunk)
                    # Preserve extension for the inspector; .upload files are ignored by the watcher.
                    identity = inspect_apk(temporary, suffix=destination.suffix)
                    os.link(temporary, destination)
                finally:
                    temporary.unlink(missing_ok=True)
                return self.reply(200, {"name": name, **identity})
            if not 0 <= length <= 1024 * 1024:
                raise ValueError("Request body exceeds size limit")
            data = json.loads(self.rfile.read(length) or b"{}")
            if parsed.path == "/api/sync":
                manager.begin("sync")
            elif parsed.path == "/api/profile":
                manager.save_profile(data)
            elif parsed.path == "/api/settings":
                manager.configure(data)
            elif parsed.path == "/api/cancel":
                manager.cancel()
            elif parsed.path == "/api/patch":
                name = data.get("name", "") if isinstance(data, dict) else ""
                if not isinstance(name, str) or not name or Path(name).name != name:
                    raise ValueError("Choose an inbox APK")
                source = (manager.root / "inbox" / name).resolve()
                if source.parent != manager.root / "inbox" or not source.is_file():
                    raise ValueError("Inbox APK not found")
                manager.begin("patch", source)
            else:
                return self.reply(404, {"error": "Not found"})
            self.reply(200, {"ok": True})

    server = LocalServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    watcher = threading.Thread(target=manager.watch, daemon=True)
    watcher.start()
    url = f"http://127.0.0.1:{server.server_port}"
    print(f"XPatch: {url}\nData: {manager.root}", flush=True)
    if open_browser:
        import webbrowser
        webbrowser.open(url)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        manager.close()
