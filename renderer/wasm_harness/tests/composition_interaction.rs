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
//! fb/<slug>_dark<d>.raw + interaction-geometry.json (the pointer-contract
//! DECLARATION this suite reads instead of copying the corpus's own numbers
//! — see `geometry`), plus renderer/output/controls.wasm +
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
/// The composition-lane canvas (corpus/composition.edn :canvas) and the
/// pinned render protocol's dpi. These stay COMPILE-TIME constants on
/// purpose while the pointer geometry below is read: `core/run-composition`
/// throws when the inventory canvas differs from the pinned render
/// protocol, so a corpus canvas edit fails at the source and this copy
/// cannot go stale silently. The pointer numbers had no such guard.
const WIDTH: u32 = 800;
const HEIGHT: u32 = 480;
const DPI: i32 = 160;
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
/// The pointer-contract DECLARATION the devcards runner emits beside the
/// cards (devcards.interaction/geometry-declaration ->
/// out/composition/interaction-geometry.json). READ, never re-copied.
///
/// These numbers — the track rect, the ext-click width, the seek min/max,
/// the dock's button count — are all projections of
/// tools/devcards/corpus/composition.edn and devcards.legos/scrubber-halo,
/// which the GraalWasm lane derives. Hard-copying them here made a
/// legitimate corpus edit red THIS suite while leaving that lane green —
/// and red on the wrong push, because a corpus edit matches devcards.yml's
/// `paths:` and not renderer.yml's. Reading the declaration is what makes
/// the two engines move together. It is a DECLARATION, not a reading of
/// the rendered tree: deriving it from `dump_tree` would make this suite
/// assert that the renderer does whatever it currently does.
///
/// Absence is a sequencing bug, exactly like a missing card.
fn geometry() -> serde_json::Value {
    let path = devcards_input("interaction-geometry.json");
    let raw =
        std::fs::read(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    serde_json::from_slice(&raw)
        .unwrap_or_else(|e| panic!("parse {}: {e}", path.display()))
}
/// An inclusive-origin rect in canvas px.
#[derive(Clone, Copy, Debug)]
struct Rect {
    x: i32,
    y: i32,
    w: i32,
    h: i32,
}
fn decl_i32(v: &serde_json::Value, path: &str) -> i32 {
    let mut cur = v;
    for key in path.split('.') {
        cur = &cur[key];
    }
    i32::try_from(
        cur.as_i64()
            .unwrap_or_else(|| panic!("interaction-geometry.json: {path} is not an integer")),
    )
    .unwrap_or_else(|e| panic!("interaction-geometry.json: {path} out of i32 range: {e}"))
}
fn decl_str(v: &serde_json::Value, path: &str) -> String {
    let mut cur = v;
    for key in path.split('.') {
        cur = &cur[key];
    }
    cur.as_str()
        .unwrap_or_else(|| panic!("interaction-geometry.json: {path} is not a string"))
        .to_owned()
}
/// The scrubber card's TRACK rect, as declared (corpus `:placement` +
/// devcards.legos/scrubber-halo).
fn scrubber_track(g: &serde_json::Value) -> Rect {
    Rect {
        x: decl_i32(g, "scrubber.track.x"),
        y: decl_i32(g, "scrubber.track.y"),
        w: decl_i32(g, "scrubber.track.w"),
        h: decl_i32(g, "scrubber.track.h"),
    }
}
/// The stock-mapped slider value for a tap at `frac` of the track — the
/// same mapping devcards.interaction/seek-value applies, over the same
/// declared :min/:max.
fn seek_value(g: &serde_json::Value, frac: f64) -> i64 {
    let min = f64::from(decl_i32(g, "scrubber.min"));
    let max = f64::from(decl_i32(g, "scrubber.max"));
    (frac.mul_add(max - min, min)).round() as i64
}
/// Every composition card the devcards runner persisted, DISCOVERED from the
/// card dir it writes rather than hand-listed. A hand-maintained roster is a
/// second copy of the corpus: it stops covering a card the moment the corpus
/// gains one, and the comparison below still passes — silently narrower than
/// its own name. Sorted for a stable report order; an empty set is a hard
/// failure, because a comparison over zero cards passes while proving nothing.
fn card_slugs() -> Vec<String> {
    let dir = devcards_input("cards");
    let mut slugs: Vec<String> = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("read_dir {}: {e}", dir.display()))
        .map(|entry| entry.expect("card dir entry").path())
        .filter(|p| p.extension().is_some_and(|ext| ext == "pb"))
        .map(|p| {
            p.file_stem()
                .expect("a .pb path has a stem")
                .to_string_lossy()
                .into_owned()
        })
        .collect();
    slugs.sort();
    assert!(
        !slugs.is_empty(),
        "{} holds no .pb cards — the runner wrote none, and a cross-engine \
         comparison over an empty card set would pass having compared nothing",
        dir.display()
    );
    slugs
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
fn track_px(t: Rect, frac: f64, dy: i32) -> (i32, i32) {
    (t.x + (frac * f64::from(t.w)) as i32, t.y + t.h / 2 + dy)
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
/// the GraalWasm dump of the same `.pb` on the same wasm. "Every" is held by
/// `card_slugs` discovering the set, so a corpus addition joins this
/// comparison by itself instead of waiting for someone to remember.
#[test]
fn composition_cross_engine_fb() {
    let mut host = boot();
    for slug in card_slugs() {
        let pb = card_bytes(&slug);
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
    let g = geometry();
    let mut host = boot();
    let pb = card_bytes(&decl_str(&g, "scrubber.slug"));
    let _ = render_card(&mut host, &pb, 1);
    let (x, y) = track_px(scrubber_track(&g), 0.70, 0);
    press_px(&mut host, x, y);
    settle(&mut host, 4);
    let after_down = envelopes(&mut host);
    assert_eq!(
        seek_values(&after_down),
        vec![seek_value(&g, 0.70)],
        "press-seek must fire exactly once at DOWN with the stock-mapped value"
    );
    release_px(&mut host, x, y);
    settle(&mut host, 4);
    let after_up = envelopes(&mut host);
    assert!(
        seek_values(&after_up).is_empty(),
        "release must add NO duplicate seek (got {after_up:?})"
    );
    println!(
        "press-seek [{}], no duplicate at release",
        seek_value(&g, 0.70)
    );
}
/// Drag continuity: press-seek prepends exactly one immediate value to the
/// stock MOVE stream.
#[test]
fn scrubber_press_drag_stream() {
    let g = geometry();
    let track = scrubber_track(&g);
    let mut host = boot();
    let pb = card_bytes(&decl_str(&g, "scrubber.slug"));
    let _ = render_card(&mut host, &pb, 1);
    let (x0, y0) = track_px(track, 0.30, 0);
    press_px(&mut host, x0, y0);
    settle(&mut host, 3);
    for frac in [0.45, 0.55, 0.70] {
        let (x, y) = track_px(track, frac, 0);
        move_px(&mut host, x, y);
        settle(&mut host, 3);
    }
    let (xe, ye) = track_px(track, 0.70, 0);
    release_px(&mut host, xe, ye);
    settle(&mut host, 3);
    let events = envelopes(&mut host);
    let expected: Vec<i64> = [0.30, 0.45, 0.55, 0.70]
        .iter()
        .map(|f| seek_value(&g, *f))
        .collect();
    assert_eq!(
        seek_values(&events),
        expected,
        "drag must stream press value + each MOVE value exactly once"
    );
    println!("drag stream {expected:?}");
}
/// The ext-click envelope THROUGH THE LEGO (the halo wrapper is what lets
/// LVGL's point-on-parent-coords descent reach the slider's widened click
/// area). Fresh load_ui per dy: a repeat tap at an unchanged value fires
/// no VALUE_CHANGED and would fake a miss.
///
/// WHAT A GREEN HERE DOES NOT MEAN. The boundary is the MINIMUM of two
/// widenings: the slider's `WidgetNode.hit_slop` and the lego's transparent
/// halo wrapper. LVGL descends into a child only when the point is on the
/// PARENT's coords, so the wrapper clips first and the smaller wins.
/// MEASURED across three sha-distinct wasm builds, back when the widening
/// was a renderer-side constant: 24 -> 48 with the wrapper unchanged stays
/// GREEN — the widening is invisible; 24 -> 12 goes RED at
/// `dy 16 ... expected HIT`. So this pins the boundary DOWNWARD ONLY: it
/// catches either side SHRINKING below the declared value and is blind to
/// either side GROWING. Read a green as "nothing narrowed the reachable
/// halo", never as "the hit slop is still 24". The value itself is DERIVED
/// from the corpus declaration rather than copied.
///
/// Those two are no longer INDEPENDENTLY authored, which is what that
/// measurement was warning about: `devcards.legos/scrubber-halo` is now the
/// single source for both the wrapper band and the slider's `hit_slop`, so
/// the drift this docstring described cannot be introduced by editing one
/// side. The downward-only limit still stands — it is a property of the
/// sweep, not of where the number came from.
#[test]
fn scrubber_ext_click_envelope() {
    let g = geometry();
    let track = scrubber_track(&g);
    let ext_click_px = decl_i32(&g, "scrubber.ext_click_px");
    let mut host = boot();
    let pb = card_bytes(&decl_str(&g, "scrubber.slug"));
    let y2 = track.y + track.h - 1; // inclusive bottom edge
    let x = track.x + (0.70 * f64::from(track.w)) as i32;
    for dy in [2, 16, ext_click_px, ext_click_px + 1, ext_click_px + 6] {
        let expect_hit = dy <= ext_click_px;
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
    let g = geometry();
    let expected_buttons = usize::try_from(decl_i32(&g, "dock.button_count")).expect("count >= 0");
    let mut host = boot();
    let pb = card_bytes(&decl_str(&g, "dock.slug"));
    let _ = render_card(&mut host, &pb, 1);
    let tree: serde_json::Value =
        serde_json::from_str(&host.dump_tree().expect("dump_tree")).expect("tree JSON");
    let mut buttons = Vec::new();
    find_type(&tree, "lv_button", &mut buttons);
    assert_eq!(
        buttons.len(),
        expected_buttons,
        "expanded dock: fold + 3 buttons per stage expected"
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
