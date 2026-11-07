from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from pathlib import Path

from .core import Manager, find_java


def default_data():
    return Path(os.environ.get("XPATCH_DATA_DIR", str(Path.home() / ".local/share/xpatch")))


def main():
    parser = argparse.ArgumentParser(description="Patch locally supplied Twitter Android APKs using Morphe and Piko.")
    parser.add_argument("--data", type=Path, default=default_data(), help="Persistent tools, profiles, inbox and signing directory")
    commands = parser.add_subparsers(dest="command")
    web = commands.add_parser("serve", help="Open the local app (default)")
    web.add_argument("--port", type=int, default=8765)
    web.add_argument("--no-browser", action="store_true")
    commands.add_parser("sync", help="Fetch and verify the latest stable patch tools")
    commands.add_parser("doctor", help="Check Java and show current compatibility")
    commands.add_parser("watch", help="Patch new inbox files; retry after upstream updates")
    profile = commands.add_parser("profile", help="Export or import a JSON patch profile")
    profile.add_argument("--import", dest="import_path", type=Path)
    patch = commands.add_parser("patch", help="Patch an APK, APKM, APKS or XAPK")
    patch.add_argument("source", type=Path)
    args = parser.parse_args()
    manager = None
    try:
        manager = Manager(args.data)
        command = args.command or "serve"
        if command == "serve":
            from .server import serve
            serve(manager, getattr(args, "port", 8765), not getattr(args, "no_browser", False))
        elif command == "doctor":
            print(f"Java: {find_java()}\nData: {manager.root}")
            state = manager.state()
            print("Supported Twitter versions: " + (", ".join(state["versions"]) or "run sync first"))
        elif command == "profile":
            if args.import_path:
                manager.save_profile(json.loads(args.import_path.read_text()))
            print(json.dumps(manager.state()["profile"], indent=2))
        elif command == "watch":
            manager.configure({"watch": True})
            print(f"Watching {manager.root / 'inbox'} (Ctrl-C to stop)", flush=True)
            threading.Thread(target=manager.watch, daemon=True).start()
            _stream(manager, None)
        else:
            thread = manager.begin(command, getattr(args, "source", None))
            _stream(manager, thread)
            if manager.state()["job"]["state"] != "succeeded":
                return 1
        return 0
    except KeyboardInterrupt:
        return 130
    except (ValueError, OSError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if manager and not manager.lock_file.closed:
            manager.close()


def _stream(manager, thread):
    position, started = 0, None
    while True:
        job = manager.state()["job"]
        if job.get("started") != started:
            position, started = 0, job.get("started")
        for line in job["logs"][position:]:
            print(line, flush=True)
        position = len(job["logs"])
        if thread and not thread.is_alive():
            break
        time.sleep(0.2)


if __name__ == "__main__":
    sys.exit(main())
