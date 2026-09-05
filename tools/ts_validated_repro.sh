#!/usr/bin/env bash
# tools/ts_validated_repro.sh — re-run generate-protos.sh's TYPESCRIPT-VALIDATED
# LEG into a throwaway directory and prove the committed
# output/typescript-validated is exactly what the PINNED generator produces.
#
# WHY THIS EXISTS. Dockerfile.base pins @bufbuild/protoc-gen-es under a comment
# arguing that a tool which rewrites committed source must be pinned. The pin
# half of that is done; this is the proof the pin was bought for. The plugin
# STAMPS ITS OWN VERSION into the first line of every file it emits, so its
# version is part of the committed bytes rather than merely a reproducibility
# concern — the same property the Go plugins have and a compiler pin does not.
# Unpinned, that stamp was whatever npm resolved on the day a given file was
# last written, and a regeneration that rewrote only some files left the
# directory carrying two generator versions at once. A proof that is merely
# asserted repeats that; this is the re-runnable evidence behind the claim.
#
# TWO IMAGE INPUTS DECIDE THOSE BYTES, and only one of them is the pin — say so,
# because a red here sends the reader to Dockerfile.base and the obvious suspect
# is not the whole set:
#   1. PROTOC_GEN_ES_VERSION   — pinned, as `@bufbuild/protoc-gen-es@X.Y.Z`, and
#      asserted against the emitted stamp by the PIN clause below.
#   2. add-validate-import.sh  — NOT a pin, and the trap. Two DIFFERENT copies
#      of it exist: Dockerfile.base writes one inline into the base image, and
#      Dockerfile COPYs scripts/add-validate-import.sh over it in the generator
#      image. They are not equivalent — the base copy adds the validate import
#      to EVERY proto, the scripts copy only to protos that actually carry
#      buf.validate annotations, and the import decides whether the emitted
#      TypeScript carries `file_buf_validate_validate`. So this check runs in
#      the GENERATOR image, never the base/uber one; running it in the uber
#      container would compare against a leg the fleet does not use.
#
# HOST-ONLY, in `check` mode. It drives `docker run`, and the toolchain image
# ships no docker CLI, so that mode cannot run inside tools/uber.sh. `--canary`
# is HERMETIC and needs neither docker nor an image — see its own section.
#
# It NEVER writes into output/ — generation goes to a mktemp directory that is
# removed on exit. Offline by default (`--network none`), because a local leg
# that needs the network is the defect this check exists to keep out.
#
# EXIT CODES separate a verdict from a precondition failure, so a canary can
# assert which one happened:
#   0  every generated file is byte-identical to output/typescript-validated,
#      and the emitted stamp equals the pin declared in Dockerfile.base
#   1  FAIL — a content difference, a generated path missing from the committed
#      tree, or the emitted stamp disagreeing with the declared pin
#   2  ERROR — could not run, or a discovery came back empty (never a pass)
#
# usage:
#   tools/ts_validated_repro.sh                 # generate offline, compare, verdict
#   tools/ts_validated_repro.sh --allow-network # same, without --network none
#   tools/ts_validated_repro.sh --image IMG     # compare against a specific image
#   tools/ts_validated_repro.sh --canary        # prove this check can FAIL (hermetic)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="jettison-proto-generator:latest"
NET_ARGS=(--network none)
MODE="check"
COMMITTED="$ROOT/output/typescript-validated"
PIN_FILE="$ROOT/Dockerfile.base"

while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:?--image needs a value}"; shift 2 ;;
    --allow-network) NET_ARGS=(); shift ;;
    --canary) MODE="canary"; shift ;;
    -h|--help) sed -n '2,52p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'ts-validated-repro: unknown argument %s\n' "$1" >&2; exit 2 ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }

err() { red "[ts-validated-repro] ERROR — $*" >&2; exit 2; }

