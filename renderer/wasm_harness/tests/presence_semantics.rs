//! Explicit-presence contracts: a `ui.WidgetNode` field whose ZERO is a legal
//! domain value must be honoured when the producer SETS it, and left alone when
//! the producer omits it.
//!
//! WHY THIS SUITE EXISTS. `ui_ast.proto` originally declared these fields as
//! bare proto3 scalars, which have no presence: the renderer could only ask
//! "is it zero?", and every one of them has a zero that means something. The
//! sharpest is `WidgetNode.scroll_dir`, a direct cast to `lv_dir_t` whose zero
//! is `LV_DIR_NONE` — LVGL's own name for "this object does not scroll".
//! `lv_obj_allocate_spec_attr` defaults a fresh object to `LV_DIR_ALL`, so the
//! renderer's `if (node->scroll_dir != 0)` guard meant a screen could ENABLE
//! any subset of scroll axes and could never DISABLE scrolling. The field is
//! `optional` now, and the guard reads the presence flag nanopb emits.
//!
//! EVERY CASE DRIVES THE BYTES DIRECTLY, and that is the point rather than an
//! implementation detail. `scroll_dir = 0` was always encodable — a varint zero
//! on tag 34 is legal proto3 wire format whatever the schema says — and nanopb
//! always decoded it. What was missing was the renderer's ability to tell that
//! byte from a field nobody sent. So the input is assembled BY HAND here: the
//! same bytes are fed before and after the schema change, and only the
//! renderer's reading of them differs. A test that built its input through the
//! generated binding would instead be asserting what `prost` chooses to emit,
//! which is a different question and one the schema answers by construction.
//!
//! EVERY CASE IS A TRIPLE, because a "does not scroll" assertion alone cannot
//! tell a working guard from a renderer that has stopped scrolling anything:
//!   * OMITTED       — no tag 34 at all. Must scroll (the LVGL default).
//!   * EXPLICIT VER  — tag 34 = `LV_DIR_VER`. Must scroll (a set value is
//!                     still applied; the presence read did not break it).
//!   * EXPLICIT NONE — tag 34 = `LV_DIR_NONE`. Must NOT scroll. This is the
//!                     case that was inexpressible.
#![allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::panic,
    clippy::print_stdout,
    clippy::missing_docs_in_private_items,
    clippy::indexing_slicing,
    clippy::cast_possible_truncation,
    clippy::cast_possible_wrap,
    clippy::cast_sign_loss,
    missing_docs,
    unused_crate_dependencies,
    reason = "test binary, not library code"
)]
use lvgl_harness::proto::ui;
use lvgl_harness::{ControlsHost, HostConfig, PointerEvent, TICK_MS};
use prost::Message as _;
use std::path::PathBuf;

const WIDTH: u32 = 960;
const HEIGHT: u32 = 540;
const DPI: i32 = 160;

/// `LV_OBJ_FLAG_SCROLLABLE` (`renderer/lvgl/src/core/lv_obj.h`), direct-cast by
/// the renderer from `WidgetNode.obj_flags`.
const LV_OBJ_FLAG_SCROLLABLE: u32 = 1 << 4;
/// `LV_OBJ_FLAG_CLICKABLE`. NOT optional here: `lv_obj_hit_test` returns false
/// outright for a non-clickable object, so `lv_indev_search_obj` finds nothing
/// under the press, no `act_obj` is latched, and `find_scroll_obj` is never
/// reached. Without it the whole triple passes vacuously — measured.
const LV_OBJ_FLAG_CLICKABLE: u32 = 1 << 1;
/// `LV_DIR_VER` = `LV_DIR_TOP | LV_DIR_BOTTOM` (`lvgl/src/misc/lv_area.h`).
const LV_DIR_VER: u64 = 12;
/// `LV_DIR_NONE` — the value the wire could not previously distinguish from an
/// unset field, and the whole subject of this suite.
const LV_DIR_NONE: u64 = 0;

/// The uid the assertions read out of `dump_tree`. Any non-zero value works;
/// the registry only needs it to be unique in the tree.
const CONTENT_UID: u32 = 4242;

/// Scroller box, framebuffer px. Smaller than its content in `y`, so the
/// content overflows and there is something to scroll.
const SCROLLER_W: u32 = 400;
const SCROLLER_H: u32 = 200;
const CONTENT_H: u32 = 900;

/// How far the drag travels upward, in px. Comfortably past LVGL's
/// scroll-recognition threshold so a scrolling container definitely moves.
const DRAG_PX: i32 = 120;

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .to_path_buf()
}

fn new_host() -> ControlsHost {
    let config = HostConfig::new(repo_root().join("output/controls.wasm"), WIDTH, HEIGHT)
        .with_wasi_root(repo_root().join("assets"));
    ControlsHost::new(&config).expect("failed to create ControlsHost")
}

