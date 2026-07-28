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

# THE MOUNT PATH HAS ONE HOME. It is the bind-mount destination, the working
# directory, GIT_WORK_TREE, CARGO_HOME's parent and the chown-back's target —
# five uses that must agree, and did so by five copies of the same literal.
WORKSPACE="/workspace"

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
    GIT_MOUNT=(-v "$GITDIR:/gitdir:ro" -e GIT_DIR=/gitdir -e GIT_WORK_TREE="$WORKSPACE")
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
#
# THAT CHOWN USED TO BE UNABLE TO REPORT ITS OWN FAILURE. It was
# `chown -R … 2>/dev/null || true`: stderr discarded, status forced true. Both
# halves of that silence are real, and were measured here rather than reasoned
# about. A read-only submount inside the mount (`-v … :ro` under /workspace)
# makes a real `chown -R` print a "Read-only file system" line per refused path
# and exit 1; wrapped in the old line, the same run printed NOTHING and exited
# 0. So an ownership failure could not be observed by the caller, by CI, or by
# any gate — and root-owned residue in a checkout is not cosmetic: it is what
# stopped `tools/claude/forks.sh release` from deleting a fork, with no record
# anywhere of which command had produced it.
#
# WHAT A FAILURE DOES TO THE RUN: it reports, LOUDLY, and changes the exit
# status by exactly nothing. That is a decision, not timidity.
#   - uber.sh's contract is "run this command on the pinned toolchain and hand
#     me ITS status". That status is read as the verdict OF THE COMMAND — by a
#     developer at a shell, by `make`, and by whatever runs a suite through here
#     (the mutation harnesses under tools/devcards/dev/ read the OUTPUT instead,
#     which is the same argument one channel over). Folding an ownership problem
#     into it would red a green suite for a reason unrelated to the code under
#     test — a false gate wearing the right colour, the one failure class this
#     repo refuses everywhere else. The inverse is worse still: a chown failure
#     must never MASK a command failure.
#   - Not every failure is even the caller's problem. A `:ro` submount inside
#     the workspace refuses the chown on files the caller never owned. The
#     report names what is actually still foreign-owned, which separates that
#     case from a genuinely poisoned checkout; a hard failure could not.
# The report is on stderr, prefixed `uber.sh:`, and greppable as
# `chown-back FAILED` by anyone who does want to gate on it.
#
# THE LIMIT, SAID OUT LOUD: this reports a chown that RUNS and fails. A
# container that is SIGKILLed never reaches this line at all and leaves exactly
# the same root-owned residue, with nothing here able to report it — measured:
# `docker kill` on a container that had just written into the mount left those
# paths at uid 0 and printed nothing anywhere. What catches THAT class is
# `assert_fork_is_clearable` in tools/claude/forks.sh, and the repair for both
# is the same one-liner this report prints.
#
# WHICH WAY THE SIGNAL CASES FALL, because the intuition is backwards. A SIGTERM
# does NOT produce residue: the payload runs as the container PID 1, and the
# kernel drops a default-action signal at a PID 1 that installed no handler, so
# the command runs to completion and the chown-back happens normally. Measured
# twice — a SIGTERM to this script mid-command, and `docker kill --signal=TERM`
# straight at the container: both survived and exited 0 on their own. That is
# also why `docker stop` is only half safe: its SIGTERM is ignored and the
# SIGKILL ten seconds later is not.
#
# REVERT-TO-BREAK: collapse everything between the two BEGIN/END markers in the
# payload below back to the historical one-liner
#   chown -R "$UBER_UID:$UBER_GID" "$UBER_WORKSPACE" 2>/dev/null || true
# and tools/uber_chown_test.sh must go red naming the missing report, with its
# quiet-on-success and status-propagates controls still green. The sharper
# mutation is the report GUARD alone (`if [ "$chown_rc" -ne 0 ]` -> `if false`),
# which leaves every status assertion green and reds only the report ones; the
# suite runs both against copies of itself on every invocation.
#
# The payload is assembled in a QUOTED heredoc rather than written inline after
# `-lc`, so it can contain quotes of both kinds without escaping games — the
# hazard tools/payload_apostrophes.awk exists for.
INNER_SCRIPT="$(cat <<'UBER_INNER'
bash -lc "$UBER_CMD"
rc=$?

# ---8<--- chown-back report BEGIN
chown_err="$(chown -R "$UBER_UID:$UBER_GID" "$UBER_WORKSPACE" 2>&1 >/dev/null)"
chown_rc=$?
if [ "$chown_rc" -ne 0 ]; then
  {
    printf 'uber.sh: WARNING — chown-back FAILED (chown exit %s) on %s\n' "$chown_rc" "$UBER_WORKSPACE"
    printf '  Your command ran and this run still exits with ITS status (%s). What is\n' "$rc"
    printf '  broken is OWNERSHIP: this container runs as root, so anything it wrote\n'
    printf '  into the bind mount can still be root-owned, and uid %s cannot rewrite\n' "$UBER_UID"
    printf '  or delete those paths on the host.\n'
    if [ -n "$chown_err" ]; then
      printf '  chown said:\n'
      printf '%s\n' "$chown_err" \
        | awk 'NR<=10 { printf "    %s\n", $0 } END { if (NR>10) printf "    ... and %d more chown error line(s)\n", NR-10 }'
    fi
    residue="$(find "$UBER_WORKSPACE" \( ! -uid "$UBER_UID" -o ! -gid "$UBER_GID" \) -printf '%M %u:%g %p\n' 2>/dev/null)"
    find_rc=$?
    if [ -n "$residue" ]; then
      printf '  Still not owned by %s:%s under %s:\n' "$UBER_UID" "$UBER_GID" "$UBER_WORKSPACE"
      printf '%s\n' "$residue" \
        | awk 'NR<=10 { printf "    %s\n", $0 } END { if (NR>10) printf "    ... and %d more path(s)\n", NR-10 }'
    elif [ "$find_rc" -ne 0 ]; then
      printf '  The residue scan itself FAILED (find exit %s), so the chown message\n' "$find_rc"
      printf '  above is the whole record — do not read this as a clean tree.\n'
    else
      printf '  Nothing under %s is foreign-owned now, so the refusal was on a path\n' "$UBER_WORKSPACE"
      printf '  that is already gone or on a read-only submount that was never yours.\n'
    fi
    printf '  Repair from the host: tools/uber.sh true   (runs this same chown again)\n'
  } >&2
fi
# ---8<--- chown-back report END

exit $rc
UBER_INNER
)"

exec docker run --rm --platform "$PLATFORM" --entrypoint bash \
  -v "$ROOT:$WORKSPACE" -w "$WORKSPACE" \
  ${GIT_MOUNT[@]+"${GIT_MOUNT[@]}"} \
  -e CARGO_HOME="$WORKSPACE/.cargo-home" \
  -e UBER_CMD="$*" -e UBER_UID="$(id -u)" -e UBER_GID="$(id -g)" \
  -e UBER_WORKSPACE="$WORKSPACE" \
  "$IMG" -lc "$INNER_SCRIPT"

