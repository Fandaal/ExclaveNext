#!/usr/bin/env python3
"""Release-build R8 audit for Exclave Next.

Run after :app:assembleOssRelease. Exits non-zero when a check fails.

Checks:
  1. Required keep-rules are present in the merged R8 configuration.
  2. Nothing from the reflection watchlist was REMOVED from the build
     (usage.txt lists what R8 deemed dead).
  3. Watchlist classes survive as their OWN mapping sections — not merged
     into unrelated synthetics (the kotlin.TuplesKt failure mode of 0.17.58.1).
  4. Strings the runtime reflects over exist inside the release dex.

Background: 0.17.58.1 shipped with com.maxmind.db.Metadata folded into
kotlin.TuplesKt and the @MaxMindDbConstructor annotation stripped, so every
.mmdb was rejected at runtime. Debug builds never run R8 — this audit is the
release-side safety net.
"""
import re
import sys
from pathlib import Path

BUILD = Path(__file__).resolve().parent.parent / "app/build/outputs"
MAPPING_DIR = BUILD / "mapping/ossRelease"
APK_DIR = BUILD / "apk/oss/release"

# (label, required -keep rule pattern in configuration.txt)
REQUIRED_RULES = [
    ("maxmind-db", r"-keep class com\.maxmind\.db\.\*\*"),
    ("maxmind model", r"-keep class com\.maxmind\.geoip2\.model\.\*\*"),
    ("maxmind record", r"-keep class com\.maxmind\.geoip2\.record\.\*\*"),
    ("annotation attrs", r"-keepattributes .*RuntimeVisibleAnnotations"),
    ("snakeyaml", r"-keep class org\.yaml\.snakeyaml\.\*\*"),
]

# (label, class that must survive UNMERGED and with all members)
UNMERGED_CLASSES = [
    ("maxmind Metadata", "com.maxmind.db.Metadata"),
    ("maxmind Decoder", "com.maxmind.db.Decoder"),
    ("maxmind Reader", "com.maxmind.db.Reader"),
    ("CountryResponse", "com.maxmind.geoip2.model.CountryResponse"),
    ("AsnResponse", "com.maxmind.geoip2.model.AsnResponse"),
    ("record Country", "com.maxmind.geoip2.record.Country"),
    # NOTE: geoip2 4.2.1 has no record.ASN — ASN fields live inside
    # AsnResponse itself. Do not re-add without checking the jar.
]

# (label, regex over usage.txt; any REMOVED member matching = failure)
NOT_REMOVED = [
    ("reader.country()", r"com\.maxmind\.geoip2\.DatabaseReader:.*\bCountryResponse country\("),
    ("reader.asn()", r"com\.maxmind\.geoip2\.DatabaseReader:.*\bAsnResponse asn\("),
    ("reader metadata", r"com\.maxmind\.db\.Reader:.*\bMetadata getMetadata\(|com\.maxmind\.db\.Reader:.*\bmetadata"),
    ("record constructors", r"com\.maxmind\.geoip2\.record\..*\.ctor"),
    ("model constructors", r"com\.maxmind\.geoip2\.model\..*\.ctor"),
]

# (label, byte string that must exist in classes*.dex)
DEX_STRINGS = [
    ("Metadata descriptor", b"Lcom/maxmind/db/Metadata;"),
    ("MaxMindDbConstructor annotation", b"com/maxmind/db/MaxMindDbConstructor"),
    ("CountryResponse descriptor", b"Lcom/maxmind/geoip2/model/CountryResponse;"),
    ("record Country descriptor", b"Lcom/maxmind/geoip2/record/Country;"),
]

failures = []


def check(label, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + label + (f"  [{detail}]" if detail else ""))
    if not ok:
        failures.append(label)


def main():
    if not MAPPING_DIR.is_dir():
        sys.exit(f"no mapping dir at {MAPPING_DIR} — build the release first")
    config = (MAPPING_DIR / "configuration.txt").read_text(errors="replace")
    mapping = (MAPPING_DIR / "mapping.txt").read_text(errors="replace")
    usage = (MAPPING_DIR / "usage.txt").read_text(errors="replace")

    # 1. rules merged into the R8 configuration
    for label, pattern in REQUIRED_RULES:
        check(f"rule: {label}", re.search(pattern, config) is not None)

    # 2. watchlist members not removed
    for label, pattern in NOT_REMOVED:
        check(f"kept: {label}", re.search(pattern, usage) is None)

    # 3. classes survive as own sections (no horizontal merge)
    sections = {}
    current = None
    for line in mapping.splitlines():
        m = re.match(r"^(\S+) -> (\S+):$", line)
        if m:
            current = m.group(2)
            continue
        # member lines reference the ORIGINAL class name
        m2 = re.match(r"^\s+.*?(\S+) -> \S+$", line)
        if m2 and current:
            sections.setdefault(current, []).append(line)
    for label, cls in UNMERGED_CLASSES:
        # the class must appear as a mapping target with its own header
        header = re.search(rf"^{re.escape(cls)} -> {re.escape(cls)}:", mapping, re.M)
        check(f"unmerged: {label}", header is not None)

    # find merged residue: watchlist class members inside foreign sections
    for label, cls in UNMERGED_CLASSES:
        pat = re.compile(rf"^\s+.*{re.escape(cls)}\.[A-Za-z<>$]+.* -> ", re.M)
        foreign = [
            ln.split(" -> ")[0].strip()
            for ln in mapping.splitlines()
            if cls + "." in ln and not ln.startswith((cls, "#"))
        ]
        # crude but effective: any member line mentioning the class inside a
        # section whose header is a different class
        check(f"no merge residue: {label}", not residue(mapping, cls))

    # 4. dex strings
    apk = APK_DIR / [p.name for p in APK_DIR.glob("*arm64-v8a.apk")][0]
    import zipfile

    with zipfile.ZipFile(apk) as z:
        dex = b"".join(z.read(n) for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
    for label, needle in DEX_STRINGS:
        check(f"dex: {label}", needle in dex)

    print()
    if failures:
        print(f"AUDIT FAILED — {len(failures)} problem(s): " + "; ".join(failures))
        sys.exit(1)
    print("AUDIT PASSED — R8 surface of the release build is intact.")


def residue(mapping: str, cls: str) -> bool:
    """True when members of `cls` appear inside a foreign section."""
    inside = None
    for line in mapping.splitlines():
        m = re.match(r"^(\S+) -> (\S+):$", line)
        if m:
            inside = m.group(2)
            continue
        if inside and inside != cls and f" {cls}." in line:
            return True
    return False


if __name__ == "__main__":
    main()
