#!/usr/bin/env bash
# Interleaved elapsed-time observations for protogen, not noise subtraction.
#
# Usage: tools/perf/bench.sh [-n ROUNDS] [-l LABEL] -- "cmd A" "cmd B" ...
# Commands run sequentially in fixed round-robin order (A B C, A B C).
# The report summarizes per-round shares and ratios to variant 0, alongside
# elapsed milliseconds and their observed spread. Pairing can reduce a common
# slow drift; fixed order, cache effects and unequal contention still bias it.
# Neither relative quantities nor overlapping ranges are significance tests.
#
# Raw TSV, command stdout/stderr and summary land in .protogen/perf/ (ignored).
# Nonzero command exits or nonpositive elapsed times invalidate comparison and
# return nonzero after retaining all observations. Python3 monotonic_ns times
# bash startup, the command and waiting for its exit, excluding Python startup.
# Raw nanoseconds are retained. Every interval includes bash startup, so batch
# short work inside each command. No warmup or CPU/GPU isolation is added.
set -euo pipefail

ROUNDS=3
LABEL=bench
while [ $# -gt 0 ]; do
  case "$1" in
    -n|-l)
      if [ $# -lt 2 ]; then
        echo "bench: $1 requires a value" >&2
        exit 2
      fi
      if [ "$1" = -n ]; then ROUNDS="$2"; else LABEL="$2"; fi
      shift 2 ;;
    --) shift; break ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "bench: unknown option $1" >&2; exit 2 ;;
  esac
done
if [[ ! "$ROUNDS" =~ ^[1-9][0-9]*$ ]] || [ "${#ROUNDS}" -gt 6 ]; then
  echo 'bench: -n must be a positive integer (at most 999999)' >&2
  exit 2
fi
if [[ ! "$LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo 'bench: label must contain only letters, digits, dot, underscore or hyphen' >&2
  exit 2
fi
if [ $# -lt 1 ]; then
  echo 'bench: need at least one variant after --' >&2
  exit 2
fi
for cmd in "$@"; do
  if [[ "$cmd" == *$'\t'* || "$cmd" == *$'\n'* || "$cmd" == *$'\r'* || -z "$cmd" ]]; then
    echo 'bench: commands must be nonempty and contain no tabs or newlines (LF or CR)' >&2
    exit 2
  fi
done
VARIANTS=("$@")
NV=${#VARIANTS[@]}
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUTDIR="$ROOT/.protogen/perf"
mkdir -p "$OUTDIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
# Unique per invocation, including concurrent runs with the same label/second.
LOG="$(mktemp "$OUTDIR/${STAMP}-${LABEL}-XXXXXX.tsv")"
SUMMARY="${LOG%.tsv}.summary.md"
RUNLOGS="${LOG%.tsv}.runs"
mkdir "$RUNLOGS"
CPUS="$(nproc 2>/dev/null || echo unknown)"
LOAD_BEFORE="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo unknown)"
{
  printf '# protogen bench v2\t%s\n' "$STAMP"
  printf '# cpus\t%s\n' "$CPUS"
  printf '# loadavg_before\t%s\n' "$LOAD_BEFORE"
  printf '# rounds\t%s\n' "$ROUNDS"
  python3 -c 'import time; c=time.get_clock_info("monotonic"); print("# clock\t%s; monotonic=%s; resolution_s=%.12g; Python startup excluded" % (c.implementation,c.monotonic,c.resolution))'
  printf 'round\tvariant_idx\tvariant\telapsed_ms\texit\telapsed_ns\n'
} >"$LOG"
echo "bench: ${NV} variant(s) x ${ROUNDS} round(s), fixed round-robin"
echo "bench: cpus=${CPUS} loadavg_before=${LOAD_BEFORE}"
echo "bench: log -> ${LOG}"
for ((r=1; r<=ROUNDS; r++)); do
  for ((i=0; i<NV; i++)); do
    cmd="${VARIANTS[$i]}"
    if ! measurement="$(python3 "$HERE/measure.py" "$RUNLOGS/round-${r}-v${i}.log" "$cmd")"; then
      echo 'INVALID: measurement helper failed; raw observations are incomplete.' >"$SUMMARY"
      exit 1
    fi
    IFS=$'\t' read -r elapsed_ns rc <<<"$measurement"
    if [[ ! "$elapsed_ns" =~ ^-?(0|[1-9][0-9]{0,17})$ || ! "$rc" =~ ^[0-9]{1,3}$ ]]; then
      echo 'INVALID: malformed measurement; raw observations are incomplete.' >"$SUMMARY"
      exit 1
    fi
    elapsed=$(( elapsed_ns / 1000000 ))
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$r" "$i" "$cmd" "$elapsed" "$rc" "$elapsed_ns" >>"$LOG"
    printf '  round %s  v%s  %sms  exit=%s  %s\n' "$r" "$i" "$elapsed" "$rc" "$cmd"
  done
done
LOAD_AFTER="$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || echo unknown)"
printf '# loadavg_after\t%s\n' "$LOAD_AFTER" >>"$LOG"
result=0
awk -F'\t' -v cpus="$CPUS" -v lb="$LOAD_BEFORE" -v la="$LOAD_AFTER" \
  -v rounds="$ROUNDS" -v variants="$NV" -f "$HERE/reduce.awk" "$LOG" | tee "$SUMMARY" || result=1
echo "bench: summary -> ${SUMMARY}"
echo "bench: command logs -> ${RUNLOGS}"
exit "$result"
