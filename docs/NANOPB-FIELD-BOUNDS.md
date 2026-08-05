# nanopb field bounds — the three shapes, per family, measured

> **PUBLIC-REPO GUARDRAIL.** This document describes generated C *layout* and the
> allocation behaviour of the pinned nanopb generator. It names no deployment,
> no host, and no operator path.

This is a DECISION DOCUMENT. It changes no `.proto` and no `.options` file. It
exists so that the question *"should the frozen wire families carry nanopb
bounds?"* is answered from measurement rather than re-derived each time it is
asked, and so that the answer states which fields sit at an untrusted edge and
which do not.

Everything numeric below was produced by running the pinned toolchain
(`Dockerfile.base`, via [`tools/uber.sh`](../tools/uber.sh)) against this
checkout. §9 is the reproduction recipe for every figure.

---

## 0. The one-paragraph answer

[`proto/ui/ui_ast.options`](../proto/ui/ui_ast.options) is the only nanopb
options file in the repository, so
**every `string`/`bytes` field outside `proto/ui/` generates as an unbounded
`pb_callback_t`** — 42 of them — while all 24 in `ui_ast` are bounded. That is
the situation, and it is confirmed by measurement rather than by reading the
tree layout.

What follows from measuring it is narrower than "add bounds":

- The **command family carries no `string` or `bytes` field at all.** There is
  nothing there to bound. `cmd.Root` reaches string data only through
  `opaque_payloads`, which is `ser.JonOpaquePayload`.
- The **state message's own layout is already insulated.**
  `sizeof(ser_JonGUIState)` does not move under ANY of the bound hypotheses
  measured here, because every string it can reach sits behind an indirection
  that is already there.
- **`FT_POINTER` exists, works, and is already used in this repository** — and
  it does not merely leave `sizeof` unmoved, it *shrinks* it relative to the
  callback shape it replaces, because `pb_callback_t` is two words and a pointer
  is one. Measured over all 362 generated structs: 13 change size on wasm32 and
  23 on x86-64, and not one of them grows.
- **`FT_POINTER` is not a bound.** `max_size` beside it is silently ignored, and
  a 100 000-byte string decodes into a field declared `max_size:16` with no
  error. What actually bounds a pointer decode is `pb_istream_t.bytes_left`.
- The bounds worth taking are the ones `buf.validate` **already declares**, on
  identifier-shaped fields, where the honest ceiling is exact and the cost is
  tens of bytes. The bounds NOT worth taking are the bulk ones, where the same
  discipline costs 64 KiB per struct instance.

---

## 1. The inventory, measured

**42 unbounded `string`/`bytes` fields**, all outside `proto/ui/`; **24 bounded**,
all inside it. There are also **19 unbounded repeated non-string fields** outside
`ui` (arrays of message/uint32/uint64/float), which are the same generator
mechanism and are covered in §7 where they matter.

Counting note: the `.pb.h` scan finds 70 `pb_callback_t` declarations, every one
of them in a file the current generator produces. A raw scan of `output/c` is
only safe to quote while that holds, and nothing asserts it — see §8.1.

| field | type | `buf.validate` today |
|---|---|---|
| **`proto/jon_can_stream.proto`** | | |
| `jon.can.CANFrame.data` #5 | bytes | `max_len 64` |
| `jon.can.CANStreamConnected.streams` #1 | string | — |
| **`proto/jon_client_logs.proto`** | | |
| `jon.logs.ClientLogEntry.lvl` #1 | string | `in` error/warn/info/debug |
| `jon.logs.ClientLogEntry.mod` #2 | string | `min_len 1` |
| `jon.logs.ClientLogEntry.msg` #3 | string | `min_len 1` |
| `jon.logs.ClientLogEntry.file` #5 | string | — |
| `jon.logs.ClientLogEntry.sid` #7 | string | `min_len 1` |
| `jon.logs.ClientLogEntry.ua` #8 | string | — |
| `jon.logs.ClientLogEntry.url` #9 | string | — |
| `jon.logs.ClientLogEntry.origin` #10 | string | — |
| `jon.logs.ClientLogEntry.commit` #11 | string | — |
| `jon.logs.ClientLogEntry.build` #12 | string | — |
| `jon.logs.ClientLogEntry.lang` #16 | string | — |
| `jon.logs.ClientLogEntry.tz` #17 | string | — |
| `jon.logs.ClientLogEntry.extra` #18 | string | — |
| `jon.logs.ClientLogEntry.state_snapshot` #19 | bytes | — |
| **`proto/jon_shared_data_types.proto`** | | |
| `ser.JonGuiDataTrackedObject.uuid` #1 | string | `len 36`, `pattern` |
| `ser.JonOpaquePayload.type_uuid` #1 | string | `pattern` |
| `ser.JonOpaquePayload.payload` #3 | bytes | `min_len 1` |
| **`proto/jon_sych_archive.proto`** | | |
| `jon.archive.ArchiveEntry.path` #1 | string | `min_len 1` |
| `jon.archive.OSDReference.package_path` #1 | string | `min_len 1` |
| `jon.archive.OSDReference.config_path` #2 | string | `min_len 1` |
| `jon.archive.OSDReference.package_name` #3 | string | `min_len 1` |
| `jon.archive.OSDReference.package_version` #4 | string | `min_len 1` |
| `jon.archive.OSDReference.package_variant` #5 | string | `in` recording_day |
| `jon.archive.SychArchiveIndex.exported_from` #3 | string | `min_len 1` |
| `jon.archive.VideoEntry.id` #1 | string | `min_len 1` |
| `jon.archive.VideoEntry.archive_path` #2 | string | `min_len 1` |
| `jon.archive.VideoEntry.thumbnail_path` #3 | string | — |
| **`proto/jon_video_meta.proto`** | | |
| `jon.video.VideoError.uuid` #1 | string | — |
| `jon.video.VideoError.storage_path` #2 | string | — |
| `jon.video.VideoError.error_message` #4 | string | — |
| `jon.video.VideoIdList.uuids` #1 | string | — |
| `jon.video.VideoMeta.uuid` #1 | string | — |
| `jon.video.VideoMeta.storage_path` #4 | string | — |
| `jon.video.VideoMeta.source_type` #5 | string | — |
| `jon.video.VideoMetaResponse.dsi` #12 | bytes | — |
| `jon.video.VideoRangeQuery.source_type` #3 | string | — |
| **`proto/opaque/sam_tracking_day.proto`** | | |
| `ser.SamTrackingDay.mask_rle` #11 | bytes | `max_len 65536` |
| **`proto/opaque/sam_tracking_heat.proto`** | | |
| `ser.SamTrackingHeat.mask_rle` #11 | bytes | `max_len 65536` |
| **`proto/opaque/trinity_tracking.proto`** | | |
| `ser.TrinityBoardVersion.family` #1 | string | `min_len 1` |
| `ser.TrinityBoardVersion.geometry_sha256` #4 | string | `pattern` |

**No `proto/jon_shared_cmd*.proto` file appears in that table, and that is not an
omission.** The command family declares zero `string` and zero `bytes` fields.

---

## 2. The three shapes, measured side by side

A single probe message — one `string`, one `bytes` — generated three ways from
the same `.proto`, differing only in its `.options`:

```
message Inner { string s = 1; bytes b = 2; }
message Outer { Inner one = 1; Inner two = 2; }
```

Compiled with `-DPB_FIELD_32BIT -DPB_ENABLE_MALLOC`, the flags
[`renderer/wasm.mk`](../renderer/wasm.mk) uses:

