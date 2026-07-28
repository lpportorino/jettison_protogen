/* Draw-stream palette observer — see palette_observer.h.
 *
 * evaluate_cb is deliberately observation-only.  In particular, it must not
 * call lv_draw_add_task/lv_draw_finalize_task_creation: a task created while
 * LVGL is evaluating another task can be rendered inline and silently violate
 * the task state machine without moving a golden pixel.
 */
#include "palette_observer.h"
#include "lvgl/src/core/lv_obj_style.h"
#include "lvgl/src/draw/lv_draw_arc.h"
#include "lvgl/src/draw/lv_draw_label.h"
#include "lvgl/src/draw/lv_draw_line.h"
#include "lvgl/src/draw/lv_draw_private.h"
#include "lvgl/src/draw/lv_draw_rect.h"
#include "lvgl/src/draw/lv_draw_triangle.h"
#include "theme.h"
#include <stddef.h>
#include <string.h>
/* Measured after implementation over every shipped card in both modes.  The
 * capacity is intentionally a hard bound: overflow is exported into the dump
 * and becomes :cantTell, never a silently partial clean result. */
#define PALETTE_OBSERVATION_CAPACITY 4096u
static palette_observation_t observations[PALETTE_OBSERVATION_CAPACITY];
static uint32_t observation_count;
static bool observation_overflowed;
static lv_draw_unit_t *observer_unit;
static uint32_t color_rgb(lv_color_t color) {
  return lv_color_to_u32(color) & 0x00FFFFFFu;
}
static lv_color32_t effective_recolor(const lv_draw_dsc_base_t *base) {
  lv_color32_t recolor = lv_color32_make(0, 0, 0, LV_OPA_TRANSP);
  if (base == NULL || base->obj == NULL)
    return recolor;
  if (base->layer != NULL) {
    recolor = base->layer->recolor;
    if (base->part != LV_PART_MAIN)
      recolor =
          lv_obj_style_apply_recolor(base->obj, (lv_part_t)base->part, recolor);
  } else {
    recolor =
        lv_obj_get_style_recolor_recursive(base->obj, (lv_part_t)base->part);
  }
  return recolor;
}
static bool same_task_key(const palette_observation_t *a,
                          const palette_observation_t *b) {
  return a->task_type == b->task_type && a->object == b->object &&
         a->x1 == b->x1 && a->y1 == b->y1 && a->x2 == b->x2 && a->y2 == b->y2;
}
static bool same_color_member(const palette_observation_t *a,
                              const palette_observation_t *b) {
  return a->rgb == b->rgb && a->role == b->role &&
         a->theme_recolor == b->theme_recolor;
}
static void observe_color(lv_draw_task_t *task, const lv_draw_dsc_base_t *base,
                          lv_color_t color, palette_observer_role_t role) {
  if (task == NULL || base == NULL)
    return;
  const lv_color32_t recolor = effective_recolor(base);
  palette_observation_t next = {
      .rgb = color_rgb(color),
      .recolor_rgb = ((uint32_t)recolor.red << 16) |
                     ((uint32_t)recolor.green << 8) | (uint32_t)recolor.blue,
      .object = (uint32_t)(uintptr_t)base->obj,
      .part = base->part,
      .task_type = (uint32_t)task->type,
      .role = (uint32_t)role,
      .x1 = task->area.x1,
      .y1 = task->area.y1,
      .x2 = task->area.x2,
      .y2 = task->area.y2,
      .recolor_opa = recolor.alpha,
      .theme_recolor = asgard_theme_recolor_is_declared(recolor),
  };
  /* Merge repeated PARTIAL-strip tasks by key, retaining the SET of colours
   * carried by that task instead of summing location-dependent repeats. */
  for (uint32_t i = 0; i < observation_count; i++) {
    if (same_task_key(&observations[i], &next) &&
        same_color_member(&observations[i], &next))
      return;
  }
  if (observation_count == PALETTE_OBSERVATION_CAPACITY) {
    observation_overflowed = true;
    return;
  }
  observations[observation_count++] = next;
}
static void observe_gradient(lv_draw_task_t *task,
                             const lv_draw_dsc_base_t *base,
                             const lv_grad_dsc_t *gradient) {
  if (gradient == NULL || gradient->dir == LV_GRAD_DIR_NONE)
    return;
  for (uint32_t i = 0; i < gradient->stops_count; i++) {
    if (gradient->stops[i].opa > LV_OPA_MIN)
      observe_color(task, base, gradient->stops[i].color,
                    PALETTE_ROLE_GRADIENT);
  }
}
static int32_t evaluate_cb(lv_draw_unit_t *draw_unit, lv_draw_task_t *task) {
  (void)draw_unit;
  if (task == NULL || task->draw_dsc == NULL)
    return 0;
  switch (task->type) {
  case LV_DRAW_TASK_TYPE_FILL: {
    const lv_draw_fill_dsc_t *dsc = task->draw_dsc;
    if (dsc->opa <= LV_OPA_MIN)
      break;
    if (dsc->grad.dir == LV_GRAD_DIR_NONE)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    else
      observe_gradient(task, &dsc->base, &dsc->grad);
    break;
  }
  case LV_DRAW_TASK_TYPE_BORDER: {
    const lv_draw_border_dsc_t *dsc = task->draw_dsc;
    if (dsc->opa > LV_OPA_MIN && dsc->width > 0)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    break;
  }
  case LV_DRAW_TASK_TYPE_BOX_SHADOW: {
    const lv_draw_box_shadow_dsc_t *dsc = task->draw_dsc;
    if (dsc->opa > LV_OPA_MIN && dsc->width > 0)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    break;
  }
  case LV_DRAW_TASK_TYPE_LETTER: {
    const lv_draw_letter_dsc_t *dsc = task->draw_dsc;
    if (dsc->opa > LV_OPA_MIN)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    if (dsc->outline_stroke_opa > LV_OPA_MIN && dsc->outline_stroke_width > 0)
      observe_color(task, &dsc->base, dsc->outline_stroke_color,
                    PALETTE_ROLE_OUTLINE);
    break;
  }
  case LV_DRAW_TASK_TYPE_LABEL: {
    const lv_draw_label_dsc_t *dsc = task->draw_dsc;
    if (dsc->text == NULL || dsc->text[0] == '\0' || dsc->opa <= LV_OPA_MIN)
      break;
    observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    if (dsc->sel_start != LV_DRAW_LABEL_NO_TXT_SEL &&
        dsc->sel_end != LV_DRAW_LABEL_NO_TXT_SEL &&
        dsc->sel_start < dsc->sel_end) {
      observe_color(task, &dsc->base, dsc->sel_color,
                    PALETTE_ROLE_SELECTION_TEXT);
      observe_color(task, &dsc->base, dsc->sel_bg_color,
                    PALETTE_ROLE_SELECTION_BG);
    }
    if (dsc->outline_stroke_opa > LV_OPA_MIN && dsc->outline_stroke_width > 0)
      observe_color(task, &dsc->base, dsc->outline_stroke_color,
                    PALETTE_ROLE_OUTLINE);
    break;
  }
  case LV_DRAW_TASK_TYPE_LINE: {
    const lv_draw_line_dsc_t *dsc = task->draw_dsc;
    if (dsc->opa > LV_OPA_MIN && dsc->width > 0)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    break;
  }
  case LV_DRAW_TASK_TYPE_ARC: {
    const lv_draw_arc_dsc_t *dsc = task->draw_dsc;
    if (dsc->img_src == NULL && dsc->opa > LV_OPA_MIN && dsc->width > 0)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    break;
  }
  case LV_DRAW_TASK_TYPE_TRIANGLE: {
    const lv_draw_triangle_dsc_t *dsc = task->draw_dsc;
    if (dsc->opa <= LV_OPA_MIN)
      break;
    if (dsc->grad.dir == LV_GRAD_DIR_NONE)
      observe_color(task, &dsc->base, dsc->color, PALETTE_ROLE_COLOR);
    else
      observe_gradient(task, &dsc->base, &dsc->grad);
    break;
  }
  default:
    /* IMAGE/LAYER/MASK/BLUR do not expose a scalar painted `color` field.
     * Asset pixels and post-blend framebuffer colours are deliberately not
     * reclassified as descriptor-declared palette colours. */
    break;
  }
  return 0;
}
static int32_t dispatch_cb(lv_draw_unit_t *draw_unit, lv_layer_t *layer) {
  (void)draw_unit;
  (void)layer;
  return LV_DRAW_UNIT_IDLE;
}
void palette_observer_init(void) {
  /* Clear FIRST, and unconditionally: controls_destroy does not lv_deinit, so
   * a destroy/re-init cycle reaches the registration guard below and would
   * otherwise carry the previous session's records into the new one. */
  palette_observer_clear();
  /* Register exactly once per module instance.  lv_init is re-entrant-guarded
   * (`lv_initialized`, lv_init.c) and nothing here calls lv_deinit, so the
   * unit list survives controls_destroy and re-registering would append a
   * duplicate observer to it. */
  if (observer_unit != NULL)
    return;
  observer_unit = lv_draw_create_unit(sizeof(lv_draw_unit_t));
  observer_unit->name = "palette-observer";
  observer_unit->evaluate_cb = evaluate_cb;
  observer_unit->dispatch_cb = dispatch_cb;
}
void palette_observer_clear(void) {
  observation_count = 0;
  observation_overflowed = false;
  memset(observations, 0, sizeof(observations));
}
uint32_t palette_observer_count(void) { return observation_count; }
bool palette_observer_overflowed(void) { return observation_overflowed; }
const palette_observation_t *palette_observer_records(void) {
  return observations;
}