# ── the declared pin ──────────────────────────────────────────────────────────
#
# READ FROM Dockerfile.base, never retyped. The version is the thing under test,
# so a copy of it here would make the PIN clause compare a constant with itself.
# Echoes the bare version; returns 2 with a diagnosis when it cannot find one,
# because "no pin" must never read as "the pin matches".
declared_pin() {
  local src="${1:-$PIN_FILE}" v
  [ -f "$src" ] || { red "[ts-validated-repro] ERROR — no Dockerfile at $src" >&2; return 2; }
  v="$(sed -n 's|.*@bufbuild/protoc-gen-es@\([0-9][0-9A-Za-z.+-]*\).*|\1|p' "$src" | head -1)"
  if [ -z "$v" ]; then
    red "[ts-validated-repro] ERROR — no '@bufbuild/protoc-gen-es@<version>' pin in $src." >&2
    printf '  The generator is either unpinned again or the install line was reshaped.\n' >&2
    printf '  An unpinned generator is the defect this check exists for, so this is an\n' >&2
    printf '  ERROR rather than a skip.\n' >&2
    return 2
  fi
  printf '%s\n' "$v"
}

# The stamp protoc-gen-es writes into the first line of every file it emits.
# Echoes the distinct versions found under $1, one per line, deduplicated.
emitted_stamps() {
  local dir="$1"
  find "$dir" -name '*_pb.ts' -type f -print0 \
    | xargs -0 -r -n1 head -1 \
    | sed -n 's|^// @generated by protoc-gen-es v\([0-9][0-9A-Za-z.+-]*\) .*|\1|p' \
    | sort -u
}

# ── generation ───────────────────────────────────────────────────────────────

# The payload is EXTRACTED from generate-protos.sh, never retyped here. A copy
# would drift from the real leg and this check would then prove something about
# the copy, which is the failure mode it is meant to exclude.
extract_payload() {
  local src="${1:-$ROOT/generate-protos.sh}" block
  [ -f "$src" ] || { red "[ts-validated-repro] ERROR — no generate-protos.sh at $src" >&2; return 2; }
  block="$(sed -n "/^TYPESCRIPT_VALIDATED_SCRIPT='\$/,/^'\$/p" "$src")"
  if [ -z "$block" ]; then
    red "[ts-validated-repro] ERROR — could not extract the TYPESCRIPT_VALIDATED_SCRIPT assignment from $src" >&2
    printf '  The extractor expects a lone `TYPESCRIPT_VALIDATED_SCRIPT=%s` line and a lone\n' "'" >&2
    printf '  closing quote. If the assignment was renamed or reflowed, fix the extractor\n' >&2
    printf '  rather than retyping the payload here.\n' >&2
    return 2
  fi
  eval "$block"
  # Non-vacuity on the payload itself: an even number of apostrophes rebalances
  # the quoting and yields an EMPTY payload that still parses (see lint.mk).
  if [ -z "${TYPESCRIPT_VALIDATED_SCRIPT:-}" ]; then
    red "[ts-validated-repro] ERROR — the TYPESCRIPT_VALIDATED_SCRIPT assignment evaluated EMPTY" >&2
    return 2
  fi
  # And that what was extracted is THIS leg rather than some other assignment
  # the range happened to span. Both tokens, because either alone appears in
  # neighbouring legs' prose.
  if ! grep -q 'protoc-gen-es' <<<"$TYPESCRIPT_VALIDATED_SCRIPT" \
    || ! grep -q -- '--es_out' <<<"$TYPESCRIPT_VALIDATED_SCRIPT"; then
    red "[ts-validated-repro] ERROR — the extracted payload does not invoke protoc-gen-es with --es_out; it is not the typescript-validated leg" >&2
    return 2
  fi
}

generate_into() {
  local out="$1"
  command -v docker >/dev/null 2>&1 || err "docker is not on PATH; check mode is host-only"
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || err "image $IMAGE is absent — build it with: make build"
  mkdir -p "$out"
  # CHOWN BACK, for the same reason tools/uber.sh and tools/go_leg_repro.sh do
  # it: the image runs as root, so everything the leg writes into the bind mount
  # lands root-owned and the invoking user cannot then delete it. Left unhandled
  # that surfaces as a cleanup failure whose exit status REPLACES the verdict.
  # Generation and chown are one payload so a failure of either is seen.
  local payload
  payload="$TYPESCRIPT_VALIDATED_SCRIPT
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
    || err "the typescript-validated leg itself failed inside $IMAGE; re-run without redirection to see it"
}

