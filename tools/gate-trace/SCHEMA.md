# gate-trace run record, schema version 1

A run record is the durable account of one run of a gate or test chain: a
header, one record per span, and annotations. It is CLOSED and VERSIONED.

- **Closed.** Every object below lists its complete key set and every key is
  required. A value that may be absent is present and `null`, so a reader never
  guesses whether a missing key means "unknown" or "not applicable". A reader
  REFUSES a key it does not know (`unknown-key`) and a key it expected and did
  not find (`missing-key`).
- **Versioned.** Every record carries `schema`. A reader refuses a version it
  does not support (`unknown-schema`). Adding, removing or retyping a field is a
  version bump, never a silent extension, because a later reader comparing two
  runs must know both describe the same quantities.

The vocabulary has one home, `lib/trace_schema.py`. The tables in this document
are compared against it by the test suite, key for key and in order, so this
prose cannot drift from what is enforced. Bounds are named here by their
constant in that module rather than copied as numbers.

## The clock

Every interval is **CLOCK_MONOTONIC nanoseconds** (`time.monotonic_ns()` on
Linux): system-wide within one boot, never stepped by NTP or by someone setting
the date. The header carries one wall-clock anchor, `started_unix_ns` and
`started_mono_ns` sampled back to back, so any instant `t` converts to wall time
as `started_unix_ns + (t - started_mono_ns)`. Wall time is recorded only in that
anchor and in the end record, never per span, so no two spans can disagree about
their order because the wall clock moved between them. A record does not
compare across a reboot, and does not need to: a reboot ends the run.

## Layout

```
<root>/<UTC stamp>-<entrypoint>-<first 8 hex of the run id>/
  run.json                       the header, written by trace-start
  end.json                       the end stamp, written by trace-finish
  spans/
    <span id>.start.json         a span's start marker, written BEFORE its child runs
    <span id>.span.json          the span's final record, written after the child is reaped
    <span id>.attr.<nonce>.json  one annotation; the span id is the span it describes
```

**Every file is written once.** The bytes go to a dot-prefixed temporary ending
in `.tmp` in the same directory, which is then hard-linked to its final name and
unlinked. A reader therefore sees a whole record or no record, an existing
record is never overwritten, and a writer stopped between the two steps leaves
only the temporary, which the validator names (`stray-temp`). This is atomic
visibility, not crash durability: nothing is synced to disk.

**The start marker is kept, not replaced.** A span is complete when both its
marker and its final record exist and agree on every marker key. Keeping the
marker is what makes "written before the spawn" checkable after the fact: a
final record with no marker means the start was never durable (`unmarked-span`),
and a marker with no final record is a span that never ended (`orphan-span`).

Every record is one line of compact UTF-8 JSON ending in exactly one newline,
at most `MAX_RECORD_BYTES`. A record without its newline was cut off
(`truncated-record`).

## Identifiers and nesting

The run id IS the W3C trace id: 32 lowercase hex digits, not all zero. Span ids
are 16 lowercase hex digits, not all zero. `trace-start` exports
`TRACEPARENT=00-<run id>-<root span id>-01`, and each `trace-run` hands its
child `TRACEPARENT=00-<run id>-<its own span id>-<flags>`, so nesting follows
the process tree through any intermediary that passes the environment on. Only
version `00` is read. The root span has no files of its own: its interval is the
run's, from `started_mono_ns` to `end.json`'s `ended_mono_ns`.

## run.json

<!-- keys: RUN_KEYS -->
| key | type | meaning |
|---|---|---|
| `schema` | integer | the schema version |
| `record` | `"run"` | the discriminator |
| `run_id` | 32 hex | the run id, which is the trace id |
| `root_span_id` | 16 hex | the parent of every top-level span |
| `entrypoint` | token | what was run, `[a-z0-9][a-z0-9._-]*`, at most `MAX_ENTRYPOINT_CHARS` |
| `started_unix_ns` | integer | wall clock at start: the anchor |
| `started_mono_ns` | integer | the monotonic clock at the same instant |
| `vcs` | object | the revision judged, below |
| `toolchain` | object | caller-declared terms, `name -> value`, at least one, at most `MAX_TOOLCHAIN_TERMS` |
| `host` | object | non-identifying host facts, below |

A toolchain name is a token like the entrypoint (at most
`MAX_TOOLCHAIN_NAME_CHARS`); a value is printable text of at most
`MAX_SHORT_TEXT_CHARS`. `--toolchain-file NAME=PATH` records `sha256:<hex>` of a
file, which is the usual way to name an image by the file that builds it. At
least one term is required: a run that cannot say what judged it cannot be
compared with another.

<!-- keys: VCS_KEYS -->
| key | type | meaning |
|---|---|---|
| `revision` | 40 or 64 hex | `HEAD` |
| `dirty` | boolean | the worktree differs from `HEAD` |
| `dirty_digest` | 64 hex or null | sha256 over the status, the diff against `HEAD` and every untracked file's bytes; null exactly when clean |
| `submodules` | object | `path -> submodule`, at most `MAX_SUBMODULES` |
| `changeset` | object | what the run judged, below |

A submodule's content is not folded into `dirty_digest`; its revision and state
are recorded per submodule instead.

<!-- keys: SUBMODULE_KEYS -->
| key | type | meaning |
|---|---|---|
| `revision` | 40 or 64 hex, or null | the checked-out commit; null when uninitialized |
| `state` | enum | one of `SUBMODULE_STATES` |

<!-- keys: CHANGESET_KEYS -->
| key | type | meaning |
|---|---|---|
| `kind` | enum | one of `CHANGESET_KINDS` |
| `base` | 40 or 64 hex | `HEAD` for `worktree`; the resolved base for `range` |
| `tip` | 40 or 64 hex, or null | null for `worktree`; the resolved tip for `range` |
| `paths` | list of text | the leading changed paths that fit in `MAX_CHANGESET_PATH_BYTES` |
| `path_count` | integer | how many paths changed; larger than the list when it was cut |

<!-- keys: HOST_KEYS -->
| key | type | meaning |
|---|---|---|
| `os` | text | the platform, e.g. `linux` |
| `kernel` | text | the kernel release |
| `machine` | text | the CPU architecture |
| `cpus` | integer | online CPUs |
| `cpu_model` | text or null | the CPU model string, when the platform reports one |
| `name` | text or null | the host name; null unless `--hostname` was passed |

**A record may be quoted publicly, so know what it reveals.** The host name is
the one fact that names a MACHINE, and it is off by default. The other host
fields are shared by many machines; taken together they narrow a population
without naming a member. The `vcs` object identifies the REPOSITORY and its
state: a revision, submodule revisions and changed paths. Each span's
`process.executable.name` is the base name of its command, a fragment of the
command line. The span name and annotation values are the caller's text, and
the caller decides what they reveal. Full command lines, environment
variables, user names and paths outside the repository are never recorded.

## end.json

<!-- keys: RUN_END_KEYS -->
| key | type | meaning |
|---|---|---|
| `schema` | integer | the schema version |
| `record` | `"run-end"` | the discriminator |
| `run_id` | 32 hex | must equal the header's |
| `ended_unix_ns` | integer | wall clock at the end |
| `ended_mono_ns` | integer | the monotonic clock at the end; never before `started_mono_ns` |

## Spans

A start marker holds exactly these keys, and the final record holds them with
the same values followed by the end keys.

<!-- keys: SPAN_START_KEYS -->
| key | type | meaning |
|---|---|---|
| `schema` | integer | the schema version |
| `record` | `"span-start"` or `"span"` | the discriminator; the file name decides which |
| `trace_id` | 32 hex | must equal the run id |
| `span_id` | 16 hex | must equal the id in the file name |
| `parent_span_id` | 16 hex | a span of this run, or its root |
| `name` | name | the caller's label, see Grammars |
| `kind` | enum | one of `KINDS` |
| `start_mono_ns` | integer | sampled before the start marker is written |

