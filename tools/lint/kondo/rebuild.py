#!/usr/bin/env python3
"""Reconstruct and verify the patched source JAR without modifying Maven caches."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent


def sha256(data):
    """Return the lowercase content digest."""
    return hashlib.sha256(data).hexdigest()


def reconstruct(metadata, upstream):
    """Apply the exact patch and preserve every other upstream entry byte."""
    if sha256(upstream) != metadata["upstream_sha256"]:
        raise ValueError("upstream JAR checksum mismatch")
    with zipfile.ZipFile(io.BytesIO(upstream)) as source:
        names = source.namelist()
        if len(set(names)) != len(names):
            raise ValueError("duplicate upstream archive entries")
        entries = {name: source.read(name) for name in names}
    with tempfile.TemporaryDirectory(prefix="kondo-patch-") as directory:
        stage = Path(directory)
        for name in metadata["modified_entries"]:
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(entries[name])
        subprocess.run(
            ["patch", "--batch", "--fuzz=0", "-p1", "-i", str(ROOT / metadata["patch"])],
            cwd=stage,
            check=True,
        )
        for name in metadata["modified_entries"]:
            revised = (stage / name).read_bytes()
            if revised == entries[name]:
                raise ValueError(f"patch did not change expected entry: {name}")
            entries[name] = revised
    result = io.BytesIO()
    # Fixed ZIP metadata and compression settings make the source JAR repeatable
    # under the pinned toolchain. A toolchain change must recheck its bytes.
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED) as target:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o40755 if name.endswith("/") else 0o100644) << 16
            target.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return result.getvalue()


def main():
    """Write explicitly, or compare the committed artifact with reconstruction."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--upstream", type=Path)
    parser.add_argument("--artifact-only", action="store_true")
    args = parser.parse_args()
    metadata = json.loads((ROOT / "artifact.json").read_text())
    if sha256((ROOT / metadata["patch"]).read_bytes()) != metadata["patch_sha256"]:
        raise ValueError("patch checksum mismatch")
    target = ROOT / metadata["artifact"]
    if args.artifact_only:
        if sha256(target.read_bytes()) != metadata["artifact_sha256"]:
            raise ValueError("patched artifact checksum mismatch")
        print("Patched clj-kondo artifact and patch checksums verified")
        return
    if args.upstream:
        upstream = args.upstream.read_bytes()
    else:
        # The ordinary Maven cache path follows from the upstream artifact's own
        # file name, so the version has exactly one home: artifact.json's URL.
        upstream_name = metadata["upstream_url"].rsplit("/", 1)[1]
        if not (upstream_name.startswith("clj-kondo-") and upstream_name.endswith(".jar")):
            raise ValueError("upstream_url does not name a clj-kondo-<version>.jar")
        upstream_version = upstream_name[len("clj-kondo-") : -len(".jar")]
        cache = (
            Path.home() / f".m2/repository/clj-kondo/clj-kondo/{upstream_version}/{upstream_name}"
        )
        if cache.is_file():
            upstream = cache.read_bytes()
        else:
            with urllib.request.urlopen(metadata["upstream_url"], timeout=60) as response:
                upstream = response.read()
    result = reconstruct(metadata, upstream)
    if sha256(result) != metadata["artifact_sha256"]:
        raise ValueError("reconstruction checksum differs from declared artifact")
    if args.write:
        target.write_bytes(result)
    elif target.read_bytes() != result:
        raise ValueError("patched artifact differs from exact reconstruction")
    print(json.dumps({"status": "written" if args.write else "verified", "sha256": sha256(result)}))


if __name__ == "__main__":
    main()
