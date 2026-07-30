#!/usr/bin/env bash
# tools/go_leg_repro.sh — re-run generate-protos.sh's GO LEG into a throwaway
# directory and prove the committed output/go is exactly what it produces.
#
# WHY THIS EXISTS. The Go leg is the only leg whose PLUGIN VERSIONS are stamped
# into the bytes it writes (protoc-gen-go puts its own version in every file
# header), so its output is a function of Dockerfile.base as much as of proto/.
# A pin bumped without a regeneration, or a regeneration run on an image built
# from different pins, moves 48 files that ten consumer repositories vendor.
# This is the check that says so, and it is the re-runnable evidence behind any
# claim that a change to the leg was byte-neutral.
#
# THREE IMAGE INPUTS DECIDE THOSE BYTES, NOT TWO — say so, because a red here
# sends the reader to Dockerfile.base and the obvious two suspects are not the
# whole set:
#   1. PROTOC_GEN_GO_VERSION      — pinned
#   2. PROTOC_GEN_GO_GRPC_VERSION — pinned, and inert today (no proto declares a
#                                   service, so this plugin emits no file)
#   3. the protovalidate clone    — NOT PINNED. Dockerfile.base clones it at
#      default-branch HEAD, and buf/validate/validate.pb.go — one of the 48 — is
#      generated from it. An upstream commit there moves a vendored file with no
#      change in this repo at all. If this check goes red on exactly that one
#      file and nothing else, suspect the clone before you suspect a pin.
#
# HOST-ONLY. It drives `docker run`, and the toolchain image ships no docker
# CLI, so this cannot run inside tools/uber.sh.
#
# It NEVER writes into output/ — generation goes to a mktemp directory that is
# removed on exit. Offline by default (`--network none`), because a local leg
# that needs the network is the defect this check exists to keep out.
#
# EXIT CODES separate a verdict from a precondition failure, so a canary can
# assert which one happened:
#   0  every generated file is byte-identical to output/go
#   1  FAIL — a content difference, or a generated path missing from output/go
#   2  ERROR — could not run, or discovery came back empty (never a pass)
#
# usage:
#   tools/go_leg_repro.sh                 # generate offline, compare, verdict
#   tools/go_leg_repro.sh --allow-network # same, without --network none
#   tools/go_leg_repro.sh --image IMG     # compare against a specific image
#   tools/go_leg_repro.sh --canary        # prove this check can FAIL (see below)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="jettison-proto-generator:latest"
NET_ARGS=(--network none)
MODE="check"

while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:?--image needs a value}"; shift 2 ;;
    --allow-network) NET_ARGS=(); shift ;;
    --canary) MODE="canary"; shift ;;
    -h|--help) sed -n '2,31p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'go-leg-repro: unknown argument %s\n' "$1" >&2; exit 2 ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }

err() { red "[go-leg-repro] ERROR — $*" >&2; exit 2; }

# ── generation ───────────────────────────────────────────────────────────────

# The payload is EXTRACTED from generate-protos.sh, never retyped here. A copy
# would drift from the real leg and this check would then prove something about
# the copy, which is the failure mode it is meant to exclude.
extract_payload() {
  local src="$ROOT/generate-protos.sh" block
  [ -f "$src" ] || err "no generate-protos.sh at $src"
  block="$(sed -n "/^GO_SCRIPT='\$/,/^'\$/p" "$src")"
  [ -n "$block" ] || err "could not extract the GO_SCRIPT assignment from $src"
  eval "$block"
  [ -n "${GO_SCRIPT:-}" ] || err "the GO_SCRIPT assignment evaluated empty"
  # Non-vacuity on the payload itself: an even number of apostrophes rebalances
  # the quoting and yields an EMPTY payload that still parses (see lint.mk).
  grep -q '^buf generate$' <<<"$GO_SCRIPT" \
    || err "the extracted payload contains no 'buf generate' — it is not the go leg"
}

