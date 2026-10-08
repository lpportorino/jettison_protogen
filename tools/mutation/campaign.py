#!/usr/bin/env python3
"""Run a declared mutation campaign over committed HEAD and keep its evidence.

usage: campaign.py FAULTS_JSON OUT_DIR [--only ID]... [--suite NAME]...

FAULTS_JSON declares the suites and the faults:

  {"scope": "...",
   "suites": {"<name>": {"command": "<bash>", "timeout": <seconds>,
                          "prepare": "<bash, optional>"}},
   "faults": [{"id", "source", "anchor", "replacement", "suite",
               "kill": "<regex>", "control": "<regex>", "only": "<regex, optional>"}],
   "excluded": [{"what", "why"}]}

Every fault runs in its own `git clone --local` of HEAD under OUT_DIR, so the
checkout is never edited. The anchor must occur EXACTLY ONCE in the source; after
the edit the replacement must be present and, unless it contains the anchor, the
anchor gone — a mutation that did not land is invalid, never a kill. The suite
then runs (bash -c, in the clone, $ROOT naming this checkout).

  killed    the suite exited non-zero, NOT by timeout, and its log matches BOTH
            `kill` (the named assertion that must fail) and `control`, and —
            when the fault declares `only` — every failing test the log names
            (a `FAIL in (…)` / `ERROR in (…)` line) matches `only`;
  survived  the suite exited 0;
  invalid   anything else — a timeout, a crash, a red that names neither, or a
            red outside `only`.

What `control` proves depends on the suite, and each faults file says which:
a neighbouring case printed as passing (the shell suites), a failure whose kind
is FAIL rather than ERROR in a harness that stops at its first failure (the
browser suite), or — with `only` making the attribution exact — a positive
test and assertion count (the Clojure suites).

Each suite also runs unmutated before and after the faults (baselines), and the
checkout's HEAD, status and the sources' hashes are fingerprinted before and
after. OUT_DIR/report.json holds every fault's exact edit, command, exit code,
duration, log path and log digest, the matched lines and the outcome.

Exit 0: every baseline passed, every fault killed, the checkout unchanged.
2: a baseline failed, a fault was invalid, or the checkout changed — evidence
that cannot be trusted outranks a survivor. 1: otherwise, a fault survived.
3: CANNOT RUN — usage, an unreadable faults file, not a git checkout, a dirty
tree (only a committed tree is judged), or git failing to clone.
A timed-out suite's whole process group is killed; a container it started is
not, and the docker suites' timeouts are sized so that does not arise.
"""

import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path


def cannot_run(message):
    print(f"[campaign] CANNOT RUN — {message}", file=sys.stderr)
    sys.exit(3)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def git(root, *args):
    return subprocess.run(
        ["git", "-C", str(root), *args], check=True, capture_output=True, text=True
    ).stdout


def checkout_state(root, sources):
    stamp = Path(root) / "tools/gate-graph/browser/node_modules/.installed-from"
    return {
        "head": git(root, "rev-parse", "HEAD").strip(),
        "status": git(root, "status", "--porcelain", "--ignored=no"),
        "sources": {s: sha256(Path(root) / s) for s in sorted(sources)},
        # Ignored, but hardlinked into every clone by the browser suites' prepare.
        "node_modules_installed_from": stamp.read_text().strip() if stamp.exists() else None,
    }


