#!/usr/bin/env bash
# tools/image_pin_check.sh — prove the generator image carries the TWO
# Dockerfile.base pins whose drift silently changes committed output —
# PROTOC_GEN_GO_VERSION and PROTOVALIDATE_REF — before any leg runs.
#
# EXACTLY THOSE TWO, not every pin. It is not a general image-currency check: a
# change to any other Dockerfile.base pin still needs `make rebuild-base` by
# hand. PROTOC_GEN_GO_GRPC_VERSION is deliberately left out because no proto
# declares a service, so the grpc plugin writes no file and its version reaches
# no committed byte — every Go header carries protoc-gen-go and nothing else:
#   grep -rh 'protoc-gen-go' output/go | sort | uniq -c
# A proto that adds a service changes that, and then this check owes the grpc
# pin too.
#
# WHY THIS EXISTS. `make build` reuses any existing
# jettison-proto-generator-base:latest, and a consumer's generate wrapper keeps
# that base image on purpose, so an image built BEFORE a pin moved keeps
# generating with the OLD input. Nothing downstream notices: the legs succeed
# and write plausible bytes. Two inputs have done exactly that:
#   - protoc-gen-go stamps its version into every Go header, so a stale plugin
#     rewrites every .pb.go banner;
#   - the protovalidate clone supplies validate.proto to every validate-aware
#     leg, so a stale clone re-mints validate.pb.go and every json-descriptor
#     carrying the buf.validate rule text.
# So the run refuses up front, naming the fix.
#
# It compares the image against the Dockerfile.base it is given, reading each
# pin from its own `ARG NAME=value` line. Surrounding whitespace and one pair of
# surrounding double or single quotes are stripped, as Docker does, so
# `ARG X="v1"` reads as v1. A pin line that is indented, doubled or absent is an
# ERROR rather than a guess. HOST-ONLY: it drives `docker run`.
#
# EXIT CODES separate a verdict from a precondition failure:
#   0  the image carries every pin
#   1  STALE — at least one pin differs; the fix is printed
#   2  ERROR — could not run (no docker, no image, a pin line missing or doubled)
#
# usage:
#   tools/image_pin_check.sh [--image IMG] [--dockerfile PATH]
#   tools/image_pin_check.sh --canary     # prove the check can FAIL, per pin
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="jettison-proto-generator:latest"
DOCKERFILE="$ROOT/Dockerfile.base"
MODE="check"

while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:?--image needs a value}"; shift 2 ;;
    --dockerfile) DOCKERFILE="${2:?--dockerfile needs a value}"; shift 2 ;;
    --canary) MODE="canary"; shift ;;
    -h|--help) sed -n '2,29p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'image-pin-check: unknown argument %s\n' "$1" >&2; exit 2 ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
err()   { red "[image-pin-check] ERROR — $*" >&2; exit 2; }

