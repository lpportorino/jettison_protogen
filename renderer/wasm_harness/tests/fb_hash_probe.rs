//! Cross-engine determinism probe — the standing tool for re-proving
//! wasmtime ≡ GraalWasm on a NEW wasm build (e.g. a newly themed ABI
//! revision): renders
//! the 4 probe fixtures × light/dark under the pinned protocol and WRITES
//! the raw RGBA framebuffers to `target/fb-probe/` for shell-side sha256
//! comparison against the GraalWasm runner's dumps (the devcards tool's
//! `dev/spike_r22.clj` counterpart). No hashing dep on purpose —
//! `sha256sum` does the hashing outside, so this tool never touches
//! Cargo.lock. Initial result on the stock ABI-2 wasm: 8/8 EQUAL.
#![allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::print_stdout,
    missing_docs,
    unused_crate_dependencies,
    reason = "throwaway spike probe, not library code"
)]
use lvgl_harness::{ControlsHost, HostConfig, RENDER_TICKS, TICK_MS};
use std::path::PathBuf;
fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("wasm_harness has a parent")
        .to_path_buf()
}
const SPIKE_FIXTURES: &[&str] = &["vr_button", "vr_slider", "vc_clip", "vr_svg_icon"];
#[test]
fn dump_spike_framebuffers() {
    let root = repo_root();
    let config = HostConfig::new(root.join("output/controls.wasm"), 480, 320)
        .with_wasi_root(root.join("assets"));
    let mut host = ControlsHost::new(&config).expect("host boot");
    let out_dir = root.join("wasm_harness/target/fb-probe");
    std::fs::create_dir_all(&out_dir).expect("mkdir fb-probe");
    for name in SPIKE_FIXTURES {
        let pb =
            std::fs::read(root.join(format!("output/fixtures/{name}.pb"))).expect("read fixture");
        for dark in [0_i32, 1_i32] {
            // The pinned protocol — must stay call-for-call identical to
            // devcards.host/render-card! (R2.1).
            host.set_breakpoint(0).expect("set_breakpoint");
            host.set_theme_dark(dark).expect("set_theme_dark");
            host.set_dpi(160).expect("set_dpi");
            host.load_ui(&pb).expect("load_ui");
            let mut flushed = false;
            for _ in 0..RENDER_TICKS {
                if host.tick(TICK_MS).expect("tick") {
                    flushed = true;
                }
            }
            assert!(flushed, "no flush for {name} dark={dark}");
            let fb = host.read_framebuffer().expect("read_framebuffer");
            let path = out_dir.join(format!("{name}_dark{dark}.raw"));
            std::fs::write(&path, &fb).expect("write raw fb");
            println!("wrote {} ({} bytes)", path.display(), fb.len());
        }
    }
}
