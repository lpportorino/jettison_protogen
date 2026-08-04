# Cross-Language Interface Contracts

> **PUBLIC-REPO GUARDRAIL — PROTOCOL / FORMAT / ENCODING ONLY.** This document
> describes WHAT the bytes, frames, and formats ARE — never WHERE they are
> deployed. It contains no IP addresses, hostnames, deployment port numbers,
> passwords, tokens, or operator filesystem paths. The port→stream wiring
> (which UDP port carries which stream) is deployment topology and lives in the
> consuming repos, not here. Magic bytes, proto field numbers, byte offsets, and
> relative path PATTERNS are protocol facts and ARE in scope.

This is the canonical wire contract shared by the two co-equal render targets
that consume `jettison_protogen` — the web-consumer stack (Clojure/TypeScript)
and the native-consumer stack (Rust/C) — plus the shared `controls.wasm` LVGL
renderer both embed. Each section is a contract both ends MUST agree on
byte-for-byte; each ends with a `Reference implementations:` line citing the
producing/consuming source in BOTH repos so any drift is a two-sided bug.

**This document is SEPARABLE from `UI-QUALITY-CONTRACTS.md`.** That one is the
interface-quality standard, and it binds a ui_ast SURFACE — an interface the
reference interpreter renders. This one binds anything that speaks the WIRE.
**A consumer can owe this document entirely and that one's GATES not at all**: a
client that speaks the protocol and draws its own interface, in any technology,
owes the bytes here and none of the UI gates there, because those gates read a
`dump_tree` and a framebuffer it does not produce. Read that one's scope section
anyway before concluding nothing is owed — the readability and bench obligations
it states are properties of a panel and an operator, not of a widget toolkit.

All multi-byte integers are explicitly tagged big-endian (BE) or little-endian
(LE) per field — the two stacks mix conventions deliberately (transport headers
are BE; framing length prefixes and codec headers are LE), so never assume.

---

## 1. Stream / datagram magic + framing

Every QUIC stream and datagram class is identified by a 2-byte ASCII
magic prefix. Two distinct framing families share the magic mechanism:

**Transport-header streams/datagrams (BE, versioned)** — the video plane. A
2-byte magic, a 1-byte protocol version (`5`), then a fixed BE header:

| Magic | Bytes (hex) | Carries | Header | Version byte |
|-------|-------------|---------|--------|--------------|
| `WB`  | `0x57 0x42` | Video frame datagram (delta frames, fragmented) | 26-byte BE header (§2-adjacent below) | `0x05` @ offset 2 |
| `WK`  | `0x57 0x4B` | Video keyframe reliable uni-stream | 15-byte BE header | `0x05` @ offset 2 |

**Length-framed streams (LE, magic-only, no version byte)** — the state and
control planes. A 2-byte magic, then repeated `[4-byte LE uint32 length][payload]`
records. The payload is raw protobuf (or zstd-compressed protobuf; see §3).

| Magic | Bytes (hex) | Direction | Carries |
|-------|-------------|-----------|---------|
| `WR`  | `0x57 0x52` | server → client | OSD-state reliable persistent stream — `[4B LE len][raw protobuf]*` |
| `CW`  | `0x43 0x57` | client → server | Command persistent uni-stream — `[4B LE len][cmd.* protobuf]*` |
| `CS`  | `0x43 0x53` | bidirectional | ARM-proxy "signal" bidi stream — down: SSE signal patches; up: action JSON. zstd, `[4B LE len][payload]` inside |
| `CG`  | `0x43 0x47` | bidirectional | ARM-proxy "general" bidi stream — down: SSE elements + asset responses; up: asset requests + log events. zstd, `[4B LE len][payload]` inside |

Framing rules:
- The magic is written ONCE at stream open, raw (before any zstd wrapping on
  `CS`/`CG`), then the length-framed body follows.
- The `WB`/`WK`/`WR` header magic+version is validated on every datagram/stream
  open; a wrong magic or version byte is rejected (the datagram is dropped, the
  stream is skipped).
- A bare 4-byte `PING` datagram (`0x50 0x49 0x4E 0x47`) and its `PONG` reply
  (`0x50 0x4F 0x4E 0x47`) ride the video/state planes as datagrams — they are
  NOT length-framed and carry no header. A datagram whose length is exactly 4
  and whose bytes equal `PING`/`PONG` is the keepalive, distinguished before any
  header parse.

Reference implementations:
- The web consumer and the native consumer each implement this framing in their
  own stack; the magic bytes, version, and `PING`/`PONG` above are the shared
  contract.

---

## 2. The 25-byte codec-frame header (distinct from WB/WK transport headers)

Three different headers travel on the video plane — keep them separate:

**The 25-byte codec-frame header** wraps the encoded picture itself, INSIDE a
reassembled `WB`/`WK` frame's payload. After datagram reassembly (`WB`) or
reliable-stream read (`WK`), the assembled frame begins with this 25-byte header,
then the codec bitstream. All fields LE:

| Offset | Field | Type | Endianness | Unit |
|--------|-------|------|------------|------|
| 0  | `pts_ns`      | uint64 | LE | ns |
| 8  | `duration_ns` | uint64 | LE | ns |
| 16 | `system_time` | uint64 | LE | unspecified |
| 24 | `is_keyframe` | uint8 (0/non-0) | — | — |
| 25 | codec bitstream begins | — | — | — |

