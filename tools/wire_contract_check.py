#!/usr/bin/env python3
"""Assert docs/INTERFACE-CONTRACTS.md against the generated descriptor set.

WHY THIS EXISTS
---------------
`docs/INTERFACE-CONTRACTS.md` §10 and CLAUDE.md both state that renumbering a
proto field IS ALLOWED when every consumer rebuilds in lockstep. So a hard
`buf breaking` gate would contradict a deliberate design choice, and this is
NOT one. What that policy does is DELEGATE detection to the consumers' §9
wire-parity tests — and it therefore assumes this repo keeps §9 TRUE.

It did not. Measured (`.protogen/research/exp-e2-proto-breaking.md`): renumber
`cmd.Root.protocol_version` 1 -> 40 and the §9 G1 ping encodes
`28 02 50 03 e2 01 00 c0 02 01` instead of the documented
`08 01 28 02 50 03 e2 01 00`. Every gate in the repo stayed green, because the
literal byte string lives ONLY in that markdown file and no test or code read
it. A consumer that then bumped its pin would go red against a contract already
silently invalidated upstream; one that did not bump would stay
wire-incompatible with every peer that did.

This script closes that: it DERIVES the §9 vectors from the descriptor set and
asserts the documented bytes reproduce them, so a renumber makes the CONTRACT
RED instead of making it quietly false. It is the tripwire the existing policy
already assumed existed.

ASSERT, DO NOT GENERATE
-----------------------
The doc is deliberately not machine-generated from the descriptor. Generating it
would make it always true, which destroys the tripwire — exactly the failure
mode E2 measured in `generated-projection`, whose "was STALE ... regenerated in
place; review and commit it" auto-fix is green on the very next run. The doc
must go RED and stay red until a human decides what the fleet does about it.

WHAT MAKES A FINDING, AND WHAT DOES NOT
---------------------------------------
  RED   a renumber, a type change, an enum-value change, a changed validate
        constraint, or a hand-edit to the doc that no longer matches the proto.
  RED   a field appended to a message whose COMPLETE field set the doc pins
        (§5 `ser.OsdClientMetadata`, §5 `ser.JonOpaquePayload`) — the doc says
        both stacks define the field set from that proto, so an undocumented
        field makes that claim false.
  GREEN a field appended at a free number to a message where the doc pins only
        a PATH through it (§6 pins `cmd.Root`'s four ping-path fields, never
        its full field set). An additive change there does not move the ping
        encoding and is not a finding.

That asymmetry is the point. A check that reds for additive changes too is a
freshness diff, not a compatibility tripwire.

AN EXTRACTOR THAT FINDS NOTHING IS A FAILURE, NOT A PASS
--------------------------------------------------------
Every doc extractor below declares how many items it must find. If the doc is
reformatted so a pattern stops matching, the run FAILS with "extractor found N,
expected >= M" rather than silently asserting nothing. A green run here means
the checks ran, not that no check could be located.

WHAT THIS DOES NOT COVER — stated out loud, never silently skipped
------------------------------------------------------------------
See NOT_COVERED below; it is printed on every run, pass or fail.

USAGE
    tools/wire_contract_check.py [--descriptors PATH] [--doc PATH] [--quiet]

Exit 0 = every assertion held. Exit 1 = at least one failed, or an extractor
came up short. Exit 2 = the inputs could not be read at all.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DESCRIPTORS = REPO_ROOT / "output" / "json-descriptors" / "descriptor-set.json"
DEFAULT_DOC = REPO_ROOT / "docs" / "INTERFACE-CONTRACTS.md"
UUID_PROTO = REPO_ROOT / "proto" / "opaque" / "osd_client_metadata.proto"
ENVELOPE_SCHEMA = REPO_ROOT / "ui-event-envelope.schema.json"

NOT_COVERED = [
    "§1 stream/datagram magic + framing — not a proto surface; no descriptor home.",
    "§2 / §9 G2 — the 25-byte codec header's BYTES are not DERIVED from anything: "
    "it is a hand-packed struct in the consumers, not a proto message, so nothing "
    "here can say the layout is right. What IS asserted is that §2's table and "
    "§9's G2 vector agree with EACH OTHER — names, order, widths, endianness, "
    "offsets, the declared units, and that G2's hex decodes to the values it "
    "claims. Two homes for one layout cannot drift; neither is checked against "
    "a producer.",
    "§2 / §9 G3 — the 26/15-byte WB/WK transport headers, same reason, and "
    "without even a second home to cross-check against.",
    "§3 OSD-state stream profiles and §9 G4 (the rate-request datagram).",
    "§4 the NDC convention — the transforms are prose formulae, and the "
    "[-1,1] validate bounds on ui.PointerEvent.x/y are descriptor-visible but "
    "NOT asserted here: §8 checks those two fields' NUMBERS only, because the "
    "doc states their bounds in §4's prose rather than in a table this can "
    "compare against.",
    "§5 the buf.validate constraints on OsdClientMetadata/JonOpaquePayload — the "
    "doc's tables have no constraint column, so there is nothing to compare "
    "against; only §6's cmd.Root table states constraints and only it is checked.",
    "§7 controls.tar layout and the etag-poll protocol.",
    "§8 the controls_* export list, CONTROLS_ABI_VERSION, the env.* import "
    "signatures and the fb-format constants — their home is renderer C source, "
    "not the descriptor set.",
    "§9 G5 envelope BYTES — only each vector's key set is checked against "
    "ui-event-envelope.schema.json; the escaping and seq semantics are the "
    "renderer's and are not asserted here.",
    "Whether the descriptor set is FRESH vs proto/. Reading a committed "
    "descriptor cannot see an unregenerated proto edit — which is why this "
    "script also runs in build-and-release.yml immediately after `make "
    "generate`, against the freshly generated set, before any consumer push.",
]

# ---------------------------------------------------------------------------
# descriptor model
# ---------------------------------------------------------------------------

# protobuf wire types, keyed by FieldDescriptorProto.Type.
_VARINT, _I64, _LEN, _I32 = 0, 1, 2, 5
WIRE_TYPE = {
    "TYPE_INT32": _VARINT, "TYPE_INT64": _VARINT, "TYPE_UINT32": _VARINT,
    "TYPE_UINT64": _VARINT, "TYPE_SINT32": _VARINT, "TYPE_SINT64": _VARINT,
    "TYPE_BOOL": _VARINT, "TYPE_ENUM": _VARINT,
    "TYPE_FIXED64": _I64, "TYPE_SFIXED64": _I64, "TYPE_DOUBLE": _I64,
    "TYPE_FIXED32": _I32, "TYPE_SFIXED32": _I32, "TYPE_FLOAT": _I32,
    "TYPE_STRING": _LEN, "TYPE_BYTES": _LEN, "TYPE_MESSAGE": _LEN,
    "TYPE_GROUP": _LEN,
}
# Spellings the doc is allowed to use for each wire type. Both the label and the
# parenthesised number must agree with the descriptor.
WIRE_LABELS = {
    _VARINT: {"varint"},
    _I64: {"fixed64", "64-bit", "i64"},
    _LEN: {"length-delimited", "len", "length delimited"},
    _I32: {"fixed32", "32-bit", "i32"},
}


class Descriptors:
    """Flat index over a FileDescriptorSet rendered as JSON."""

    def __init__(self, raw: dict):
        self.messages: dict[str, dict] = {}
        self.enums: dict[str, dict[str, int]] = {}
        for f in raw.get("file", []):
            pkg = f.get("package", "")
            prefix = f"{pkg}." if pkg else ""
            for m in f.get("messageType", []):
                self._add_message(prefix, m)
            for e in f.get("enumType", []):
                self._add_enum(prefix + e["name"], e)

    def _add_message(self, prefix: str, m: dict) -> None:
        fq = prefix + m["name"]
        self.messages[fq] = m
        for n in m.get("nestedType", []):
            self._add_message(fq + ".", n)
        for e in m.get("enumType", []):
            self._add_enum(f"{fq}.{e['name']}", e)

    def _add_enum(self, fq: str, e: dict) -> None:
        self.enums[fq] = {v["name"]: v.get("number", 0) for v in e.get("value", [])}

    def message(self, fq: str) -> dict:
        if fq not in self.messages:
            raise KeyError(f"message {fq} is not in the descriptor set")
        return self.messages[fq]

    def field(self, fq: str, name: str) -> dict | None:
        for fl in self.message(fq).get("field", []):
            if fl["name"] == name:
                return fl
        return None

    def field_names(self, fq: str) -> list[str]:
        return [fl["name"] for fl in self.message(fq).get("field", [])]

    def enum_value(self, enum_fq: str, short: str) -> tuple[str, int]:
        """Resolve a doc's short variant name (LOCAL_NETWORK) to (full, number).

        Matching is by suffix so the doc can keep using the readable tail of a
        prefixed variant. An ambiguous suffix is an error, never a first-match.
        """
        if enum_fq not in self.enums:
            raise KeyError(f"enum {enum_fq} is not in the descriptor set")
        hits = [n for n in self.enums[enum_fq]
                if n == short or n.endswith("_" + short)]
        if len(hits) != 1:
            raise KeyError(
                f"{enum_fq}: short name {short!r} resolved to {len(hits)} variants "
                f"({hits}) — expected exactly 1")
        return hits[0], self.enums[enum_fq][hits[0]]


# ---------------------------------------------------------------------------
# buf.validate constraint canonicalisation
# ---------------------------------------------------------------------------

def _snake(s: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", s).lower()


def _scalar(v) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    if isinstance(v, list):
        return "[" + ",".join(_scalar(x) for x in v) + "]"
    return str(v)


def constraint_tokens(field: dict) -> set[str]:
    """The field's buf.validate rules as canonical `key:value` / `key` tokens.

    A boolean-true rule (`defined_only`, `required`) becomes a bare token,
    matching how the doc writes it.
    """
    opts = (field.get("options") or {}).get("[buf.validate.field]")
    if not opts:
        return set()
    out: set[str] = set()
    for key, val in opts.items():
        rules = val if isinstance(val, dict) else {key: val}
        for rk, rv in rules.items():
            if rv is True:
                out.add(_snake(rk))
            else:
                out.add(f"{_snake(rk)}:{_scalar(rv)}")
    return out


# ---------------------------------------------------------------------------
# encoder — derives the §9 vectors from the descriptor
# ---------------------------------------------------------------------------

def _varint(n: int) -> bytes:
    if n < 0:
        raise ValueError(f"negative varint {n} is not supported by this encoder")
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def encode_message(desc: Descriptors, fq: str, assignments: dict[str, str]) -> bytes:
    """Encode `fq{...}` in canonical field-number order from the descriptor.

    Only the value shapes the §9 vectors actually use are supported — an
    unsigned integer literal, and `{}` for an empty submessage. Anything else
    raises, so an unhandled vector shape is a loud failure rather than a skip.
    """
    parts: list[tuple[int, bytes]] = []
    for name, literal in assignments.items():
        fl = desc.field(fq, name)
        if fl is None:
            raise KeyError(f"{fq} has no field named {name!r}")
        num, ftype = fl["number"], fl["type"]
        wt = WIRE_TYPE[ftype]
        tag = _varint((num << 3) | wt)
        if literal == "{}":
            if ftype != "TYPE_MESSAGE":
                raise ValueError(f"{fq}.{name}: `{{}}` given for non-message {ftype}")
            sub = desc.message(fl["typeName"].lstrip("."))
            if sub.get("field"):
                raise ValueError(
                    f"{fq}.{name}: `{{}}` assumes an EMPTY submessage but "
                    f"{fl['typeName']} has {len(sub['field'])} field(s)")
            parts.append((num, tag + _varint(0)))
        elif re.fullmatch(r"\d+", literal):
            if wt != _VARINT:
                raise ValueError(
                    f"{fq}.{name}: integer literal given for {ftype} "
                    f"(wire type {wt}) — this encoder only writes varints")
            parts.append((num, tag + _varint(int(literal))))
        else:
            raise ValueError(f"{fq}.{name}: unsupported literal {literal!r}")
    parts.sort(key=lambda p: p[0])
    return b"".join(p[1] for p in parts)


def hexs(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b)


def parse_hex(s: str) -> bytes:
    return bytes(int(t, 16) for t in s.split())


def decode_field_numbers(b: bytes) -> list[int]:
    """Field numbers present in a flat encoded message, in wire order."""
    out, i = [], 0
    while i < len(b):
        key, i = _read_varint(b, i)
        num, wt = key >> 3, key & 7
        out.append(num)
        if wt == _VARINT:
            _, i = _read_varint(b, i)
        elif wt == _I64:
            i += 8
        elif wt == _I32:
            i += 4
        elif wt == _LEN:
            ln, i = _read_varint(b, i)
            i += ln
        else:
            raise ValueError(f"wire type {wt} at offset {i} is not decodable")
    return out


def _read_varint(b: bytes, i: int) -> tuple[int, int]:
    val, shift = 0, 0
    while True:
        if i >= len(b):
            raise ValueError("varint ran off the end of the buffer")
        byte = b[i]
        i += 1
        val |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return val, i
        shift += 7


# ---------------------------------------------------------------------------
# doc parsing
# ---------------------------------------------------------------------------

FENCE = re.compile(r"^```[^\n]*\n(.*?)^```", re.S | re.M)


def sections(doc: str) -> dict[str, str]:
    """`## N. Title` -> body text, keyed by the bare section number."""
    out, marks = {}, list(re.finditer(r"^## (\d+)\. .*$", doc, re.M))
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(doc)
        out[m.group(1)] = doc[m.end():end]
    return out


def fenced(text: str) -> list[tuple[int, str]]:
    """(offset, body) for each fenced block, offset = the fence's start."""
    return [(m.start(), m.group(1)) for m in FENCE.finditer(text)]


def table_rows(text: str, header_contains: str) -> list[list[str]]:
    """Cells of every body row of the first markdown table whose header row
    contains `header_contains`. Returns [] if no such table is found; callers
    assert on the count."""
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if ln.lstrip().startswith("|") and header_contains in ln:
            if i + 1 >= len(lines) or not re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
                continue
            rows = []
            for body in lines[i + 2:]:
                if not body.lstrip().startswith("|"):
                    break
                rows.append([c.strip() for c in body.strip().strip("|").split("|")])
            return rows
    return []


BACKTICKED = re.compile(r"`([^`]+)`")


def head_token(cell: str) -> str:
    """The cell's leading type token: the first backticked span if the cell
    starts with one, else the first bare word."""
    c = cell.strip()
    if c.startswith("`"):
        m = BACKTICKED.match(c)
        if m:
            return m.group(1).strip()
    return c.split()[0].strip("*_,;") if c.split() else ""


def tail_tokens(cell: str) -> set[str]:
    """Comma-split contents of every backticked span AFTER the leading one —
    how the doc writes a field's validate constraints."""
    spans = BACKTICKED.findall(cell.strip())
    if cell.strip().startswith("`"):
        spans = spans[1:]
    out: set[str] = set()
    for s in spans:
        for tok in s.split(","):
            tok = tok.strip().replace(" ", "")
            if tok:
                out.add(tok)
    return out


def braced(text: str, opener: str) -> str | None:
    """The brace-balanced body following `opener` (e.g. `PointerEvent{`)."""
    start = text.find(opener)
    if start < 0:
        return None
    i = start + len(opener)
    depth = 1
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + len(opener):i]
        i += 1
    return None


# ---------------------------------------------------------------------------
# result accumulator
# ---------------------------------------------------------------------------

class Report:
    def __init__(self) -> None:
        self.passed: list[str] = []
        self.failed: list[tuple[str, str]] = []

    def check(self, name: str, cond: bool, detail: str = "") -> bool:
        if cond:
            self.passed.append(name)
        else:
            self.failed.append((name, detail))
        return cond

    def eq(self, name: str, expected, actual) -> bool:
        return self.check(
            name, expected == actual,
            f"descriptor says {expected!r}, doc says {actual!r}")

    def fail(self, name: str, detail: str) -> None:
        self.failed.append((name, detail))

    def found(self, name: str, n: int, minimum: int) -> bool:
        """An extractor that comes up short is a FINDING, never a quiet pass."""
        return self.check(
            name, n >= minimum,
            f"extractor found {n}, expected >= {minimum} — the doc's shape "
            f"changed and this check asserted nothing")

    def cell_int(self, name: str, cell: str) -> int | None:
        """A table cell that should hold a field number. A malformed one is a
        finding with a readable message, never a traceback out of the gate."""
        try:
            return int(cell.strip().strip("`*_ "))
        except ValueError:
            self.fail(name, f"expected a field number, got {cell!r}")
            return None


# ---------------------------------------------------------------------------
# checks
# ---------------------------------------------------------------------------

def check_ping_table(d: Descriptors, s6: str, r: Report) -> None:
    """§6 — the cmd.Root ping-path table: numbers, types, validate constraints.

    Subset-correct by design: §6 pins a PATH through cmd.Root, not its whole
    field set, so an additive field elsewhere in cmd.Root is not a finding here.
    """
    rows = table_rows(s6, "Value for ping")
    if not r.found("§6 ping-path table rows", len(rows), 4):
        return
    for cells in rows:
        if len(cells) < 3:
            r.fail("§6 ping-path table row", f"malformed row {cells!r}")
            continue
        name = head_token(cells[0])
        fl = d.field("cmd.Root", name)
        if fl is None:
            r.fail(f"§6 cmd.Root.{name} exists", "no such field in the descriptor")
            continue
        num = r.cell_int(f"§6 cmd.Root.{name} number", cells[1])
        if num is not None:
            r.eq(f"§6 cmd.Root.{name} number", fl["number"], num)
        want = fl["typeName"].lstrip(".") if fl["type"] in ("TYPE_MESSAGE", "TYPE_ENUM") \
            else fl["type"].removeprefix("TYPE_").lower()
        got = head_token(cells[2])
        r.check(
            f"§6 cmd.Root.{name} type",
            got == want or got == want.rsplit(".", 1)[-1],
            f"descriptor says {want!r}, doc says {got!r}")
        r.eq(f"§6 cmd.Root.{name} validate constraints",
             constraint_tokens(fl), tail_tokens(cells[2]))
    # cmd.Ping must be empty or the `e2 01 00` body length of 0 is wrong.
    r.eq("§6 cmd.Ping is an empty message", [], d.field_names("cmd.Ping"))


def check_identity_table(d: Descriptors, s6: str, r: Report) -> set[tuple[int, int]]:
    """§6 — the per-client identity table, resolved through the descriptor."""
    rows = table_rows(s6, "client_type")
    if not r.found("§6 identity table rows", len(rows), 2):
        return set()
    pairs: set[tuple[int, int]] = set()
    for cells in rows:
        if len(cells) < 3:
            r.fail("§6 identity table row", f"malformed row {cells!r}")
            continue
        client, nums = cells[0], []
        for cell, field_name in ((cells[1], "client_type"), (cells[2], "client_app")):
            spans = BACKTICKED.findall(cell)
            if len(spans) != 2:
                r.fail(f"§6 identity {client}/{field_name}",
                       f"expected `N` (`VARIANT`), got {cell!r}")
                nums.append(None)
                continue
            stated, short = int(spans[0]), spans[1]
            fl = d.field("cmd.Root", field_name)
            try:
                full, actual = d.enum_value(fl["typeName"].lstrip("."), short)
            except KeyError as exc:
                r.fail(f"§6 identity {client}/{field_name}", str(exc))
                nums.append(None)
                continue
            r.eq(f"§6 identity {client}/{field_name} = {short} ({full})",
                 actual, stated)
            nums.append(actual)
        if all(n is not None for n in nums):
            pairs.add(tuple(nums))
    return pairs


SPEC = re.compile(
    r"^\s*([A-Za-z_][\w.]*)\{(.*?)\}\s*\n\s*=\s*((?:[0-9a-f]{2}\s+)*[0-9a-f]{2})"
    r"\s*(?:\((\d+)\s*bytes?\))?\s*$",
    re.M | re.S)


def spec_assignments(args: str) -> dict[str, str]:
    """The field assignments in a §9 spec's argument list."""
    out: dict[str, str] = {}
    for part in args.split(","):
        k, _, v = part.partition("=")
        out[k.strip()] = v.strip()
    return out


def spec_key(fq: str, assignments: dict[str, str]) -> str:
    """The key `derived` is stored under. ONE home, because check_repeated_blocks
    must rebuild the identical key to look a vector up by its G-label; two
    hand-rolled copies of this formatting would diverge silently and the lookup
    would simply start missing, which reads as "no vector for this label"."""
    return f"{fq}{{{', '.join(f'{k}={v}' for k, v in assignments.items())}}}"


def check_golden_specs(d: Descriptors, s9: str, r: Report) -> dict[str, bytes]:
    """§9 — DERIVE each `Msg{...} = <bytes>` vector and assert the doc's bytes.

    This is the assertion the whole script exists for: the bytes come out of the
    descriptor, so a renumber moves them and the doc stops matching.
    """
    derived: dict[str, bytes] = {}
    specs = [m for _, body in fenced(s9) for m in [SPEC.match(body.strip())] if m]
    if not r.found("§9 spec-carrying golden vectors", len(specs), 2):
        return derived
    for m in specs:
        fq, args, doc_hex, stated_len = m.group(1), m.group(2), m.group(3), m.group(4)
        assignments = spec_assignments(args)
        label = spec_key(fq, assignments)
        try:
            want = encode_message(d, fq, assignments)
        except (KeyError, ValueError) as exc:
            r.fail(f"§9 derive {label}", str(exc))
            continue
        r.eq(f"§9 {label} bytes", hexs(want), " ".join(doc_hex.split()))
        if stated_len is not None:
            r.eq(f"§9 {label} stated length", len(want), int(stated_len))
        derived[label] = want
    return derived


def check_repeated_blocks(derived: dict[str, bytes], doc: str, r: Report) -> None:
    """§6 repeats §9's G1/G1-B bytes, and both sections repeat the CW-framed
    form. Every copy must equal a derived vector — two copies of one constant
    is itself a drift surface."""
    if not derived:
        return
    framed = {hexs(len(v).to_bytes(4, "little") + v) for v in derived.values()}
    bare = {hexs(v) for v in derived.values()}

    labelled = list(re.finditer(r"\*\*(G1(?:-B)?) —", doc))
    r.found("§6/§9 G1 labelled blocks", len(labelled), 4)
    # THE LABEL MUST BE LOAD-BEARING, NOT DECORATION. This used to assert
    # membership — `got in bare` — while putting the G-label only in the
    # assertion's NAME. That tests "this block is SOME derived vector" and never
    # "the block under the G1 label is G1", so §6's two copies could be swapped
    # for each other, or one replaced by the other's bytes, and the run stayed
    # green with a byte-identical assertion report. §9 was pinned; §6 — the
    # copies a human reads and encodes against — was pinned by nothing.
    #
    # PASS 1 binds each label to the ONE vector its §9 spec derives.
    by_label: dict[str, bytes] = {}
    for m in labelled:
        blocks = fenced(doc[m.end():m.end() + 900])
        if not blocks:
            continue
        sm = SPEC.match(blocks[0][1].strip())
        if not sm:
            continue
        want = derived.get(spec_key(sm.group(1), spec_assignments(sm.group(2))))
        if want is not None:
            by_label.setdefault(m.group(1), want)

    # PASS 2 asserts every non-spec copy equals ITS OWN label's vector.
    copies: dict[str, int] = {}
    for m in labelled:
        blocks = fenced(doc[m.end():m.end() + 900])
        if not blocks:
            r.fail(f"{m.group(1)} block after label", "no fenced block follows")
            continue
        body = blocks[0][1].strip().splitlines()[0]
        if SPEC.match(blocks[0][1].strip()):
            continue  # the §9 spec form; already asserted by check_golden_specs
        label = m.group(1)
        copies[label] = copies.get(label, 0) + 1
        got = " ".join(re.sub(r"\(.*", "", body).split())
        want = by_label.get(label)
        if want is None:
            r.fail(f"{label} inline copy is pinned",
                   f"no §9 spec block defines {label}, so nothing derives what this copy must equal")
            continue
        r.eq(f"{label} inline copy reproduces the {label} vector", got, hexs(want))

    # AND A DELETED COPY MUST NOT PASS EITHER. Equality alone cannot see an
    # absent block: removing §6's copy outright simply leaves fewer things to
    # compare, which is green. The labelled-block floor above does not catch it
    # either, because it counts §9's spec blocks in the same population.
    for label in sorted(by_label):
        r.check(f"{label} still has an inline §6 copy", copies.get(label, 0) >= 1,
                f"§9 derives {label} but no non-spec block repeats it — the human-readable "
                f"copy was removed, and equality checks alone cannot see an absence")

    cw = list(re.finditer(r"Length-(?:prefixed|framed) on the `CW` stream", doc))
    r.found("CW length-framed blocks", len(cw), 2)
    for m in cw:
        blocks = fenced(doc[m.end():m.end() + 400])
        if not blocks:
            r.fail("CW-framed block", "no fenced block follows the marker")
            continue
        got = " ".join(blocks[0][1].split())
        r.check("CW-framed copy reproduces a derived vector + LE length",
                got in framed, f"doc has {got!r}; derived framed forms are {sorted(framed)}")


def check_byte_bullets(d: Descriptors, s6: str, r: Report) -> None:
    """§6's byte-by-byte gloss on G1: ``08 01`` — field 1 (`protocol_version`).

    Each bullet is checked twice — that the named field still HAS that number,
    and that the quoted tag byte still ENCODES that number and wire type. The
    second is what a renumber moves, and it moves independently of the table.
    """
    flat = re.sub(r"\s+", " ", s6)
    bullets = re.findall(r"`((?:[0-9a-f]{2} )*[0-9a-f]{2})` — field (\d+) \(`(\w+)`\)", flat)
    if not r.found("§6 byte-by-byte bullets", len(bullets), 4):
        return
    for hex_text, stated_num, name in bullets:
        stated_num = int(stated_num)
        fl = d.field("cmd.Root", name)
        if fl is None:
            r.fail(f"§6 byte gloss cmd.Root.{name}", "no such field in the descriptor")
            continue
        r.eq(f"§6 byte gloss: cmd.Root.{name} number", fl["number"], stated_num)
        try:
            key, _ = _read_varint(parse_hex(hex_text), 0)
        except ValueError as exc:
            r.fail(f"§6 byte gloss: {hex_text} is a tag", str(exc))
            continue
        r.eq(f"§6 byte gloss: `{hex_text}` encodes cmd.Root.{name}'s tag",
             (fl["number"] << 3) | WIRE_TYPE[fl["type"]], key)


def check_negative_vector(d: Descriptors, s6: str, derived: dict[str, bytes], r: Report) -> None:
    """§6's negative vector must still decode to the fields the prose names, and
    must still DIFFER from the positive one (or it stops being a negative test)."""
    m = re.search(r"\(WRONG[^)]*\)", s6)
    if not r.found("§6 negative vector marker", 1 if m else 0, 1):
        return
    line_start = s6.rfind("\n", 0, m.start()) + 1
    hex_text = s6[line_start:m.start()].strip()
    if not re.fullmatch(r"(?:[0-9a-f]{2}\s+)*[0-9a-f]{2}", hex_text):
        r.fail("§6 negative vector bytes", f"unparseable hex {hex_text!r}")
        return
    raw = parse_hex(hex_text)
    nums = set(decode_field_numbers(raw))
    r.check("§6 negative vector still differs from every derived vector",
            all(raw != v for v in derived.values()),
            "the negative vector now EQUALS a positive one — it no longer tests anything")
    # `which decodes as: ... field 2 (`session_id`) ... field 8 (`state_time`)`.
    # Flattened first: the claims wrap across lines in the source markdown, and
    # an unflattened regex silently dropped one of them.
    tail = re.sub(r"\s+", " ", s6[m.end():m.end() + 600])
    claims = [(int(n), nm) for n, nm in re.findall(r"field (\d+) \(`(\w+)`\)", tail)
              if int(n) in nums]
    if not r.found("§6 negative vector field claims", len(claims), 3):
        return
    for num, claimed in claims:
        fl = next((f for f in d.message("cmd.Root")["field"] if f["number"] == num), None)
        r.eq(f"§6 negative vector: cmd.Root field {num} name",
             fl["name"] if fl else None, claimed)


def check_tag_arithmetic(d: Descriptors, doc: str, r: Report) -> None:
    """`(28<<3)|2 = 226 = 0xe2` — verify both the arithmetic and that 28 is
    still `ping`'s real number."""
    hits = list(re.finditer(r"\((\d+)<<3\)\|(\d+)\s*=\s*(\d+)\s*=\s*0x([0-9a-f]{2})", doc))
    if not r.found("tag-arithmetic claims", len(hits), 1):
        return
    for m in hits:
        num, wt, dec, hx = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4), 16)
        want = (num << 3) | wt
        r.check(f"tag arithmetic ({num}<<3)|{wt}", want == dec == hx,
                f"({num}<<3)|{wt} = {want}, doc says {dec} = 0x{hx:02x}")
        fl = d.field("cmd.Root", "ping")
        if fl and num == 28:
            r.eq("§6 tag arithmetic uses cmd.Root.ping's real number",
                 fl["number"], num)


