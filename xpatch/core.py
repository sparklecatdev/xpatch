from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import threading
import time
import uuid
from pathlib import Path

from .apk import inspect_apk
from . import releases

PRESETS = {
    "Essential": ["Remove Ads", "Remove premium upsell", "Clear tracking params", "No shortened URL"],
    "Custom interface": ["Remove Ads", "Remove premium upsell", "Clear tracking params",
                         "No shortened URL", "Customize Navigation Bar items", "Customize side bar items",
                         "Customize Inline action Bar items", "Customize timeline top bar",
                         "Customize explore tabs", "Customize notification tabs", "Customize profile tabs",
                         "Customize search tab items",
                         "Hide FAB Menu Buttons", "Hide badges from navigation bar icons"],
    "Client extras": ["Remove Ads", "Remove premium upsell", "Clear tracking params", "No shortened URL",
                      "Download patch", "Enable Undo Posts", "Enable PiP mode automatically",
                      "Enable force HD videos", "Dynamic color"],
}


def load_json(path: Path, fallback=None):
    if not path.exists():
        return copy.deepcopy(fallback)
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError) as exc:
        raise ValueError(f"Cannot read {path.name}: {exc}") from exc


def save_json(path: Path, value):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value, indent=2) + "\n")
    temp.replace(path)


def find_java() -> str:
    candidates = [str(Path(os.environ["JAVA_HOME"]) / "bin/java")] if os.environ.get("JAVA_HOME") else []
    candidates += [shutil.which("java"), "/opt/homebrew/opt/openjdk@21/bin/java",
                   "/usr/local/opt/openjdk@21/bin/java"]
    for candidate in dict.fromkeys(c for c in candidates if c):
        try:
            result = subprocess.run([candidate, "-version"], capture_output=True, text=True, timeout=15)
            match = re.search(r'version "(\d+)', result.stderr + result.stdout)
            if result.returncode == 0 and match and int(match[1]) >= 21:
                return candidate
        except (OSError, subprocess.TimeoutExpired):
            pass
    raise ValueError("Java 21 or newer is required. Install it and set JAVA_HOME, then retry.")


def twitter_catalog(metadata: dict) -> list:
    patches = []
    for patch in metadata.get("patches", []):
        packages = [p for p in patch.get("compatiblePackages") or [] if p.get("packageName") == "com.twitter.android"]
        if not packages:
            continue
        item = dict(patch)
        item["versions"] = sorted({t["version"] for p in packages for t in p.get("targets", [])
                                   if t.get("version") and not t.get("isExperimental")})
        if not item["versions"]:
            raise ValueError(f"No stable compatibility targets for {item['name']}")
        patches.append(item)
    if not patches or len({p["name"] for p in patches}) != len(patches):
        raise ValueError("Invalid Twitter patch catalog")
    return patches


def validate_profile(profile: dict, catalog: list, version=None) -> dict:
    if not isinstance(profile, dict) or not isinstance(profile.get("patches"), list):
        raise ValueError("A profile must contain a patches list")
    names = profile["patches"]
    if not names or len(names) > 300 or any(not isinstance(n, str) for n in names) or len(set(names)) != len(names):
        raise ValueError("Select at least one patch; duplicate patches are not allowed")
    by_name = {p["name"]: p for p in catalog}
    missing = set(names) - by_name.keys()
    if missing:
        raise ValueError("Saved patches are unavailable; review your profile: " + ", ".join(sorted(missing)))
    if version:
        incompatible = [n for n in names if version not in by_name[n]["versions"]]
        if incompatible:
            raise ValueError(f"Twitter {version} is unsupported by: {', '.join(incompatible)}. Wait for upstream patch support.")
    options = profile.get("options", {})
    if not isinstance(options, dict) or set(options) - set(names):
        raise ValueError("Options must belong to selected patches")
    for name, values in options.items():
        if not isinstance(values, dict):
            raise ValueError(f"Invalid options for {name}")
        definitions = {o["key"]: o for o in by_name[name].get("options", [])}
        for key, value in values.items():
            if key not in definitions:
                raise ValueError(f"Unknown option {name}: {key}")
            kind = definitions[key]["type"]
            valid = ((kind == "kotlin.Boolean" and type(value) is bool) or
                     (kind == "kotlin.Int" and type(value) is int and -2147483648 <= value <= 2147483647) or
                     (kind == "kotlin.String" and isinstance(value, str) and len(value) <= 8192) or
                     (kind == "kotlin.collections.List<kotlin.String>" and isinstance(value, list) and
                      len(value) <= 100 and all(isinstance(v, str) and len(v) <= 1024 for v in value)))
            if not valid:
                raise ValueError(f"Invalid value for {name}: {key}")
    return {"patches": list(names), "options": copy.deepcopy(options)}


