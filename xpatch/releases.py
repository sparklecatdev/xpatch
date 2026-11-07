"""Fetch only official stable upstream releases; verify every executable asset."""
from __future__ import annotations

import hashlib
import json
import re
import urllib.request
from pathlib import Path

REPOS = {"engine": "MorpheApp/morphe-desktop", "patches": "crimera/piko"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def request(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "XPatch/0.1", "Accept": "application/vnd.github+json"})
    return urllib.request.urlopen(req, timeout=60)


def json_url(url: str) -> dict:
    with request(url) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("Upstream metadata exceeds size limit")
    result = json.loads(data)
    if not isinstance(result, dict):
        raise ValueError("Unexpected upstream metadata")
    return result


def latest(kind: str) -> dict:
    repo = REPOS[kind]
    release = json_url(f"https://api.github.com/repos/{repo}/releases/latest")
    tag = release.get("tag_name", "")
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"v?\d+\.\d+\.\d+", tag):
        raise ValueError("Expected a stable upstream release")
    pattern = r"morphe-desktop-[\d.]+-all\.jar" if kind == "engine" else r"patches-[\d.]+\.mpp"
    assets = [a for a in release.get("assets", []) if re.fullmatch(pattern, a.get("name", ""))]
    if len(assets) != 1:
        raise ValueError(f"Cannot find a unique {kind} release asset")
    asset = assets[0]
    digest = asset.get("digest", "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError(f"GitHub did not provide a SHA-256 digest for {asset['name']}")
    url = asset["browser_download_url"]
    if url != f"https://github.com/{repo}/releases/download/{tag}/{asset['name']}":
        raise ValueError("Unexpected release download URL")
    return {"tag": tag, "name": asset["name"], "url": url, "sha256": digest[7:]}


def download(asset: dict, directory: Path, log) -> Path:
    destination = directory / asset["name"]
    if destination.is_file() and sha256(destination) == asset["sha256"]:
        return destination
    temporary = destination.with_suffix(destination.suffix + ".part")
    log(f"Downloading {asset['name']}")
    try:
        total = 0
        with request(asset["url"]) as response, temporary.open("wb") as target:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > 512 * 1024 * 1024:
                    raise ValueError("Release asset exceeds size limit")
                target.write(chunk)
        if sha256(temporary) != asset["sha256"]:
            raise ValueError("Release checksum mismatch; download rejected")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination
