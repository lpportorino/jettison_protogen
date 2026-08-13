/**
 * SVG image decoder for LVGL using ThorVG.
 *
 * Registers a custom LVGL image decoder that handles .svg files.
 * Uses ThorVG's C API (compiled internally via LV_USE_THORVG_INTERNAL)
 * to parse SVG and render to an ARGB8888 draw buffer.
 *
 * Pattern follows lv_lodepng.c: info_cb / open_cb / close_cb + caching.
 */
#include "svg_decoder.h"
#include "log.h"
#include "lvgl.h"
#include "lvgl/src/draw/lv_image_decoder_private.h"
#if LV_USE_THORVG_INTERNAL
#include "lvgl/src/libs/thorvg/thorvg_capi.h"
#include <stdlib.h>
#include <string.h>
#define DECODER_NAME "SVG"
/* Maximum SVG file size we'll attempt to read (256 KB). */
#define MAX_SVG_SIZE (256 * 1024)
/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */
/**
 * Read entire file contents via LVGL VFS into a malloc'd buffer.
 * Caller must free the returned buffer. Returns NULL on failure.
 */
static uint8_t *read_file_contents(const char *path, uint32_t *out_size) {
  lv_fs_file_t f;
  if (lv_fs_open(&f, path, LV_FS_MODE_RD) != LV_FS_RES_OK)
    return NULL;
  /* Seek to end to get file size */
  if (lv_fs_seek(&f, 0, LV_FS_SEEK_END) != LV_FS_RES_OK) {
    lv_fs_close(&f);
    return NULL;
  }
  uint32_t pos = 0;
  if (lv_fs_tell(&f, &pos) != LV_FS_RES_OK || pos == 0 || pos > MAX_SVG_SIZE) {
    lv_fs_close(&f);
    return NULL;
  }
  uint32_t file_size = pos;
  /* Seek back to start and read */
  if (lv_fs_seek(&f, 0, LV_FS_SEEK_SET) != LV_FS_RES_OK) {
    lv_fs_close(&f);
    return NULL;
  }
  uint8_t *buf = lv_malloc(file_size + 1);
  if (!buf) {
    lv_fs_close(&f);
    return NULL;
  }
  uint32_t bytes_read = 0;
  lv_fs_res_t res = lv_fs_read(&f, buf, file_size, &bytes_read);
  lv_fs_close(&f);
  if (res != LV_FS_RES_OK || bytes_read != file_size) {
    lv_free(buf);
    return NULL;
  }
  buf[file_size] = '\0'; /* NUL-terminate for text parsing */
  *out_size = file_size;
  return buf;
}
/* ------------------------------------------------------------------ */
/* Decoder callbacks                                                   */
/* ------------------------------------------------------------------ */
/**
 * Get SVG image info (dimensions).
 *
 * The framework opens the file via LVGL VFS before calling this callback
 * and provides dsc->file for reading. We check the .svg extension, then
 * read the file and parse with ThorVG to get the SVG viewport size.
 */