The consumer strips these 25 bytes; what remains is the raw codec payload
(H.264 Annex-B NALUs, or AV1 OBUs). This header is LE — contrast the BE
transport headers below.

### 2.1 The time fields carry a UNIT, and it is part of the contract

The first three fields are `uint64` quantities whose unit the byte layout cannot
express, so this section states it. It is not advisory: a consumer that picks a
different unit is wrong against this contract, and the §9 G2 vector plus
`tools/wire_contract_check.py` hold the table and the vector in agreement so the
declaration cannot be silently dropped or contradicted.

**`pts_ns` — NANOSECONDS. This one is derivable from the protos, not asserted.**
`cmd.Root.frame_time_day` / `frame_time_heat` are documented as *"frame
timestamps (PTS) from video streams when the command was issued"*, unit
NANOSECONDS, and `ser.JonGUIState.frame_pts_day_ns` / `frame_pts_heat_ns` publish
the same quantity from the producing side — *"pipeline GStreamer buffer PTS in
nanoseconds"*. A client fills the `cmd.Root` fields from the frame it was
displaying, and this header's `pts_ns` is the ONLY per-frame timestamp the video
plane carries: neither the `WB` nor the `WK` transport header above has a time
field at all. So a consumer reading `pts_ns` in any other unit puts a wrong-unit
value into a `cmd.Root` field the producer correlates against its own
nanosecond `frame_pts_*_ns`.

**`duration_ns` — NANOSECONDS, on the evidence rather than by derivation.** It is
the presentation duration of the frame `pts_ns` timestamps: the same clock
domain, the same producer, the adjacent field of the same width. The proto
comments above name a GStreamer pipeline as the source of the PTS, and a
GStreamer buffer's PTS and DURATION are one type in one unit, so a header taking
its PTS from that buffer in nanoseconds takes its duration from it in
nanoseconds too. What is NOT available here is a measurement of the encoder, so
this is stated as a decision with a named falsifier rather than as a proof —
see the cross-check below, which any consumer can run on live frames.

**`system_time` — UNIT UNSPECIFIED, deliberately.** It is described as a
capture-side wall clock and nothing in the proto surface pins it: sibling
timestamps in this contract's protos are variously nanoseconds
(`cmd.Root.state_time`), microseconds (`ser.JonGUIState.system_monotonic_time_us`),
milliseconds (`cmd.Root.client_time_ms`) and Unix seconds
(`jon.video.VideoMeta.timestamp`). Writing a unit here would be inventing one.
A consumer MUST NOT pace, seek, or correlate on `system_time` until this row
says otherwise; use it only for opaque comparison between frames of one stream.

**Two normative consumer rules follow, and together they make a wrong reading
harmless as well as detectable:**

1. **PACE FROM `pts_ns` DELTAS, never from `duration_ns`.** `pts_ns` is the
   derived-from-the-protos field; `duration_ns` is a hint. A consumer whose
   playback clock is driven by successive `pts_ns` values cannot stall on a
   duration it misread.
2. **CROSS-CHECK THE TWO, because the check is unit-free.** They share one clock
   domain, so consecutive frames of one stream satisfy
   `pts_ns[n+1] - pts_ns[n] ≈ duration_ns[n]`. A consumer reading the two fields
   in units that differ by 10³ or 10⁶ sees this relation fail by that factor,
   which is the cheapest possible detector and needs no agreement about which
   unit is right. A consumer SHOULD assert it. If it fails on a real stream,
   `duration_ns`'s row above is what is wrong, and it is fixed HERE.

**WHY A UNIT COULD BE DECLARED AT ALL — the byte layout is FROZEN and this is
not a change to it.** The 25 bytes are written by deployed producers that cannot
be rebuilt on demand, so the offsets, widths and endianness above are as frozen
as the `ser.*` / `cmd.*` wire families even though this header belongs to
neither and is not a protobuf message. A unit is not encoded in those bytes; it
was always carried by the reader's assumption. Declaring it moves no offset,
changes no width, and leaves every byte on the wire identical — so it needs no
compatibility story, and a producer already in the field is unaffected whatever
it writes. What changes is that one of two disagreeing readers is now wrong
against a written contract instead of both being defensible.

**The field NAMES changed with the units — `pts` → `pts_ns`, `duration` →
`duration_ns` — and that is a LABEL change with no wire effect.** These names are
this document's, not any consumer's struct member names; the header is
hand-packed at both ends. The rename follows the convention the protos already
use for exactly this reason (`frame_pts_day_ns`, `system_monotonic_time_us`,
`client_time_ms`, `jon.video.VideoMeta.duration_ms`): a `uint64` that means a
time is named for its unit, so a reader cannot reach the field without reaching
the unit. `system_time` keeps its bare name precisely because its unit is not
known — the asymmetry is the signal.

**The 26-byte WB datagram header** (transport, BE, version `5`):

| Offset | Field | Type | Endianness |
|--------|-------|------|------------|
| 0..2  | magic `WB` (`0x57 0x42`) | — | — |
| 2     | version (`5`) | uint8 | — |
| 3..11 | `frame_seq` (a.k.a. frameId) | uint64 | BE |
| 11..15 | `datagram_seq` | uint32 | BE |
| 15..19 | `total_datagrams` | uint32 | BE |
| 19..22 | `payload_size` | uint24 (3 bytes) | BE |
| 22..26 | `payload_offset` | uint32 | BE |
| 26..   | payload | — | — |

