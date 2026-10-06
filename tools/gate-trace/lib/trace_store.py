"""`trace-start`, `trace-finish`, and the record reader every later step uses.

The COLD half of gate-trace: it runs once per run, not once per span, so it may
import `json`, `argparse` and `subprocess` freely.

`load_run` is the one reader. It returns the parsed record together with every
integrity finding, and a caller that projects a run (a converter, a history
index) refuses one whose findings are not empty. `trace-finish` is that reader
plus a verdict.

A FINDING IS A NAMED, CLOSED CODE, never free-form prose, so a canary can
assert which clause fired and a later reader can count them. The codes are the
keys of `FINDINGS`, and `SCHEMA.md` documents each one.

EXIT CODES of both commands: 0 ok, 1 findings (trace-finish only), 2 cannot
run (a usage error or an unusable environment), 3 an internal error. An
uncaught traceback is never allowed to read as exit 1.
"""

import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys
import time

import trace_run
import trace_schema as S

# --- findings ------------------------------------------------------------------

FINDINGS = {
    "missing-header": "the run directory has no run.json",
    "missing-end": "the run has no end.json: trace-finish never stamped it",
    "truncated-record": "a record does not end in its newline: it was cut off",
    "malformed-record": "a record is not exactly one line of UTF-8 JSON holding an object",
    "duplicate-key": "a record repeats a key",
    "oversize-record": "a record exceeds the schema's byte bound",
    "unknown-schema": "a record names a schema version this reader does not support",
    "unknown-record": "a record's `record` discriminator is not the one its file name implies",
    "unknown-key": "a record carries a key the schema does not define",
    "missing-key": "a record lacks a key the schema requires",
    "bad-value": "a value has the wrong type, is outside its enum or range, or contradicts another",
    "stray-file": "the run directory holds a file no writer produces",
    "stray-temp": "a writer stopped between writing a record and linking it into place",
    "misnamed-record": "a record's span id differs from the one in its file name",
    "foreign-trace": "a record belongs to a different trace than the run",
    "orphan-span": "a span left its start marker and never recorded an end",
    "unmarked-span": "a span recorded an end but no start marker, so its start was never durable",
    "start-mismatch": "a span's start marker and its final record disagree",
    "dangling-parent": "a span's parent is neither a span of the run nor its root",
    "parent-cycle": "span parents loop instead of reaching the run's root",
    "time-inconsistent": "an interval ends before it starts or lies outside its parent or the run",
    "dangling-annotation": "an annotation targets no span of the run",
    "late-annotation": "an annotation was made after its span ended",
    "conflicting-annotation": "two annotations give one attribute of one span different values",
    "too-many-attributes": "a span carries more attributes than the schema allows",
    "empty-run": "the run recorded no span at all",
}


class Finding(tuple):
    """(code, where, detail), where `code` is a key of FINDINGS."""

    __slots__ = ()

    def __new__(cls, code, where, detail):
        if code not in FINDINGS:
            raise ValueError(f"unregistered finding code {code!r}")
        return super().__new__(cls, (code, where, detail))

    @property
    def code(self):
        return self[0]

    @property
    def where(self):
        return self[1]

    @property
    def detail(self):
        return self[2]


class Refusal(Exception):
    """A command could not run at all: exit 2 with the message."""


# --- value checks ---------------------------------------------------------------
# Each check takes (value, where) and returns a list of (code, where, detail).


def _int(lo, hi=None):
    def check(value, where):
        if isinstance(value, bool) or not isinstance(value, int):
            return [("bad-value", where, f"expected an integer, got {type(value).__name__}")]
        if value < lo or (hi is not None and value > hi):
            bound = hi if hi is not None else "inf"
            return [("bad-value", where, f"{value} outside [{lo}, {bound}]")]
        return []

    return check


def _bool(value, where):
    return [] if isinstance(value, bool) else [("bad-value", where, "expected true or false")]


def _const(expected):
    def check(value, where):
        return [] if value == expected else [("bad-value", where, f"expected {expected!r}")]

    return check


def _enum(values):
    def check(value, where):
        if isinstance(value, str) and value in values:
            return []
        return [("bad-value", where, f"{value!r} is not one of {', '.join(values)}")]

    return check


def _text(max_chars, printable=True, nonempty=True):
    def check(value, where):
        if not isinstance(value, str):
            return [("bad-value", where, f"expected a string, got {type(value).__name__}")]
        if nonempty and not value:
            return [("bad-value", where, "empty string")]
        if len(value) > max_chars:
            return [("bad-value", where, f"longer than {max_chars} characters")]
        if printable and not value.isprintable():
            return [("bad-value", where, "contains a non-printable character")]
        return []

    return check


def _problem(problem_of):
    """A check built from one of the schema's grammar functions."""

    def check(value, where):
        problem = problem_of(value)
        return [] if problem is None else [("bad-value", where, f"{value!r}: {problem}")]

    return check


def _hex_id(width):
    def check(value, where):
        if S.is_hex_id(value, width):
            return []
        return [("bad-value", where, f"expected a non-zero {width}-digit lowercase hex id")]

    return check


def _hex(widths):
    def check(value, where):
        if (
            isinstance(value, str)
            and len(value) in widths
            and set(value) <= set("0123456789abcdef")
        ):
            return []
        return [
            ("bad-value", where, f"expected {' or '.join(map(str, widths))} lowercase hex digits")
        ]

    return check


