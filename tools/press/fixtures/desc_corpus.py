"""The shared description corpus check: the JVM half writes one
canonical-JSON description per refusal class into fixtures/desc_negative/ (diagram descriptions and
chart ENVELOPES) and accepted ones into fixtures/desc_positive/, each directory with a manifest.json
{cases: [{case, file, rule?}]}; BOTH halves must refuse every negative file and accept every
positive one. This half asserts "refused with exit 6, never 7"; the JVM test asserts the named rule.

usage: desc_corpus.py <fixtures-dir>   (inside the image; `press selftest` runs it LAST)
check(root, env) -> [(name, ok, detail)].

Each file is read in its own process exactly as press reads it, routed by its `kind`: a "chart"
envelope goes to chart/desc.py (`press chart`'s reader), anything else to fig/desc.py (`press fig`'s). A
negative file must exit 6 with the rule named -- never 0, never a crash; a positive file must load.
The directory listing and the manifest must name the same files, both ways. An empty or missing
directory, or a missing manifest, is a FAILURE -- a corpus that is not there proves nothing."""

import json
import os
import pathlib
import subprocess
import sys

READ = (
    "import json, sys\nfrom fig.desc import DescError\n"
    "try:\n"
    '    tree = json.loads(open(sys.argv[1], "rb").read().decode("utf-8"))\n'
    "except Exception:\n"
    "    tree = None\n"
    'if isinstance(tree, dict) and tree.get("kind") == "chart":\n'
    "    from chart.desc import load\n"
    "else:\n"
    "    from fig.desc import load\n"
    "try:\n"
    "    load(sys.argv[1])\n"
    "except DescError as e:\n"
    "    print(e)\n"
    "    sys.exit(6)\n"
)


def _kind(path):
    try:
        tree = json.loads(path.read_bytes().decode("utf-8"))
    except (OSError, ValueError):
        return None
    return tree.get("kind") if isinstance(tree, dict) else None


def check(root, env):
    out = []
    for sub, want in (("desc_negative", "refused"), ("desc_positive", "accepted")):
        d = root / sub
        files = sorted(p.name for p in d.glob("*.fig.json")) if d.is_dir() else []
        out.append(
            (f"{sub}/ exists and holds descriptions", bool(files), f"{len(files)} files under {d}")
        )
        if not files:
            continue
        try:
            listed = sorted(
                c["file"] for c in json.loads((d / "manifest.json").read_text())["cases"]
            )
        except (OSError, ValueError, KeyError, TypeError) as e:
            out.append((f"{sub}/manifest.json lists the cases", False, f"{type(e).__name__}: {e}"))
            continue
        only_dir, only_man = sorted(set(files) - set(listed)), sorted(set(listed) - set(files))
        out.append(
            (
                f"{sub}/ and its manifest name the same files, both ways",
                not only_dir and not only_man and len(listed) == len(set(listed)),
                f"only in the directory {only_dir}; only in the manifest {only_man}",
            )
        )
        bad = []
        for name in files:
            r = subprocess.run(
                [sys.executable, "-c", READ, str(d / name)], capture_output=True, text=True, env=env
            )
            ok = (
                (r.returncode == 6 and bool(r.stdout.strip()))
                if want == "refused"
                else r.returncode == 0
            )
            if not ok:
                bad.append(f"{name}: exit {r.returncode} {(r.stdout + r.stderr).strip()[-120:]}")
        out.append(
            (
                f"every {sub} file is {want} by press's reader for its kind (never a crash)",
                not bad,
                f"{len(files)} files ({sum(1 for f in files if _kind(d / f) == 'chart')} chart envelopes); {bad[:3]}",
            )
        )
    return out


if __name__ == "__main__":
    rows = check(pathlib.Path(sys.argv[1]), dict(os.environ, PYTHONPATH="/work/press"))
    for name, ok, detail in rows:
        print(f"{'PASS' if ok else 'FAIL'}  corpus: {name}  {detail}")
    bad = [n for n, ok, _ in rows if not ok]
    print(f"desc corpus: {len(rows) - len(bad)} passed, {len(bad)} failed")
    sys.exit(1 if bad else 0)