# pin <dockerfile> <ARG name>: sets PIN to the value of the one `ARG NAME=value`
# line, whitespace-trimmed and unquoted. It sets a variable rather than
# printing, because a caller reading it through $(...) would turn the refusal
# below into a subshell exit and carry on with an empty pin.
pin() {
  local n q
  n="$(grep -c "^ARG $2=" "$1" || true)"
  [ "$n" = "1" ] || err "expected exactly one 'ARG $2=' line in $1, found ${n:-0}"
  PIN="$(sed -n "s/^ARG $2=\\(.*\\)\$/\\1/p" "$1")"
  PIN="${PIN#"${PIN%%[![:space:]]*}"}"
  PIN="${PIN%"${PIN##*[![:space:]]}"}"
  for q in '"' "'"; do
    if [ "${#PIN}" -ge 2 ] && [ "${PIN:0:1}" = "$q" ] && [ "${PIN: -1}" = "$q" ]; then
      PIN="${PIN:1:${#PIN}-2}"
      break
    fi
  done
  [ -n "$PIN" ] || err "ARG $2 in $1 has an empty value"
}

# check <dockerfile>: compare the image against that Dockerfile's pins.
check() {
  local df="$1" want_pv want_pgg got got_pv got_pgg rc=0
  [ -f "$df" ] || err "no Dockerfile at $df"
  command -v docker >/dev/null 2>&1 || err "docker is not on PATH; this check is host-only"
  docker image inspect "$IMAGE" >/dev/null 2>&1 || err "image $IMAGE is absent — build it with: make build"
  pin "$df" PROTOVALIDATE_REF; want_pv="$PIN"
  pin "$df" PROTOC_GEN_GO_VERSION; want_pgg="$PIN"
  got="$(docker run --rm --network none --entrypoint /bin/bash "$IMAGE" -c \
    'env -u GIT_DIR -u GIT_WORK_TREE git -C /opt/protovalidate rev-parse HEAD && protoc-gen-go --version')" \
    || err "could not read the pins out of $IMAGE"
  got_pv="$(sed -n 1p <<<"$got")"
  got_pgg="$(sed -n 2p <<<"$got" | sed 's/^protoc-gen-go //')"
  [ -n "$got_pv" ] && [ -n "$got_pgg" ] || err "the image reported nothing for its pins"
  if [ "$got_pv" != "$want_pv" ]; then
    red "[image-pin-check] STALE — /opt/protovalidate is at $got_pv, Dockerfile.base pins PROTOVALIDATE_REF=$want_pv"
    rc=1
  fi
  if [ "$got_pgg" != "$want_pgg" ]; then
    red "[image-pin-check] STALE — protoc-gen-go is $got_pgg, Dockerfile.base pins PROTOC_GEN_GO_VERSION=$want_pgg"
    rc=1
  fi
  if [ "$rc" -ne 0 ]; then
    red "[image-pin-check] $IMAGE was built from older pins. Rebuild the base image (make rebuild-base), then re-run."
    return 1
  fi
  green "[image-pin-check] OK — $IMAGE carries PROTOVALIDATE_REF=$want_pv and protoc-gen-go $want_pgg"
}

# canary: the real Dockerfile passes, and a copy with each pin moved fails
# from that pin's own clause, with the rebuild instruction.
canary() {
  local work pass=0 fail=0 out rc
  work="$(mktemp -d)"
  # shellcheck disable=SC2064  # expand $work now
  trap "rm -rf '$work'" RETURN
  # expect <want-exit> <got-exit> <needle> <output> <second-needle|""> <label>
  expect() {
    if [ "$2" -eq "$1" ] && grep -qF "$3" <<<"$4" && { [ -z "$5" ] || grep -qF "$5" <<<"$4"; }; then
      green "  ok   $6 (exit $2)"; pass=$((pass + 1))
    else red "  FAIL $6 — exit $2; output: $(tr '\n' ' ' <<<"$4")"; fail=$((fail + 1)); fi
  }
  set +e; out="$(check "$DOCKERFILE" 2>&1)"; rc=$?; set -e
  expect 0 "$rc" "OK" "$out" "" "control: the real Dockerfile.base passes"

  sed 's/^ARG PROTOVALIDATE_REF=.*/ARG PROTOVALIDATE_REF=0000000000000000000000000000000000000000/' \
    "$DOCKERFILE" >"$work/pv.Dockerfile"
  grep -q '^ARG PROTOVALIDATE_REF=0000000000000000000000000000000000000000$' "$work/pv.Dockerfile" \
    || err "canary fixture: the PROTOVALIDATE_REF mutation did not land"
  set +e; out="$(check "$work/pv.Dockerfile" 2>&1)"; rc=$?; set -e
  expect 1 "$rc" "PROTOVALIDATE_REF=0000000000000000000000000000000000000000" "$out" "make rebuild-base" \
    "a wrong PROTOVALIDATE_REF is STALE and names the rebuild"
  grep -qF "PROTOC_GEN_GO_VERSION=" <<<"$out" && { red "  FAIL the protoc-gen-go clause also fired"; fail=$((fail + 1)); }

  sed 's/^ARG PROTOC_GEN_GO_VERSION=.*/ARG PROTOC_GEN_GO_VERSION=v0.0.0-planted/' \
    "$DOCKERFILE" >"$work/pgg.Dockerfile"
  grep -q '^ARG PROTOC_GEN_GO_VERSION=v0.0.0-planted$' "$work/pgg.Dockerfile" \
    || err "canary fixture: the PROTOC_GEN_GO_VERSION mutation did not land"
  set +e; out="$(check "$work/pgg.Dockerfile" 2>&1)"; rc=$?; set -e
  expect 1 "$rc" "PROTOC_GEN_GO_VERSION=v0.0.0-planted" "$out" "make rebuild-base" \
    "a wrong PROTOC_GEN_GO_VERSION is STALE and names the rebuild"
  grep -qF "PROTOVALIDATE_REF=" <<<"$out" && { red "  FAIL the protovalidate clause also fired"; fail=$((fail + 1)); }

  grep -v '^ARG PROTOVALIDATE_REF=' "$DOCKERFILE" >"$work/none.Dockerfile"
  set +e; out="$( (check "$work/none.Dockerfile") 2>&1)"; rc=$?; set -e
  expect 2 "$rc" "exactly one 'ARG PROTOVALIDATE_REF=' line" "$out" "" "a missing pin line is an ERROR, never a pass"

  # Docker-legal spellings of the SAME pin must read as the same pin: a check
  # that calls them STALE is a loop `make rebuild-base` can never clear.
  sed 's/^ARG PROTOC_GEN_GO_VERSION=\(.*\)$/ARG PROTOC_GEN_GO_VERSION="\1"/' "$DOCKERFILE" >"$work/dq.Dockerfile"
  grep -q '^ARG PROTOC_GEN_GO_VERSION=".*"$' "$work/dq.Dockerfile" || err "canary fixture: the double-quote mutation did not land"
  set +e; out="$(check "$work/dq.Dockerfile" 2>&1)"; rc=$?; set -e
  expect 0 "$rc" "OK" "$out" "" "a double-quoted pin reads as the same pin"

  sed "s/^ARG PROTOVALIDATE_REF=\\(.*\\)\$/ARG PROTOVALIDATE_REF='\\1'/" "$DOCKERFILE" >"$work/sq.Dockerfile"
  grep -q "^ARG PROTOVALIDATE_REF='.*'\$" "$work/sq.Dockerfile" || err "canary fixture: the single-quote mutation did not land"
  set +e; out="$(check "$work/sq.Dockerfile" 2>&1)"; rc=$?; set -e
  expect 0 "$rc" "OK" "$out" "" "a single-quoted pin reads as the same pin"

  sed 's/^ARG PROTOVALIDATE_REF=.*$/&  /' "$DOCKERFILE" >"$work/ws.Dockerfile"
  grep -q '^ARG PROTOVALIDATE_REF=.*[^ ]  $' "$work/ws.Dockerfile" || err "canary fixture: the trailing-space mutation did not land"
  set +e; out="$(check "$work/ws.Dockerfile" 2>&1)"; rc=$?; set -e
  expect 0 "$rc" "OK" "$out" "" "a pin with trailing whitespace reads as the same pin"

  sed 's/^ARG PROTOVALIDATE_REF=/  &/' "$DOCKERFILE" >"$work/indent.Dockerfile"
  grep -q '^  ARG PROTOVALIDATE_REF=' "$work/indent.Dockerfile" || err "canary fixture: the indent mutation did not land"
  set +e; out="$( (check "$work/indent.Dockerfile") 2>&1)"; rc=$?; set -e
  expect 2 "$rc" "exactly one 'ARG PROTOVALIDATE_REF=' line" "$out" "" "an indented pin line is an ERROR, never a guess"

  # printf: Dockerfile.base ends without a newline, and an append would glue
  # the copy onto its last line instead of doubling the pin.
  { cat "$DOCKERFILE"; printf '\n'; grep '^ARG PROTOVALIDATE_REF=' "$DOCKERFILE"; } >"$work/double.Dockerfile"
  [ "$(grep -c '^ARG PROTOVALIDATE_REF=' "$work/double.Dockerfile")" = "2" ] \
    || err "canary fixture: the doubled-line mutation did not land"
  set +e; out="$( (check "$work/double.Dockerfile") 2>&1)"; rc=$?; set -e
  expect 2 "$rc" "found 2" "$out" "" "a doubled pin line is an ERROR, never a guess"

  printf '\n'
  if [ "$fail" -eq 0 ]; then green "[image-pin-check canary] ALL GREEN — $pass assertion(s)"; return 0; fi
  red "[image-pin-check canary] $fail assertion(s) FAILED, $pass passed"
  return 1
}

if [ "$MODE" = "canary" ]; then
  canary
  exit $?
fi
check "$DOCKERFILE"
