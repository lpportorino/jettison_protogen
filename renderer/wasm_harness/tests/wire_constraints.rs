//! Wire VALUE-constraint contracts: the renderer must REFUSE a `ui.Screen`
//! carrying a value its own `.proto` declares illegal, rather than rendering a
//! plausible frame built from a substitute.
//!
//! WHY A SUITE AT ALL — the constraint is declared and then DELETED before this
//! leg is built. `scripts/proto_cleanup.awk` strips every bracketed field
//! option, so the nanopb generator never sees a `buf.validate` annotation and
//! the generated C carries no trace of one. What survives the strip is only
//! `proto/ui/ui_ast.options`: nanopb `max_size` / `max_count` still bound the
//! generated structs, so a string or a static array past its bound is refused
//! by the decoder itself. Nothing else is. `lte`, `gte`, `min_len`,
//! `min_items` and `enum defined_only` are all invisible here, and an undefined
//! enum value arrives as a plain int the switch below simply does not name.
//!
//! `output/manifests/ui-ast-constraints.json` is the published account of that
//! split — every constraint the proto declares, whether it survives the strip,
//! and if not, what the renderer does instead. Each test here is the BEHAVIOURAL
//! half of one `renderer-guard` entry in it; the manifest names the test, and
//! `make -f renderer.mk manifests` fails if a named test is absent.
//!
//! EVERY CASE IS A PAIR, and the second half is the load-bearing one. A refusal
//! test alone cannot tell a working guard from one that refuses everything, so
//! each illegal input is shadowed by the LEGAL value nearest to it — the last
//! defined enumerator, or the exact declared bound — asserted to load clean.
//! A guard written one off is red in exactly one of the two.
//!
//! WHAT THIS SUITE DOES NOT COVER, so a green is not read as more than it is.
//! It drives exactly the constraints the manifest dispositions `renderer-guard`
//! — a minority of what the proto declares. Most of the rest are recorded there
//! as `unenforced`, deliberately and with the harm named. A green here says
//! those guards fire and admit their own bound; it says nothing about the
//! others, and the manifest is where their account lives.
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

/// One past the highest `ui.WidgetType` enumerator. Deliberately NOT derived
/// from the generated binding: the test's job is to prove the renderer refuses
/// a value its own descriptor does not name, so reading the descriptor to pick
/// the value would make the input legal by construction the day the vocabulary
/// grows.
const UNDEFINED_WIDGET_TYPE: i32 = 9001;

/// Mirrors `MAX_HIT_SLOP` in `renderer/src/renderer.c`, which mirrors
/// `WidgetNode.hit_slop`'s `lte: 64` in `proto/ui/ui_ast.proto`. Duplicated
/// here on the same reasoning `decode_limits.rs` gives for `MAX_DECODE_DEPTH`:
/// the test holds the C constant to the wire's promise, so reading the promise
/// from the place that makes it would assert nothing.
const MAX_HIT_SLOP: u32 = 64;

/// Mirrors `MAX_TARGET_BORDER_WIDTH` in `renderer/src/renderer.c`, which
/// mirrors `TargetOverlayProps.border_width`'s `lte: 16`. Duplicated for the
/// reason above.
const MAX_TARGET_BORDER_WIDTH: u32 = 16;

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

fn screen_of(root: ui::WidgetNode) -> Vec<u8> {
    ui::Screen {
        root: Some(root),
        subjects: vec![],
    }
    .encode_to_vec()
}

/// The subject `visibility_screen` binds against, DECLARED — without it the
/// renderer refuses the load for an unresolved subject, which is a real clause
/// and the wrong one. Measured before this existed: `undefined_compare_op_is_refused`
/// passed against the unfixed renderer, and its own control failed with the
/// same `-2`, which is what exposed the attribution. A refusal shown is not a
/// refusal attributed.
const PROBE_SUBJECT: &str = "probe_subject";

fn screen_with_subject(root: ui::WidgetNode) -> Vec<u8> {
    ui::Screen {
        root: Some(root),
        subjects: vec![ui::SubjectDeclaration {
            name: PROBE_SUBJECT.to_owned(),
            r#type: ui::SubjectType::SubjectInt as i32,
            initial: Some(ui::subject_declaration::Initial::IntInitial(0)),
        }],
    }
    .encode_to_vec()
}

/// `controls_load_ui`'s status for these bytes. A trap is a separate failure
/// from a refusal and is reported as one: the guest going down gives the host
/// nothing to act on, which is the outcome every guard here exists to avoid.
fn load_status(bytes: &[u8]) -> i32 {
    let mut host = new_host();
    host.load_ui_raw(bytes)
        .expect("load_ui TRAPPED — a constraint guard must refuse, not crash")
}

fn assert_refused(bytes: &[u8], what: &str) {
    let status = load_status(bytes);
    assert!(
        status != 0,
        "{what} must be REFUSED; controls_load_ui returned success ({status})"
    );
}

