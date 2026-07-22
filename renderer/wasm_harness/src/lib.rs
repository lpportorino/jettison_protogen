//! LVGL Controls WASM test harness.
//!
//! Loads a `controls.wasm` module via wasmtime, pushes protobuf UI definitions,
//! ticks LVGL to render, reads the RGBA framebuffer, and saves PNGs. Supports
//! hot-reloading of both the WASM module and `.pb` files during development.
pub mod analysis;
pub mod error;
pub mod framebuffer;
pub mod proto;
pub mod tree_diff;
pub mod wasm_host;
pub use error::HarnessError;
pub use framebuffer::{composite_on_checkerboard, save_png};
pub use tree_diff::tree_diff;
pub use wasm_host::{
    ControlsHost, GestureDecision, GestureEvent, HostConfig, Lifecycle, PointerEvent, PointerKind,
    PointerPhase, ProxyReport, ThemeMode,
};
/// Milliseconds advanced per `controls_tick` call.
pub const TICK_MS: u32 = 16;
/// The pinned render tick budget: every render ticks EXACTLY this many
/// times (no adaptive settle), so a render is a deterministic function of
/// the module + inputs — load-bearing for the demo-parity differential,
/// where BOTH modules (controls.wasm and reference.wasm) must observe the
/// same elapsed time. The first flush lands on tick 1; the remaining ticks
/// are the settle window the adaptive loop used to take. Anim-frozen
/// values authored in EDN (demo arcs at 21, "Revenue: 21 %", needle at 10)
/// are functions of this budget × `TICK_MS` — changing either re-freezes
/// them.
pub const RENDER_TICKS: u32 = 3;
/// Binary-only dependencies — suppress `unused_crate_dependencies` lint
/// when compiling lib (these are used only in `main.rs`).
mod _bin_deps {
    use clap as _;
    use notify as _;
    use notify_debouncer_mini as _;
    use tracing_subscriber as _;
}
