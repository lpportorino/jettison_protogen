//! Full-load reload/teardown regression suite — sequences the morph oracles
//! do NOT cover: repeated `controls_load_ui` cycles (TTF/style-morph reload)
//! and the adversarial DUPLICATE-uid full-load case.
//!
//! The dup-uid case is the mirror, on the `controls_load_ui` entry point, of
//! the `duplicate_insert_uid` degenerate PATCH case: a screen whose slider and
//! an unrelated sibling button-label share ONE codegen uid (built by the
//! `:morph-fixtures` generator, which stamps the collision by hand — `assign-uids`
//! refuses to mint an in-tree duplicate). Before the atomic-registration fix,
//! `finalize_widget` set `user_data` + cross-attributed the label's TTF style
//! into the slider's uid-registry entry BEFORE `register_uid`'s refusal was
//! inspected, so two live objects claimed one identity. That corruption is
//! directly visible as a DUPLICATE uid in `dump_tree` (the decisive assertion
//! below), and is a latent mis-target for any later UPDATE_PROPS style-morph —
//! the reconciler would `lv_obj_remove_style` the wrong (obj, style) pair. A
//! PURE reload never reaches that reconciler, so it does not itself trap; the
//! identity collision is the observable defect. The renderer must instead
//! refuse the colliding uid CLEANLY.
//!
//! The three non-colliding tests are the control group: they prove ordinary
//! reload/morph behavior did not regress with the fix.
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
use lvgl_harness::{ControlsHost, HostConfig};
use serde_json::Value;
use std::path::PathBuf;

const WIDTH: u32 = 960;
const HEIGHT: u32 = 540;
const TICK_MS: u32 = lvgl_harness::TICK_MS;
const RENDER_TICKS: u32 = lvgl_harness::RENDER_TICKS;

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .to_path_buf()
}
fn fixtures_dir() -> PathBuf {
    repo_root().join("output/morph-fixtures")
}
fn new_host() -> ControlsHost {
    let config = HostConfig::new(repo_root().join("output/controls.wasm"), WIDTH, HEIGHT)
        .with_wasi_root(repo_root().join("assets"));
    ControlsHost::new(&config).expect("failed to create ControlsHost")
}
/// Read a fixture triple part (`<name>.<part>.pb`).
fn read_fixture(name: &str, part: &str) -> Vec<u8> {
    let path = fixtures_dir().join(format!("{name}.{part}.pb"));
    std::fs::read(&path).unwrap_or_else(|err| panic!("read {}: {err}", path.display()))
}
/// Read a plain full-load screen (`<name>.pb`).
fn read_screen(name: &str) -> Vec<u8> {
    let path = fixtures_dir().join(format!("{name}.pb"));
    std::fs::read(&path).unwrap_or_else(|err| panic!("read {}: {err}", path.display()))
}
fn ticks(host: &mut ControlsHost, n: u32) {
    for _ in 0..n {
        host.tick(TICK_MS).expect("tick failed");
    }
}
fn load(host: &mut ControlsHost, data: &[u8]) {
    host.load_ui(data).expect("load_ui failed");
    ticks(host, RENDER_TICKS);
}

/// Pure TTF reload cycle: A(TTF label) -> B -> A -> B.
#[test]
fn ttf_label_reload_cycle() {
    let a = read_fixture("font_style_morph", "base");
    let b = read_fixture("text_update", "base");
    let mut host = new_host();
    load(&mut host, &a);
    load(&mut host, &b);
    load(&mut host, &a);
    load(&mut host, &b);
}

/// Full-corpus reload cycle (dropdowns/rollers/sliders — theme suspects).
#[test]
fn corpus_reload_cycle() {
    let ks = read_fixture("kitchen_sink_text", "base");
    let dw = read_fixture("demo_widgets_text", "base");
    let mut host = new_host();
    load(&mut host, &ks);
    load(&mut host, &dw);
    load(&mut host, &ks);
}

/// TTF style morphs THEN reloads (the consumer flow shape: screen with
/// TTF labels takes style morphs, then the session moves screens).
#[test]
fn ttf_morphs_then_reload_cycle() {
    let a = read_fixture("font_style_morph", "base");
    let fwd = read_fixture("font_style_morph", "patch");
    let rev = read_fixture("font_style_morph_rev", "patch");
    let ks = read_fixture("kitchen_sink_text", "base");
    let mut host = new_host();
    load(&mut host, &a);
    for i in 0..4 {
        let p = if i % 2 == 0 { &fwd } else { &rev };
        let rc = host.apply_patch(p).expect("apply_patch call");
        assert_eq!(rc, 0, "morph {i} rc {rc}");
        ticks(&mut host, 2);
    }
    load(&mut host, &ks);
    load(&mut host, &a);
    for i in 0..4 {
        let p = if i % 2 == 0 { &fwd } else { &rev };
        let rc = host.apply_patch(p).expect("apply_patch call");
        assert_eq!(rc, 0, "second-round morph {i} rc {rc}");
        ticks(&mut host, 2);
    }
    load(&mut host, &ks);
}