fn assert_loads_clean(bytes: &[u8], what: &str) {
    let status = load_status(bytes);
    assert_eq!(status, 0, "{what} is LEGAL and must load; got status {status}");
}

// ── WidgetNode.type — `enum defined_only` ──────────────────────────────────
//
// The undefined value does not fall into a hole: `ensure_widget`'s switch has a
// `default:` arm that builds a bare `lv_obj`, which is ALSO the arm WIDGET_OBJ
// takes. So an undefined type renders as an empty box, indistinguishable from a
// screen whose author wrote one — the precise failure `apply_widget_props`'s own
// default arm refuses for the props half of the same node.

#[test]
fn undefined_widget_type_is_refused() {
    assert_refused(
        &screen_of(ui::WidgetNode {
            r#type: UNDEFINED_WIDGET_TYPE,
            ..Default::default()
        }),
        "a WidgetNode whose type is not a defined ui.WidgetType",
    );
}

/// The control, and it is the sharp one: `WIDGET_OBJ` is zero and shares the
/// `default:` arm with the undefined case, so a guard that refuses by reaching
/// the default rather than by testing the value refuses every plain container
/// in every screen this repo ships.
#[test]
fn widget_type_obj_still_loads_clean() {
    assert_loads_clean(
        &screen_of(plain_node()),
        "a WIDGET_OBJ node (the zero enumerator)",
    );
}

/// The other end of the vocabulary, so a guard written as a range cannot be off
/// by one at the top.
#[test]
fn widget_type_at_the_highest_defined_value_loads_clean() {
    assert_loads_clean(
        &screen_of(ui::WidgetNode {
            r#type: ui::WidgetType::WidgetTargetOverlay as i32,
            ..Default::default()
        }),
        "a WIDGET_TARGET_OVERLAY node (the highest defined enumerator)",
    );
}

// ── WidgetNode.hit_slop — `lte: 64` ────────────────────────────────────────
//
// TWO harms, and the second is why a clamp would not do. Arithmetically the
// value reaches `LV_DPX`, which is `(dpi * n + 80) / 160` on `int32_t` — a
// wire-controlled multiply that overflows for large n, which is undefined
// behaviour rather than a big number. Semantically `lv_obj_set_ext_click_area`
// GROWS the node's reachable box, and `lv_indev_search_obj` returns the FIRST
// hit walking children in reverse: an oversized slop silently swallows presses
// aimed at every sibling drawn before it, with no pixel and no event to show
// for it. That is the dead-zone class, arriving through the one wire field that
// can widen a hit box at all.

#[test]
fn hit_slop_past_the_declared_bound_is_refused() {
    assert_refused(
        &screen_of(ui::WidgetNode {
            hit_slop: MAX_HIT_SLOP + 1,
            ..plain_node()
        }),
        "a hit_slop one past the declared lte bound",
    );
}

/// The overflow input specifically — the one that reaches `LV_DPX`'s multiply
/// with a value no clamp inside LVGL is guarding.
#[test]
fn hit_slop_at_the_int32_ceiling_is_refused() {
    assert_refused(
        &screen_of(ui::WidgetNode {
            hit_slop: u32::MAX,
            ..plain_node()
        }),
        "a hit_slop of 0xFFFFFFFF",
    );
}

#[test]
fn hit_slop_at_the_declared_bound_loads_clean() {
    assert_loads_clean(
        &screen_of(ui::WidgetNode {
            hit_slop: MAX_HIT_SLOP,
            ..plain_node()
        }),
        "a hit_slop exactly at the declared lte bound",
    );
}

// ── TargetOverlayProps.border_width — `lte: 16` ────────────────────────────
//
// Reaches the same `LV_DPX` multiply, from the widget whose sibling bound —
// the box count — IS enforced, by `MAX_TARGET_BOXES` in the renderer. A reader
// comparing the two in the proto sees two declared bounds and cannot tell which
// one anything upholds.

/// `border_width` is `Option` because the field is proto3 `optional`: its ZERO
/// is a stroke-less box, so presence is what selects an authored width over the
/// renderer's own default. The bound check is on the PRESENT value, which is
/// what these cases drive.
fn target_overlay_screen(border_width: Option<u32>) -> Vec<u8> {
    screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetTargetOverlay as i32,
        widget_props: Some(ui::widget_node::WidgetProps::TargetOverlayProps(
            ui::TargetOverlayProps {
                boxes: vec![ui::TargetBox {
                    x: 10,
                    y: 10,
                    w: 40,
                    h: 40,
                    ..Default::default()
                }],
                border_width,
                hide_labels: false,
            },
        )),
        ..Default::default()
    })
}

