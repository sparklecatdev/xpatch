import hashlib
import io
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from xpatch import releases
from xpatch.apk import inspect_apk, manifest_identity
from xpatch.core import Manager, save_json, twitter_catalog, validate_profile


def apk_bytes(version="1.0", package="com.twitter.android"):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        archive.writestr("AndroidManifest.xml", f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionName="{version}" android:versionCode="1"/>')
    return data.getvalue()


def binary_manifest(utf8=True):
    strings = ["manifest", "package", "versionName", "versionCode", "com.twitter.android", "v" * 140]
    encoded, offsets = b"", []
    for value in strings:
        offsets.append(len(encoded))
        if utf8:
            n = len(value)
            length = bytes([0x80 | (n >> 8), n & 255]) if n > 127 else bytes([n])
            encoded += length * 2 + value.encode() + b"\0"
        else:
            encoded += struct.pack("<H", len(value)) + value.encode("utf-16le") + b"\0\0"
    start = 28 + 4 * len(strings)
    pool = struct.pack("<HHIIIIII", 1, 28, start + len(encoded), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack("<" + "I" * len(strings), *offsets) + encoded
    attributes = b""
    for name, kind, value in [(1, 3, 4), (2, 3, 5), (3, 16, 1)]:
        attributes += struct.pack("<IIIHBBI", 0xFFFFFFFF, name, 0xFFFFFFFF, 8, 0, kind, value)
    element = struct.pack("<HHIII", 0x102, 16, 36 + len(attributes), 1, 0xFFFFFFFF)
    element += struct.pack("<IIHHHHHH", 0xFFFFFFFF, 0, 20, 20, 3, 0, 0, 0) + attributes
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(element)) + pool + element


CATALOG = [{"name": "Remove Ads", "versions": ["1.0"], "options": [
    {"key": "count", "type": "kotlin.Int"}, {"key": "items", "type": "kotlin.collections.List<kotlin.String>"}]}]
PROFILE = {"patches": ["Remove Ads"], "options": {}}


class ManifestTests(unittest.TestCase):
    def test_binary_string_pools_and_typed_attributes(self):
        for utf8 in (True, False):
            identity = manifest_identity(binary_manifest(utf8))
            self.assertEqual(identity, {"package": "com.twitter.android", "version": "v" * 140, "versionCode": 1})

    def test_malformed_binary_is_rejected(self):
        for data in (b"", b"nonsense", binary_manifest()[:-1], b"<manifest>"):
            with self.subTest(data=data[:10]), self.assertRaises(ValueError):
                manifest_identity(data)

    def test_apk_bundle_and_package_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "twitter.apk"
            source.write_bytes(apk_bytes())
            self.assertEqual(inspect_apk(source)["version"], "1.0")
            bundle = Path(directory) / "twitter.apks"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("splits/base-master.apk", source.read_bytes())
                archive.writestr("splits/config.en.apk", b"irrelevant")
            self.assertEqual(inspect_apk(bundle)["package"], "com.twitter.android")
            source.write_bytes(apk_bytes(package="other.package"))
            with self.assertRaisesRegex(ValueError, "Expected com.twitter.android"):
                inspect_apk(source)


class ProfileTests(unittest.TestCase):
    def test_unsupported_or_missing_patches_are_rejected(self):
        for profile, version in [(PROFILE, "2.0"), ({"patches": ["missing"]}, None),
                                 ({"patches": []}, None), ({"patches": ["Remove Ads"] * 2}, None)]:
            with self.subTest(profile=profile, version=version), self.assertRaises(ValueError):
                validate_profile(profile, CATALOG, version)

    def test_option_types_and_ownership(self):
        valid = {"patches": ["Remove Ads"], "options": {"Remove Ads": {"count": 3, "items": ["Home"]}}}
        self.assertEqual(validate_profile(valid, CATALOG, "1.0"), valid)
        for options in ({"Remove Ads": {"count": True}}, {"Remove Ads": {"items": "Home"}},
                        {"Remove Ads": {"unknown": 1}}, {"unselected": {}}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                validate_profile({**PROFILE, "options": options}, CATALOG)

    def test_catalog_filters_packages_before_duplicate_names(self):
        def item(package, experimental=False):
            return {"name": "Shared name", "compatiblePackages": [{"packageName": package,
                    "targets": [{"version": "1.0", "isExperimental": experimental}]}]}
        self.assertEqual(len(twitter_catalog({"patches": [item("instagram"), item("com.twitter.android")]})), 1)
        with self.assertRaises(ValueError):
            twitter_catalog({"patches": [item("com.twitter.android", True)]})


class DownloadTests(unittest.TestCase):
    def test_checksum_failure_leaves_no_executable(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(releases, "request", return_value=io.BytesIO(b"wrong")):
            asset = {"name": "engine.jar", "url": "https://example.invalid", "sha256": "0" * 64}
            with self.assertRaisesRegex(ValueError, "checksum"):
                releases.download(asset, Path(directory), lambda _: None)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_rejects_unofficial_release_url(self):
        asset = {"name": "patches-1.0.0.mpp", "digest": "sha256:" + "0" * 64,
                 "browser_download_url": "https://example.invalid/patches.mpp"}
        with patch.object(releases, "json_url", return_value={"tag_name": "v1.0.0", "assets": [asset]}):
            with self.assertRaisesRegex(ValueError, "download URL"):
                releases.latest("patches")


class BuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.manager = Manager(Path(self.temp.name))
        self.manager.configure({"autoSync": False})
        self.source = self.manager.root / "inbox/twitter.apk"
        self.source.write_bytes(apk_bytes())
        assets = {}
        for kind, name in [("engine", "engine.jar"), ("patches", "patches.mpp")]:
            path = self.manager.root / "tools" / name
            path.write_bytes(kind.encode())
            assets[kind] = {"name": name, "sha256": releases.sha256(path), "tag": "v1.0.0"}
        save_json(self.manager.root / "release.json", {**assets, "catalog": CATALOG})
        save_json(self.manager.root / "profile.json", PROFILE)
        self.java = patch("xpatch.core.find_java", return_value="java")
        self.java.start()

    def tearDown(self):
        self.java.stop()
        self.manager.close()
        self.temp.cleanup()

    def engine(self, args, *, applied=True, signing=True, version="1.0"):
        Path(args[args.index("--out") + 1]).write_bytes(apk_bytes(version))
        save_json(Path(args[args.index("--result-file") + 1]), {
            "success": True, "appliedPatches": ["Remove Ads"] if applied else [], "failedPatches": [],
            "stepResults": [{"step": "SIGNING", "success": signing}]})

    def test_signed_build_idempotence_and_cleanup(self):
        with patch.object(self.manager, "run", side_effect=self.engine) as run:
            first = self.manager.patch(self.source)
            second = self.manager.patch(self.source)
            self.assertEqual(first, second)
            self.assertEqual(run.call_count, 1)
            self.assertTrue((self.manager.root / "outputs" / first["output"]).is_file())
            self.assertEqual(list((self.manager.root / "runs").glob("*/input.apk")), [])

    def test_incomplete_or_unsigned_or_wrong_version_is_withheld(self):
        for changes in ({"applied": False}, {"signing": False}, {"version": "2.0"}):
            with self.subTest(changes=changes), patch.object(self.manager, "run", side_effect=lambda args: self.engine(args, **changes)):
                with self.assertRaises(ValueError):
                    self.manager.patch(self.source)
                self.assertEqual(list((self.manager.root / "outputs").iterdir()), [])
                self.assertEqual(list((self.manager.root / "runs").glob("*/patched.apk")), [])

    def test_tampered_engine_is_rejected_before_execution(self):
        (self.manager.root / "tools/engine.jar").write_bytes(b"changed")
        with patch.object(self.manager, "run") as run, self.assertRaisesRegex(ValueError, "checksum"):
            self.manager.patch(self.source)
        run.assert_not_called()

    def test_options_file_preserves_types(self):
        profile = {"patches": ["Remove Ads"], "options": {"Remove Ads": {"count": 4, "items": ["Home", "Search"]}}}
        save_json(self.manager.root / "profile.json", profile)
        def engine(args):
            options = json.loads(Path(args[args.index("--options-file") + 1]).read_text())
            self.assertEqual(options[0]["patches"]["Remove Ads"]["options"], profile["options"]["Remove Ads"])
            self.engine(args)
        with patch.object(self.manager, "run", side_effect=engine):
            self.manager.patch(self.source)

    def test_directory_and_job_lock(self):
        with self.assertRaisesRegex(ValueError, "already using"):
            Manager(self.manager.root)
        self.manager.operation.acquire()
        try:
            with self.assertRaisesRegex(ValueError, "already running"):
                self.manager.begin("sync")
            with self.assertRaisesRegex(ValueError, "Wait"):
                self.manager.save_profile(PROFILE)
        finally:
            self.manager.operation.release()


if __name__ == "__main__":
    unittest.main()
