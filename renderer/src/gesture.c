/**
 * gesture.c — the pure pointer/wheel gesture FSM (see gesture.h).
 *
 * The arithmetic is double (f64) throughout because two contracts are
 * boundary-sensitive: the movePx strict-> threshold and the pinch ratchet
 * accumulator. px -> NDC uses the "1 NDC = 100px" convention.
 */
#include "gesture.h"
#include "gesture_thresholds.h"
#include <math.h>
/* px -> NDC: 1 NDC unit = 100px. */
static double px_to_ndc(double px) { return px / 100.0; }
/* Euclidean distance between two NDC points.
 * STATIC: used only within this translation unit. It carried external
 * linkage with no cross-TU reference and no export, which is a symbol the
 * linker must keep and no caller can reach — `misc-use-internal-linkage`'s
 * class, found by an llvm-nm sweep over the whole link set. */
static double gesture_ndc_dist(double ax, double ay, double bx, double by) {
  double dx = ax - bx;
  double dy = ay - by;
  return hypot(dx, dy);
}
void gesture_reset(gesture_recognizer_t *g) {
  g->phase = GESTURE_PHASE_IDLE;
  g->primary_id = 0;
  g->has_primary = 0;
  g->has_start = 0;
  g->move_ndc = 0.0;
  g->count = 0;
  g->start_spread = 0.0;
  g->pinch_accum = 1.0;
  g->has_last_tap = 0;
  g->last_tap_x = 0.0;
  g->last_tap_y = 0.0;
  g->last_tap_t = 0;
}
/* Find a live pointer slot by id; -1 if absent. */
static int32_t pointer_index(const gesture_recognizer_t *g,
                             int32_t pointer_id) {
  for (int32_t i = 0; i < g->count; i++) {
    if (g->pointers[i].pointer_id == pointer_id)
      return i;
  }
  return -1;
}
/* Map.set: update in place if present, else append (insertion order). */
static void pointers_set(gesture_recognizer_t *g, const gesture_sample_t *s) {
  int32_t idx = pointer_index(g, s->pointer_id);
  if (idx >= 0) {
    g->pointers[idx] = *s;
    return;
  }
  if (g->count < GESTURE_MAX_POINTERS) {
    g->pointers[g->count] = *s;
    g->count++;
  }
}
/* Map.delete: remove by id, preserving the order of the remaining entries. */
static void pointers_delete(gesture_recognizer_t *g, int32_t pointer_id) {
  int32_t idx = pointer_index(g, pointer_id);
  if (idx < 0)
    return;
  for (int32_t i = idx; i + 1 < g->count && i + 1 < GESTURE_MAX_POINTERS; i++)
    g->pointers[i] = g->pointers[i + 1];
  g->count--;
}
/* Current NDC spread between the first two live pointers, or 0 if < 2. */
static double spread(const gesture_recognizer_t *g) {
  if (g->count < 2)
    return 0.0;
  return gesture_ndc_dist(g->pointers[0].x, g->pointers[0].y, g->pointers[1].x,
                          g->pointers[1].y);
}
/* Emit one {pinch, +/-1} per full pinchScale spread-ratio step crossed. */
static int32_t ratchet(gesture_recognizer_t *g, gesture_decision_t *out) {
  int32_t n = 0;
  if (g->start_spread <= 0.0)
    return 0;
  double cur = spread(g);
  if (cur <= 0.0)
    return 0;
  double ratio = cur / g->start_spread;
  double step = GESTURE_PINCH_SCALE;
  while (ratio >= g->pinch_accum + step && n < GESTURE_MAX_DECISIONS) {
    g->pinch_accum += step;
    out[n].kind = GESTURE_KIND_PINCH;
    out[n].x = 0.0;
    out[n].y = 0.0;
    out[n].delta = 1;
    n++;
  }
  while (ratio <= g->pinch_accum - step && n < GESTURE_MAX_DECISIONS) {
    g->pinch_accum -= step;
    out[n].kind = GESTURE_KIND_PINCH;
    out[n].x = 0.0;
    out[n].y = 0.0;
    out[n].delta = -1;
    n++;
  }
  return n;
}
int32_t gesture_on_down(gesture_recognizer_t *g, const gesture_sample_t *s,
                        gesture_decision_t *out) {
  (void)out;
  pointers_set(g, s);
  if (g->count >= 2) {
    /* 1->2 (or 0->2): abort any pending 1-pointer gesture SILENTLY. */
    g->phase = GESTURE_PHASE_PINCHING;
    g->has_primary = 0;
    g->has_start = 0;
    g->start_spread = spread(g);
    g->pinch_accum = 1.0;
    return 0;
  }
  g->phase = GESTURE_PHASE_PRESS1;
  g->primary_id = s->pointer_id;
  g->has_primary = 1;
  g->start = *s;
  g->has_start = 1;
  g->move_ndc = px_to_ndc((double)GESTURE_MOVE_PX);
  return 0;
}
int32_t gesture_on_move(gesture_recognizer_t *g, const gesture_sample_t *s,
                        gesture_decision_t *out) {
  if (g->phase == GESTURE_PHASE_PINCHING) {
    if (pointer_index(g, s->pointer_id) >= 0)
      pointers_set(g, s);
    return ratchet(g, out);
  }
  if (!g->has_primary || s->pointer_id != g->primary_id || !g->has_start)
    return 0; /* orphan move */
  if (g->phase == GESTURE_PHASE_PRESS1) {
    if (gesture_ndc_dist(g->start.x, g->start.y, s->x, s->y) > g->move_ndc) {
      g->phase = GESTURE_PHASE_PANNING;
      out[0].kind = GESTURE_KIND_PAN_MOVE;
      out[0].x = s->x;
      out[0].y = s->y;
      out[0].delta = 0;
      return 1;
    }
    return 0; /* under threshold, remain in press1 */
  }
  /* panning */
  out[0].kind = GESTURE_KIND_PAN_MOVE;
  out[0].x = s->x;
  out[0].y = s->y;
  out[0].delta = 0;
  return 1;
}
/* classifyRelease: a release that never crossed movePx -> tap | track. */
static int32_t classify_release(const gesture_recognizer_t *g,
                                const gesture_sample_t *s,
                                gesture_decision_t *out) {
  /* UNSIGNED delta on purpose. t_ms carries the host's W3C event.timeStamp
   * (ms since navigation start) narrowed to int32 by the shell, so it wraps
   * after ~24.8 days of session uptime. Subtracting two int32 values that
   * straddle that wrap is SIGNED OVERFLOW — undefined behaviour, which -O2 is
   * entitled to assume cannot happen. Computed in uint32 the wraparound is
   * well-defined and yields the correct elapsed ms for any gap under 2^31,
   * which is every gap a double-tap window could care about. */
  if (g->has_last_tap &&
      (double)((uint32_t)s->t_ms - (uint32_t)g->last_tap_t) <=
          (double)GESTURE_DOUBLE_TAP_MS &&
      gesture_ndc_dist(g->last_tap_x, g->last_tap_y, s->x, s->y) <=
          px_to_ndc((double)GESTURE_DOUBLE_TAP_PX)) {
    out[0].kind = GESTURE_KIND_TRACK;
  } else {
    out[0].kind = GESTURE_KIND_TAP;
  }
  out[0].x = s->x;
  out[0].y = s->y;
  out[0].delta = 0;
  return 1;
}
int32_t gesture_on_up(gesture_recognizer_t *g, const gesture_sample_t *s,
                      gesture_decision_t *out) {
  if (g->phase == GESTURE_PHASE_PINCHING) {
    pointers_delete(g, s->pointer_id);
    if (g->count < 2) {
      g->phase = GESTURE_PHASE_IDLE;
      g->has_primary = 0;
      g->start_spread = 0.0;
    }
    return 0; /* 2->1 boundary never resumes pan, never emits a terminal */
  }
  pointers_delete(g, s->pointer_id);
  if (!g->has_primary || s->pointer_id != g->primary_id || !g->has_start)
    return 0; /* orphan up */
  gesture_phase_t phase = g->phase;
  g->has_primary = 0;
  g->has_start = 0;
  g->phase = GESTURE_PHASE_IDLE;
  if (phase == GESTURE_PHASE_PANNING) {
    g->has_last_tap = 0;
    out[0].kind = GESTURE_KIND_PAN_END;
    out[0].x = s->x;
    out[0].y = s->y;
    out[0].delta = 0;
    return 1;
  }
  /* press1 (never crossed movePx): tap | track */
  int32_t n = classify_release(g, s, out);
  if (out[0].kind == GESTURE_KIND_TAP) {
    g->has_last_tap = 1;
    g->last_tap_x = s->x;
    g->last_tap_y = s->y;
    g->last_tap_t = s->t_ms;
  } else {
    g->has_last_tap = 0;
  }
  return n;
}
int32_t gesture_on_cancel(gesture_recognizer_t *g, const gesture_sample_t *s,
                          gesture_decision_t *out) {
  (void)out;
  /* Drop the pointer from the live set, then SILENTLY abort whatever it was
   * driving — no terminal, no last_tap seed. This is the 2->1 pinch-drop path
   * generalized: a pinch losing a finger falls to idle (never resumes pan with
   * the survivor); a press1/panning pointer being cancelled emits nothing. */
  pointers_delete(g, s->pointer_id);
  if (g->phase == GESTURE_PHASE_PINCHING) {
    if (g->count < 2) {
      g->phase = GESTURE_PHASE_IDLE;
      g->has_primary = 0;
      g->start_spread = 0.0;
    }
    return 0;
  }
  /* press1 / panning: only the primary's cancel ends the gesture; a
   * non-primary cancel just leaves the live set (mirrors the orphan guard). */
  if (g->has_primary && s->pointer_id == g->primary_id) {
    g->phase = GESTURE_PHASE_IDLE;
    g->has_primary = 0;
    g->has_start = 0;
  }
  return 0;
}
int32_t gesture_on_wheel(gesture_recognizer_t *g, double delta_y,
                         gesture_decision_t *out) {
  (void)g;
  out[0].kind = GESTURE_KIND_WHEEL;
  out[0].x = 0.0;
  out[0].y = 0.0;
  out[0].delta = delta_y < 0.0 ? 1 : -1;
  return 1;
}