# ── comparison ───────────────────────────────────────────────────────────────
#
# compare <committed-dir> <fresh-dir> <declared-pin>; echoes its findings,
# returns 0/1/2. Four clauses, and only three of them are verdicts:
#
#   CONTENT  a generated file differs from the committed one            → FAIL
#   ABSENT   a generated file is missing from the committed tree        → FAIL
#   PIN      the emitted stamp disagrees with the declared pin          → FAIL
#   OBSERVED a committed path this leg does not produce                 → named
#
# THE PIN CLAUSE IS THE ONE THAT IS NOT REDUNDANT WITH A BYTE DIFF, and that is
# why it is here rather than left implicit. Byte-identity alone cannot see a
# tree that was generated by a STALE image and then compared against that same
# stale image — a warm developer image built from older pins, which is exactly
# the condition go_leg_repro.sh names as its own subject. Both trees then agree
# with each other and disagree with Dockerfile.base. Reading the stamp out of
# the FRESH output and comparing it against the DECLARED pin is the only clause
# that fires on it, and the canary drives it alone, with CONTENT green.
#
# THE ASYMMETRY ON `extra` IS DELIBERATE, and is the same call go_leg_repro.sh
# makes. A generated path MISSING from the committed tree is a verdict: the leg
# produces it and the tree does not carry it. A path in the committed tree that
# the leg does NOT produce is a question about the TREE rather than the LEG, and
# it already has a home — tools/orphan_scan.sh runs every leg generate-protos.sh
# declares (typescript-validated included, since it derives them from the LANGS
# array) and makes exactly that call, wired into build-and-release.yml. Folding
# it in here would put a tree verdict inside a leg check and give one question
# two homes. Extras are counted and named on every run so they cannot go
# unnoticed; read the OBSERVED list as a pointer at that gate, never a finding.
#
# ONE DIAGNOSTIC IS NOT A CLAUSE, AND THE GAP IS NAMED RATHER THAN FAKED. The
# hazard Dockerfile.base's comment describes — the committed directory carrying
# TWO generator stamps at once — cannot fail INDEPENDENTLY here: one generation
# run emits one version, so a split committed tree necessarily differs from the
# fresh one and CONTENT fires first. A clause no input can reach alone is
# unattributable, and gate-enforcement.md §2 refuses one. So the committed
# stamp census is REPORTED on every run as a diagnostic that sharpens a CONTENT
# red into "the tree carries N versions", and it is never a verdict of its own.
compare() {
  local committed="$1" fresh="$2" pin="$3" rc=0
  local -a differing=() missing=() extra=()

  # ONE ENUMERATION FEEDS BOTH THE FLOOR AND THE COMPARISON. Splitting them is a
  # false-green generator rather than a style wart: a process substitution runs
  # in a subshell whose failure `set -euo pipefail` cannot observe, so a dead
  # enumeration leaves the loops reading zero lines and every finding array
  # empty, while a floor computed by a DIFFERENT command still reports a healthy
  # count and waves it through. A floor can only floor the thing it counts.
  local -a fresh_list=() committed_list=()
  mapfile -t fresh_list < <(cd "$fresh" && find . -type f | sed 's|^\./||' | sort)
  mapfile -t committed_list < <(cd "$committed" && find . -type f | sed 's|^\./||' | sort)
  local n_fresh="${#fresh_list[@]}" n_committed="${#committed_list[@]}"
  [ "$n_fresh" -gt 0 ] || { red "[ts-validated-repro] ERROR — enumerated ZERO generated files; that is a broken run or a broken enumeration, never a clean tree" >&2; return 2; }
  [ "$n_committed" -gt 0 ] || { red "[ts-validated-repro] ERROR — enumerated ZERO files in the committed tree; discovery broke" >&2; return 2; }

  # THE STAMP FLOOR. The PIN clause's population is the stamped files, and its
  # pass value over an empty population is indistinguishable from a match — no
  # stamps, no disagreement. Floor it separately from the file count above,
  # because a tree full of files none of which carries a stamp satisfies that
  # one completely.
  local -a fresh_stamps=()
  mapfile -t fresh_stamps < <(emitted_stamps "$fresh")
  if [ "${#fresh_stamps[@]}" -eq 0 ]; then
    red "[ts-validated-repro] ERROR — the fresh output carries ZERO protoc-gen-es stamps, so the PIN clause has nothing to judge" >&2
    printf '  Either the leg emitted no *_pb.ts, or the generator stopped writing its\n' >&2
    printf '  version banner. Both make a green here a statement about nothing.\n' >&2
    return 2
  fi
  if [ "${#fresh_stamps[@]}" -gt 1 ]; then
    red "[ts-validated-repro] ERROR — the fresh output carries ${#fresh_stamps[@]} DISTINCT generator stamps: ${fresh_stamps[*]}" >&2
    printf '  One generation run emits one version, so this is a broken run or a broken\n' >&2
    printf '  census, not a verdict about the committed tree.\n' >&2
    return 2
  fi

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

  local -a committed_stamps=()
  mapfile -t committed_stamps < <(emitted_stamps "$committed")

  printf '[ts-validated-repro] image=%s  generated=%s file(s)  committed=%s file(s)\n' \
    "$IMAGE" "$n_fresh" "$n_committed"
  printf '[ts-validated-repro] declared pin=%s  emitted stamp=%s  committed stamp(s)=%s\n' \
    "$pin" "${fresh_stamps[0]}" "${committed_stamps[*]:-none}"
  if [ "${#committed_stamps[@]}" -gt 1 ]; then
    red "[ts-validated-repro] DIAGNOSTIC — the committed tree carries ${#committed_stamps[@]} generator versions at once (${committed_stamps[*]}); the CONTENT clause below is what refuses it"
  fi

  if [ "${#differing[@]}" -gt 0 ]; then
    red "[ts-validated-repro] FAIL — ${#differing[@]} generated file(s) differ in CONTENT from the committed tree:"
    printf '    %s\n' "${differing[@]}"
    rc=1
  fi
  if [ "${#missing[@]}" -gt 0 ]; then
    red "[ts-validated-repro] FAIL — ${#missing[@]} generated file(s) are ABSENT from the committed tree:"
    printf '    %s\n' "${missing[@]}"
    rc=1
  fi
  if [ "${fresh_stamps[0]}" != "$pin" ]; then
    red "[ts-validated-repro] FAIL — PIN mismatch: Dockerfile.base pins $pin, the leg emitted ${fresh_stamps[0]}."
    printf '    The image was built from a different Dockerfile.base than this checkout\n'
    printf '    carries, or the pin was bumped without regenerating. Bumping a generator\n'
    printf '    that stamps itself IS a regeneration: rebuild the image and land the\n'
    printf '    regenerated output/typescript-validated with the bump.\n'
    rc=1
  fi
  if [ "${#extra[@]}" -gt 0 ]; then
    printf '[ts-validated-repro] OBSERVED — %s committed path(s) this leg does NOT produce (not a verdict; see the header):\n' "${#extra[@]}"
    printf '    %s\n' "${extra[@]}"
  fi
  # An `if`, not `cond && green …`. As a trailing `&&` list this is correct only
  # because every caller wraps the call in `set +e`; under errexit the list would
  # abort the function and the status would happen to equal $rc for the one value
  # that can reach here. A contract that holds by coincidence is not a contract.
  if [ "$rc" -eq 0 ]; then
    green "[ts-validated-repro] OK — all $n_fresh generated file(s) byte-identical to output/typescript-validated, stamped $pin"
  fi
  return "$rc"
}