_REVISION = _hex((40, 64))
_SHA256 = _hex((64,))


def _nullable(inner):
    def check(value, where):
        return [] if value is None else inner(value, where)

    return check


def _list(item, max_items):
    def check(value, where):
        if not isinstance(value, list):
            return [("bad-value", where, "expected a list")]
        if len(value) > max_items:
            return [("bad-value", where, f"{len(value)} items, at most {max_items}")]
        out = []
        for index, element in enumerate(value):
            out.extend(item(element, f"{where}[{index}]"))
        return out

    return check


def _map(key_check, value_check, max_items, min_items=0):
    """An object whose KEYS are data (names, paths), each judged by `key_check`."""

    def check(value, where):
        if not isinstance(value, dict):
            return [("bad-value", where, "expected an object")]
        if not min_items <= len(value) <= max_items:
            return [("bad-value", where, f"{len(value)} entries, allowed {min_items}..{max_items}")]
        out = []
        for key, element in value.items():
            out.extend(key_check(key, f"{where} key"))
            out.extend(value_check(element, f"{where}.{key}"))
        return out

    return check


def _object(keys, checks):
    """A CLOSED object: exactly `keys`, each judged by its check."""

    def check(value, where):
        if not isinstance(value, dict):
            return [("bad-value", where, "expected an object")]
        out = [("unknown-key", where, f"{key!r}") for key in value if key not in keys]
        for key in keys:
            if key not in value:
                out.append(("missing-key", where, f"{key!r}"))
            else:
                out.extend(checks[key](value[key], f"{where}.{key}"))
        return out

    return check


_LOWER_DIGITS = "abcdefghijklmnopqrstuvwxyz0123456789"


def _token_problem(max_chars):
    def problem_of(value):
        if (
            isinstance(value, str)
            and 0 < len(value) <= max_chars
            and value[0] in _LOWER_DIGITS
            and set(value) <= set(_LOWER_DIGITS + "._-")
        ):
            return None
        return f"must be [a-z0-9][a-z0-9._-]* and at most {max_chars} characters"

    return problem_of


ENTRYPOINT_PROBLEM = _token_problem(S.MAX_ENTRYPOINT_CHARS)
TOOLCHAIN_NAME_PROBLEM = _token_problem(S.MAX_TOOLCHAIN_NAME_CHARS)

_EXIT_CHECKS = {
    "exited": _object(S.EXIT_KEYS["exited"], {"status": _const("exited"), "code": _int(0, 255)}),
    "signaled": _object(
        S.EXIT_KEYS["signaled"],
        {"status": _const("signaled"), "signal": _int(1, 64), "core_dumped": _bool},
    ),
    "spawn-failed": _object(
        S.EXIT_KEYS["spawn-failed"],
        {
            "status": _const("spawn-failed"),
            "errno": _text(32),
            "code": _int(126, 127),
        },
    ),
}


def _exit(value, where):
    if isinstance(value, dict) and value.get("status") in _EXIT_CHECKS:
        return _EXIT_CHECKS[value["status"]](value, where)
    return [("bad-value", where, f"exit.status must be one of {', '.join(S.EXIT_STATUSES)}")]


_START_CHECKS = {
    "schema": _int(0),
    "record": _const(S.RECORD_SPAN_START),
    "trace_id": _hex_id(S.TRACE_ID_HEX),
    "span_id": _hex_id(S.SPAN_ID_HEX),
    "parent_span_id": _hex_id(S.SPAN_ID_HEX),
    "name": _problem(S.name_problem),
    "kind": _enum(S.KINDS),
    "start_mono_ns": _int(0),
}
SPAN_START_CHECK = _object(S.SPAN_START_KEYS, _START_CHECKS)
SPAN_CHECK = _object(
    S.SPAN_KEYS,
    dict(
        _START_CHECKS,
        record=_const(S.RECORD_SPAN),
        end_mono_ns=_int(0),
        verdict=_enum(S.VERDICTS),
        exit=_exit,
        rusage=_nullable(_object(S.RUSAGE_KEYS, {key: _int(0) for key in S.RUSAGE_KEYS})),
        signals_forwarded=_object(
            S.FORWARDED_SIGNALS, {name: _int(0) for name in S.FORWARDED_SIGNALS}
        ),
        attributes=_object(
            S.SPAN_ATTRIBUTE_KEYS,
            {
                "process.pid": _nullable(_int(1)),
                "process.executable.name": _text(
                    S.MAX_EXECUTABLE_NAME_CHARS, printable=False, nonempty=False
                ),
            },
        ),
    ),
)

ANNOTATION_CHECK = _object(
    S.ANNOTATION_KEYS,
    {
        "schema": _int(0),
        "record": _const(S.RECORD_ANNOTATION),
        "trace_id": _hex_id(S.TRACE_ID_HEX),
        "span_id": _hex_id(S.SPAN_ID_HEX),
        "time_mono_ns": _int(0),
        "attributes": _map(
            _problem(S.attribute_key_problem),
            _text(S.MAX_ATTRIBUTE_VALUE_CHARS, printable=False, nonempty=False),
            S.MAX_ANNOTATION_PAIRS,
            min_items=1,
        ),
        "truncated": _list(_problem(S.attribute_key_problem), S.MAX_ANNOTATION_PAIRS),
    },
)