generate_into() {
  local out="$1"
  command -v docker >/dev/null 2>&1 || err "docker is not on PATH; this check is host-only"
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || err "image $IMAGE is absent — build it with: make build"
  mkdir -p "$out"
  # CHOWN BACK, for the same reason tools/uber.sh does it: the image runs as
  # root, so everything the leg writes into the bind mount lands root-owned and
  # the invoking user cannot then delete it. Left unhandled that surfaces as a
  # cleanup failure whose exit status REPLACES the verdict — measured here: a
  # green comparison exited 1 because the EXIT trap could not remove the temp
  # tree. Generation and chown are one payload so a failure of either is seen.
  local payload
  payload="$GO_SCRIPT
chown -R $(id -u):$(id -g) /workspace/output"
  # Run BARE. A pipeline would report the FILTER's status and a failed
  # generation would read as success.
  docker run --rm "${NET_ARGS[@]+"${NET_ARGS[@]}"}" \
    -v "$ROOT/proto:/workspace/proto:ro" \
    -v "$out:/workspace/output:rw" \
    -v "$ROOT/scripts:/workspace/scripts:ro" \
    -w /workspace \
    --entrypoint /bin/bash \
    "$IMAGE" -c "$payload" >/dev/null \
    || err "the go leg itself failed inside $IMAGE; re-run without redirection to see it"
}

# ── comparison ───────────────────────────────────────────────────────────────
#
# compare <committed-dir> <fresh-dir>; echoes its findings, returns 0/1/2.
#
# THE ASYMMETRY IS DELIBERATE. A generated path MISSING from the committed tree
# is a verdict: the leg produces it and the tree does not carry it. A path in
# the committed tree that the leg does NOT produce is NOT a verdict here, and
# saying so is the honest scope of this check rather than an advisory tier:
# `make generate` never deletes, so output/ accumulates bindings whose proto was
# removed, plus artifacts other flows wrote. Whether those should be deleted is
# a decision about the TREE — a generated artifact a consumer may vendor is not
# this check's to condemn — while this check judges the LEG. They are counted
# and named on every run so they cannot go unnoticed.
compare() {
  local committed="$1" fresh="$2" rc=0
  local -a differing=() missing=() extra=()

  # ONE ENUMERATION FEEDS BOTH THE FLOOR AND THE COMPARISON. This used to be two
  # commands — `find … | wc -l` for the non-vacuity floor, a separate `find` in a
  # process substitution for the loops — and that split is a false-green
  # generator, not a style wart. A process substitution runs in a subshell whose
  # failure `set -euo pipefail` cannot observe, so if the enumeration dies the
  # loops read zero lines, `differing` and `missing` stay empty, `rc` stays 0 —
  # while the floor, computed by a DIFFERENT command that did not fail, still
  # reports a healthy count and waves it through. Measured on the two-command
  # version, same inputs, one file genuinely differing: honest run FAIL/exit 1,
  # broken-enumeration run `OK — all 2 … byte-identical`/exit 0.
  #
  # A floor can only floor the thing it actually counts. Deriving both from one
  # array makes a dead enumeration surface as a count of ZERO — an ERROR — which
  # is the whole point of having a floor.
  local -a fresh_list=() committed_list=()
  mapfile -t fresh_list < <(cd "$fresh" && find . -type f | sed 's|^\./||' | sort)
  mapfile -t committed_list < <(cd "$committed" && find . -type f | sed 's|^\./||' | sort)
  local n_fresh="${#fresh_list[@]}" n_committed="${#committed_list[@]}"
  [ "$n_fresh" -gt 0 ] || { red "[go-leg-repro] ERROR — enumerated ZERO generated files; that is a broken run or a broken enumeration, never a clean tree" >&2; return 2; }
  [ "$n_committed" -gt 0 ] || { red "[go-leg-repro] ERROR — enumerated ZERO files in the committed tree; discovery broke" >&2; return 2; }

  local rel
  for rel in "${fresh_list[@]}"; do
    if [ ! -f "$committed/$rel" ]; then
      missing+=("$rel")
    elif ! cmp -s "$fresh/$rel" "$committed/$rel"; then
      differing+=("$rel")
    fi
  done

  for rel in "${committed_list[@]}"; do
    [ -f "$fresh/$rel" ] || extra+=("$rel")
  done

  printf '[go-leg-repro] image=%s  generated=%s file(s)  committed=%s file(s)\n' \
    "$IMAGE" "$n_fresh" "$n_committed"

  if [ "${#differing[@]}" -gt 0 ]; then
    red "[go-leg-repro] FAIL — ${#differing[@]} generated file(s) differ in CONTENT from the committed tree:"
    printf '    %s\n' "${differing[@]}"
    rc=1
  fi
  if [ "${#missing[@]}" -gt 0 ]; then
    red "[go-leg-repro] FAIL — ${#missing[@]} generated file(s) are ABSENT from the committed tree:"
    printf '    %s\n' "${missing[@]}"
    rc=1
  fi
  if [ "${#extra[@]}" -gt 0 ]; then
    printf '[go-leg-repro] OBSERVED — %s committed path(s) this leg does NOT produce (not a verdict; see the header):\n' "${#extra[@]}"
    printf '    %s\n' "${extra[@]}"
  fi
  # An `if`, not `cond && green …`. As a trailing `&&` list this is correct only
  # because every caller wraps the call in `set +e`; under errexit the list would
  # abort the function and the status would happen to equal $rc for the one value
  # that can reach here. A contract that holds by coincidence is not a contract.
  if [ "$rc" -eq 0 ]; then
    green "[go-leg-repro] OK — all $n_fresh generated file(s) are byte-identical to output/go"
  fi
  return "$rc"
}