# ── canary ───────────────────────────────────────────────────────────────────
#
# Proves this check can go RED, and that each red is attributable to the clause
# under test. HERMETIC: every planted input is a synthetic tree under a mktemp
# directory, so a canary run never touches output/, works on a dirty tree, and
# — unlike its go-leg sibling — needs neither docker nor the generator image.
# That is what lets it ride `lint` while the check itself cannot.
#
# The one thing it therefore does NOT prove is that generation works; that is a
# precondition of check mode, not a clause, and it is judged by running the
# check. Said out loud so a green canary is not read as a green check.
canary() {
  local work rc pass=0 fail=0 out
  work="$(mktemp -d)"
  # shellcheck disable=SC2064  # expand $work now: it must survive this function
  trap "rm -rf '$work'" RETURN

  assert_rc() {
    local want="$1" got="$2" what="$3"
    if [ "$got" -eq "$want" ]; then green "  ok   $what (exit $got)"; pass=$((pass + 1));
    else red "  FAIL $what — expected exit $want, got $got"; fail=$((fail + 1)); fi
  }
  assert_says() {
    local hay="$1" needle="$2" what="$3"
    if grep -q -- "$needle" <<<"$hay"; then green "  ok   $what"; pass=$((pass + 1));
    else red "  FAIL $what — the run never said '$needle'"; fail=$((fail + 1)); fi
  }
  assert_silent() {
    local hay="$1" needle="$2" what="$3"
    if grep -q -- "$needle" <<<"$hay"; then red "  FAIL $what — the run DID say '$needle'"; fail=$((fail + 1));
    else green "  ok   $what"; pass=$((pass + 1)); fi
  }

  # A synthetic leg output: N stamped files plus the package.json the real leg
  # writes. Small on purpose — the clauses are what is under test, not protoc.
  local pin="9.9.9"
  local base="$work/base"
  mkdir -p "$base/opaque"
  local f
  for f in alpha beta; do
    printf '// @generated by protoc-gen-es v%s with parameter "target=ts"\nexport const %s = 1;\n' \
      "$pin" "$f" >"$base/${f}_pb.ts"
  done
  printf '// @generated by protoc-gen-es v%s with parameter "target=ts"\nexport const gamma = 1;\n' \
    "$pin" >"$base/opaque/gamma_pb.ts"
  printf '{"name":"fixture"}\n' >"$base/package.json"

  # 1. CONTROL: an exact copy must pass, and the PIN clause must be satisfied by
  #    a matching declared pin. Without this the suite cannot tell a working
  #    check from one that fails on everything.
  cp -r "$base" "$work/control"
  set +e; compare "$work/control" "$base" "$pin" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 0 "$rc" "control: an identical tree at the declared pin is a PASS"

  # 2. A planted CONTENT difference must be a FAIL, attributed to the content
  #    clause and NOT to the pin clause — the two would otherwise be
  #    indistinguishable to a reader of the exit code.
  cp -r "$base" "$work/mutated"
  local victim="alpha_pb.ts"
  printf '\n// planted by ts_validated_repro --canary\n' >>"$work/mutated/$victim"
  # PROOF THE MUTATION LANDED — a no-op mutation yields a green that reads as
  # attribution while proving the exact opposite.
  grep -q 'planted by ts_validated_repro' "$work/mutated/$victim" \
    || { red "  FAIL canary fixture: the mutation did not land in $victim"; return 1; }
  cmp -s "$work/mutated/$victim" "$base/$victim" \
    && { red "  FAIL canary fixture: mutant is byte-identical to the original"; return 1; }
  set +e; out="$(compare "$work/mutated" "$base" "$pin" 2>&1)"; rc=$?; set -e
  assert_rc 1 "$rc" "planted content difference is a FAIL"
  assert_says "$out" 'differ in CONTENT' "attributed to the CONTENT clause"
  assert_silent "$out" 'PIN mismatch' "the PIN clause stayed green on a content-only defect"

  # 3. A generated file ABSENT from the committed tree must be a FAIL, from its
  #    own clause — a neighbouring clause refusing the same input proves nothing.
  cp -r "$base" "$work/reduced"
  rm "$work/reduced/$victim"
  [ ! -e "$work/reduced/$victim" ] || { red "  FAIL canary fixture: the deletion did not land"; return 1; }
  set +e; out="$(compare "$work/reduced" "$base" "$pin" 2>&1)"; rc=$?; set -e
  assert_rc 1 "$rc" "generated file absent from the committed tree is a FAIL"
  assert_says "$out" 'ABSENT from the committed tree' "attributed to the ABSENT clause"

  # 4. THE PIN CLAUSE, ALONE. Content is byte-identical in both directions — the
  #    fixture is the CONTROL tree — and only the declared pin disagrees with the
  #    emitted stamp. This is the case byte-identity structurally cannot reach: a
  #    tree generated by a stale image and compared against that same stale image.
  set +e; out="$(compare "$work/control" "$base" "8.8.8" 2>&1)"; rc=$?; set -e
  assert_rc 1 "$rc" "a stamp that disagrees with the declared pin is a FAIL"
  assert_says "$out" 'PIN mismatch' "attributed to the PIN clause"
  assert_silent "$out" 'differ in CONTENT' "the CONTENT clause stayed green on a pin-only defect"
  assert_silent "$out" 'ABSENT from the committed' "the ABSENT clause stayed green on a pin-only defect"

  # 5. An extra committed path is OBSERVED, never a verdict. This is the clause
  #    most likely to be wrong by accident, so it is asserted in both halves:
  #    the exit stays 0 AND the path is still named.
  cp -r "$base" "$work/extra"
  printf 'orphan\n' >"$work/extra/ORPHAN_FIXTURE_pb.ts.bak"
  set +e; out="$(compare "$work/extra" "$base" "$pin" 2>&1)"; rc=$?; set -e
  assert_rc 0 "$rc" "an extra committed path does NOT flip the verdict"
  assert_says "$out" 'ORPHAN_FIXTURE' "the extra path is still NAMED"

  # 6. EMPTY DISCOVERY IS AN ERROR, NEVER A PASS — in both directions, because
  #    an empty committed tree and an empty generation fail identically to a
  #    union floor and are different defects.
  mkdir -p "$work/empty-committed"
  set +e; compare "$work/empty-committed" "$base" "$pin" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "empty committed tree is an ERROR"
  mkdir -p "$work/empty-fresh"
  set +e; compare "$work/control" "$work/empty-fresh" "$pin" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "empty generation is an ERROR"

  # 7. THE STAMP FLOOR, WHICH THE FILE-COUNT FLOOR CANNOT COVER. A fresh tree
  #    that is fully populated but carries no stamp leaves the PIN clause with an
  #    empty population, whose pass value equals its nothing-ran value.
  cp -r "$base" "$work/unstamped"
  sed -i 's|^// @generated by protoc-gen-es .*|// no banner|' "$work/unstamped"/*_pb.ts "$work/unstamped"/opaque/*_pb.ts
  [ -z "$(emitted_stamps "$work/unstamped")" ] \
    || { red "  FAIL canary fixture: the unstamped tree still carries a stamp"; return 1; }
  [ "$(find "$work/unstamped" -type f | wc -l)" -gt 0 ] \
    || { red "  FAIL canary fixture: the unstamped tree is empty, which tests the wrong floor"; return 1; }
  set +e; compare "$work/control" "$work/unstamped" "$pin" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "a populated but UNSTAMPED generation is an ERROR, not a pin match"

  # 8. A SPLIT fresh tree is an ERROR rather than a verdict: one run emits one
  #    version, so two means the run or the census is broken.
  cp -r "$base" "$work/split"
  sed -i 's|protoc-gen-es v9.9.9|protoc-gen-es v7.7.7|' "$work/split/beta_pb.ts"
  grep -q 'protoc-gen-es v7.7.7' "$work/split/beta_pb.ts" \
    || { red "  FAIL canary fixture: the split mutation did not land"; return 1; }
  set +e; compare "$work/control" "$work/split" "$pin" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "a fresh tree carrying two stamps is an ERROR"

  # 9. A BROKEN ENUMERATION IS AN ERROR, NEVER A GREEN. Regression guard over the
  #    defect its go-leg sibling actually shipped: while the floor was counted by
  #    a different command than the loops enumerated with, a dead enumeration
  #    printed the byte-identical OK line and exited 0 over a tree that differed.
  #    `$work/mutated` genuinely differs from $base, so a green here would be a
  #    FALSE green rather than an accidentally-correct one.
  mkdir -p "$work/shim"
  printf '#!/bin/sh\nexit 1\n' >"$work/shim/sed"
  chmod +x "$work/shim/sed"
  set +e; (PATH="$work/shim:$PATH"; compare "$work/mutated" "$base" "$pin") >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "a broken enumeration is an ERROR, not a green over a differing tree"

  # 10. THE PIN READER. "No pin found" must never read as "the pin matches", so
  #     a Dockerfile with the install line reshaped is an ERROR.
  printf 'RUN npm install -g ts-proto lv_font_conv@1.5.2\n' >"$work/nopin.Dockerfile"
  set +e; declared_pin "$work/nopin.Dockerfile" >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "a Dockerfile with no protoc-gen-es pin is an ERROR"
  local real_pin
  set +e; real_pin="$(declared_pin "$PIN_FILE" 2>/dev/null)"; rc=$?; set -e
  assert_rc 0 "$rc" "control: the real Dockerfile.base still yields a pin"
  if [ -n "$real_pin" ]; then green "  ok   the real pin reads as $real_pin"; pass=$((pass + 1));
  else red "  FAIL the real Dockerfile.base yielded an EMPTY pin"; fail=$((fail + 1)); fi

  # 11. THE PAYLOAD EXTRACTOR. A renamed or reflowed assignment must be an ERROR
  #     — the shape that would otherwise let this check silently generate
  #     nothing. Driven against a COPY, so the real script is never touched.
  sed 's|^TYPESCRIPT_VALIDATED_SCRIPT=|TS_VALIDATED_RENAMED=|' \
    "$ROOT/generate-protos.sh" >"$work/renamed.sh"
  grep -q '^TS_VALIDATED_RENAMED=' "$work/renamed.sh" \
    || { red "  FAIL canary fixture: the rename did not land"; return 1; }
  grep -q "^TYPESCRIPT_VALIDATED_SCRIPT='\$" "$work/renamed.sh" \
    && { red "  FAIL canary fixture: the original assignment survived the rename"; return 1; }
  set +e; (extract_payload "$work/renamed.sh") >/dev/null 2>&1; rc=$?; set -e
  assert_rc 2 "$rc" "a renamed payload assignment is an ERROR, not an empty generation"
  set +e; (extract_payload "$ROOT/generate-protos.sh") >/dev/null 2>&1; rc=$?; set -e
  assert_rc 0 "$rc" "control: the real generate-protos.sh still yields the leg"

  printf '\n'
  if [ "$fail" -eq 0 ]; then green "[ts-validated-repro canary] ALL GREEN — $pass assertion(s)"; return 0; fi
  red "[ts-validated-repro canary] $fail assertion(s) FAILED, $pass passed"
  return 1
}

# ── main ─────────────────────────────────────────────────────────────────────

if [ "$MODE" = "canary" ]; then
  canary
  exit $?
fi

[ -d "$COMMITTED" ] || err "no committed tree at $COMMITTED"
extract_payload || exit $?
PIN="$(declared_pin)" || exit $?

FRESH="$(mktemp -d)"
earned=0
# THE TRAP MUST NOT REWRITE THE VERDICT. A bare `trap rm -rf ... EXIT` hands the
# cleanup command's status to the caller, so a tree this script cannot delete
# reports as a FAIL of the comparison. Save the real status, report a cleanup
# failure LOUDLY, and exit with the status that was actually earned — the same
# split tools/uber.sh makes between "the command's verdict" and "ownership went
# wrong". Neither is allowed to impersonate the other.
trap 'earned=$?; rm -rf "$FRESH" \
        || printf "ts-validated-repro: WARNING — could not remove %s (verdict unaffected: %s)\n" "$FRESH" "$earned" >&2
      exit "$earned"' EXIT
generate_into "$FRESH"

set +e
compare "$COMMITTED" "$FRESH" "$PIN"
rc=$?
set -e
exit "$rc"