RUN_CHECK = _object(
    S.RUN_KEYS,
    {
        "schema": _int(0),
        "record": _const(S.RECORD_RUN),
        "run_id": _hex_id(S.TRACE_ID_HEX),
        "root_span_id": _hex_id(S.SPAN_ID_HEX),
        "entrypoint": _problem(ENTRYPOINT_PROBLEM),
        "started_unix_ns": _int(0),
        "started_mono_ns": _int(0),
        "vcs": _object(
            S.VCS_KEYS,
            {
                "revision": _REVISION,
                "dirty": _bool,
                "dirty_digest": _nullable(_SHA256),
                "submodules": _map(
                    _text(S.MAX_PATH_CHARS, printable=False),
                    _object(
                        S.SUBMODULE_KEYS,
                        {"revision": _nullable(_REVISION), "state": _enum(S.SUBMODULE_STATES)},
                    ),
                    S.MAX_SUBMODULES,
                ),
                "changeset": _object(
                    S.CHANGESET_KEYS,
                    {
                        "kind": _enum(S.CHANGESET_KINDS),
                        "base": _REVISION,
                        "tip": _nullable(_REVISION),
                        "paths": _list(
                            _text(S.MAX_PATH_CHARS, printable=False), S.MAX_CHANGESET_PATH_BYTES
                        ),
                        "path_count": _int(0),
                    },
                ),
            },
        ),
        "toolchain": _map(
            _problem(TOOLCHAIN_NAME_PROBLEM),
            _text(S.MAX_SHORT_TEXT_CHARS),
            S.MAX_TOOLCHAIN_TERMS,
            min_items=1,
        ),
        "host": _object(
            S.HOST_KEYS,
            {
                "os": _text(S.MAX_SHORT_TEXT_CHARS),
                "kernel": _text(S.MAX_SHORT_TEXT_CHARS),
                "machine": _text(S.MAX_SHORT_TEXT_CHARS),
                "cpus": _int(1),
                "cpu_model": _nullable(_text(S.MAX_SHORT_TEXT_CHARS)),
                "name": _nullable(_text(S.MAX_SHORT_TEXT_CHARS)),
            },
        ),
    },
)

RUN_END_CHECK = _object(
    S.RUN_END_KEYS,
    {
        "schema": _int(0),
        "record": _const(S.RECORD_RUN_END),
        "run_id": _hex_id(S.TRACE_ID_HEX),
        "ended_unix_ns": _int(0),
        "ended_mono_ns": _int(0),
    },
)


# --- reading -------------------------------------------------------------------


class _DuplicateKey(Exception):
    pass


def _no_duplicates(pairs):
    record = {}
    for key, value in pairs:
        if key in record:
            raise _DuplicateKey(key)
        record[key] = value
    return record


def _reject_constant(name):
    raise ValueError(f"non-finite number {name}")


def _read_record(path, where, findings):
    """A one-record file as a dict, or None with a finding."""
    try:
        size = os.stat(path).st_size
        if size > S.MAX_RECORD_BYTES:
            findings.append(
                Finding("oversize-record", where, f"{size} bytes, bound {S.MAX_RECORD_BYTES}")
            )
            return None
        with open(path, "rb") as handle:
            data = handle.read(S.MAX_RECORD_BYTES + 1)
    except OSError as err:
        findings.append(Finding("malformed-record", where, f"unreadable: {err.strerror}"))
        return None
    if not data.endswith(b"\n"):
        findings.append(Finding("truncated-record", where, "no terminating newline"))
        return None
    body = data[:-1]
    if b"\n" in body:
        findings.append(Finding("malformed-record", where, "more than one line"))
        return None
    try:
        value = json.loads(
            body.decode("utf-8"), object_pairs_hook=_no_duplicates, parse_constant=_reject_constant
        )
    except UnicodeDecodeError as err:
        findings.append(Finding("malformed-record", where, f"not UTF-8: {err.reason}"))
        return None
    except _DuplicateKey as dup:
        findings.append(Finding("duplicate-key", where, f"{dup.args[0]!r}"))
        return None
    except ValueError as err:
        findings.append(Finding("malformed-record", where, str(err)))
        return None
    if not isinstance(value, dict):
        findings.append(Finding("malformed-record", where, "not a JSON object"))
        return None
    return value


def _accept(record, check, expected_record, where, findings):
    """True when `record` has a supported schema, the expected discriminator and
    the closed shape `check` describes; every refusal becomes a finding."""
    version = record.get("schema")
    if isinstance(version, bool) or version not in S.SUPPORTED_SCHEMAS:
        findings.append(
            Finding(
                "unknown-schema", where, f"schema {version!r}; supported: {S.SUPPORTED_SCHEMAS}"
            )
        )
        return False
    if record.get("record") != expected_record:
        findings.append(
            Finding(
                "unknown-record",
                where,
                f"record {record.get('record')!r}, expected {expected_record!r}",
            )
        )
        return False
    problems = check(record, where)
    for code, at, detail in problems:
        findings.append(Finding(code, at, detail))
    return not problems