#[test]
fn target_overlay_border_width_past_the_declared_bound_is_refused() {
    assert_refused(
        &target_overlay_screen(Some(MAX_TARGET_BORDER_WIDTH + 1)),
        "a target-overlay border_width one past the declared lte bound",
    );
}

#[test]
fn target_overlay_border_width_at_the_int32_ceiling_is_refused() {
    assert_refused(
        &target_overlay_screen(Some(u32::MAX)),
        "a target-overlay border_width of 0xFFFFFFFF",
    );
}

#[test]
fn target_overlay_border_width_at_the_declared_bound_loads_clean() {
    assert_loads_clean(
        &target_overlay_screen(Some(MAX_TARGET_BORDER_WIDTH)),
        "a target-overlay border_width exactly at the declared lte bound",
    );
}

/// A PRESENT zero is a stroke-less box — the caption alone marks the detection
/// — so it must survive a bound check written as a range. It used to mean "give
/// me the renderer's default stroke", which is now what ABSENCE means; the case
/// below pins that second reading so the two cannot collapse back together.
#[test]
fn target_overlay_border_width_zero_loads_clean() {
    assert_loads_clean(
        &target_overlay_screen(Some(0)),
        "a target-overlay border_width of 0 (an explicit stroke-less box)",
    );
}

/// The other half of the pair above: no `border_width` on the wire at all, which
/// is what now requests the renderer's own default stroke.
#[test]
fn target_overlay_border_width_absent_loads_clean() {
    assert_loads_clean(
        &target_overlay_screen(None),
        "a target-overlay with no border_width (the default-stroke request)",
    );
}

// ── EventBinding.trigger — `enum defined_only` ─────────────────────────────
//
// The undefined value silently becomes LV_EVENT_CLICKED, so a control its
// author bound to a LONG PRESS fires on a tap. Nothing in the frame differs,
// and the wrong command reaches the device.

fn event_screen(trigger: i32) -> Vec<u8> {
    screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetButton as i32,
        event: Some(ui::EventBinding {
            name: "probe".to_owned(),
            trigger,
            ..Default::default()
        }),
        ..Default::default()
    })
}

#[test]
fn undefined_event_trigger_is_refused() {
    assert_refused(
        &event_screen(UNDEFINED_WIDGET_TYPE),
        "an EventBinding whose trigger is not a defined ui.EventTrigger",
    );
}

#[test]
fn event_trigger_at_the_highest_defined_value_loads_clean() {
    assert_loads_clean(
        &event_screen(ui::EventTrigger::TriggerLongPressed as i32),
        "an EventBinding with TRIGGER_LONG_PRESSED (the highest defined enumerator)",
    );
}

// ── VisibilityBinding.compare — `enum defined_only` ────────────────────────
//
// `compare_holds`'s `default:` returns equality, and the three binding sites
// that consume the shape route anything they do not name into that observer.
// So an undefined operator silently becomes `==`: a control meant to appear
// ABOVE a threshold appears only exactly AT it.

fn visibility_screen(compare: i32) -> Vec<u8> {
    screen_with_subject(ui::WidgetNode {
        visibility: Some(ui::VisibilityBinding {
            subject: PROBE_SUBJECT.to_owned(),
            ref_value: 1,
            compare,
        }),
        ..plain_node()
    })
}

#[test]
fn undefined_compare_op_is_refused() {
    assert_refused(
        &visibility_screen(UNDEFINED_WIDGET_TYPE),
        "a VisibilityBinding whose compare is not a defined ui.CompareOp",
    );
}

#[test]
fn compare_op_at_the_highest_defined_value_loads_clean() {
    assert_loads_clean(
        &visibility_screen(ui::CompareOp::CompareLte as i32),
        "a VisibilityBinding with COMPARE_LTE (the highest defined enumerator)",
    );
}

// ── The three `max_items` bounds nanopb cannot carry ───────────────────────
//
// These fields are FT_POINTER, so nanopb allocates whatever the stream declares
// and there is no static width to overrun. Their bound lives ONLY in the proto's
// `max_items`, which this leg cannot see, so a renderer count check is the whole
// enforcement. Those checks predate this suite; the cases below are what makes
// the manifest's claim about them testable rather than asserted.

/// Mirrors `MAX_TARGET_BOXES` in `renderer/src/renderer.c`, which mirrors
/// `TargetOverlayProps.boxes`' `max_items: 32`.
const MAX_TARGET_BOXES: usize = 32;

/// Mirrors `CMD_PATCH_MAX_BY_VALUE` in `renderer/src/cmd_patch.h`, which mirrors
/// `EventBinding.cmd_by_value`'s `max_items: 16`.
const MAX_CMD_BY_VALUE: usize = 16;

