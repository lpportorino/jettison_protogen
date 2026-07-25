#ifndef LOG_H
#define LOG_H
/**
 * Structured logging macros for the LVGL controls renderer.
 * Outputs to stderr with severity level and source file.
 *
 * Line numbers are deliberately omitted. __LINE__ bakes every LOG site's line
 * number into the wasm as a constant, so any line-shifting edit — a comment, a
 * blank line, a reformat — rewrites the ~3.6 MB binary with zero behavioural
 * change and moves it out of step with every consumer's pin. __FILE__ is kept:
 * it names the source and is invariant under such edits.
 */
#include <stdio.h>
/* fprintf return is intentionally discarded (stderr logging) — the (void) cast
   satisfies cert-err33-c's unchecked-return check. */
#define LOG_ERROR(fmt, ...)                                                    \
  (void)fprintf(stderr, "[ERROR] %s: " fmt "\n", __FILE__, ##__VA_ARGS__)
#define LOG_WARN(fmt, ...)                                                     \
  (void)fprintf(stderr, "[WARN]  %s: " fmt "\n", __FILE__, ##__VA_ARGS__)
#define LOG_INFO(fmt, ...)                                                     \
  (void)fprintf(stderr, "[INFO]  " fmt "\n", ##__VA_ARGS__)
#endif
/* LOG_H */