| `.options` for `Inner.s` / `Inner.b` | generated C | `sizeof(Inner)` wasm32 | x86-64 | `sizeof(Outer)` wasm32 | x86-64 |
|---|---|---|---|---|---|
| *(none — today's default)* | `pb_callback_t s; pb_callback_t b;` | **16** | **32** | **40** | **80** |
| `max_size:64` | `char s[64];` + `PB_BYTES_ARRAY_T(64) b;` | **132** | **132** | **272** | **272** |
| `type:FT_POINTER` | `char *s; pb_bytes_array_t *b;` | **8** | **16** | **24** | **48** |

Three things this table settles.

1. **The baseline is not zero.** A callback field already costs two words — a
   function pointer plus a `void *arg`. So the layout question is never
   "bound versus free"; it is "bound versus two words versus one word".
2. **`FT_POINTER` is SMALLER than the callback it replaces**, on both ABIs. The
   phrasing "leaves `sizeof` unmoved" understates it.
3. **The bound's cost is transitive and multiplies with embedding.** `Outer`
   embeds `Inner` twice, so the +116-byte inline cost becomes +232 there, and
   would become +232 again in anything embedding `Outer`.

### 2.1 The choice is invisible on the wire

The same message encoded through the bounded-inline binding and through the
`FT_POINTER` binding produces byte-identical output:

```
inline  encode rc=0 bytes=0a0b68656c6c6f2d776f726c641203deadbe
pointer encode rc=0 bytes=0a0b68656c6c6f2d776f726c641203deadbe
WIRE-IDENTICAL: yes
```

The generated field descriptor changes in exactly one column — the allocation
type — and nowhere else:

```
< X(a, CALLBACK, SINGULAR, BYTES,    data,              5) \
> X(a, STATIC,   SINGULAR, BYTES,    data,              5) \
```

Field number, wire type and label are untouched. **A nanopb options change is
not a wire change.** It is a C-API change, which is a different question and is
answered in §7.4.

### 2.2 What a bound actually buys: a loud refusal, and a compile-time size

A bounded field REFUSES an over-long value rather than truncating it. Measured
against `max_size:64`:

```
wire_len=64      decode_ok=1 strlen(s)=62
wire_len=65      decode_ok=1 strlen(s)=63
wire_len=66      decode_ok=0 err=string overflow
wire_len=100004  decode_ok=0 err=string overflow
```

**`max_size` on a `string` INCLUDES the NUL**; on `bytes` it does not. Measured
on the same `max_size:64` declaration: a string accepts 63 characters, a bytes
field accepts 64 (`bytes overflow` at 65). So mirroring a `buf.validate
max_len: N` needs `max_size: N+1` for a string and `max_size: N` for bytes.
Every figure in §6 uses that convention.

The bound also gives the message a compile-time size constant. Bounding
`jon.can.CANFrame.data` turns

```
/* jon_can_CANFrame_size depends on runtime parameters */
```

into

```
#define jon_can_CANFrame_size                    122
```

which is what a producer needs in order to size a static encode buffer without
guessing.

---

## 3. `FT_POINTER` — available, in use, and not a bound

`FT_POINTER = 4` is declared in the pinned generator's own option-definition
`nanopb.proto` (carried in the toolchain image, not in this repository), and
this repository already uses it: three
fields in [`proto/ui/ui_ast.options`](../proto/ui/ui_ast.options)
(`ui.WidgetNode.gestures`, `ui.EventBinding.cmd`, `ui.EventBinding.cmd_by_value`)
are `type:FT_POINTER` precisely so their bulk is malloc'd rather than placed in
the recursive decode frame.

Four measured properties decide where it may be used.

### 3.1 It requires `PB_ENABLE_MALLOC`, and the failure is at RUNTIME

Compiling the generated pointer binding without `-DPB_ENABLE_MALLOC` **succeeds**.
The decode then fails:

```
build rc=0
wire_len=10     decode_ok=0 err=no malloc support
wire_len=100004 decode_ok=0 err=no malloc support
```

The whole message fails, not just the field. `pb_decode.c`'s
`decode_pointer_field` is `PB_RETURN_ERROR(stream, "no malloc support")` under
`#ifndef PB_ENABLE_MALLOC`. So converting a field to `FT_POINTER` silently
imposes a build-flag requirement on every consumer of that binding, enforced
nowhere at build time.

### 3.2 `max_size` beside `FT_POINTER` is SILENTLY IGNORED

Declaring `type:FT_POINTER max_size:16` generates the same `char *` as
`type:FT_POINTER` alone, the generator emits no warning, and:

```
wire_len=100004 decode_ok=1 strlen(s)=100000
```

An author who writes both believes they have a bound and has none. This is the
sharpest trap in the whole area.

### 3.3 What DOES bound a pointer decode is the input stream

With a buffer-backed `pb_istream_from_buffer`, a length prefix larger than the
remaining buffer is refused **before** any allocation:

```
claimed_len=100000000  buffer_bytes=15  decode_ok=0 err=end-of-stream
claimed_len=4000000000 buffer_bytes=16  decode_ok=0 err=end-of-stream
claimed_len=64         buffer_bytes=12  decode_ok=0 err=end-of-stream
claimed_len=10         buffer_bytes=12  decode_ok=1
```

So for a buffer-backed decode the heap an attacker can command is proportional
to the message they were already allowed to deliver. nanopb's own security
document states the complementary rule for the other case: *"If using stream
input, a maximum size should be set in `pb_istream_t`"*, and *"If using
`malloc()` support, some method of limiting memory use should be employed. This
can be done by defining custom `pb_realloc()`."* Neither
[`renderer/wasm.mk`](../renderer/wasm.mk) nor the generated output defines a
custom `pb_realloc`; the default is plain `realloc`.

### 3.4 A repeated pointer field amplifies, and has a hard element ceiling

A repeated `FT_POINTER` submessage array allocates `sizeof(elem)` per element
while each empty element costs 2 wire bytes. Measured on a 32-byte element:

```
wire_bytes=20      elems=10     heap_for_array=320      amplification=16.0x
wire_bytes=2000    elems=1000   heap_for_array=32000    amplification=16.0x
wire_bytes=131070  elems=65535  heap_for_array=2097120  amplification=16.0x
wire_bytes=131072  decode_ok=0  err=too many array entries
```

The ceiling is `PB_SIZE_MAX`. With the default `pb_size_t` (`uint_least16_t`)
that is 65535 entries; **under `-DPB_FIELD_32BIT`, which the renderer sets,
`pb_size_t` is `uint32_t` and the ceiling is 2³²−1**, so the 65535 backstop is
absent exactly where malloc is enabled. Amplification is a real consideration
for `FT_POINTER` on a repeated submessage; it is not one for a singular string
or bytes field, where the allocation tracks the wire length.

---

## 4. The untrusted edge — which fields are decoded from bytes that arrived

This is the distinction the whole question turns on. An unbounded field nobody
decodes from an untrusted source is a different risk from one that is.

### 4.1 What this repository can establish first-hand

**The only C decoder IN this repository is the ui_ast reference interpreter, and
it decodes only `ui.*`.** Every `pb_decode` call site in
[`renderer/src/renderer.c`](../renderer/src/renderer.c) and
[`renderer/src/main.c`](../renderer/src/main.c) names a `ui_*_fields`
descriptor; the source contains no `ser_*_fields` reference at all. Its input is
always `pb_istream_from_buffer`, and every `ui_ast` string field is already
bounded. **So none of the 42 unbounded fields is decoded by any C code in this
repository.**

### 4.2 What it cannot

`output/c` is published to the `jettison_proto_c` binding repository by
[`.github/workflows/build-and-release.yml`](../.github/workflows/build-and-release.yml),
which is one of ten such distribution steps. **Which repositories then pin that
binding, and whether any of them runs a nanopb decoder on a socket, is not
establishable from this checkout.** Nothing here enumerates the consumers of a
downstream binding repo. That is a real limit on this analysis and §8.4 restates
it rather than papering over it.

What IS establishable is the wire DIRECTION of each family, from
[`docs/INTERFACE-CONTRACTS.md`](INTERFACE-CONTRACTS.md) and the protos, and
therefore whether the bytes cross a trust boundary in ANY language.

### 4.3 Classification

| family | direction | trust status of a decode | evidence |
|---|---|---|---|
| `ui.*` | server → client, `controls.tar` asset plane | **UNTRUSTED, and decoded in C here** | the interpreter; §4.1 |
| `ser.*` state | server → client on the `WR` stream | **UNTRUSTED for the client** | `INTERFACE-CONTRACTS.md` §1 |
| `ser.JonOpaquePayload` | BOTH — appended client-side too | **UNTRUSTED both ways** | `INTERFACE-CONTRACTS.md` §5 |
| `ser.*` opaque CV payloads | produced on-device by nanopb, injected into `JonGUIState.opaque_payloads` | encode-side in C; decode is a consumer-language concern | `docs/proto/ser.CvMeta.md` |
| `cmd.*` | client → server on the `CW` stream | **UNTRUSTED for the server** — but carries no string/bytes | `INTERFACE-CONTRACTS.md` §1; §1 above |
| `jon.logs.*` | client → server, batched | **UNTRUSTED for the server** | `ClientLogBatch` is "sent over WebSocket" |
| `jon.video.*` requests | client → server | **UNTRUSTED for the server** | `VideoMetaRequest` |
| `jon.video.*` responses | server → client | untrusted for the client | `VideoMetaResponse` |
| `jon.archive.*` | parsed out of a `.sych_video` archive FILE | **UNTRUSTED — a file-format parser** | `SychArchiveIndex` is the archive's own index |
| `jon.can.*` | device → collector | untrusted at the collector | `CANFrame` |

Two consequences worth stating plainly.

**The `ser.*` opaque payloads are a nanopb ENCODE surface on-device**, not a
decode surface: `docs/proto/ser.CvMeta.md` records that the aggregated proto "is
encoded via nanopb on the critical read path". A `max_size` on an encode-side
field bounds what the producer can emit — which is a *correctness* cap, not a
hardening one, and it is worth having for exactly that reason (a producer that
silently drops an over-long field is the failure a bound converts into a loud
one).

**`jon.logs.ClientLogEntry` is the clearest untrusted-decode case in the tree**,
and it is also the one where an honest bound is least available (§5).

---

## 5. Is an honest bound even expressible?

Three tiers. The tier decides the recommendation more than the family does.

### Tier A — the bound is ALREADY DECLARED, so mirroring it invents nothing

These fields carry a `buf.validate` constraint that fixes an exact or maximum
length today. A nanopb `max_size` mirroring it changes no contract; it makes the
C struct agree with a cap the wire already asserts.

| field | declared | honest `max_size` |
|---|---|---|
| `ser.JonGuiDataTrackedObject.uuid` | `len 36` + UUID pattern | 37 |
| `ser.JonOpaquePayload.type_uuid` | UUID pattern ⇒ 36 chars | 37 |
| `ser.TrinityBoardVersion.geometry_sha256` | `^[0-9a-f]{64}$` ⇒ 64 chars | 65 |
| `jon.can.CANFrame.data` | `max_len 64` (and CAN-FD's own payload maximum) | 64 |
| `jon.archive.OSDReference.package_variant` | `in` one 13-char value | 14 |
| `jon.logs.ClientLogEntry.lvl` | `in` four values, longest 5 | 6 |
| `ser.SamTracking{Day,Heat}.mask_rle` | `max_len 65536` | 65536 |

Note the last row is Tier A by the letter and a bad idea by measurement (§6).
Being *expressible* is not being *cheap*.

### Tier B — a bound is expressible but is a NEW wire decision

The value has an obvious shape that nothing declares. Adding a nanopb bound here
without also adding the `buf.validate` constraint puts the cap in one language's
binding and nowhere else, which is the drift this repository exists to prevent.

- `jon.video.VideoMeta.uuid`, `jon.video.VideoError.uuid` — described as UUIDs,
  with no `pattern` or `len` declared.
- `jon.video.{VideoMeta,VideoRangeQuery}.source_type` — a two-value set
  (`"day"`/`"heat"`) that lives in a comment rather than in an `in:`.
- `jon.archive.*` path fields — paths inside a tar. The archive format's own
  name field has no single ceiling once pax/GNU extensions are in play, so a
  number here is a policy choice, not a derivation.
- `jon.logs.ClientLogEntry.{mod,sid,file,commit,build,lang,tz}` — small by
  nature, undeclared in fact.
- `ser.TrinityBoardVersion.family`, `jon.can.CANStreamConnected.streams`.

**The correct move for a Tier B field is to declare the constraint in the
`.proto` first**, in a change that every language's validated output picks up,
and only then mirror it into `.options`. Doing it the other way round makes the
C binding stricter than the contract.

### Tier C — the honest bound is UNKNOWABLE, and a made-up ceiling would be worse

- `jon.logs.ClientLogEntry.msg` — free text from a client.
- `jon.logs.ClientLogEntry.extra` — documented as "optional JSON blob".
- `jon.logs.ClientLogEntry.state_snapshot` — documented in the proto as
  "Raw state snapshot bytes - NOT validated, stored as-is, decode later".
- `jon.logs.ClientLogEntry.{ua,url,origin}` — client-supplied identifiers with
  no upper bound anyone controls.
- `jon.video.VideoError.error_message` — a diagnostic string.
- `jon.video.VideoMetaResponse.dsi` — an `avcC` decoder-config blob whose size
  is a codec property.
- **`ser.JonOpaquePayload.payload`** — the extension point.
  `INTERFACE-CONTRACTS.md` §5 describes the transport as passing it through
  without interpretation, so any cap here is a cap on every payload type that
  will ever exist, chosen before those types do.

For Tier C the brief's own framing is the right answer: **a callback (or
`FT_POINTER`) with a documented allocation policy, not an invented ceiling.**
A truncating cap on a diagnostic field is a silent data loss; a refusing cap is
a dropped log line. Neither is better than an honest unbounded field whose
allocation is bounded by the input buffer.

---

## 6. What a bound costs — measured on the real protos

Two hypotheses were generated from the real `proto/` tree with the real
`scripts/proto_cleanup.awk` pipeline, into a scratch directory, and every
generated struct measured on both ABIs.

The baseline run reproduces every committed file in `output/c` **byte for byte**,
so the deltas below are attributable to the hypothesis and to nothing else.

**Hypothesis "declared"** = Tier A only: mirror the bounds `buf.validate`
already declares, including the repeated ones (`detections max_items 256`, the
three exact-count sharpness arrays, `mask_rle max_len 65536`). No new contract.

**Hypothesis "pointer"** = convert every currently-callback `string`/`bytes` and
repeated field outside `ui` to `type:FT_POINTER`.

wasm32, `-DPB_FIELD_32BIT -DPB_ENABLE_MALLOC`; only structs that moved:

| struct | today | declared | Δ | pointer | Δ |
|---|---|---|---|---|---|
| `ser_SamTrackingDay` | 176 | 65712 | **+65536** | 176 | 0 |
| `ser_SamTrackingHeat` | 176 | 65712 | **+65536** | 176 | 0 |
| `ser_ObjectDetectionsDay` | 88 | 6224 | **+6136** | 88 | 0 |
| `ser_ObjectDetectionsHeat` | 88 | 6224 | **+6136** | 88 | 0 |
| `ser_CvMeta` | 760 | 2184 | **+1424** | 760 | 0 |
| `ser_CvChannelMeta` | 88 | 800 | +712 | 88 | 0 |
| `ser_TrinityBoardVersion` | 24 | 84 | +60 | 16 | −8 |
| `cmd_CV_StartTrackTrinity` | 32 | 92 | +60 | 24 | −8 |
| `jon_can_CANFrame` | 56 | 112 | +56 | 48 | −8 |
| `cmd_CV_Root` | 48 | 104 | +56 | 48 | 0 |
| `ser_TrinityTracking` | 232 | 288 | +56 | 224 | −8 |
| `ser_JonGuiDataTrackedObject` | 200 | 224 | +24 | 192 | −8 |
| `ser_JonOpaquePayload` | 40 | 64 | +24 | 32 | −8 |
| `cmd_Root` | 152 | 168 | **+16** | 152 | 0 |
| `jon_logs_ClientLogEntry` | 144 | 144 | 0 | 88 | **−56** |
| `jon_archive_SychArchiveIndex` | 96 | 96 | 0 | 72 | −24 |
| `jon_archive_VideoEntry` | 128 | 128 | 0 | 104 | −24 |
| `jon_archive_OSDReference` | 40 | 40 | 0 | 20 | −20 |
| `jon_video_VideoMeta` | 96 | 96 | 0 | 80 | −16 |
| `jon_video_VideoError` | 28 | 28 | 0 | 16 | −12 |
| `jon_video_VideoMetaResponse` | 40 | 40 | 0 | 36 | −4 |

On x86-64 the shape is the same and the pointer savings are larger
(`jon_logs_ClientLogEntry` 256 → 144, i.e. −112).

Five readings that matter more than the individual numbers.

1. **`ser_JonGUIState` is ABSENT from that table.** 2736 bytes on wasm32, and it
   does not move under either hypothesis. Its only reach into string data is
   through `opaque_payloads` and `JonGuiDataCV.tracked_objects`, both of which
   are already an indirection — and a `pb_callback_t` and a `{count, pointer}`
   pair happen to be the same width. **The state message's layout is insulated
   from this entire question**, which removes the loudest form of the
   "a bound changes layout for every consumer" objection for the state family.
2. **`cmd_Root` moves by +16 while its arm moves by +56.** It is a `oneof` union,
   so a bound on one arm costs only the amount by which that arm overtakes the
   current dominator. Inside a union, the layout objection is usually much
   smaller than it looks.
3. **`cmd_Root` moves AT ALL, from a bound on a `ser` message.** The command
   family owns no strings, and a `ser` bound still reaches it through
   `cmd.CV.StartTrackTrinity` → `ser.TrinityBoardVersion`. Layout cost does not
   respect family boundaries.
4. **The three expensive rows are all BULK arrays, not identifiers.** 64 KiB for
   `mask_rle`, 6 KiB for a 256-detection array, 1.4 KiB for the exact-count
   sharpness arrays. These are the fields where inline bounding is wrong even
   though the bound is honest and already declared.
5. **Nothing grows under `FT_POINTER`.** Of the 362 generated structs, 13 change
   size on wasm32 and 23 on x86-64; every one of them shrinks. Twenty-one
   structs on wasm32 (25 on x86-64) are touched by one hypothesis or the other,
   which is the population the table above lists.

---

## 7. Recommendation, per family

Each carries its reasoning. Where the answer is "leave it unbounded", that is
said outright.

### 7.1 `cmd.*` — nothing to do, and the reason is structural

**No change.** The family declares no `string` and no `bytes` field. The
question does not arise. The only way a bound reaches `cmd.Root` is transitively
through `ser` (§6 reading 3), which is a reason to weigh a `ser` bound carefully
and not a reason to bound anything in `cmd`.

### 7.2 `ser.*` state — bound the two identifiers; leave the payload alone

**Bound**, Tier A, at a total measured cost of +24 bytes each to two structs
neither of which is embedded in `JonGUIState` inline:

- `ser.JonGuiDataTrackedObject.uuid` → `max_size:37` (declared `len 36`)
- `ser.JonOpaquePayload.type_uuid` → `max_size:37` (UUID pattern)

Both are dispatch keys. `type_uuid` in particular is what a consumer matches on
to decide how to interpret `payload`, so a value that cannot be represented is
better refused loudly at decode than silently mishandled. `sizeof(ser_JonGUIState)`
is unmoved (§6 reading 1).

**Leave unbounded: `ser.JonOpaquePayload.payload`.** It is the extension point;
the transport is contractually uninterpreting; a cap chosen now is a cap on
payload types that do not exist yet. Its allocation is already bounded by the
message buffer it arrives in (§3.3). If it is ever converted, `FT_POINTER` is
the shape — never an inline `max_size`, which would put the largest conceivable
payload into every `JonOpaquePayload` instance.

### 7.3 `ser.*` opaque CV payloads — `FT_POINTER`, not inline

**Do NOT inline-bound `mask_rle`, `detections`, or the sharpness arrays**, even
though all four bounds are already declared. Measured cost: +65536 bytes to
`ser_SamTrackingDay` and `ser_SamTrackingHeat`, +6136 to each detections
message, +1424 to `ser_CvMeta`. These are produced on a latency-sensitive path
into fixed buffers; a 64 KiB struct instance is not the shape that path wants.

**`ser.TrinityBoardVersion.geometry_sha256` → `max_size:65`** is the exception in
this group: a hex digest of exactly 64 characters, +60 bytes, and it is a
comparison key where a truncated value silently compares unequal.

`ser.TrinityBoardVersion.family` is Tier B — declare the constraint in the
`.proto` first if it is to be bounded at all.

### 7.4 `ui.*` — already bounded; the interesting work is elsewhere

No change is proposed. `proto/ui/ui_ast.options` already carries a fully reasoned
bound set with its stack budget measured. Its own §"nine max_size:64 bounds" note
is the model for how a bound decision should be recorded, and this document does
not restate it. See §8.2 for one measurement in that file that no longer holds.

### 7.5 `jon.logs.*` — leave unbounded; this is the Tier C case

**Leave every field unbounded.** `msg`, `extra`, `state_snapshot`, `ua`, `url`
and `origin` have no honest ceiling; the proto says so about `state_snapshot`
in as many words. This is simultaneously the family with the clearest untrusted
decode (client → server) and the family where a bound is least available, and
those two facts do not cancel: the hardening that actually applies here is a cap
on the input stream and on `ClientLogBatch.entries`, not a per-field `max_size`.

`lvl` is Tier A (`in` four values ⇒ `max_size:6`) and could be bounded on its
own; the value is small, since the `in:` constraint already refuses anything
else in every validated binding.

**If this family is converted at all, `FT_POINTER` is the shape** — it is the
largest measured win in the tree (`jon_logs_ClientLogEntry` 144 → 88 on wasm32,
256 → 144 on x86-64) and it costs no ceiling anyone has to invent. But read §3.1
before proposing it: it makes `PB_ENABLE_MALLOC` mandatory for every consumer of
that binding, and the failure mode is a runtime decode error, not a build error.

### 7.6 `jon.video.*` — leave unbounded; the bounds are Tier B

**No nanopb change now.** Every candidate here (`uuid`, `source_type`) needs its
`buf.validate` constraint declared first, in a change that reaches all ten
bindings. `dsi` is a codec blob and `SampleTable`'s arrays are proportional to
video length — a `max_count` there would cap how long a video may be, which is
the wrong contract expressed in the wrong place.

### 7.7 `jon.archive.*` — leave unbounded

**No change.** Path strings inside a tar have no derivable ceiling, and the
repeated `files` / `videos` arrays are proportional to archive contents.
`package_variant` is Tier A (`in` one value) but bounding one field of a message
whose siblings stay unbounded buys nothing.

### 7.8 `jon.can.*` — bound `CANFrame.data`

**`jon.can.CANFrame.data` → `max_size:64`.** It is the single most honest bound
available in this repository: 64 is the CAN-FD payload maximum, it is already
declared as `buf.validate max_len 64`, and the field is `bytes` so `max_size:64`
admits exactly 64 (§2.2). Measured cost: `jon_can_CANFrame` 56 → 112 on wasm32.
It also converts `jon_can_CANFrame_size` from "depends on runtime parameters"
into the constant `122`, which is what a frame collector needs to size a buffer.

`jon.can.CANStreamConnected.streams` is Tier B — leave it.

### 7.9 The precondition that outranks all of the above

**Every recommendation here is a C-API break for consumers of the `jettison_proto_c`
binding**, even though none of them is a wire break (§2.1). A field changes from
`pb_callback_t` — where the consumer installs an encode/decode function — to
`char[N]` or `char *`, and every read and write site must change. `FT_POINTER`
additionally requires `-DPB_ENABLE_MALLOC` and a matching `pb_release` discipline.

`CLAUDE.md` describes a proto change as ONE coordinated event in which every
active consumer bumps its pin, regenerates and gates in lockstep, so the fleet
already performs this shape of change. **What this analysis cannot tell you is
how many C consumers exist and what that lockstep costs them** (§4.2, §8.4).
That number, and not the layout arithmetic, is what should decide whether the
Tier A bounds above are worth taking.

---

## 8. Findings surfaced along the way

These are outside the question asked and are recorded rather than fixed, because
each lives in a directory this analysis does not own.

### 8.1 Nothing detects an ORPHANED projection under `output/`

An orphan is a tracked file under `output/` that the current generator does not
produce. Nothing regenerates one, nothing deletes one, and nothing reports one,
so it sits in a public tree looking exactly like a live artifact — and a census
over `output/` silently counts it, which is why the note under §1 says what a
raw `pb_callback_t` scan is worth.

Three mechanisms produce one, and they are worth separating because a check that
covers one covers neither of the others:

1. **The declaring `.proto` is deleted.** Every generator stamps its source, so
   this class is findable statically: read the stamp, ask whether the source
   still exists.
2. **The generator's output PATH moves.** The source is alive and the stamp
   names it correctly, so the static read calls the file healthy while a
   canonical twin at the new path is regenerated without it. The evidence is
   two files, one declared source, and a symbol in one and not the other.
3. **A MESSAGE is removed from a live `.proto`.** For the per-message emitters
   (Kotlin writes one file per message) the source and the path are both
   correct and only the message is gone. Neither of the checks above sees it.

**Only running the generator settles all three.** `tools/go_leg_repro.sh` does
exactly that for the Go leg — into a throwaway directory, comparing against
`output/go` — and already prints the committed paths that leg does not produce.
Its header states why it is in no workflow, and the argument generalises: after
`make generate` the comparison is vacuous, and before it, a proto change makes
it wrong. So the honest home for a whole-tree detector is a step INSIDE the
distribution workflow, after its own `make generate`, comparing the tracked set
against what that run actually wrote.

A cheap static version is refused rather than deferred: reading stamps alone
covers mechanism 1 and reports clean over 2 and 3, which is a pass value
indistinguishable from a nothing-ran value. It would need a canary watched to
fail on a planted input of each mechanism before it was wired at all.

### 8.2 The `sizeof(ui_WidgetNode)` figure in `proto/ui/ui_ast.options` is stale

That file records 2252 bytes on wasm32 in three places, including the stack-budget
table its nine `max_size:64` decisions rest on. Measured with the renderer's own
flags against the current headers: **2256**. The x86-64 figure (2328) still holds.

The cause is identified: `ui.WidgetNode.hit_slop` (`uint32`, field 47) was added
after the measurement was recorded, and four bytes is exactly its width. The
per-level and peak figures in that table move with it; the conclusion the table
supports (that a uniform widen to 256 overflows the 262144 stack reservation) is
not threatened by 4 bytes per instance, but the numbers no longer reproduce.

Two smaller claims in the same file also fail to resolve: it says
"the `generated-projection` lane's header records how to re-measure it", and
that header in [`renderer.mk`](../renderer.mk) contains no re-measurement
procedure, no `sizeof` and no `-fdump-record-layouts`.

### 8.3 There is no gate over any of this

Nothing in this repository asserts that a `string`/`bytes` field's nanopb shape
matches its `buf.validate` constraint, in either direction. Two checks would be
cheap and are named here for whoever owns `tools/lint/`:

- **A Tier A drift check** — for every field carrying a `buf.validate` `len`,
  `max_len` or single-valued `in:`, assert that its nanopb declaration is either
  absent (callback/pointer, deliberately) or exactly the mirrored `max_size`.
  A `max_size` that disagrees with the declared cap is a defect in either
  direction: too small refuses conforming traffic, too large admits traffic the
  contract forbids.
- **An `FT_POINTER` + `max_size` refusal** — the combination is silently
  meaningless (§3.2) and should be a hard error, not a comment.

Neither is proposed here, because this analysis owns `docs/` only. Both would
need a canary that watches them fail on a planted input before they are wired.

### 8.4 What could NOT be established

- **Whether any C nanopb decoder is exposed to untrusted bytes.** Established:
  none in this repository (§4.1). Not establishable from here: whether a
  repository pinning the `jettison_proto_c` binding runs one on a socket. This
  is the single fact that would most change the recommendations in §7, and it
  has to come from the consuming side.
- **The number of C consumers**, and therefore the true cost of the API break in
  §7.9.
- **Whether any consumer already defines a custom `pb_realloc`**, which would
  change the §3.3 analysis for `FT_POINTER` from "bounded by the input buffer"
  to "bounded by policy".
- **Runtime behaviour on the wasm32 target.** Every `sizeof` here is measured on
  wasm32 via the record layouts the pinned WASI-SDK clang emits, but every
  decode-behaviour measurement (§2.2, §3.1–3.4) was executed on x86-64. The
  toolchain image carries no standalone WASI CLI runtime (`wasmtime`, `wasmer`
  and `wasm3` are all absent from `PATH`; the harness drives wasmtime as a Rust
  library and GraalWasm from Clojure), so running the probes on the target ABI
  would have meant building a host for them. The nanopb decode paths involved
  are ABI-independent, but that is an argument, not a measurement.

---

## 9. Reproducing every figure

All of it runs from the repository root through the pinned container. The
scratch tree is disposable; nothing below writes into `proto/` or `output/`.

**The three shapes (§2).** Write a two-field probe message and three `.options`
files (empty, `max_size:64`, `type:FT_POINTER`), run
`protoc --plugin=protoc-gen-nanopb=/opt/nanopb/generator/protoc-gen-nanopb`
with `--nanopb_opt=-I<dir>` pointing at the directory holding the `.options`,
and read `sizeof` two ways: `gcc` + run for x86-64, and
`clang --target=wasm32-wasip1 -Xclang -fdump-record-layouts -c` for wasm32.
The wasm dump only emits a record it actually lays out, so declare a global of
each type — a `sizeof` inside `-fsyntax-only` is not enough.

**The real-proto hypotheses (§6).** Replicate the C leg of
[`generate-protos.sh`](../generate-protos.sh): run
[`scripts/proto_cleanup.awk`](../scripts/proto_cleanup.awk) over every
`proto/**/*.proto` except `test/`, copy the `.options` files beside them, append
the hypothesis options, and run the same `protoc` invocation into a scratch
output directory. **Assert first that the no-hypothesis run reproduces `output/c`
byte for byte** — without that control the deltas mean nothing.

**Allocation behaviour (§3).** Hand-build the wire bytes (`0x0A`, a varint
length, then the payload) rather than encoding them, so the length prefix can be
made to disagree with the buffer. Compile the generated binding against the
image's own `pb_decode.c` and `pb_common.c` with and without
`-DPB_ENABLE_MALLOC`, and read `PB_GET_ERROR` on failure.

**Read every exit status bare.** `cmd | tail` and `cmd; echo $?` after a pipeline
both report the pipeline's last command, so a failing generation step records as
success. Every figure above was taken from a bare invocation.

---

## Related

- [`docs/INTERFACE-CONTRACTS.md`](INTERFACE-CONTRACTS.md) — the wire contract
  these families speak, and the stream directions §4.3 draws on.
- [`proto/ui/ui_ast.options`](../proto/ui/ui_ast.options) — the only nanopb
  options file in the repository, and the worked example of a bound decision
  recorded with its measurement.
- [`generate-protos.sh`](../generate-protos.sh) — the C leg, including why
  `--nanopb_opt=-I` is not redundant and what its absence silently does.
