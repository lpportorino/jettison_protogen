/**
 * SVG image decoder for LVGL using ThorVG.
 *
 * Registers an LVGL image decoder that handles .svg files via ThorVG's
 * software rasterizer. Renders SVG to ARGB8888 draw buffers.
 */
#ifndef SVG_DECODER_H
#define SVG_DECODER_H
/**
 * Initialize the SVG decoder and ThorVG engine.
 * Call after lv_init().
 */
void svg_decoder_init(void);
/**
 * Deinitialize the SVG decoder and ThorVG engine.
 * Call before lv_deinit() or at shutdown.
 */
void svg_decoder_deinit(void);
#endif
/* SVG_DECODER_H */
