"""The closed, versioned shape of a gate-trace run record: its one home.

Every writer (`trace_run`, `trace_store`) and the validator take their
vocabulary from here, and `SCHEMA.md` beside this tool documents it. The test
suite compares that document's key tables against the tuples below, so the
prose cannot drift from what is enforced.

This module imports nothing. It sits on the wrapper's hot path, where every
import is paid once per span.

A RECORD IS CLOSED: a reader refuses a key it does not know and a schema
version it does not support. Adding a field is a schema bump, never a silent
extension, because a later reader comparing two runs must know both describe
the same quantities.

THE CLOCK. Every interval in a record is CLOCK_MONOTONIC nanoseconds
(`time.monotonic_ns()` on Linux), which is system-wide within one boot and is
not stepped by NTP or by an operator setting the date. The run header carries
one (unix, monotonic) pair sampled back to back, so a reader converts any
instant to wall time as `started_unix_ns + (t - started_mono_ns)`. Wall time is
recorded only in that anchor and in the end record, never per span, so no two
spans can disagree about ordering because the wall clock moved between them.
"""

SCHEMA_VERSION = 1
SUPPORTED_SCHEMAS = (1,)

# --- closed enums ----------------------------------------------------------

# What a span IS. `chain` is an aggregate of gates run as one unit, `gate` one
# check that yields a verdict, `test` one test-suite run, `step` any other
# command (a build, a code generator), `boundary` a hop into another process
# namespace such as a container. A kind a later step needs is a schema bump.
KINDS = ("chain", "gate", "test", "step", "boundary")
DEFAULT_KIND = "step"

# How a span ended, derived ONLY from how its process ended. The values are the
# matching subset of OpenTelemetry's `cicd.pipeline.task.run.result`, so a
# converter maps them by identity: `success` exited 0, `failure` exited non-zero
# or died by a signal, `error` the command could not be started.
VERDICTS = ("success", "failure", "error")

EXIT_STATUSES = ("exited", "signaled", "spawn-failed")

# The only signals the wrapper forwards. SIGINT and SIGQUIT are deliberately
# absent: a terminal delivers them to the whole foreground process group, which
# the child shares, so the child already has its copy and a forward would
# deliver it twice.
FORWARDED_SIGNALS = ("SIGHUP", "SIGTERM")

CHANGESET_KINDS = ("worktree", "range")
SUBMODULE_STATES = ("match", "moved", "uninitialized", "conflict")

# --- record discriminators -------------------------------------------------

RECORD_RUN = "run"
RECORD_RUN_END = "run-end"
RECORD_SPAN_START = "span-start"
RECORD_SPAN = "span"
RECORD_ANNOTATION = "annotation"

# --- layout ----------------------------------------------------------------
# <run>/run.json, <run>/end.json, <run>/spans/<span>.start.json,
# <run>/spans/<span>.span.json, <run>/spans/<span>.attr.<nonce>.json.
# Every file is written ONCE: to a dot-prefixed temporary, then hard-linked into
# place, so a reader sees a whole record or none and nothing is ever rewritten.

RUN_FILE = "run.json"
END_FILE = "end.json"
SPANS_DIR = "spans"
START_SUFFIX = ".start.json"
SPAN_SUFFIX = ".span.json"
ANNOTATION_INFIX = ".attr."
JSON_SUFFIX = ".json"
TEMP_PREFIX = "."
TEMP_SUFFIX = ".tmp"

# --- finite bounds at every edge -------------------------------------------
# A writer refuses or truncates BEFORE it could produce a record its own reader
# rejects as oversize; the annotation bound below is sized so that the largest
# legal annotation still fits in one record.

MAX_RECORD_BYTES = 1 << 20
MAX_NAME_CHARS = 128
MAX_EXECUTABLE_NAME_CHARS = 255
MAX_ENTRYPOINT_CHARS = 64
MAX_ANNOTATION_PAIRS = 32
MAX_ATTRIBUTE_KEY_CHARS = 128
MAX_ATTRIBUTE_VALUE_CHARS = 4096
MAX_ATTRIBUTES_PER_SPAN = 256
MAX_TOOLCHAIN_TERMS = 32
MAX_TOOLCHAIN_NAME_CHARS = 64
MAX_SHORT_TEXT_CHARS = 256
MAX_PATH_CHARS = 4096
MAX_CHANGESET_PATH_BYTES = 1 << 18
MAX_SUBMODULES = 256

# --- closed key sets, per record -------------------------------------------
# Each tuple is the COMPLETE key set of its object, and every key is required.
# A value that may be absent is present and null, so a reader never has to
# guess whether a missing key means "unknown" or "not applicable".

RUN_KEYS = (
    "schema",
    "record",
    "run_id",
    "root_span_id",
    "entrypoint",
    "started_unix_ns",
    "started_mono_ns",
    "vcs",
    "toolchain",
    "host",
)
VCS_KEYS = ("revision", "dirty", "dirty_digest", "submodules", "changeset")
SUBMODULE_KEYS = ("revision", "state")
CHANGESET_KEYS = ("kind", "base", "tip", "paths", "path_count")
HOST_KEYS = ("os", "kernel", "machine", "cpus", "cpu_model", "name")

RUN_END_KEYS = ("schema", "record", "run_id", "ended_unix_ns", "ended_mono_ns")

SPAN_START_KEYS = (
    "schema",
    "record",
    "trace_id",
    "span_id",
    "parent_span_id",
    "name",
    "kind",
    "start_mono_ns",
)
SPAN_END_KEYS = (
    "end_mono_ns",
    "verdict",
    "exit",
    "rusage",
    "signals_forwarded",
    "attributes",
)
SPAN_KEYS = SPAN_START_KEYS + SPAN_END_KEYS