def check_field_set_table(d: Descriptors, text: str, fq: str, header: str,
                          r: Report) -> None:
    """A §5 table that claims a message's COMPLETE field set: number, name,
    proto type and wire type per row, and set equality on the field names."""
    rows = table_rows(text, header)
    expected = d.field_names(fq)
    # Two different findings live here and must not be conflated: NO table is an
    # extractor break, whereas a SHORT table is the doc genuinely documenting
    # fewer fields than the message has. Only the first is a reason to stop —
    # the second is named field-by-field by the COMPLETE check at the bottom,
    # which is a far more useful message than a row count.
    if not r.found(f"§5 {fq} table located", len(rows), 1):
        return
    seen = []
    for cells in rows:
        if len(cells) < 4:
            r.fail(f"§5 {fq} row", f"malformed row {cells!r}")
            continue
        name = head_token(cells[1])
        seen.append(name)
        fl = d.field(fq, name)
        if fl is None:
            r.fail(f"§5 {fq}.{name}", "documented field is not in the descriptor")
            continue
        num = r.cell_int(f"§5 {fq}.{name} number", cells[0])
        if num is not None:
            r.eq(f"§5 {fq}.{name} number", fl["number"], num)
        want = fl["typeName"].lstrip(".") if fl["type"] in ("TYPE_MESSAGE", "TYPE_ENUM") \
            else fl["type"].removeprefix("TYPE_").lower()
        got = head_token(cells[2])
        r.check(f"§5 {fq}.{name} proto type",
                got == want or got == want.rsplit(".", 1)[-1],
                f"descriptor says {want!r}, doc says {got!r}")
        wt = WIRE_TYPE[fl["type"]]
        cell = cells[3].lower()
        n = re.search(r"\((\d)\)", cell)
        r.check(f"§5 {fq}.{name} wire type",
                n is not None and int(n.group(1)) == wt
                and any(lbl in cell for lbl in WIRE_LABELS[wt]),
                f"descriptor wire type is {wt} ({sorted(WIRE_LABELS[wt])}), "
                f"doc says {cells[3]!r}")
    r.eq(f"§5 {fq} documented field set is COMPLETE", expected, seen)


def check_opaque_payload_wrapping(d: Descriptors, s5: str, r: Report) -> None:
    """§5's `JonGUIState.opaque_payloads` is field 8, repeated, tag byte 0x42."""
    m = re.search(r"\*\*field (\d+), repeated\*\*.*?tag byte `0x([0-9a-f]{2})`", s5, re.S)
    if not r.found("§5 opaque_payloads wrapping claim", 1 if m else 0, 1):
        return
    stated_num, stated_tag = int(m.group(1)), int(m.group(2), 16)
    fl = d.field("ser.JonGUIState", "opaque_payloads")
    if fl is None:
        r.fail("§5 ser.JonGUIState.opaque_payloads", "field is gone from the descriptor")
        return
    r.eq("§5 opaque_payloads field number", fl["number"], stated_num)
    r.eq("§5 opaque_payloads is repeated", "LABEL_REPEATED", fl.get("label"))
    r.eq("§5 opaque_payloads element type", ".ser.JonOpaquePayload", fl.get("typeName"))
    r.eq("§5 opaque_payloads tag byte", (fl["number"] << 3) | _LEN, stated_tag)


def check_uuid(s5: str, r: Report) -> None:
    """§5's type_uuid constant against its one home in proto/."""
    pat = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    doc_hits = set(re.findall(pat, s5))
    if not r.found("§5 UUID constant", len(doc_hits), 1):
        return
    try:
        proto_hits = set(re.findall(pat, UUID_PROTO.read_text()))
    except OSError as exc:
        r.fail("§5 UUID vs proto comment", f"cannot read {UUID_PROTO}: {exc}")
        return
    if not r.found("proto UUID comment", len(proto_hits), 1):
        return
    r.eq(f"§5 UUID matches {UUID_PROTO.relative_to(REPO_ROOT)}", proto_hits, doc_hits)


def check_ui_input(d: Descriptors, s8: str, r: Report) -> None:
    """§8 says the HostToWasm/WasmToHost field numbers and enum values are
    protocol facts fixed by proto/ui/ui_input.proto. Assert exactly that."""
    checked = 0
    for msg in ("HostToWasm", "PointerEvent", "WasmToHost"):
        body = braced(s8, msg + "{")
        if body is None:
            r.fail(f"§8 {msg} field list", "no `{...}` span found in §8")
            continue
        for name, num in re.findall(r"(\w+)=(\d+)", body):
            fl = d.field(f"ui.{msg}", name)
            if fl is None:
                r.fail(f"§8 ui.{msg}.{name}", "documented field is not in the descriptor")
                continue
            r.eq(f"§8 ui.{msg}.{name} number", fl["number"], int(num))
            checked += 1
    r.found("§8 ui_input field-number claims", checked, 12)

    enum_claims = 0
    for m in re.finditer(r"`(PointerPhase|PointerKind|ThemeMode|CursorType)`"
                         r"((?:\s*[A-Z_]+=\d+/?)+)", s8):
        enum_fq = "ui." + m.group(1)
        for short, num in re.findall(r"([A-Z_]+)=(\d+)", m.group(2)):
            try:
                full, actual = d.enum_value(enum_fq, short)
            except KeyError as exc:
                r.fail(f"§8 {enum_fq}.{short}", str(exc))
                continue
            r.eq(f"§8 {enum_fq}.{short} ({full})", actual, int(num))
            enum_claims += 1
    r.found("§8 ui_input enum-value claims", enum_claims, 15)


def check_g5_envelopes(s9: str, r: Report) -> None:
    """§9 G5 — each envelope vector's key set against the published schema.

    The BYTES are the renderer's to produce and are not derived here; what is
    asserted is that the vectors and ui-event-envelope.schema.json agree on the
    closed key set, which is the drift this repo can actually see.
    """
    try:
        schema = json.loads(ENVELOPE_SCHEMA.read_text())
    except OSError as exc:
        r.fail("§9 G5 schema", f"cannot read {ENVELOPE_SCHEMA}: {exc}")
        return
    r.check("G5 schema is a closed map (additionalProperties:false)",
            schema.get("additionalProperties") is False,
            f"schema says additionalProperties={schema.get('additionalProperties')!r} "
            f"— §8 calls the envelope a CLOSED map whose unknown keys REJECT")
    allowed, required = set(schema.get("properties", {})), set(schema.get("required", []))
    vectors = []
    for _, body in fenced(s9):
        body = body.strip()
        if body.startswith("{") and body.endswith("}"):
            try:
                vectors.append(json.loads(body))
            except json.JSONDecodeError as exc:
                r.fail("§9 G5 vector", f"not valid JSON: {exc}: {body[:60]!r}")
    if not r.found("§9 G5 envelope vectors", len(vectors), 3):
        return
    for v in vectors:
        tag = v.get("tag", "?")
        keys = set(v)
        r.check(f"G5 {tag!r} declares no key the schema forbids",
                keys <= allowed, f"unknown keys {sorted(keys - allowed)}")
        r.check(f"G5 {tag!r} carries every required key",
                required <= keys, f"missing {sorted(required - keys)}")