<!-- keys: SPAN_END_KEYS -->
| key | type | meaning |
|---|---|---|
| `end_mono_ns` | integer | sampled when the child's exit is first observed |
| `verdict` | enum | one of `VERDICTS`, derived only from `exit` |
| `exit` | object | how the process ended, below |
| `rusage` | object or null | resource use; null exactly when the spawn failed |
| `signals_forwarded` | object | `SIGHUP` and `SIGTERM`, each a count of forwards to the child |
| `attributes` | object | OpenTelemetry-named facts, below |

<!-- keys: SPAN_ATTRIBUTE_KEYS -->
| key | type | meaning |
|---|---|---|
| `process.pid` | integer or null | the CHILD's pid; null exactly when the spawn failed |
| `process.executable.name` | text | the base name of the command, at most `MAX_EXECUTABLE_NAME_CHARS` |

These two names are OpenTelemetry semantic conventions, used because one exists
for exactly these facts, so a projection copies them unrenamed. Annotations are
records of their own, not keys of this object.

`exit` is discriminated by `status`, one of `EXIT_STATUSES`:

<!-- keys: EXIT_KEYS.exited -->
| key | type | meaning |
|---|---|---|
| `status` | `"exited"` | the process returned |
| `code` | 0..255 | its exit status, exactly as the caller saw it |

<!-- keys: EXIT_KEYS.signaled -->
| key | type | meaning |
|---|---|---|
| `status` | `"signaled"` | the process was killed |
| `signal` | 1..64 | the signal number; the wrapper died of the same signal |
| `core_dumped` | boolean | the child dumped core (the wrapper never does) |

<!-- keys: EXIT_KEYS.spawn-failed -->
| key | type | meaning |
|---|---|---|
| `status` | `"spawn-failed"` | the command never started |
| `errno` | text | the symbolic errno, e.g. `ENOENT` |
| `code` | 126 or 127 | the status returned, as a shell reports it: 127 for not found, 126 otherwise |

`rusage` is what wait4(2) reports for the child, which covers every descendant
the child itself waited for. No OpenTelemetry span convention exists for these,
so they are a closed object of their own.

<!-- keys: RUSAGE_KEYS -->
| key | type | meaning |
|---|---|---|
| `user_us` | integer | user CPU time, microseconds |
| `sys_us` | integer | system CPU time, microseconds |
| `maxrss_kib` | integer | the largest single process's peak resident set, KiB, never a sum |
| `minflt` | integer | minor page faults |
| `majflt` | integer | major page faults |
| `inblock` | integer | block input operations |
| `oublock` | integer | block output operations |
| `nvcsw` | integer | voluntary context switches |
| `nivcsw` | integer | involuntary context switches |

## Annotations

<!-- keys: ANNOTATION_KEYS -->
| key | type | meaning |
|---|---|---|
| `schema` | integer | the schema version |
| `record` | `"annotation"` | the discriminator |
| `trace_id` | 32 hex | must equal the run id |
| `span_id` | 16 hex | the span described: the caller's `TRACEPARENT` span |
| `time_mono_ns` | integer | when it was made, inside its span's interval |
| `attributes` | object | 1 to `MAX_ANNOTATION_PAIRS` keys, each value text of at most `MAX_ATTRIBUTE_VALUE_CHARS` |
| `truncated` | list | the keys whose value was cut to that bound |

A value is data and is never a reason to refuse an annotation, so an over-long
one is cut and its key listed in `truncated`. Two annotations giving one key of
one span different values are a finding (`conflicting-annotation`), because
their order across processes is not defined. A span carries at most
`MAX_ATTRIBUTES_PER_SPAN` attributes in all.

## Enums

<!-- values: KINDS -->
| value | meaning |
|---|---|
| `chain` | an aggregate of gates run as one unit |
| `gate` | one check that yields a verdict |
| `test` | one test-suite run |
| `step` | any other command; the default |
| `boundary` | a hop into another process namespace, such as a container |

