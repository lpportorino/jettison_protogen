#!/usr/bin/env python3
"""Native renderer/LVGL boundary contracts; run in the official dev container."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass

# Closed assertion budget for the 116-case maintained contract. Native branches
# use fixture outcomes, so mutants must execute this same assertion population.
EXPECTED_ASSERTIONS = 1165


@dataclass(frozen=True)
class Fault:
    name: str
    old: str
    new: str
    failure: str
    control: str = "config.public"
    section: str = "flags"
    configuration: str = "release"


SECTIONS = {
    "flags": ("static void apply_wire_flags(", "/* Names carried by a state update"),
    "reset": ("static void reset_subject_registry(", "static subject_entry_t *find_subject("),
    "declaration": ("static bool subjects_decode_cb(", "static bool bindings_decode_cb("),
    "compare": ("static void apply_compare_binding(", " * Reactive text-color binding"),
    "cleanup": ("static void cleanup_event_cb(", "static void cleanup_event_cb("),
    "color": ("static void apply_color_when(", "/* An EventBinding.set_subject"),
    "color-observer": ("static void color_observer_cb(", "static void apply_color_when("),
}


def replace_exact(source, fault):
    if fault.section == "config":
        start, end = 0, len(source)
    else:
        first, last = SECTIONS[fault.section]
        start = source.index(first)
        end = (
            source.index("\n", start)
            if fault.section == "cleanup"
            else source.index(last, start + len(first))
        )
    section = source[start:end]
    if section.count(fault.old) != 1:
        raise ValueError(
            f"{fault.name}: expected one mutation anchor, found {section.count(fault.old)}"
        )
    changed = section.replace(fault.old, fault.new, 1)
    assert changed != section
    return source[:start] + changed + source[end:]


def faults(dedicated, users, reserved):
    result = []
    for index, (name, _, setter) in enumerate(dedicated):
        neighbor = dedicated[(index + 1) % len(dedicated)][2]
        result.append(
            Fault(
                "setter-" + name.lower(),
                f"{{{name}, {setter}}}",
                f"{{{name}, {neighbor}}}",
                f"flags.{name}.set",
                "subjects.int.explicit",
            )
        )
    for name, _, index in users:
        result.append(
            Fault(
                "user-index-" + str(index),
                "lv_obj_set_user_flag(obj, bit, enabled);",
                f"lv_obj_set_user_flag(obj, bit == {index} ? {(index + 1) % 4} : bit, enabled);",
                f"flags.{name}.set",
                "flags.LV_OBJ_FLAG_HIDDEN.set",
            )
        )
    for index, (name, _, prop) in enumerate(reserved):
        neighbor = reserved[(index + 1) % len(reserved)][2]
        result.append(
            Fault(
                "property-" + name.lower(),
                f"{{{name}, {prop}}}",
                f"{{{name}, {neighbor}}}",
                f"flags.{name}.set",
                "flags.LV_OBJ_FLAG_HIDDEN.set",
            )
        )
    result.extend(
        [
            Fault(
                "dedicated-last-dropped",
                "i < sizeof(setters) / sizeof(setters[0])",
                "i + 1 < sizeof(setters) / sizeof(setters[0])",
                "flags.LV_OBJ_FLAG_LAYOUT_1.set",
                "flags.LV_OBJ_FLAG_HIDDEN.set",
            ),
            Fault(
                "user-last-dropped",
                "bit < 4",
                "bit < 3",
                "flags.LV_OBJ_FLAG_USER_4.set",
                "flags.LV_OBJ_FLAG_USER_1.set",
            ),
            Fault(
                "unknown-bit-accepted",
                "bit < 4",
                "bit < 5",
                "flags.unknown",
                "flags.LV_OBJ_FLAG_HIDDEN.set",
            ),
            Fault(
                "reserved-last-dropped",
                "i < sizeof(reserved) / sizeof(reserved[0])",
                "i + 1 < sizeof(reserved) / sizeof(reserved[0])",
                "flags.LV_OBJ_FLAG_WIDGET_2.set",
                "flags.LV_OBJ_FLAG_HIDDEN.set",
            ),
            Fault(
                "dedicated-polarity",
                "setters[i].set(obj, enabled);",
                "setters[i].set(obj, !enabled);",
                "flags.LV_OBJ_FLAG_HIDDEN.clear",
                "flags.LV_OBJ_FLAG_USER_1.clear",
            ),
            Fault(
                "user-polarity",
                "lv_obj_set_user_flag(obj, bit, enabled);",
                "lv_obj_set_user_flag(obj, bit, !enabled);",
                "flags.LV_OBJ_FLAG_USER_1.clear",
                "flags.LV_OBJ_FLAG_HIDDEN.clear",
            ),
            Fault(
                "reserved-polarity",
                ".num = enabled ? 1 : 0",
                ".num = enabled ? 0 : 1",
                "flags.LV_OBJ_FLAG_LAYOUT_2.clear",
                "flags.LV_OBJ_FLAG_HIDDEN.clear",
            ),
            Fault(
                "property-rejection-latch",
                "load_resource_error = true;",
                "load_resource_error = false;",
                "property.reject.LV_OBJ_FLAG_LAYOUT_2.0",
                "flags.LV_OBJ_FLAG_LAYOUT_2.clear",
            ),
        ]
    )
    reset_faults = [
        (
            "delete-omitted",
            "lv_subject_delete(subject_registry[i].subject);",
            "(void)subject_registry[i].subject;",
        ),
        (
            "delete-twice",
            "lv_subject_delete(subject_registry[i].subject);",
            "lv_subject_delete(subject_registry[i].subject);\n    lv_subject_delete(subject_registry[i].subject);",
        ),
        ("last-not-deleted", "i < subject_count", "i + 1 < subject_count"),
        (
            "pointer-not-cleared",
            "subject_registry[i].subject = NULL;",
            "(void)subject_registry[i].subject;",
        ),
        ("count-not-cleared", "subject_count = 0;", "subject_count = 1;"),
        ("overflow-not-cleared", "subject_overflow = false;", "subject_overflow = true;"),
        (
            "unknown-count-not-cleared",
            "unknown_subject_reported_count = 0;",
            "unknown_subject_reported_count = 1;",
        ),
        (
            "unknown-latch-not-cleared",
            "unknown_subject_report_saturated = false;",
            "unknown_subject_report_saturated = true;",
        ),
        (
            "reader-not-installed",
            "cmd_patch_set_subject_reader(renderer_subject_int);",
            "cmd_patch_set_subject_reader(NULL);",
        ),
    ]
    result.extend(
        Fault(
            "reset-" + name,
            old,
            new,
            "subjects.reset.owned",
            "flags.LV_OBJ_FLAG_HIDDEN.set",
            "reset",
        )
        for name, old, new in reset_faults
    )
    declaration_faults = [
        (
            "allocation-latch",
            "load_resource_error = true;",
            "load_resource_error = false;",
            "subjects.int.allocation_failure",
        ),
        (
            "allocation-guard",
            "if (!entry->subject)",
            "if (false)",
            "subjects.int.allocation_failure",
        ),
        (
            "wrong-subject-type",
            "? LV_SUBJECT_TYPE_STRING",
            "? LV_SUBJECT_TYPE_INT",
            "subjects.string.explicit",
        ),
        ("count-not-advanced", "subject_count++;", "subject_count += 0;", "subjects.int.explicit"),
        (
            "string-buffer-size",
            "SUBJECT_STRING_BUF_SIZE);",
            "SUBJECT_STRING_BUF_SIZE - 1);",
            "subjects.string.explicit",
        ),
        (
            "string-buffers-swapped",
            "entry->str_buf,\n                                        entry->str_prev_buf,",
            "entry->str_prev_buf,\n                                        entry->str_buf,",
            "subjects.string.explicit",
        ),
    ]
    result.extend(
        Fault(
            "declaration-" + name, old, new, failure, "flags.LV_OBJ_FLAG_HIDDEN.set", "declaration"
        )
        for name, old, new, failure in declaration_faults
    )
    compare_faults = [
        (
            "allocation-latch",
            'LOG_ERROR("compare observer alloc failed — binding would be inert");\n'
            "    load_resource_error = true;",
            'LOG_ERROR("compare observer alloc failed — binding would be inert");\n'
            "    load_resource_error = false;",
            "compare.eq.allocation_failure",
        ),
        (
            "observer-latch",
            'LOG_ERROR("compare observer registration failed — binding would be inert");\n'
            "    load_resource_error = true;",
            'LOG_ERROR("compare observer registration failed — binding would be inert");\n'
            "    load_resource_error = false;",
            "compare.eq.observer_failure",
        ),
        (
            "observer-leak",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    free(data);",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    (void)data;",
            "compare.eq.observer_failure",
        ),
        (
            "observer-double-free",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    free(data);",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    free(data);\n    free(data);",
            "compare.eq.observer_failure",
        ),
        (
            "cleanup-not-registered",
            "lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data);",
            "obj == NULL ? lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data) : NULL;",
            "compare.eq.success",
        ),
        (
            "wrong-cleanup-event",
            "LV_EVENT_DELETE, data);",
            "LV_EVENT_CLICKED, data);",
            "compare.eq.success",
        ),
        (
            "reference-not-copied",
            "data->ref_value = bind->ref_value;",
            "(void)bind->ref_value;",
            "compare.eq.success",
        ),
        (
            "operator-not-copied",
            "data->compare = bind->compare;",
            "(void)bind->compare;",
            "compare.ne.success",
        ),
        ("class-not-copied", "data->cls = cls;", "(void)cls;", "compare.eq.success"),
        (
            "cleanup-failure-latch",
            'LOG_ERROR("compare cleanup registration failed — binding would leak");\n'
            "    load_resource_error = true;",
            'LOG_ERROR("compare cleanup registration failed — binding would leak");\n'
            "    load_resource_error = false;",
            "compare.eq.cleanup_failure",
        ),
        (
            "cleanup-failure-leak",
            "if (!cleanup) {\n    free(data);",
            "if (!cleanup) {\n    (void)data;",
            "compare.eq.cleanup_failure",
        ),
        ("cleanup-failure-guard", "if (!cleanup)", "if (false)", "compare.eq.cleanup_failure"),
        (
            "observer-rollback-omitted",
            "lv_obj_remove_event_dsc(obj, cleanup);",
            "(void)cleanup;",
            "compare.eq.observer_failure",
        ),
        (
            "observer-rollback-wrong-descriptor",
            "lv_obj_remove_event_dsc(obj, cleanup);",
            "lv_obj_remove_event_dsc(obj, (lv_event_dsc_t *)data);",
            "compare.eq.observer_failure",
        ),
    ]
    result.extend(
        Fault("compare-" + name, old, new, failure, "subjects.int.explicit", "compare")
        for name, old, new, failure in compare_faults
    )
    result.append(
        Fault(
            "cleanup-leak",
            "free(lv_event_get_user_data(e));",
            "(void)lv_event_get_user_data(e);",
            "compare.eq.success",
            "subjects.int.explicit",
            "cleanup",
        )
    )
    color_faults = [
        (
            "allocation-size",
            "malloc(sizeof(color_cb_data_t))",
            "malloc(sizeof(compare_cb_data_t))",
            "color.eq.success",
        ),
        (
            "allocation-latch",
            'LOG_ERROR("color observer alloc failed — binding would be inert");\n    load_resource_error = true;',
            'LOG_ERROR("color observer alloc failed — binding would be inert");\n    load_resource_error = false;',
            "color.eq.allocation_failure",
        ),
        (
            "cleanup-latch",
            'LOG_ERROR("color cleanup registration failed — binding would leak");\n    load_resource_error = true;',
            'LOG_ERROR("color cleanup registration failed — binding would leak");\n    load_resource_error = false;',
            "color.eq.cleanup_failure",
        ),
        (
            "observer-latch",
            'LOG_ERROR("color observer registration failed — binding would be inert");\n    load_resource_error = true;',
            'LOG_ERROR("color observer registration failed — binding would be inert");\n    load_resource_error = false;',
            "color.eq.observer_failure",
        ),
        (
            "cleanup-leak",
            "if (!cleanup) {\n    free(data);",
            "if (!cleanup) {\n    (void)data;",
            "color.eq.cleanup_failure",
        ),
        (
            "cleanup-double-free",
            "if (!cleanup) {\n    free(data);",
            "if (!cleanup) {\n    free(data);\n    free(data);",
            "color.eq.cleanup_failure",
        ),
        ("cleanup-guard", "if (!cleanup)", "if (false)", "color.eq.cleanup_failure"),
        (
            "observer-leak",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    free(data);",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    (void)data;",
            "color.eq.observer_failure",
        ),
        (
            "observer-double-free",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    free(data);",
            "lv_obj_remove_event_dsc(obj, cleanup);\n    free(data);\n    free(data);",
            "color.eq.observer_failure",
        ),
        (
            "observer-rollback-omitted",
            "lv_obj_remove_event_dsc(obj, cleanup);",
            "(void)cleanup;",
            "color.eq.observer_failure",
        ),
        (
            "observer-rollback-wrong-descriptor",
            "lv_obj_remove_event_dsc(obj, cleanup);",
            "lv_obj_remove_event_dsc(obj, (lv_event_dsc_t *)data);",
            "color.eq.observer_failure",
        ),
        (
            "cleanup-not-registered",
            "lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data);",
            "obj == NULL ? lv_obj_add_event_cb(obj, cleanup_event_cb, LV_EVENT_DELETE, data) : NULL;",
            "color.eq.success",
        ),
        (
            "wrong-cleanup-event",
            "LV_EVENT_DELETE, data);",
            "LV_EVENT_CLICKED, data);",
            "color.eq.success",
        ),
        (
            "wrong-observer",
            "lv_subject_add_observer_obj(entry->subject, color_observer_cb, obj,\n                                   data)",
            "lv_subject_add_observer_obj(entry->subject, compare_binding_observer_cb, obj, data)",
            "color.eq.success",
        ),
        (
            "reference-not-copied",
            "data->ref_value = cb->when.ref_value;",
            "(void)cb->when.ref_value;",
            "color.eq.success",
        ),
        (
            "operator-not-copied",
            "data->compare = cb->when.compare;",
            "data->compare = ui_CompareOp_COMPARE_EQ;",
            "color.ne.success",
        ),
    ]
    color_faults.append(
        (
            "observer-rollback-neighbor-descriptor",
            "lv_obj_remove_event_dsc(obj, cleanup);",
            "lv_obj_remove_event_dsc(obj, lv_obj_get_event_dsc(obj, 0));",
            "color.eq.observer_failure",
        )
    )
    color_faults.extend(
        ("channel-" + channel, f"cb->color.{channel}", f"cb->color.{other}", "color.eq.success")
        for channel, other in (("r", "g"), ("g", "b"), ("b", "r"))
    )
    result.extend(
        Fault("color-" + name, old, new, failure, "subjects.int.explicit", "color")
        for name, old, new, failure in color_faults
    )
    result.extend(
        [
            Fault(
                "color-polarity",
                "if (compare_holds(",
                "if (!compare_holds(",
                "color.eq.success",
                "subjects.int.explicit",
                "color-observer",
            ),
            Fault(
                "color-revert-omitted",
                "lv_obj_remove_local_style_prop(obj, LV_STYLE_TEXT_COLOR, LV_PART_MAIN);",
                "(void)obj;",
                "color.ne.success",
                "subjects.int.explicit",
                "color-observer",
            ),
            Fault(
                "color-revert-wrong-property",
                "LV_STYLE_TEXT_COLOR",
                "LV_STYLE_BG_COLOR",
                "color.eq.success",
                "subjects.int.explicit",
                "color-observer",
            ),
        ]
    )
    for configuration in ("release", "dev"):
        result.append(
            Fault(
                "config-argb-" + configuration,
                "#define LV_COLOR_FORMAT_DEFAULT LV_COLOR_FORMAT_XRGB8888",
                "#define LV_COLOR_FORMAT_DEFAULT LV_COLOR_FORMAT_ARGB8888",
                "config.public",
                "flags.LV_OBJ_FLAG_HIDDEN.set",
                "config",
                configuration,
            )
        )
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def oracle(renderer):
    """Read public flag bits, aliases, setters and property declarations only."""
    header = (renderer / "lvgl/include/lvgl/core/lv_obj.h").read_text()
    flags = [
        (name, int(bit))
        for name, bit in re.findall(r"(LV_OBJ_FLAG_\w+)\s*=\s*\(1u << (\d+)\)", header)
    ]
    assert sorted(bit for _, bit in flags) == list(range(31)), flags
    aliases = dict(re.findall(r"(LV_OBJ_FLAG_\w+)\s*=\s*(LV_OBJ_FLAG_\w+)\s*,", header))
    setters = set(re.findall(r"void\s+(lv_obj_set_\w+)\(lv_obj_t \* obj, bool \w+\)", header))
    dedicated, users, reserved = [], [], []
    for name, bit in flags:
        suffix = name.removeprefix("LV_OBJ_FLAG_")
        setter = "lv_obj_set_" + suffix.lower()
        if setter in setters:
            dedicated.append((name, bit, setter))
        elif re.fullmatch(r"USER_[1-4]", suffix):
            users.append((name, bit, int(suffix[-1]) - 1))
        elif suffix in ("LAYOUT_2", "WIDGET_1", "WIDGET_2"):
            assert re.search(r"LV_PROPERTY_ID\(OBJ,\s*FLAG_" + suffix + r",", header)
            reserved.append((name, bit, "LV_PROPERTY_OBJ_FLAG_" + suffix))
        else:
            candidates = [
                "lv_obj_set_" + alias.removeprefix("LV_OBJ_FLAG_").lower()
                for alias, target in aliases.items()
                if target == name
            ]
            candidates = [candidate for candidate in candidates if candidate in setters]
            assert len(candidates) == 1, (name, candidates)
            dedicated.append((name, bit, candidates[0]))
    assert (len(dedicated), len(users), len(reserved)) == (24, 4, 3)
    lines = ["/* Generated solely from public LVGL declarations. */"]
    for name, _, setter in dedicated:
        lines.append(
            f"void {setter}(lv_obj_t *obj, bool enabled) {{ record_flag(obj, {name}, enabled); }}"
        )
    lines.append("void lv_obj_set_user_flag(lv_obj_t *obj, uint32_t bit, bool enabled) {")
    lines.append("  switch (bit) {")
    for name, _, index in users:
        lines.append(f"  case {index}: record_flag(obj, {name}, enabled); break;")
    lines.extend(["  default: boundary_errors++; break;", "  }", "}"])
    lines.append("lv_result_t lv_obj_set_property(lv_obj_t *obj, const lv_property_t *p) {")
    lines.append("  switch (p->id) {")
    for name, _, prop in reserved:
        lines.append(f"  case {prop}: record_flag(obj, {name}, p->num != 0); break;")
    lines.extend(
        [
            "  default: boundary_errors++; break;",
            "  }",
            "  if (p->num != 0 && p->num != 1) boundary_errors++;",
            "  return p->id == rejected_property ? LV_RESULT_INVALID : LV_RESULT_OK;",
            "}",
            "static const struct { const char *name; uint32_t flag; } oracle_flags[] = {",
        ]
    )
    lines.extend(f'  {{"{name}", {name}}},' for name, _ in flags)
    lines.extend(
        [
            "};",
            "static const struct { const char *name; uint32_t flag; lv_prop_id_t id; }",
            "oracle_reserved[] = {",
        ]
    )
    lines.extend(f'  {{"{name}", {name}, {prop}}},' for name, _, prop in reserved)
    lines.append("};")
    return "\n".join(lines) + "\n", dedicated, users, reserved


def execute(command, cwd, log, timeout):
    process = subprocess.Popen(
        command,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    timed_out = False

    def kill_group():
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass

    try:
        try:
            output, _ = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            kill_group()
            output, _ = process.communicate(timeout=5)
    finally:
        # Also reap descendants after an otherwise successful direct child.
        # A compiler driver finishing is not proof that its child exited.
        kill_group()
    log.write_text(output)
    return (None if timed_out else process.returncode), output


def supervisor_canaries(renderer, out):
    results = []
    for holds_stdio in (True, False):
        name = "child-holds-stdio" if holds_stdio else "child-detached-stdio"
        code = (
            "import os, time\n"
            "pid = os.fork()\n"
            "if pid == 0:\n"
            + ("    os.close(1); os.close(2)\n" if not holds_stdio else "")
            + "    time.sleep(30)\n"
            "    os._exit(0)\n"
            "print('SUPERVISOR_CHILD_PID=' + str(pid), flush=True)\n"
        )
        status, output = execute(
            [sys.executable, "-c", code], renderer, out / ("canary-" + name + ".log"), 0.5
        )
        match = re.search(r"^SUPERVISOR_CHILD_PID=(\d+)$", output, re.M)
        child_alive = True
        if match:
            child = int(match.group(1))
            for _ in range(100):
                path = pathlib.Path(f"/proc/{child}/stat")
                try:
                    state = path.read_text().split(") ", 1)[1].split()[0]
                    child_alive = state not in ("Z", "X")
                except FileNotFoundError:
                    child_alive = False
                if not child_alive:
                    break
                time.sleep(0.01)
        result = {
            "name": name,
            "exit": status,
            "marker_retained": bool(match),
            "child_alive": child_alive,
        }
        result["canary_passed"] = (
            bool(match) and not child_alive and (status is None if holds_stdio else status == 0)
        )
        results.append(result)
        print("canary", name, result, flush=True)
    return results


def expected_cases(renderer):
    _, dedicated, users, reserved = oracle(renderer)
    cases = {
        f"flags.{name}.{direction}"
        for name, _, _ in dedicated + users + reserved
        for direction in ("set", "clear")
    }
    cases.update(
        f"property.reject.{name}.{enabled}" for name, _, _ in reserved for enabled in (0, 1)
    )
    cases.update(
        {
            "config.public",
            "flags.mixed.set",
            "flags.mixed.clear",
            "flags.all.set",
            "flags.all.clear",
            "flags.empty",
            "flags.unknown",
            "property.latch.persist",
            "subjects.reset.owned",
            "subjects.reset.empty",
        }
    )
    cases.update(
        f"subjects.{kind}.{mode}"
        for kind in ("int", "string")
        for mode in ("explicit", "default", "allocation_failure")
    )
    cases.update(
        f"compare.{operator}.{mode}"
        for operator in ("eq", "ne")
        for mode in ("success", "allocation_failure", "observer_failure", "cleanup_failure")
    )
    cases.update(
        f"color.{operator}.{mode}"
        for operator in ("eq", "ne", "gt", "gte", "lt", "lte")
        for mode in ("success", "allocation_failure", "observer_failure", "cleanup_failure")
    )
    if len(cases) != 116:
        raise ValueError(f"expected 116 contract cases, discovered {len(cases)}")
    return cases


def classify(code, output, expected, *, expected_assertions):
    if type(expected_assertions) is not int or expected_assertions < 1:
        raise ValueError("An explicit positive assertion budget is required")
    summary = re.findall(r"^SUMMARY assertions=(\d+) failures=(\d+)$", output, re.M)
    passes = re.findall(r"^PASS (\S+)$", output, re.M)
    failures = re.findall(r"^FAIL ([^ :\n]+):", output, re.M)
    observed = set(passes) | set(failures)
    result = {
        "exit": code,
        "summary": summary,
        "cases": len(observed),
        "missing_cases": sorted(expected - observed),
        "unexpected_cases": sorted(observed - expected),
    }
    if code is None:
        result["status"] = "timeout"
    elif (
        code not in (0, 1)
        or len(summary) != 1
        or int(summary[0][0]) != expected_assertions
        or (code == 0) != (int(summary[0][1]) == 0)
        or int(summary[0][1]) != len(failures)
        or observed != expected
        or len(passes) != len(set(passes))
        or set(passes) & set(failures)
    ):
        result["status"] = "invalid"
    else:
        result["status"] = "pass" if code == 0 else "fail"
    return result


def build_run(
    renderer, directory, source, configuration="release", config_override=None, probe_source=None
):
    includes = [directory]
    if config_override:
        includes.append(config_override)
    if configuration == "dev":
        includes.append(renderer / "config/dev")
    includes.extend([renderer, renderer / "lvgl", renderer / "src", renderer / "generated"])
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
        "-Wunused-function",
        "-Wunused-variable",
        "-Wunused-but-set-variable",
        "-Wformat=2",
        "-Wformat-security",
        "-Wno-attributes",
        "-ffunction-sections",
        "-fdata-sections",
        "-DLV_CONF_INCLUDE_SIMPLE",
        "-DPB_FIELD_32BIT",
        "-DPB_ENABLE_MALLOC",
        "-DHAS_NANOPB",
        f'-DRENDERER_SOURCE="{source}"',
    ]
    command.extend(f"-I{include}" for include in includes)
    command.append(str(probe_source or renderer / "tools/lvgl-api-selftest.c"))
    command.extend(
        str(renderer / "generated" / file)
        for file in ("pb_common.c", "pb_decode.c", "pb_encode.c", "ui_ast.pb.c")
    )
    command.extend(["-Wl,--gc-sections", "-o", str(binary)])
    (directory / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    compile_code, _ = execute(command, renderer, directory / "compile.log", 60)
    if compile_code != 0:
        return {
            "status": "timeout" if compile_code is None else "invalid",
            "compile_exit": compile_code,
        }
    code, output = execute([str(binary)], renderer, directory / "run.log", 10)
    return classify(code, output, expected_cases(renderer), expected_assertions=EXPECTED_ASSERTIONS)


def source_hashes(renderer):
    files = {renderer / "src/renderer.c", renderer / "lv_conf.h", renderer / "config/dev/lv_conf.h"}
    for folder in ("src", "lvgl", "generated"):
        files.update((renderer / folder).rglob("*.h"))
    files.update((renderer / "tools").glob("lvgl-api-selftest.*"))
    files.update(
        renderer / "generated" / name
        for name in ("pb_common.c", "pb_decode.c", "pb_encode.c", "ui_ast.pb.c")
    )
    return {
        str(path.relative_to(renderer)): digest(path) for path in sorted(files) if path.is_file()
    }


def run_fault(renderer, out, generated, source, fault):
    directory = out / fault.name
    directory.mkdir()
    (directory / "lvgl-api-oracle.h").write_text(generated)
    config_override = None
    original = source
    mutated_source = directory / "renderer.c"
    mutated_source.write_text(source)
    if fault.section == "config":
        config = (
            renderer / "config/dev/lv_conf.h"
            if fault.configuration == "dev"
            else renderer / "lv_conf.h"
        )
        original = config.read_text()
        config_override = directory
        changed_file = directory / "lv_conf.h"
    else:
        changed_file = mutated_source
    changed = replace_exact(original, fault)
    changed_file.write_text(changed)
    result = build_run(renderer, directory, mutated_source, fault.configuration, config_override)
    result.update(
        {
            "name": fault.name,
            "section": fault.section,
            "expected_failure": fault.failure,
            "control": fault.control,
            "old": fault.old,
            "new": fault.new,
            "original_sha256": hashlib.sha256(original.encode()).hexdigest(),
            "mutated_sha256": digest(changed_file),
        }
    )
    log = (directory / "run.log").read_text() if (directory / "run.log").exists() else ""
    result["expected_failure_seen"] = bool(
        re.search(r"^FAIL " + re.escape(fault.failure) + ":", log, re.M)
    )
    result["control_passed"] = bool(
        re.search(r"^PASS " + re.escape(fault.control) + "$", log, re.M)
    )
    result["killed"] = (
        result["status"] == "fail"
        and result["exit"] == 1
        and result["expected_failure_seen"]
        and result["control_passed"]
    )
    return result


def canaries(renderer, out, generated, source):
    anchor = "  for (size_t i = 0; i < sizeof(setters)"
    cases = [
        ("compile-error", "#error deliberate native compile canary\n", "invalid"),
        ("crash", "  abort();\n", "invalid"),
        ("timeout", "  volatile bool probe_wait = true; while (probe_wait) {}\n", "timeout"),
    ]
    results = []
    for name, insertion, expected in cases:
        fault = Fault("canary-" + name, anchor, insertion + anchor, "flags.LV_OBJ_FLAG_HIDDEN.set")
        result = run_fault(renderer, out, generated, source, fault)
        result["expected_status"] = expected
        result["canary_passed"] = result["status"] == expected and not result["killed"]
        results.append(result)
        print(result["name"], result["status"], flush=True)
    return results


def ordinary_canaries(renderer, out, generated, source):
    fault = Fault(
        "canary-assertion",
        "{LV_OBJ_FLAG_HIDDEN, lv_obj_set_hidden}",
        "{LV_OBJ_FLAG_HIDDEN, lv_obj_set_clickable}",
        "flags.LV_OBJ_FLAG_HIDDEN.set",
        "subjects.int.explicit",
    )
    result = run_fault(renderer, out, generated, source, fault)
    result["canary_passed"] = result["killed"]
    results = [result]
    directory = out / "canary-empty-native-case"
    directory.mkdir()
    (directory / "lvgl-api-oracle.h").write_text(generated)
    (directory / "renderer.c").write_text(source)
    probe = (renderer / "tools/lvgl-api-selftest.c").read_text()
    old = (
        "  CHECK(LV_COLOR_FORMAT_DEFAULT == LV_COLOR_FORMAT_XRGB8888);\n"
        "  CHECK(LV_USE_THORVG && LV_USE_THORVG_INTERNAL);\n"
        "  CHECK(LV_USE_OBJ_PROPERTY);\n"
    )
    if probe.count(old) != 1:
        raise ValueError("empty native case canary anchor missing or ambiguous")
    probe_source = directory / "probe.c"
    probe_source.write_text(probe.replace(old, "", 1))
    result = build_run(renderer, directory, directory / "renderer.c", probe_source=probe_source)
    output = (directory / "run.log").read_text() if (directory / "run.log").exists() else ""
    result.update(
        {
            "name": "empty-native-case",
            "probe_sha256": digest(probe_source),
            "canary_passed": result["status"] == "invalid"
            and "FAIL config.public: no assertions executed\n" in output
            and "PASS subjects.int.explicit\n" in output,
        }
    )
    results.append(result)
    expected = expected_cases(renderer)
    good_cases = "".join(f"PASS {name}\n" for name in sorted(expected))
    cases = [
        ("empty", "", 0),
        ("zero-assertions", good_cases + "SUMMARY assertions=0 failures=0\n", 0),
        (
            "missing-case",
            good_cases.replace("PASS config.public\n", "")
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=0\n",
            0,
        ),
        (
            "failure-exit-zero",
            good_cases.replace("PASS config.public\n", "FAIL config.public: forced\n")
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=1\n",
            0,
        ),
        (
            "extra-summary",
            good_cases
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=0\n"
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=0\n",
            0,
        ),
        (
            "duplicate-case",
            good_cases
            + "PASS config.public\n"
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=0\n",
            0,
        ),
        (
            "pass-and-fail-case",
            good_cases
            + "FAIL config.public: forced\n"
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=1\n",
            1,
        ),
        (
            "failure-count",
            good_cases.replace("PASS config.public\n", "FAIL config.public: forced\n")
            + f"SUMMARY assertions={EXPECTED_ASSERTIONS} failures=2\n",
            1,
        ),
    ]
    cases.append(
        (
            "wrong-assertion-count",
            good_cases + f"SUMMARY assertions={EXPECTED_ASSERTIONS - 1} failures=0\n",
            0,
        )
    )
    cases.append(
        (
            "foreign-suite-assertion-count",
            good_cases + "SUMMARY assertions=74 failures=0\n",
            0,
        )
    )
    for name, output, exit_code in cases:
        program = f"import sys; print({output!r}, end=''); sys.exit({exit_code})"
        status, log = execute(
            [sys.executable, "-c", program], renderer, out / ("canary-" + name + ".log"), 5
        )
        result = classify(status, log, expected, expected_assertions=EXPECTED_ASSERTIONS)
        result.update({"name": name, "canary_passed": result["status"] == "invalid"})
        results.append(result)
    for item in results:
        print("ordinary-canary", item["name"], item["canary_passed"], flush=True)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=pathlib.Path,
        required=True,
        help="Fresh evidence directory (inside the official container)",
    )
    parser.add_argument(
        "--mutations",
        action="store_true",
        help="Also run exact-anchor faults and invalid/timeout canaries",
    )
    args = parser.parse_args()
    renderer = pathlib.Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    generated, dedicated, users, reserved = oracle(renderer)
    source = (renderer / "src/renderer.c").read_text()
    frozen_source = out / "renderer.c"
    frozen_source.write_text(source)
    report = {
        "baselines": [],
        "mutants": [],
        "canaries": [],
        "ordinary_canaries": [],
        "supervisor_canaries": [],
        "source_hashes": source_hashes(renderer),
        "oracle": {"dedicated": dedicated, "users": users, "reserved": reserved},
    }
    report["compiler"] = subprocess.check_output(["cc", "--version"], text=True)

    def save():
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")

    def baseline(stage):
        for configuration in ("release", "dev"):
            directory = out / (stage + "-" + configuration)
            directory.mkdir()
            (directory / "lvgl-api-oracle.h").write_text(generated)
            result = build_run(renderer, directory, frozen_source, configuration)
            result.update({"configuration": configuration, "stage": stage})
            report["baselines"].append(result)
            print(stage, configuration, result, flush=True)
            save()
        return all(item["status"] == "pass" for item in report["baselines"])

    if not baseline("before"):
        return 1
    report["ordinary_canaries"] = ordinary_canaries(renderer, out, generated, source)
    save()
    if not all(item["canary_passed"] for item in report["ordinary_canaries"]):
        return 1
    if args.mutations:
        report["supervisor_canaries"] = supervisor_canaries(renderer, out)
        save()
        if not all(item["canary_passed"] for item in report["supervisor_canaries"]):
            return 1
        report["canaries"] = canaries(renderer, out, generated, source)
        save()
        if not all(item["canary_passed"] for item in report["canaries"]):
            return 1
        for fault in faults(dedicated, users, reserved):
            result = run_fault(renderer, out, generated, source, fault)
            report["mutants"].append(result)
            print(result["name"], "killed" if result["killed"] else result["status"], flush=True)
            save()
        baseline("after")
    after_hashes = source_hashes(renderer)
    report["source_drift"] = sorted(
        name
        for name in set(after_hashes) | set(report["source_hashes"])
        if after_hashes.get(name) != report["source_hashes"].get(name)
    )
    report["passed"] = (
        all(item["status"] == "pass" for item in report["baselines"])
        and all(item["killed"] for item in report["mutants"])
        and all(item["canary_passed"] for item in report["canaries"])
        and all(item["canary_passed"] for item in report["ordinary_canaries"])
        and all(item["canary_passed"] for item in report["supervisor_canaries"])
        and not report["source_drift"]
    )
    save()
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
