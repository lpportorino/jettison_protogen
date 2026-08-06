/**
 * gesture.h — the pure, LVGL-free, malloc-free pointer/wheel gesture FSM.
 *
 * A C port of the web consumer's gesture-core recognizer (the GestureRecognizer
 * class). The web recognizer and this host recognizer are the two faces of one
 * cross-target state machine; both mirror GESTURE_THRESHOLDS and the NDC
 * convention verbatim (generated/gesture_thresholds.h is the C projection of
 * the one home, edn/gesture-thresholds.edn).
 *
 * THE PARITY IS NOT BYTE-FOR-BYTE, AND ONE DIVERGENCE IS PINNED BY A TEST.
 * This header used to claim byte-for-byte, which is a stronger promise than the
 * code keeps: the TIMESTAMP DOMAIN differs. `t_ms` reaches this FSM narrowed to
 * int32, and the double-tap elapsed is computed as an UNSIGNED modular delta,
 * where the reference uses a signed floating-point interval. The two agree on
 * every ordinary input and disagree on a backwards-stamped release — unsigned
 * arbitrates it to a tap, signed to a track. That is deliberate: the unsigned
 * subtraction is also what keeps the wrap well-defined rather than undefined.
 * `test_backwards_stamped_release_is_tap` in the wasm harness is the case, and
 * it fails if this is rewritten to a signed delta.
 *
 * Read the parity claim as: same states, same thresholds, same NDC convention,
 * with the timestamp domain carved out. A consumer diffing behaviour against
 * the web recognizer should expect agreement everywhere else.
 *
 * Phases: idle -> press1 -> {panning | (via 2nd pointer) pinching}. A 1-pointer
 * drag crossing movePx commits to panning and can ONLY end as pan-end; a
 * release that never crossed is tap | track (double-tap arbitration). A 2nd
 * live pointer aborts any pending 1-pointer gesture SILENTLY (no terminal) and
 * enters pinching; the pinch ratchet emits +/-1 per pinchScale spread-ratio
 * step. The wheel path is independent of the FSM.
 *
 * Coordinates are NDC (the shell converts screen px -> NDC; 1 NDC = 100px);
 * x/y are double to match the TS f64 arithmetic exactly. Time is the event
 * timestamp carried in the sample (gesture_feed clocks off t_ms, never a
 * wall clock). Nothing here calls into LVGL or allocates — decisions land in a
 * caller-owned fixed buffer (GESTURE_MAX_DECISIONS).
 */
#ifndef GESTURE_H
#define GESTURE_H
#include <stdint.h>
#define GESTURE_MAX_DECISIONS 32
/* The live-pointer store RETAINS every live pointer (the JS Map keeps all
 * entries); only the pinch VIEW is the first two (#spread's first-two-values
 * read). Retaining the rest is what lets a 3rd pointer PROMOTE into the pair
 * when a first-two pointer lifts — matching the TS oracle. Sized to the shell's
 * pointer table (main.c GESTURE_MAX_POINTERS_TABLE) so this store never
 * overflows before the shell's own loud DOWN-drop (HOSTMSG_DROP_OVERFLOW); a
 * static_assert in main.c enforces that invariant at compile time. */
#define GESTURE_MAX_POINTERS 10
/** A recognized gesture kind; mirrors the TS GestureDecision union tag set. */
typedef enum {
  GESTURE_KIND_PAN_MOVE = 0,
  GESTURE_KIND_PAN_END = 1,
  GESTURE_KIND_TAP = 2,
  GESTURE_KIND_TRACK = 3,
  GESTURE_KIND_PINCH = 4,
  GESTURE_KIND_WHEEL = 5,
  /* ROI rubber-band rectangle: a REGISTRY LOOKUP KEY only, never a value this
   * FSM emits. No gesture_on_* handler returns it; a completed pan (PAN_END) is
   * REINTERPRETED as an ROI rect by the caller when an ROI-mode GestureSpec is
   * registered under this kind (main.c feed_gesture). Kept in this enum to
   * mirror ui_GestureKind (ui_ast.proto) 1:1 — no decision struct carries it. */
  GESTURE_KIND_ROI = 6
} gesture_kind_t;
/** One pointer observation, already mapped to NDC space by the shell. */
typedef struct {
  int32_t pointer_id;
  double x;     /* NDC [-1,1], +x right */
  double y;     /* NDC [-1,1], +y UP (Y-flipped from screen) */
  int32_t t_ms; /* event timestamp, ms */
} gesture_sample_t;
/**
 * A recognized gesture. For pan/tap/track x,y carry the NDC location; for
 * pinch/wheel delta is +/-1 and x,y are unused (0).
 */
