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
 *
 * THE THREE DIFFER ONLY IN THE LEVEL TOKEN. Same layout, same __FILE__, same
 * absent __LINE__ — so moving a diagnostic between levels changes what the
 * module CLAIMS about the event and nothing else a reader parses. A level that
 * also dropped the source name would make every such move two changes, one of
 * them unannounced.
 */
#include <stdio.h>
/* fprintf return is intentionally discarded (stderr logging) — the (void) cast
   satisfies cert-err33-c's unchecked-return check. */
#define LOG_ERROR(fmt, ...)                                                    \
  (void)fprintf(stderr, "[ERROR] %s: " fmt "\n", __FILE__, ##__VA_ARGS__)
#define LOG_WARN(fmt, ...)                                                     \
  (void)fprintf(stderr, "[WARN]  %s: " fmt "\n", __FILE__, ##__VA_ARGS__)
#define LOG_INFO(fmt, ...)                                                     \
  (void)fprintf(stderr, "[INFO]  %s: " fmt "\n", __FILE__, ##__VA_ARGS__)
#endif
/* LOG_H */
