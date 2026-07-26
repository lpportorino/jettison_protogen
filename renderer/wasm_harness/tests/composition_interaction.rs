//! Composition-lane interaction suite — the WASMTIME half of the
//! both-engine proof for the public legos (tools/devcards/corpus/
//! composition.edn): loads the SAME card `.pb` bytes the devcards runner
//! built, renders them under the pinned protocol, byte-compares the raw
//! RGBA framebuffers against the GraalWasm dumps, and drives the same
//! pointer interactions (press-seek / no-duplicate / drag / ext-click
//! envelope / dock fold identity) through the harness's existing
//! `PointerEvent` + `host_event` capture. The GraalWasm mirror of the
//! interaction lane lives in tools/devcards (devcards.interaction);
//! agreement of the two production-host engines on every pixel and every
//! envelope is what this suite pins.
//!
//! Inputs are repo-relative, produced by the devcards runner (`fixtures`, or
//! `fixtures-prebuilt` on a host with no WASI toolchain, persists them under
//! tools/devcards/out/composition/): cards/<slug>.pb +
//! fb/<slug>_dark<d>.raw, plus renderer/output/controls.wasm +
//! renderer/assets. A missing input is a battery SEQUENCING bug (run the
//! matching devcards lane first) — fail loud, never skip.
#![allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::panic,
    clippy::print_stdout,
    clippy::indexing_slicing,
    missing_docs,
    unused_crate_dependencies,
    reason = "interaction suite, test-shaped, not library code"
)]
use lvgl_harness::{ControlsHost, HostConfig, PointerEvent, RENDER_TICKS, TICK_MS};
use std::path::PathBuf;
/// The composition-lane canvas (corpus/composition.edn :canvas).
const WIDTH: u32 = 800;
const HEIGHT: u32 = 480;
const DPI: i32 = 160;
/// The scrubber cards' TRACK rect — MUST match corpus/composition.edn
/// (placement arithmetic: the hit-halo wrapper sits at track-xy minus
/// devcards.legos/scrubber-halo, so the track lands here).
const TRACK_X: i32 = 100;
const TRACK_Y: i32 = 200;
const TRACK_W: i32 = 600;
const TRACK_H: i32 = 20;
/// The renderer's seek_on_press ext-click widening in px at the pinned
/// dpi (LV_DPX(24)) — the hit-halo boundary the envelope test walks.
const EXT_CLICK_PX: i32 = 24;
const CARD_SLUGS: &[&str] = &[
    "lego_scrubber",
    "lego_scrubber-plain",
    "lego_scrubber-noseek",
    "lego_dock-expanded",
    "lego_dock-folded",
];
/// The protogen repo root (wasm_harness -> renderer -> root).
fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("wasm_harness has a parent")
        .parent()
        .expect("renderer has a parent")
        .to_path_buf()
}
/// A repo-relative input produced by the devcards runner; absence is a
/// sequencing bug, never a skip.
fn devcards_input(rel: &str) -> PathBuf {
    let p = repo_root().join("tools/devcards/out/composition").join(rel);
    assert!(
        p.exists(),
        "{} missing — run `make -f renderer.mk fixtures` first, or `fixtures-prebuilt` \
         if this host has no WASI toolchain (the devcards runner persists the \
         composition cards + framebuffers)",
        p.display()
    );
    p
}
fn boot() -> ControlsHost {
    let renderer = repo_root().join("renderer");
    let config = HostConfig::new(renderer.join("output/controls.wasm"), WIDTH, HEIGHT)
        .with_wasi_root(renderer.join("assets"));
    ControlsHost::new(&config).expect("host boot")
}
fn card_bytes(slug: &str) -> Vec<u8> {
    let path = devcards_input(&format!("cards/{slug}.pb"));
    std::fs::read(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
}
/// The pinned render protocol — call-for-call identical to
/// devcards.host/render-card! (set_breakpoint → set_theme_dark → set_dpi →
/// load_ui → tick × RENDER_TICKS @ TICK_MS; at least one flush).
fn render_card(host: &mut ControlsHost, pb: &[u8], dark: i32) -> Vec<u8> {
    host.set_breakpoint(0).expect("set_breakpoint");
    host.set_theme_dark(dark).expect("set_theme_dark");
    host.set_dpi(DPI).expect("set_dpi");
    host.load_ui(pb).expect("load_ui");
    let mut flushed = false;
    for _ in 0..RENDER_TICKS {
        if host.tick(TICK_MS).expect("tick") {
            flushed = true;
        }
    }
    assert!(flushed, "no flush within the pinned tick budget");
    let fb = host.read_framebuffer().expect("read_framebuffer");
    let _ = host.take_host_events(); // drain render-time emissions (none expected)
    fb
}
/// Framebuffer px -> NDC (+x right, +y UP, Y-flipped) for the 800x480 canvas.
fn px_to_ndc(x: i32, y: i32) -> (f64, f64) {
    let ndc_x = f64::from(x) / f64::from(WIDTH) * 2.0 - 1.0;
    let ndc_y = 1.0 - f64::from(y) / f64::from(HEIGHT) * 2.0;
    (ndc_x, ndc_y)
}
/// Monotonic event_time (ms) — strictly increasing, far under the FSM's
/// stale-GC window. Shared across tests (parallel-safe atomic).
fn next_event_time() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static CLOCK: AtomicU64 = AtomicU64::new(1000);
    CLOCK.fetch_add(5, Ordering::Relaxed)
}
fn press_px(host: &mut ControlsHost, x: i32, y: i32) {
    let (nx, ny) = px_to_ndc(x, y);
    host.pointer(PointerEvent::down(1, nx, ny, next_event_time()))
        .expect("pointer down");
}
fn move_px(host: &mut ControlsHost, x: i32, y: i32) {
    let (nx, ny) = px_to_ndc(x, y);
    host.pointer(PointerEvent::mv(1, nx, ny, next_event_time()))
        .expect("pointer move");
}
fn release_px(host: &mut ControlsHost, x: i32, y: i32) {
    let (nx, ny) = px_to_ndc(x, y);
    host.pointer(PointerEvent::up(1, nx, ny, next_event_time()))
        .expect("pointer up");
}
fn settle(host: &mut ControlsHost, ticks: u32) {
    for _ in 0..ticks {
        let _ = host.tick(TICK_MS).expect("tick");
    }
}
/// px point at `frac` of the horizontal track, `dy` px below its center.
fn track_px(frac: f64, dy: i32) -> (i32, i32) {
    (
        TRACK_X + (frac * f64::from(TRACK_W)) as i32,
        TRACK_Y + TRACK_H / 2 + dy,
    )
}
/// Drain + parse captured host_event envelopes; return (tag, value) pairs.
fn envelopes(host: &mut ControlsHost) -> Vec<(String, i64)> {
    host.take_host_events()
        .into_iter()
        .map(|bytes| {
            let v: serde_json::Value =
                serde_json::from_slice(&bytes).expect("host_event envelope JSON");
            (
                v["tag"].as_str().expect("tag").to_owned(),
                v["value"].as_i64().expect("value"),
            )
        })
        .collect()
}
fn seek_values(events: &[(String, i64)]) -> Vec<i64> {
    events
        .iter()
        .filter(|(tag, _)| tag == "seek")
        .map(|(_, v)| *v)
        .collect()
}
/// Depth-first preorder walk collecting coords of nodes with `type` == `ty`
/// (mirrors the devcards.interaction tree-seq order so button indices agree).
fn find_type(node: &serde_json::Value, ty: &str, out: &mut Vec<[i64; 4]>) {
    if node["type"].as_str() == Some(ty) {
        let c = node["coords"].as_array().expect("coords");
        out.push([
            c[0].as_i64().unwrap(),
            c[1].as_i64().unwrap(),
            c[2].as_i64().unwrap(),
            c[3].as_i64().unwrap(),
        ]);
    }
    if let Some(children) = node["children"].as_array() {
        for child in children {
            find_type(child, ty, out);
        }
    }
}
/// Cross-engine raw-FB equality: every card × dark/light byte-identical to
/// the GraalWasm dump of the same `.pb` on the same wasm.
#[test]
fn composition_cross_engine_fb() {
    let mut host = boot();
    for slug in CARD_SLUGS {
        let pb = card_bytes(slug);
        for dark in [1_i32, 0_i32] {
            let fb = render_card(&mut host, &pb, dark);
            let expected_path = devcards_input(&format!("fb/{slug}_dark{dark}.raw"));
            let expected = std::fs::read(&expected_path)
                .unwrap_or_else(|e| panic!("read {}: {e}", expected_path.display()));
            assert_eq!(
                fb.len(),
                expected.len(),
                "{slug} dark={dark}: framebuffer size mismatch"
            );
            assert!(
                fb == expected,
                "{slug} dark={dark}: wasmtime framebuffer differs from GraalWasm"
            );
            println!(
                "cross-engine EQUAL: {slug} dark={dark} ({} bytes)",
                fb.len()
            );
        }
    }
}
/// Press-seek identity: the seek envelope arrives immediately after DOWN
/// with the exact stock-mapped value, and release adds NO duplicate.
#[test]
fn scrubber_press_seek_identity() {
    let mut host = boot();
    let pb = card_bytes("lego_scrubber");
    let _ = render_card(&mut host, &pb, 1);
    let (x, y) = track_px(0.70, 0);
    press_px(&mut host, x, y);
    settle(&mut host, 4);
    let after_down = envelopes(&mut host);
    assert_eq!(
        seek_values(&after_down),
        vec![70],
        "press-seek must fire exactly once at DOWN with the stock-mapped value"
    );
    release_px(&mut host, x, y);
    settle(&mut host, 4);
    let after_up = envelopes(&mut host);
    assert!(
        seek_values(&after_up).is_empty(),
        "release must add NO duplicate seek (got {after_up:?})"
    );
    println!("press-seek [70], no duplicate at release");
}
/// Drag continuity: press-seek prepends exactly one immediate value to the
/// stock MOVE stream.
#[test]
fn scrubber_press_drag_stream() {
    let mut host = boot();
    let pb = card_bytes("lego_scrubber");
    let _ = render_card(&mut host, &pb, 1);
    let (x0, y0) = track_px(0.30, 0);
    press_px(&mut host, x0, y0);
    settle(&mut host, 3);
    for frac in [0.45, 0.55, 0.70] {
        let (x, y) = track_px(frac, 0);
        move_px(&mut host, x, y);
        settle(&mut host, 3);
    }
    let (xe, ye) = track_px(0.70, 0);
    release_px(&mut host, xe, ye);
    settle(&mut host, 3);
    let events = envelopes(&mut host);
    assert_eq!(
        seek_values(&events),
        vec![30, 45, 55, 70],
        "drag must stream press value + each MOVE value exactly once"
    );
    println!("drag stream [30, 45, 55, 70]");
}
/// The ext-click envelope THROUGH THE LEGO (the halo wrapper is what lets
/// LVGL's point-on-parent-coords descent reach the slider's widened click
/// area). Fresh load_ui per dy: a repeat tap at an unchanged value fires
/// no VALUE_CHANGED and would fake a miss.
#[test]
fn scrubber_ext_click_envelope() {
    let mut host = boot();
    let pb = card_bytes("lego_scrubber");
    let y2 = TRACK_Y + TRACK_H - 1; // inclusive bottom edge
    let x = TRACK_X + (0.70 * f64::from(TRACK_W)) as i32;
    for dy in [2, 16, EXT_CLICK_PX, EXT_CLICK_PX + 1, 30] {
        let expect_hit = dy <= EXT_CLICK_PX;
        let _ = render_card(&mut host, &pb, 1);
        press_px(&mut host, x, y2 + dy);
        settle(&mut host, 4);
        release_px(&mut host, x, y2 + dy);
        settle(&mut host, 4);
        let events = envelopes(&mut host);
        let hit = !seek_values(&events).is_empty();
        assert_eq!(
            hit,
            expect_hit,
            "dy {dy} below the track edge: expected {} (events {events:?})",
            if expect_hit { "HIT" } else { "MISS" }
        );
        println!("ext-click dy {dy}: {}", if hit { "HIT" } else { "MISS" });
    }
}
/// Dock event identity on the wasmtime engine: tapping the header fold
/// button emits exactly the dock-fold envelope. (The per-stage
/// up/delete/toggle identities ride the same EventBinding mechanism and
/// are gate-held on the GraalWasm engine's interaction lane.)
#[test]
fn dock_fold_identity() {
    let mut host = boot();
    let pb = card_bytes("lego_dock-expanded");
    let _ = render_card(&mut host, &pb, 1);
    let tree: serde_json::Value =
        serde_json::from_str(&host.dump_tree().expect("dump_tree")).expect("tree JSON");
    let mut buttons = Vec::new();
    find_type(&tree, "lv_button", &mut buttons);
    assert_eq!(
        buttons.len(),
        10,
        "expanded dock: fold + 3x3 stage buttons expected"
    );
    let fold = buttons[0];
    let (cx, cy) = (
        i32::try_from((fold[0] + fold[2]) / 2).unwrap(),
        i32::try_from((fold[1] + fold[3]) / 2).unwrap(),
    );
    press_px(&mut host, cx, cy);
    settle(&mut host, 4);
    release_px(&mut host, cx, cy);
    settle(&mut host, 4);
    let events = envelopes(&mut host);
    assert_eq!(
        events,
        vec![("dock-fold".to_owned(), 0)],
        "the fold tap must emit exactly the dock-fold identity"
    );
    println!("dock-fold identity OK");
}