/// Collect every non-zero `uid` in the dumped tree, depth-first. `dump_tree`
/// emits `"uid"` only for a node whose `user_data` was set (`finalize_widget`
/// mirrors the codegen uid into `user_data`).
fn collect_uids(node: &Value, out: &mut Vec<u64>) {
    if let Some(uid) = node.get("uid").and_then(Value::as_u64) {
        out.push(uid);
    }
    if let Some(children) = node.get("children").and_then(Value::as_array) {
        for child in children {
            collect_uids(child, out);
        }
    }
}

/// Consumer collision shape: a screen carrying a DUPLICATE uid (a slider and
/// an unrelated sibling button-label share one). The renderer must refuse the
/// colliding uid ATOMICALLY — `register_uid` rejects the second claim, and
/// nothing downstream keys on it, so the collided node's `user_data` stays
/// UNSET and no two live objects claim one identity.
///
/// The decisive assertion is on `dump_tree`: it emits `"uid"` only for a node
/// whose `user_data` was set, so two live nodes sharing one non-zero uid is
/// exactly the corruption. Before the atomic-registration fix `finalize_widget`
/// stamped `user_data` UNCONDITIONALLY (before `register_uid`'s refusal was
/// inspected) and cross-attributed the label's TTF style into the slider's
/// registry entry, so the button-label "GO" wrongly claimed the slider's uid
/// (RED). After the fix it carries no uid (GREEN). The reload sequence then
/// confirms teardown of the (correctly un-cross-attributed) dup tree stays
/// clean across repeated `controls_load_ui` cycles.
#[test]
fn duplicate_uid_screen_reload() {
    let dup = read_screen("dup_uid_screen");
    let other = read_screen("dup_uid_reload_target");
    let mut host = new_host();

    // A duplicate uid is a contract violation: the load must REFUSE with a
    // non-OK status (register_uid rejects the second claim → ctx->error →
    // controls_load_ui returns -1), never report success on a compromised
    // tree. True both before and after the fix — the refusal signal already
    // existed; what the fix adds is that no corrupted bookkeeping survives it.
    let rc1 = host.load_ui(&dup);
    println!("load#1(dup) -> {rc1:?}");
    assert!(rc1.is_err(), "colliding load must refuse (non-OK status), got {rc1:?}");
    ticks(&mut host, RENDER_TICKS);

    // The DECISIVE property: the refusal left NO corrupted identity
    // bookkeeping. dump_tree emits "uid" only for a node whose user_data was
    // set, so two live nodes sharing one non-zero uid means a collided node
    // stole another's identity. Pre-fix the button-label "GO" wrongly claims
    // the slider's uid (RED); post-fix it carries none (GREEN).
    let tree: Value =
        serde_json::from_str(&host.dump_tree().expect("dump_tree")).expect("dump_tree JSON");
    let mut uids = Vec::new();
    collect_uids(&tree, &mut uids);
    // Non-vacuity floor: the uniqueness check is meaningless over an empty
    // tree, and its pass value (no dups) equals its nothing-was-built value.
    // The colliding load half-builds an IDENTIFIED tree (the slider registers
    // its uid before the collision), so uids must be non-empty here.
    assert!(
        !uids.is_empty(),
        "non-vacuity: colliding load built no identified node — the uniqueness \
         assertion would pass over an empty tree"
    );
    let mut seen = std::collections::BTreeSet::new();
    let dups: Vec<u64> = uids.iter().copied().filter(|u| !seen.insert(*u)).collect();
    assert!(
        dups.is_empty(),
        "colliding load left duplicate uid(s) in the live tree: {dups:?} \
         (a collided node stole another's identity; all uids: {uids:?})"
    );

    // Reload teardown across repeated cycles must not trap. The clean 'other'
    // screen loads OK; the dup screen refuses again (-1) but must not corrupt
    // or trap the teardown of the previous tree.
    let rc2 = host.load_ui(&other);
    println!("load#2(other, tears down dup) -> {rc2:?}");
    assert!(rc2.is_ok(), "clean reload load#2 failed: {rc2:?}");
    ticks(&mut host, RENDER_TICKS);
    let rc3 = host.load_ui(&dup);
    println!("load#3(dup again, refuses) -> {rc3:?}");
    ticks(&mut host, RENDER_TICKS);
    let rc4 = host.load_ui(&other);
    println!("load#4(other) -> {rc4:?}");
    assert!(rc4.is_ok(), "clean reload load#4 failed: {rc4:?}");
    ticks(&mut host, RENDER_TICKS);
}