/// Mirrors `CMD_PATCH_MAX_GESTURES` in `renderer/src/cmd_patch.h`, which mirrors
/// `WidgetNode.gestures`' `max_items`. Duplicated rather than derived, on the
/// same reasoning the constants above give: the test holds the C constant to the
/// wire's promise, so reading the promise from the place that makes it would
/// assert nothing. `main.c`'s static_assert derives only a FLOOR from the
/// gesture vocabulary and cannot see `max_items` at all, so this pair is what
/// pins the C bound from ABOVE — but it pins it to THIS TRANSCRIPTION, not to
/// the proto. `ui.WidgetNode.gestures` is FT_POINTER, so it carries no nanopb
/// `max_count` for the constraints manifest to compare against either. Nothing
/// mechanical reads `max_items` here; three copies are kept in step by review.
const MAX_GESTURES: usize = 9;

fn overlay_with_boxes(n: usize) -> Vec<u8> {
    screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetTargetOverlay as i32,
        widget_props: Some(ui::widget_node::WidgetProps::TargetOverlayProps(
            ui::TargetOverlayProps {
                boxes: (0..n)
                    .map(|i| ui::TargetBox {
                        x: i as i32,
                        y: i as i32,
                        w: 4,
                        h: 4,
                        ..Default::default()
                    })
                    .collect(),
                border_width: None,
                hide_labels: true,
            },
        )),
        ..Default::default()
    })
}

#[test]
fn target_overlay_boxes_past_the_declared_bound_is_refused() {
    assert_refused(
        &overlay_with_boxes(MAX_TARGET_BOXES + 1),
        "a target overlay one box past the declared max_items",
    );
}

#[test]
fn target_overlay_boxes_at_the_declared_bound_loads_clean() {
    assert_loads_clean(
        &overlay_with_boxes(MAX_TARGET_BOXES),
        "a target overlay with exactly max_items boxes",
    );
}

/// A minimal `CmdSpec` — the count is what is under test, so the template is
/// empty and no `FieldPatch` rides along.
fn bare_cmd_spec() -> ui::CmdSpec {
    ui::CmdSpec {
        command_id: "cmd.Probe".to_owned(),
        root_template: vec![],
        patches: vec![],
        // No slot receives a y, so there is no destination plane to state —
        // and stating one would be a fact about nothing. Written out rather
        // than defaulted so this file says which of the two cases it is.
        ndc_y_sense: ui::NdcYSense::Unspecified as i32,
    }
}

// ââ CmdSpec.ndc_y_sense â `enum defined_only`, and the obligation the guard adds â
//
// The gesture recognizer works in ONE plane (ui_input's, +y UP) and the device's
// NDC commands do not all share it, so a spec that patches a y must SAY which
// plane it is writing into. Both senses are byte-legal and range-legal in the
// other's, so neither may be the default: a guess produces a vertically
// mirrored command that decodes cleanly, is inside every declared range, and is
// detectable by nothing downstream.
//
// The guard is therefore CONDITIONAL, and the tests below pin both halves. A
// spec with a y slot must carry a defined, non-UNSPECIFIED sense; a spec with no
// y slot must still load with UNSPECIFIED â which is what every widget-value
// and form spec in this repository carries, so a guard that refused by reaching
// a default rather than by testing for a y slot would refuse all of them.

/// One past the highest `ui.NdcYSense` enumerator, chosen the way
/// `UNDEFINED_DELTA_SIGN` is: NOT derived from the binding, or it would become
/// legal the day the vocabulary grows.
const UNDEFINED_NDC_Y_SENSE: i32 = 9002;

/// A button whose `EventBinding.cmd` patches an NDC y into an 8-byte template,
/// carrying `sense`. The template is 8 zero bytes because the slot-bounds guard
/// at the same decode boundary refuses a slot that does not fit — an empty
/// template would be refused for THAT reason and prove nothing about this one.
fn ndc_y_screen(sense: i32) -> Vec<u8> {
    screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetButton as i32,
        event: Some(ui::EventBinding {
            name: "probe".to_owned(),
            cmd: Some(ui::CmdSpec {
                command_id: "cmd.Probe".to_owned(),
                root_template: vec![0u8; 8],
                patches: vec![ui::FieldPatch {
                    byte_offset: 0,
                    byte_width: 8,
                    kind: ui::PatchKind::NdcY as i32,
                    ..Default::default()
                }],
                ndc_y_sense: sense,
            }),
            ..Default::default()
        }),
        ..Default::default()
    })
}

#[test]
fn undefined_ndc_y_sense_is_refused() {
    assert_refused(
        &ndc_y_screen(UNDEFINED_NDC_Y_SENSE),
        "a CmdSpec patching an NDC y whose ndc_y_sense is not a defined ui.NdcYSense",
    );
}

