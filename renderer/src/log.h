#ifndef LOG_H
#define LOG_H
/**
 * Structured logging macros for the LVGL controls renderer.
 * Outputs to stderr with severity level and source location.
 */
#include <stdio.h>
/* fprintf return is intentionally discarded (stderr logging) — the (void) cast
   satisfies cert-err33-c's unchecked-return check. */
#define LOG_ERROR(fmt, ...)                                                    \
  (void)fprintf(stderr, "[ERROR] %s:%d: " fmt "\n", __FILE__, __LINE__,        \
                ##__VA_ARGS__)
#define LOG_WARN(fmt, ...)                                                     \
  (void)fprintf(stderr, "[WARN]  %s:%d: " fmt "\n", __FILE__, __LINE__,        \
                ##__VA_ARGS__)
#define LOG_INFO(fmt, ...)                                                     \
  (void)fprintf(stderr, "[INFO]  " fmt "\n", ##__VA_ARGS__)
#endif
/* LOG_H */