// ── hand-assembled wire bytes ───────────────────────────────────────────────

/// Append a base-128 varint, little-endian groups of 7 bits.
fn put_varint(mut v: u64, out: &mut Vec<u8>) {
    while v >= 0x80 {
        out.push((v as u8 & 0x7f) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

/// Append a length-delimited (wire type 2) field: tag, length, payload.
fn put_len_field(field_no: u64, payload: &[u8], out: &mut Vec<u8>) {
    put_varint((field_no << 3) | 2, out);
    put_varint(payload.len() as u64, out);
    out.extend_from_slice(payload);
}

/// Append a varint (wire type 0) field.
fn put_varint_field(field_no: u64, value: u64, out: &mut Vec<u8>) {
    put_varint(field_no << 3, out);
    put_varint(value, out);
}

/// `ui.WidgetNode.scroll_dir` — field number 34, and the one byte pair this
/// whole suite is about. Named rather than inlined so a renumbering shows up
/// here as a compile-adjacent edit rather than as a silent behaviour change.
const SCROLL_DIR_FIELD_NO: u64 = 34;
/// `ui.WidgetNode.children` — field number 8.
const CHILDREN_FIELD_NO: u64 = 8;
/// `ui.Screen.root` — field number 1.
const SCREEN_ROOT_FIELD_NO: u64 = 1;

/// A style group giving the node a fixed pixel size. Only `variant_index` 0 is
/// emitted: the renderer treats variant 0 as the BASE and applies it whenever
/// no entry matches the active composite index (`renderer.c`,
/// `style_variant_decode_cb`).
/// `PROP_WIDTH`/`PROP_HEIGHT` take the UINT slot, not the int one — `renderer.c`
/// `apply_style_property` rejects the whole load on a wrong-slot value rather
/// than skipping the property, so this is a contract and not a preference.
fn sized(w: u32, h: u32) -> Vec<ui::StyleGroup> {
    let prop = |t: ui::StylePropertyType, v: u32| ui::StyleProperty {
        r#type: t as i32,
        value: Some(ui::style_property::Value::UintValue(v)),
    };
    vec![ui::StyleGroup {
        state_selector: 0,
        variants: vec![ui::StyleVariant {
            variant_index: 0,
            properties: vec![
                prop(ui::StylePropertyType::PropWidth, w),
                prop(ui::StylePropertyType::PropHeight, h),
            ],
        }],
    }]
}

/// The overflowing content child, carrying the uid the assertions read.
fn content_node() -> ui::WidgetNode {
    ui::WidgetNode {
        r#type: ui::WidgetType::WidgetObj as i32,
        uid: CONTENT_UID,
        style_groups: sized(SCROLLER_W - 20, CONTENT_H),
        ..Default::default()
    }
}

/// The scrollable box, WITHOUT any `scroll_dir` field — the caller splices one
/// in afterwards, or does not.
fn scroller_node() -> ui::WidgetNode {
    ui::WidgetNode {
        r#type: ui::WidgetType::WidgetObj as i32,
        obj_flags: LV_OBJ_FLAG_SCROLLABLE | LV_OBJ_FLAG_CLICKABLE,
        style_groups: sized(SCROLLER_W, SCROLLER_H),
        children: vec![content_node()],
        ..Default::default()
    }
}

/// A full-screen root holding the scroller, with `scroll_dir` spliced onto the
/// SCROLLER at the byte level.
///
/// `scroll_dir: None` omits tag 34 entirely; `Some(v)` appends it as a varint.
/// Assembling the two enclosing messages by hand is what lets `Some(0)` be
/// expressed at all under the ORIGINAL schema, where the generated field is a
/// bare `u32` that no encoder would put on the wire.
fn screen_bytes(scroll_dir: Option<u64>) -> Vec<u8> {
    let mut scroller = scroller_node().encode_to_vec();
    if let Some(v) = scroll_dir {
        put_varint_field(SCROLL_DIR_FIELD_NO, v, &mut scroller);
    }

    let mut root = ui::WidgetNode {
        r#type: ui::WidgetType::WidgetObj as i32,
        style_groups: sized(WIDTH, HEIGHT),
        ..Default::default()
    }
    .encode_to_vec();
    put_len_field(CHILDREN_FIELD_NO, &scroller, &mut root);

    let mut screen = Vec::new();
    put_len_field(SCREEN_ROOT_FIELD_NO, &root, &mut screen);
    screen
}

// ── drive + observe ─────────────────────────────────────────────────────────

/// Framebuffer px -> NDC. **The Y axis flips**: `main.c`'s `ndc_to_px` maps
/// `+y UP`, so `y = (1 - ndc_y) * 0.5 * height`. Getting this wrong does not
/// misplace the press by a few pixels — it MIRRORS it about the canvas centre,
/// which here landed every event outside the scroller, gave the pointer to the
/// video owner instead of LVGL, and made all three cases report "did not
/// scroll". Measured.
fn px_to_ndc(x: i32, y: i32) -> (f64, f64) {
    (
        f64::from(x) / f64::from(WIDTH) * 2.0 - 1.0,
        1.0 - f64::from(y) / f64::from(HEIGHT) * 2.0,
    )
}

fn next_event_time() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static CLOCK: AtomicU64 = AtomicU64::new(1000);
    CLOCK.fetch_add(5, Ordering::Relaxed)
}

fn settle(host: &mut ControlsHost, ticks: u32) {
    for _ in 0..ticks {
        let _ = host.tick(TICK_MS).expect("tick");
    }
}

/// Absolute `y1` of the `CONTENT_UID` node, read from `dump_tree`. A container
/// that scrolls moves its children, so this coordinate IS the observable.
fn content_y1(host: &mut ControlsHost) -> i64 {
    let dump = host.dump_tree().expect("dump_tree");
    let v: serde_json::Value = serde_json::from_str(&dump).expect("dump_tree JSON");
    fn find(v: &serde_json::Value) -> Option<i64> {
        if v["uid"].as_u64() == Some(u64::from(CONTENT_UID)) {
            return v["coords"][1].as_i64();
        }
        v["children"]
            .as_array()
            .into_iter()
            .flatten()
            .find_map(find)
    }
    find(&v).expect("CONTENT_UID node with coords in dump_tree")
}

/// Load the screen, drag upward inside the scroller, and return
/// `(y1_before, y1_after)` for the content node.
///
/// The drag is read WHILE THE POINTER IS STILL DOWN. LVGL scrolls during the
/// move, and reading before the release keeps momentum and elastic snap-back
/// out of the measurement — this asks whether scrolling happened at all, not
/// where it settles.
fn drag_and_measure(scroll_dir: Option<u64>) -> (i64, i64) {
    let mut host = new_host();
    host.set_breakpoint(0).expect("set_breakpoint");
    host.set_theme_dark(1).expect("set_theme_dark");
    host.set_dpi(DPI).expect("set_dpi");
    host.load_ui(&screen_bytes(scroll_dir)).expect("load_ui");
    settle(&mut host, 4);

    let before = content_y1(&mut host);

    // Start near the BOTTOM of the scroller and drag upward, so every event in
    // the sequence stays inside both the scroller and the display. A drag that
    // leaves the display is refused by `indev_pointer_proc` with a warning and
    // silently contributes nothing.
    let (x, y0) = (SCROLLER_W as i32 / 2, SCROLLER_H as i32 - 30);
    let (nx, ny) = px_to_ndc(x, y0);
    host.pointer(PointerEvent::down(1, nx, ny, next_event_time()))
        .expect("pointer down");
    settle(&mut host, 2);
    for step in 1..=6 {
        let y = y0 - (DRAG_PX * step) / 6;
        let (nx, ny) = px_to_ndc(x, y);
        host.pointer(PointerEvent::mv(1, nx, ny, next_event_time()))
            .expect("pointer move");
        settle(&mut host, 2);
    }
    let after = content_y1(&mut host);

    let (nx, ny) = px_to_ndc(x, y0 - DRAG_PX);
    host.pointer(PointerEvent::up(1, nx, ny, next_event_time()))
        .expect("pointer up");
    settle(&mut host, 2);
    (before, after)
}

// ── the triple ──────────────────────────────────────────────────────────────

#[test]
fn omitted_scroll_dir_leaves_the_lvgl_default() {
    let (before, after) = drag_and_measure(None);
    println!("omitted: y1 {before} -> {after}");
    assert!(
        after < before,
        "with no scroll_dir on the wire the container must keep LVGL's \
         LV_DIR_ALL default and scroll: y1 went {before} -> {after}"
    );
}

#[test]
fn explicit_ver_scroll_dir_still_scrolls() {
    let (before, after) = drag_and_measure(Some(LV_DIR_VER));
    println!("LV_DIR_VER: y1 {before} -> {after}");
    assert!(
        after < before,
        "an explicitly SET scroll_dir must still be applied — this is the \
         control that separates 'the presence read works' from 'the renderer \
         stopped scrolling': y1 went {before} -> {after}"
    );
}

#[test]
fn explicit_none_scroll_dir_disables_scrolling() {
    let (before, after) = drag_and_measure(Some(LV_DIR_NONE));
    println!("LV_DIR_NONE: y1 {before} -> {after}");
    assert_eq!(
        after, before,
        "scroll_dir = LV_DIR_NONE is an explicit request to DISABLE scrolling. \
         Before `optional`, tag 34 = 0 was indistinguishable from an unset \
         field and the renderer skipped it, so the container scrolled anyway — \
         which is exactly the y1 movement this assertion refuses."
    );
}