/// The sharp case, and the reason the zero value is not a plane. UNSPECIFIED is
/// a DEFINED enumerator, so `defined_only` alone would admit it â and admitting
/// it means the renderer picks a plane, which is the mirror this whole field
/// exists to prevent.
#[test]
fn unspecified_ndc_y_sense_on_a_y_bearing_spec_is_refused() {
    assert_refused(
        &ndc_y_screen(ui::NdcYSense::Unspecified as i32),
        "a CmdSpec that patches an NDC y without stating its destination plane",
    );
}

/// The control at the top of the vocabulary, so a guard written as a range
/// cannot be off by one there.
#[test]
fn ndc_y_sense_down_loads_clean() {
    assert_loads_clean(
        &ndc_y_screen(ui::NdcYSense::Down as i32),
        "a y-DOWN destination plane (the highest defined enumerator)",
    );
}

#[test]
fn ndc_y_sense_up_loads_clean() {
    assert_loads_clean(
        &ndc_y_screen(ui::NdcYSense::Up as i32),
        "a y-UP destination plane",
    );
}

/// The control at the bottom, and the one that makes the guard's CONDITION the
/// thing under test rather than its verdict: a spec with no y slot carries
/// UNSPECIFIED and must load. This is every widget-value, by-value and form spec
/// this repository ships.
#[test]
fn unspecified_ndc_y_sense_without_a_y_slot_loads_clean() {
    assert_loads_clean(
        &screen_of(ui::WidgetNode {
            r#type: ui::WidgetType::WidgetButton as i32,
            event: Some(ui::EventBinding {
                name: "probe".to_owned(),
                cmd: Some(bare_cmd_spec()),
                ..Default::default()
            }),
            ..Default::default()
        }),
        "a CmdSpec with no NDC y slot and no stated plane",
    );
}

fn cmd_by_value_screen(n: usize) -> Vec<u8> {
    screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetButton as i32,
        event: Some(ui::EventBinding {
            name: "probe".to_owned(),
            cmd_by_value: (0..n).map(|_| bare_cmd_spec()).collect(),
            ..Default::default()
        }),
        ..Default::default()
    })
}

#[test]
fn cmd_by_value_past_the_declared_bound_is_refused() {
    assert_refused(
        &cmd_by_value_screen(MAX_CMD_BY_VALUE + 1),
        "an EventBinding one cmd_by_value template past the declared max_items",
    );
}

#[test]
fn cmd_by_value_at_the_declared_bound_loads_clean() {
    assert_loads_clean(
        &cmd_by_value_screen(MAX_CMD_BY_VALUE),
        "an EventBinding with exactly max_items cmd_by_value templates",
    );
}

/// Every spec CARRIES a cmd, because the renderer's count is over cmd-bearing
/// entries only — the partiality `ui-ast-constraint-dispositions.edn` records
/// for this field. A fixture of cmd-less specs would not reach the guard, and
/// the resulting green would read as coverage.
///
/// EVERY SPEC IS ALSO LIVE AND PAIRWISE DISJOINT, which is a stronger property
/// than it first looks and is what makes the `_at_the_declared_bound_` control
/// mean something. The renderer refuses an entry that can never answer a
/// decision — a SIGNED selector on a kind whose decisions carry no step — as
/// well as two entries of one kind that could both answer one. So the legal
/// registry is exactly: one ANY entry per kind that carries no step, plus the
/// {POSITIVE, NEGATIVE} pair on each STEPPED kind. That is NINE, and it is the
/// same nine `max_items` bounds, which is not a coincidence — the bound is
/// derived from it. This generator enumerates that maximum in order.
///
/// The previous version emitted N identical Tap specs, and a version before
/// this one alternated POSITIVE/NEGATIVE across every kind; both would now have
/// the `_at_the_declared_bound_` control assert that a screen of PERMANENTLY
/// DEAD templates loads clean, which is verbatim the failure the guard exists
/// to remove.
///
/// Past `LEGAL_SLOTS` the surplus repeats the last slot. Those entries are
/// unreachable by construction: the COUNT check runs before the selector and
/// disjointness checks and `break`s, so the refusal is attributable to the
/// count and the repeat is never judged.
fn gestures_screen(n: usize) -> Vec<u8> {
    /// The stepless kinds, each of which admits exactly ONE entry (ANY).
    const STEPLESS: [ui::GestureKind; 5] = [
        ui::GestureKind::PanMove,
        ui::GestureKind::PanEnd,
        ui::GestureKind::Tap,
        ui::GestureKind::Track,
        ui::GestureKind::Roi,
    ];
    /// The five stepless kinds plus a direction each for the two STEPPED kinds
    /// — which is the whole legal registry, and the same sum `max_items`
    /// bounds. That is not a coincidence: the bound is derived from it.
    const LEGAL_SLOTS: usize = 9;
    let slot = |i: usize| -> (ui::GestureKind, ui::GestureDeltaSign) {
        match i {
            0..=4 => (STEPLESS[i], ui::GestureDeltaSign::Any),
            5 => (ui::GestureKind::Pinch, ui::GestureDeltaSign::Positive),
            6 => (ui::GestureKind::Pinch, ui::GestureDeltaSign::Negative),
            7 => (ui::GestureKind::Wheel, ui::GestureDeltaSign::Positive),
            _ => (ui::GestureKind::Wheel, ui::GestureDeltaSign::Negative),
        }
    };
    screen_of(ui::WidgetNode {
        gestures: (0..n)
            .map(|i| {
                let (kind, sign) = slot(i.min(LEGAL_SLOTS - 1));
                ui::GestureSpec {
                    kind: kind as i32,
                    delta_sign: sign as i32,
                    cmd: Some(bare_cmd_spec()),
                }
            })
            .collect(),
        ..plain_node()
    })
}

