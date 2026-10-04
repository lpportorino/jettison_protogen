#!/usr/bin/env python3
"""Native global-subject lifetime proof; broader faults are manual (--mutations)."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

MODULE = Path(__file__).with_name("lvgl-api-selftest.py")
SPEC = importlib.util.spec_from_file_location("lvgl_api_selftest", MODULE)
API = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = API
SPEC.loader.exec_module(API)

CASES = {
    "globals.invalid-size",
    "globals.success-and-reinit",
    "globals.first-allocation-fails",
    "globals.second-allocation-fails",
}
BASE_ASSERTIONS = 74
CANARY_ASSERTIONS = 75


def replace_once(source, old, new):
    if not old or old == new or source.count(old) != 1:
        raise ValueError(f"Expected one changing source anchor: {old!r}")
    return source.replace(old, new, 1)


def hashes(renderer):
    paths = {
        renderer / "src/main.c",
        renderer / "lv_conf.h",
        MODULE,
        renderer / "config/dev/lv_conf.h",
        Path(__file__),
    }
    paths.update((renderer / "tools").glob("lvgl-global-subject-selftest.*"))
    for directory in ("src", "lvgl", "generated"):
        paths.update((renderer / directory).rglob("*.h"))
    return {
        str(path.relative_to(renderer)): API.digest(path)
        for path in sorted(paths)
        if path.is_file()
    }


def run(renderer, out, name, source, configuration="release", fail_canary=False, timeout=10):
    directory = out / name
    directory.mkdir()
    candidate = directory / "main.c"
    candidate.write_text(source)
    binary = directory / "probe"
    command = [
        "cc",
        "-std=c2x",
        "-O1",
        "-g",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wshadow",
        "-Wformat=2",
        "-Wformat-security",
        # main.c contains 32-bit WASM pointer exports. They are not called by
        # this 64-bit native probe; the strict WASM build checks those exports.
        "-Wno-attributes",
        "-Wno-int-to-pointer-cast",
        "-ffunction-sections",
        "-fdata-sections",
        "-DLV_CONF_INCLUDE_SIMPLE",
        "-DPB_FIELD_32BIT",
        "-DPB_ENABLE_MALLOC",
        "-DHAS_NANOPB",
        f'-DMAIN_SOURCE="{candidate}"',
    ]
    if fail_canary:
        command.append("-DGLOBAL_PROBE_FAIL_CANARY")
    if configuration == "dev":
        command.append(f"-I{renderer / 'config/dev'}")
    command.extend(f"-I{renderer / path}" for path in (".", "lvgl", "src", "generated"))
    command.extend(
        [
            str(renderer / "tools/lvgl-global-subject-selftest.c"),
            "-Wl,--gc-sections",
            "-o",
            str(binary),
        ]
    )
    (directory / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    compile_exit, _ = API.execute(command, renderer, directory / "compile.log", 60)
    row = {
        "name": name,
        "configuration": configuration,
        "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
        "compile_exit": compile_exit,
    }
    if compile_exit != 0:
        row["status"] = "timeout" if compile_exit is None else "invalid"
        return row
    code, output = API.execute([str(binary)], renderer, directory / "run.log", timeout)
    expected_assertions = CANARY_ASSERTIONS if fail_canary else BASE_ASSERTIONS
    row.update(API.classify(code, output, CASES, expected_assertions=expected_assertions))
    if row["status"] in ("pass", "fail"):
        row["status"] = "passed" if code == 0 else "failed"
    row["failures"] = sorted(set(re.findall(r"^FAIL (globals\.[^\s:]+):", output, re.M)))
    row["passes"] = re.findall(r"^PASS (globals\.[^\s:]+)$", output, re.M)
    return row


def selected_faults(source):
    faults = []

    def add(name, old, new, expected="globals.success-and-reinit"):
        replace_once(source, old, new)
        faults.append({"name": name, "old": old, "new": new, "expected_failure": expected})

    for subject in ("subj_composite", "subj_channel_type"):
        allocation = f"{subject} = lv_subject_create(LV_SUBJECT_TYPE_INT);"
        add(f"omit-{subject}-create", allocation, f"{subject} = NULL;")
        add(
            f"wrong-{subject}-type",
            allocation,
            f"{subject} = lv_subject_create(LV_SUBJECT_TYPE_STRING);",
        )
        add(f"omit-{subject}-delete", f"lv_subject_delete({subject});", f"(void){subject};")
    add(
        "accept-partial-allocation",
        "if (!subj_composite || !subj_channel_type)",
        "if (!subj_composite && !subj_channel_type)",
        "globals.first-allocation-fails",
    )
    failure = (
        '    LOG_ERROR("global subject allocation failed");\n'
        "    controls_destroy();\n    return -1;"
    )
    add(
        "omit-allocation-rollback",
        failure,
        failure.replace("controls_destroy();", "(void)0;"),
        "globals.first-allocation-fails",
    )
    add(
        "mask-allocation-failure",
        failure,
        failure.replace("return -1;", "return 0;"),
        "globals.first-allocation-fails",
    )
    add(
        "delete-wrong-global",
        "lv_subject_delete(subj_channel_type);",
        "lv_subject_delete(subj_composite);",
    )
    cleanup = "  renderer_cleanup();               /* Then subjects + style pool */\n  lv_subject_delete(subj_composite);"
    add(
        "delete-before-detach",
        cleanup,
        "  lv_subject_delete(subj_composite);\n  renderer_cleanup();",
    )
    add("destroy-not-idempotent", "if (!display) {", "if (false) {")
    return faults


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="New evidence directory")
    parser.add_argument("--mutations", action="store_true", help="Manual affected-scope campaign")
    args = parser.parse_args()
    renderer = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = (renderer / "src/main.c").read_text()
    faults = selected_faults(source) if args.mutations else []
    report = {
        "inputs": hashes(renderer),
        "python": sys.version,
        "compiler": subprocess.check_output(["cc", "--version"], text=True),
        "selected": faults,
        "baselines": [],
        "canaries": [],
        "mutants": [],
    }

    def save():
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")

    save()
    for configuration in ("release", "dev"):
        row = run(renderer, out, "baseline-" + configuration, source, configuration)
        report["baselines"].append(row)
        print(row["name"], row["status"], flush=True)
    if not all(row["status"] == "passed" for row in report["baselines"]):
        save()
        return 1

    # The actual passing global output must use its explicit suite budget.
    # Native and assertion-canary budgets must reject the same named controls.
    baseline_output = (out / "baseline-release/run.log").read_text()
    control = API.classify(0, baseline_output, CASES, expected_assertions=BASE_ASSERTIONS)
    foreign = API.classify(0, baseline_output, CASES, expected_assertions=API.EXPECTED_ASSERTIONS)
    wrong_canary = API.classify(0, baseline_output, CASES, expected_assertions=CANARY_ASSERTIONS)
    report["canaries"].append(
        {
            "name": "shared-assertion-budget",
            "evidence": "baseline-release/run.log",
            "control": control,
            "native_budget": foreign,
            "assertion_canary_budget": wrong_canary,
            "accepted": control["status"] == "pass"
            and foreign["status"] == "invalid"
            and wrong_canary["status"] == "invalid",
        }
    )

    # This fixture assertion proves the native runner really reports failures.
    row = run(renderer, out, "assertion-canary", source, fail_canary=True)
    row["accepted"] = (
        row["status"] == "failed"
        and row["failures"] == ["globals.invalid-size"]
        and "globals.success-and-reinit" in row["passes"]
    )
    report["canaries"].append(row)
    for name, insertion, expected in (
        ("empty", "exit(0);\n", "invalid"),
        ("compile", "#error deliberate compile refusal\n", "invalid"),
        ("crash", "abort();\n", "invalid"),
        ("timeout", "volatile bool wait_forever = true; while (wait_forever) {}\n", "timeout"),
    ):
        changed = replace_once(source, "  lv_init();", insertion + "  lv_init();")
        row = run(renderer, out, name + "-canary", changed, timeout=0.25)
        row["accepted"] = row["status"] == expected
        report["canaries"].append(row)
    if all(row["accepted"] for row in report["canaries"]):
        for fault in faults:
            changed = replace_once(source, fault["old"], fault["new"])
            row = run(renderer, out, fault["name"], changed)
            row["killed"] = (
                row["status"] == "failed"
                and fault["expected_failure"] in row["failures"]
                and "globals.invalid-size" in row["passes"]
            )
            report["mutants"].append(row)
            print(row["name"], row["status"], "killed=" + str(row["killed"]), flush=True)
            save()
    for configuration in ("release", "dev"):
        report["baselines"].append(
            run(renderer, out, "final-" + configuration, source, configuration)
        )
    report["input_drift"] = hashes(renderer) != report["inputs"]
    save()
    return (
        0
        if (
            not report["input_drift"]
            and all(row["status"] == "passed" for row in report["baselines"])
            and all(row["accepted"] for row in report["canaries"])
            and len(report["mutants"]) == len(faults)
            and all(row["killed"] for row in report["mutants"])
        )
        else 1
    )


if __name__ == "__main__":
    sys.exit(main())
