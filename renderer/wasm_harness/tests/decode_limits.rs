//! Decode-limit contracts: the renderer must REFUSE an oversized widget tree
//! cleanly rather than trapping, silently corrupting, or building the tree
//! anyway after it has already diagnosed a failure.
//!
//! Every assertion here is on a synthetic tree built in-process, because the
//! whole point is inputs no authored screen produces. The in-tree fixtures top
//! out at three nesting levels and the shipped screen corpus at six, so nothing
//! in the existing battery has ever exercised a single one of these paths.
//!
//! WHAT THIS SUITE COVERS — and, as importantly, what it does not.
//!
//! 1. NESTING. `MAX_DECODE_DEPTH` is the renderer's stated cap. A chain at the
//!    cap must load; a chain past it must be REFUSED rather than trap. The
//!    guard is only meaningful if the C stack can actually reach the cap — a
//!    stack too small to get there turns the declared limit into unreachable
//!    dead code and converts a diagnosed rejection into a trap. `wasm.mk`'s
//!    stack reservation is what makes it reachable, so these tests fail loudly
//!    if that flag is ever dropped.
//!
//! 2. A FAN-OUT FLOOR, not a cap. `the_widest_corpus_fanout_loads_clean` pins
//!    the widest single parent the fixture corpus actually uses, so a future
//!    per-parent cap cannot be set below a fixture the battery depends on.
//!
//! NOT COVERED HERE, deliberately: the per-parent `uint16_t` child-count wrap
//! (65536 siblings corrupt the heap and still report SUCCESS) and the aggregate
//! node bound. A cap for those was written and withdrawn: `children_decode_cb`
//! has TWO callers — the full load AND `decode_op_node` on the patch path — and
//! a counter scoped to the load path alone is both too weak (patch-inserted
//! leaves bypass it, leaving the wrap reachable through `controls_apply_patch`)
//! and too strong (it never resets, so it eventually refuses every patch).
//! Closing it needs a bound on the LIVE tree plus `apply_patch` coverage that
//! `morph_parity` structurally cannot provide — it builds a fresh host per case
//! and applies exactly one patch. That is its own change; do not re-add a cap
//! here without covering both entry points.
#![allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::panic,
    clippy::print_stdout,
    clippy::missing_docs_in_private_items,
    missing_docs,
    unused_crate_dependencies,
    reason = "test binary, not library code"
)]
use lvgl_harness::proto::ui;
use lvgl_harness::{ControlsHost, HostConfig};
use prost::Message as _;
use std::path::PathBuf;

const WIDTH: u32 = 960;
const HEIGHT: u32 = 540;

/// Mirrors `MAX_DECODE_DEPTH` in `renderer/src/renderer.c`. Duplicated here
/// deliberately: the test's job is to hold the C constant to its promise, so
/// reading the promise from the same place that makes it would assert nothing.
const MAX_DECODE_DEPTH: usize = 32;

/// The widest single parent in the renderer's own fixture corpus (`vc_trunc`:
/// 780 labels under one `lv_obj`, sized to overflow the 128 KB dump buffer).
/// Pinned here so a future fan-out cap cannot be set below a fixture the
/// battery already depends on — the mistake this suite exists to prevent.
const WIDEST_CORPUS_FANOUT: usize = 780;

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

fn plain_node() -> ui::WidgetNode {
    ui::WidgetNode {
        r#type: ui::WidgetType::WidgetObj as i32,
        ..Default::default()
    }
}

/// A single chain `levels` nodes long: the root plus `levels - 1` descendants,
/// each the only child of the one above. `levels = 1` is a bare root.
fn nested_chain(levels: usize) -> Vec<u8> {
    assert!(levels >= 1, "a chain needs at least the root");
    let mut node = plain_node();
    for _ in 1..levels {
        let parent = ui::WidgetNode {
            children: vec![node],
            ..plain_node()
        };
        node = parent;
    }
    ui::Screen {
        root: Some(node),
        subjects: vec![],
    }
    .encode_to_vec()
}

/// A root with `k` leaf children — fan-out, not depth.
fn flat_fanout(k: usize) -> Vec<u8> {
    ui::Screen {
        root: Some(ui::WidgetNode {
            children: (0..k).map(|_| plain_node()).collect(),
            ..plain_node()
        }),
        subjects: vec![],
    }
    .encode_to_vec()
}

// ── 1. NESTING ─────────────────────────────────────────────────────────────

/// A chain exactly at the declared cap must load cleanly. This is the half that
/// makes the cap a real number instead of an aspiration: if the stack cannot
/// carry the decoder to `MAX_DECODE_DEPTH`, the cap is dead code and the true
/// limit is whatever the linker's default stack happened to allow.
#[test]
fn nesting_at_the_declared_cap_loads_clean() {
    let mut host = new_host();
    let status = host
        .load_ui_raw(&nested_chain(MAX_DECODE_DEPTH))
        .expect("load_ui trapped at the declared depth cap — the cap is unreachable");
    assert_eq!(
        status, 0,
        "a chain at MAX_DECODE_DEPTH ({MAX_DECODE_DEPTH}) must load; got status {status}"
    );
}

/// Past the cap the decoder must REFUSE — a diagnosed -1, not a trap. A trap
/// takes the whole guest down and gives the host nothing to act on.
#[test]
fn nesting_past_the_cap_is_refused_not_trapped() {
    let mut host = new_host();
    let status = host
        .load_ui_raw(&nested_chain(MAX_DECODE_DEPTH + 8))
        .expect("load_ui trapped past the depth cap — the guard must refuse, not crash");
    assert!(
        status != 0,
        "a chain past MAX_DECODE_DEPTH must be refused, got success"
    );
}

/// The instance is still usable after a refusal: a rejection is not a
/// one-way door into a wedged guest.
#[test]
fn a_refused_deep_tree_leaves_the_instance_loadable() {
    let mut host = new_host();
    let refused = host
        .load_ui_raw(&nested_chain(MAX_DECODE_DEPTH + 8))
        .expect("load_ui trapped past the depth cap");
    assert!(refused != 0, "expected a refusal to set up this test");
    let status = host
        .load_ui_raw(&nested_chain(2))
        .expect("load_ui trapped on a trivial tree after a refusal");
    assert_eq!(
        status, 0,
        "a trivial tree must still load after a refused one; got {status}"
    );
}

// ── 2. FAN-OUT ─────────────────────────────────────────────────────────────

/// The widest fan-out the fixture corpus actually uses must load. This is the
/// regression guard for a real mistake: a per-parent cap of 256 was added here
/// on the reasoning that "no real screen declares anything near it" — true of
/// the shipped screen corpus (max 78 nodes/screen) and FALSE of the renderer's
/// own `vc_trunc` fixture, which puts 780 labels under one parent on purpose.
/// The battery caught it; this test is what makes that catch permanent.
#[test]
fn the_widest_corpus_fanout_loads_clean() {
    let mut host = new_host();
    let status = host
        .load_ui_raw(&flat_fanout(WIDEST_CORPUS_FANOUT))
        .expect("load_ui trapped at the widest fan-out the corpus uses");
    assert_eq!(
        status, 0,
        "a parent with {WIDEST_CORPUS_FANOUT} children is in-corpus (vc_trunc) and must load; \
         got status {status} — a fan-out cap has been set below a live fixture"
    );
}
