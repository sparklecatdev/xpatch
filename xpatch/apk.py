"""Read package identity without extracting or executing an APK."""
from __future__ import annotations

import struct
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

SUFFIXES = {".apk", ".apkm", ".apks", ".xapk"}
MAX_APK = 1024 * 1024 * 1024
MAX_MANIFEST = 8 * 1024 * 1024
ANDROID = "http://schemas.android.com/apk/res/android"


def manifest_identity(data: bytes) -> dict:
    try:
        if data.lstrip().startswith(b"<"):
            root = ET.fromstring(data)
            attrs = {"package": root.get("package"),
                     "versionName": root.get(f"{{{ANDROID}}}versionName"),
                     "versionCode": root.get(f"{{{ANDROID}}}versionCode")}
        else:
            attrs = _binary_manifest(data)
        package, version = attrs.get("package"), attrs.get("versionName")
        if not isinstance(package, str) or not isinstance(version, str) or not version:
            raise ValueError("Manifest is missing package or versionName")
        return {"package": package, "version": version, "versionCode": attrs.get("versionCode")}
    except (struct.error, IndexError, UnicodeError, ET.ParseError) as exc:
        raise ValueError("Invalid Android manifest") from exc


def _binary_manifest(data: bytes) -> dict:
    def u16(pos):
        return struct.unpack_from("<H", data, pos)[0]

    def u32(pos):
        return struct.unpack_from("<I", data, pos)[0]

    if len(data) < 8 or u16(0) != 3 or u32(4) != len(data):
        raise ValueError("Invalid binary Android manifest")
    strings = []
    pos = u16(2)
    while pos + 8 <= len(data):
        kind, header, size = struct.unpack_from("<HHI", data, pos)
        end = pos + size
        if header < 8 or size < header or end > len(data):
            raise ValueError("Invalid manifest chunk")
        if kind == 1:  # ResStringPool
            if header < 28:
                raise ValueError("Invalid string pool")
            count, _, flags, start = struct.unpack_from("<IIII", data, pos + 8)
            if count > (size - header) // 4 or start > size:
                raise ValueError("Invalid string offsets")

            def length(at, utf8):
                first = data[at] if utf8 else u16(at)
                step, high = (1, 0x80) if utf8 else (2, 0x8000)
                if first & high:
                    second = data[at + step] if utf8 else u16(at + step)
                    return ((first & (high - 1)) << (8 if utf8 else 16)) | second, at + step * 2
                return first, at + step

            for i in range(count):
                at = pos + start + u32(pos + header + i * 4)
                if not pos + start <= at < end:
                    raise ValueError("String outside manifest chunk")
                utf8 = bool(flags & 0x100)
                n, at = length(at, utf8)
                if utf8:
                    n, at = length(at, True)
                byte_count = n if utf8 else n * 2
                if at + byte_count > end:
                    raise ValueError("Truncated manifest string")
                strings.append(data[at:at + byte_count].decode("utf-8" if utf8 else "utf-16le"))
        elif kind == 0x102:  # ResXMLTree_startElement; node header precedes extension.
            if header < 16 or header + 20 > size:
                raise ValueError("Invalid manifest element")
            ext = pos + header
            if strings[u32(ext + 4)] == "manifest":
                offset, stride, count = struct.unpack_from("<HHH", data, ext + 8)
                if stride < 20 or offset < 20 or ext + offset + count * stride > end:
                    raise ValueError("Invalid manifest attributes")
                attrs = {}
                for i in range(count):
                    at = ext + offset + i * stride
                    name, raw = u32(at + 4), u32(at + 8)
                    value_type, value = data[at + 15], u32(at + 16)
                    attrs[strings[name]] = (strings[raw] if raw != 0xFFFFFFFF else
                                             strings[value] if value_type == 3 else value)
                return attrs
        pos = end
    raise ValueError("No manifest element found")


def _read_manifest(archive: zipfile.ZipFile) -> dict:
    item = archive.getinfo("AndroidManifest.xml")
    if item.file_size > MAX_MANIFEST:
        raise ValueError("Android manifest is too large")
    return manifest_identity(archive.read(item))


def inspect_apk(path: Path, *, suffix=None) -> dict:
    if (suffix or path.suffix).lower() not in SUFFIXES:
        raise ValueError("Choose an APK, APKM, APKS, or XAPK file")
    if not 0 < path.stat().st_size <= MAX_APK:
        raise ValueError("APK must be between 1 byte and 1 GiB")
    try:
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > 100000:
                raise ValueError("Too many archive entries")
            if "AndroidManifest.xml" in archive.namelist():
                identity = _read_manifest(archive)
            else:
                candidates = [i for i in archive.infolist() if i.filename.lower().endswith(".apk")]
                preferred = [i for i in candidates if i.filename in
                             ("base.apk", "splits/base-master.apk", "splits/base.apk")]
                if not preferred:
                    preferred = [i for i in candidates if not Path(i.filename).name.startswith(("split", "config"))]
                if not preferred:
                    raise ValueError("Bundle has no identifiable base APK")
                item = max(preferred, key=lambda i: i.file_size)
                if not 0 < item.file_size <= MAX_APK:
                    raise ValueError("Base APK is too large")
                with tempfile.TemporaryFile() as temp, archive.open(item) as source:
                    total = 0
                    while True:
                        chunk = source.read(1024 * 1024)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > MAX_APK:
                            raise ValueError("Base APK is too large")
                        temp.write(chunk)
                    temp.seek(0)
                    with zipfile.ZipFile(temp) as base:
                        identity = _read_manifest(base)
    except (zipfile.BadZipFile, KeyError, RuntimeError, OSError) as exc:
        raise ValueError(f"Cannot read APK: {exc}") from exc
    if identity["package"] != "com.twitter.android":
        raise ValueError(f"Expected com.twitter.android, found {identity['package']}")
    return identity
