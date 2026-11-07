#!/usr/bin/env python3
"""Align, sign and verify the publisher APK using a private key supplied locally."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--password-file", required=True, type=Path)
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        parser.error("Set ANDROID_HOME to your Android SDK.")
    for path in (args.keystore, args.password_file):
        if not path.is_file():
            parser.error(f"Missing file: {path}")
    unsigned = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    if not unsigned.is_file():
        parser.error("Run ./gradlew :app:assembleRelease first.")
    pin = (ROOT / "release-signing.sha256").read_text().strip().lower()
    if not re.fullmatch(r"[a-f0-9]{64}", pin):
        parser.error("Invalid publisher certificate fingerprint.")
    tools = Path(sdk) / "build-tools/36.0.0"
    output = ROOT / ".xpatch/release"
    output.mkdir(parents=True, exist_ok=True)
    aligned, signed = output / "aligned.apk", output / "xpatch.apk"
    signed.unlink(missing_ok=True)
    try:
        subprocess.run([str(tools / "zipalign"), "-f", "-P", "16", "4", str(unsigned), str(aligned)], check=True)
        subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(args.keystore.resolve()), "--ks-key-alias", "publisher", "--ks-pass", f"file:{args.password_file.resolve()}", "--out", str(signed), str(aligned)], check=True)
        result = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(signed)], text=True)
        cert = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)", result)
        if not cert or cert.group(1).lower() != pin:
            raise ValueError("Publisher certificate does not match release-signing.sha256.")
        subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(signed)], check=True)
        digest = hashlib.sha256(signed.read_bytes()).hexdigest()
        (output / "SHA256SUMS").write_text(f"{digest}  xpatch.apk\n")
        print(f"Verified {signed}\nSHA-256: {digest}")
    except Exception:
        signed.unlink(missing_ok=True)
        raise
    finally:
        aligned.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