def clone(root, dest, prepare):
    try:
        subprocess.run(
            ["git", "clone", "-q", "--local", str(root), str(dest)], check=True, capture_output=True
        )
    except subprocess.CalledProcessError as error:
        cannot_run(f"git clone into {dest} failed: {error.stderr.decode(errors='replace')[:300]}")
    if prepare:
        result = subprocess.run(
            ["bash", "-c", prepare],
            cwd=dest,
            env={**os.environ, "ROOT": str(root)},
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            cannot_run(f"prepare failed in {dest}: {(result.stdout + result.stderr)[:300]}")


def run_suite(root, cwd, suite, log_path):
    started = time.monotonic()
    # Its own session, so a timeout reaps the whole process group, not just bash.
    proc = subprocess.Popen(
        ["bash", "-c", suite["command"]],
        cwd=cwd,
        env={**os.environ, "ROOT": str(root)},
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        log, _ = proc.communicate(timeout=suite["timeout"])
        exit_code, timed_out = proc.returncode, False
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        log, _ = proc.communicate()
        exit_code, timed_out = None, True
    Path(log_path).write_text(log)
    return {
        "command": suite["command"],
        "exit": exit_code,
        "timed_out": timed_out,
        "seconds": round(time.monotonic() - started, 1),
        "log": str(log_path),
        "log_sha256": sha256(log_path),
    }, log


def mutate(path, anchor, replacement):
    text = Path(path).read_text()
    if anchor == replacement:
        return "the replacement equals the anchor: an unchanged copy is not a tested fault"
    count = text.count(anchor)
    if count != 1:
        return f"anchor occurs {count} times, want exactly 1"
    mutated = text.replace(anchor, replacement)
    if replacement not in mutated or (anchor not in replacement and anchor in mutated):
        return "the edit did not land"
    if mutated == text:
        return "the edit changed nothing"
    Path(path).write_text(mutated)
    return None


def counts_in(log):
    """The executed counts a suite printed, so a green can be told from an empty run."""
    found = {}
    for pattern, key in (
        (r"(\d+) passed, (\d+) failed", "shell"),
        (r"Ran (\d+) tests containing (\d+) assertions", "clojure"),
        (r"passed: (\d+)\s+failed: (\d+)", "shell"),
        (r"ALL GREEN — (\d+) assertion", "shell"),
    ):
        match = re.search(pattern, log)
        if match:
            found[key] = [int(g) for g in match.groups()]
    return found


def tool_fingerprints(root):
    def out(*cmd):
        try:
            return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            return None

    run_sh = root / "tools/gate-graph/browser/run.sh"
    pin = (
        re.search(r'^PLAYWRIGHT_IMAGE="(.*)"$', run_sh.read_text(), re.MULTILINE)
        if run_sh.exists()
        else None
    )
    image = pin.group(1) if pin else None
    stamp = root / "tools/gate-graph/browser/node_modules/.installed-from"
    return {
        "python": sys.version.split()[0],
        "git": out("git", "--version"),
        "docker": out("docker", "--version"),
        "base_image": out(
            "docker", "image", "inspect", "-f", "{{.Id}}", "jettison-proto-generator-base:latest"
        ),
        "playwright_image": image,
        "node_modules_installed_from": stamp.read_text().strip() if stamp.exists() else None,
    }


def first_match(pattern, log):
    match = re.search(pattern, log, re.MULTILINE)
    return match.group(0)[:300] if match else None


def main(argv):
    parser = argparse.ArgumentParser(prog="campaign.py")
    parser.add_argument("faults")
    parser.add_argument("out")
    parser.add_argument("--only", action="append", default=[])
    parser.add_argument("--suite", action="append", default=[])
    try:
        opts = parser.parse_args(argv)
    except SystemExit as exit_:
        if exit_.code == 0:
            raise
        cannot_run("usage: campaign.py FAULTS_JSON OUT_DIR [--only ID]... [--suite NAME]...")
    try:
        spec = json.loads(Path(opts.faults).read_text())
    except (OSError, ValueError) as error:
        cannot_run(f"{opts.faults} is not a readable campaign: {error}")
    try:
        root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").strip())
        dirty = git(root, "status", "--porcelain")
    except subprocess.CalledProcessError:
        cannot_run("not inside a git checkout")
    if dirty:
        cannot_run("the tree has uncommitted changes; only a committed tree is judged")
    out = Path(opts.out).resolve()
    if out.exists():
        cannot_run(f"{out} exists; a campaign never overwrites evidence")
    faults = [
        f
        for f in spec["faults"]
        if (not opts.only or f["id"] in opts.only) and (not opts.suite or f["suite"] in opts.suite)
    ]
    if not faults:
        cannot_run("no fault selected; a campaign over nothing proves nothing")
    for key in ("suites", "faults"):
        if key not in spec:
            cannot_run(f"{opts.faults} has no {key!r}")
    for f in faults:
        if f["suite"] not in spec["suites"]:
            cannot_run(f"fault {f['id']} names unknown suite {f['suite']}")
        if not (root / f["source"]).is_file():
            cannot_run(f"fault {f['id']} names missing source {f['source']}")
    out.mkdir(parents=True)
    faults_sha256 = sha256(opts.faults)
    tools = tool_fingerprints(root)
    sources = {f["source"] for f in faults}
    before = checkout_state(root, sources)
    suites = sorted({f["suite"] for f in faults})

    def baselines(tag):
        results = {}
        for name in suites:
            suite = spec["suites"][name]
            work = out / f"baseline-{tag}-{name}"
            clone(root, work, suite.get("prepare"))
            run, log = run_suite(root, work, suite, out / f"baseline-{tag}-{name}.log")
            run["passed"] = run["exit"] == 0
            run["executed"] = counts_in(log)
            results[name] = run
            print(f"[campaign] baseline {tag} {name}: {'pass' if run['passed'] else 'FAIL'}")
        return results

    initial = baselines("initial")
    results = []
    for f in faults:
        suite = spec["suites"][f["suite"]]
        work = out / f["id"]
        clone(root, work, suite.get("prepare"))
        problem = mutate(work / f["source"], f["anchor"], f["replacement"])
        head_blob = subprocess.run(
            ["git", "-C", str(root), "show", f"HEAD:{f['source']}"], capture_output=True, check=True
        ).stdout
        record = {"fault": f, "source_sha256_head": hashlib.sha256(head_blob).hexdigest()}
        if problem:
            record.update(outcome="invalid", reason=problem)
        else:
            record["mutant_sha256"] = sha256(work / f["source"])
            run, log = run_suite(root, work, suite, out / f"{f['id']}.log")
            killed_by, control = first_match(f["kill"], log), first_match(f["control"], log)
            failing = re.findall(r"^(?:FAIL|ERROR) in \((\S+)\)", log, re.MULTILINE)
            outside = [t for t in failing if "only" in f and not re.fullmatch(f["only"], t)]
            record.update(
                run=run,
                killed_by=killed_by,
                control=control,
                failing_tests=failing,
                executed=counts_in(log),
            )
            if run["timed_out"]:
                record.update(outcome="invalid", reason="timed out")
            elif run["exit"] == 0:
                record.update(outcome="survived", reason="the suite passed with the fault in place")
            elif outside:
                record.update(outcome="invalid", reason=f"a red outside `only`: {outside[:5]}")
            elif killed_by and control:
                record.update(outcome="killed", reason="named assertion failed, control passed")
            else:
                record.update(
                    outcome="invalid",
                    reason="red, but the log names "
                    + ("no control" if killed_by else "not the expected failing assertion"),
                )
        print(f"[campaign] {f['id']}: {record['outcome']} — {record['reason']}")
        results.append(record)
    final = baselines("final")
    after = checkout_state(root, sources)
    counts = {o: sum(r["outcome"] == o for r in results) for o in ("killed", "survived", "invalid")}
    passed = (
        all(b["passed"] for b in initial.values())
        and all(b["passed"] for b in final.values())
        and before == after
        and counts["killed"] == len(results)
    )
    report = {
        "scope": spec.get("scope"),
        "faults_file": str(opts.faults),
        "faults_file_sha256": faults_sha256,
        "argv": argv,
        "selection": {"only": opts.only, "suite": opts.suite, "faults": len(faults)},
        "tools": tools,
        "checkout_before": before,
        "checkout_after": after,
        "unchanged": before == after,
        "baselines": {"initial": initial, "final": final},
        "excluded": spec.get("excluded", []),
        "results": results,
        "counts": counts,
        "passed": passed,
    }
    (out / "report.json").write_text(json.dumps(report, indent=1) + "\n")
    print(f"[campaign] {counts} unchanged={before == after} -> {out / 'report.json'}")
    if passed:
        return 0
    untrusted = (
        counts["invalid"]
        or before != after
        or not all(b["passed"] for b in list(initial.values()) + list(final.values()))
    )
    return 2 if untrusted else 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except (KeyError, TypeError, ValueError, OSError, subprocess.CalledProcessError) as error:
        # An instrument that crashed has not judged anything: never the survivor's code.
        cannot_run(f"the campaign itself failed: {type(error).__name__}: {error}")