static lv_result_t decoder_info(lv_image_decoder_t *decoder,
                                lv_image_decoder_dsc_t *dsc,
                                lv_image_header_t *header) {
  LV_UNUSED(decoder);
  if (dsc->src_type != LV_IMAGE_SRC_FILE)
    return LV_RESULT_INVALID;
  const char *fn = dsc->src;
  if (lv_strcmp(lv_fs_get_ext(fn), "svg") != 0)
    return LV_RESULT_INVALID;
  /* Read file content via the already-opened file handle. Heap-allocated:
   * a MAX_SVG_SIZE stack buffer overflows the wasm shadow stack (default
   * 64 KiB) and traps on function ENTRY — caught by the harness's revived
   * SVG tests. */
  uint8_t *buf = lv_malloc(MAX_SVG_SIZE);
  if (!buf)
    return LV_RESULT_INVALID;
  uint32_t bytes_read = 0;
  lv_fs_res_t fs_res = lv_fs_read(&dsc->file, buf, MAX_SVG_SIZE, &bytes_read);
  if (fs_res != LV_FS_RES_OK || bytes_read == 0) {
    lv_free(buf);
    return LV_RESULT_INVALID;
  }
  /* Parse SVG with ThorVG to get dimensions */
  Tvg_Paint *picture = tvg_picture_new();
  if (!picture) {
    lv_free(buf);
    return LV_RESULT_INVALID;
  }
  Tvg_Result res = tvg_picture_load_data(picture, (const char *)buf, bytes_read,
                                         "svg+xml", true);
  lv_free(buf);
  if (res != TVG_RESULT_SUCCESS) {
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  float w_f = 0, h_f = 0;
  res = tvg_picture_get_size(picture, &w_f, &h_f);
  tvg_paint_del(picture);
  if (res != TVG_RESULT_SUCCESS || w_f <= 0 || h_f <= 0)
    return LV_RESULT_INVALID;
  header->cf = LV_COLOR_FORMAT_ARGB8888;
  header->w = (int32_t)w_f;
  header->h = (int32_t)h_f;
  return LV_RESULT_OK;
}
/**
 * Open and decode an SVG image to an RGBA draw buffer.
 *
 * The file is NOT open at this point (framework closes it after info_cb).
 * We re-read the file via LVGL VFS, parse with ThorVG, render to a
 * software canvas, and store the result in dsc->decoded.
 */
static lv_result_t decoder_open(lv_image_decoder_t *decoder,
                                lv_image_decoder_dsc_t *dsc) {
  LV_UNUSED(decoder);
  if (dsc->src_type != LV_IMAGE_SRC_FILE)
    return LV_RESULT_INVALID;
  const char *fn = dsc->src;
  if (lv_strcmp(lv_fs_get_ext(fn), "svg") != 0)
    return LV_RESULT_INVALID;
  /* Read SVG file */
  uint32_t file_size = 0;
  uint8_t *svg_data = read_file_contents(fn, &file_size);
  if (!svg_data) {
    LOG_ERROR("SVG: failed to read file: %s", fn);
    return LV_RESULT_INVALID;
  }
  /* Parse SVG */
  Tvg_Paint *picture = tvg_picture_new();
  if (!picture) {
    lv_free(svg_data);
    return LV_RESULT_INVALID;
  }
  Tvg_Result res = tvg_picture_load_data(picture, (const char *)svg_data,
                                         file_size, "svg+xml", true);
  lv_free(svg_data);
  if (res != TVG_RESULT_SUCCESS) {
    LOG_ERROR("SVG: ThorVG parse failed for %s (err=%d)", fn, (int)res);
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  /* Get SVG dimensions */
  float w_f = 0, h_f = 0;
  res = tvg_picture_get_size(picture, &w_f, &h_f);
  if (res != TVG_RESULT_SUCCESS || w_f <= 0 || h_f <= 0) {
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  uint32_t w = (uint32_t)w_f;
  uint32_t h = (uint32_t)h_f;
  /* Create LVGL draw buffer */
  lv_draw_buf_t *decoded =
      lv_draw_buf_create(w, h, LV_COLOR_FORMAT_ARGB8888, LV_STRIDE_AUTO);
  if (!decoded) {
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  /* Clear buffer to transparent */
  lv_memzero(decoded->data, decoded->data_size);
  /* Create ThorVG canvas and render */
  Tvg_Canvas *canvas = tvg_swcanvas_create();
  if (!canvas) {
    lv_draw_buf_destroy(decoded);
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  uint32_t stride = decoded->header.stride / 4; /* stride in pixels */
  res = tvg_swcanvas_set_target(canvas, (uint32_t *)decoded->data, stride, w, h,
                                TVG_COLORSPACE_ARGB8888);
  if (res != TVG_RESULT_SUCCESS) {
    tvg_canvas_destroy(canvas);
    lv_draw_buf_destroy(decoded);
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  /* Push transfers ownership — do NOT tvg_paint_del(picture) after this */
  res = tvg_canvas_push(canvas, picture);
  if (res != TVG_RESULT_SUCCESS) {
    tvg_canvas_destroy(canvas);
    lv_draw_buf_destroy(decoded);
    tvg_paint_del(picture);
    return LV_RESULT_INVALID;
  }
  /* Render */
  tvg_canvas_update(canvas);
  tvg_canvas_draw(canvas);
  tvg_canvas_sync(canvas);
  tvg_canvas_destroy(canvas);
  /* ThorVG ARGB8888 on little-endian is [B,G,R,A] in memory, matching
   * LVGL's LV_COLOR_FORMAT_ARGB8888 — no channel swap needed.
   * (Unlike lodepng which stores PNG's [R,G,B,A] and needs R↔B swap.) */
  /* Post-process (stride alignment, premultiply, etc.) */
  lv_draw_buf_t *adjusted = lv_image_decoder_post_process(dsc, decoded);
  if (!adjusted) {
    lv_draw_buf_destroy(decoded);
    return LV_RESULT_INVALID;
  }
  if (adjusted != decoded) {
    lv_draw_buf_destroy(decoded);
    decoded = adjusted;
  }
  dsc->decoded = decoded;
  /* Cache the decoded image */
  if (!dsc->args.no_cache && lv_image_cache_is_enabled()) {
    lv_image_cache_data_t search_key;
    search_key.src_type = dsc->src_type;
    search_key.src = dsc->src;
    search_key.slot.size = decoded->data_size;
    lv_cache_entry_t *entry =
        lv_image_decoder_add_to_cache(decoder, &search_key, decoded, NULL);
    if (entry)
      dsc->cache_entry = entry;
  }
  return LV_RESULT_OK;
}
/**
 * Close SVG decoder session and free resources.
 */
static void decoder_close(lv_image_decoder_t *decoder,
                          lv_image_decoder_dsc_t *dsc) {
  LV_UNUSED(decoder);
  if (dsc->args.no_cache || !lv_image_cache_is_enabled())
    lv_draw_buf_destroy((lv_draw_buf_t *)dsc->decoded);
}
/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */
/* NEITHER INIT RESULT IS CHECKED, and the two halves are NOT equivalent — read
 * which is which before "fixing" them together.
 *
 * `lv_image_decoder_create` needs no check: `dec->name` below dereferences it
 * IN PLACE, so a NULL return traps here rather than surviving into a later
 * call. Under wasm a null-deref traps loudly and reddens every lane, so the
 * fail-fast property already holds without a branch.
 *
 * `tvg_engine_init` is the weaker one and is a genuine gap: its result is
 * discarded and nothing dereferences anything, so a failure surfaces only as
 * SVG content that does not draw. Nothing here would say why. It is left
 * unchecked deliberately rather than by oversight — an init failure on this
 * target means the build is wrong, not the input — but a reader who assumes
 * the trap above covers both halves is assuming too much. */
void svg_decoder_init(void) {
  /* Initialize ThorVG software engine (single-threaded for WASM) */
  tvg_engine_init(TVG_ENGINE_SW, 0);
  lv_image_decoder_t *dec = lv_image_decoder_create();
  lv_image_decoder_set_info_cb(dec, decoder_info);
  lv_image_decoder_set_open_cb(dec, decoder_open);
  lv_image_decoder_set_close_cb(dec, decoder_close);
  dec->name = DECODER_NAME;
}
void svg_decoder_deinit(void) {
  lv_image_decoder_t *dec = NULL;
  while ((dec = lv_image_decoder_get_next(dec)) != NULL) {
    if (dec->info_cb == decoder_info) {
      lv_image_decoder_delete(dec);
      break;
    }
  }
  tvg_engine_term(TVG_ENGINE_SW);
}
#endif
/* LV_USE_THORVG_INTERNAL */
