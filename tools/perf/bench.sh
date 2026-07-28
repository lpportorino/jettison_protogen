#!/usr/bin/env bash
# Interleaved, relative benchmark runner for protogen.
#
# WHY THIS EXISTS, AND WHY IT IS NOT `time`:
# This box is frequently BUSY — background agents, containers, other sessions.
# An absolute second count measured once is therefore close to meaningless: the
# same lane measured twice can differ by more than the effect any optimisation
# would produce. Two disciplines fix that, and both are built in here rather
# than left to the caller to remember.
#
#   1. INTERLEAVE. Variants run round-robin (A B C, A B C, A B C), never
#      grouped (A A A, B B B). A box that gets busier during the run then
#      penalises every variant roughly equally instead of poisoning whichever
#      one happened to run last. Grouped repeats CANNOT distinguish "B is
#      slower" from "the box got busier while B was running".
#   2. REPORT RELATIVE. The headline number is each variant SHARE of the round
#      total, plus its rank. Shares are stable across load in a way absolute
#      seconds are not. Absolutes are printed too, but as context, never as the
#      claim.
#
# It also records median (not mean — one descheduled run must not move the
# result) and the min..max spread, so noise is VISIBLE rather than averaged
# away. A variant whose spread overlaps another variant spread is NOT
# distinguishable by this measurement, and the report says so.
#
# Logs land in .protogen/perf/ which is gitignored — see the repo .gitignore.
#
# Usage:
#   tools/perf/bench.sh [-n ROUNDS] [-l LABEL] -- "cmd one" "cmd two" ...
#   tools/perf/bench.sh -n 3 -l battery -- "make -f renderer.mk matrix" \
#                                          "make -f renderer.mk morph-parity"
#
# Each argument after -- is one VARIANT, run via bash -c.

set -o pipefail
set -u

ROUNDS=3
LABEL=bench

while [ $# -gt 0 ]; do
  case "$1" in
    -n) ROUNDS="$2"; shift 2 ;;
    -l) LABEL="$2"; shift 2 ;;
    --) shift; break ;;
    -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
    *) echo "bench: unknown option $1" >&2; exit 2 ;;
  esac
done

if [ $# -lt 1 ]; then
  echo "bench: need at least one variant after --" >&2
  exit 2
fi

VARIANTS=("$@")
NV=${#VARIANTS[@]}

# Repo root: this script lives at tools/perf/ under it.
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUTDIR="$ROOT/.protogen/perf"
mkdir -p "$OUTDIR"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$OUTDIR/${STAMP}-${LABEL}.tsv"
SUMMARY="$OUTDIR/${STAMP}-${LABEL}.summary.md"

# ── Load context. A number without this is not interpretable. ────────────────
CPUS="$(nproc 2>/dev/null || echo unknown)"
LOAD_BEFORE="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo unknown)"

{
  printf '# protogen bench\t%s\n' "$STAMP"
  printf '# cpus\t%s\n' "$CPUS"
  printf '# loadavg_before\t%s\n' "$LOAD_BEFORE"
  printf '# rounds\t%s\n' "$ROUNDS"
  printf 'round\tvariant_idx\tvariant\tseconds\texit\n'
} >"$LOG"

echo "bench: ${NV} variant(s) x ${ROUNDS} round(s), INTERLEAVED"
echo "bench: cpus=${CPUS} loadavg_before=${LOAD_BEFORE}"
echo "bench: log -> ${LOG}"
echo

# ── The interleaved loop. Round-robin is the whole point; do not group. ──────
r=1
while [ "$r" -le "$ROUNDS" ]; do
  i=0
  while [ "$i" -lt "$NV" ]; do
    cmd="${VARIANTS[$i]}"
    # Millisecond resolution: $SECONDS is integer-only, which cannot rank the
    # sub-second lanes (measured: graal-check and reference both read 0s under
    # it, so they were indistinguishable from each other and from a no-op).
    start="$(date +%s%N)"
    bash -c "$cmd" >/dev/null 2>&1
    rc=$?
    end="$(date +%s%N)"
    elapsed=$(( (end - start) / 1000000 ))
    printf '%s\t%s\t%s\t%s\t%s\n' "$r" "$i" "$cmd" "$elapsed" "$rc" >>"$LOG"
    printf '  round %s  v%s  %7sms  exit=%s  %s\n' "$r" "$i" "$elapsed" "$rc" "$cmd"
    i=$((i + 1))
  done
  r=$((r + 1))
done

LOAD_AFTER="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo unknown)"
printf '# loadavg_after\t%s\n' "$LOAD_AFTER" >>"$LOG"

# ── Reduce. Relative share is the headline; absolutes are context. ───────────
# Read from the LOG rather than from shell state accumulated in the loop: a
# reduction over artifacts on disk is what makes this safe to parallelise
# later, and it is the same discipline the coverage-matrix verdict needs.
awk -F'\t' -v cpus="$CPUS" -v lb="$LOAD_BEFORE" -v la="$LOAD_AFTER" \
    -v rounds="$ROUNDS" '
  /^#/ || /^round\t/ { next }
  {
    idx = $2; sec = $4 + 0; rc = $5
    name[idx] = $3
    n[idx]++
    vals[idx "," n[idx]] = sec
    total[idx] += sec
    if (rc != 0) bad[idx]++
    if (max[idx] == "" || sec > max[idx]) max[idx] = sec
    if (min[idx] == "" || sec < min[idx]) min[idx] = sec
    grand += sec
  }
  END {
    printf "\n## Relative cost (the headline)\n\n"
    printf "%-6s %10s %10s %10s %6s  %s\n", "share", "median_ms", "min_ms", "max_ms", "runs", "variant"
    # median per variant
    for (idx in name) {
      k = n[idx]
      # insertion sort the k values
      for (a = 1; a <= k; a++) s[a] = vals[idx "," a]
      for (a = 2; a <= k; a++) {
        v = s[a]; b = a - 1
        while (b > 0 && s[b] > v) { s[b+1] = s[b]; b-- }
        s[b+1] = v
      }
      med = (k % 2) ? s[(k+1)/2] : (s[k/2] + s[k/2+1]) / 2
      medians[idx] = med
      medsum += med
    }
    for (idx in name) {
      pct = (medsum > 0) ? 100 * medians[idx] / medsum : 0
      spread = (medians[idx] > 0) ? 100 * (max[idx] - min[idx]) / medians[idx] : 0
      printf "%5.1f%% %10s %10s %10s %6s  %s%s%s\n", pct, medians[idx], min[idx], max[idx], n[idx], \
             name[idx], (bad[idx] ? "   [!! " bad[idx] " NONZERO EXIT]" : ""), \
             (spread > 30 ? "   [noisy: spread " int(spread) "% of median]" : "")
    }
    printf "\ncpus=%s  loadavg %s -> %s  rounds=%s\n", cpus, lb, la, rounds
    printf "\nNOTE: shares are the stable quantity on a busy box. Two variants\n"
    printf "whose min..max spreads OVERLAP are not distinguished by this run --\n"
    printf "raise -n or quiesce the box before claiming a difference.\n"
  }
' "$LOG" | tee "$SUMMARY"

echo
echo "bench: summary -> ${SUMMARY}"
