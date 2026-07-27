/**
 * LVGL Configuration for WASM Controls Module
 *
 * LVGL 9.5.x — configured for transparent overlay rendering
 * in a WebAssembly (wasi-sdk) environment.
 */
#ifndef LV_CONF_H
#define LV_CONF_H
/* Color depth: 32-bit ARGB for transparent overlay */
#define LV_COLOR_DEPTH 32
/* No OS — single-threaded WASM */
#define LV_USE_OS LV_OS_NONE
/* Use wasi-libc malloc/free */
#define LV_USE_STDLIB_MALLOC LV_STDLIB_CLIB
/* LVGL internal memory pool size */
#define LV_MEM_SIZE (2 * 1024 * 1024)
/* Reference DPI (host can override via subject) */
#define LV_DPI_DEF 160
/* Logging — routes to WASI stdout, captured by host */
#define LV_USE_LOG 1
#define LV_LOG_PRINTF 1
/* Observer/subject support (core feature since 9.0) */
#define LV_USE_OBSERVER 1
/* Layouts */
#define LV_USE_FLEX 1
#define LV_USE_GRID 1
/* Widgets */
#define LV_USE_BUTTON 1
#define LV_USE_LABEL 1
#define LV_USE_IMAGE 1
#define LV_USE_SLIDER 1
#define LV_USE_ARC 1
#define LV_USE_BAR 1
#define LV_USE_SWITCH 1
#define LV_USE_CHECKBOX 1
#define LV_USE_DROPDOWN 1
#define LV_USE_ROLLER 1
#define LV_USE_TEXTAREA 1
#define LV_USE_SPINBOX 1
#define LV_USE_SPINNER 1
#define LV_USE_LED 1
#define LV_USE_LINE 1
#define LV_USE_SCALE 1
#define LV_USE_BUTTONMATRIX 1
#define LV_USE_TABLE 1
#define LV_USE_TABVIEW 1
#define LV_USE_CHART 1
/* Filesystem — POSIX for WASI compatibility (binfont, SVG loading) */
#define LV_USE_FS_POSIX 1
#if LV_USE_FS_POSIX
#define LV_FS_POSIX_LETTER 'P'
#define LV_FS_POSIX_PATH ""
#define LV_FS_POSIX_CACHE_SIZE 0
#endif
/* PNG decode (P:images/ PNGs via the WASI filesystem) */
#define LV_USE_LODEPNG 1
/* TTF fonts rasterized at runtime from P:fonts/ (stb_truetype-based;
 * FILE_SUPPORT routes reads through lv_fs → the WASI P: drive). The
 * canonical asset-font path — arbitrary sizes from one .ttf, no
 * conversion toolchain. */
#define LV_USE_TINY_TTF 1
#define LV_TINY_TTF_FILE_SUPPORT 1
/* Max-quality draw settings (the rest of the quality surface is 9.5
 * defaults, which are already maximal: software AA is always on per
 * draw in v9 — pinned by the vr_aa_probe test — and font glyphs render
 * 4bpp antialiased; subpixel text is no longer a v9 conf knob). */
#define LV_USE_DRAW_SW_COMPLEX_GRADIENTS 1
/* radial/conic gradients */
#define LV_GRADIENT_MAX_STOPS 8
/* smooth multi-stop ramps */
/* Float + matrix support (required by vector graphics) */
#define LV_USE_FLOAT 1
#define LV_USE_MATRIX 1
/* Vector graphics + ThorVG (internal, from lvgl/src/libs/thorvg/) */
#define LV_USE_VECTOR_GRAPHIC 1
#define LV_USE_THORVG_INTERNAL 1
#define LV_USE_THORVG_EXTERNAL 0
/* The default theme is (re)initialized at runtime via
 * lv_theme_default_init() — see main.c apply_default_theme(): the
 * dark flag tracks the theme_dark subject so bare containers and
 * built-in widget chrome flip with the theme, and the theme font is
 * pinned to Montserrat 16 (lv_demo_widgets' DISP_LARGE font_normal —
 * demo parity). LV_FONT_DEFAULT stays as Montserrat 14 for LVGL
 * internals; our widgets always get explicit font styles from the
 * codegen pipeline. */
/* Built-in Montserrat fonts (fallbacks + the set lv_demo_widgets picks
 * across display sizes — the demo-parity oracle needs them identical
 * on both differential paths) */
#define LV_FONT_MONTSERRAT_12 1
#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_MONTSERRAT_16 1
#define LV_FONT_MONTSERRAT_18 1
#define LV_FONT_MONTSERRAT_20 1
#define LV_FONT_MONTSERRAT_22 1
#define LV_FONT_MONTSERRAT_24 1
/* lv_demo_widgets — compiled into reference.wasm ONLY (wasm.mk object
 * lists; the flag just un-guards the vendored demo sources). */
#define LV_USE_DEMO_WIDGETS 1
/* Disable unused features */
#define LV_USE_PERF_MONITOR 0
#define LV_USE_MEM_MONITOR 0
#define LV_USE_FONT_COMPRESSED 0
/* NOTE: LVGL 9 has no LV_USE_ANIMATION config — lv_anim is unconditional
 * core (the v8-era define below is a NO-OP kept only so a grep for it finds
 * this note). What time-drives the theme's 80 ms press transition is
 * LV_THEME_DEFAULT_TRANSITION_TIME (not overridden here → the vendored
 * default 80) + the accumulated controls_tick(ms) clock; `bare` widgets
 * strip theme styles and with them the transition (wasm_harness anim_clock
 * pins the themed-path behavior). */
#define LV_USE_ANIMATION 1
/* Draw engine — software (no GPU in WASM) */
#define LV_USE_DRAW_SW 1
#endif
/* LV_CONF_H */