class RunRecord:
    """A run as read: header, end, spans, orphans, annotations, and its findings.

    `spans` maps span id to its final record; `orphans` maps span id to a start
    marker that has no final record; `unjudged` maps span id to the record
    standing for a span whose final record exists but was rejected or is
    inconsistent (already a finding of its own, so never reported again as an
    orphan, a dangling parent or a missing annotation target); `named` holds
    every span id a start
    or final file is NAMED for, readable or not; `attributes` maps a span id
    (or the run's root span id) to the annotation attributes folded onto it.

    ONE ROOT CAUSE, ONE FINDING: a rejected record is reported where it fails,
    and the consequences that would otherwise cascade from it (an orphan, a
    dangling child) are not reported a second time under other names.
    """

    def __init__(self, path):
        self.path = path
        self.header = None
        self.end = None
        self.spans = {}
        self.orphans = {}
        self.unjudged = {}
        self.named = set()
        self.attributes = {}
        self.annotation_count = 0
        self.findings = []


def _classify(entry):
    """(span id, kind) for a name in spans/, kind one of start/span/attr/temp, or None."""
    if entry.startswith(S.TEMP_PREFIX) and entry.endswith(S.TEMP_SUFFIX):
        return None, "temp"
    span_id, dot, rest = entry.partition(".")
    if not dot or not S.is_hex_id(span_id, S.SPAN_ID_HEX):
        return None, None
    suffix = dot + rest
    if suffix == S.START_SUFFIX:
        return span_id, "start"
    if suffix == S.SPAN_SUFFIX:
        return span_id, "span"
    if suffix.startswith(S.ANNOTATION_INFIX) and suffix.endswith(S.JSON_SUFFIX):
        nonce = suffix[len(S.ANNOTATION_INFIX) : -len(S.JSON_SUFFIX)]
        if S.is_hex_id(nonce, S.NONCE_HEX):
            return span_id, "attr"
    return None, None


def _is_regular(path):
    try:
        return stat.S_ISREG(os.lstat(path).st_mode)
    except OSError:
        return False


def load_run(run_dir):
    """Read and validate the run at `run_dir`. Never raises on a bad record."""
    run = RunRecord(os.path.abspath(run_dir))
    findings = run.findings
    header_path = os.path.join(run_dir, S.RUN_FILE)
    if not _is_regular(header_path):
        findings.append(Finding("missing-header", S.RUN_FILE, "absent or not a regular file"))
        return run
    header = _read_record(header_path, S.RUN_FILE, findings)
    if header is None or not _accept(header, RUN_CHECK, S.RECORD_RUN, S.RUN_FILE, findings):
        return run
    run.header = header
    _load_end(run, run_dir)

    for entry in sorted(os.listdir(run_dir)):
        if entry in (S.RUN_FILE, S.END_FILE, S.SPANS_DIR):
            continue
        temp = entry.startswith(S.TEMP_PREFIX) and entry.endswith(S.TEMP_SUFFIX)
        findings.append(
            Finding("stray-temp" if temp else "stray-file", entry, "not part of the run layout")
        )
    spans_dir = os.path.join(run_dir, S.SPANS_DIR)
    if not os.path.isdir(spans_dir) or os.path.islink(spans_dir):
        findings.append(Finding("stray-file", S.SPANS_DIR, "missing or not a directory"))
        return run

    starts, finals, annotations = {}, {}, []
    rejected = {"start": set(), "span": set(), "attr": set()}
    expected = {
        "start": (SPAN_START_CHECK, S.RECORD_SPAN_START),
        "span": (SPAN_CHECK, S.RECORD_SPAN),
        "attr": (ANNOTATION_CHECK, S.RECORD_ANNOTATION),
    }
    for entry in sorted(os.listdir(spans_dir)):
        where = f"{S.SPANS_DIR}/{entry}"
        path = os.path.join(spans_dir, entry)
        span_id, kind = _classify(entry)
        if kind == "temp":
            findings.append(
                Finding("stray-temp", where, "a write that was never linked into place")
            )
            continue
        if kind is None:
            findings.append(Finding("stray-file", where, "not a start marker, span or annotation"))
            continue
        if kind != "attr":
            run.named.add(span_id)
        if not _is_regular(path):
            # Named like a record but not a regular file: one finding, and the
            # span it names is rejected rather than reported again as an orphan.
            findings.append(
                Finding("stray-file", where, "named like a record but not a regular file")
            )
            rejected[kind].add(span_id)
            continue
        record = _read_record(path, where, findings)
        check, discriminator = expected[kind]
        if record is None or not _accept(record, check, discriminator, where, findings):
            rejected[kind].add(span_id)
            continue
        if record["span_id"] != span_id:
            findings.append(Finding("misnamed-record", where, f"records span {record['span_id']}"))
            rejected[kind].add(span_id)
            continue
        if record["trace_id"] != header["run_id"]:
            findings.append(Finding("foreign-trace", where, f"trace {record['trace_id']}"))
            rejected[kind].add(span_id)
            continue
        if kind == "start":
            starts[span_id] = (record, where)
        elif kind == "span":
            finals[span_id] = (record, where)
        else:
            annotations.append((record, where))

    _pair_spans(run, starts, finals, rejected)
    _check_tree(run)
    _fold_annotations(run, annotations)
    if not run.named:
        findings.append(Finding("empty-run", S.SPANS_DIR, "no span was recorded"))
    return run


def _load_end(run, run_dir):
    findings = run.findings
    path = os.path.join(run_dir, S.END_FILE)
    if not os.path.lexists(path):
        findings.append(Finding("missing-end", S.END_FILE, "absent"))
        return
    if not _is_regular(path):
        findings.append(Finding("stray-file", S.END_FILE, "not a regular file"))
        return
    end = _read_record(path, S.END_FILE, findings)
    if end is None or not _accept(end, RUN_END_CHECK, S.RECORD_RUN_END, S.END_FILE, findings):
        return
    if end["run_id"] != run.header["run_id"]:
        findings.append(Finding("foreign-trace", S.END_FILE, f"trace {end['run_id']}"))
        return
    if end["ended_mono_ns"] < run.header["started_mono_ns"]:
        findings.append(Finding("time-inconsistent", S.END_FILE, "the run ends before it starts"))
        return
    run.end = end