# OpenTelemetry semantic-convention names, used because a convention exists for
# exactly these facts; an OTLP projection copies them unrenamed. `process.pid`
# is the CHILD's pid (null when the spawn failed), never the wrapper's.
SPAN_ATTRIBUTE_KEYS = ("process.pid", "process.executable.name")

EXIT_KEYS = {
    "exited": ("status", "code"),
    "signaled": ("status", "signal", "core_dumped"),
    "spawn-failed": ("status", "errno", "code"),
}

# No OpenTelemetry span convention exists for these, so they are a closed
# object of their own. Units: microseconds, KiB, counts. They are what wait4(2)
# reports for the child, which includes every descendant the child itself
# waited for; `maxrss_kib` is the largest single such process, never a sum.
RUSAGE_KEYS = (
    "user_us",
    "sys_us",
    "maxrss_kib",
    "minflt",
    "majflt",
    "inblock",
    "oublock",
    "nvcsw",
    "nivcsw",
)

ANNOTATION_KEYS = (
    "schema",
    "record",
    "trace_id",
    "span_id",
    "time_mono_ns",
    "attributes",
    "truncated",
)

# Attribute namespaces the tool writes itself. `trace-annotate` refuses them so
# an annotation can never shadow a measured fact.
RESERVED_ATTRIBUTE_NAMESPACES = ("process", "host", "vcs")

# --- identifiers -----------------------------------------------------------

TRACE_ID_HEX = 32
SPAN_ID_HEX = 16
NONCE_HEX = 16
_HEX = frozenset("0123456789abcdef")


def is_hex_id(value, width):
    """True when `value` is a lowercase hex id of `width` digits, not all zero.

    The W3C trace-context rule: an all-zero trace or span id is invalid.
    """
    return (
        isinstance(value, str)
        and len(value) == width
        and _HEX.issuperset(value)
        and value != "0" * width
    )


def parse_traceparent(value):
    """Split a W3C `traceparent` into (trace_id, parent_id, flags), or None.

    Only version 00 is accepted, in its exact 55-character form, so a caller
    can name the refusal rather than guess at a future version's layout.
    """
    if not isinstance(value, str) or len(value) != 55:
        return None
    parts = value.split("-")
    if len(parts) != 4 or parts[0] != "00":
        return None
    trace_id, parent_id, flags = parts[1], parts[2], parts[3]
    if not (is_hex_id(trace_id, TRACE_ID_HEX) and is_hex_id(parent_id, SPAN_ID_HEX)):
        return None
    if len(flags) != 2 or not _HEX.issuperset(flags):
        return None
    return trace_id, parent_id, flags


def format_traceparent(trace_id, span_id, flags):
    """The W3C `traceparent` a child of `span_id` inherits."""
    return f"00-{trace_id}-{span_id}-{flags}"


# --- grammars shared with the POSIX-sh shims --------------------------------
# The shims check names, kinds and annotation keys BEFORE deciding whether
# telemetry is on, so a wiring mistake is refused the same way in both modes and
# turning telemetry on can never change which invocations succeed. The two
# implementations are compared over a corpus by the test suite. Both grammars
# are ASCII-only on purpose: a POSIX shell's `${#x}` counts bytes in dash and
# characters in bash, and the two agree only when every character is one byte.

_LOWER = "abcdefghijklmnopqrstuvwxyz"
_UPPER = _LOWER.upper()
_DIGITS = "0123456789"
_NAME_HEAD = frozenset(_LOWER + _UPPER + _DIGITS)
_NAME_TAIL = _NAME_HEAD | frozenset("._:/+=@-")
_KEY_HEAD = frozenset(_LOWER)
_KEY_TAIL = _KEY_HEAD | frozenset(_DIGITS + "_")


def name_problem(name):
    """Why `name` is not a legal span name, or None if it is."""
    if not isinstance(name, str) or not name:
        return "empty"
    if name[0] not in _NAME_HEAD:
        return "must start with a letter or digit"
    if not _NAME_TAIL.issuperset(name):
        return "may hold only A-Z a-z 0-9 and . _ : / + = @ -"
    if len(name) > MAX_NAME_CHARS:
        return f"longer than {MAX_NAME_CHARS} characters"
    return None


def attribute_key_problem(key):
    """Why `key` is not a legal annotation attribute name, or None if it is.

    Two or more dot-separated segments, each a lowercase letter followed by
    lowercase letters, digits or underscores (the OpenTelemetry attribute-name
    shape), at most `MAX_ATTRIBUTE_KEY_CHARS`, and never inside a namespace the
    tool writes itself.
    """
    if not isinstance(key, str) or not key:
        return "empty key"
    if not (_KEY_TAIL | {"."}).issuperset(key):
        return "may hold only a-z 0-9 _ and ."
    segments = key.split(".")
    if any(not segment for segment in segments):
        return "empty segment"
    if len(segments) < 2:
        return "needs a namespace: two or more dot-separated segments"
    if any(segment[0] not in _KEY_HEAD for segment in segments):
        return "each segment must start with a letter"
    if len(key) > MAX_ATTRIBUTE_KEY_CHARS:
        return f"longer than {MAX_ATTRIBUTE_KEY_CHARS} characters"
    if segments[0] in RESERVED_ATTRIBUTE_NAMESPACES:
        return f"namespace '{segments[0]}' is written by the tool itself"
    return None


def verdict_for_exit(exit_record):
    """The verdict a span's exit record implies: the only way one is derived."""
    status = exit_record["status"]
    if status == "exited":
        return "success" if exit_record["code"] == 0 else "failure"
    if status == "signaled":
        return "failure"
    return "error"
