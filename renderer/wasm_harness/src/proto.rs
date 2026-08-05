//! Generated prost bindings for the `cmd.*` device-command protobufs.
//!
//! R5b cmd-out: the renderer relays OPAQUE `cmd.*` bytes via `host_command`;
//! these bindings let the harness DECODE the captured bytes and assert on the
//! command variant + field values (a video tap → `RotateToNdc` at the NDC
//! point, a 2-finger pinch → a zoom step, a value-widget click → the widget's
//! value command). The pinch case decodes two ways and BOTH are exercised: a
//! `SetZoomTableValue` whose DELTA slot carries the signed step, and — where the
//! two directions are different EMPTY commands — the `NextZoomTablePos` /
//! `PrevZoomTablePos` variant a direction-selected `GestureSpec` pair chooses
//! between, for which the VARIANT is the whole assertion. The included files are the checkout's OWN prost
//! output (`output/rust/` at the protogen root, two levels above the
//! renderer/wasm_harness crate), pulled in by `include!` so the harness never
//! hand-maintains a divergent copy (cohesion: one home).
//!
//! The dotted filenames (`cmd.rotary_platform.rs`) map to nested modules
//! (`cmd::rotary_platform`); the generated code's `super::super::ser` path is
//! satisfied by the `ser` module living at this module's root alongside `cmd`.
//! Paths in `include!` are relative to THIS file's directory (`wasm_harness/src`).
#![allow(clippy::all, clippy::pedantic, clippy::nursery, missing_docs)]
pub mod ser {
    include!("../../../output/rust/ser.rs");
}
/// The `ui.*` bridge-plane protobufs — here the `ui.WasmToHost` feedback
/// envelope (`HoverState` / `CursorRequest`) the renderer pb_encodes and relays
/// via `host_report` (R5b HOST_REPORT). Lets the harness decode the captured
/// report bytes and assert the hovered uid + the requested cursor. `super::ser`
/// (referenced by some `ui` enums) resolves to the `ser` module above. Same
/// `include!` discipline as `cmd` — one home, never a hand-maintained copy.
pub mod ui {
    include!("../../../output/rust/ui.rs");
}
pub mod cmd {
    include!("../../../output/rust/cmd.rs");
    pub mod rotary_platform {
        include!("../../../output/rust/cmd.rotary_platform.rs");
    }
    pub mod day_camera {
        include!("../../../output/rust/cmd.day_camera.rs");
    }
    pub mod heat_camera {
        include!("../../../output/rust/cmd.heat_camera.rs");
    }
    pub mod cv {
        include!("../../../output/rust/cmd.cv.rs");
    }
    pub mod compass {
        include!("../../../output/rust/cmd.compass.rs");
    }
    pub mod gps {
        include!("../../../output/rust/cmd.gps.rs");
    }
    pub mod lrf {
        include!("../../../output/rust/cmd.lrf.rs");
    }
    pub mod lrf_calib {
        include!("../../../output/rust/cmd.lrf_calib.rs");
    }
    pub mod osd {
        include!("../../../output/rust/cmd.osd.rs");
    }
    pub mod system {
        include!("../../../output/rust/cmd.system.rs");
    }
    pub mod lira {
        include!("../../../output/rust/cmd.lira.rs");
    }
    pub mod power {
        include!("../../../output/rust/cmd.power.rs");
    }
    pub mod pmu {
        include!("../../../output/rust/cmd.pmu.rs");
    }
    pub mod heater {
        include!("../../../output/rust/cmd.heater.rs");
    }
}