def _pair_spans(run, starts, finals, rejected):
    findings = run.findings
    for span_id, (record, where) in sorted(finals.items()):
        marker = starts.get(span_id)
        if marker is None:
            if span_id not in rejected["start"]:
                findings.append(
                    Finding("unmarked-span", where, f"{record['name']!r} has no start marker")
                )
        else:
            start, marker_where = marker
            differing = [k for k in S.SPAN_START_KEYS if k != "record" and start[k] != record[k]]
            if differing:
                findings.append(Finding("start-mismatch", marker_where, f"differs in {differing}"))
        if not _span_consistent(record, where, findings):
            # Already a finding of its own: kept as a node, so its children,
            # its annotations and the run are not reported again because of it.
            run.unjudged[span_id] = record
            continue
        run.spans[span_id] = record
    for span_id, (record, where) in sorted(starts.items()):
        if span_id in finals:
            continue
        if span_id in rejected["span"]:
            run.unjudged[span_id] = record
        else:
            findings.append(
                Finding(
                    "orphan-span",
                    where,
                    f"{record['name']!r} ({record['kind']}) started and never ended",
                )
            )
            run.orphans[span_id] = record


def _span_value_problems(record):
    """The cross-field contradictions a closed shape check cannot see."""
    implied = S.verdict_for_exit(record["exit"])
    if record["verdict"] != implied:
        yield f"verdict {record['verdict']!r}, but its exit implies {implied!r}"
    failed = record["exit"]["status"] == "spawn-failed"
    if failed != (record["rusage"] is None):
        yield "rusage must be null exactly when the spawn failed"
    if failed != (record["attributes"]["process.pid"] is None):
        yield "process.pid must be null exactly when the spawn failed"


def _span_consistent(record, where, findings):
    ok = True
    if record["end_mono_ns"] < record["start_mono_ns"]:
        findings.append(Finding("time-inconsistent", where, "ends before it starts"))
        ok = False
    for problem in _span_value_problems(record):
        findings.append(Finding("bad-value", where, problem))
        ok = False
    return ok


def _interval(run, span_id):
    """(start, end-or-None) of a span, an orphan or the root, or None if unknown."""
    header = run.header
    if span_id == header["root_span_id"]:
        end = run.end["ended_mono_ns"] if run.end is not None else None
        return header["started_mono_ns"], end
    if span_id in run.spans:
        record = run.spans[span_id]
        return record["start_mono_ns"], record["end_mono_ns"]
    marker = run.orphans.get(span_id) or run.unjudged.get(span_id)
    if marker is not None:
        return marker["start_mono_ns"], None
    return None


def _cycle_members(nodes, start):
    """Every span on the parent loop through `start`."""
    members, cursor = {start}, nodes[start]["parent_span_id"]
    while cursor != start:
        members.add(cursor)
        cursor = nodes[cursor]["parent_span_id"]
    return frozenset(members)


def _check_tree(run):
    findings = run.findings
    root = run.header["root_span_id"]
    reported_cycles = set()
    nodes = dict(run.unjudged)
    nodes.update(run.orphans)
    nodes.update(run.spans)
    for span_id, record in sorted(nodes.items()):
        where = f"{S.SPANS_DIR}/{span_id}"
        parent = record["parent_span_id"]
        if parent != root and parent not in run.named:
            findings.append(
                Finding("dangling-parent", where, f"parent {parent} is not in this run")
            )
            continue
        if parent != root and parent not in nodes:
            continue  # its parent's files exist but were rejected, and are findings already
        seen, cursor = {span_id}, parent
        cycle = False
        while cursor != root:
            if cursor in seen:
                # Reported once per loop, however many spans lead into it.
                members = _cycle_members(nodes, cursor)
                if members not in reported_cycles:
                    reported_cycles.add(members)
                    findings.append(
                        Finding(
                            "parent-cycle",
                            f"{S.SPANS_DIR}/{min(members)}",
                            f"spans {', '.join(sorted(members))} are each other's ancestors",
                        )
                    )
                cycle = True
                break
            seen.add(cursor)
            cursor = nodes[cursor]["parent_span_id"]
            if cursor != root and cursor not in nodes:
                break
        if cycle:
            continue
        start, end = _interval(run, span_id)
        parent_start, parent_end = _interval(run, parent)
        if start < parent_start:
            findings.append(
                Finding("time-inconsistent", where, f"starts before its parent {parent}")
            )
        elif end is not None and parent_end is not None and end > parent_end:
            findings.append(Finding("time-inconsistent", where, f"ends after its parent {parent}"))


