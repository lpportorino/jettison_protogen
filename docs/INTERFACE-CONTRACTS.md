# Cross-Language Interface Contracts

> **PUBLIC-REPO GUARDRAIL — PROTOCOL / FORMAT / ENCODING ONLY.** This document
> describes WHAT the bytes, frames, and formats ARE — never WHERE they are
> deployed. It contains no IP addresses, hostnames, deployment port numbers,
> passwords, tokens, or operator filesystem paths. The port→stream wiring
> (which UDP port carries which stream) is deployment topology and lives in the
> consuming repos, not here. Magic bytes, proto field numbers, byte offsets, and
> relative path PATTERNS are protocol facts and ARE in scope.

This is the canonical wire contract shared by the two co-equal render targets
that consume `jettison_protogen` — the Clojure/TypeScript web stack (datasart)
and the Rust/C native stack (tauri) — plus the shared `controls.wasm` LVGL
renderer both embed. Each section is a contract both ends MUST agree on
byte-for-byte; each ends with a `Reference implementations:` line citing the
producing/consuming source in BOTH repos so any drift is a two-sided bug.

All multi-byte integers are explicitly tagged big-endian (BE) or little-endian
(LE) per field — the two stacks mix conventions deliberately (transport headers
are BE; framing length prefixes and codec headers are LE), so never assume.

---

## 1. Stream / datagram magic + framing

Every WebTransport stream and datagram class is identified by a 2-byte ASCII
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
- datasart: `ts/video/ingress-protocol.ts` (`WB` magic + version + `PING`),
  `ts/osd/state-worker.ts` (`WR` magic `0x57 0x52`, `[4B LE len]` framing),
  `ts/controls/cmd-sender.ts` (`CW` magic `0x43 0x57` + `[4B LE len]`).
- tauri: `jettison_wt_client/src/protocol/mod.rs` (`WB_MAGIC`/`WK_MAGIC`,
  `PROTOCOL_VERSION = 5`, `PING`/`PONG`), `jettison_wt_client/src/session/command.rs`
  (`CMD_STREAM_MAGIC = [0x43, 0x57]` + `[4B LE len]`),
  `jettison_wt_client/src/session/arm_proxy.rs` (`SIGNAL_MAGIC = [0x43,0x53]` "CS",
  `GENERAL_MAGIC = [0x43,0x47]` "CG").

---

## 2. The 25-byte codec-frame header (distinct from WB/WK transport headers)

Three different headers travel on the video plane — keep them separate:

**The 25-byte codec-frame header** wraps the encoded picture itself, INSIDE a
reassembled `WB`/`WK` frame's payload. After datagram reassembly (`WB`) or
reliable-stream read (`WK`), the assembled frame begins with this 25-byte header,
then the codec bitstream. All fields LE:

| Offset | Field | Type | Endianness |
|--------|-------|------|------------|
| 0  | `pts`         | uint64 | LE |
| 8  | `duration`    | uint64 | LE |
| 16 | `system_time` | uint64 | LE (capture-side wall clock) |
| 24 | `is_keyframe` | uint8 (0/non-0) | — |
| 25 | codec bitstream begins | — | — |

The consumer strips these 25 bytes; what remains is the raw codec payload
(H.264 Annex-B NALUs, or AV1 OBUs). This header is LE — contrast the BE
transport headers below.

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
- datasart: `ts/video/frame-protocol.ts` (`FRAME_HEADER_SIZE = 25`, `pts`@0,
  `duration`@8, `systemTime`@16, `isKeyframe`@24, all `getBigUint64(..., true)`
  = LE), `ts/video/ingress-protocol.ts` (`DATAGRAM_HEADER_SIZE = 26`, BE fields).
- tauri: `jettison_view/src/decoder.rs` (`CAMERA_HEADER_LEN = 25`, stripped
  before codec decode), `jettison_wt_client/src/protocol/wb.rs` (26-byte BE,
  `payload_size` is 24-bit BE), `jettison_wt_client/src/protocol/wk.rs`
  (15-byte BE).

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
- datasart: `ts/osd/state-worker.ts` (Profile A — `WR_MAGIC_0 = 0x57`,
  `WR_MAGIC_1 = 0x52`, `[4B LE len]` via `getUint32(0, true)`,
  `MAX_MSG_SIZE = 16 MiB` sanity cap).
