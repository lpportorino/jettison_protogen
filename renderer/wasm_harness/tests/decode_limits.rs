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
//! 2. FAN-OUT, both directions. `MAX_LIVE_CHILDREN` bounds one parent's live
//!    child count, because LVGL keeps that count in a `uint16_t` and the
//!    65536th sibling wraps it to zero — corruption reported as SUCCESS.
//!    `the_widest_corpus_fanout_loads_clean` pins the widest parent the fixture
//!    corpus actually uses, so the cap can never be set below a live fixture.
//!
//! 3. ACCUMULATION ACROSS PATCHES — the half no other suite can express.
//!    `children_decode_cb` has TWO callers, the full load AND `decode_op_node`
//!    on the patch path, so a bound scoped to one decode invocation is both too
//!    weak (patch-inserted leaves bypass it, leaving the wrap reachable through
//!    `controls_apply_patch`) and too strong (a counter that never resets
//!    eventually refuses every patch for the module's lifetime). Bounding the
//!    LIVE tree is what satisfies both at once, and both directions are
//!    asserted here so they can never again be traded off against each other.
//!    `morph_parity` structurally cannot cover this: it builds a fresh host per
//!    case and applies exactly one patch, so accumulation is invisible to it.
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

/// Mirrors `MAX_LIVE_CHILDREN` in `renderer/src/renderer.c`, duplicated for the
/// same reason `MAX_DECODE_DEPTH` is: reading the promise from the place that
/// makes it would assert nothing.
const MAX_LIVE_CHILDREN: usize = 4096;

/// Uid carried by the root of the patch-path fixtures, so an INSERT op has a
/// `parent_uid` to resolve. Any non-zero value works; the leaves stay uid-less
/// on purpose — a uid-less node registers in NO pool, which is precisely why
/// `patch_pools_low` cannot see this class and a live child bound must.
const ROOT_UID: u32 = 7;

/// Refusal code for a bounded-resource op (`PATCH_ERR_POOL`).
const PATCH_ERR_POOL: i32 = -4;