def _fold_annotations(run, annotations):
    findings = run.findings
    root = run.header["root_span_id"]
    for record, where in annotations:
        run.annotation_count += 1
        span_id = record["span_id"]
        interval = _interval(run, span_id)
        if interval is None:
            # A span whose files exist but were rejected is a finding already.
            if span_id not in run.named:
                findings.append(
                    Finding("dangling-annotation", where, f"span {span_id} is not in this run")
                )
            continue
        if not set(record["truncated"]) <= set(record["attributes"]):
            findings.append(Finding("bad-value", where, "truncated names a key it does not carry"))
            continue
        start, end = interval
        if record["time_mono_ns"] < start:
            findings.append(Finding("time-inconsistent", where, "made before its span started"))
        elif end is not None and record["time_mono_ns"] > end:
            findings.append(Finding("late-annotation", where, "made after its span ended"))
        folded = run.attributes.setdefault(span_id, {})
        for key, value in record["attributes"].items():
            if key in folded and folded[key] != value:
                findings.append(
                    Finding(
                        "conflicting-annotation", where, f"{key}: {folded[key]!r} and {value!r}"
                    )
                )
            folded[key] = value
    for span_id, folded in sorted(run.attributes.items()):
        own = len(S.SPAN_ATTRIBUTE_KEYS) if span_id != root else 0
        if own + len(folded) > S.MAX_ATTRIBUTES_PER_SPAN:
            findings.append(
                Finding("too-many-attributes", f"{S.SPANS_DIR}/{span_id}", f"{own + len(folded)}")
            )


# --- trace-start ---------------------------------------------------------------


def _git(top, args, env):
    try:
        proc = subprocess.run(["git", *args], cwd=top, env=env, capture_output=True, check=False)
    except OSError as err:
        # git never started: it is not on PATH or not executable, or the
        # directory it would run in is unusable. An unusable environment is
        # a refusal (exit 2), never an internal error.
        if err.filename == "git":
            raise Refusal(f"cannot start git: {err.strerror}") from err
        raise Refusal(f"cannot run git in {top}: {err.strerror}") from err
    if proc.returncode != 0:
        message = proc.stderr.decode("utf-8", "replace").strip() or f"exit {proc.returncode}"
        raise Refusal(f"git {' '.join(args)}: {message}")
    return proc.stdout


def _git_env(explicit_repo):
    env = dict(os.environ)
    if explicit_repo:
        # An inherited GIT_DIR beats `cwd`, and would make an explicit --repo
        # answer about a different repository while looking authoritative.
        env.pop("GIT_DIR", None)
        env.pop("GIT_WORK_TREE", None)
    return env


def _porcelain_paths(porcelain):
    """(status, path) per entry of `git status --porcelain=v1 -z`."""
    tokens = porcelain.split(b"\0")
    out, index = [], 0
    while index < len(tokens) and tokens[index]:
        entry = tokens[index]
        status, path = entry[:2], entry[3:]
        out.append((status, path))
        index += 2 if status[:1] in (b"R", b"C") else 1
    return out


def _dirty_digest(top, porcelain, env):
    """sha256 over the worktree's departure from HEAD: the status, the tracked
    diff against HEAD and the bytes of every untracked file. A submodule's
    CONTENT is not folded in; its revision and state are recorded instead."""
    digest = hashlib.sha256()
    digest.update(b"status\0" + porcelain)
    digest.update(b"diff\0" + _git(top, ["diff", "--binary", "HEAD"], env))
    for status, path in _porcelain_paths(porcelain):
        if status != b"??":
            continue
        full = os.path.join(top, os.fsdecode(path))
        digest.update(b"untracked\0" + path + b"\0")
        try:
            if os.path.islink(full):
                digest.update(b"link\0" + os.fsencode(os.readlink(full)))
            elif os.path.isfile(full):
                with open(full, "rb") as handle:
                    for chunk in iter(lambda: handle.read(1 << 16), b""):
                        digest.update(chunk)
            else:
                digest.update(b"special")
        except OSError as err:
            raise Refusal(f"cannot digest untracked {os.fsdecode(path)}: {err.strerror}") from err
        digest.update(b"\0")
    return digest.hexdigest()


def _submodules(top, env):
    raw = _git(top, ["submodule", "status", "--recursive"], env).decode("utf-8", "surrogateescape")
    states = {" ": "match", "+": "moved", "-": "uninitialized", "U": "conflict"}
    out = {}
    for line in raw.splitlines():
        if not line:
            continue
        state = states.get(line[0])
        sha, _, rest = line[1:].partition(" ")
        if state is None or not rest:
            raise Refusal(f"unparseable submodule status line: {line!r}")
        path = rest[: rest.rindex(" (")] if rest.endswith(")") and " (" in rest else rest
        out[path] = {"revision": None if state == "uninitialized" else sha, "state": state}
    if len(out) > S.MAX_SUBMODULES:
        raise Refusal(f"{len(out)} submodules exceed the schema bound of {S.MAX_SUBMODULES}")
    return out


def _bounded_paths(paths):
    """The leading paths whose encoded size stays inside the changeset bound."""
    kept, used = [], 0
    for path in paths:
        cost = len(trace_run.encode(path)) + 1
        if used + cost > S.MAX_CHANGESET_PATH_BYTES or len(path) > S.MAX_PATH_CHARS:
            break
        kept.append(path)
        used += cost
    return kept