// ── GestureSpec.delta_sign — `enum defined_only` ───────────────────────────
//
// An undefined selector is not merely inert. The drain's resolver names the
// three defined values and answers false to anything else, so the entry matches
// no decision — but the ROI path resolves through a KIND-ONLY mode probe that
// never consults the selector at all, so the same entry stays live there. One
// entry that fires on one path and answers nothing on the other is worse than
// one that does nothing, and no frame shows the difference.

/// One past the highest `ui.GestureDeltaSign` enumerator, chosen the way
/// `UNDEFINED_WIDGET_TYPE` is: not derived from the binding, or it would become
/// legal the day the vocabulary grows.
const UNDEFINED_DELTA_SIGN: i32 = 9001;

fn gesture_sign_screen(kind: ui::GestureKind, delta_sign: i32) -> Vec<u8> {
    screen_of(ui::WidgetNode {
        gestures: vec![ui::GestureSpec {
            kind: kind as i32,
            delta_sign,
            cmd: Some(bare_cmd_spec()),
        }],
        ..plain_node()
    })
}

#[test]
fn undefined_gesture_delta_sign_is_refused() {
    assert_refused(
        &gesture_sign_screen(ui::GestureKind::Pinch, UNDEFINED_DELTA_SIGN),
        "a GestureSpec whose delta_sign is not a defined ui.GestureDeltaSign",
    );
}

/// The control at the top of the vocabulary, so a guard written as a range
/// cannot be off by one there.
#[test]
fn gesture_delta_sign_at_the_highest_defined_value_loads_clean() {
    assert_loads_clean(
        &gesture_sign_screen(
            ui::GestureKind::Pinch,
            ui::GestureDeltaSign::Negative as i32,
        ),
        "a NEGATIVE selector on PINCH (the highest defined enumerator)",
    );
}

/// The control at the bottom, and the sharp one: ANY is ZERO, which is what
/// every spec written before this field existed carries. A guard that refused
/// by reaching a default rather than by testing the value would refuse every
/// gesture spec in every screen this repo ships.
#[test]
fn gesture_delta_sign_any_still_loads_clean() {
    assert_loads_clean(
        &gesture_sign_screen(ui::GestureKind::Tap, ui::GestureDeltaSign::Any as i32),
        "an ANY selector (the zero enumerator) on a stepless kind",
    );
}

/// A DEFINED but unanswerable selector: POSITIVE tests `delta > 0` and a TAP
/// decision carries 0, so this entry can never fire. Distinct from the case
/// above — the value is in the vocabulary; it is the KIND that makes it dead.
#[test]
fn a_signed_selector_on_a_stepless_kind_is_refused() {
    assert_refused(
        &gesture_sign_screen(
            ui::GestureKind::Tap,
            ui::GestureDeltaSign::Positive as i32,
        ),
        "a POSITIVE selector on TAP, whose decisions carry no step",
    );
}

#[test]
fn gestures_past_the_declared_bound_is_refused() {
    assert_refused(
        &gestures_screen(MAX_GESTURES + 1),
        "a node one cmd-bearing gesture past the declared max_items",
    );
}

#[test]
fn gestures_at_the_declared_bound_loads_clean() {
    assert_loads_clean(
        &gestures_screen(MAX_GESTURES),
        "a node with exactly max_items cmd-bearing gestures",
    );
}

// ── TreePatchOp.kind — `enum defined_only`, on the PATCH path ──────────────
//
// The one `defined_only` this renderer already refused before the constraint
// manifest existed. It is here so the manifest can distinguish it from the
// enums that fall through, and because it is reached through
// `controls_apply_patch` rather than `controls_load_ui` — a second entry point
// no other case in this file exercises.

/// Refusal code for a malformed op (`PATCH_ERR_ARG`).
const PATCH_ERR_ARG: i32 = -5;

