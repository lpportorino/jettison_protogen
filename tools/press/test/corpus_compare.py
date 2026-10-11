"""Check generated corpus bytes against the worktree AND the Git index."""

import argparse
from pathlib import Path
import subprocess
import sys

CORPORA = ("tools/press/fixtures/desc_negative", "tools/press/fixtures/desc_positive")


class CannotRun(RuntimeError):
    """A missing input or failed Git operation, distinct from stale content."""


def git(root, *args):
    """Run Git without hiding errors or treating failed discovery as empty."""
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=False)
    if result.returncode:
        raise CannotRun(result.stderr.decode("utf-8", "replace").strip() or "Git failed")
    return result.stdout


def files(root, generated=False):
    """Read each complete flat corpus; refuse symlinks and unexpected file types."""
    found = {}
    for directory in CORPORA:
        base = root / directory
        if not base.is_dir() or base.is_symlink():
            raise CannotRun(f"missing or nonregular corpus directory: {base}")
        entries = list(base.iterdir())
        if generated and (
            not any(p.name == "manifest.json" for p in entries)
            or not any(p.name.endswith(".fig.json") for p in entries)
        ):
            raise CannotRun(f"vacuous generation: {directory}")
        for path in entries:
            if path.is_symlink() or not path.is_file() or path.suffix != ".json":
                raise CannotRun(f"nonregular corpus entry: {path}")
            found[f"{directory}/{path.name}"] = path.read_bytes()
    return found


def compare(root, generated):
    """Return stale-path diagnoses; operational errors raise CannotRun."""
    expected = files(generated, generated=True)
    actual = files(root)
    index = {}
    for record in git(root, "ls-files", "--stage", "-z", "--", *CORPORA).split(b"\0"):
        if not record:
            continue
        header, name = record.split(b"\t", 1)
        mode, object_id, stage = header.split()
        path = name.decode("utf-8")
        if stage != b"0" or mode != b"100644":
            raise CannotRun(f"unmerged or nonregular index entry: {path}")
        index[path] = git(root, "cat-file", "blob", object_id.decode("ascii"))
    if not index:
        raise CannotRun("Git discovered no indexed corpus files")
    problems = []
    for label, observed in (("worktree", actual), ("index", index)):
        for path in sorted(expected.keys() | observed.keys()):
            if path not in expected:
                problems.append(f"{label}: unexpected {path}")
            elif path not in observed:
                problems.append(f"{label}: missing {path}")
            elif expected[path] != observed[path]:
                problems.append(f"{label}: stale {path}")
    return problems


def main():
    """Exit 0 for fresh, 1 for stale, 2 when the check cannot run."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("generated", type=Path)
    args = parser.parse_args()
    try:
        problems = compare(args.root.resolve(), args.generated.resolve())
    except (CannotRun, OSError, ValueError) as exc:
        print(f"[press-corpus] CANNOT RUN: {exc}", file=sys.stderr)
        return 2
    if problems:
        print("[press-corpus] FAIL: " + "\n".join(problems), file=sys.stderr)
        return 1
    print("[press-corpus] fresh in worktree and index")
    return 0


if __name__ == "__main__":
    sys.exit(main())