def _changeset(top, spec, head, porcelain, env):
    if spec == "worktree":
        paths = [os.fsdecode(path) for _status, path in _porcelain_paths(porcelain)]
        base, tip, kind = head, None, "worktree"
    else:
        base_ref, sep, tip_ref = spec.partition("..")
        if not sep or not base_ref or not tip_ref:
            raise Refusal(f"--changeset is 'worktree' or '<base>..<tip>', not {spec!r}")
        base = _git(top, ["rev-parse", "--verify", base_ref + "^{commit}"], env).decode().strip()
        tip = _git(top, ["rev-parse", "--verify", tip_ref + "^{commit}"], env).decode().strip()
        names = _git(top, ["diff", "--name-only", "-z", base, tip], env)
        paths = [os.fsdecode(name) for name in names.split(b"\0") if name]
        kind = "range"
    return {
        "kind": kind,
        "base": base,
        "tip": tip,
        "paths": _bounded_paths(paths),
        "path_count": len(paths),
    }


def _cpu_model():
    try:
        with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                key, sep, value = line.partition(":")
                if sep and key.strip() in ("model name", "Model", "cpu model"):
                    text = value.strip()[: S.MAX_SHORT_TEXT_CHARS]
                    return text if text and text.isprintable() else None
    except OSError:
        return None
    return None


def _toolchain(values, files):
    terms = {}

    def add(name, value, flag):
        problem = TOOLCHAIN_NAME_PROBLEM(name)
        if problem is not None:
            raise Refusal(f"{flag} {name!r}: the name {problem}")
        if name in terms:
            raise Refusal(f"toolchain term {name} given twice")
        if _text(S.MAX_SHORT_TEXT_CHARS)(value, flag):
            raise Refusal(
                f"{flag} {name}: value must be printable and at most {S.MAX_SHORT_TEXT_CHARS} characters"
            )
        terms[name] = value

    for item in values:
        name, sep, value = item.partition("=")
        if not sep:
            raise Refusal(f"--toolchain wants NAME=VALUE, got {item!r}")
        add(name, value, "--toolchain")
    for item in files:
        name, sep, path = item.partition("=")
        if not sep or not path:
            raise Refusal(f"--toolchain-file wants NAME=PATH, got {item!r}")
        try:
            with open(path, "rb") as handle:
                digest = hashlib.sha256(handle.read()).hexdigest()
        except OSError as err:
            raise Refusal(f"--toolchain-file {name}: cannot read {path}: {err.strerror}") from err
        add(name, f"sha256:{digest}", "--toolchain-file")
    if not terms:
        raise Refusal(
            "no toolchain term: pass --toolchain NAME=VALUE or --toolchain-file NAME=PATH, "
            "because a run that cannot say what judged it cannot be compared with another"
        )
    if len(terms) > S.MAX_TOOLCHAIN_TERMS:
        raise Refusal(f"more than {S.MAX_TOOLCHAIN_TERMS} toolchain terms")
    return terms


def build_header(entrypoint, repo, changeset_spec, toolchain, with_hostname):
    """The run.json header for a run about to start. Writes nothing."""
    problem = ENTRYPOINT_PROBLEM(entrypoint)
    if problem is not None:
        raise Refusal(f"entrypoint {entrypoint!r} {problem}")
    git_env = _git_env(repo is not None)
    start_dir = repo if repo is not None else os.getcwd()
    top = os.fsdecode(_git(start_dir, ["rev-parse", "--show-toplevel"], git_env).strip())
    head = _git(top, ["rev-parse", "--verify", "HEAD^{commit}"], git_env).decode().strip()
    porcelain = _git(top, ["status", "--porcelain=v1", "-z", "--untracked-files=all"], git_env)
    dirty = bool(porcelain)
    uname = os.uname()
    started_unix = time.time_ns()
    started_mono = time.monotonic_ns()
    return {
        "schema": S.SCHEMA_VERSION,
        "record": S.RECORD_RUN,
        "run_id": trace_run.new_id(S.TRACE_ID_HEX),
        "root_span_id": trace_run.new_id(S.SPAN_ID_HEX),
        "entrypoint": entrypoint,
        "started_unix_ns": started_unix,
        "started_mono_ns": started_mono,
        "vcs": {
            "revision": head,
            "dirty": dirty,
            "dirty_digest": _dirty_digest(top, porcelain, git_env) if dirty else None,
            "submodules": _submodules(top, git_env),
            "changeset": _changeset(top, changeset_spec, head, porcelain, git_env),
        },
        "toolchain": toolchain,
        "host": {
            "os": sys.platform,
            "kernel": uname.release,
            "machine": uname.machine,
            "cpus": os.cpu_count() or 1,
            "cpu_model": _cpu_model(),
            "name": uname.nodename if with_hostname else None,
        },
    }


def _shell_quote(text):
    return "'" + text.replace("'", "'\\''") + "'"