/// FNV-1a-32 over bytes — matches `fnv1a32` in `renderer/src/main.c`. A
/// `ScreenPatch` must carry the current state's hash as its `base_hash` or the
/// reconciler refuses it, and THAT refusal is a neighbouring clause: without a
/// correct hash this case would go red for the wrong reason.
fn fnv1a32(data: &[u8]) -> u32 {
    let mut hash: u32 = 0x811c_9dc5;
    for byte in data {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

fn patch_with_kind(base: &[u8], kind: i32) -> Vec<u8> {
    ui::ScreenPatch {
        base_hash: fnv1a32(base),
        target_hash: 0,
        ops: vec![ui::TreePatchOp {
            kind,
            target_uid: 1,
            parent_uid: 1,
            index: 0,
            node: Some(plain_node()),
        }],
    }
    .encode_to_vec()
}

#[test]
fn undefined_patch_op_kind_is_refused() {
    let base = screen_of(ui::WidgetNode {
        uid: 1,
        ..plain_node()
    });
    let mut host = new_host();
    let status = host.load_ui_raw(&base).expect("load_ui trapped");
    assert_eq!(status, 0, "the base screen must load; got {status}");
    let rc = host
        .apply_patch(&patch_with_kind(&base, UNDEFINED_WIDGET_TYPE))
        .expect("apply_patch trapped — the guard must refuse, not crash");
    assert_eq!(
        rc, PATCH_ERR_ARG,
        "an undefined ui.PatchOpKind must be refused with PATCH_ERR_ARG"
    );
}

/// The control: the SAME patch shape with a defined kind must apply, so the
/// refusal above cannot come from the hash, the uid, or the op payload.
#[test]
fn defined_patch_op_kind_applies() {
    let base = screen_of(ui::WidgetNode {
        uid: 1,
        ..plain_node()
    });
    let mut host = new_host();
    let status = host.load_ui_raw(&base).expect("load_ui trapped");
    assert_eq!(status, 0, "the base screen must load; got {status}");
    let rc = host
        .apply_patch(&patch_with_kind(
            &base,
            ui::PatchOpKind::PatchOpUpdateProps as i32,
        ))
        .expect("apply_patch trapped");
    assert_eq!(rc, 0, "a defined patch op kind must apply; got {rc}");
}

// ── EventBinding: a device-command template the dispatch gate can never reach ──
//
// `button_event_cb` relays a template only when `set_subject` is EMPTY or
// `notify_host` is set. So a binding carrying a template AND a non-empty
// `set_subject` with `notify_host` unset decodes, copies the template into the
// persistent event data, attaches — and then drops it at every fire, silently.
//
// That silence is what this pair is about, and it is peculiar to this
// combination: the neighbouring out-of-range `cmd_by_value` index and the
// both-templates violation each log loudly, while the gate itself says nothing
// because it is an `if`, not a check.
//
// NO `buf.validate` ANNOTATION COULD EXPRESS IT, which is why it is here rather
// than in `output/manifests/ui-ast-constraints.json`: every entry there is one
// declared constraint on one FIELD, and this is a property of a field
// COMBINATION — the routing decision `EventBinding` takes from which fields are
// set rather than from a tag. The renderer is the only layer that sees the whole
// combination, so the report has to live there.
//
// THE VERDICT IS A REPORT, NOT A REFUSAL, matching the both-templates neighbour
// it sits beside: the resolution is deterministic, and refusing would turn a
// screen that loads today into a rejected one — a contract change this suite
// cannot validate against a consumer's own corpus.

/// The distinctive span of the diagnostic under test. Matching a SUBSTRING
/// rather than the whole line keeps the assertion on the finding instead of on
/// the wording, while staying specific enough that no other clause emits it.
const UNREACHABLE_MARKER: &str = "cmd template is UNREACHABLE";

/// The both-templates neighbour's marker — an INDEPENDENT clause in the same
/// attach block. It is what makes a red here attributable: a mutation that
/// breaks the unreachable clause must leave this one refusing.
const BOTH_TEMPLATES_MARKER: &str = "carries BOTH cmd and cmd_by_value";

/// `controls_load_ui`'s status for these bytes, plus everything the guest wrote
/// to stderr while loading them. Capture is OFF by default in the harness, so a
/// test whose subject IS a log line must ask for it.
fn load_capturing_stderr(bytes: &[u8]) -> (i32, String) {
    let config = HostConfig::new(repo_root().join("output/controls.wasm"), WIDTH, HEIGHT)
        .with_wasi_root(repo_root().join("assets"))
        .with_captured_stderr();
    let mut host = ControlsHost::new(&config).expect("failed to create ControlsHost");
    let status = host
        .load_ui_raw(bytes)
        .expect("load_ui TRAPPED — a diagnostic must not take the guest down");
    (status, host.captured_stderr())
}

/// A binding that mutates `PROBE_SUBJECT` and carries a device-command
/// template. `notify_host` is the whole difference between a template the
/// dispatch gate can reach and one it cannot; `by_value` picks which of the two
/// template lanes carries it.
///
/// The subject is DECLARED (`screen_with_subject`), deliberately: an
/// undeclared one makes the deferred `event set_subject references unknown
/// subject` clause fire on this same input, and a red would then be compatible
/// with the clause under test being dead.
fn subject_mutating_cmd_screen(notify_host: bool, by_value: bool) -> Vec<u8> {
    let mut event = ui::EventBinding {
        name: "probe".to_owned(),
        set_subject: PROBE_SUBJECT.to_owned(),
        set_value: 1,
        notify_host,
        ..Default::default()
    };
    if by_value {
        event.cmd_by_value = vec![bare_cmd_spec()];
    } else {
        event.cmd = Some(bare_cmd_spec());
    }
    screen_with_subject(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetButton as i32,
        event: Some(event),
        ..Default::default()
    })
}

#[test]
fn cmd_unreachable_behind_a_local_subject_is_reported() {
    let (status, err) = load_capturing_stderr(&subject_mutating_cmd_screen(false, false));
    assert!(
        err.contains(UNREACHABLE_MARKER) && err.contains(PROBE_SUBJECT),
        "a `cmd` gated off by set_subject without notify_host must be REPORTED, \
         naming the subject that gates it; stderr was: {err}"
    );
    assert_eq!(
        status, 0,
        "the report is a diagnostic, not a refusal — the load must still succeed"
    );
}

#[test]
fn cmd_by_value_unreachable_behind_a_local_subject_is_reported() {
    let (status, err) = load_capturing_stderr(&subject_mutating_cmd_screen(false, true));
    assert!(
        err.contains(UNREACHABLE_MARKER),
        "a `cmd_by_value` table gated off the same way must be REPORTED too — \
         the emit gate drops both lanes together; stderr was: {err}"
    );
    assert_eq!(status, 0, "a diagnostic, not a refusal");
}

/// The control, and it carries its own NON-VACUITY GUARD. "The marker is
/// absent" is also what a dead harness prints, so this test first drives the
/// known-bad fixture through the SAME helper and requires the marker to appear.
/// Without that, a capture that silently stopped working would pass here and
/// read as proof that the clause discriminates.
#[test]
fn cmd_reachable_with_notify_host_is_not_reported() {
    let (_, bad) = load_capturing_stderr(&subject_mutating_cmd_screen(false, false));
    assert!(
        bad.contains(UNREACHABLE_MARKER),
        "non-vacuity: the known-bad fixture must reach the clause through this \
         same helper, or the absence asserted below proves nothing"
    );
    let (status, err) = load_capturing_stderr(&subject_mutating_cmd_screen(true, false));
    assert_eq!(status, 0, "a notify_host binding is legal and must load");
    assert!(
        !err.contains(UNREACHABLE_MARKER),
        "notify_host REOPENS the gate — the template is reachable, so reporting \
         it would fire on every legitimate notify-and-send binding; stderr was: {err}"
    );
}

/// The second control, on the other axis: no local subject at all. The gate is
/// open, so a template here is reachable and must draw no report.
#[test]
fn cmd_without_a_local_subject_is_not_reported() {
    let screen = screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetButton as i32,
        event: Some(ui::EventBinding {
            name: "probe".to_owned(),
            cmd: Some(bare_cmd_spec()),
            ..Default::default()
        }),
        ..Default::default()
    });
    let (status, err) = load_capturing_stderr(&screen);
    assert_eq!(status, 0, "a plain host-event binding with a cmd must load");
    assert!(
        !err.contains(UNREACHABLE_MARKER),
        "an empty set_subject leaves the gate open; stderr was: {err}"
    );
}

/// The NEIGHBOUR, pinned so it can serve as the attribution control: the
/// both-templates violation is an independent clause in the same attach block
/// and had no test of its own. Its fixture deliberately carries NO
/// `set_subject`, so exactly one of the two clauses fires on it.
#[test]
fn event_carrying_both_template_lanes_is_reported() {
    let screen = screen_of(ui::WidgetNode {
        r#type: ui::WidgetType::WidgetButton as i32,
        event: Some(ui::EventBinding {
            name: "probe".to_owned(),
            cmd: Some(bare_cmd_spec()),
            cmd_by_value: vec![bare_cmd_spec()],
            ..Default::default()
        }),
        ..Default::default()
    });
    let (status, err) = load_capturing_stderr(&screen);
    assert!(
        err.contains(BOTH_TEMPLATES_MARKER),
        "a binding carrying both template lanes must be REPORTED; stderr was: {err}"
    );
    assert!(
        !err.contains(UNREACHABLE_MARKER),
        "this fixture carries no set_subject, so the unreachable clause must \
         stay silent on it — that separation is what makes it an attribution \
         control; stderr was: {err}"
    );
    assert_eq!(status, 0, "a diagnostic, not a refusal");
}