**The 15-byte WK keyframe-stream header** (transport, BE, version `5`):

| Offset | Field | Type | Endianness |
|--------|-------|------|------------|
| 0..2  | magic `WK` (`0x57 0x4B`) | — | — |
| 2     | version (`5`) | uint8 | — |
| 3..11 | `frame_seq` | uint64 | BE |
| 11..15 | `frame_length` | uint32 | BE |
| 15..  | payload (`frame_length` bytes) | — | — |

The flow: `WB`/`WK` (BE transport header) → reassemble → 25-byte LE codec header
→ strip → codec bitstream. The 25-byte and 26-byte headers are easy to confuse;
the 25-byte one is LE and has no magic, the 26-byte one is BE and starts with
`WB`.

Reference implementations:
- The web consumer and the native consumer each parse these headers in their own
  stack; the field offsets + endianness above are the shared contract.

---

## 3. OSD-state stream profiles

The OSD telemetry stream (the camera state pushed to the OSD renderer) is
offered in two interchangeable PROFILES on a server-opened reliable uni-stream.
A consumer picks one profile per its decompression capability; the payloads
decode to the same `JonGUIState` protobuf.

**Profile A — uncompressed + `WR` magic** (the web/browser profile):
1. Server opens a unidirectional stream.
2. First 2 bytes: `WR` magic (`0x57 0x52`) — reliable persistent stream.
3. Then repeated `[4-byte LE uint32 length][payload]` records, each payload a
   raw (uncompressed) `JonGUIState` protobuf.

**Profile B — zstd, magic-less** (the native profile):
1. Server opens a unidirectional stream.
2. There is NO 2-byte magic prefix. The entire stream is a single continuous
   zstd stream.
3. The decompressed byte stream is repeated `[4-byte LE uint32 length][payload]`
   records, each payload a raw `JonGUIState` protobuf.

**The rate-request datagram** (client → server, both profiles): a 2-byte
datagram `[0x52, rate_hz]` — byte 0 is `0x52` (ASCII `R`), byte 1 is the desired
update rate in Hz as a single uint8. Sent once on stream setup; the server
adjusts its push cadence. (This is a DATAGRAM, distinct from the `WR` reliable
stream — and distinct from the 4-byte `PING`.)

A consumer that reads the stream MUST first peek the leading 2 bytes: if they
equal `WR` (`0x57 0x52`) it is Profile A; otherwise the stream is a zstd member
(Profile B). The `[4B LE len][payload]` inner framing is identical once past
the magic/decompression layer.

Reference implementations:
- The web consumer implements Profile A and the native consumer Profile B in
  their own stacks; the two profiles above are the shared contract. Each caps a
  single record for sanity — `MAX_MSG_SIZE = 16 MiB` (Profile A),
  `MAX_STATE_FRAME = 4 MiB` (Profile B).

---

## 4. NDC convention

Pointer/gesture coordinates and the `cmd.*` NDC fields share ONE normalized
device-coordinate convention:

- Range: `[-1.0, 1.0]` on both axes, type `double` (IEEE-754 fixed64 on the
  wire where it is a proto field).
- `+x` is RIGHT.
- `+y` is UP — a Y-FLIP relative to window/screen pixels (where +y is down).
- Out-of-range inputs are CLAMPED to `[-1.0, 1.0]` by the consumer (the renderer
  re-clamps because the host is untrusted).

The exact transforms:

```
# window pixel (px_x, px_y) in a WxH surface  →  NDC
ndc_x =  (px_x / W) * 2 - 1
ndc_y =  1 - (px_y / H) * 2          # the Y-flip

# NDC (ndc_x, ndc_y)  →  framebuffer pixel in a WxH buffer
fb_x = (ndc_x + 1) * 0.5 * W
fb_y = (1 - ndc_y) * 0.5 * H         # 1.0 (top) maps to row 0
```

Because the `ui_input` NDC convention is byte-identical to the `cmd.*` NDC
convention, an NDC `double` is written VERBATIM from the pointer channel into
the device command — no fixed-point recast, no host-side conversion. This
byte-identity is load-bearing for the golden-vector parity in §9.

Reference implementations:
- The web consumer and the native consumer both document this NDC convention
  (`[-1,1]`, +x right, +y UP); the WASM-side clamp + `ndc_to_px` flip live in
  `renderer/src/main.c` `ndc_to_px`.

### 4.1 The CV payload plane uses the OPPOSITE Y SENSE under the same name

**`+y is UP` above is scoped to the pointer/`cmd.*` plane. The CV detection and
tracking payloads declare `+y is DOWN` — `-1.0` at TOP, `+1.0` at BOTTOM — and
both planes are `double` in `[-1.0, 1.0]` called "NDC".**

Nothing on the wire distinguishes them. A `double` carrying a y value is
type-identical, range-identical and name-identical in both planes, so moving one
across without a flip yields a VERTICALLY MIRRORED box — and a mirrored box is a
plausible detection, not an error. There is no signal to catch it downstream.

Which plane a field belongs to is decided by the message it lives in, never by
its type. Derive the members rather than trusting a list here:

