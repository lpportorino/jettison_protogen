#!/usr/bin/env python3
"""Manual, isolated mutations of renderer-gen's generator contracts (the schema campaign)."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
RGEN = ROOT / "tools/renderer-gen"
SPEC = importlib.util.spec_from_file_location(
    "renderer_gen_schema_native_api", ROOT / "renderer/tools/lvgl-api-selftest.py"
)
API = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = API
SPEC.loader.exec_module(API)

PRETTY = "tools/renderer-gen/src/lvgl_codegen/pretty.clj"
PALETTE = "tools/renderer-gen/src/lvgl_codegen/palette_ladder.clj"
COVERAGE = "tools/renderer-gen/test/lvgl_codegen/spec_coverage.clj"
OWNER = [
    PRETTY,
    PALETTE,
    COVERAGE,
    "tools/renderer-gen/test/lvgl_codegen/pretty_test.clj",
    "tools/renderer-gen/test/lvgl_codegen/palette_ladder_test.clj",
    "tools/renderer-gen/test/lvgl_codegen/instrument.clj",
    "tools/renderer-gen/tests.edn",
    "tools/renderer-gen/deps.edn",
]
SEED = "20261003"
FOCUS = [
    "--focus",
    "lvgl-codegen.pretty-test",
    "--focus",
    "lvgl-codegen.palette-ladder-test/scan-proposal-root-contract",
]
CONTROL = ["--focus", "lvgl-codegen.pretty-test/scalar-and-diff-control"]
FOCUS_POPULATION = {"tests": 7, "assertions": 44}
CONTROL_POPULATION = {"tests": 1, "assertions": 13}
PROJECT_ROOTS = ("src", "test", "resources", "target/proto-classes")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
FOOTER = re.compile(r"^(\d+) tests, (\d+) assertions, (\d+) failures\.$", re.M)
FAIL_LINE = re.compile(r"^FAIL in (\S+) \(([^\n]+)\)$", re.M)
ERROR_MARK = re.compile(
    r"(^ERROR in |Execution error|Syntax error|CompilerException|Timed out)", re.M
)
CATALOG_SIZE = 11
# 4 process + 6 report + 3 real-kaocha + 6 failure-report canaries.
EXPECTED_CANARIES = 19


def selection():
    """Freeze the selected fault catalog, each with its declared failing test."""
    raw = [
        (
            "default-map-comparator",
            PRETTY,
            "(sorted-map-by #(compare (key-order %1) (key-order %2)))",
            "(sorted-map)",
            "lvgl-codegen.pretty-test/mixed-key-printing-is-lossless-and-ordered",
        ),
        (
            "printed-key-comparator",
            PRETTY,
            "(sorted-map-by #(compare (key-order %1) (key-order %2)))",
            "(sorted-map-by #(compare (pr-str %1) (pr-str %2)))",
            "lvgl-codegen.pretty-test/rank-fields-preserve-distinct-keys",
        ),
        (
            "reverse-key-type-order",
            PRETTY,
            '[0 (if (some? (namespace k)) 1 0) (or (namespace k) "") (name k)]\n    [1 0 "" k]',
            '[1 (if (some? (namespace k)) 1 0) (or (namespace k) "") (name k)]\n    [0 0 "" k]',
            "lvgl-codegen.pretty-test/mixed-key-printing-is-lossless-and-ordered",
        ),
        (
            "namespace-rank-omitted",
            PRETTY,
            '(or (namespace k) "")',
            '""',
            "lvgl-codegen.pretty-test/rank-fields-preserve-distinct-keys",
        ),
        (
            "name-rank-omitted",
            PRETTY,
            "(name k)",
            '""',
            "lvgl-codegen.pretty-test/mixed-key-printing-is-lossless-and-ordered",
        ),
        (
            "namespace-presence-omitted",
            PRETTY,
            "(if (some? (namespace k)) 1 0)",
            "0",
            "lvgl-codegen.pretty-test/rank-fields-preserve-distinct-keys",
        ),
        (
            "recursive-value-sort-omitted",
            PRETTY,
            "[k (sort-map-keys v)]",
            "[k v]",
            "lvgl-codegen.pretty-test/mixed-key-printing-is-lossless-and-ordered",
        ),
        (
            "printer-input-widened",
            PRETTY,
            "(m/=> pp-str [:=> [:cat printable-value] string?])",
            "(m/=> pp-str [:=> [:cat :any] string?])",
            "lvgl-codegen.pretty-test/unsupported-domain-is-rejected-by-instrumentation",
        ),
        (
            "sorter-input-widened",
            PRETTY,
            "(m/=> sort-map-keys [:=> [:cat printable-value] printable-value])",
            "(m/=> sort-map-keys [:=> [:cat :any] printable-value])",
            "lvgl-codegen.pretty-test/unsupported-domain-is-rejected-by-instrumentation",
        ),
        (
            "proposal-root-widened",
            PALETTE,
            "(m/=> scan-ambiguous-keys\n"
            "      [:=> [:cat [:map-of :keyword :any]] [:sequential [:map-of :keyword :any]]])",
            "(m/=> scan-ambiguous-keys\n"
            "      [:=> [:cat :any] [:sequential [:map-of :keyword :any]]])",
            "lvgl-codegen.palette-ladder-test/scan-proposal-root-contract",
        ),
        (
            "pretty-coverage-not-enrolled",
            COVERAGE,
            '    "lvgl-codegen.pretty"\n',
            "",
            "lvgl-codegen.pretty-test/pretty-contract-coverage-is-enrolled",
        ),
    ]
    return [
        {"name": name, "source": source, "old": old, "new": new, "expected_failure": failing}
        for name, source, old, new, failing in raw
    ]


def classify(code, output, population, expected_failure=None):
    """Accept only the frozen kaocha population and, for a kill, the declared FAIL.

    A pass needs exit 0, exactly one footer with the frozen test and assertion
    counts and zero failures. A kill needs a non-zero exit, that same population,
    a footer failure count equal to the FAIL lines printed, and the declared
    failing test among them. Any error marker, a second footer, a moved count, an
    errors or pending column (kaocha then prints a footer this pattern refuses),
    an empty or foreign selection, a crash or a timeout is no verdict at all.
    """
    cleaned = ANSI.sub("", output)
    footers = FOOTER.findall(cleaned)
    fails = FAIL_LINE.findall(cleaned)
    error = ERROR_MARK.search(cleaned)
    result = {
        "status": "invalid",
        "exit": code,
        "footers": footers,
        "failures": fails,
        "error_marker": error.group(0) if error else None,
        "log_sha256": hashlib.sha256(output.encode()).hexdigest(),
    }
    if code is None:
        result["status"] = "timeout"
        return result
    if error or len(footers) != 1:
        return result
    tests, assertions, failures = map(int, footers[0])
    if tests != population["tests"] or assertions != population["assertions"]:
        return result
    if failures != len(fails):
        return result
    if code == 0 and not fails:
        result["status"] = "passed"
        return result
    if expected_failure is None or code == 0 or not fails:
        return result
    if any(name == expected_failure for name, _ in fails):
        result["status"] = "failed"
    return result


def mutant_status(verdict, control, bound, drift):
    """Name a fault's outcome; both verdicts need the same proof the fault was tested.

    A failed or passed focused run counts only when the binding proof shows the
    worker classpath resolved the mutated file, the control passed and the
    worker did not drift. Without that proof a green run is not a survivor: the
    mutation may never have reached the code, and reporting it as one would
    point a reader at a test to strengthen rather than at the run.
    """
    sound = bound and not drift and control["status"] == "passed"
    if verdict["status"] == "failed" and sound:
        return "killed"
    if verdict["status"] == "passed" and sound:
        return "survived"
    if verdict["status"] == "timeout":
        return "timeout"
    return "invalid"


def digest_tree(paths):
    out = {}
    for path in paths:
        path = Path(path)
        if path.is_file():
            out[str(path)] = API.digest(path)
        elif path.is_dir():
            for item in sorted(path.rglob("*")):
                if item.is_file():
                    out[str(item)] = API.digest(item)
        else:
            out[str(path)] = None
    return out


def relative(tree):
    return {str(Path(key).relative_to(ROOT)): value for key, value in tree.items()}


def process_canaries(out, failing):
    """Real processes that must earn neither a pass nor a kill."""
    probes = [
        ("empty-process", "pass", "invalid", 5),
        (
            "compile-shaped-process",
            "print('Syntax error compiling at (pretty.clj:1:1).');"
            "print('7 tests, 44 assertions, 1 failures.');"
            "print('FAIL in " + failing + " (pretty_test.clj:1)');"
            "raise SystemExit(1)",
            "invalid",
            5,
        ),
        ("crash-process", "import os; os.abort()", "invalid", 5),
        ("deadline-process", "import time; time.sleep(10)", "timeout", 0.1),
    ]
    rows = []
    for name, code, expected, timeout in probes:
        command = [sys.executable, "-c", code]
        start = time.monotonic()
        rc, output = API.execute(command, out, out / (name + ".log"), timeout)
        as_pass = classify(rc, output, FOCUS_POPULATION)
        as_kill = classify(rc, output, FOCUS_POPULATION, failing)
        rows.append(
            {
                "name": name,
                "command": command,
                "expected": expected,
                "as_pass": as_pass,
                "as_kill": as_kill,
                "seconds": round(time.monotonic() - start, 3),
                "passed": as_pass["status"] == expected and as_kill["status"] == expected,
            }
        )
    return rows


def report_canaries(out, positive):
    """Corrupt the real passing report; every variant must be refused."""
    footer = next(line for line in positive.splitlines() if FOOTER.match(line))
    tests, assertions, _ = FOOTER.match(footer).groups()
    variants = [
        ("zero-population", 0, positive.replace(footer, "0 tests, 0 assertions, 0 failures.")),
        (
            "assertion-drift",
            0,
            positive.replace(
                footer, f"{tests} tests, {int(assertions) - 1} assertions, 0 failures."
            ),
        ),
        ("duplicate-footer", 0, positive + footer + "\n"),
        (
            "errors-column",
            0,
            positive.replace(
                footer, f"{tests} tests, {assertions} assertions, 1 errors, 0 failures."
            ),
        ),
        ("error-marker", 0, positive + "ERROR in lvgl-codegen.pretty-test (pretty_test.clj:1)\n"),
        ("error-exit-with-green-report", 1, positive),
    ]
    rows = []
    for name, code, output in variants:
        verdict = classify(code, output, FOCUS_POPULATION)
        (out / (name + ".log")).write_text(output)
        rows.append({"name": name, "observed": verdict, "passed": verdict["status"] == "invalid"})
    return rows


def failure_canaries(out, observed, expected):
    """Corrupt the first real kill report; no variant may keep the kill."""
    footer = next(line for line in observed.splitlines() if FOOTER.match(line))
    tests, assertions, failures = FOOTER.match(footer).groups()
    other = "lvgl-codegen.pretty-test/scalar-and-diff-control"
    variants = [
        ("failure-exit-zero", 0, observed),
        ("failure-wrong-test", 1, observed.replace("FAIL in " + expected, "FAIL in " + other)),
        (
            "failure-count-mismatch",
            1,
            observed.replace(
                footer, f"{tests} tests, {assertions} assertions, {int(failures) + 1} failures."
            ),
        ),
        (
            "failure-assertion-drift",
            1,
            observed.replace(
                footer, f"{tests} tests, {int(assertions) + 1} assertions, {failures} failures."
            ),
        ),
        ("failure-load-error", 1, observed + "\nSyntax error compiling at (pretty.clj:1:1).\n"),
        ("failure-extra-footer", 1, observed + footer + "\n"),
    ]
    rows = []
    for name, code, output in variants:
        verdict = classify(code, output, FOCUS_POPULATION, expected)
        (out / (name + ".log")).write_text(output)
        rows.append({"name": name, "observed": verdict, "passed": verdict["status"] != "failed"})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out", required=True, type=Path, help="new evidence directory; never reused"
    )
    args = parser.parse_args()
    if not (Path("/.dockerenv").exists() or Path("/run/.containerenv").exists()):
        parser.error("run inside the official development/review container")
    out = args.out.resolve()
    if out.exists():
        parser.error("--out must not exist")
    if out.is_relative_to(ROOT) and out.relative_to(ROOT).parts[0] != ".fork-scratch":
        parser.error("evidence inside the checkout must be under .fork-scratch")
    out.mkdir(parents=True)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    started = time.monotonic()
    report = {
        "scope": (
            "renderer-gen pretty printer comparator and value-domain boundaries, "
            "the palette scanner's instrumented root, and pretty's spec-coverage enrolment"
        ),
        "seed": SEED,
        "focus": FOCUS,
        "control": CONTROL,
        "populations": {"focus": FOCUS_POPULATION, "control": CONTROL_POPULATION},
        "selection": selection(),
        "baselines": [],
        "canaries": [],
        "mutants": [],
        "passed": False,
    }
    (out / "selected.json").write_text(json.dumps(report["selection"], indent=2) + "\n")

    def save():
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")

    def run(name, cwd, command, timeout=600):
        (out / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        start = time.monotonic()
        rc, output = API.execute(command, cwd, out / (name + ".log"), timeout)
        return rc, output, round(time.monotonic() - start, 3)

    def identities():
        rows = {}
        for name, command in [
            ("java", ["java", "-version"]),
            ("clojure", ["clojure", "--version"]),
            ("python", [sys.executable, "--version"]),
        ]:
            rc, output, _ = run("identity-" + name, ROOT, command, 120)
            if rc != 0:
                raise RuntimeError(f"missing tool: {command[0]}")
            binary = shutil.which(command[0]) or command[0]
            rows[name] = {"version": output, "binary_sha256": API.digest(Path(binary))}
        return rows

    try:
        for fault in report["selection"]:
            if (ROOT / fault["source"]).read_text().count(fault["old"]) != 1:
                raise ValueError(f"{fault['name']}: expected one exact source anchor")
        report["tools"] = identities()
        rc, cp_raw, _ = run("classpath", RGEN, ["clojure", "-Spath", "-M:test"], 300)
        if rc != 0:
            raise RuntimeError("classpath resolution through the :test alias failed")
        entries = [line for line in cp_raw.strip().splitlines() if line][-1].split(":")
        missing = [root for root in PROJECT_ROOTS if root not in entries]
        if missing:
            raise RuntimeError(f"the :test classpath lacks project root(s) {missing}")
        jars = [entry for entry in entries if entry not in PROJECT_ROOTS]
        shared = [str(RGEN / "resources"), str(RGEN / "target/proto-classes")]
        if not (RGEN / "target/proto-classes").is_dir():
            raise RuntimeError(
                "target/proto-classes is absent; run make -f renderer.mk proto-classes"
            )
    except Exception as error:
        report["error"] = f"setup {type(error).__name__}: {error}"
        save()
        print(report["error"], flush=True)
        return 1

    def inputs():
        return {
            "owner_sources": {path: API.digest(ROOT / path) for path in OWNER},
            "renderer_gen_src_test": relative(digest_tree([RGEN / "src", RGEN / "test"])),
            "shared_roots": relative(digest_tree([Path(path) for path in shared])),
            "jars": digest_tree(jars),
            "drivers": {
                "schema": API.digest(Path(__file__)),
                "supervisor": API.digest(ROOT / "renderer/tools/lvgl-api-selftest.py"),
            },
        }

    def classpath_for(project):
        return ":".join([str(project / "test"), str(project / "src")] + shared + jars)

    def kaocha(name, project, selection_args):
        command = [
            "java",
            "-cp",
            classpath_for(project),
            "clojure.main",
            "-m",
            "kaocha.runner",
            "--no-color",
            "--seed",
            SEED,
        ] + selection_args
        rc, output, seconds = run(name, project, command)
        return command, rc, output, seconds

    def baseline(stage):
        rows = []
        alias = ["clojure", "-M:test", "--no-color", "--seed", SEED] + FOCUS
        rc, output, seconds = run(stage + "-alias", RGEN, alias)
        rows.append(
            {
                "name": stage + "-alias",
                "command": alias,
                "seconds": seconds,
                **classify(rc, output, FOCUS_POPULATION),
            }
        )
        command, rc, positive, seconds = kaocha(stage + "-classpath", RGEN, FOCUS)
        rows.append(
            {
                "name": stage + "-classpath",
                "command": command,
                "seconds": seconds,
                **classify(rc, positive, FOCUS_POPULATION),
            }
        )
        command, rc, output, seconds = kaocha(stage + "-control", RGEN, CONTROL)
        rows.append(
            {
                "name": stage + "-control",
                "command": command,
                "seconds": seconds,
                **classify(rc, output, CONTROL_POPULATION),
            }
        )
        report["baselines"].extend(rows)
        save()
        same = rows[0]["footers"] == rows[1]["footers"]
        if not same or not all(row["status"] == "passed" for row in rows):
            raise RuntimeError(f"{stage}: baseline must pass identically on alias and classpath")
        return positive

    def make_worker(name):
        worker = out / "workers" / name
        worker.mkdir(parents=True)
        shutil.copytree(RGEN / "src", worker / "src", symlinks=True)
        shutil.copytree(RGEN / "test", worker / "test", symlinks=True)
        shutil.copy2(RGEN / "tests.edn", worker / "tests.edn")
        return worker

    def worker_files(worker):
        return {
            str(path.relative_to(worker)): API.digest(path)
            for path in sorted(worker.rglob("*"))
            if path.is_file()
        }

    def binding(name, worker, rel, target):
        """Prove the mutated file is the one the worker classpath resolves."""
        expr = (
            "(require '[clojure.java.io :as io]) (let [r (io/resource "
            + json.dumps(rel)
            + ')] (println "RESOURCE_ORIGIN=" (str r)) (println "RESOURCE_SHA256=" '
            "(.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "
            '"SHA-256") (with-open [s (io/input-stream r)] (.readAllBytes s))))))'
        )
        command = ["java", "-cp", classpath_for(worker), "clojure.main", "-e", expr]
        rc, output, seconds = run(name + "-binding", worker, command, 180)
        return {
            "valid": rc == 0
            and ("RESOURCE_ORIGIN= file:" + str(target)) in output
            and ("RESOURCE_SHA256= " + API.digest(target)) in output,
            "exit": rc,
            "seconds": seconds,
        }

    def real_canary(name, mutate, selection_args, population, failing):
        worker = make_worker(name)
        target = worker / "src/lvgl_codegen/pretty.clj"
        if mutate:
            original = target.read_text()
            target.write_text(mutate(original))
            if target.read_text() == original:
                raise RuntimeError(f"{name}: canary mutation did not change the worker")
        command, rc, output, seconds = kaocha(name, worker, selection_args)
        as_pass = classify(rc, output, population)
        as_kill = classify(rc, output, population, failing)
        return {
            "name": name,
            "command": command,
            "seconds": seconds,
            "as_pass": as_pass,
            "as_kill": as_kill,
            "passed": as_pass["status"] != "passed" and as_kill["status"] != "failed",
        }

    report["input_before"] = inputs()
    save()
    first = report["selection"][0]["expected_failure"]
    try:
        positive = baseline("before")
        report["canaries"].extend(
            {"kind": "process", **row} for row in process_canaries(out, first)
        )
        report["canaries"].extend(
            {"kind": "report", **row} for row in report_canaries(out, positive)
        )
        report["canaries"].append(
            {
                "kind": "kaocha",
                **real_canary(
                    "canary-compile-error",
                    lambda text: text + "\n(defn broken [\n",
                    FOCUS,
                    FOCUS_POPULATION,
                    first,
                ),
            }
        )
        report["canaries"].append(
            {
                "kind": "kaocha",
                **real_canary(
                    "canary-load-error",
                    lambda text: text + '\n(throw (ex-info "SCHEMA_CAMPAIGN_LOAD_CANARY" {}))\n',
                    FOCUS,
                    FOCUS_POPULATION,
                    first,
                ),
            }
        )
        report["canaries"].append(
            {
                "kind": "kaocha",
                **real_canary(
                    "canary-empty-focus",
                    None,
                    ["--focus", "lvgl-codegen.pretty-test/no-such-test"],
                    CONTROL_POPULATION,
                    first,
                ),
            }
        )
        save()
        if not all(row["passed"] for row in report["canaries"]):
            raise RuntimeError("a runner canary was accepted as a verdict")
        attributed = False
        for fault in report["selection"]:
            source = (ROOT / fault["source"]).read_text()
            worker = make_worker(fault["name"])
            target = worker / Path(fault["source"]).relative_to("tools/renderer-gen")
            if target.read_text() != source:
                raise RuntimeError(f"{fault['name']}: worker does not mirror the checkout")
            changed = source.replace(fault["old"], fault["new"], 1)
            if changed == source:
                raise RuntimeError(f"{fault['name']}: mutation did not change source")
            target.write_text(changed)
            before = worker_files(worker)
            rel = str(Path(*target.relative_to(worker).parts[1:]))
            bound = binding(fault["name"], worker, rel, target)
            command, rc, output, seconds = kaocha(fault["name"], worker, FOCUS)
            verdict = classify(rc, output, FOCUS_POPULATION, fault["expected_failure"])
            _, crc, coutput, cseconds = kaocha(fault["name"] + "-control", worker, CONTROL)
            control = classify(crc, coutput, CONTROL_POPULATION)
            drift = worker_files(worker) != before
            row = {
                **fault,
                "original_sha256": API.digest(ROOT / fault["source"]),
                "mutated_sha256": API.digest(target),
                "command": command,
                "seconds": seconds,
                "verdict": verdict,
                "control": control,
                "control_seconds": cseconds,
                "binding": bound,
                "worker_drift": drift,
            }
            row["status"] = mutant_status(verdict, control, bound["valid"], drift)
            if row["status"] == "killed" and not attributed:
                report["canaries"].extend(
                    {"kind": "failure-report", **item}
                    for item in failure_canaries(out, output, fault["expected_failure"])
                )
                attributed = True
            report["mutants"].append(row)
            save()
            print(f"{fault['name']}: {row['status']}", flush=True)
        baseline("after")
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
    report["input_after"] = inputs()
    report["source_drift"] = sorted(
        f"{group}:{key}"
        for group in report["input_before"]
        for key in set(report["input_before"][group]) | set(report["input_after"][group])
        if report["input_before"][group].get(key) != report["input_after"][group].get(key)
    )
    try:
        final_tools = identities()
        report["tool_drift"] = sorted(
            name for name in report["tools"] if final_tools.get(name) != report["tools"][name]
        )
    except Exception as error:
        report["tool_drift"] = [f"{type(error).__name__}: {error}"]
    try:
        report["head"] = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        report["head"] = f"unresolved: {type(error).__name__}"
    report["elapsed_seconds"] = round(time.monotonic() - started, 1)
    report["passed"] = (
        "error" not in report
        and not report["source_drift"]
        and not report["tool_drift"]
        and len(report["selection"]) == CATALOG_SIZE
        and len(report["mutants"]) == CATALOG_SIZE
        and all(row["status"] == "killed" for row in report["mutants"])
        and len(report["baselines"]) == 6
        and all(row["status"] == "passed" for row in report["baselines"])
        and len(report["canaries"]) == EXPECTED_CANARIES
        and any(row["kind"] == "failure-report" for row in report["canaries"])
        and all(row["passed"] for row in report["canaries"])
    )
    save()
    print(
        f"renderer-gen schema mutations: {'GREEN' if report['passed'] else 'FAILED'}; "
        f"report {out / 'report.json'}",
        flush=True,
    )
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
