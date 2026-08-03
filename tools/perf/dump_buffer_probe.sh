#!/usr/bin/env bash
# tools/perf/dump_buffer_probe.sh — measure controls_dump_tree OCCUPANCY as a
# function of pooled child count, by driving the REAL dump.
#
# WHY. `controls_dump_tree` writes into one static buffer sized by a
# compile-time constant, and `dump_obj` recurses every lv_obj child with no
# visibility test. A consumer sizing a statically-allocated widget pool needs
# the curve, and a curve read off the source is a prediction, not a
# measurement. This script produces the measurement; predict separately and
# compare, because the two disagreeing is the interesting outcome.
#
# WHAT IT EMITS: one TSV row per sample point on stdout —
#   variant  count  wire_bytes  dump_bytes  truncated  nodes  status
# `truncated` is read from the dump's own SENTINEL, never inferred from a size
# comparison: main.c overwrites the tail with `,"truncated":true` precisely so
# a clipped dump is distinguishable from a whole one, and a size-based guess
# would be a second, weaker oracle for a fact the artifact already states.
#
# EXIT STATUS IS READ BARE. The harness's status decides `status`; nothing here
# pipes a command whose status is then read, because a pipeline reports its
# LAST member. A load REFUSED by the renderer is a legitimate data point (it is
# a competing ceiling), so a refusal is RECORDED and the sweep continues; only
# a probe-side failure aborts.
#
# Runs INSIDE the pinned toolchain container (tools/uber.sh), once, for the
# whole sweep: a per-sample container start would dominate the wall clock and
# buy nothing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WASM="${WASM:-renderer/output/controls.wasm}"
HARNESS="${HARNESS:-renderer/wasm_harness/target/release/lvgl_harness}"
SCRATCH="${SCRATCH:-.fork-scratch/probe}"
WIDTH="${WIDTH:-960}"
HEIGHT="${HEIGHT:-540}"

for prereq in "$WASM" "$HARNESS"; do
  if [ ! -f "$prereq" ]; then
    echo "dump_buffer_probe: MISSING PREREQUISITE $prereq" >&2
    echo "  build them first: make -f renderer.mk wasm" >&2
    echo "  and: cd renderer/wasm_harness && cargo build --release" >&2
    exit 2
  fi
done

mkdir -p "$SCRATCH/fixtures" "$SCRATCH/out"

# The sentinel main.c appends when an append was DROPPED for space. Matched as
# a plain substring, never through `grep -q` on a pipe: that combination is a
# known SIGPIPE race here (grep exits without draining, pipefail promotes 141).
SENTINEL=',"truncated":true'

printf 'variant\tcount\twire_bytes\tdump_bytes\ttruncated\tnodes\tstatus\n'

sample() {
  local variant="$1" count="$2"
  shift 2
  local pb="$SCRATCH/fixtures/${variant}_${count}.pb"
  local stem
  stem="$(basename "$pb" .pb)"
  local tree="$SCRATCH/out/${stem}_bp0_light.tree.json"

  rm -f "$tree"

  local genout
  genout="$(python3 tools/perf/dump_buffer_fixture.py --count "$count" --out "$pb" "$@")"
  local wire="${genout##*wire_bytes=}"

  set +e
  "$HARNESS" --wasm "$WASM" --pb "$pb" --output "$SCRATCH/out" \
    --dump-tree --width "$WIDTH" --height "$HEIGHT" \
    >"$SCRATCH/out/${stem}.log" 2>&1
  local rc=$?
  set -e

  if [ "$rc" -ne 0 ] || [ ! -f "$tree" ]; then
    printf '%s\t%s\t%s\t-\t-\t-\tREFUSED(rc=%s)\n' "$variant" "$count" "$wire" "$rc"
    return 0
  fi

  local bytes trunc nodes body
  bytes="$(wc -c <"$tree")"
  body="$(cat "$tree")"
  case "$body" in
    *"$SENTINEL") trunc=yes ;;
    *) trunc=no ;;
  esac
  # Node count = occurrences of the one key every node emits FIRST. Counted
  # from the artifact rather than predicted from `count`, so a dump that lost
  # subtrees reports the nodes it actually carries.
  nodes="$(grep -o '"type":' "$tree" | wc -l)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\tOK\n' \
    "$variant" "$count" "$wire" "$bytes" "$trunc" "$nodes"
}

# Sample points: dense at the low end (where the marginal cost is read off
# successive differences) and continuing well past any plausible pool size, so
# the overflow point is OBSERVED rather than extrapolated.
COUNTS="${COUNTS:-1 2 3 4 8 16 24 32 48 64 96 128 160 192 224 255 320 384 448 512 640 768 896 1024 1280 1536}"

for n in $COUNTS; do sample uid "$n" --uids; done
for n in $COUNTS; do sample hidden "$n" --uids --hidden; done
for n in $COUNTS; do sample nouid "$n"; done