```
# the y-DOWN plane (CV payloads, ROI geometry)
grep -rn 'left/top' proto/
# the y-UP plane (pointer / gesture)
grep -rniE '\+y up' proto/ui/ui_input.proto
```

The transforms in §4 apply ONLY to the y-UP plane. For a y-DOWN field the
framebuffer mapping has no flip:

```
fb_y = (ndc_y + 1) * 0.5 * H         # -1.0 (top) maps to row 0
```

Two consequences a consumer must act on. A conversion helper written for one
plane is WRONG for the other and will compile, run and produce output for both —
so a shared `ndc_to_px` must take the plane as an argument or exist twice under
names that cannot be confused. And the byte-identity claim above — that an NDC
`double` is written VERBATIM from the pointer channel into the device command —
holds WITHIN the y-UP plane and does not extend across this boundary.

---

## 5. OsdClientMetadata enrichment

The state stream's `JonGUIState` is enriched client-side by appending an
`OsdClientMetadata` payload to `JonGUIState.opaque_payloads` so the OSD renderer
knows the client's canvas geometry, DPR, NDC video bounds, and theme. (The
TypeScript side names its in-memory struct `CanvasMetadata`; on the wire it is
the `ser.OsdClientMetadata` proto message — same field set.)

**`ser.OsdClientMetadata` field set** (`proto/opaque/osd_client_metadata.proto`):

| # | Field | Proto type | Wire type |
|---|-------|-----------|-----------|
| 1  | `canvas_width_px`       | uint32 | varint (0) |
| 2  | `canvas_height_px`      | uint32 | varint (0) |
| 3  | `device_pixel_ratio`    | float  | fixed32 (5) |
| 4  | `osd_buffer_width`      | uint32 | varint (0) |
| 5  | `osd_buffer_height`     | uint32 | varint (0) |
| 6  | `video_proxy_ndc_x`     | float  | fixed32 (5) |
| 7  | `video_proxy_ndc_y`     | float  | fixed32 (5) |
| 8  | `video_proxy_ndc_width` | float  | fixed32 (5) |
| 9  | `video_proxy_ndc_height`| float  | fixed32 (5) |
| 10 | `scale_factor`          | float  | fixed32 (5) |
| 11 | `is_sharp_mode`         | bool   | varint (0) |
| 12 | `theme_hue`             | float  | fixed32 (5) |
| 13 | `theme_chroma`          | float  | fixed32 (5) |
| 14 | `theme_lightness`       | float  | fixed32 (5) |

**Opaque-payload wrapping.** The encoded `OsdClientMetadata` bytes are wrapped in
a `ser.JonOpaquePayload` (`proto/jon_shared_data_types.proto`):

| # | Field | Proto type | Wire type |
|---|-------|-----------|-----------|
| 1 | `type_uuid` | string | length-delimited (2) |
| 2 | `version`   | `JonOpaquePayloadVersion` message | length-delimited (2) |
| 3 | `payload`   | bytes (the encoded `OsdClientMetadata`) | length-delimited (2) |

That `JonOpaquePayload` is then appended as `JonGUIState.opaque_payloads`
(`proto/jon_shared_data.proto`), which is **field 8, repeated** — wire type
length-delimited (2), tag byte `0x42`. Appending a repeated field is byte
concatenation: `[0x42][varint len][JonOpaquePayload bytes]` is concatenated onto
the existing `JonGUIState` buffer (or the whole state is decoded, the payload
pushed, and re-encoded).

**The UUID constant** (the `type_uuid` value identifying this payload type):

```
01941b00-0000-7000-8000-000000000001
```

**MUST be generated from the proto.** Both stacks define the field set from
`osd_client_metadata.proto`; the byte-identity contract (a payload encoded by
one stack decodes in the other) holds only if both serialize the generated
message rather than hand-rolling field order. A hand-rolled encoder is permitted
only as an optimization that EXACTLY reproduces the generated wire bytes
(field-number order, wire types, UUID); any divergence is a contract break.

Reference implementations:
- The web consumer and the native consumer each encode this payload in their own
  stack — one via a proto-mirroring hand-roll, the other via prost-generated
  `OsdClientMetadata`/`JonOpaquePayload` from `output/rust/ser.rs`; the
  generated-proto rule above applies to both.

---

## 6. Keepalive ping — the `cmd.Root` ping command

The command plane requires a periodic keepalive so the server can detect a
hung/gone client and cancel its in-flight commands. The keepalive is a full
`cmd.Root` protobuf with the `ping` payload set, sent length-framed on the `CW`
stream (`[4B LE len][cmd.Root bytes]`).

**The verified `cmd.Root` field path** (`proto/jon_shared_cmd.proto`):

| Field | # | Proto type | Value for ping |
|-------|---|-----------|----------------|
| `protocol_version` | 1  | uint32 (`gt:0, lte:2147483647`) | `1` |
| `client_type`      | 5  | `ser.JonGuiDataClientType` enum (`defined_only`, `not_in:[0]`) | per client, below |
| `client_app`       | 10 | `ser.JonGuiDataClientApp` enum (`defined_only`, `not_in:[0]`) | per client, below |
| `ping`             | 28 | `cmd.Ping` (empty message) | `{}` |

`cmd.Ping` is an empty message — its body length is 0.