# ---------------------------------------------------------------------------
# §2 / §9 G2 — the codec header's TWO HOMES, held against each other
# ---------------------------------------------------------------------------
#
# WHY THIS IS A DIFFERENT KIND OF CHECK FROM EVERYTHING ABOVE, AND SAYS SO.
# Every other check here DERIVES its expectation from the descriptor set, so a
# green means "the doc matches the protos". This one cannot: the 25-byte codec
# header is hand-packed at both ends and has no descriptor home at all. What it
# asserts instead is that the layout's TWO written homes — §2's table and §9's
# G2 vector — agree with each other. That is a real drift surface (the same one
# check_repeated_blocks exists for, one section further on), and it is the only
# one available here. It is NOT evidence that either home matches a producer,
# and NOT_COVERED above says so in those words rather than dropping the entry.
#
# WHAT IT BUYS THAT PROSE DOES NOT. §2.1 declares the time fields' units. A unit
# is not in the bytes, so no check can validate it against anything — but it CAN
# be made undroppable: the Unit column must exist and be populated, a field
# named `<x>_ns` must carry unit `ns` (and likewise `_us` / `_ms` / `_s`), and
# G2's per-field labels must equal §2's field names in order. Delete the units,
# contradict them between the two homes, or quietly rename back to a bare `pts`,
# and the run goes red naming the clause. That is what turns the §2.1 decision
# from advisory into binding.

# Byte width per type token, for the LE codec header. Not a general table: the
# transport headers below §2's first one are BE and carry a `uint24`, and they
# are deliberately outside this check (they have no second home to compare
# against, so there is nothing to hold them to).
_WIDTHS = {"uint8": 1, "uint16": 2, "uint32": 4, "uint64": 8}

# The unit tokens a time-valued row may declare. `unspecified` is a FIRST-CLASS
# value, not a hole: §2.1 uses it for `system_time`, whose unit genuinely is not
# known, and the whole point is that a reader can tell "nobody wrote it down"
# from "it is nanoseconds". A blank cell is a finding; `unspecified` is not.
_UNITS = {"ns", "us", "ms", "s", "unspecified"}

_G2_LINE = re.compile(
    r"^\s*((?:[0-9a-f]{2}\s+)*[0-9a-f]{2})\s+#\s*([A-Za-z_]\w*)\s*=\s*(\d+)", re.M)


def _codec_table(s2: str, r: Report) -> list[dict] | None:
    """§2's codec-header table -> field rows, or None if it cannot be read.

    Keyed on the `Unit` column, which ONLY this table has — so the selector is
    also the assertion that the unit declaration still exists. Reformat §2 so
    the column vanishes and this returns nothing, which `found` turns into a
    finding rather than a vacuous pass.
    """
    rows = table_rows(s2, "Unit")
    if not r.found("§2 codec-header table rows", len(rows), 5):
        return None
    fields, terminator, offset = [], None, 0
    for cells in rows:
        if len(cells) != 5:
            r.fail("§2 codec-header row shape",
                   f"expected 5 cells (Offset|Field|Type|Endianness|Unit), got "
                   f"{len(cells)}: {cells!r}")
            return None
        off_cell, name_cell, type_cell, end_cell, unit_cell = cells
        off = r.cell_int("§2 codec-header row offset", off_cell)
        if off is None:
            return None
        # A FIELD row names its field in a backticked span; the trailing
        # "codec bitstream begins" row does not. Discriminating on the backtick
        # rather than on the row's position means a row inserted in the middle
        # is caught by the offset arithmetic below instead of silently becoming
        # the terminator.
        if not name_cell.strip().startswith("`"):
            terminator = off
            continue
        if terminator is not None:
            r.fail("§2 codec-header terminator is last",
                   f"a field row follows the bitstream-start row at offset {off}")
            return None
        width = _WIDTHS.get(head_token(type_cell))
        if width is None:
            r.fail("§2 codec-header field type",
                   f"{head_token(name_cell)!r}: unknown type token "
                   f"{head_token(type_cell)!r}; known: {sorted(_WIDTHS)}")
            return None
        fields.append({"name": head_token(name_cell), "offset": off, "width": width,
                       "endian": end_cell.strip(), "unit": unit_cell.strip(),
                       "declared": offset})
        offset += width
    if not r.found("§2 codec-header fields", len(fields), 4):
        return None
    if not r.check("§2 codec-header declares where the bitstream starts",
                   terminator is not None,
                   "no non-field row — the table never says the header ends"):
        return None
    for f in fields:
        r.eq(f"§2 {f['name']} offset", f["declared"], f["offset"])
        # Endianness is load-bearing on a multi-byte field and meaningless on a
        # single byte; a `LE` on a uint8 would be noise a reader could mistake
        # for a claim, so both directions are asserted.
        want = "LE" if f["width"] > 1 else "—"
        r.eq(f"§2 {f['name']} endianness", want, f["endian"])
        if f["width"] > 1:
            r.check(f"§2 {f['name']} declares a unit",
                    f["unit"] in _UNITS,
                    f"unit cell is {f['unit']!r}; expected one of "
                    f"{sorted(_UNITS)} — an undeclared unit on a time-valued "
                    f"uint64 is what §2.1 exists to prevent")
        else:
            r.eq(f"§2 {f['name']} carries no unit", "—", f["unit"])
        # THE NAME AND THE COLUMN ARE TWO HOMES FOR ONE FACT. §2.1 renames a
        # time field to carry its unit, so the suffix and the cell must agree or
        # one of them is lying to whoever reads only the other.
        suffix = f["name"].rsplit("_", 1)[-1]
        if suffix in _UNITS - {"unspecified"} and "_" in f["name"]:
            r.eq(f"§2 {f['name']} name suffix matches its Unit column",
                 suffix, f["unit"])
    r.eq("§2 codec-header total width", offset, terminator)
    return fields