<!-- values: VERDICTS -->
| value | meaning |
|---|---|
| `success` | exited 0 |
| `failure` | exited non-zero, or died by a signal |
| `error` | the command could not be started |

These are the matching subset of OpenTelemetry's
`cicd.pipeline.task.run.result`, so a converter maps them by identity.

<!-- values: EXIT_STATUSES -->
| value | meaning |
|---|---|
| `exited` | the process returned a status |
| `signaled` | the process died by a signal |
| `spawn-failed` | the process was never started |

<!-- values: CHANGESET_KINDS -->
| value | meaning |
|---|---|
| `worktree` | the uncommitted state against `HEAD` |
| `range` | the commits from `base` to `tip` |

<!-- values: SUBMODULE_STATES -->
| value | meaning |
|---|---|
| `match` | checked out at the recorded commit |
| `moved` | checked out at a different commit |
| `uninitialized` | not checked out |
| `conflict` | in a merge conflict |

## Grammars

Both are ASCII on purpose: the POSIX-sh shims apply them before any interpreter
starts, and a shell's string length counts bytes in dash and characters in bash,
which agree only when every character is one byte.

- **Span name**: a letter or digit, then letters, digits and `. _ : / + = @ -`,
  at most `MAX_NAME_CHARS`.
- **Attribute key**: two or more dot-separated segments, each a lowercase
  letter followed by lowercase letters, digits or underscores (the OpenTelemetry
  attribute-name shape), at most `MAX_ATTRIBUTE_KEY_CHARS`, and never inside a
  namespace the tool writes itself (`RESERVED_ATTRIBUTE_NAMESPACES`).

## Findings

`trace-finish` reports each integrity failure it detects as one of these closed
codes, one per line on stderr as `trace-finish: <code>: <where>: <detail>`, and
the suite plants a defect for every code and requires that code alone. One root
cause yields one finding: a record that is rejected, or parses but contradicts
itself, still stands for its span, so it is not reported again as an orphan,
its children are not reported as dangling, its annotations are not reported as
targeting nothing, and a run whose spans are all rejected is not also called
empty. A parent loop is reported once, however many spans lead into it.

<!-- values: FINDINGS -->
| code | meaning |
|---|---|
| `missing-header` | the run directory has no run.json |
| `missing-end` | the run has no end.json: trace-finish never stamped it |
| `truncated-record` | a record does not end in its newline: it was cut off |
| `malformed-record` | a record is not exactly one line of UTF-8 JSON holding an object |
| `duplicate-key` | a record repeats a key |
| `oversize-record` | a record exceeds the schema's byte bound |
| `unknown-schema` | a record names a schema version this reader does not support |
| `unknown-record` | a record's discriminator is not the one its file name implies |
| `unknown-key` | a record carries a key the schema does not define |
| `missing-key` | a record lacks a key the schema requires |
| `bad-value` | a value has the wrong type, is outside its enum or range, or contradicts another |
| `stray-file` | the run directory holds a file no writer produces |
| `stray-temp` | a writer stopped between writing a record and linking it into place |
| `misnamed-record` | a record's span id differs from the one in its file name |
| `foreign-trace` | a record belongs to a different trace than the run |
| `orphan-span` | a span left its start marker and never recorded an end |
| `unmarked-span` | a span recorded an end but no start marker |
| `start-mismatch` | a span's start marker and its final record disagree |
| `dangling-parent` | a span's parent is neither a span of the run nor its root |
| `parent-cycle` | span parents loop instead of reaching the run's root |
| `time-inconsistent` | an interval ends before it starts, or lies outside its parent or the run |
| `dangling-annotation` | an annotation targets no span of the run |
| `late-annotation` | an annotation was made after its span ended |
| `conflicting-annotation` | two annotations give one attribute of one span different values |
| `too-many-attributes` | a span carries more attributes than the schema allows |
| `empty-run` | the run recorded no span at all |