**Identity values are per-client, and MUST name defined enum variants.** Both
enum fields carry `defined_only` + `not_in:[0]` constraints, so an undefined
number is a validation violation, not a tolerated unknown — a sender-side
protovalidate gate refuses to emit it. (A value that happens to equal the
field's own NUMBER is the classic hand-encoding confusion; this section's
negative-vector discipline exists to keep exactly that class of defect out of
the golden vectors.)

| Client | `client_type` | `client_app` |
|--------|---------------|--------------|
| Native desktop consumer | `2` (`LOCAL_NETWORK`) | `3` (`DESKTOP_NATIVE`) |
| Browser HUD consumer | `2` (`LOCAL_NETWORK`) | `1` (`BROWSER_UI`) |

**G1 — the native-client ping encoding:**

```
08 01 28 02 50 03 e2 01 00        (9 bytes)
```

Byte by byte:
- `08 01` — field 1 (`protocol_version`), varint, value `1`.
- `28 02` — field 5 (`client_type`), varint, value `2` (`LOCAL_NETWORK`).
- `50 03` — field 10 (`client_app`), varint, value `3` (`DESKTOP_NATIVE`).
- `e2 01 00` — field 28 (`ping`), wire type 2 (length-delimited). The tag for
  field 28 wire-type 2 is `(28<<3)|2 = 226 = 0xe2`, which needs a 2-byte varint
  `e2 01`; followed by length `00` (empty `Ping` body).

**G1-B — the browser-HUD ping encoding** (differs only in `client_app`):

```
08 01 28 02 50 01 e2 01 00        (9 bytes)
```

Length-prefixed on the `CW` stream (native shown; browser identical shape):

```
09 00 00 00 08 01 28 02 50 03 e2 01 00
```

(`09 00 00 00` = LE uint32 length 9, then the 9 ping bytes.)

**Negative vector (a former web-consumer hand-roll bug).** A consumer's
`buildPingPayload()` formerly emitted:

```
08 01 10 02 18 03 42 00            (WRONG — kept only as the negative test vector)
```

which decodes as: field 1 = `1` (correct), field 2 (`session_id`) = `2`, field
3 (`important`) = `3`, field 8 (`state_time`) as wire-type-2-length-0 — the
intended `client_type` (field 5), `client_app` (field 10), and `ping` (field 28)
were NOT set. That form has been REPLACED by the correct field-5/10/28 shape
(now G1-B for this sender); the broken bytes are retained ONLY as the
non-vacuous negative vector asserted by the web consumer's wire-parity test. A
second defect class this history teaches: the field-shape fix initially carried
enum VALUES equal to the fields' own numbers (`client_type=5`, `client_app=10`)
— undefined variants both, which `defined_only` validation rejects; the
identity table above is the correction.

Reference implementations:
- proto (authoritative): `proto/jon_shared_cmd.proto` (field numbers
  `protocol_version=1`, `client_type=5`, `client_app=10`, `ping=28`;
  `message Ping {}` empty; enum variants in `proto/jon_shared_data_types.proto`).
- The web consumer and the native consumer each build the ping in their own stack
  (G1-B and G1 respectively) and assert it byte-for-byte against the golden vector
  in their wire-parity tests.

---

## 7. `controls.tar` package format + the etag-poll protocol

The interactive controls UI ships as a tar archive fetched over the asset
plane. Two archive SHAPES exist; both are served under the relative path pattern
`/osd/{name}.tar`.

**Plain `controls.tar` (flat POSIX tar)** — the controls package:

Members (POSIX tar, 512-byte header blocks; filename in the first 100 bytes,
octal size string at header offset 124 for 11/12 bytes, data 512-aligned):
- `controls.wasm` — the LVGL/WASM renderer binary (the ABI of §8).
- `ui/{screen}.pb` — one or more UI-AST screen protobufs (`ui_ast.proto`).
- `assets/` — fonts, SVGs, and other renderer resources.

The screen-default a consumer extracts when no screen is named is
`zoom_controls` (i.e. `ui/zoom_controls.pb`).

**OSD-variant package (outer tar + manifest + inner compressed tar)** — the OSD
overlay packages (named e.g. `live_day.tar`, `live_thermal.tar`). The OUTER tar
contains:
- `manifest.jwt` — a JWT carrying build metadata (the payload is decoded for
  metadata; signature verification is not part of the read contract).
- an inner `*.tar.gz` — gzip-compressed tar whose members are:
  - `*.wasm` — the OSD WASM binary.
  - `config.json` (or `{variant}_config.json`) — variant config.
  - `resources/...` — fonts and textures.

