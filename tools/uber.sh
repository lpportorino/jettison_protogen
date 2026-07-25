#!/usr/bin/env bash
# tools/uber.sh — run a command inside the pinned toolchain ("uber") container,
# robustly, on any host. See .claude/rules/uber-container.md for the why.
#
# The uber image (Dockerfile.base) carries every pinned toolchain — WASI-SDK
# clang for the wasm build, GraalVM Community JDK + Clojure for the devcards
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
runnable() { present && docker run --rm --platform "$PLATFORM" --entrypoint bash "$IMG" -c 'exit 0' >/dev/null 2>&1; }

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

# GIT INSIDE THE CONTAINER. When `.git` is a FILE rather than a directory — the
# gitfile family: a submodule (how the consumer fleet vendors this repo) or a
# linked `git worktree` — it names a gitdir OUTSIDE this bind mount. Mount only
# $ROOT and every in-container `git` call dies with "not a git repository", so
# any target that discovers its inputs from git's index silently sees ZERO
# files (lint.mk's LINT_SH_FILES is exactly that shape).
#
# Mount the real gitdir read-only and override GIT_DIR/GIT_WORK_TREE, because
# the gitdir's own `core.worktree` is a HOST-relative path that cannot resolve
# in here. `env -u` keeps a stray GIT_DIR in the caller's environment from
# redirecting resolution at an unrelated repository.
#
# ONLY a SELF-CONTAINED gitdir is mounted. A linked worktree's private gitdir
# holds no objects and no real refs (just HEAD/index/commondir), so mounting it
# alone yields a git that fails on every command; its common dir is referenced
# by a host path that would not resolve in-container either. Rather than
# half-support that shape, leave git unavailable and let lint-sh's non-vacuity
# guard report the true reason.
#
# Resolution failure is deliberately NOT fatal: git is a hard prerequisite of
# the lint-sh GATE, not of uber.sh itself, and that gate now fails loudly on
# its own. A checkout with a dangling gitlink must still be able to build the
# wasm, render devcards, and generate docs — none of which need git.
#
# A standalone checkout (CI) has a real `.git` DIRECTORY inside $ROOT already
# and needs none of this.
GIT_MOUNT=()
if [ -f "$ROOT/.git" ]; then
  GITDIR="$(env -u GIT_DIR -u GIT_WORK_TREE git -C "$ROOT" rev-parse --absolute-git-dir 2>/dev/null || true)"
  COMMONDIR="$(env -u GIT_DIR -u GIT_WORK_TREE git -C "$ROOT" rev-parse --git-common-dir 2>/dev/null || true)"
  if [ -n "$GITDIR" ] && [ -d "$GITDIR" ] && [ "$GITDIR" = "$COMMONDIR" ]; then
    GIT_MOUNT=(-v "$GITDIR:/gitdir:ro" -e GIT_DIR=/gitdir -e GIT_WORK_TREE=/workspace)
  fi
fi

# --entrypoint bash names the shell explicitly so the `-lc "$*"` script string is
# run by bash. The base image sets no ENTRYPOINT (it runs whatever argv you pass
# directly), so without this override the leading `-lc` would be exec'd as a
# binary and fail; a bare `docker run <img> make …` needs no override.
#
# OWNERSHIP: the image runs as root (its Maven/Clojure caches live under /root),
# so every file the command WRITES into the bind mount lands root-owned on the
# host — and any target that rewrites tracked files (`fmt-fix`, the gallery
# re-mint) then leaves a tree the host user cannot edit without sudo. The
# command is therefore wrapped so the workspace is chowned back to the invoking
# uid/gid afterwards, on success or failure alike, and the command's own exit
# status is what propagates.
exec docker run --rm --platform "$PLATFORM" --entrypoint bash \
  -v "$ROOT:/workspace" -w /workspace \
  ${GIT_MOUNT[@]+"${GIT_MOUNT[@]}"} \
  -e CARGO_HOME=/workspace/.cargo-home \
  -e UBER_CMD="$*" -e UBER_UID="$(id -u)" -e UBER_GID="$(id -g)" \
  "$IMG" -lc 'bash -lc "$UBER_CMD"; rc=$?
              chown -R "$UBER_UID:$UBER_GID" /workspace 2>/dev/null || true
              exit $rc'

