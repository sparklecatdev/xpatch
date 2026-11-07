#!/usr/bin/env python3
"""Run the synthetic patch/install/runtime smoke test on a disposable emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True, help="Disposable emulator serial, e.g. emulator-5554")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This test only accepts disposable Android emulators.")
sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
adb = str(sdk / "platform-tools/adb")
work = ROOT / ".work/smoke"
work.mkdir(parents=True, exist_ok=True)


def run(*command):
    return subprocess.check_output([adb, "-s", args.serial, *command], stderr=subprocess.STDOUT)


def shell(*command):
    return run("shell", *command).decode()


def tree():
    shell("uiautomator", "dump", "/sdcard/xpatch-smoke.xml")
    return ET.fromstring(shell("cat", "/sdcard/xpatch-smoke.xml"))


def click(text, scroll=False):
    for _ in range(8 if scroll else 1):
        for node in tree().iter("node"):
            if node.get("text", "").casefold() == text.casefold() or node.get("content-desc", "").casefold() == text.casefold():
                if node.get("enabled") != "true":
                    raise RuntimeError(f"Disabled control: {text}")
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return
        if scroll:
            shell("input", "swipe", "540", "2050", "540", "650", "300")
    raise RuntimeError(f"Missing control: {text}")


def wait_for(check, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.5)
    raise RuntimeError("Timed out waiting for Android")


def texts():
    return [node.get("text") for node in tree().iter("node")]


def has_control(description):
    return any(node.get("content-desc") == description for node in tree().iter("node"))


def restart():
    shell("am", "force-stop", "com.twitter.android")
    shell("am", "start", "-n", "com.twitter.android/.FixtureActivity")


installed = shell("pm", "list", "packages", "com.twitter.android")
if "package:com.twitter.android" in installed:
    details = shell("dumpsys", "package", "com.twitter.android")
    if "versionName=fixture-1" not in details:
        raise SystemExit("Refusing to replace a non-fixture X installation.")
    shell("pm", "uninstall", "com.twitter.android")
run("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
shell("pm", "clear", "dev.sparklecat.xpatch")
run("install", str(ROOT / "fixture/build/outputs/apk/debug/fixture-debug.apk"))
shell("am", "start", "-n", "com.twitter.android/.FixtureActivity")
wait_for(lambda: "Visible entries: 3" in texts())
assert "Premium display: false" in texts()
shell("am", "force-stop", "dev.sparklecat.xpatch")
shell("am", "start", "-n", "dev.sparklecat.xpatch/.MainActivity")
click("Use installed X")
wait_for(lambda: any("Application hook: matched" in (text or "") for text in texts()))
click("Build patched APK", scroll=True)


def generation():
    prefs = ET.fromstring(shell("run-as", "dev.sparklecat.xpatch", "cat", "shared_prefs/xpatch.xml"))
    return next((node.text for node in prefs if node.get("name") == "output"), "")


def built():
    return generation().startswith("build-")


wait_for(built, timeout=90)
patched = work / "patched-fixture.apk"
patched.write_bytes(run("exec-out", "run-as", "dev.sparklecat.xpatch", "cat", f"files/{generation()}/patched.apk"))
report = run("exec-out", "run-as", "dev.sparklecat.xpatch", "cat", f"files/{generation()}/report.txt").decode()
assert "fixture-1" in report and "2 timeline parser(s)" in report, report
assert "Premium UI: 1 site(s)" in report, report
(work / "report.txt").write_text(report)
with zipfile.ZipFile(patched) as apk:
    assert apk.read("assets/xpatch/NOTICE") == (ROOT / "third_party/piko/NOTICE").read_bytes()
    assert apk.read("assets/xpatch/Piko-LICENSE.txt") == (ROOT / "third_party/piko/Piko-LICENSE.txt").read_bytes()
    assert apk.read("assets/xpatch/release-notes.txt") == (ROOT / "app/src/main/assets/xpatch/release-notes.txt").read_bytes()
    assert json.loads(apk.read("assets/xpatch/profile.json"))["patchVersion"] == "0.3.0-alpha.1"
shell("pm", "uninstall", "com.twitter.android")
run("install", str(patched))
shell("am", "start", "-n", "com.twitter.android/.FixtureActivity")
wait_for(lambda: "Visible entries: 1" in texts())
wait_for(lambda: "Modern entries: 1" in texts())
visible = texts()
assert "tweet-2" in visible and "promoted-tweet-1" not in visible and "rtb-3" not in visible, visible
assert "Premium display: false" in visible
click("Save X media")
assert "Video · 4000 kbps · fixture-high.mp4" in texts()
assert not any("fixture-low" in (text or "") for text in texts())
click("Video · 4000 kbps · fixture-high.mp4")
assert "https://video.twimg.com/fixture-high.mp4" in texts()
click("Cancel")
click("Save X media")
click("Photo · original · fixture-photo")
assert "https://pbs.twimg.com/media/fixture-photo?format=png&name=orig" in texts()
click("Cancel")
click("XPatch mods")
click("Premium cosmetics: off", scroll=True)
click("OK")
restart()
wait_for(lambda: "Premium display: true" in texts())
click("XPatch mods")
click("Media downloads: on", scroll=True)
wait_for(lambda: not has_control("Save X media"))
restart()
wait_for(lambda: "Premium display: true" in texts())
assert not has_control("Save X media")
click("XPatch mods")
click("Media downloads: off", scroll=True)
wait_for(lambda: has_control("Save X media"))
restart()
wait_for(lambda: "Visible entries: 1" in texts())
click("XPatch mods")
click("Bottom navigation")
click("Hide this bar")
click("Save")
wait_for(lambda: "Home" not in texts())
click("XPatch mods")
click("Inspect this screen")
assert "toolbar" in texts()
shell("input", "keyevent", "4")
click("XPatch mods")
click("Ad filtering: hidden")
wait_for(lambda: "Ad filtering: hidden" not in texts())
time.sleep(.5)
restart()
wait_for(lambda: "Visible entries: 3" in texts())
wait_for(lambda: "Modern entries: 4" in texts())
assert "Home" not in texts(), "Live bar settings did not persist"
assert "Premium display: true" in texts(), "Premium setting did not persist"
click("Save X media")
assert "Video · 4000 kbps · fixture-high.mp4" in texts(), "Media capture stopped with ads disabled"
click("Clear list")
click("Save X media")
assert "Open a post containing" in " ".join(text for text in texts() if text)
click("OK")
click("XPatch mods")
click("Updates and release notes", scroll=True)
assert "Check mod updates on launch" in texts()
click("What's new")
assert any("What's new · XPatch" in (text or "") for text in texts())
click("Done")
(work / "fixture.png").write_bytes(run("exec-out", "screencap", "-p"))
shell("am", "force-stop", "dev.sparklecat.xpatch")
shell("am", "start", "-n", "dev.sparklecat.xpatch/.MainActivity")
wait_for(lambda: any("Saved X fixture-1" in (text or "") for text in texts()))
click("What's new in this version", scroll=True)
assert any("What's new ·" in (text or "") for text in texts())
click("Done")
print("PASS: patch, signature, install, ad filtering, Premium display state, original photo/highest MP4 selection, in-app toggles, bar hiding, inspector, persistent settings, update menus, saved APK restore, license assets")