So `controls.tar` is a FLAT tar (wasm + ui/*.pb + assets/), whereas an
OSD-variant `.tar` is a NESTED archive (outer tar → manifest.jwt + inner
.tar.gz → wasm + config + resources). A consumer distinguishes them by content:
a `manifest.jwt` / `*.tar.gz` member at the top level means OSD-variant; a
top-level `controls.wasm` + `ui/` means the flat controls package.

**The etag-poll PROTOCOL** (change detection for hot-reload). Two relative-path
endpoints per package `{name}`:

| Path pattern | Response |
|--------------|----------|
| `/osd/{name}.tar` | The package bytes. Carries `ETag` + `Last-Modified` + `Cache-Control: no-cache`. Conditional `If-None-Match` matching the current ETag → `304` (headers only, no body). |
| `/osd/{name}.tar/etag` | The **bare ETag string as the response body** (a cheap probe for transports whose responses carry no headers). |

Status contract (both endpoints):
- `200` with the resource (full bytes / bare etag body) on a hit.
- `304 Not Modified` (full `.tar` endpoint only) when `If-None-Match` equals the
  current ETag — body-less.
- `503` + `Retry-After: 5` when the package is not yet loaded (startup race) —
  the client retries, treating it as transient.
- `404` for a `{name}` not on the package whitelist (an unknown/off-whitelist
  name is rejected, not served).

**The poll floor.** Clients use an instant push channel for change
notifications, with a periodic HEAD/etag poll as the idempotent FLOOR (covering
a missed push). The poll floor cadence is **5 seconds** (the default poll
interval), debounced so a push and the next poll for the same change collapse
into a single reload.

Reference implementations:
- The web consumer extracts + repackages this archive (flat POSIX tar,
  `controls.wasm` + `ui/{screen}.pb`, screen default `zoom_controls`; the
  OSD-variant outer tar → `manifest.jwt` + inner `*.tar.gz`) and polls the etag
  (200/304/503+Retry-After:5, a HEAD-floor debounce). The native consumer loads
  `controls.wasm` + a `ui.Screen` `.pb` from `controls.tar` and runs the
  bare-etag poll (`/osd/{name}.tar/etag`, 5 s poll interval, no push channel) —
  each in its own stack.

---

## 8. `controls.wasm` ABI (reference, not redefined)

The `controls.wasm` host↔guest ABI is NOT defined here. Its authoritative home
is this repo's renderer source: `renderer/src/main.c` (the export bodies + the
export list in the file header) and `renderer/src/host_imports.h` (the imports).
This section
only LISTS the names so a consumer knows what to link; the contract (arg/return
semantics, error codes) lives at that home.

**Guest exports** (`controls_*`, from `renderer/src/main.c`):
`controls_init`, `controls_load_ui`, `controls_apply_patch`,
`controls_update_state`, `controls_host_message`, `controls_key_event`,
`controls_text_input`, `controls_get_focused_text`, `controls_tick`,
`controls_get_framebuffer`, `controls_abi_version`, `controls_fb_format`,
`controls_fb_width`, `controls_fb_height`, `controls_fb_bpp`,
`controls_set_breakpoint`, `controls_set_theme_dark`, `controls_set_theme_family`, `controls_set_dpi`,
`controls_resize`, `controls_get_dirty_rect`, `controls_get_dirty_rect_ptr`,
`controls_dump_tree`, `controls_destroy` (plus the WASI reactor `_initialize`
and the `malloc`/`free` buffer-transfer pair).

ABI self-description getters (the host validates these at load/reload before
reading the framebuffer at a stride): `controls_abi_version` (returns
`CONTROLS_ABI_VERSION`, defined in `renderer/src/main.c`; `v2` added the
`env.host_event` import to the REQUIRED import set — a WASM import is
instantiation-MANDATORY, so hosts link it BEFORE the module can load at all;
`v3` added the `controls_set_theme_family` export; `v4` CLASSIFIED
`controls_load_ui`'s nonzero status — `-1` now means specifically ABORTED (the
decode stopped mid-stream, the tree is truncated, and the module has torn the
screen down itself) and `-2` means DEFECTIVE (the tree decoded whole and is
still rendering; one or more nodes are degraded, canonically a duplicate uid).
A host treating any nonzero as "failed" remains correct across this bump; a
host that wants to distinguish "show the operator nothing" from "show it,
flagged" gates on `>= 4` to know the distinction is real rather than assumed.
The codes are `LOAD_ERR_*` in `renderer/src/renderer.h`),
`controls_fb_format` (`1` = `RGBA8888`, memory byte order
`framebuffer[i*4+0]`=R), `controls_fb_width`/`controls_fb_height`,
`controls_fb_bpp` (`4`). All are plain `u32` returns (no i64/BigInt).

**Host imports** (`env.*`, from `renderer/src/host_imports.h`):
- `env.host_command(ptr, len) -> i32` — relays OPAQUE `cmd.*` bytes to the host
  (the host forwards to the command plane WITHOUT decoding).
- `env.host_report(ptr, len) -> i32` — relays a `ui.WasmToHost` hover/cursor
  report (the host DECODES this — see §below).
- `env.host_proxy_report(id, id_len, phase, mode, x, y, w, h, z, flags) -> i32`
  — the host-proxy positioning-geometry stream (rect in framebuffer px).
- `env.host_event(ptr, len) -> i32` (ABI ≥ 2) — the named-event lane: one
  UTF-8 JSON **UI Event Envelope v1** per fired `EventBinding` with a nonempty
  `name` whose host-relay gate is open (no subject mutation, or `notify_host`).
  ADDITIVE beside `host_command` (cmd bytes first, unchanged). The envelope is
  the CLOSED map `{"v":1,"tag":<EventBinding.name>,"origin":<widget uid>,
  "event":<trigger>,"seq":<per-instance monotonic>,"value":<int>}` — schema:
  [`ui-event-envelope.schema.json`](../ui-event-envelope.schema.json) (repo
  root, the language-neutral validator source); golden vectors: §9 G5. Hosts
  validate at their membrane (unknown keys REJECT); consumers PARSE envelopes
  as JSON — byte comparison is only for EMITTER parity tests (§9).

The pointer/lifecycle channel into the WASM is `ui.HostToWasm` (the host encodes
it and calls `controls_host_message`); the feedback channel out is
`ui.WasmToHost` (the WASM encodes it and calls `env.host_report`). Field
numbers + enum values for both are protocol facts and are fixed by
`proto/ui/ui_input.proto`: `HostToWasm{ version=1; oneof event { PointerEvent
pointer=2 | Lifecycle lifecycle=3 } }`; `PointerEvent{ phase=1, kind=2,
pointer_id=3, x=4 (fixed64 double), y=5 (fixed64 double), event_time=6 }`;
`WasmToHost{ version=1; oneof report { HoverState hover=2 | CursorRequest
cursor=3 } }`. Enums: `PointerPhase` DOWN=1/MOVE=2/UP=3/CANCEL=4; `PointerKind`
MOUSE=1/TOUCH=2/PEN=3; `ThemeMode` LIGHT=1/DARK=2; `CursorType`
DEFAULT=1/POINTER=2/TEXT=3/GRAB=4/RESIZE=5/NOT_ALLOWED=6. The channel schema
version is `1` and is fail-fast checked (no migration branch).

Reference implementations:
- the renderer (authoritative ABI home, in this repo): `renderer/src/main.c`
  (export bodies + `CONTROLS_ABI_VERSION`/`CONTROLS_FB_FMT_RGBA8888`),
  `renderer/src/host_imports.h`
  (`host_command`/`host_report`/`host_proxy_report` `import_module("env")`).
- proto: `proto/ui/ui_input.proto` (the `HostToWasm`/`WasmToHost` field numbers
  + enum values above).
- The native consumer links these `env.*` imports and calls the `controls_*`
  exports, and encodes `HostToWasm` / decodes `WasmToHost`, in its own stack; the
  web consumer does the same in its own stack.

---

## 9. Golden vectors (anti-drift)

Canonical secret-free byte samples each repo's wire-parity test asserts its
encoder reproduces. These are the minimum set; both stacks MUST round-trip them
byte-for-byte.

**G1 — `cmd.Root` keepalive ping, native client** (§6). The encoder MUST
produce exactly:

```
cmd.Root{protocol_version=1, client_type=2, client_app=3, ping={}}
= 08 01 28 02 50 03 e2 01 00          (9 bytes)
```

**G1-B — the browser-HUD variant** (§6; `client_app=1 BROWSER_UI`):

```
cmd.Root{protocol_version=1, client_type=2, client_app=1, ping={}}
= 08 01 28 02 50 01 e2 01 00          (9 bytes)
```

Length-framed on the `CW` stream (native shown):

```
09 00 00 00 08 01 28 02 50 03 e2 01 00
```

**G2 — 25-byte codec-frame header** (§2). For
`pts_ns=1`, `duration_ns=2`, `system_time=3`, `is_keyframe=1` (all LE), the
header is:

```
01 00 00 00 00 00 00 00   # pts_ns      = 1   (u64 LE)
02 00 00 00 00 00 00 00   # duration_ns = 2   (u64 LE)
03 00 00 00 00 00 00 00   # system_time = 3   (u64 LE)
01                        # is_keyframe = 1
```

**These values pin the ENCODING and are not a sample of realistic ones.** Under
§2.1's declared unit `duration_ns=2` is two nanoseconds, which no producer emits;
that is deliberate and must not be "corrected". A golden vector's job is to make
a field's offset, width and endianness reproducible byte-for-byte, and every
consumer's wire-parity test asserts these exact bytes — so re-minting them to
look plausible would force a code change in every consumer and buy nothing about
the encoding. Read a realistic frame duration out of §2.1's cross-check rule
instead.

flattened:

```
01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00 03 00 00 00 00 00 00 00 01
```

(25 bytes; byte 24 = `0x01` keyframe flag; the codec bitstream follows at
offset 25.)

**G3 — `WB` datagram header** (§2, transport, BE). For `frame_seq=42`,
`datagram_seq=0`, `total_datagrams=3`, `payload_size=2922`, `payload_offset=0`,
version `5`:

```
57 42                    # magic "WB"
05                       # version 5
00 00 00 00 00 00 00 2a  # frame_seq=42        (u64 BE)
00 00 00 00              # datagram_seq=0       (u32 BE)
00 00 00 03              # total_datagrams=3    (u32 BE)
00 0b 6a                 # payload_size=2922    (u24 BE)
00 00 00 00              # payload_offset=0     (u32 BE)
```

flattened (26 bytes):

```
57 42 05 00 00 00 00 00 00 00 2a 00 00 00 00 00 00 00 03 00 0b 6a 00 00 00 00
```

**G4 — the rate-request datagram** (§3): `[0x52, rate_hz]`, e.g. for 20 Hz:

```
52 14
```

**G5 — UI Event Envelope v1** (§8 `env.host_event`; schema
`ui-event-envelope.schema.json`). Envelope EMITTERS must reproduce these
bytes exactly (field order + spelling pinned); envelope CONSUMERS parse as
JSON and never byte-compare. All are single-line UTF-8, no trailing newline,
`len` excludes any NUL.

**G5-A — plain named click** (`EventBinding{name:"fireMission", int_value:7}`
on the widget with uid 42; the instance's first envelope):

```
{"v":1,"tag":"fireMission","origin":42,"event":"clicked","seq":1,"value":7}
```

**G5-B — hostile-name escaping** (name bytes `ev"il\tag` + LF + 0x01 + `end`):
quote → `\"`, backslash → `\\`, control chars → lowercase `\u00xx`, all as
literal escape sequences in the envelope bytes:

```
{"v":1,"tag":"ev\"il\\tag\u000a\u0001end","origin":42,"event":"clicked","seq":1,"value":0}
```

**G5-C — the kebab-case trigger spelling pin** (a VALUE_CHANGED binding, the
instance's second envelope; `value-changed`/`long-pressed` are kebab-case —
never `value_changed`):

```
{"v":1,"tag":"volume.set","origin":7,"event":"value-changed","seq":2,"value":55}
```

Reference implementations:
- The web consumer and the native consumer each assert G1-G4 byte-for-byte in
  their own wire-parity test suite (G3 uses exactly `frame_seq=42,
  total_datagrams=3, payload_size=2922`).

---

## 10. Evolving this contract (anti-drift)

These are CROSS-LANGUAGE wire surfaces: every encoder/decoder of them lives in
two repos and must agree byte-for-byte. Three mechanisms keep them from
drifting — and only the third one lives in THIS repo.

1. **Generate, don't hand-roll.** Encoders should be built from the pinned proto
   (`prost` in Rust, the generated ts-proto in TypeScript). A hand-roll is
   permitted ONLY as an optimization that EXACTLY reproduces the generated wire
   bytes (e.g. §5 enrichment, §6 ping) — and then only behind a parity test.

2. **Each consumer asserts the §9 golden vectors in a wire-parity test** that
   decodes its own encoder output against the pinned generated proto and asserts
   the §9 bytes:
   - Each consumer asserts G1 (ping) and §5 (enrichment) in its own wire-parity
     test suite.
   Touch a wire encoder ⇒ the parity test guards it; this contract + the §9
   vectors are the source of truth.

3. **This repo asserts the vectors above against the generated descriptor set**
   — `tools/wire_contract_check.py`, run by `make -f lint.mk wire-contract`.
   Mechanism 2 delegates detection to the consumers, which only works if the
   vectors here are TRUE; for a long time nothing checked that, and a renumber
   of `cmd.Root.protocol_version` moved the G1 encoding while every gate in this
   repo stayed green. The checker re-derives §9's G1/G1-B bytes by encoding each
   vector's stated message from the descriptor, and compares §5's/§6's/§8's
   field numbers, proto types, wire types, enum values and (for §6) validate
   constraints against it.

   **It ASSERTS; it does not GENERATE this document.** Generating the vectors
   would make them always true and destroy the tripwire — the drift would move
   silently into the consumers instead of stopping here.

   **It is not a compatibility gate, and does not try to be.** Renumbering stays
   allowed exactly as the loop below says. What it refuses is shipping a
   renumber with a stale contract. So an additive field at a free number in
   `cmd.Root` is GREEN (this doc pins a PATH through that message, not its field
   set), while an additive field in `ser.OsdClientMetadata` is RED, because §5
   claims that message's COMPLETE field set and an undocumented field makes the
   claim false.

   **What it cannot see**: anything with no descriptor home — §1 framing, §2's
   codec/transport headers and their G2/G3 vectors, §3's profiles and G4, §7's
   package format, §8's `controls_*` export list and ABI constants, and G5's
   envelope bytes (only each vector's key set is checked, against
   `ui-event-envelope.schema.json`). Those remain mechanism 2's alone, and the
   checker prints that list on every run, green or red.

   **Read that printed list as a FLOOR, not a total — one gap is missing from
   it.** Only the two ping vectors as stated in §9 are re-derived from the
   descriptor and compared byte-for-byte; §9 is their pinned home. §6 repeats
   those same bytes for a human reader, and the checker tests each §6 copy only
   for MEMBERSHIP in the set of derived vectors — never for which label it sits
   under. Measured: give §6's native-client block the browser-HUD bytes, or the
   reverse, or delete a §6 copy outright, and the run stays green with a
   byte-identical assertion report; the same corruption inside §9 goes red and
   names the vector. So a §6 copy that contradicts §9 is drift this repo does
   NOT catch. When either moves, re-read §6 against §9 by hand — and treat the
   `inline copy reproduces a derived vector` lines in the checker's output as
   asserting less than their wording suggests.

   It runs in `.github/workflows/wire-contract.yml` (push + PR, against the
   committed descriptors) and — the leg that matters — as a step of
   `build-and-release.yml` immediately after `make generate` and before the
   first consumer push, where the descriptors are FRESH and nothing has shipped
   yet. `.githooks/pre-push` runs it locally.

**The evolution loop** — when a proto change touches a surface named here: edit
`proto/`, regenerate the bindings, update this doc to match, bump the
`jettison_protogen` submodule pin in BOTH consumers (web + native) in
lockstep, and rebuild. Renumbering is allowed (no compat shims — both consumers
rebuild together); the §9 parity tests fail loudly on any divergence, and
mechanism 3 blocks the fan-out until the "update this doc to match" beat is
actually done.