- tauri: `jettison_wt_client/src/session/state.rs` (Profile B — continuous
  `ZstdDecoder`, `read_u32_le()` length prefix, `MAX_STATE_FRAME = 4 MiB`; the
  initial rate datagram `connection.send_datagram([0x52, update_rate_hz])`).

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
- datasart: `ts/video/gesture-core.ts` (`PointerSample` `x`/`y` doc: "NDC
  `[-1,1]`, +x right, +y UP (Y-flipped from screen)").
- tauri: `jettison_view/src/ui_input.rs` (module header: "`x`/`y` are NDC in
  `[-1, 1]`, +x right, +y UP (Y-flip from window px)"; the WASM-side clamp +
  `ndc_to_px` flip live in datasart `src/main.c` `ndc_to_px`).

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
- datasart: `ts/video/enrichment.ts` (`OSD_CLIENT_METADATA_UUID`,
  `FIELD_OPAQUE_PAYLOADS = 8`, `FIELD_PAYLOAD_UUID = 1`, `FIELD_PAYLOAD_DATA = 3`,
  per-field encoder — a hand-roll that mirrors the proto; the generated-proto
  rule above applies).
- tauri: `jettison_view/src/proto.rs` (`OSD_CLIENT_METADATA_UUID`, prost-generated
  `OsdClientMetadata`/`JonOpaquePayload` from `jettison_protogen/output/rust/ser.rs`,
  `state.opaque_payloads.push(...)` then `encode_to_vec()`).

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
| Native desktop (tauri) | `2` (`LOCAL_NETWORK`) | `3` (`DESKTOP_NATIVE`) |
| Browser HUD (datasart controls) | `2` (`LOCAL_NETWORK`) | `1` (`BROWSER_UI`) |

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

**Negative vector (the former datasart hand-roll bug).** `ts/controls/cmd-sender.ts`
`buildPingPayload()` formerly emitted:

```
08 01 10 02 18 03 42 00            (WRONG — kept only as the negative test vector)
```

which decodes as: field 1 = `1` (correct), field 2 (`session_id`) = `2`, field
3 (`important`) = `3`, field 8 (`state_time`) as wire-type-2-length-0 — the
intended `client_type` (field 5), `client_app` (field 10), and `ping` (field 28)
were NOT set. That form has been REPLACED by the correct field-5/10/28 shape
(now G1-B for this sender); the broken bytes are retained ONLY as the
non-vacuous negative vector asserted by `ts/controls/cmd-sender.test.ts`. A
second defect class this history teaches: the field-shape fix initially carried
enum VALUES equal to the fields' own numbers (`client_type=5`, `client_app=10`)
— undefined variants both, which `defined_only` validation rejects; the
identity table above is the correction.

Reference implementations:
- proto (authoritative): `proto/jon_shared_cmd.proto` (field numbers
  `protocol_version=1`, `client_type=5`, `client_app=10`, `ping=28`;
  `message Ping {}` empty; enum variants in `proto/jon_shared_data_types.proto`).
- datasart: `ts/controls/cmd-sender.ts` `buildPingPayload()` — emits G1-B,
  asserted byte-for-byte by `ts/controls/cmd-sender.test.ts`.
- tauri: `tauri-app/src/proto.rs` `build_ping_command()` (prost `cmd::Root` → G1,
  asserted by `test_build_ping_command_matches_g1_golden_vector`);
  `jettison_wt_client/src/session/command.rs` (`send_persistent` `CW`-stream length
  framing; the ping payload is the app-supplied `cmd.Root`).

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
- datasart: `ts/controls/package.ts` (`extractControlsTar` — flat POSIX tar,
  `controls.wasm` + `ui/{screen}.pb`, screen default `zoom_controls`, size@124
  octal, 512-block alignment), `ts/osd/package.ts` (OSD-variant: outer tar →
  `manifest.jwt` + inner `*.tar.gz` → wasm + config + resources),
  `src/arm/osd.clj` (`handle-package` 200/304/503+Retry-After:5/404-off-whitelist,
  `handle-package-etag` bare-body etag), `ts/osd/state-worker.ts` (5 s
  `pollPackage` HEAD floor + 1.5 s debounce), `src/arm/codegen.clj`
  (`repackage-controls!` assembles `controls.wasm` + `ui/` + `assets/`).
- tauri: `jettison_view/src/controls.rs` (loads `controls.wasm` + a `ui.Screen`
  `.pb` from `controls.tar`); the bare-etag poll (`/osd/{name}.tar/etag`) +
  5 s cadence is the native OSD/controls reload poller (consuming-repo wiring).

---

## 8. `controls.wasm` ABI (reference, not redefined)

The `controls.wasm` host↔guest ABI is NOT defined here. Its authoritative home
is the datasart renderer source: `src/main.c` (the export bodies + the export
list in the file header) and `src/host_imports.h` (the imports). This section
only LISTS the names so a consumer knows what to link; the contract (arg/return
semantics, error codes) lives at that home.

**Guest exports** (`controls_*`, from `src/main.c`):
`controls_init`, `controls_load_ui`, `controls_apply_patch`,
`controls_update_state`, `controls_host_message`, `controls_key_event`,
`controls_text_input`, `controls_get_focused_text`, `controls_tick`,
`controls_get_framebuffer`, `controls_abi_version`, `controls_fb_format`,
`controls_fb_width`, `controls_fb_height`, `controls_fb_bpp`,
`controls_set_breakpoint`, `controls_set_theme_dark`, `controls_set_dpi`,
`controls_resize`, `controls_get_dirty_rect`, `controls_get_dirty_rect_ptr`,
`controls_dump_tree`, `controls_destroy` (plus the WASI reactor `_initialize`
and the `malloc`/`free` buffer-transfer pair).

ABI self-description getters (the host validates these at load/reload before
reading the framebuffer at a stride): `controls_abi_version` (`CONTROLS_ABI_VERSION`
is `2`; `v2` added the `env.host_event` import to the REQUIRED import set —
a WASM import is instantiation-MANDATORY, so hosts link it BEFORE a v2 module
can load at all), `controls_fb_format` (`1` = `RGBA8888`, memory byte order
`framebuffer[i*4+0]`=R), `controls_fb_width`/`controls_fb_height`,
`controls_fb_bpp` (`4`). All are plain `u32` returns (no i64/BigInt).

**Host imports** (`env.*`, from `src/host_imports.h`):
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
- datasart (authoritative ABI home): `src/main.c` (export bodies +
  `CONTROLS_ABI_VERSION`/`CONTROLS_FB_FMT_RGBA8888`), `src/host_imports.h`
  (`host_command`/`host_report`/`host_proxy_report` `import_module("env")`).
- proto: `proto/ui/ui_input.proto` (the `HostToWasm`/`WasmToHost` field numbers
  + enum values above).
- tauri: `jettison_view/src/controls.rs` (links `env.host_command`/
  `env.host_report`/`env.host_proxy_report`, calls the `controls_*` exports),
  `jettison_view/src/ui_input.rs` (encodes `HostToWasm`, decodes `WasmToHost`).

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
`pts=1`, `duration=2`, `system_time=3`, `is_keyframe=1` (all LE), the header is:

```
01 00 00 00 00 00 00 00   # pts        = 1   (u64 LE)
02 00 00 00 00 00 00 00   # duration   = 2   (u64 LE)
03 00 00 00 00 00 00 00   # system_time= 3   (u64 LE)
01                        # is_keyframe= 1
```

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
- datasart: `ts/controls/cmd-sender.ts` (G1, asserted by `cmd-sender.test.ts`),
  `ts/video/frame-protocol.ts` (G2), `ts/video/ingress-protocol.ts` (G3),
  `ts/osd/state-worker.ts` (G4 is the inbound rate the server honors).
- tauri: `jettison_wt_client/src/protocol/wb.rs` test `make_wb_datagram`
  (G3 — uses exactly `frame_seq=42, total_datagrams=3, payload_size=2922`),
  `jettison_view/src/decoder.rs` (G2 — strips the 25-byte header),
  `jettison_wt_client/src/session/state.rs` test asserts `[0x52, 20]` (G4),
  the `cmd.Root` ping (G1) is the app-supplied `CW` payload, asserted by
  `tauri-app/src/proto.rs` `test_build_ping_command_matches_g1_golden_vector`.

---

## 10. Evolving this contract (anti-drift)

These are CROSS-LANGUAGE wire surfaces: every encoder/decoder of them lives in
two repos and must agree byte-for-byte. Two mechanisms keep them from drifting.

1. **Generate, don't hand-roll.** Encoders should be built from the pinned proto
   (`prost` in Rust, the generated ts-proto in TypeScript). A hand-roll is
   permitted ONLY as an optimization that EXACTLY reproduces the generated wire
   bytes (e.g. §5 enrichment, §6 ping) — and then only behind a parity test.

2. **Each consumer asserts the §9 golden vectors in a wire-parity test** that
   decodes its own encoder output against the pinned generated proto and asserts
   the §9 bytes:
   - datasart: `ts/controls/cmd-sender.test.ts` (G1 ping),
     `ts/video/enrichment.test.ts` (§5 enrichment).
   - tauri: `tauri-app/src/proto.rs::test_build_ping_command_matches_g1_golden_vector`
     (G1), `jettison_view/src/proto.rs::test_enrichment_matches_section5_wire_contract`
     (§5).
   Touch a wire encoder ⇒ the parity test guards it; this contract + the §9
   vectors are the source of truth.

**The evolution loop** — when a proto change touches a surface named here: edit
`proto/`, regenerate the bindings, update this doc to match, bump the
`jettison_protogen` submodule pin in BOTH consumers (datasart + tauri) in
lockstep, and rebuild. Renumbering is allowed (no compat shims — both consumers
rebuild together); the §9 parity tests fail loudly on any divergence.