/// FNV-1a-32 over bytes — matches `fnv1a32` in `renderer/src/main.c`. A
/// `ScreenPatch` must carry the current state's hash as its `base_hash` or the
/// reconciler refuses it; after a successful apply the current hash becomes the
/// patch's `target_hash`, which is what lets a chain of patches be built.
fn fnv1a32(data: &[u8]) -> u32 {
    let mut hash: u32 = 0x811c_9dc5;
    for byte in data {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

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

/// `flat_fanout`, but the root carries `ROOT_UID` so a patch can target it.
fn patchable_fanout(k: usize) -> Vec<u8> {
    ui::Screen {
        root: Some(ui::WidgetNode {
            uid: ROOT_UID,
            children: (0..k).map(|_| plain_node()).collect(),
            ..plain_node()
        }),
        subjects: vec![],
    }
    .encode_to_vec()
}

/// One INSERT of a bare uid-less leaf under `ROOT_UID`, chained onto the
/// current state hash.
fn insert_leaf_patch(base_hash: u32, target_hash: u32) -> Vec<u8> {
    ui::ScreenPatch {
        base_hash,
        target_hash,
        ops: vec![ui::TreePatchOp {
            kind: ui::PatchOpKind::PatchOpInsertNode as i32,
            target_uid: 0,
            parent_uid: ROOT_UID,
            index: 0,
            node: Some(plain_node()),
        }],
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

/// A load exactly at the cap is legal; one past it is refused, not built. The
/// refusal is what keeps `child_cnt` a real count: past 65536 the `uint16_t`
/// wraps to zero and LVGL orphans the whole child array while still reporting
/// success, so "refused" is the only safe answer above the bound.
#[test]
fn fanout_at_the_cap_loads_and_past_it_is_refused() {
    let mut host = new_host();
    let at_cap = host
        .load_ui_raw(&flat_fanout(MAX_LIVE_CHILDREN))
        .expect("load_ui trapped at the declared fan-out cap");
    assert_eq!(
        at_cap, 0,
        "a parent with exactly MAX_LIVE_CHILDREN ({MAX_LIVE_CHILDREN}) children must load; \
         got {at_cap} — the cap is off by one or unreachable"
    );

    let mut host = new_host();
    let past_cap = host
        .load_ui_raw(&flat_fanout(MAX_LIVE_CHILDREN + 1))
        .expect("load_ui trapped past the fan-out cap — the guard must refuse, not crash");
    assert!(
        past_cap != 0,
        "a parent past MAX_LIVE_CHILDREN must be refused, got success — \
         the uint16_t child_cnt wrap is reachable again"
    );
}

// ── 3. ACCUMULATION ACROSS PATCHES ─────────────────────────────────────────

/// TOO WEAK, the direction that leaves the wrap reachable. `patch_pools_low`
/// gates on the uid registry and the style/grid/scale/bg-image/binfont pools —
/// and a uid-less leaf registers in NONE of them. So before the live child
/// bound, N INSERT ops of bare leaves under one parent walked `child_cnt`
/// toward 65536 with nothing in the renderer able to see it.
///
/// Filling the parent through the LOAD path first is what makes this assertion
/// affordable: the bound is on the LIVE count, so it does not care how the
/// siblings arrived, and 65536 sequential patches are not needed to prove the
/// door is shut.
#[test]
fn insert_into_a_full_parent_is_refused() {
    let mut host = new_host();
    let base = patchable_fanout(MAX_LIVE_CHILDREN);
    let status = host.load_ui_raw(&base).expect("load_ui trapped at the cap");
    assert_eq!(status, 0, "the at-cap base screen must load; got {status}");

    let rc = host
        .apply_patch(&insert_leaf_patch(fnv1a32(&base), 1))
        .expect("apply_patch trapped — an over-cap INSERT must refuse, not crash");
    assert_eq!(
        rc, PATCH_ERR_POOL,
        "an INSERT into a parent already at MAX_LIVE_CHILDREN must refuse with \
         PATCH_ERR_POOL ({PATCH_ERR_POOL}), got {rc} — a uid-less leaf is invisible to \
         patch_pools_low, so this is the only thing standing between \
         controls_apply_patch and the child_cnt wrap"
    );
}

/// TOO STRONG, the opposite direction — and the one a decode-scoped counter
/// gets wrong. A long-lived module applies many patches; a bound that
/// accumulated across invocations (never reset, nothing credited back on
/// delete) would refuse every patch for the rest of the module's lifetime once
/// the total crossed, and report a tree size the live tree does not have.
///
/// `PATCH_RUN` is deliberately far above the headroom any such counter would
/// have had (an aggregate 1024-node cap leaves ~205 after a codegen-sized
/// screen), so this test goes red against exactly that mistake.
#[test]
fn many_sequential_patches_on_one_host_all_apply() {
    const PATCH_RUN: usize = 512;
    let mut host = new_host();
    let base = patchable_fanout(1);
    let status = host.load_ui_raw(&base).expect("load_ui trapped on the base");
    assert_eq!(status, 0, "the base screen must load; got {status}");

    // The reconciler chains hashes: each patch declares the current state as
    // its base and names the next one, which becomes current on success.
    let mut current = fnv1a32(&base);
    for i in 0..PATCH_RUN {
        let next = current.wrapping_add(1);
        let rc = host
            .apply_patch(&insert_leaf_patch(current, next))
            .expect("apply_patch trapped mid-run");
        assert_eq!(
            rc, 0,
            "patch {i} of {PATCH_RUN} was refused (rc {rc}) on a host whose live tree is \
             nowhere near any bound — a resource counter is accumulating across decode \
             invocations instead of tracking the live tree"
        );
        current = next;
    }
}