def _start(args):
    if "TELEMETRY_DIR" in os.environ:
        raise Refusal(
            "a run is already active (TELEMETRY_DIR is set); a nested run is refused, "
            "so wrap the inner entry point with trace-run instead"
        )
    toolchain = _toolchain(args.toolchain, args.toolchain_file)
    header = build_header(args.entrypoint, args.repo, args.changeset, toolchain, args.hostname)
    try:
        data = trace_run.record_bytes(header)
    except ValueError as err:
        raise Refusal(f"the run header would be unreadable: {err}") from err
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(header["started_unix_ns"] // 10**9))
    try:
        os.makedirs(args.root, exist_ok=True)
        run_dir = os.path.abspath(
            os.path.join(args.root, f"{stamp}-{header['entrypoint']}-{header['run_id'][:8]}")
        )
        os.mkdir(run_dir)
        os.mkdir(os.path.join(run_dir, S.SPANS_DIR))
        trace_run.write_once(os.path.join(run_dir, S.RUN_FILE), data)
    except OSError as err:
        raise Refusal(f"cannot create a run directory under {args.root}: {err.strerror}") from err
    traceparent = S.format_traceparent(header["run_id"], header["root_span_id"], "01")
    sys.stdout.write(
        f"TELEMETRY_DIR={_shell_quote(run_dir)}; export TELEMETRY_DIR\n"
        f"TRACEPARENT={_shell_quote(traceparent)}; export TRACEPARENT\n"
    )
    return 0


def start_main(argv):
    """Entry point for `bin/trace-start`. Never returns."""
    parser = argparse.ArgumentParser(
        prog="trace-start",
        description="Create a gate-trace run directory and print the shell "
        "assignments that turn telemetry on (use under eval).",
        allow_abbrev=False,
    )
    parser.add_argument("entrypoint", help="what is being run, e.g. pre-push: [a-z0-9][a-z0-9._-]*")
    parser.add_argument("--root", required=True, help="directory that holds run directories")
    parser.add_argument("--repo", help="git work tree the run judges (default: the current one)")
    parser.add_argument(
        "--changeset",
        default="worktree",
        help="'worktree' (default: the uncommitted state against HEAD) or '<base>..<tip>'",
    )
    parser.add_argument(
        "--toolchain",
        action="append",
        default=[],
        metavar="NAME=VALUE",
        help="a toolchain term to record verbatim, e.g. image=<digest>; repeatable",
    )
    parser.add_argument(
        "--toolchain-file",
        action="append",
        default=[],
        metavar="NAME=PATH",
        help="a toolchain term recorded as the sha256 of a file, e.g. a Dockerfile; repeatable",
    )
    parser.add_argument(
        "--hostname",
        action="store_true",
        help="record the host name (off by default: a record may be quoted publicly)",
    )
    _run_command("trace-start", lambda: _start(parser.parse_args(argv)))


# --- trace-finish --------------------------------------------------------------


def _stamp_end(run_dir):
    """Write end.json once, if the header is whole and no end exists yet.

    Returns False when the run was already finished. A broken header is left
    exactly as found, and `load_run` then names what is wrong with it.
    """
    path = os.path.join(run_dir, S.END_FILE)
    if os.path.lexists(path):
        return False
    scratch = []
    header_path = os.path.join(run_dir, S.RUN_FILE)
    if not _is_regular(header_path):
        return True
    header = _read_record(header_path, S.RUN_FILE, scratch)
    if header is None or not _accept(header, RUN_CHECK, S.RECORD_RUN, S.RUN_FILE, scratch):
        return True
    end = {
        "schema": S.SCHEMA_VERSION,
        "record": S.RECORD_RUN_END,
        "run_id": header["run_id"],
        "ended_unix_ns": time.time_ns(),
        "ended_mono_ns": time.monotonic_ns(),
    }
    try:
        trace_run.write_once(path, trace_run.record_bytes(end))
    except FileExistsError:
        return False
    except OSError as err:
        raise Refusal(f"cannot record the run's end in {path}: {err.strerror}") from err
    return True


def _finish(args):
    run_dir = args.run_dir or os.environ.get("TELEMETRY_DIR")
    if not run_dir:
        raise Refusal("no run directory: pass one, or set TELEMETRY_DIR (trace-start prints it)")
    if not os.path.isdir(run_dir):
        raise Refusal(f"not a directory: {run_dir}")
    stamped = _stamp_end(run_dir)
    run = load_run(run_dir)
    spans = len(run.spans)
    failed = sum(1 for record in run.spans.values() if record["verdict"] != "success")
    again = "" if stamped else " (already finished)"
    if run.findings:
        for finding in sorted(run.findings):
            sys.stderr.write(f"trace-finish: {finding.code}: {finding.where}: {finding.detail}\n")
        sys.stderr.write(
            f"trace-finish: FAIL {len(run.findings)} finding(s) over {spans} span(s), "
            f"{len(run.unjudged)} unjudged and {len(run.orphans)} orphan(s){again}; "
            f"run {run.path}\n"
        )
        return 1
    sys.stdout.write(
        f"trace-finish: ok {spans} span(s), {failed} not successful, "
        f"{run.annotation_count} annotation(s){again}; run {run.path}\n"
    )
    return 0


def finish_main(argv):
    """Entry point for `bin/trace-finish`. Never returns."""
    parser = argparse.ArgumentParser(
        prog="trace-finish",
        description="Stamp a gate-trace run's end and validate the whole record.",
        allow_abbrev=False,
    )
    parser.add_argument("run_dir", nargs="?", help="the run directory (default: $TELEMETRY_DIR)")
    _run_command("trace-finish", lambda: _finish(parser.parse_args(argv)))


def _run_command(prog, body):
    """Map the outcome to the exit-code contract: 0 ok, 1 findings, 2 cannot
    run, 3 an internal error. argparse's own usage errors already exit 2."""
    if sys.version_info < trace_run.MIN_PYTHON:
        sys.stderr.write(f"{prog}: needs Python 3.12 or newer\n")
        sys.exit(2)
    try:
        code = body()
    except Refusal as refusal:
        sys.stderr.write(f"{prog}: refused: {refusal}\n")
        code = 2
    except Exception as err:
        sys.stderr.write(f"{prog}: ERROR (internal): {type(err).__name__}: {err}\n")
        code = 3
    sys.stdout.flush()
    sys.exit(code)
