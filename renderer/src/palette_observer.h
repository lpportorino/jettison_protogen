/* Read-only LVGL draw-stream colour observer.
 *
 * The observer records descriptor-declared colours when draw tasks are
 * evaluated.  It never creates, takes, dispatches, or finishes a task.
 * PARTIAL rendering can recreate the same task for every strip it touches, so
 * records are merged by (task type, object, area) with a set of colours rather
 * than counted as independent observations.
 */
#ifndef PALETTE_OBSERVER_H
#define PALETTE_OBSERVER_H
#include "lvgl.h"
#include <stdbool.h>
#include <stdint.h>
typedef enum {
  PALETTE_ROLE_COLOR = 0,
  PALETTE_ROLE_GRADIENT = 1,
  PALETTE_ROLE_SELECTION_TEXT = 2,
  PALETTE_ROLE_SELECTION_BG = 3,
  PALETTE_ROLE_OUTLINE = 4,
} palette_observer_role_t;
typedef struct {
  uint32_t rgb;
  uint32_t recolor_rgb;
  uint32_t object;
  uint32_t part;
  uint32_t task_type;
  uint32_t role;
  int32_t x1;
  int32_t y1;
  int32_t x2;
  int32_t y2;
  uint32_t recolor_opa;
  bool theme_recolor;
} palette_observation_t;
void palette_observer_init(void);
void palette_observer_clear(void);
uint32_t palette_observer_count(void);
bool palette_observer_overflowed(void);
const palette_observation_t *palette_observer_records(void);
#endif /* PALETTE_OBSERVER_H */
