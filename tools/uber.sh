#!/usr/bin/env bash
# tools/uber.sh — run a command inside the pinned toolchain ("uber") container,
# robustly, on any host. See .claude/rules/uber-container.md for the why.
#
# The uber image (Dockerfile.base) carries every pinned toolchain — WASI-SDK
# clang for the wasm build, GraalVM Community JDK 25 + Clojure for the devcards
# render/gallery + docs, protoc + plugins for bindings — so locally you can run
# ANYTHING in one container and match CI's per-tool versions.
#
# Usage:
#   tools/uber.sh <command...>   # run <command> at the repo root, in-container
#   tools/uber.sh --build        # (re)build the image only
#   tools/uber.sh --check        # build AND verify the image can execute
#
# Arch detection: docker build+run are pinned to the host's native platform.
# On some hosts docker/buildkit defaults to a different platform and silently
# cross-builds, yielding an image whose binaries "cannot execute" — forcing the
# native --platform sidesteps that. `--check` reports it loudly if it persists
# (a host builder problem to fix, not a repo one).
set -euo pipefail

IMG="jettison-proto-generator-base:latest"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$(uname -m)" in
  x86_64|amd64)  PLATFORM="linux/amd64" ;;
  aarch64|arm64) PLATFORM="linux/arm64" ;;
  *) echo "uber.sh: unsupported host arch $(uname -m)" >&2; exit 2 ;;
esac

build()    { echo "uber.sh: building $IMG for $PLATFORM ..." >&2
             docker build --platform "$PLATFORM" -t "$IMG" -f "$ROOT/Dockerfile.base" "$ROOT"; }
present()  { docker image inspect "$IMG" >/dev/null 2>&1; }
runnable() { present && docker run --rm --platform "$PLATFORM" "$IMG" true >/dev/null 2>&1; }

case "${1:-}" in
  --build) build; exit 0 ;;
  --check)
    build
    if runnable; then echo "uber.sh: OK — $IMG runs on $PLATFORM"; exit 0; fi
    echo "uber.sh: FAIL — $IMG built but its binaries cannot execute on $PLATFORM." >&2
    echo "  Your docker/buildkit is cross-building. Fix the host builder (e.g. a native" >&2
    echo "  'docker buildx' builder, or DOCKER_DEFAULT_PLATFORM=$PLATFORM) — repo-side is correct." >&2
    exit 1 ;;
  "") echo "usage: tools/uber.sh <command...> | --build | --check" >&2; exit 2 ;;
esac

present || build
exec docker run --rm --platform "$PLATFORM" \
  -v "$ROOT:/workspace" -w /workspace \
  -e CARGO_HOME=/workspace/.cargo-home \
  "$IMG" bash -lc "$*"
