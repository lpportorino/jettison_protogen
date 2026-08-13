//! WASM host wrapper for the LVGL controls module via wasmtime.
//!
//! Loads a `controls.wasm` module, manages its lifecycle, and provides methods
//! to push UI protobuf data, tick LVGL, and read the RGBA framebuffer.
//!
//! Modeled after the native consumer's controls host but simplified for standalone
//! headless rendering (no command capture, no custom store data).
use crate::HarnessError;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use wasmtime::{Caller, Engine, Extern, Instance, Linker, Memory, Module, Store, TypedFunc};
use wasmtime_wasi::p1::WasiP1Ctx;
use wasmtime_wasi::p2::pipe::MemoryOutputPipe;
use wasmtime_wasi::{DirPerms, FilePerms, WasiCtxBuilder};
/// Process-wide compiled-module cache: each DISTINCT wasm binary is
/// cranelift-compiled once per process, not once per `ControlsHost` — the
/// multi-MB compile dominated per-test time in the visual-regression suite.
/// Keyed on the module's full BYTES (exact equality, no digest collisions):
/// a rebuilt module recompiles, a byte-identical re-read hits the cache —
/// content, never path or mtime. `Engine`/`Module` are internally
/// reference-counted, so clones out of the cache are cheap, and every `Store`
/// is built from the SAME engine its module was compiled for.
static MODULE_CACHE: OnceLock<Mutex<HashMap<Vec<u8>, (Engine, Module)>>> = OnceLock::new();
/// Fetch (or compile and cache) the engine+module pair for `wasm_path`.
///
/// # Errors
///
/// Returns an error when the file cannot be read or the module fails to
/// compile.
fn cached_engine_module(wasm_path: &Path) -> Result<(Engine, Module), HarnessError> {
    let bytes = std::fs::read(wasm_path).map_err(|err| {
        HarnessError::Wasm(format!("read WASM module '{}': {err}", wasm_path.display()))
    })?;
    let cache = MODULE_CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    let mut map = cache
        .lock()
        .map_err(|err| HarnessError::Wasm(format!("module cache poisoned: {err}")))?;
    if let Some((engine, module)) = map.get(&bytes) {
        return Ok((engine.clone(), module.clone()));
    }
    let engine = Engine::default();
    let module = Module::new(&engine, &bytes)
        .map_err(|err| HarnessError::Wasm(format!("load WASM module: {err}")))?;
    let _ = map.insert(bytes, (engine.clone(), module.clone()));
    Ok((engine, module))
}
/// Configuration for creating a [`ControlsHost`].
///
/// Uses a config struct to keep the constructor under 5 parameters
/// (per `clippy.toml` `too-many-arguments-threshold = 5`).
#[non_exhaustive]
#[derive(Debug, Clone)]
pub struct HostConfig {
    /// Path to the `controls.wasm` file.
    pub wasm_path: PathBuf,
    /// Framebuffer width in pixels.
    pub width: u32,
    /// Framebuffer height in pixels.
    pub height: u32,
    /// Optional WASI root directory (preopened as `/` read-only).
    /// The controls WASM module doesn't read files at runtime, so this
    /// is typically `None`.
    pub wasi_root: Option<PathBuf>,
    /// Route the guest's WASI stderr into an in-memory buffer readable via
    /// [`ControlsHost::captured_stderr`], instead of inheriting the harness
    /// process's stderr.
    ///
    /// OFF by default, deliberately: inherited stderr is what makes a guest
    /// `LOG_ERROR` visible in a failing test's output, and capturing it
    /// everywhere would hide every diagnostic the other suites rely on
    /// reading. Turn it on only in a test whose SUBJECT is a log line.
    pub capture_stderr: bool,
}
impl HostConfig {
    /// Create a new host configuration.
    pub const fn new(wasm_path: PathBuf, width: u32, height: u32) -> Self {
        Self {
            wasm_path,
            width,
            height,
            wasi_root: None,
            capture_stderr: false,
        }
    }
    /// Set the WASI root directory (preopened as `/` read-only).
    #[must_use]
    pub fn with_wasi_root(mut self, root: PathBuf) -> Self {
        self.wasi_root = Some(root);
        self
    }
    /// Capture the guest's stderr in memory rather than inheriting it.
    #[must_use]
    pub const fn with_captured_stderr(mut self) -> Self {
        self.capture_stderr = true;
        self
    }
}
/// One `host_proxy_report` emission captured from the module — the
/// host-proxy positioning stream (id + interaction phase + mode + rect in
/// framebuffer px + the opaque z hint + flags, bit0 = hidden).
#[non_exhaustive]
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxyReport {
    /// The proxy's stable host-side join key (`HostProxyProps.proxy_id`).
    pub id: String,
    /// Interaction phase: 0 SYNC, 1 START, 2 MOVE, 3 END.
    pub phase: i32,
    /// Current `ui.ProxyMode` int.
    pub mode: i32,
    /// The proxy's rect in framebuffer px: `(x, y, w, h)`.
    pub rect: (i32, i32, i32, i32),
    /// Opaque host stacking hint (`HostProxyProps.z`), echoed verbatim.
    pub z: i32,
    /// bit0 = hidden (`lv_obj_is_visible == false`); rest reserved 0.
    pub flags: i32,
}
/// A pointer/wheel event fed to the pure gesture FSM via `feed_gestures`.
/// Mirrors the C `gesture_feed_event_t` wire layout (op, id, NDC x/y, t_ms).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GestureEvent {
    /// 0=down, 1=move, 2=up, 3=wheel (deltaY in `x`), 4=cancel.
    pub op: i32,
    /// Pointer id (ignored for wheel).
    pub pointer_id: i32,
    /// NDC x (or deltaY for wheel).
    pub x: f64,
    /// NDC y.
    pub y: f64,
    /// Event timestamp in ms.
    pub t_ms: i32,
}
impl GestureEvent {
    /// Bytes per event on the wire (matches `sizeof(gesture_feed_event_t)`:
    /// `int32 op; int32 id; double x; double y; int32 t_ms; int32 pad`).
    pub const WIRE_SIZE: usize = 32;
    /// A pointer-down at `(x, y)` for `pointer_id` at `t_ms`.
    #[must_use]
    pub const fn down(pointer_id: i32, x: f64, y: f64, t_ms: i32) -> Self {
        Self {
            op: 0,
            pointer_id,
            x,
            y,
            t_ms,
        }
    }
    /// A pointer-move.
    #[must_use]
    pub const fn mv(pointer_id: i32, x: f64, y: f64, t_ms: i32) -> Self {
        Self {
            op: 1,
            pointer_id,
            x,
            y,
            t_ms,
        }
    }
    /// A pointer-up.
    #[must_use]
    pub const fn up(pointer_id: i32, x: f64, y: f64, t_ms: i32) -> Self {
        Self {
            op: 2,
            pointer_id,
            x,
            y,
            t_ms,
        }
    }
    /// A wheel event carrying `delta_y` (the FSM-independent zoom path).
    #[must_use]
    pub const fn wheel(delta_y: f64) -> Self {
        Self {
            op: 3,
            pointer_id: 0,
            x: delta_y,
            y: 0.0,
            t_ms: 0,
        }
    }
    /// A pointer-cancel — the silent abort (no terminal, no `last_tap` seed).
    #[must_use]
    pub const fn cancel(pointer_id: i32, x: f64, y: f64, t_ms: i32) -> Self {
        Self {
            op: 4,
            pointer_id,
            x,
            y,
            t_ms,
        }
    }
    /// Serialize one event into its 32-byte little-endian wire form.
    fn encode_into(&self, buf: &mut Vec<u8>) {
        buf.extend_from_slice(&self.op.to_le_bytes());
        buf.extend_from_slice(&self.pointer_id.to_le_bytes());
        buf.extend_from_slice(&self.x.to_le_bytes());
        buf.extend_from_slice(&self.y.to_le_bytes());
        buf.extend_from_slice(&self.t_ms.to_le_bytes());
        buf.extend_from_slice(&0_i32.to_le_bytes()); // pad
    }
    /// Serialize a slice of events into a contiguous wire buffer.
    fn encode_all(events: &[Self]) -> Vec<u8> {
        let mut buf = Vec::with_capacity(events.len() * Self::WIRE_SIZE);
        for event in events {
            event.encode_into(&mut buf);
        }
        buf
    }
}
/// A recognized gesture read back from the FSM. Mirrors the C
/// `gesture_decision_t` (kind tag + NDC x/y for pan/tap/track, or `delta`
/// +/-1 for pinch/wheel).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GestureDecision {
    /// 0 pan-move, 1 pan-end, 2 tap, 3 track, 4 pinch, 5 wheel.
    pub kind: i32,
    /// NDC x (0 for pinch/wheel).
    pub x: f64,
    /// NDC y (0 for pinch/wheel).
    pub y: f64,
    /// pinch/wheel: +1 or -1; else 0.
    pub delta: i32,
}
impl GestureDecision {
    /// Bytes per decision on the wire (matches `sizeof(gesture_decision_t)`:
    /// `int32 kind` + 4 pad + `double x` + `double y` + `int32 delta` + 4 pad).
    pub const WIRE_SIZE: usize = 32;
    /// Decode one 32-byte little-endian decision at the C struct offsets.
    fn decode_one(bytes: &[u8]) -> Self {
        let i32_at = |off: usize| {
            let mut buf = [0_u8; 4];
            buf.copy_from_slice(&bytes[off..off + 4]);
            i32::from_le_bytes(buf)
        };
        let f64_at = |off: usize| {
            let mut buf = [0_u8; 8];
            buf.copy_from_slice(&bytes[off..off + 8]);
            f64::from_le_bytes(buf)
        };
        Self {
            kind: i32_at(0),
            x: f64_at(8),
            y: f64_at(16),
            delta: i32_at(24),
        }
    }
    /// Decode all decisions in a contiguous wire buffer.
    fn decode_all(slice: &[u8]) -> Vec<Self> {
        slice
            .chunks_exact(Self::WIRE_SIZE)
            .map(Self::decode_one)
            .collect()
    }
}
/// W3C pointer phase (mirrors `ui.PointerPhase`; 0 UNSPECIFIED is invalid).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PointerPhase {
    /// `pointerdown` — allocates a table slot + latches ownership.
    Down = 1,
    /// `pointermove`.
    Move = 2,
    /// `pointerup` — UP terminal (pan-end for a panning video pointer).
    Up = 3,
    /// `pointercancel` / `lostpointercapture` — silent abort, no terminal.
    Cancel = 4,
}
/// W3C pointer kind (mirrors `ui.PointerKind`; 0 UNSPECIFIED is invalid).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PointerKind {
    /// Mouse.
    Mouse = 1,
    /// Touch contact.
    Touch = 2,
    /// Pen / stylus.
    Pen = 3,
}
/// OS theme the host reports (mirrors `ui.ThemeMode`; 0 UNSPECIFIED invalid).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThemeMode {
    /// Light.
    Light = 1,
    /// Dark.
    Dark = 2,
}
/// A W3C `ui.PointerEvent` to forward via `controls_host_message`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PointerEvent {
    /// Lifecycle phase.
    pub phase: PointerPhase,
    /// Pointing-device kind.
    pub kind: PointerKind,
    /// W3C pointerId — the multi-pointer FSM key.
    pub pointer_id: u32,
    /// NDC x, +x right (clamped to [-1, 1] by the WASM).
    pub x: f64,
    /// NDC y, +y UP (clamped to [-1, 1] by the WASM).
    pub y: f64,
    /// W3C event timestamp, ms (the FSM clock; 0 is invalid).
    pub event_time: u64,
}
/// An OS `ui.Lifecycle` event (theme restyle + the whole-surface FSM flush).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Lifecycle {
    /// OS theme.
    pub theme: ThemeMode,
    /// Window focus — `false` triggers the whole-surface flush.
    pub focused: bool,
    /// Window visibility — `false` triggers the whole-surface flush.
    pub visible: bool,
}
/// The `ui.HostToWasm` channel schema version the WASM accepts (fail-fast).
const HOST_MSG_VERSION: u32 = 1;
/// Append a protobuf varint (LEB128) for `value`.
fn put_varint(buf: &mut Vec<u8>, mut value: u64) {
    loop {
        let mut byte = u8::try_from(value & 0x7f).unwrap_or(0);
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        buf.push(byte);
        if value == 0 {
            break;
        }
    }
}
/// Append a protobuf field key (`field_number << 3 | wire_type`).
fn put_key(buf: &mut Vec<u8>, field: u32, wire_type: u32) {
    put_varint(buf, u64::from((field << 3) | wire_type));
}
/// Append a varint-typed scalar field (wire type 0).
fn put_varint_field(buf: &mut Vec<u8>, field: u32, value: u64) {
    put_key(buf, field, 0);
    put_varint(buf, value);
}
/// Append a fixed64 (double) field (wire type 1).
fn put_double_field(buf: &mut Vec<u8>, field: u32, value: f64) {
    put_key(buf, field, 1);
    buf.extend_from_slice(&value.to_le_bytes());
}
/// Append a length-delimited sub-message field (wire type 2).
fn put_message_field(buf: &mut Vec<u8>, field: u32, msg: &[u8]) {
    put_key(buf, field, 2);
    put_varint(buf, msg.len() as u64);
    buf.extend_from_slice(msg);
}
impl PointerEvent {
    /// A `pointerdown` (touch) at NDC `(x, y)` for `pointer_id` at `t_ms`.
    #[must_use]
    pub const fn down(pointer_id: u32, x: f64, y: f64, t_ms: u64) -> Self {
        Self {
            phase: PointerPhase::Down,
            kind: PointerKind::Touch,
            pointer_id,
            x,
            y,
            event_time: t_ms,
        }
    }
    /// A `pointermove` (touch).
    #[must_use]
    pub const fn mv(pointer_id: u32, x: f64, y: f64, t_ms: u64) -> Self {
        Self {
            phase: PointerPhase::Move,
            kind: PointerKind::Touch,
            pointer_id,
            x,
            y,
            event_time: t_ms,
        }
    }
    /// A `pointerup` (touch).
    #[must_use]
    pub const fn up(pointer_id: u32, x: f64, y: f64, t_ms: u64) -> Self {
        Self {
            phase: PointerPhase::Up,
            kind: PointerKind::Touch,
            pointer_id,
            x,
            y,
            event_time: t_ms,
        }
    }
    /// A `pointercancel` (touch).
    #[must_use]
    pub const fn cancel(pointer_id: u32, x: f64, y: f64, t_ms: u64) -> Self {
        Self {
            phase: PointerPhase::Cancel,
            kind: PointerKind::Touch,
            pointer_id,
            x,
            y,
            event_time: t_ms,
        }
    }
    /// Replace the device kind (the default constructors use `Touch`).
    #[must_use]
    pub const fn with_kind(mut self, kind: PointerKind) -> Self {
        self.kind = kind;
        self
    }
    /// Encode the inner `ui.PointerEvent` body (no envelope).
    fn encode_body(&self) -> Vec<u8> {
        let mut body = Vec::new();
        put_varint_field(&mut body, 1, self.phase as u64);
        put_varint_field(&mut body, 2, self.kind as u64);
        put_varint_field(&mut body, 3, u64::from(self.pointer_id));
        put_double_field(&mut body, 4, self.x);
        put_double_field(&mut body, 5, self.y);
        put_varint_field(&mut body, 6, self.event_time);
        body
    }
    /// Encode the full `ui.HostToWasm{ version; pointer }` envelope bytes.
    #[must_use]
    pub fn encode_envelope(&self) -> Vec<u8> {
        let mut buf = Vec::new();
        put_varint_field(&mut buf, 1, u64::from(HOST_MSG_VERSION));
        put_message_field(&mut buf, 2, &self.encode_body());
        buf
    }
}
impl Lifecycle {
    /// A lifecycle event with explicit theme + focus + visibility.
    #[must_use]
    pub const fn new(theme: ThemeMode, focused: bool, visible: bool) -> Self {
        Self {
            theme,
            focused,
            visible,
        }
    }
    /// Encode the inner `ui.Lifecycle` body (no envelope).
    fn encode_body(&self) -> Vec<u8> {
        let mut body = Vec::new();
        put_varint_field(&mut body, 1, self.theme as u64);
        put_varint_field(&mut body, 2, u64::from(self.focused));
        put_varint_field(&mut body, 3, u64::from(self.visible));
        body
    }
    /// Encode the full `ui.HostToWasm{ version; lifecycle }` envelope bytes.
    #[must_use]
    pub fn encode_envelope(&self) -> Vec<u8> {
        let mut buf = Vec::new();
        put_varint_field(&mut buf, 1, u64::from(HOST_MSG_VERSION));
        put_message_field(&mut buf, 3, &self.encode_body());
        buf
    }
}
/// WASM controls module host. Manages a wasmtime instance with WASI p1
/// support for headless LVGL rendering.
///
/// The WASM module must export the standard `controls_*` ABI functions
/// (see `lvgl_controls/src/main.c`).
pub struct ControlsHost {
    /// Wasmtime store with plain WASI p1 context.
    store: Store<WasiP1Ctx>,
    /// The WASM module instance.
    #[expect(
        dead_code,
        reason = "kept alive for the instance's memory and functions"
    )]
    instance: Instance,
    /// Direct handle to the module's linear memory.
    memory: Memory,
    /// `malloc(size) -> ptr`
    fn_malloc: TypedFunc<u32, u32>,
    /// `free(ptr)`
    fn_free: TypedFunc<u32, ()>,
    /// `controls_load_ui(ptr, len) -> status`
    fn_load_ui: TypedFunc<(u32, u32), i32>,
    /// `controls_update_state(ptr, len) -> status`
    fn_update_state: TypedFunc<(u32, u32), i32>,
    /// `controls_apply_patch(ptr, len) -> status` (0 ok / PATCH_ERR_*)
    fn_apply_patch: TypedFunc<(u32, u32), i32>,
    /// Commands the module pushed through `host_command` — the cmd-out
    /// channel (R5b). Each entry is the raw OPAQUE `cmd.*` protobuf the
    /// renderer pre-built from a CmdSpec template + live value; tests decode
    /// it with prost to assert WHICH command + WHAT field values fired.
    host_commands: Arc<Mutex<Vec<Vec<u8>>>>,
    /// Reports the module pushed through `host_report` — the hover/cursor
    /// feedback channel (R5b HOST_REPORT). Each entry is the raw OPAQUE
    /// `ui.WasmToHost` protobuf; tests prost-decode it to assert the hovered
    /// uid + the requested cursor.
    host_reports: Arc<Mutex<Vec<Vec<u8>>>>,
    /// Envelopes the module pushed through `host_event` — the named-event
    /// lane (ABI v2). Each entry is the raw UTF-8 JSON envelope bytes
    /// (`{"v":1,"tag":...,"origin":...,"event":...,"seq":...,"value":...}`);
    /// tests parse them with serde_json to assert the closed-map contract.
    host_events: Arc<Mutex<Vec<Vec<u8>>>>,
    /// Reports the module pushed through `host_proxy_report` — the
    /// host-proxy positioning stream, captured for assertions.
    proxy_reports: Arc<Mutex<Vec<ProxyReport>>>,
    /// `controls_host_message(ptr, len) -> rc` — decode a `ui.HostToWasm`
    /// (W3C pointer event or lifecycle); owns the bounded pointer table +
    /// capture-on-claim hit-test routing (R4).
    fn_host_message: TypedFunc<(u32, u32), i32>,
    /// `controls_pointer_decisions_count() -> n` — buffered gesture
    /// decisions (the FSM-fed routing oracle until R5 drains them).
    fn_pointer_decisions_count: TypedFunc<(), u32>,
    /// `controls_pointer_decisions_ptr() -> ptr` to the buffered array.
    fn_pointer_decisions_ptr: TypedFunc<(), u32>,
    /// `controls_pointer_decisions_clear()` — reset the buffer.
    fn_pointer_decisions_clear: TypedFunc<(), ()>,
    /// `controls_pointer_active_count() -> n` — active table slots (the
    /// table-invariant oracle: overflow / orphan / re-seat / GC / flush).
    fn_pointer_active_count: TypedFunc<(), u32>,
    /// `controls_cmd_patch_probe(off, width, len) -> rc` — R5b slot-bounds
    /// memory-safety oracle (a wrapping/OOB slot must return -1, emit nothing).
    fn_cmd_patch_probe: TypedFunc<(u32, u32, u32), i32>,
    /// `controls_cmd_spec_decode_probe(ptr, len) -> rc` — the crafted-`.pb`
    /// decode-boundary oracle (nanopb + cmd_spec_copy_from_proto): 0 accepted,
    /// -1 slot-bounds reject, -2 nanopb decode reject.
    fn_cmd_spec_decode_probe: TypedFunc<(u32, u32), i32>,
    /// `controls_key_event(key, pressed) -> status` — keypad indev queue.
    /// `key` is an `lv_key_t`, a Unicode codepoint, or a module-owned
    /// MODIFIER code at/above `0x01000000`.
    fn_key_event: TypedFunc<(u32, u32), i32>,
    /// `controls_text_input(ptr, len) -> status` — paste into the focused
    /// textarea, REPLACING any selection; refuses over-cap input
    fn_text_input: TypedFunc<(u32, u32), i32>,
    /// `controls_get_focused_text() -> ptr` (NUL-terminated; 0 = no focus
    /// OR the field is in password mode)
    fn_get_focused_text: TypedFunc<(), u32>,
    /// `controls_take_clipboard_request() -> req` — drains the pending
    /// clipboard request (0 none, 1 copy, 2 cut, 3 paste)
    fn_take_clipboard_request: TypedFunc<(), u32>,
    /// `controls_get_clipboard_text() -> ptr` (NUL-terminated; 0 = empty)
    fn_get_clipboard_text: TypedFunc<(), u32>,
    /// `controls_tick(elapsed_ms) -> changed` (1 = framebuffer changed)
    fn_tick: TypedFunc<u32, i32>,
    /// `controls_get_framebuffer() -> ptr`
    fn_get_framebuffer: TypedFunc<(), u32>,
    /// `controls_dump_tree() -> ptr` (NUL-terminated JSON widget tree)
    fn_dump_tree: TypedFunc<(), u32>,
    /// `controls_set_breakpoint(bp) -> status`
    fn_set_breakpoint: TypedFunc<i32, i32>,
    /// `controls_set_theme_dark(dark) -> status`
    fn_set_theme_dark: TypedFunc<i32, i32>,
    /// `controls_set_theme_family(family) -> status` (0=asgard/1=vanilla/2=stock)
    fn_set_theme_family: TypedFunc<i32, i32>,
    /// `controls_set_dpi(dpi) -> status`
    fn_set_dpi: TypedFunc<i32, i32>,
    /// `controls_resize(w, h) -> status`
    fn_resize: TypedFunc<(u32, u32), i32>,
    /// `controls_destroy() -> status`
    fn_destroy: TypedFunc<(), i32>,
    /// True once an explicit `destroy()` succeeded — `Drop`'s RAII backstop
    /// skips the guest then. Explicit `destroy()` calls ALWAYS reach the
    /// guest (the wasm-side idempotency guard is a tested contract; the
    /// 2026-07-19 double-destroy livelock is the regression this composes
    /// against).
    destroyed: bool,
    /// `gesture_test_reset()` — reset the pure gesture FSM (harness-only).
    fn_gesture_test_reset: TypedFunc<(), ()>,
    /// `gesture_test_feed(events_ptr, count) -> last_decision_count`
    fn_gesture_test_feed: TypedFunc<(u32, u32), i32>,
    /// `gesture_decisions_ptr() -> ptr` to the captured decision array
    fn_gesture_decisions_ptr: TypedFunc<(), u32>,
    /// Framebuffer width.
    width: u32,
    /// Framebuffer height.
    height: u32,
    /// In-memory guest stderr, present only when the config asked for it.
    stderr_pipe: Option<MemoryOutputPipe>,
}
impl ControlsHost {
    /// Load and initialize a WASM controls module.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM module cannot be loaded, instantiated,
    /// or initialized (missing exports, WASI setup failure, etc.).
    #[expect(
        clippy::too_many_lines,
        reason = "wasmtime setup is inherently verbose; splitting would scatter related init"
    )]
    pub fn new(config: &HostConfig) -> Result<Self, HarnessError> {
        let (engine, module) = cached_engine_module(&config.wasm_path)?;
        // Build WASI context — inherit stdout/stderr for direct terminal output
        let mut builder = WasiCtxBuilder::new();
        let _ = builder.inherit_stdout();
        // A test whose subject IS a log line needs the bytes, not the terminal.
        // The pipe REFUSES a write past its capacity (wasmtime-wasi returns a
        // trap rather than truncating), so the budget is generous: a saturation
        // case deliberately drives dozens of lines, and a silently-dropped tail
        // would make an assertion about "how many lines" answer a question about
        // the buffer instead of about the renderer.
        let stderr_pipe = if config.capture_stderr {
            let pipe = MemoryOutputPipe::new(1 << 20);
            let _ = builder.stderr(pipe.clone());
            Some(pipe)
        } else {
            let _ = builder.inherit_stderr();
            None
        };
        if let Some(root) = &config.wasi_root {
            let abs = root.canonicalize().map_err(|err| {
                HarnessError::Wasm(format!(
                    "canonicalize wasi_root '{}': {err}",
                    root.display()
                ))
            })?;
            let _ = builder
                .preopened_dir(&abs, "/", DirPerms::READ, FilePerms::READ)
                .map_err(|err| HarnessError::Wasm(format!("preopened_dir: {err}")))?;
        }
        let wasi_ctx = builder.build_p1();
        let mut store = Store::new(&engine, wasi_ctx);
        let mut linker = Linker::new(&engine);
        // Link WASI p1 functions — identity projection (store data IS WasiP1Ctx)
        wasmtime_wasi::p1::add_to_linker_sync(&mut linker, |ctx| ctx)
            .map_err(|err| HarnessError::Wasm(format!("WASI linker: {err}")))?;
        // Capture host_command — the module's cmd-out channel (R5b). The
        // renderer pre-builds the full cmd.* protobuf (CmdSpec template +
        // patched live value) and hands the OPAQUE bytes here; the harness
        // reads them from guest memory verbatim so tests can prost-decode and
        // assert WHICH command variant + WHAT field values fired.
        let host_commands: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
        let commands_sink = Arc::clone(&host_commands);
        let _ = linker
            .func_wrap(
                "env",
                "host_command",
                move |mut caller: Caller<'_, WasiP1Ctx>, cmd_ptr: u32, cmd_len: u32| -> i32 {
                    let bytes = caller
                        .get_export("memory")
                        .and_then(Extern::into_memory)
                        .and_then(|mem| {
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let start = cmd_ptr as usize;
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let end = start.checked_add(cmd_len as usize)?;
                            mem.data(&caller).get(start..end).map(<[u8]>::to_vec)
                        })
                        .unwrap_or_default();
                    if let Ok(mut sink) = commands_sink.lock() {
                        sink.push(bytes);
                    }
                    0
                },
            )
            .map_err(|err| HarnessError::Wasm(format!("link host_command: {err}")))?;
        // Capture host_report — the module's hover/cursor feedback channel
        // (R5b HOST_REPORT). On a pointer MOVE the WASM hit-tests and, when the
        // hovered widget or cursor CHANGES, pb_encodes a ui.WasmToHost{ hover |
        // cursor } and hands the OPAQUE bytes here. The harness reads them from
        // guest memory verbatim so tests can prost-decode and assert WHICH
        // report (hover uid / cursor type) the WASM emitted.
        let host_reports: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
        let reports_out = Arc::clone(&host_reports);
        let _ = linker
            .func_wrap(
                "env",
                "host_report",
                move |mut caller: Caller<'_, WasiP1Ctx>, rpt_ptr: u32, rpt_len: u32| -> i32 {
                    let bytes = caller
                        .get_export("memory")
                        .and_then(Extern::into_memory)
                        .and_then(|mem| {
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let start = rpt_ptr as usize;
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let end = start.checked_add(rpt_len as usize)?;
                            mem.data(&caller).get(start..end).map(<[u8]>::to_vec)
                        })
                        .unwrap_or_default();
                    if let Ok(mut sink) = reports_out.lock() {
                        sink.push(bytes);
                    }
                    0
                },
            )
            .map_err(|err| HarnessError::Wasm(format!("link host_report: {err}")))?;
        // Capture host_event — the named-event envelope lane (ABI v2). When an
        // EventBinding with a nonempty name fires (and its host-relay gate is
        // open), the module builds the closed JSON envelope and hands the raw
        // UTF-8 bytes here. The import is instantiation-MANDATORY for a v2
        // module, so the harness (like every host) must link it BEFORE loading;
        // tests serde_json-parse the captured bytes to assert the contract.
        let host_events: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
        let events_sink = Arc::clone(&host_events);
        let _ = linker
            .func_wrap(
                "env",
                "host_event",
                move |mut caller: Caller<'_, WasiP1Ctx>, evt_ptr: u32, evt_len: u32| -> i32 {
                    let bytes = caller
                        .get_export("memory")
                        .and_then(Extern::into_memory)
                        .and_then(|mem| {
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let start = evt_ptr as usize;
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let end = start.checked_add(evt_len as usize)?;
                            mem.data(&caller).get(start..end).map(<[u8]>::to_vec)
                        })
                        .unwrap_or_default();
                    if let Ok(mut sink) = events_sink.lock() {
                        sink.push(bytes);
                    }
                    0
                },
            )
            .map_err(|err| HarnessError::Wasm(format!("link host_event: {err}")))?;
        // Capture host_proxy_report — the host-proxy positioning stream.
        // The id string is read from guest memory so proxy tests can
        // assert WHICH proxy reported WHAT rect at WHICH phase.
        let proxy_reports: Arc<Mutex<Vec<ProxyReport>>> = Arc::new(Mutex::new(Vec::new()));
        let reports_sink = Arc::clone(&proxy_reports);
        let _ = linker
            .func_wrap(
                "env",
                "host_proxy_report",
                move |mut caller: Caller<'_, WasiP1Ctx>,
                      id_ptr: u32,
                      id_len: u32,
                      phase: i32,
                      mode: i32,
                      x: i32,
                      y: i32,
                      w: i32,
                      h: i32,
                      z: i32,
                      flags: i32|
                      -> i32 {
                    let id = caller
                        .get_export("memory")
                        .and_then(Extern::into_memory)
                        .and_then(|mem| {
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let start = id_ptr as usize;
                            #[expect(
                                clippy::as_conversions,
                                reason = "u32→usize is lossless on supported platforms"
                            )]
                            let end = start.checked_add(id_len as usize)?;
                            mem.data(&caller)
                                .get(start..end)
                                .map(|bytes| String::from_utf8_lossy(bytes).into_owned())
                        })
                        .unwrap_or_default();
                    if let Ok(mut sink) = reports_sink.lock() {
                        sink.push(ProxyReport {
                            id,
                            phase,
                            mode,
                            rect: (x, y, w, h),
                            z,
                            flags,
                        });
                    }
                    0
                },
            )
            .map_err(|err| HarnessError::Wasm(format!("link host_proxy_report: {err}")))?;
        // Instantiate the (cached) precompiled module
        let instance = linker
            .instantiate(&mut store, &module)
            .map_err(|err| HarnessError::Wasm(format!("instantiate: {err}")))?;
        // Call _initialize (WASI reactor pattern)
        let fn_initialize: TypedFunc<(), ()> =
            instance
                .get_typed_func(&mut store, "_initialize")
                .map_err(|err| HarnessError::Wasm(format!("missing _initialize export: {err}")))?;
        fn_initialize
            .call(&mut store, ())
            .map_err(|err| HarnessError::Wasm(format!("_initialize failed: {err}")))?;
        // Cache memory and function exports
        let memory = instance
            .get_memory(&mut store, "memory")
            .ok_or_else(|| HarnessError::Wasm("missing memory export".into()))?;
        let fn_malloc: TypedFunc<u32, u32> = instance
            .get_typed_func(&mut store, "malloc")
            .map_err(|err| HarnessError::Wasm(format!("missing malloc export: {err}")))?;
        let fn_free: TypedFunc<u32, ()> = instance
            .get_typed_func(&mut store, "free")
            .map_err(|err| HarnessError::Wasm(format!("missing free export: {err}")))?;
        let fn_init: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_init")
            .map_err(|err| HarnessError::Wasm(format!("missing controls_init export: {err}")))?;
        let fn_load_ui: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_load_ui")
            .map_err(|err| HarnessError::Wasm(format!("missing controls_load_ui export: {err}")))?;
        let fn_update_state: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_update_state")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_update_state export: {err}"))
            })?;
        let fn_apply_patch: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_apply_patch")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_apply_patch export: {err}"))
            })?;
        let fn_host_message: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_host_message")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_host_message export: {err}"))
            })?;
        let fn_pointer_decisions_count: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_pointer_decisions_count")
            .map_err(|err| {
                HarnessError::Wasm(format!(
                    "missing controls_pointer_decisions_count export: {err}"
                ))
            })?;
        let fn_pointer_decisions_ptr: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_pointer_decisions_ptr")
            .map_err(|err| {
                HarnessError::Wasm(format!(
                    "missing controls_pointer_decisions_ptr export: {err}"
                ))
            })?;
        let fn_pointer_decisions_clear: TypedFunc<(), ()> = instance
            .get_typed_func(&mut store, "controls_pointer_decisions_clear")
            .map_err(|err| {
                HarnessError::Wasm(format!(
                    "missing controls_pointer_decisions_clear export: {err}"
                ))
            })?;
        let fn_pointer_active_count: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_pointer_active_count")
            .map_err(|err| {
                HarnessError::Wasm(format!(
                    "missing controls_pointer_active_count export: {err}"
                ))
            })?;
        let fn_cmd_patch_probe: TypedFunc<(u32, u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_cmd_patch_probe")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_cmd_patch_probe export: {err}"))
            })?;
        let fn_cmd_spec_decode_probe: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_cmd_spec_decode_probe")
            .map_err(|err| {
                HarnessError::Wasm(format!(
                    "missing controls_cmd_spec_decode_probe export: {err}"
                ))
            })?;
        let fn_key_event: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_key_event")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_key_event export: {err}"))
            })?;
        let fn_text_input: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_text_input")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_text_input export: {err}"))
            })?;
        let fn_get_focused_text: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_get_focused_text")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_get_focused_text export: {err}"))
            })?;
        let fn_take_clipboard_request: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_take_clipboard_request")
            .map_err(|err| {
                HarnessError::Wasm(format!(
                    "missing controls_take_clipboard_request export: {err}"
                ))
            })?;
        let fn_get_clipboard_text: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_get_clipboard_text")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_get_clipboard_text export: {err}"))
            })?;
        let fn_tick: TypedFunc<u32, i32> = instance
            .get_typed_func(&mut store, "controls_tick")
            .map_err(|err| HarnessError::Wasm(format!("missing controls_tick export: {err}")))?;
        let fn_get_framebuffer: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_get_framebuffer")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_get_framebuffer export: {err}"))
            })?;
        let fn_dump_tree: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "controls_dump_tree")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_dump_tree export: {err}"))
            })?;
        let fn_set_breakpoint: TypedFunc<i32, i32> = instance
            .get_typed_func(&mut store, "controls_set_breakpoint")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_set_breakpoint export: {err}"))
            })?;
        let fn_set_theme_dark: TypedFunc<i32, i32> = instance
            .get_typed_func(&mut store, "controls_set_theme_dark")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_set_theme_dark export: {err}"))
            })?;
        let fn_set_theme_family: TypedFunc<i32, i32> = instance
            .get_typed_func(&mut store, "controls_set_theme_family")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing controls_set_theme_family export: {err}"))
            })?;
        let fn_set_dpi: TypedFunc<i32, i32> = instance
            .get_typed_func(&mut store, "controls_set_dpi")
            .map_err(|err| HarnessError::Wasm(format!("missing controls_set_dpi export: {err}")))?;
        let fn_resize: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "controls_resize")
            .map_err(|err| HarnessError::Wasm(format!("missing controls_resize export: {err}")))?;
        let fn_destroy: TypedFunc<(), i32> = instance
            .get_typed_func(&mut store, "controls_destroy")
            .map_err(|err| HarnessError::Wasm(format!("missing controls_destroy export: {err}")))?;
        let fn_gesture_test_reset: TypedFunc<(), ()> = instance
            .get_typed_func(&mut store, "gesture_test_reset")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing gesture_test_reset export: {err}"))
            })?;
        let fn_gesture_test_feed: TypedFunc<(u32, u32), i32> = instance
            .get_typed_func(&mut store, "gesture_test_feed")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing gesture_test_feed export: {err}"))
            })?;
        let fn_gesture_decisions_ptr: TypedFunc<(), u32> = instance
            .get_typed_func(&mut store, "gesture_decisions_ptr")
            .map_err(|err| {
                HarnessError::Wasm(format!("missing gesture_decisions_ptr export: {err}"))
            })?;
        // Initialize the controls module with explicit dimensions
        let status = fn_init
            .call(&mut store, (config.width, config.height))
            .map_err(|err| HarnessError::Wasm(format!("controls_init failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_init returned error: {status}"
            )));
        }
        Ok(Self {
            store,
            instance,
            memory,
            fn_malloc,
            fn_free,
            fn_load_ui,
            fn_update_state,
            fn_apply_patch,
            host_commands,
            host_reports,
            host_events,
            proxy_reports,
            fn_host_message,
            fn_pointer_decisions_count,
            fn_pointer_decisions_ptr,
            fn_pointer_decisions_clear,
            fn_pointer_active_count,
            fn_cmd_patch_probe,
            fn_cmd_spec_decode_probe,
            fn_key_event,
            fn_text_input,
            fn_get_focused_text,
            fn_take_clipboard_request,
            fn_get_clipboard_text,
            fn_tick,
            fn_get_framebuffer,
            fn_dump_tree,
            fn_set_breakpoint,
            fn_set_theme_dark,
            fn_set_theme_family,
            fn_set_dpi,
            fn_resize,
            fn_destroy,
            destroyed: false,
            fn_gesture_test_reset,
            fn_gesture_test_feed,
            fn_gesture_decisions_ptr,
            width: config.width,
            height: config.height,
            stderr_pipe,
        })
    }
    /// Everything the guest has written to WASI stderr so far, decoded
    /// lossily. Empty unless [`HostConfig::with_captured_stderr`] was set —
    /// which is why the caller asserting on a log line must set it, and why an
    /// empty result here never by itself proves the guest stayed quiet.
    #[must_use]
    pub fn captured_stderr(&self) -> String {
        self.stderr_pipe.as_ref().map_or_else(String::new, |pipe| {
            String::from_utf8_lossy(&pipe.contents()).into_owned()
        })
    }
    /// Push a protobuf UI AST to the WASM module, rebuilding the widget tree.
    ///
    /// # Errors
    ///
    /// Returns an error if memory allocation or the WASM call fails.
    pub fn load_ui(&mut self, data: &[u8]) -> Result<(), HarnessError> {
        let func = self.fn_load_ui.clone();
        self.call_with_buffer(data, &func, "controls_load_ui")
    }
    /// Raw `controls_load_ui` returning the module's STATUS code without
    /// erroring on a non-zero (rejection) status — the load-path twin of
    /// `resize_raw`/`apply_patch`.
    ///
    /// Decode-limit contracts (today: nesting depth, plus a fan-out floor)
    /// are REJECTIONS, not harness failures: the assertion under test is
    /// the exact status AND that the guest is left in a defined state. The
    /// typed `load_ui` collapses every non-zero status into one error string,
    /// which cannot distinguish "refused cleanly at the cap" from "trapped".
    ///
    /// # Errors
    ///
    /// Returns an error only if allocation or the wasm call itself traps — a
    /// trap being precisely the failure a decode-limit guard must prevent.
    pub fn load_ui_raw(&mut self, data: &[u8]) -> Result<i32, HarnessError> {
        let len = u32::try_from(data.len())
            .map_err(|_err| HarnessError::Wasm("ui payload too large for WASM u32".into()))?;
        let ptr = self
            .fn_malloc
            .call(&mut self.store, len)
            .map_err(|err| HarnessError::Wasm(format!("malloc failed: {err}")))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on supported platforms"
        )]
        let ptr_usize = ptr as usize;
        self.memory
            .write(&mut self.store, ptr_usize, data)
            .map_err(|err| HarnessError::Wasm(format!("memory write failed: {err}")))?;
        let status = self
            .fn_load_ui
            .call(&mut self.store, (ptr, len))
            .map_err(|err| HarnessError::Wasm(format!("controls_load_ui failed: {err}")))?;
        self.fn_free
            .call(&mut self.store, ptr)
            .map_err(|err| HarnessError::Wasm(format!("free failed: {err}")))?;
        Ok(status)
    }
    /// Push a protobuf `StateUpdate` to the module — the controller-binding
    /// inbound channel (`controls_update_state`).
    ///
    /// # Errors
    ///
    /// Returns an error if memory allocation or the WASM call fails.
    pub fn update_state(&mut self, data: &[u8]) -> Result<(), HarnessError> {
        let func = self.fn_update_state.clone();
        self.call_with_buffer(data, &func, "controls_update_state")
    }
    /// Apply a `ScreenPatch` (`controls_apply_patch` — partial tree
    /// update). Returns the module's STATUS code (0 ok; negative
    /// `PATCH_ERR_*` classes) so failure-contract tests can assert the
    /// exact code — a nonzero status is a host full-reload signal, not a
    /// harness error.
    ///
    /// # Errors
    ///
    /// Returns an error if memory allocation or the WASM call itself fails.
    pub fn apply_patch(&mut self, data: &[u8]) -> Result<i32, HarnessError> {
        let len = u32::try_from(data.len())
            .map_err(|_err| HarnessError::Wasm("patch too large for WASM u32".into()))?;
        let ptr = self
            .fn_malloc
            .call(&mut self.store, len)
            .map_err(|err| HarnessError::Wasm(format!("malloc failed: {err}")))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on supported platforms"
        )]
        let ptr_usize = ptr as usize;
        self.memory
            .write(&mut self.store, ptr_usize, data)
            .map_err(|err| HarnessError::Wasm(format!("memory write failed: {err}")))?;
        let status = self
            .fn_apply_patch
            .call(&mut self.store, (ptr, len))
            .map_err(|err| HarnessError::Wasm(format!("controls_apply_patch failed: {err}")))?;
        self.fn_free
            .call(&mut self.store, ptr)
            .map_err(|err| HarnessError::Wasm(format!("free failed: {err}")))?;
        Ok(status)
    }
    /// Drain the commands the module pushed through `host_command` (the
    /// cmd-out channel, R5b): each entry is the raw OPAQUE `cmd.*` protobuf
    /// the renderer relayed, in emission order. Tests prost-decode these.
    pub fn take_host_commands(&mut self) -> Vec<Vec<u8>> {
        self.host_commands
            .lock()
            .map(|mut sink| std::mem::take(&mut *sink))
            .unwrap_or_default()
    }
    /// Drain the reports the module pushed through `host_report` (the
    /// hover/cursor feedback channel, R5b HOST_REPORT): each entry is the raw
    /// OPAQUE `ui.WasmToHost` protobuf, in emission order. Tests prost-decode
    /// these to assert the hovered uid + the requested cursor.
    pub fn take_host_reports(&mut self) -> Vec<Vec<u8>> {
        self.host_reports
            .lock()
            .map(|mut sink| std::mem::take(&mut *sink))
            .unwrap_or_default()
    }
    /// Drain the envelopes the module pushed through `host_event` (the
    /// named-event lane, ABI v2): each entry is the raw UTF-8 JSON envelope
    /// (`{"v":1,"tag":...,"origin":...,"event":...,"seq":...,"value":...}`),
    /// in emission order. Tests serde_json-parse these to assert the closed
    /// six-key contract, escaping, and per-instance seq monotonicity.
    pub fn take_host_events(&mut self) -> Vec<Vec<u8>> {
        self.host_events
            .lock()
            .map(|mut sink| std::mem::take(&mut *sink))
            .unwrap_or_default()
    }
    /// Drain the reports the module pushed through `host_proxy_report`
    /// (the host-proxy positioning stream), in emission order.
    pub fn take_proxy_reports(&mut self) -> Vec<ProxyReport> {
        self.proxy_reports
            .lock()
            .map(|mut sink| std::mem::take(&mut *sink))
            .unwrap_or_default()
    }
    /// Advance LVGL by `elapsed_ms`. Returns `true` if the framebuffer changed.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn tick(&mut self, elapsed_ms: u32) -> Result<bool, HarnessError> {
        let changed = self
            .fn_tick
            .call(&mut self.store, elapsed_ms)
            .map_err(|err| HarnessError::Wasm(format!("tick failed: {err}")))?;
        Ok(changed != 0)
    }
    /// Read the RGBA framebuffer from WASM memory as an owned `Vec<u8>`.
    ///
    /// # Errors
    ///
    /// Returns an error if the framebuffer pointer is out of WASM memory bounds.
    pub fn read_framebuffer(&mut self) -> Result<Vec<u8>, HarnessError> {
        let fb_ptr = self
            .fn_get_framebuffer
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("get_framebuffer failed: {err}")))?;
        let fb_size = self
            .width
            .checked_mul(self.height)
            .and_then(|pixels| pixels.checked_mul(4))
            .ok_or_else(|| HarnessError::Framebuffer("framebuffer size overflow".into()))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on all supported platforms (32-bit+)"
        )]
        let start = fb_ptr as usize;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on all supported platforms (32-bit+)"
        )]
        let end = start
            .checked_add(fb_size as usize)
            .ok_or_else(|| HarnessError::Framebuffer("framebuffer offset overflow".into()))?;
        let mem_data = self.memory.data(&self.store);
        let slice = mem_data.get(start..end).ok_or_else(|| {
            HarnessError::Framebuffer("framebuffer out of WASM memory bounds".into())
        })?;
        Ok(slice.to_vec())
    }
    /// Dump the active widget tree as a JSON string — the PRIMARY semantic-diff
    /// oracle. Calls `controls_dump_tree`, which returns a pointer to a
    /// NUL-terminated JSON string (type + coords + children) in linear memory.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails, the pointer is out of bounds,
    /// the string is not NUL-terminated, or the bytes are not valid UTF-8.
    pub fn dump_tree(&mut self) -> Result<String, HarnessError> {
        let ptr = self
            .fn_dump_tree
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("dump_tree failed: {err}")))?;
        self.read_cstr(ptr, "dump_tree")
    }
    /// Set the responsive breakpoint tier (0=sm, 1=md, 2=lg, 3=xl).
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn set_breakpoint(&mut self, bp_tier: i32) -> Result<(), HarnessError> {
        let status = self
            .fn_set_breakpoint
            .call(&mut self.store, bp_tier)
            .map_err(|err| HarnessError::Wasm(format!("set_breakpoint failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_set_breakpoint returned error: {status}"
            )));
        }
        Ok(())
    }
    /// Set the dark/light theme (0=light, 1=dark).
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn set_theme_dark(&mut self, dark: i32) -> Result<(), HarnessError> {
        let status = self
            .fn_set_theme_dark
            .call(&mut self.store, dark)
            .map_err(|err| HarnessError::Wasm(format!("set_theme_dark failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_set_theme_dark returned error: {status}"
            )));
        }
        Ok(())
    }
    /// Set the theme family (0=asgard default / 1=vanilla stock-restatement /
    /// 2=stock — the child theme's apply is skipped). ABI 3.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails or the module rejects the
    /// family (out of range).
    pub fn set_theme_family(&mut self, family: i32) -> Result<(), HarnessError> {
        let status = self
            .fn_set_theme_family
            .call(&mut self.store, family)
            .map_err(|err| HarnessError::Wasm(format!("set_theme_family failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_set_theme_family returned error: {status}"
            )));
        }
        Ok(())
    }
    /// Set the DPI for the controls module.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    /// Resize the display + framebuffer to `width` × `height` pixels.
    /// On success the host's framebuffer dimensions follow, so
    /// `read_framebuffer` returns the NEW size.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails or the module rejects the
    /// dimensions (zero, or buffer allocation failure).
    pub fn resize(&mut self, width: u32, height: u32) -> Result<(), HarnessError> {
        let status = self
            .fn_resize
            .call(&mut self.store, (width, height))
            .map_err(|err| HarnessError::Wasm(format!("resize failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_resize({width}, {height}) returned error: {status}"
            )));
        }
        self.width = width;
        self.height = height;
        Ok(())
    }
    /// Raw `controls_resize` returning the status code WITHOUT erroring on a
    /// non-zero (rejection) status — for asserting the reject paths (a
    /// dimension-cap / overflow rejection returns -1, leaving the display
    /// intact). On a `0` (accepted) status the tracked dims are updated.
    ///
    /// # Errors
    ///
    /// Returns an error only if the wasm call itself traps.
    pub fn resize_raw(&mut self, width: u32, height: u32) -> Result<i32, HarnessError> {
        let status = self
            .fn_resize
            .call(&mut self.store, (width, height))
            .map_err(|err| HarnessError::Wasm(format!("resize failed: {err}")))?;
        if status == 0 {
            self.width = width;
            self.height = height;
        }
        Ok(status)
    }
    pub fn set_dpi(&mut self, dpi: i32) -> Result<(), HarnessError> {
        let status = self
            .fn_set_dpi
            .call(&mut self.store, dpi)
            .map_err(|err| HarnessError::Wasm(format!("set_dpi failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_set_dpi returned error: {status}"
            )));
        }
        Ok(())
    }
    /// Forward a raw `ui.HostToWasm` envelope (already-encoded bytes) and
    /// return the module's `controls_host_message` rc verbatim (negative =
    /// decode/validate reject; 0 = handled; positive = a benign no-op class).
    /// Unlike the typed helpers this does NOT treat a nonzero rc as an error,
    /// so reject/no-op contracts can assert the exact code.
    ///
    /// # Errors
    ///
    /// Returns an error only if malloc, the WASM call, or free fails.
    pub fn host_message_raw(&mut self, bytes: &[u8]) -> Result<i32, HarnessError> {
        let len = u32::try_from(bytes.len())
            .map_err(|_err| HarnessError::Wasm("host message too large for u32".into()))?;
        let ptr = self
            .fn_malloc
            .call(&mut self.store, len.max(1))
            .map_err(|err| HarnessError::Wasm(format!("malloc failed: {err}")))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on supported platforms"
        )]
        let ptr_usize = ptr as usize;
        self.memory
            .write(&mut self.store, ptr_usize, bytes)
            .map_err(|err| HarnessError::Wasm(format!("memory write failed: {err}")))?;
        let rc = self
            .fn_host_message
            .call(&mut self.store, (ptr, len))
            .map_err(|err| HarnessError::Wasm(format!("controls_host_message failed: {err}")))?;
        self.fn_free
            .call(&mut self.store, ptr)
            .map_err(|err| HarnessError::Wasm(format!("free failed: {err}")))?;
        Ok(rc)
    }
    /// Forward a typed [`PointerEvent`] through `controls_host_message`,
    /// returning the module's rc.
    ///
    /// # Errors
    ///
    /// Returns an error if malloc, the WASM call, or free fails.
    pub fn pointer(&mut self, event: PointerEvent) -> Result<i32, HarnessError> {
        self.host_message_raw(&event.encode_envelope())
    }
    /// Forward a typed [`Lifecycle`] through `controls_host_message`,
    /// returning the module's rc.
    ///
    /// # Errors
    ///
    /// Returns an error if malloc, the WASM call, or free fails.
    pub fn lifecycle(&mut self, event: Lifecycle) -> Result<i32, HarnessError> {
        self.host_message_raw(&event.encode_envelope())
    }
    /// Count of buffered gesture decisions (`controls_pointer_decisions_count`)
    /// — the FSM-fed routing oracle: VIDEO-owned pointers feed the FSM (count
    /// climbs), LVGL-owned pointers do not.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn pointer_decisions_count(&mut self) -> Result<u32, HarnessError> {
        self.fn_pointer_decisions_count
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("pointer_decisions_count failed: {err}")))
    }
    /// Read back the buffered [`GestureDecision`]s the real pointer pipeline
    /// fed into the FSM (`controls_pointer_decisions_ptr`).
    ///
    /// # Errors
    ///
    /// Returns an error if a WASM call fails or the array is out of bounds.
    pub fn pointer_decisions(&mut self) -> Result<Vec<GestureDecision>, HarnessError> {
        let n = self.pointer_decisions_count()?;
        let ptr = self
            .fn_pointer_decisions_ptr
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("pointer_decisions_ptr failed: {err}")))?;
        let n_usize = usize::try_from(n)
            .map_err(|_err| HarnessError::Wasm("decision count too large".into()))?;
        let size = n_usize
            .checked_mul(GestureDecision::WIRE_SIZE)
            .ok_or_else(|| HarnessError::Wasm("decision size overflow".into()))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on supported platforms"
        )]
        let start = ptr as usize;
        let end = start
            .checked_add(size)
            .ok_or_else(|| HarnessError::Wasm("decision offset overflow".into()))?;
        let mem_data = self.memory.data(&self.store);
        let slice = mem_data
            .get(start..end)
            .ok_or_else(|| HarnessError::Wasm("decisions out of WASM memory bounds".into()))?;
        Ok(GestureDecision::decode_all(slice))
    }
    /// Clear the buffered gesture decisions (`controls_pointer_decisions_clear`).
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn pointer_decisions_clear(&mut self) -> Result<(), HarnessError> {
        self.fn_pointer_decisions_clear
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("pointer_decisions_clear failed: {err}")))
    }
    /// Active slots in the bounded pointer table
    /// (`controls_pointer_active_count`) — the table-invariant oracle.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn pointer_active_count(&mut self) -> Result<u32, HarnessError> {
        self.fn_pointer_active_count
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("pointer_active_count failed: {err}")))
    }
    /// Probe the cmd_patch slot-bounds guard (`controls_cmd_patch_probe`) — the
    /// R5b memory-safety oracle. Returns 0 if the slot is accepted (one
    /// host_command fired), -1 if rejected (none). An out-of-bounds or wrapping
    /// slot MUST return -1 and never write past the scratch buffer.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn cmd_patch_probe(
        &mut self,
        byte_offset: u32,
        byte_width: u32,
        template_len: u32,
    ) -> Result<i32, HarnessError> {
        self.fn_cmd_patch_probe
            .call(&mut self.store, (byte_offset, byte_width, template_len))
            .map_err(|err| HarnessError::Wasm(format!("cmd_patch_probe failed: {err}")))
    }
    /// Feed a CRAFTED `ui.CmdSpec` `.pb` through the renderer's untrusted
    /// decode boundary (`controls_cmd_spec_decode_probe` → nanopb pb_decode +
    /// `cmd_spec_copy_from_proto`). Unlike [`Self::cmd_patch_probe`], which
    /// hand-builds the `cmd_spec_t` and so bypasses decode + the copy guard,
    /// this exercises the real §8 host-untrusted path with wire bytes. Returns
    /// 0 accepted, -1 the copy rejected the slot bounds (overflowing/wrapping
    /// byte_offset+byte_width), -2 nanopb rejected the bytes (root_template past
    /// the 128-byte cap, or more patches than `ui_CmdSpec.patches` holds — 8
    /// today; the generated header is the bound's home, and
    /// `rejects_more_than_eight_patches_at_nanopb_cap` pins it executably).
    ///
    /// # Errors
    ///
    /// Returns an error only if malloc, the WASM call, or free fails.
    pub fn cmd_spec_decode_probe(&mut self, bytes: &[u8]) -> Result<i32, HarnessError> {
        let len = u32::try_from(bytes.len())
            .map_err(|_err| HarnessError::Wasm("cmd spec too large for u32".into()))?;
        let ptr = self
            .fn_malloc
            .call(&mut self.store, len.max(1))
            .map_err(|err| HarnessError::Wasm(format!("malloc failed: {err}")))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on supported platforms"
        )]
        let ptr_usize = ptr as usize;
        self.memory
            .write(&mut self.store, ptr_usize, bytes)
            .map_err(|err| HarnessError::Wasm(format!("memory write failed: {err}")))?;
        let rc = self
            .fn_cmd_spec_decode_probe
            .call(&mut self.store, (ptr, len))
            .map_err(|err| HarnessError::Wasm(format!("cmd_spec_decode_probe failed: {err}")))?;
        self.fn_free
            .call(&mut self.store, ptr)
            .map_err(|err| HarnessError::Wasm(format!("free failed: {err}")))?;
        Ok(rc)
    }
    /// Reset the pure gesture FSM (harness-only `gesture_test_reset`).
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn gesture_test_reset(&mut self) -> Result<(), HarnessError> {
        self.fn_gesture_test_reset
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("gesture_test_reset failed: {err}")))?;
        Ok(())
    }
    /// Feed a pointer/wheel event sequence into the pure gesture FSM and read
    /// back the [`GestureDecision`]s the LAST event emitted (matching the TS
    /// handlers' per-call return). The events are serialized to the module's
    /// wire layout, written into a malloc'd guest buffer, fed via
    /// `gesture_test_feed`, then the decision array is read from
    /// `gesture_decisions_ptr`.
    ///
    /// # Errors
    ///
    /// Returns an error if malloc, the WASM call, free, or the memory read
    /// fails (out of bounds).
    pub fn feed_gestures(
        &mut self,
        events: &[GestureEvent],
    ) -> Result<Vec<GestureDecision>, HarnessError> {
        let bytes = GestureEvent::encode_all(events);
        let len = u32::try_from(bytes.len())
            .map_err(|_err| HarnessError::Wasm("gesture events too large for u32".into()))?;
        let count = u32::try_from(events.len())
            .map_err(|_err| HarnessError::Wasm("gesture event count too large for u32".into()))?;
        let ptr = self
            .fn_malloc
            .call(&mut self.store, len.max(1))
            .map_err(|err| HarnessError::Wasm(format!("malloc failed: {err}")))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on all supported platforms (32-bit+)"
        )]
        let ptr_usize = ptr as usize;
        self.memory
            .write(&mut self.store, ptr_usize, &bytes)
            .map_err(|err| HarnessError::Wasm(format!("memory write failed: {err}")))?;
        let n = self
            .fn_gesture_test_feed
            .call(&mut self.store, (ptr, count))
            .map_err(|err| HarnessError::Wasm(format!("gesture_test_feed failed: {err}")))?;
        self.fn_free
            .call(&mut self.store, ptr)
            .map_err(|err| HarnessError::Wasm(format!("free failed: {err}")))?;
        let decisions_ptr = self
            .fn_gesture_decisions_ptr
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("gesture_decisions_ptr failed: {err}")))?;
        let n_usize = usize::try_from(n.max(0))
            .map_err(|_err| HarnessError::Wasm("negative decision count".into()))?;
        let size = n_usize
            .checked_mul(GestureDecision::WIRE_SIZE)
            .ok_or_else(|| HarnessError::Wasm("decision size overflow".into()))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on all supported platforms (32-bit+)"
        )]
        let start = decisions_ptr as usize;
        let end = start
            .checked_add(size)
            .ok_or_else(|| HarnessError::Wasm("decision offset overflow".into()))?;
        let mem_data = self.memory.data(&self.store);
        let slice = mem_data
            .get(start..end)
            .ok_or_else(|| HarnessError::Wasm("decisions out of WASM memory bounds".into()))?;
        Ok(GestureDecision::decode_all(slice))
    }
    /// Enqueue a keypad key edge (`controls_key_event`). `key` is an
    /// `lv_key_t` control value (`lvgl/src/core/lv_group.h`) or a Unicode
    /// codepoint; `pressed` selects the press/release edge.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails or the module's key queue
    /// is full (the module returns nonzero).
    pub fn key_event(&mut self, key: u32, pressed: bool) -> Result<(), HarnessError> {
        let status = self
            .fn_key_event
            .call(&mut self.store, (key, u32::from(pressed)))
            .map_err(|err| HarnessError::Wasm(format!("key_event failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_key_event({key}, {pressed}) returned error: {status}"
            )));
        }
        Ok(())
    }
    /// Send one full keystroke: a press edge followed by a release edge.
    ///
    /// # Errors
    ///
    /// Returns an error if either underlying `key_event` call fails.
    pub fn key(&mut self, key: u32) -> Result<(), HarnessError> {
        self.key_event(key, true)?;
        self.key_event(key, false)
    }
    /// Insert UTF-8 text into the group-focused textarea
    /// (`controls_text_input` — the paste path).
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails or the module rejects the
    /// text (no textarea focused, or text exceeds the module's cap).
    pub fn text_input(&mut self, text: &str) -> Result<(), HarnessError> {
        let func = self.fn_text_input.clone();
        self.call_with_buffer(text.as_bytes(), &func, "controls_text_input")
    }
    /// Whole text of the group-focused textarea
    /// (`controls_get_focused_text`), or `None` when no textarea is focused
    /// OR the field is in PASSWORD mode.
    ///
    /// This is NOT the copy path — that is
    /// [`Self::take_clipboard_request`] plus [`Self::clipboard_text`], which
    /// carry the SELECTION. This accessor is the whole field, and it refuses
    /// a password field rather than handing back its cleartext.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails or the returned pointer is
    /// invalid.
    pub fn focused_text(&mut self) -> Result<Option<String>, HarnessError> {
        let ptr = self
            .fn_get_focused_text
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("get_focused_text failed: {err}")))?;
        if ptr == 0 {
            return Ok(None);
        }
        self.read_cstr(ptr, "focused_text").map(Some)
    }
    /// Drain the module's pending clipboard request
    /// (`controls_take_clipboard_request`): 0 none, 1 copy, 2 cut, 3 paste.
    /// Reading it CLEARS it, so a second call returns 0.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails.
    pub fn take_clipboard_request(&mut self) -> Result<u32, HarnessError> {
        self.fn_take_clipboard_request
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("take_clipboard_request failed: {err}")))
    }
    /// Bytes the module staged for the host to put on the system clipboard
    /// (`controls_get_clipboard_text`), or `None` when nothing is staged.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM call fails or the returned pointer is
    /// invalid.
    pub fn clipboard_text(&mut self) -> Result<Option<String>, HarnessError> {
        let ptr = self
            .fn_get_clipboard_text
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("get_clipboard_text failed: {err}")))?;
        if ptr == 0 {
            return Ok(None);
        }
        self.read_cstr(ptr, "clipboard_text").map(Some)
    }
    /// Call the WASM module's destroy function for cleanup.
    ///
    /// # Errors
    ///
    /// Returns an error if the WASM destroy call fails.
    pub fn destroy(&mut self) -> Result<(), HarnessError> {
        let status = self
            .fn_destroy
            .call(&mut self.store, ())
            .map_err(|err| HarnessError::Wasm(format!("destroy failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "controls_destroy returned error: {status}"
            )));
        }
        self.destroyed = true;
        Ok(())
    }
    // --- Private helpers ---

    /// Read a NUL-terminated UTF-8 string out of linear memory at `ptr`.
    ///
    /// # Errors
    ///
    /// Returns an error if the pointer is out of bounds, the string is not
    /// NUL-terminated, or the bytes are not valid UTF-8.
    fn read_cstr(&self, ptr: u32, what: &str) -> Result<String, HarnessError> {
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on all supported platforms (32-bit+)"
        )]
        let start = ptr as usize;
        let mem_data = self.memory.data(&self.store);
        let tail = mem_data
            .get(start..)
            .ok_or_else(|| HarnessError::Wasm(format!("{what} pointer out of bounds")))?;
        let nul = tail
            .iter()
            .position(|&byte| byte == 0)
            .ok_or_else(|| HarnessError::Wasm(format!("{what} string not NUL-terminated")))?;
        let bytes = tail
            .get(..nul)
            .ok_or_else(|| HarnessError::Wasm(format!("{what} slice out of bounds")))?;
        let text = std::str::from_utf8(bytes)
            .map_err(|err| HarnessError::Wasm(format!("{what} invalid UTF-8: {err}")))?;
        Ok(text.to_owned())
    }
    /// Common pattern: malloc -> write -> call(ptr, len) -> free.
    ///
    /// # Errors
    ///
    /// Returns an error if malloc, the WASM call, or free fails.
    fn call_with_buffer(
        &mut self,
        data: &[u8],
        func: &TypedFunc<(u32, u32), i32>,
        name: &str,
    ) -> Result<(), HarnessError> {
        let len = u32::try_from(data.len())
            .map_err(|_err| HarnessError::Wasm("data too large for WASM u32".into()))?;
        let ptr = self
            .fn_malloc
            .call(&mut self.store, len)
            .map_err(|err| HarnessError::Wasm(format!("malloc failed: {err}")))?;
        #[expect(
            clippy::as_conversions,
            reason = "u32→usize is lossless on all supported platforms (32-bit+)"
        )]
        let ptr_usize = ptr as usize;
        self.memory
            .write(&mut self.store, ptr_usize, data)
            .map_err(|err| HarnessError::Wasm(format!("memory write failed: {err}")))?;
        let status = func
            .call(&mut self.store, (ptr, len))
            .map_err(|err| HarnessError::Wasm(format!("{name} failed: {err}")))?;
        self.fn_free
            .call(&mut self.store, ptr)
            .map_err(|err| HarnessError::Wasm(format!("free failed: {err}")))?;
        if status != 0 {
            return Err(HarnessError::Wasm(format!(
                "{name} returned error: {status}"
            )));
        }
        Ok(())
    }
}
impl Drop for ControlsHost {
    fn drop(&mut self) {
        // RAII backstop only: an explicit destroy() already tore the guest
        // down — calling again from Drop is the double-destroy pattern that
        // livelocked pre-guard wasms (2026-07-19 root cause).
        if !self.destroyed
            && let Err(err) = self.destroy()
        {
            tracing::warn!("controls destroy error during drop: {err}");
        }
    }
}
// Required for Debug derive on types that contain ControlsHost.
impl std::fmt::Debug for ControlsHost {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ControlsHost")
            .field("width", &self.width)
            .field("height", &self.height)
            .finish_non_exhaustive()
    }
}