class Manager:
    def __init__(self, root: Path):
        self.root = root.expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root.chmod(0o700)
        for name in ("tools", "inbox", "outputs", "runs", "signing"):
            (self.root / name).mkdir(exist_ok=True, mode=0o700)
        # One process owns this data directory, including its signing key and state.
        self.lock_file = (self.root / ".lock").open("a+")
        if os.name != "nt":
            import fcntl
            try:
                fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                self.lock_file.close()
                raise ValueError("XPatch is already using this data directory") from exc
        else:
            import msvcrt
            self.lock_file.write("0")
            self.lock_file.flush()
            self.lock_file.seek(0)
            try:
                msvcrt.locking(self.lock_file.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError as exc:
                self.lock_file.close()
                raise ValueError("XPatch is already using this data directory") from exc
        self.guard = threading.RLock()
        self.operation = threading.Lock()
        self.cancelled = threading.Event()
        self.process = None
        self.job = {"state": "idle", "logs": []}
        self.settings = load_json(self.root / "settings.json", {"watch": False, "autoSync": True})
        self.stop = threading.Event()

    def close(self):
        self.stop.set()
        self.cancel()
        with self.operation:
            self.lock_file.close()

    def state(self) -> dict:
        with self.guard:
            release = load_json(self.root / "release.json", {})
            catalog = release.get("catalog", [])
            profile = load_json(self.root / "profile.json", {"patches": PRESETS["Custom interface"], "options": {}})
            inputs = []
            for file in sorted((self.root / "inbox").iterdir()):
                if file.suffix.lower() in {".apk", ".apkm", ".apks", ".xapk"} and file.is_file():
                    inputs.append({"name": file.name, "size": file.stat().st_size})
            outputs = [{"name": f.name, "size": f.stat().st_size} for f in
                       sorted((self.root / "outputs").glob("*.apk"), key=lambda f: f.stat().st_mtime, reverse=True)]
            return {"release": {k: v for k, v in release.items() if k != "catalog"}, "catalog": catalog,
                    "profile": profile, "presets": PRESETS, "settings": dict(self.settings),
                    "job": copy.deepcopy(self.job), "inputs": inputs, "outputs": outputs,
                    "dataDirectory": str(self.root), "versions": sorted({v for p in catalog for v in p["versions"]})}

    def log(self, message):
        line = re.sub(r"\x1b\[[0-9;]*[a-zA-Z]", "", str(message)).rstrip()
        with self.guard:
            self.job["logs"].append(line[:8000])
            self.job["logs"] = self.job["logs"][-1000:]

    def save_profile(self, profile):
        if not self.operation.acquire(blocking=False):
            raise ValueError("Wait for the current job before saving a profile")
        try:
            release = load_json(self.root / "release.json", {})
            validated = validate_profile(profile, release.get("catalog", []))
            with self.guard:
                save_json(self.root / "profile.json", validated)
            return validated
        finally:
            self.operation.release()

    def configure(self, settings):
        if not isinstance(settings, dict) or set(settings) - {"watch", "autoSync"} or any(type(v) is not bool for v in settings.values()):
            raise ValueError("Invalid settings")
        with self.guard:
            self.settings.update(settings)
            save_json(self.root / "settings.json", self.settings)

    def begin(self, kind, source=None):
        if kind not in {"sync", "patch"}:
            raise ValueError("Unknown job")
        if not self.operation.acquire(blocking=False):
            raise ValueError("Another job is already running")
        if self.stop.is_set():
            self.operation.release()
            raise ValueError("XPatch is shutting down")
        self.cancelled.clear()
        with self.guard:
            self.job = {"state": "running", "kind": kind, "logs": [], "started": time.time()}
        thread = threading.Thread(target=self._work, args=(kind, source), daemon=True)
        thread.start()
        return thread

    def _work(self, kind, source):
        try:
            result = self.sync() if kind == "sync" else self.patch(source)
            self.check_cancelled()
            with self.guard:
                self.job.update(state="succeeded", result=result)
        except Exception as exc:
            self.log(exc)
            with self.guard:
                self.job.update(state="cancelled" if self.cancelled.is_set() else "failed", error=str(exc))
        finally:
            self.operation.release()

    def check_cancelled(self):
        if self.cancelled.is_set():
            raise ValueError("Job cancelled")

    def cancel(self):
        self.cancelled.set()
        with self.guard:
            process = self.process
            if process and process.poll() is None:
                try:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def run(self, args):
        self.check_cancelled()
        env = dict(os.environ, MORPHE_DATA_DIR=str(self.root / "signing"))
        with self.guard:
            self.process = subprocess.Popen(args, cwd=self.root, env=env, stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, text=True, errors="replace",
                                            start_new_session=os.name != "nt")
            process = self.process
        try:
            for line in process.stdout:
                self.log(line)
                self.check_cancelled()
            code = process.wait()
            self.check_cancelled()
            if code:
                raise ValueError(f"Morphe exited with status {code}. See the job log.")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
            with self.guard:
                self.process = None

    def sync(self):
        java = find_java()
        self.log("Checking stable Morphe and Piko releases")
        engine, patches = releases.latest("engine"), releases.latest("patches")
        metadata = releases.json_url(f"https://raw.githubusercontent.com/crimera/piko/{patches['tag']}/patches-list.json")
        if metadata.get("version", "").lstrip("v") != patches["tag"].lstrip("v"):
            raise ValueError("Patch catalog does not match the release")
        catalog = twitter_catalog(metadata)
        for asset in (engine, patches):
            self.check_cancelled()
            releases.download(asset, self.root / "tools", self.log)
        self.check_cancelled()
        listing = self.root / "tools" / "engine-catalog.txt"
        self.run([java, "-jar", str(self.root / "tools" / engine["name"]), "list-patches",
                  "--patches", str(self.root / "tools" / patches["name"]), "--filter-package-name",
                  "com.twitter.android", "--out", str(listing)])
        listed = set(re.findall(r"^\s*Name:\s*(.*?)\s*$", listing.read_text(), re.MULTILINE))
        if not {p["name"] for p in catalog} <= listed:
            raise ValueError("Engine and release patch catalogs disagree")
        release = {"engine": engine, "patches": patches, "catalog": catalog, "checkedAt": time.time()}
        with self.guard:
            save_json(self.root / "release.json", release)
        self.log(f"Ready: Piko {patches['tag']}, Morphe {engine['tag']}, {len(catalog)} Twitter patches")
        return {"patches": patches["tag"], "engine": engine["tag"]}

    def patch(self, source: Path):
        source = Path(source).resolve()
        identity = inspect_apk(source)
        self.log(f"Twitter {identity['version']} · {source.name}")
        if self.settings["autoSync"] or not (self.root / "release.json").exists():
            self.sync()
        release = load_json(self.root / "release.json")
        profile = load_json(self.root / "profile.json", {"patches": PRESETS["Custom interface"], "options": {}})
        profile = validate_profile(profile, release["catalog"], identity["version"])
        java = find_java()
        for kind in ("engine", "patches"):
            asset = release[kind]
            if releases.sha256(self.root / "tools" / asset["name"]) != asset["sha256"]:
                raise ValueError(f"Cached {kind} checksum changed; sync tools again")
        fingerprint = releases.sha256(source)
        key = hashlib.sha256(json.dumps([fingerprint, release["engine"]["sha256"],
                    release["patches"]["sha256"], profile], sort_keys=True).encode()).hexdigest()
        history = load_json(self.root / "history.json", {})
        previous = history.get(key)
        if previous and (self.root / "outputs" / previous["output"]).is_file():
            output = self.root / "outputs" / previous["output"]
            if releases.sha256(output) == previous["sha256"]:
                self.log("This source and profile have already been patched")
                return previous
        run = self.root / "runs" / uuid.uuid4().hex
        run.mkdir()
        # Snapshot the input so a watched file cannot change underneath the engine.
        input_path = run / ("input" + source.suffix.lower())
        version = re.sub(r"[^a-zA-Z0-9._-]", "_", identity["version"])
        filename = f"twitter-{version}-{key[:12]}.apk"
        pending, report = run / "patched.apk", run / "result.json"
        args = [java, "-Xmx4g", "-jar", str(self.root / "tools" / release["engine"]["name"]),
                "patch", "--patches", str(self.root / "tools" / release["patches"]["name"]), "--exclusive"]
        for name in profile["patches"]:
            args.extend(["--enable", name])
        if profile["options"]:
            options_file = run / "engine-options.json"
            save_json(options_file, [{"meta": {"source": release["patches"]["name"],
                      "sha256": release["patches"]["sha256"]}, "patches": {
                      p["name"]: {"enabled": p["name"] in profile["patches"],
                                  "options": profile["options"].get(p["name"], {})}
                      for p in release["catalog"]}}])
            args.extend(["--options-file", str(options_file)])
        args.extend(["--out", str(pending), "--result-file", str(report), str(input_path)])
        save_json(run / "profile.json", profile)
        save_json(run / "source.json", {**identity, "sha256": fingerprint, "release": release["patches"]})
        try:
            shutil.copyfile(source, input_path)
            if releases.sha256(input_path) != fingerprint:
                raise ValueError("Source changed while copying; retry when the download is complete")
            self.run(args)
            result = load_json(report, {})
            applied = set(result.get("appliedPatches", []))
            if (result.get("success") is not True or result.get("failedPatches") or
                    set(profile["patches"]) - applied or not pending.is_file()):
                raise ValueError("Patch report did not confirm every selected patch; output withheld")
            output_identity = inspect_apk(pending)
            if output_identity["version"] != identity["version"]:
                raise ValueError("Output version differs from input")
            if not any(s.get("step") == "SIGNING" and s.get("success") is True for s in result.get("stepResults", [])):
                raise ValueError("Patch report did not confirm signing")
            self.check_cancelled()
            output = self.root / "outputs" / filename
            pending.replace(output)
            record = {"output": filename, "sha256": releases.sha256(output), "version": identity["version"],
                      "patches": release["patches"]["tag"], "createdAt": time.time(), "run": run.name}
            history[key] = record
            save_json(self.root / "history.json", history)
            self.log(f"Signed APK ready: {filename}")
            return record
        finally:
            input_path.unlink(missing_ok=True)
            pending.unlink(missing_ok=True)
            save_json(run / "log.json", self.job["logs"])

    def watch(self):
        """Only retry a completed/failed file when it, the profile, or upstream changes."""
        seen, stable = {}, {}
        last_sync = 0
        while not self.stop.wait(5):
            if not self.settings["watch"] or self.operation.locked():
                continue
            try:
                now = time.time()
                if self.settings["autoSync"] and now - last_sync >= 3600:
                    last_sync = now
                    self.begin("sync").join()
                    continue
                release = load_json(self.root / "release.json", {})
                profile = load_json(self.root / "profile.json", {})
                revision = json.dumps([release.get("patches"), release.get("engine"), profile], sort_keys=True)
                for path in sorted((self.root / "inbox").iterdir()):
                    if path.suffix.lower() not in {".apk", ".apkm", ".apks", ".xapk"} or not path.is_file():
                        continue
                    stat = path.stat()
                    stamp = (stat.st_size, stat.st_mtime_ns)
                    if stable.get(path.name) != stamp:
                        stable[path.name] = stamp
                        continue
                    signature = (stamp, revision)
                    if seen.get(path.name) == signature:
                        continue
                    try:
                        thread = self.begin("patch", path)
                    except ValueError:
                        break
                    seen[path.name] = signature
                    thread.join()
                    break
            except (OSError, ValueError) as exc:
                self.log(f"Inbox: {exc}")
