#!/usr/bin/env bash
# Sourced by the renderer battery's oracle drivers (coverage_matrix/run.sh,
# tools/demo-parity.sh) AFTER they set ROOT to the renderer tree root.
# Root-anchored (adapted from the private source repo's battery driver): no
# container-name proxy, no private workspace-path constant — the battery
# assumes it ALREADY runs inside the toolchain container (renderer.mk header:
# locally `docker exec` into it; in CI the Dockerfile.base image), so a
# host invocation fails loudly here instead of running host toolchains.
#
# Provides (all paths derived from the caller's ROOT):
#   WS                        the renderer root (kept under the historical
#                             name — the drivers reference $WS for harness
#                             --wasm/--wasi-root/--pb paths)
#   dev   <cmd...>            run <cmd> from the renderer root
#   dev_w <subdir> <cmd...>   run <cmd> from <renderer-root>/<subdir>
#   rgen  <cmd...>            run <cmd> from <root>/tools/renderer-gen — the
#                             codegen seam's own project dir (clojure -M:...
#                             aliases + CWD-relative edn/ + assets/ symlink)
# Redirections/`||` on the call site apply to the whole invocation.

if [ ! -f /.dockerenv ]; then
  echo "in-container.sh: not inside a container — run the renderer battery" >&2
  echo "  in the toolchain container (docker exec) or the CI image." >&2
  exit 1
fi

[ -n "${ROOT:-}" ] || {
  echo "in-container.sh: caller must set ROOT (the renderer tree root) before sourcing" >&2
  exit 1
}

WS="$ROOT"
RGEN="$(cd "$ROOT/../tools/renderer-gen" && pwd)"

dev() { (cd "$WS" && "$@"); }
dev_w() {
  local d=$1
  shift
  (cd "$WS/$d" && "$@")
}
rgen() { (cd "$RGEN" && "$@"); }
