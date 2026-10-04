#!/usr/bin/env python3
"""Manual, isolated actual-WASM mutations of the three reactive Rust contracts."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "lvgl_reactive_native_api", ROOT / "renderer/tools/lvgl-api-selftest.py"
)
API = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = API
SPEC.loader.exec_module(API)
PREFIX = "reactive_api_contracts::"
COMPARE = PREFIX + "every_comparison_preserves_initial_update_and_reload_polarity"
STRING = PREFIX + "string_subject_reloads_own_distinct_initial_and_update_values"
FLAGS = PREFIX + "wire_flag_clear_wins_and_neighbor_remains_visible"
CASES = frozenset([COMPARE, STRING, FLAGS])
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def selection():
    """Freeze the complete affected behavior catalog before any execution."""
    raw = [
        (
            "comparison-polarity",
            "holds == data->cls->assert_when_holds",
            "holds != data->cls->assert_when_holds",
            COMPARE,
        ),
        (
            "string-initial",
            "lv_subject_set_string(entry->subject, initial);",
            "(void)initial;",
            STRING,
        ),
        (
            "flag-clear",
            "apply_wire_flags(obj, node->obj_flags_clear, false);",
            "apply_wire_flags(obj, node->obj_flags_clear, true);",
            FLAGS,
        ),
        ("eq", "return val == ref_value;", "return val != ref_value;", COMPARE),
        ("not-eq", "return val != ref_value;", "return val == ref_value;", COMPARE),
        ("gt", "return val > ref_value;", "return val >= ref_value;", COMPARE),
        ("gte", "return val >= ref_value;", "return val > ref_value;", COMPARE),
        ("lt", "return val < ref_value;", "return val <= ref_value;", COMPARE),
        ("lte", "return val <= ref_value;", "return val < ref_value;", COMPARE),
        ("int-initial", "lv_subject_set_int(entry->subject, initial);", "(void)initial;", COMPARE),
        (
            "string-update",
            "lv_subject_set_string(entry->subject, sv.value.string_value);",
            "(void)sv.value.string_value;",
            STRING,
        ),
        (
            "string-buffer-capacity",
            "entry->str_prev_buf,\n                                        SUBJECT_STRING_BUF_SIZE);",
            "entry->str_prev_buf,\n                                        1);",
            STRING,
        ),
        (
            "checked-target",
            '"checked_when", false, LV_STATE_CHECKED, true',
            '"checked_when", false, LV_STATE_DISABLED, true',
            COMPARE,
        ),
        (
            "enabled-target",
            '"enabled_when", false, LV_STATE_DISABLED, false',
            '"enabled_when", false, LV_STATE_CHECKED, false',
            COMPARE,
        ),
        (
            "pending-target",
            '"pending_when", false, LV_STATE_USER_1, true',
            '"pending_when", false, LV_STATE_USER_2, true',
            COMPARE,
        ),
    ]
    return [
        {
            "name": name,
            "old": old,
            "new": new,
            "expected_failure": case,
            "controls": sorted(CASES - {case}),
        }
        for name, old, new, case in raw
    ]


def classify(exit_code, output, expected_failure=None):
    """Accept only the complete real Rust case population and assertion verdict."""
    result = {"status": "invalid", "exit": exit_code, "cases": [], "failures": []}
    if exit_code is None:
        result["status"] = "timeout"
        return result
    if exit_code not in (0, 101):
        return result
    output = ANSI.sub("", output)
    errors = re.findall(r"^error:.*$", output, re.MULTILINE)
    if any(not line.startswith("error: test failed, to rerun pass ") for line in errors):
        return result
    headers = re.findall(r"^running (\d+) tests$", output, re.MULTILINE)
    rows = re.findall(r"^test (\S+) \.\.\. (ok|FAILED|ignored)$", output, re.MULTILINE)
    summaries = re.findall(
        r"^test result: (ok|FAILED)\. (\d+) passed; (\d+) failed; (\d+) ignored; "
        r"(\d+) measured; (\d+) filtered out; finished in [^\n]+$",
        output,
        re.MULTILINE,
    )
    result["cases"] = rows
    if headers != ["3"] or len(rows) != 3 or len(summaries) != 1:
        return result
    names = [name for name, _ in rows]
    if len(set(names)) != 3 or set(names) != CASES:
        return result
    status, passed, failed, ignored, measured, _ = summaries[0]
    passed, failed, ignored, measured = map(int, (passed, failed, ignored, measured))
    failures = [name for name, verdict in rows if verdict == "FAILED"]
    result["failures"] = failures
    if ignored or measured or any(verdict == "ignored" for _, verdict in rows):
        return result
    if passed != sum(verdict == "ok" for _, verdict in rows) or failed != len(failures):
        return result
    if exit_code == 0 and status == "ok" and passed == 3 and not failed:
        if " panicked at " not in output and "---- " not in output:
            result["status"] = "passed"
        return result
    if exit_code != 101 or status != "FAILED" or passed != 2 or failed != 1:
        return result
    if expected_failure is None or failures != [expected_failure]:
        return result
    blocks = re.findall(
        r"^---- (\S+) stdout ----\n(.*?)(?=^---- |^failures:\s*$|\Z)",
        output,
        re.MULTILINE | re.DOTALL,
    )
    if len(blocks) != 1 or blocks[0][0] != expected_failure:
        return result
    block = blocks[0][1]
    if (
        not re.search(
            rf"thread '{re.escape(expected_failure)}'(?: \(\d+\))? panicked at tests/visual_regression\.rs:\d+:\d+:",
            block,
        )
        or "assertion `left == right` failed" not in block
    ):
        return result
    listed = re.findall(r"^    (reactive_api_contracts::\S+)$", output, re.MULTILINE)
    if listed != [expected_failure]:
        return result
    result["status"] = "failed"
    return result


def run(command, cwd, out, name, timeout):
    start = time.monotonic()
    (out / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
    rc, output = API.execute(command, cwd, out / (name + ".log"), timeout)
    return rc, output, round(time.monotonic() - start, 3)


def roster(root, out):
    rc, text, _ = run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        root,
        out,
        "source-roster",
        30,
    )
    if rc != 0:
        raise RuntimeError("source discovery failed")
    paths = sorted(set(text.split("\0")) - {""})
    return [
        p for p in paths if not p.startswith((".fork-scratch/", "renderer/wasm_harness/target"))
    ]


def snapshot(root, paths):
    result = {}
    for rel in paths:
        p = root / rel
        if p.is_symlink():
            if not p.resolve().is_relative_to(root.resolve()):
                raise ValueError(f"source symlink leaves checkout: {rel}")
            result[rel] = {
                "link": os.readlink(p),
                "sha256": API.digest(p) if p.is_file() else None,
                "directory": p.is_dir(),
            }
        elif p.is_file():
            result[rel] = API.digest(p)
        elif not p.exists():
            result[rel] = None
        else:
            raise ValueError(f"unsupported source entry: {rel}")
    return result


def copy_snapshot(source, worker, paths):
    for rel in paths:
        src, dst = source / rel, worker / rel
        if dst.is_symlink() or dst.is_file():
            dst.unlink()
        if not src.exists() and not src.is_symlink():
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        if src.is_symlink():
            dst.symlink_to(os.readlink(src))
        else:
            shutil.copy2(src, dst)
    target = worker / "renderer/output/controls.wasm"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / "renderer/output/controls.wasm", target)


def canaries(out, baseline, renderer):
    """Real process rejection probes and corrupted observed-baseline report guards."""
    result = []
    probes = [
        ("empty-process", "pass", "invalid", 5),
        (
            "compile-shaped-process",
            "print('error: could not compile lvgl_harness'); raise SystemExit(101)",
            "invalid",
            5,
        ),
        ("crash-process", "import os; os.abort()", "invalid", 5),
        ("deadline-process", "import time; time.sleep(10)", "timeout", 0.1),
    ]
    for name, code, expected, timeout in probes:
        rc, output, elapsed = run([sys.executable, "-c", code], renderer, out, name, timeout)
        observed = classify(rc, output)
        row = {
            "name": name,
            "expected": expected,
            "observed": observed,
            "elapsed_seconds": elapsed,
            "passed": observed["status"] == expected,
        }
        result.append(row)
        if not row["passed"]:
            raise RuntimeError(f"runner canary failed: {name}")
    sample = next(iter(sorted(CASES)))
    corrupt = [
        ("zero-population", baseline.replace("running 3 tests", "running 0 tests")),
        ("missing-case", baseline.replace(f"test {sample} ... ok\n", "")),
        ("duplicate-case", baseline + f"\ntest {sample} ... ok\n"),
        (
            "duplicate-summary",
            baseline
            + next(line for line in baseline.splitlines() if line.startswith("test result:"))
            + "\n",
        ),
        ("ignored-case", baseline.replace(f"test {sample} ... ok", f"test {sample} ... ignored")),
        ("zero-summary", baseline.replace("3 passed; 0 failed;", "0 passed; 0 failed;")),
        ("error-exit-with-green-report", baseline),
    ]
    for name, output in corrupt:
        rc = 101 if name == "error-exit-with-green-report" else 0
        observed = classify(rc, output)
        row = {"name": name, "observed": observed, "passed": observed["status"] == "invalid"}
        result.append(row)
        (out / (name + ".log")).write_text(output)
        if not row["passed"]:
            raise RuntimeError(f"report canary failed: {name}")
    return result


def failure_canaries(out, observed, expected):
    """Corrupt the first actual named Rust assertion report; grant no fault credit."""
    control = sorted(CASES - {expected})[0]
    variants = [
        ("failure-exit-zero", 0, observed),
        (
            "failure-wrong-heading",
            101,
            observed.replace(f"---- {expected} stdout ----", f"---- {control} stdout ----"),
        ),
        (
            "failure-load-panic",
            101,
            observed.replace(
                "assertion `left == right` failed", "called Result::unwrap() on an Err value"
            ),
        ),
        (
            "failure-wrong-location",
            101,
            observed.replace("tests/visual_regression.rs:", "src/host.rs:"),
        ),
        ("failure-missing-control", 101, observed.replace(f"test {control} ... ok\n", "")),
        (
            "failure-extra-summary",
            101,
            observed
            + next(line for line in observed.splitlines() if line.startswith("test result:"))
            + "\n",
        ),
        (
            "failure-count-mismatch",
            101,
            observed.replace("2 passed; 1 failed;", "1 passed; 2 failed;"),
        ),
        ("failure-extra-listing", 101, observed + f"\n    {expected}\n"),
        ("failure-compile-error", 101, observed + "\nerror: could not compile lvgl_harness\n"),
    ]
    rows = []
    for name, exit_code, output in variants:
        verdict = classify(exit_code, output, expected)
        (out / (name + ".log")).write_text(output)
        row = {"name": name, "observed": verdict, "passed": verdict["status"] == "invalid"}
        rows.append(row)
        if not row["passed"]:
            raise RuntimeError(f"failed-report canary accepted invalid output: {name}")
    return rows


def tool_tree(root):
    return {
        str(path.relative_to(root)): API.digest(path)
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out", required=True, type=Path, help="new evidence directory; never reused"
    )
    parser.add_argument(
        "--jobs", type=int, choices=[1], default=1, help="one isolated mutation worker"
    )
    parser.add_argument(
        "--seed-target", type=Path, help="optional read-only cache copied into private target"
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
    report = {
        "selection": selection(),
        "baselines": [],
        "canaries": [],
        "mutants": [],
        "passed": False,
    }
    (out / "selected.json").write_text(json.dumps(report["selection"], indent=2) + "\n")
    worker = out / "worker"
    source = ROOT / "renderer/src/renderer.c"
    try:
        original = source.read_text()
        for fault in report["selection"]:
            if original.count(fault["old"]) != 1:
                raise ValueError(f"{fault['name']}: expected one exact source anchor")
        paths = roster(ROOT, out)
        frozen = snapshot(ROOT, paths)
    except Exception as error:
        report["error"] = f"setup {type(error).__name__}: {error}"
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(report["error"], flush=True)
        return 1
    report["inputs"] = frozen
    report["baseline_wasm_sha256"] = API.digest(ROOT / "renderer/output/controls.wasm")
    sdk = Path(os.environ.get("WASI_SDK", "/opt/wasi-sdk")).resolve()
    report["sdk_inputs"] = tool_tree(sdk)
    target = out / "cargo-target"
    cargo = [
        "env",
        f"CARGO_TARGET_DIR={target}",
        "CARGO_TERM_COLOR=never",
        "RUST_TEST_THREADS=1",
        "cargo",
    ]
    renderer = worker / "renderer"
    current = renderer / "src/renderer.c"

    def save():
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")

    def build(name):
        rc, output, elapsed = run(
            [
                "env",
                "-u",
                "MAKEFLAGS",
                "-u",
                "MFLAGS",
                "-u",
                "CFLAGS",
                "-u",
                "CXXFLAGS",
                "-u",
                "CPPFLAGS",
                "-u",
                "LDFLAGS",
                "-u",
                "CC",
                "-u",
                "CXX",
                "-u",
                "WARN_FLAGS",
                "-u",
                "APP_STD",
                "make",
                "-f",
                "wasm.mk",
                "-j1",
                "all",
            ],
            renderer,
            out,
            name + "-build",
            900,
        )
        return {
            "compile_exit": rc,
            "build_seconds": elapsed,
            "build_output_sha256": hashlib.sha256(output.encode()).hexdigest(),
        }

    def tests(name, expected=None):
        rc, output, elapsed = run(
            cargo
            + [
                "test",
                "--offline",
                "--locked",
                "--test",
                "visual_regression",
                "reactive_api_contracts",
                "--",
                "--test-threads=1",
            ],
            renderer / "wasm_harness",
            out,
            name + "-test",
            900,
        )
        result = classify(rc, output, expected)
        result["test_seconds"] = elapsed
        return result, output

    def baseline(name):
        row = {"name": name, **build(name)}
        if row["compile_exit"] != 0:
            row["status"] = "invalid" if row["compile_exit"] is not None else "timeout"
            report["baselines"].append(row)
            raise RuntimeError(f"{name}: strict build failed")
        row["wasm_sha256"] = API.digest(renderer / "output/controls.wasm")
        verdict, output = tests(name)
        row.update(verdict)
        report["baselines"].append(row)
        save()
        if row["status"] != "passed" or row["wasm_sha256"] != report["baseline_wasm_sha256"]:
            raise RuntimeError(f"{name}: baseline must pass and reproduce input WASM")
        return output

    try:
        for tool in [
            "cargo",
            "rustc",
            "make",
            sys.executable,
            str(Path(os.environ.get("WASI_SDK", "/opt/wasi-sdk")) / "bin/clang"),
            str(Path(os.environ.get("WASI_SDK", "/opt/wasi-sdk")) / "bin/clang++"),
            str(Path(os.environ.get("WASI_SDK", "/opt/wasi-sdk")) / "bin/wasm-ld"),
        ]:
            name = "identity-" + Path(tool).name
            rc, output, _ = run([tool, "--version"], ROOT, out, name, 30)
            if rc != 0:
                raise RuntimeError(f"missing tool: {tool}")
            report.setdefault("tools", {})[tool] = {
                "version": output,
                "binary_sha256": API.digest(Path(shutil.which(tool) or tool)),
            }
        rc, _, _ = run(
            ["git", "clone", "--local", "--no-hardlinks", str(ROOT), str(worker)],
            ROOT,
            out,
            "clone-worker",
            120,
        )
        if rc != 0:
            raise RuntimeError("worker clone failed")
        rc, _, _ = run(
            ["git", "remote", "remove", "origin"], worker, out, "strip-worker-remote", 30
        )
        if rc != 0:
            raise RuntimeError("worker remote stripping failed")
        copy_snapshot(ROOT, worker, paths)
        if snapshot(worker, paths) != frozen:
            raise RuntimeError("worker source snapshot mismatch")
        if args.seed_target:
            seed = args.seed_target.resolve()
            if not seed.is_dir() or seed == target:
                raise ValueError("--seed-target must be an existing separate cache directory")
            if any(path.is_symlink() for path in seed.rglob("*")):
                raise ValueError("--seed-target must not contain symlinks")
            target.mkdir()
            rc, _, _ = run(
                ["cp", "-a", "--reflink=auto", str(seed) + "/.", str(target)],
                ROOT,
                out,
                "copy-private-cache",
                300,
            )
            if rc != 0:
                raise RuntimeError("private cache copy failed")
            report["seed_target"] = str(seed)
        rc, _, _ = run(
            cargo + ["clean", "--offline", "--locked", "-p", "lvgl_harness"],
            renderer / "wasm_harness",
            out,
            "force-worker-package-path",
            120,
        )
        if rc != 0:
            raise RuntimeError("worker package clean failed")
        report["worker_package_cleaned"] = True
        positive = baseline("before")
        report["canaries"] = canaries(out, positive, renderer)
        current.write_text(original + "\n#error LVGL_REACTIVE_COMPILE_CANARY\n")
        rejected = build("compile-canary")
        rejected["name"] = "actual-wasm-compile"
        rejected["passed"] = (
            rejected["compile_exit"] not in (0, None)
            and "LVGL_REACTIVE_COMPILE_CANARY" in (out / "compile-canary-build.log").read_text()
        )
        report["canaries"].append(rejected)
        if not rejected["passed"]:
            raise RuntimeError("actual strict compiler canary failed")
        current.write_text(original)
        baseline("after-compile-canary")
        for fault in report["selection"]:
            changed = original.replace(fault["old"], fault["new"], 1)
            if changed == original:
                raise RuntimeError("mutation did not change source")
            current.write_text(changed)
            observed = snapshot(worker, paths)
            differences = [p for p in paths if observed[p] != frozen[p]]
            if differences != ["renderer/src/renderer.c"]:
                raise RuntimeError(f"unexpected mutant input changes: {differences}")
            row = {**fault, "source_sha256": API.digest(current), **build(fault["name"])}
            if row["compile_exit"] == 0:
                row["wasm_sha256"] = API.digest(renderer / "output/controls.wasm")
                verdict, observed_output = tests(fault["name"], fault["expected_failure"])
                row.update(verdict)
                if row["status"] == "failed":
                    row["status"] = (
                        "killed"
                        if row["wasm_sha256"] != report["baseline_wasm_sha256"]
                        else "invalid"
                    )
                    if fault["name"] == "comparison-polarity" and row["status"] == "killed":
                        report["canaries"].extend(
                            failure_canaries(out, observed_output, fault["expected_failure"])
                        )
                elif row["status"] == "passed":
                    row["status"] = "survived"
            else:
                row["status"] = "timeout" if row["compile_exit"] is None else "invalid"
            report["mutants"].append(row)
            save()
            print(f"{fault['name']}: {row['status']}", flush=True)
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        if current.exists():
            current.write_text(original)
            try:
                baseline("after")
            except Exception as error:
                report["restoration_error"] = f"{type(error).__name__}: {error}"
        final_source = snapshot(ROOT, paths)
        final_worker = snapshot(worker, paths) if worker.exists() else {}
        report["tool_drift"] = []
        for tool, expected in report.get("tools", {}).items():
            try:
                rc, version, _ = run(
                    [tool, "--version"], ROOT, out, "final-identity-" + Path(tool).name, 30
                )
                binary_sha = API.digest(Path(shutil.which(tool) or tool))
                changed = (
                    rc != 0
                    or version != expected["version"]
                    or binary_sha != expected["binary_sha256"]
                )
            except Exception:
                changed = True
            if changed:
                report["tool_drift"].append(tool)
        final_sdk = tool_tree(sdk)
        report["sdk_drift"] = sorted(set(final_sdk) ^ set(report["sdk_inputs"])) + [
            path
            for path in set(final_sdk) & set(report["sdk_inputs"])
            if final_sdk[path] != report["sdk_inputs"][path]
        ]
        report["root_wasm_drift"] = (
            API.digest(ROOT / "renderer/output/controls.wasm") != report["baseline_wasm_sha256"]
        )
        report["source_drift"] = [p for p in paths if final_source[p] != frozen[p]]
        report["worker_drift"] = [p for p in paths if final_worker.get(p) != frozen[p]]
        report["passed"] = (
            "error" not in report
            and "restoration_error" not in report
            and not report["source_drift"]
            and not report["worker_drift"]
            and not report["tool_drift"]
            and not report["sdk_drift"]
            and not report["root_wasm_drift"]
            and len(report["mutants"]) == 15
            and all(row["status"] == "killed" for row in report["mutants"])
            and len(report["baselines"]) == 3
            and all(row["status"] == "passed" for row in report["baselines"])
            and all(row["passed"] for row in report["canaries"])
            and len(report["canaries"]) == 21
        )
        save()
    print(
        f"reactive mutations: {'GREEN' if report['passed'] else 'FAILED'}; report {out / 'report.json'}",
        flush=True,
    )
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
