/**
 * Stub setjmp/longjmp for WASM (wasi-sdk).
 *
 * ThorVG's tvgSwRle.cpp uses setjmp/longjmp for rasterizer error recovery
 * (cell overflow). WASI doesn't support real setjmp/longjmp without the
 * WASM exception handling proposal. These stubs make setjmp always return 0
 * (normal path) and longjmp abort.
 */
#include <stdlib.h>
/* Match wasi-libc's jmp_buf typedef (array of 1 int) */
typedef int wasm_jmp_buf[1];
int
/* NOLINTNEXTLINE(readability-non-const-parameter) — must match setjmp(3) */
setjmp(wasm_jmp_buf env) {
  (void)env;
  return 0;
}
_Noreturn void
/* NOLINTNEXTLINE(readability-non-const-parameter) — must match longjmp(3) */
longjmp(wasm_jmp_buf env, int val) {
  (void)env;
  (void)val;
  abort();
}