# ── canary ───────────────────────────────────────────────────────────────────
#
# Proves this check can go RED, and that each red is attributable to the clause
# under test. SYNTHETIC FIXTURES ONLY: every planted input is a copy in a temp
# directory, so a canary run never touches output/ and works on a dirty tree.
# The generation runs ONCE and every case reuses its result.
canary() {
  local fresh="$1" work rc pass=0 fail=0
  work="$(mktemp -d)"
  # shellcheck disable=SC2064  # expand $work now: it must survive this function
  trap "rm -rf '$work'" RETURN

  assert_rc() {
    local want="$1" got="$2" what="$3"
    if [ "$got" -eq "$want" ]; then green "  ok   $what (exit $got)"; pass=$((pass + 1));
    else red "  FAIL $what — expected exit $want, got $got"; fail=$((fail + 1)); fi
  }

  # 1. CONTROL: an exact copy must pass. Without this the suite cannot tell a
  #    working check from one that fails on everything.
  cp -r "$fresh" "$work/control"
  set +e; compare "$work/control" "$fresh" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 0 "$rc" "control: an identical tree is a PASS"

  # 2. A planted CONTENT difference must be a FAIL, and must be attributed to
  #    the content clause rather than to any other.
  cp -r "$fresh" "$work/mutated"
  local victim
  victim="$(cd "$work/mutated" && find . -name '*.pb.go' -type f | sort | head -1)"
  [ -n "$victim" ] || { red "  FAIL canary fixture: no .pb.go to mutate"; return 1; }
  printf '\n// planted by go_leg_repro --canary\n' >>"$work/mutated/$victim"
  # PROOF THE MUTATION LANDED — a no-op mutation yields a green that reads as
  # attribution while proving the opposite.
  grep -q 'planted by go_leg_repro' "$work/mutated/$victim" \
    || { red "  FAIL canary fixture: the mutation did not land in $victim"; return 1; }
  cmp -s "$work/mutated/$victim" "$fresh/$victim" \
    && { red "  FAIL canary fixture: mutant is byte-identical to the original"; return 1; }
  set +e; out="$(compare "$work/mutated" "$fresh" 2>&1)"; rc=$?; set -e
  assert_rc 1 "$rc" "planted content difference is a FAIL"
  if grep -q 'differ in CONTENT' <<<"$out"; then green "  ok   attributed to the CONTENT clause"; pass=$((pass + 1));
  else red "  FAIL red fired, but not from the content clause"; fail=$((fail + 1)); fi

  # 3. A generated file ABSENT from the committed tree must be a FAIL, from its
  #    own clause — a neighbouring clause refusing the same input proves nothing.
  cp -r "$fresh" "$work/reduced"
  rm "$work/reduced/$victim"
  [ ! -e "$work/reduced/$victim" ] || { red "  FAIL canary fixture: the deletion did not land"; return 1; }
  set +e; out="$(compare "$work/reduced" "$fresh" 2>&1)"; rc=$?; set -e
  assert_rc 1 "$rc" "generated file absent from the committed tree is a FAIL"
  if grep -q 'ABSENT from the committed tree' <<<"$out"; then green "  ok   attributed to the ABSENT clause"; pass=$((pass + 1));
  else red "  FAIL red fired, but not from the absent clause"; fail=$((fail + 1)); fi

  # 4. An extra committed path is OBSERVED, never a verdict. This is the clause
  #    most likely to be wrong by accident, so it is asserted in both halves:
  #    the exit stays 0 AND the path is still named.
  cp -r "$fresh" "$work/extra"
  printf 'orphan\n' >"$work/extra/ORPHAN_FIXTURE.pb.go"
  set +e; out="$(compare "$work/extra" "$fresh" 2>&1)"; rc=$?; set -e
  assert_rc 0 "$rc" "an extra committed path does NOT flip the verdict"
  if grep -q 'ORPHAN_FIXTURE.pb.go' <<<"$out"; then green "  ok   the extra path is still NAMED"; pass=$((pass + 1));
  else red "  FAIL an extra path was silently swallowed"; fail=$((fail + 1)); fi

  # 5. EMPTY DISCOVERY IS AN ERROR, NEVER A PASS — in both directions, because
  #    an empty committed tree and an empty generation fail identically to a
  #    union floor and are different defects.
  mkdir -p "$work/empty-committed"
  set +e; compare "$work/empty-committed" "$fresh" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "empty committed tree is an ERROR"
  mkdir -p "$work/empty-fresh"
  set +e; compare "$work/control" "$work/empty-fresh" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "empty generation is an ERROR"

  # 6. A BROKEN ENUMERATION IS AN ERROR, NEVER A GREEN. This is a regression
  #    guard over a defect this script actually shipped: while the floor was
  #    counted by a different command than the loops enumerated with, a dead
  #    enumeration printed the byte-identical `OK — all N … byte-identical` and
  #    exited 0 over a tree that differed. The enumeration runs in a process
  #    substitution, so errexit cannot see it fail — only a floor derived from
  #    the SAME enumeration can. The shim breaks the enumeration pipeline and
  #    nothing else; `$work/mutated` genuinely differs from $fresh, so a green
  #    here is a FALSE green rather than an accidentally-correct one.
  mkdir -p "$work/shim"
  printf '#!/bin/sh\nexit 1\n' >"$work/shim/sed"
  chmod +x "$work/shim/sed"
  set +e; (PATH="$work/shim:$PATH"; compare "$work/mutated" "$fresh") >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "a broken enumeration is an ERROR, not a green over a differing tree"

  printf '\n'
  if [ "$fail" -eq 0 ]; then green "[go-leg-repro canary] ALL GREEN — $pass assertion(s)"; return 0; fi
  red "[go-leg-repro canary] $fail assertion(s) FAILED, $pass passed"
  return 1
}

# ── main ─────────────────────────────────────────────────────────────────────

extract_payload
FRESH="$(mktemp -d)"
earned=0
# THE TRAP MUST NOT REWRITE THE VERDICT. A bare `trap rm -rf ... EXIT` hands the
# cleanup command's status to the caller, so a tree this script cannot delete
# reports as a FAIL of the comparison. Save the real status, report a cleanup
# failure LOUDLY, and exit with the status that was actually earned — the same
# split tools/uber.sh makes between "the command's verdict" and "ownership went
# wrong". Neither is allowed to impersonate the other.
trap 'earned=$?; rm -rf "$FRESH" \
        || printf "go-leg-repro: WARNING — could not remove %s (verdict unaffected: %s)\n" "$FRESH" "$earned" >&2
      exit "$earned"' EXIT
generate_into "$FRESH"

if [ "$MODE" = "canary" ]; then
  canary "$FRESH"
  exit $?
fi

set +e
compare "$ROOT/output/go" "$FRESH"
rc=$?
set -e
exit "$rc"