def check_codec_header(s2: str, s9: str, r: Report) -> None:
    """§2's codec-header table against §9's G2 vector — the layout's two homes."""
    fields = _codec_table(s2, r)
    if fields is None:
        return

    m = re.search(r"\*\*G2 — ", s9)
    if not r.check("§9 G2 block located", m is not None,
                   "no '**G2 — ' label in §9; the vector this cross-checks "
                   "against could not be found"):
        return
    blocks = fenced(s9[m.start():])
    if not r.found("§9 G2 fenced blocks", len(blocks), 2):
        return
    annotated, flattened = blocks[0][1], blocks[1][1]

    lines = _G2_LINE.findall(annotated)
    if not r.found("§9 G2 annotated field lines", len(lines), len(fields)):
        return
    r.eq("§9 G2 field names and order match §2's table",
         [f["name"] for f in fields], [name for _, name, _ in lines])

    packed = b""
    for f, (hex_bytes, name, value) in zip(fields, lines):
        raw = parse_hex(hex_bytes)
        packed += raw
        r.eq(f"§9 G2 {name} byte count matches §2's {f['name']} width",
             f["width"], len(raw))
        # LE decode, because §2 says LE and this check just asserted it. A
        # BE-encoded vector under an LE table would otherwise read as fine for
        # every value that is a palindrome — including 0 and 1, which is what a
        # golden vector is most likely to contain.
        r.eq(f"§9 G2 {name} decodes little-endian to its stated value",
             int(value), int.from_bytes(raw, "little"))

    flat = parse_hex(" ".join(flattened.split()))
    r.eq("§9 G2 flattened bytes equal the annotated lines concatenated",
         hexs(packed), hexs(flat))
    total = sum(f["width"] for f in fields)
    r.eq("§9 G2 flattened length equals §2's declared header size", total, len(flat))

    # The prose right under the vector restates the same three numbers a THIRD
    # time. Left unchecked it is the copy most likely to rot, because it reads
    # as commentary rather than as contract.
    prose = s9[m.start():m.start() + 2600]
    for label, pat, want in (
            ("G2 prose byte count", r"\((\d+) bytes;", total),
            ("G2 prose keyframe byte index", r"byte (\d+) = ", fields[-1]["offset"]),
            ("G2 prose bitstream offset", r"offset (\d+)\.\)", total)):
        pm = re.search(pat, prose)
        if r.check(f"§9 {label} located", pm is not None,
                   f"the sentence under G2 no longer states it as /{pat}/"):
            r.eq(f"§9 {label}", want, int(pm.group(1)))


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--descriptors", type=Path, default=DEFAULT_DESCRIPTORS,
                    help="FileDescriptorSet rendered as JSON (default: the "
                         "committed output/json-descriptors/descriptor-set.json)")
    ap.add_argument("--doc", type=Path, default=DEFAULT_DOC)
    ap.add_argument("--quiet", action="store_true",
                    help="print only failures and the summary")
    args = ap.parse_args()

    try:
        raw = json.loads(args.descriptors.read_text())
        doc = args.doc.read_text()
    except (OSError, json.JSONDecodeError) as exc:
        print(f"FATAL: cannot read inputs: {exc}", file=sys.stderr)
        return 2

    d = Descriptors(raw)
    s = sections(doc)
    r = Report()
    for num in ("2", "5", "6", "8", "9"):
        if num not in s:
            r.fail(f"§{num} located in the doc", "no `## N.` heading matched")
    s2, s5, s6, s8, s9 = (s.get(n, "") for n in ("2", "5", "6", "8", "9"))

    check_ping_table(d, s6, r)
    identity = check_identity_table(d, s6, r)
    derived = check_golden_specs(d, s9, r)

    # The §9 specs and the §6 identity table must describe the same clients.
    spec_pairs = set()
    for label, val in derived.items():
        m = re.match(r"cmd\.Root\{(.*)\}$", label)
        if not m:
            continue
        kv = dict(p.strip().split("=", 1) for p in m.group(1).split(",") if "=" in p)
        if "client_type" in kv and "client_app" in kv:
            spec_pairs.add((int(kv["client_type"]), int(kv["client_app"])))
    if identity and spec_pairs:
        r.eq("§6 identity table and §9 vectors describe the same clients",
             identity, spec_pairs)

    check_repeated_blocks(derived, doc, r)
    check_byte_bullets(d, s6, r)
    check_negative_vector(d, s6, derived, r)
    check_tag_arithmetic(d, doc, r)
    check_field_set_table(d, s5, "ser.OsdClientMetadata", "Wire type", r)
    # §5 carries two same-shaped tables; the second is scoped by its own
    # heading. A missing marker is a finding, not a slice from index -1 that
    # would silently re-check the FIRST table and report a pass for the second.
    wrap = s5.find("Opaque-payload wrapping")
    if r.check("§5 opaque-payload wrapping heading", wrap >= 0,
               "the 'Opaque-payload wrapping' marker is gone — the "
               "ser.JonOpaquePayload table could not be located"):
        check_field_set_table(d, s5[wrap:], "ser.JonOpaquePayload", "Wire type", r)
    check_opaque_payload_wrapping(d, s5, r)
    check_uuid(s5, r)
    check_ui_input(d, s8, r)
    check_g5_envelopes(s9, r)
    check_codec_header(s2, s9, r)

    if not args.quiet:
        for name in r.passed:
            print(f"  ok   {name}")
    for name, detail in r.failed:
        print(f"  FAIL {name}: {detail}")

    print()
    print("NOT COVERED by this check (an unjudged surface is stated, never skipped):")
    for line in NOT_COVERED:
        print(f"  - {line}")
    print()
    print(f"descriptors: {args.descriptors}")
    print(f"doc:         {args.doc}")
    if r.failed:
        print(f"wire-contract check: RED — {len(r.failed)} failed, "
              f"{len(r.passed)} passed")
        print("docs/INTERFACE-CONTRACTS.md no longer describes the protos it "
              "claims to. Fix the DOC to match the descriptor (and bump every "
              "consumer pin in lockstep), or revert the proto change.")
        return 1
    print(f"wire-contract check: GREEN — {len(r.passed)} assertions held")
    return 0


if __name__ == "__main__":
    sys.exit(main())