typedef struct {
  gesture_kind_t kind;
  double x;
  double y;
  int32_t delta; /* pinch/wheel: +1 or -1; else 0 */
} gesture_decision_t;
typedef enum {
  GESTURE_PHASE_IDLE = 0,
  GESTURE_PHASE_PRESS1 = 1,
  GESTURE_PHASE_PANNING = 2,
  GESTURE_PHASE_PINCHING = 3
} gesture_phase_t;
/**
 * The recognizer state. Fixed-capacity (no malloc): every live pointer is
 * tracked (up to GESTURE_MAX_POINTERS), insertion-ordered; the FSM's pinch view
 * consults only the first two, exactly as the TS Map's first-two-values
 * semantics, but the rest are retained so a later pointer promotes into the
 * pair when a first-two pointer lifts. `count` is the live-pointer count.
 */
typedef struct {
  gesture_phase_t phase;
  int32_t primary_id; /* primary pointer id in press1/panning */
  int32_t has_primary;
  gesture_sample_t start; /* the press-down sample */
  int32_t has_start;
  double move_ndc; /* computed movePx threshold in NDC */
  /* Live pointers, insertion-ordered (matches the JS Map iteration order the
   * #spread() first-two-values read depends on). */
  gesture_sample_t pointers[GESTURE_MAX_POINTERS];
  int32_t count;
  double start_spread; /* NDC distance between the first two at pinch start */
  double pinch_accum;  /* pinch accumulator; starts at 1, steps by pinchScale */
  int32_t has_last_tap; /* the location+time of the last tap (double-tap) */
  double last_tap_x;
  double last_tap_y;
  int32_t last_tap_t;
} gesture_recognizer_t;
/** Reset the recognizer to its initial idle state (mirrors reset()). */
void gesture_reset(gesture_recognizer_t *g);
/**
 * The live drag's ORIGIN: writes the primary pointer's id and the retained DOWN
 * point (NDC) and returns 1, but ONLY while a 1-pointer drag has COMMITTED —
 * phase == PANNING with a start retained and a primary latched. Returns 0
 * otherwise, writing nothing.
 *
 * WHAT THIS DELIBERATELY DOES NOT ANSWER, because the recognizer cannot: WHERE
 * THE POINTER IS NOW. Outside a pinch this FSM never re-stores a moved sample —
 * `pointers[]` is written by onDown and by the pinch branch of onMove alone, so
 * a 1-pointer drag's live entry holds the sample it LANDED with for the whole
 * drag, and the current point exists only as the argument onMove was called
 * with. Retaining it here would be new recognizer state that no decision reads,
 * in a state machine whose whole contract is parity with its cross-target twin.
 * The SHELL's pointer table already tracks every live pointer's current
 * position, so a consumer pairs this origin with that table rather than asking
 * the FSM for something it does not have.
 *
 * The origin comes from `start`, which is the same field a completed drag's
 * rubber-band command is emitted from — so a drawn drag and a sent one cannot
 * disagree about where it began. The pointer table's per-slot down_x/down_y is
 * a DIFFERENT fact and is not interchangeable: an idempotent re-seat (a
 * duplicate DOWN for a live id) rewrites the table's copy while deliberately
 * not re-running onDown, so the two diverge exactly when a host repeats a DOWN.
 *
 * PRESS1 is deliberately NOT a drag: under the movePx threshold no gesture has
 * been recognised, and a release there is a tap or a track. PINCHING is not one
 * either — a second pointer aborts the pending 1-pointer gesture silently, so
 * there is no drag left to describe.
 */
int32_t gesture_drag_origin(const gesture_recognizer_t *g, int32_t *primary_id,
                            double *x, double *y);
/**
 * Feed a pointer-down. Writes any decisions into out[0..GESTURE_MAX_DECISIONS)
 * and returns the count emitted (onDown emits nothing, so always 0 — kept
 * uniform with the other handlers).
 */
int32_t gesture_on_down(gesture_recognizer_t *g, const gesture_sample_t *s,
                        gesture_decision_t *out);
/** Feed a pointer-move. Returns the number of decisions written to out. */
int32_t gesture_on_move(gesture_recognizer_t *g, const gesture_sample_t *s,
                        gesture_decision_t *out);
/** Feed a pointer-up. Returns the number of decisions written to out. */
int32_t gesture_on_up(gesture_recognizer_t *g, const gesture_sample_t *s,
                      gesture_decision_t *out);
/**
 * Feed a pointer-cancel (pointercancel / lostpointercapture / a stale-pointer
 * GC force-release). A SILENT abort: the pointer is dropped from the live set
 * and any pending 1-pointer gesture ends with NO terminal (never a phantom
 * pan-end), CANCEL never seeds last_tap (a cancelled tap must not later become
 * a track), and a pinch losing one finger drops to idle without resuming pan
 * with the survivor — the 2->1 pinch-drop path generalized to every phase.
 * Always returns 0 (kept uniform with the other handlers; `out` is unused).
 */
int32_t gesture_on_cancel(gesture_recognizer_t *g, const gesture_sample_t *s,
                          gesture_decision_t *out);
/** Feed an optional host wheel delta (FSM-independent). Returns one decision.
 */
int32_t gesture_on_wheel(gesture_recognizer_t *g, double delta_y,
                         gesture_decision_t *out);
#endif
/* GESTURE_H */
