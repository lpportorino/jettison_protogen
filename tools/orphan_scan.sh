#!/usr/bin/env bash
# tools/orphan_scan.sh — a COMMITTED generated file that NO generation leg
# produces any more is an ORPHAN. This is the check that says so.
#
# WHY THIS EXISTS. Generation never deletes. Every leg mounts its own
# output/<lang> read-write and writes into it, so a file whose producer stopped
# emitting it stays in the tree for ever — and ten consumer repositories keep
# vendoring it, because the fan-out copies whatever output/ holds. Nothing else
# looks in that direction: go_leg_repro.sh judges the LEG (are the committed
# bytes what the leg produces today) and says outright that a committed path the
# leg does not produce is not its verdict to make. That is its scope, honestly
# stated, not a gap in it. This check judges the TREE.
#
# THREE MECHANISMS PRODUCE ONE, AND THEY ARE NOT EQUALLY VISIBLE:
#   1. the declaring .proto was DELETED         — the easy majority;
#   2. an output PATH MOVED and the old one stayed, while the declaring .proto
#      is still ALIVE at its original path — a go_package edit does exactly
#      this, and every generated file still carries a `source:` header naming a
#      live proto, so a declared-source scan reports it CLEAN;
#   3. a MESSAGE was removed from a still-live .proto, stranding one file of a
#      PER-MESSAGE emitter (the kotlin leg writes <Message>Kt.kt) — invisible to
#      anything short of running the generator.
#
# A DECLARED-SOURCE SCAN FINDS ONLY THE FIRST, so this check does not do one. It
# RUNS EVERY LEG into a throwaway directory and asks one question of each
# committed path: did the producer produce it? That question is
# mechanism-agnostic, which is why it is sound for all three at once — the cause
# never has to be inferred, and a fourth mechanism nobody has thought of is
# covered by the same comparison. The canary plants one REAL instance per
# mechanism in a mutated COPY of proto/ and watches this check name the file
# each one strands.
#
# WHAT IT DOES NOT COVER, stated plainly because an overstated scope is worse
# than no check at all:
#   - only output/<lang>, for the langs generate-protos.sh declares. Any OTHER
#     tracked directory under output/ must be classified in OUT_OF_SCOPE_DIRS
#     below or this check ERRORs; it never walks past one in silence.
#   - docs/proto/**, output/manifests/** and renderer/generated/** have their own
#     producers and their own freshness lanes. They are outside this check.
#   - a PARTIAL generation inflates the orphan list. That is the safe direction —
#     a false RED, never a false green — and each payload re-arms `set -euo
#     pipefail`, so a leg that dies is reported as an ERROR rather than as a
#     short produced set that would read as a pile of orphans.
#   - it cannot tell an orphan from a regeneration that is merely PENDING. Run it
#     on a tree whose bindings are in sync with proto/, which is exactly where it
#     is wired: immediately after `make generate`, before the first consumer push.
#
# ITS VACUITY PROFILE IS THE OPPOSITE OF go_leg_repro's, and that is what makes
# it wirable where that one is deliberately not. Byte-identity AFTER `make
# generate` is vacuous (generate has just written those bytes) and BEFORE it is
# wrong (the release job exists to regenerate). Orphan-hood is neither: generate
# never deletes, so an orphan survives a full regeneration untouched and is
# still an orphan afterwards. After generate is the only place it is honest, and
# it is also the last place before the bindings reach a consumer.
#
# HOST-ONLY. It drives `docker run`, and the toolchain image ships no docker
# CLI, so this cannot run inside tools/uber.sh.
#
# It NEVER writes into output/ — every leg generates into a mktemp tree removed
# on exit.
#
# EXIT CODES separate a verdict from a precondition failure, so the canary can
# assert which one happened:
#   0  no un-allowlisted orphan in any leg
#   1  FAIL — an orphan, or a STALE allowlist / out-of-scope entry
#   2  ERROR — could not run, or a discovery came back empty (never a pass)
#
# usage:
#   tools/orphan_scan.sh              # run every leg, compare, verdict
#   tools/orphan_scan.sh --image IMG  # against a specific generator image
#   tools/orphan_scan.sh --canary     # prove this check can FAIL (see below)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="jettison-proto-generator:latest"
MODE="check"

while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:?--image needs a value}"; shift 2 ;;
    --canary) MODE="canary"; shift ;;
    -h|--help) sed -n '2,71p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'orphan-scan: unknown argument %s\n' "$1" >&2; exit 2 ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
err()   { red "[orphan-scan] ERROR — $*" >&2; exit 2; }

# A BARE `git`, never `git -C`: lint.mk's lint-sh lane rejects `git -C` in any
# tracked script, because tools/uber.sh exports GIT_DIR/GIT_WORK_TREE and GIT_DIR
# BEATS -C — a `-C` there answers about the wrong repository while looking
# authoritative. Guard the cd ON ITS OWN LINE; a compound `cd X && cmd` runs a
# chained fallback in the CALLING directory when the cd fails.
cd "$ROOT" || err "cannot enter the repository root"

# ── per-leg NETWORK POLICY — measured, not assumed ────────────────────────────
#
# Every leg runs with `--network none` unless named here. Offline is the default
# because an offline leg cannot fail for a reason unrelated to the protos. The
# two exceptions genuinely fetch at generation time, and pretending otherwise
# would either fail the run or quietly drop a leg from the population:
#
#   typescript  — the payload runs `npm install ts-proto`. It DOES complete
#                 offline from the image's warm npm cache, but pays minutes of
#                 registry retries to do it, against seconds online, for the
#                 same file set. Offline here buys nothing and costs the run.
#   rust        — the payload runs `cargo build` against the crates registry for
#                 prost and prost-build. Offline it dies resolving the index;
#                 there is no vendored copy in the image to fall back on.
#
# THIS IS NOT A SCOPE REDUCTION. Both legs still RUN and are still judged; the
# list only decides whether their container gets a network namespace. A leg that
# fails for ANY reason, network included, is an ERROR — never a leg silently
# dropped, which is the one outcome that would make a green here dishonest.
NETWORK_LEGS=(typescript rust)

# ── non-leg directories under output/ ─────────────────────────────────────────
#
# output/ is not exclusively the language legs' territory. Anything tracked
# there whose top-level directory is neither a declared lang nor listed here is
# an ERROR: this check refuses to walk past a generated tree it has no producer
# for, because "not covered" and "covered and clean" print the same thing. A
# STALE entry — one naming a directory that is no longer tracked — is a FAIL,
# which is what keeps this list ratcheting DOWN.
#
# ENTRY: manifests
#   :rationale     output/manifests/ is written by the protodoc docs flow
#                  (`make docs-docker-manifests`) from the committed
#                  proto-db.edn, not by any leg of generate-protos.sh. No
#                  language leg produces any of it, so folding it in would
#                  report every file it holds as an orphan.
#   :retires-when  the docs flow grows reproduction tooling of its own, at which
#                  point this check gains a manifests leg and the entry goes.
#   :owner         gate-port
#   :expires       2026-11-05
OUT_OF_SCOPE_DIRS=(manifests)

# ── allowlist — a committed path legitimately produced by no LEG ───────────────
#
# Format: <lang><TAB><path relative to output/<lang>>. An entry excuses exactly
# that one path under that one leg; there is no whole-directory form, on purpose.
# A STALE entry — one excusing a path this run did not find unproduced — is a
# hard FAILURE. That is what makes the list ratchet DOWN: a path cannot be
# deleted, or adopted by a producer, while its excuse stays behind.
#
# ENTRY: go	buf.build/gen/go/bufbuild/protovalidate/protocolbuffers/go/go.mod
# ENTRY: go	git-codecommit.eu-central-1.amazonaws.com/v1/repos/jettison/jonp/go.mod
#   :rationale     Go module manifests, hand-committed rather than generated:
#                  `buf generate` emits .pb.go files and no go.mod at all. They
#                  declare the module paths and the protobuf dependency that make
#                  the emitted packages importable, and the release fan-out
#                  copies output/go/* wholesale into the consumer Go repository,
#                  so deleting them as orphans would leave that repository
#                  unbuildable. Produced by nothing ON PURPOSE, not stranded.
#   :retires-when  the go leg emits its own module manifests — a buf managed-mode
#                  module override, or a generate step that writes them — at
#                  which point they stop being hand-maintained.
#   :owner         gate-port
#   :expires       2026-11-05
#
# ENTRY: typescript	binary_dedup_tags.ts
#   :rationale     Written by the binary-dedup analyzer (`make binary-dedup-run`,
#                  which `make generate` invokes after the legs), from
#                  output/json-descriptors/descriptor-set.json. Its own banner
#                  says AUTO-GENERATED. It has a live producer; that producer is
#                  simply not a language leg, so no leg reproduces it and this
#                  check would otherwise condemn a file being actively written.
#   :retires-when  this check grows a leg for the binary-dedup analyzer, or the
#                  analyzer's output moves out of output/typescript/.
#   :owner         gate-port
#   :expires       2026-11-05
ALLOWLIST=(
  "go	buf.build/gen/go/bufbuild/protovalidate/protocolbuffers/go/go.mod"
  "go	git-codecommit.eu-central-1.amazonaws.com/v1/repos/jettison/jonp/go.mod"
  "typescript	binary_dedup_tags.ts"
)

# ── extraction from generate-protos.sh — ONE HOME, never a retyped copy ───────
#
# The lang list, the lang -> payload-variable dispatch and the payloads all live
# in generate-protos.sh. A copy here would drift and this check would then judge
# the copy, which is the failure mode it exists to exclude. The dispatch is READ
# rather than derived by naming convention, because the convention does not
# hold: `json-descriptors` maps to JSON_DESCRIPTOR_SCRIPT (singular), so a
# `tr a-z- A-Z_` guess silently loses that leg.
GEN_SRC="$ROOT/generate-protos.sh"

# Every extractor below is called through a command substitution, so its `err`
# would exit only the SUBSHELL and leave the caller running with an empty value.
# Each caller therefore floors what it got. That is the same discipline
# go_leg_repro.sh records for its enumeration: a floor can only floor the thing
# it actually reads.
read_langs() {
  [ -f "$GEN_SRC" ] || return 1
  sed -n 's/^LANGS=(\(.*\))$/\1/p' "$GEN_SRC" | tr ' ' '\n' | grep -v '^$'
}

script_var_for() {
  sed -n 's/^ *\([a-z0-9-]*\)) *script="\$\([A-Z_]*\)" *;;/\1 \2/p' "$GEN_SRC" |
    awk -v l="$1" '$1 == l { print $2 }'
}

# Sets PAYLOAD to the leg's own body. Called from a non-subshell context so its
# `err` really does abort the run.
load_payload() {
  local var="$1" block
  block="$(sed -n "/^${var}='\$/,/^'\$/p" "$GEN_SRC")"
  [ -n "$block" ] || err "could not extract the $var assignment from generate-protos.sh"
  eval "$block"
  # NON-VACUITY ON THE PAYLOAD ITSELF. An EVEN number of apostrophes inside a
  # single-quoted assignment rebalances the quoting and yields an EMPTY payload
  # that still parses — see tools/payload_apostrophes.awk. An empty payload
  # generates nothing, and this check would then report every committed file of
  # that leg as an orphan: a red for a reason that has nothing to do with the
  # tree.
  PAYLOAD="${!var:-}"
  [ -n "$PAYLOAD" ] || err "the $var assignment evaluated EMPTY — see tools/payload_apostrophes.awk"
}

# ── generation ────────────────────────────────────────────────────────────────

require_docker() {
  command -v docker >/dev/null 2>&1 \
    || err "docker is not on PATH, and this check is host-only. Install docker, or run it where the release workflow does."
  docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || err "image $IMAGE is absent — build it with: make build"
}

# generate_leg <lang> <proto-dir> <out-dir>
generate_leg() {
  local lang="$1" protodir="$2" out="$3" var payload n
  local -a net_args=(--network none)
  var="$(script_var_for "$lang")"
  [ -n "$var" ] || err "generate-protos.sh declares lang '$lang' but its dispatch names no payload variable for it"
  load_payload "$var"
  for n in "${NETWORK_LEGS[@]}"; do
    if [ "$n" = "$lang" ]; then net_args=(); fi
  done
  mkdir -p "$out"
  # CHOWN BACK, for the reason tools/uber.sh and go_leg_repro.sh both give: the
  # image runs as root, so everything the leg writes into the bind mount lands
  # root-owned and the invoking user cannot then delete it — which surfaces as a
  # cleanup failure whose exit status REPLACES the verdict.
  #
  # `set -euo pipefail` is prepended exactly as generate-protos.sh's
  # run_generation does, so a leg that dies mid-way is seen here the way it is
  # seen there instead of yielding a short produced set that reads as orphans.
  payload="set -euo pipefail
$PAYLOAD
chown -R $(id -u):$(id -g) /workspace/output"
  # Run BARE. A pipeline would report the FILTER's status and a failed
  # generation would read as success — the defect lint.mk's lint-sh header
  # records against the rust leg.
  docker run --rm "${net_args[@]+"${net_args[@]}"}" \
    -v "$protodir:/workspace/proto:ro" \
    -v "$out:/workspace/output:rw" \
    -v "$ROOT/scripts:/workspace/scripts:ro" \
    -w /workspace \
    --entrypoint /bin/bash \
    "$IMAGE" -c "$payload" >/dev/null 2>&1 \
    || err "the $lang leg itself failed inside $IMAGE; re-run that leg without the redirection to see it"
}

# ── the verdict ───────────────────────────────────────────────────────────────
#
# orphan_verdict <lang> <committed-list> <produced-list>
#
# Both populations arrive as newline-separated relative paths. THEY ARE PASSED
# AS DATA rather than re-enumerated inside, so the canary can drive the real
# function with synthetic populations, and so ONE enumeration feeds both the
# floor and the comparison. go_leg_repro.sh records what the split costs: while
# its floor was counted by a different command than its loops enumerated with, a
# dead enumeration printed a healthy count and waved a differing tree through.
#
# Appends every unproduced path to ORPHANS_SEEN *before* the allowlist is
# applied — staleness is judged against the UNFILTERED findings, or every entry
# would look used by construction.
#
# 0 clean, 1 orphan(s), 2 empty population (an ERROR, never a pass).
ORPHANS_SEEN=()
orphan_verdict() {
  local lang="$1" committed_str="$2" produced_str="$3"
  local -a committed=() produced=() orphans=() excused=()
  mapfile -t committed < <(printf '%s\n' "$committed_str" | grep -v '^$' || true)
  mapfile -t produced < <(printf '%s\n' "$produced_str" | grep -v '^$' || true)

  # FLOOR EACH SIDE INDIVIDUALLY. A union floor is satisfied by whichever side is
  # still alive, so one side going dark would be invisible — and the two are
  # different defects with different diagnoses.
  if [ "${#committed[@]}" -eq 0 ]; then
    red "[orphan-scan] ERROR — $lang: enumerated ZERO committed files under output/$lang. Discovery broke, or this leg's output is untracked; either way it is not a clean tree." >&2
    return 2
  fi
  if [ "${#produced[@]}" -eq 0 ]; then
    red "[orphan-scan] ERROR — $lang: the leg produced ZERO files. That is a broken run or a broken enumeration, never a clean tree." >&2
    return 2
  fi

  local -A made=() have=()
  local p c q entry allowed
  for p in "${produced[@]}"; do made["$p"]=1; done
  for c in "${committed[@]}"; do have["$c"]=1; done

  for c in "${committed[@]}"; do
    if [ -n "${made[$c]:-}" ]; then continue; fi
    allowed=""
    for entry in "${ALLOWLIST[@]}"; do
      if [ "${entry%%$'\t'*}" = "$lang" ] && [ "${entry#*$'\t'}" = "$c" ]; then
        allowed=1
        break
      fi
    done
    ORPHANS_SEEN+=("$lang	$c")
    if [ -n "$allowed" ]; then excused+=("$c"); else orphans+=("$c"); fi
  done

  # Reported as CONTEXT, not as a finding, and deliberately not a verdict: a
  # produced path that is not committed is a FRESHNESS question, owned by
  # `make generate` and the release job's own staging step. Naming the count
  # keeps it from going unnoticed without inventing an advisory tier.
  local uncommitted=0
  for q in "${produced[@]}"; do
    if [ -z "${have[$q]:-}" ]; then uncommitted=$((uncommitted + 1)); fi
  done

  printf '[orphan-scan] %-21s committed=%-5s produced=%-5s orphan=%-3s excused=%-3s (not-committed=%s)\n' \
    "$lang" "${#committed[@]}" "${#produced[@]}" "${#orphans[@]}" "${#excused[@]}" "$uncommitted"

  if [ "${#orphans[@]}" -gt 0 ]; then
    red "[orphan-scan] FAIL — $lang: ${#orphans[@]} committed path(s) that NO leg produces any more:"
    printf "    output/$lang/%s\n" "${orphans[@]}"
    return 1
  fi
  return 0
}

# stale_allowlist_entries — echoes each ALLOWLIST entry that no run of
# orphan_verdict recorded in ORPHANS_SEEN. Split out so the canary can drive the
# REAL predicate over synthetic state rather than re-implementing it.
stale_allowlist_entries() {
  local entry
  for entry in "${ALLOWLIST[@]}"; do
    if ! printf '%s\n' "${ORPHANS_SEEN[@]+"${ORPHANS_SEEN[@]}"}" | grep -qxF -- "$entry"; then
      printf '%s\n' "$entry"
    fi
  done
}

# ── main check ────────────────────────────────────────────────────────────────

run_check() {
  local -a langs=() outdirs=() unclassified=() stale=() stale_scope=() workdirs=()
  local discovery_err d l known lang out committed_str produced_str rc=0 leg_rc

  mapfile -t langs < <(read_langs)
  if [ "${#langs[@]}" -eq 0 ]; then
    red "[orphan-scan] ERROR — extracted ZERO langs from the LANGS assignment in generate-protos.sh." >&2
    printf '  That is a broken extraction, never an empty repo: the assignment is a\n' >&2
    printf '  single line of the form LANGS=(...). If it has been reformatted, this\n' >&2
    printf '  extractor must follow it rather than a copy of the list being kept here.\n' >&2
    return 2
  fi

  # SCOPE, ASSERTED IN BOTH DIRECTIONS. A tracked top-level directory under
  # output/ that is neither a lang nor classified is an ERROR — this check has no
  # producer for it and must not print a clean line over it. A classification
  # naming a directory that is no longer tracked is STALE, and a FAIL.
  #
  # git's stderr is CAPTURED rather than discarded: when discovery fails the
  # reason IS the diagnosis, and guessing at a cause in a static message points
  # the reader at the wrong thing with confidence.
  discovery_err="$(git ls-files -- output 2>&1 >/dev/null)" || true
  mapfile -t outdirs < <(git ls-files -- output 2>/dev/null | cut -d/ -f2 | sort -u)
  if [ "${#outdirs[@]}" -eq 0 ]; then
    red "[orphan-scan] ERROR — enumerated ZERO tracked directories under output/." >&2
    if [ -n "$discovery_err" ]; then printf '  git said: %s\n' "$discovery_err" >&2; fi
    printf '  THE LINE ABOVE IS THE DIAGNOSIS, if there is one. This repo tracks over a\n' >&2
    printf '  thousand files there, so an empty set means DISCOVERY broke, not that\n' >&2
    printf '  there is nothing to check.\n' >&2
    return 2
  fi

  for d in "${outdirs[@]}"; do
    known=""
    for l in "${langs[@]}"; do if [ "$d" = "$l" ]; then known=1; fi; done
    for l in "${OUT_OF_SCOPE_DIRS[@]}"; do if [ "$d" = "$l" ]; then known=1; fi; done
    if [ -z "$known" ]; then unclassified+=("$d"); fi
  done
  for l in "${OUT_OF_SCOPE_DIRS[@]}"; do
    if ! printf '%s\n' "${outdirs[@]}" | grep -qxF -- "$l"; then stale_scope+=("$l"); fi
  done
  if [ "${#unclassified[@]}" -gt 0 ]; then
    red "[orphan-scan] ERROR — ${#unclassified[@]} tracked director(y/ies) under output/ are neither a declared lang nor classified:" >&2
    printf '    output/%s\n' "${unclassified[@]}" >&2
    printf '  Add the lang to generate-protos.sh, or classify the directory in\n' >&2
    printf '  OUT_OF_SCOPE_DIRS with its four proof fields. A generated tree this check\n' >&2
    printf '  has no producer for must not be walked past in silence.\n' >&2
    return 2
  fi

  require_docker

  for lang in "${langs[@]}"; do
    out="$SCRATCH/leg-$lang"
    workdirs+=("$out")
    generate_leg "$lang" "$ROOT/proto" "$out"
    committed_str="$(git ls-files -- "output/$lang" | sed "s|^output/$lang/||")"
    produced_str="$(cd "$out" && find . -type f | sed 's|^\./||' | sort)"
    set +e
    orphan_verdict "$lang" "$committed_str" "$produced_str"
    leg_rc=$?
    set -e
    if [ "$leg_rc" -eq 2 ]; then rc=2; fi
    if [ "$leg_rc" -eq 1 ] && [ "$rc" -ne 2 ]; then rc=1; fi
  done

  mapfile -t stale < <(stale_allowlist_entries)
  if [ "${#stale[@]}" -gt 0 ]; then
    {
      red "[orphan-scan] FAIL — ${#stale[@]} STALE allowlist entr(y/ies):"
      printf '    %s\n' "${stale[@]}"
      printf '  Each excuses a path that every leg produced this run, so it is now\n'
      printf '  widening the unchecked surface for nothing. DELETE the entry from\n'
      printf '  ALLOWLIST in tools/orphan_scan.sh. This is the down-only half of the\n'
      printf '  ratchet: a path cannot be adopted by a producer, or deleted, while its\n'
      printf '  excuse stays behind.\n'
    } >&2
    if [ "$rc" -ne 2 ]; then rc=1; fi
  fi
  if [ "${#stale_scope[@]}" -gt 0 ]; then
    {
      red "[orphan-scan] FAIL — ${#stale_scope[@]} STALE out-of-scope entr(y/ies):"
      printf '    output/%s\n' "${stale_scope[@]}"
      printf '  Each declares a directory under output/ that is no longer tracked.\n'
      printf '  DELETE it from OUT_OF_SCOPE_DIRS in tools/orphan_scan.sh.\n'
    } >&2
    if [ "$rc" -ne 2 ]; then rc=1; fi
  fi

  if [ "$rc" -eq 0 ]; then
    green "[orphan-scan] OK — ${#langs[@]} leg(s) reproduced, no un-allowlisted orphan (${#ALLOWLIST[@]} allowlist entries, ${#OUT_OF_SCOPE_DIRS[@]} directory declared out of scope)."
  fi
  return "$rc"
}

# ── canary ────────────────────────────────────────────────────────────────────
#
# Proves this check can go RED and that each red is attributable to the clause
# under test. It plants ONE REAL INSTANCE PER MECHANISM in a mutated COPY of
# proto/ and runs the real leg over it, so nothing under proto/ or output/ is
# touched and every case works on a dirty checkout.
#
# Each mechanism case is a PAIR: the UNMUTATED leg must NOT already name the
# path the mutation is expected to strand, so the red that follows is
# attributable to the mutation. It is deliberately NOT "the control exits 0" —
# this canary runs where the gate runs, and that is a tree which may legitimately
# be carrying a real orphan at the time; an exit-0 control would then fail for
# the very defect the gate exists to report, and the suite would blame itself.
# Attribution by the NAMED PATH holds either way. The control still refuses an
# exit 2, because a broken harness is not a subject either case can be about.
#
# Every mutation is asserted to have LANDED, in both directions — a mutation
# that matched nothing yields a mutant identical to the original, whose green
# then reads as attribution while proving the opposite.
#
# Every assertion demands an EXACT exit code. A case asserting merely "non-zero"
# accepts a broken harness as proof that a clause fired, and 1 (a verdict) and
# 2 (a precondition failure) are different answers here.
canary() {
  local pass=0 fail=0 rc out committed produced
  local work="$SCRATCH/canary"
  mkdir -p "$work"

  ok()  { green "  ok   $*"; pass=$((pass + 1)); }
  bad() { red   "  FAIL $*"; fail=$((fail + 1)); }
  assert_rc() {
    if [ "$2" -eq "$1" ]; then ok "$3 (exit $2)"; else bad "$3 — expected exit $1, got $2"; fi
  }
  assert_names() {
    if grep -qF -- "$2" <<<"$1"; then ok "$3"; else bad "$3 — the output never names $2"; fi
  }
  assert_silent() {
    if grep -qF -- "$2" <<<"$1"; then bad "$3 — the output names $2"; else ok "$3"; fi
  }

  printf '\n[orphan-scan canary] synthetic populations, through the real predicate\n'

  # CONTROL. Without it the suite cannot tell a working check from one that
  # refuses everything.
  set +e; out="$(ORPHANS_SEEN=(); orphan_verdict fixture $'a.txt\nb.txt\nc.txt' $'a.txt\nb.txt\nc.txt' 2>&1)"; rc=$?; set -e
  assert_rc 0 "$rc" "control: every committed path produced is a PASS"

  set +e; out="$(ORPHANS_SEEN=(); orphan_verdict fixture $'a.txt\nb.txt\nc.txt' $'a.txt\nc.txt' 2>&1)"; rc=$?; set -e
  assert_rc 1 "$rc" "an unproduced committed path is a FAIL"
  assert_names "$out" 'output/fixture/b.txt' "the FAIL names the orphan"

  # THE ALLOWLIST, AND ITS NEIGHBOUR IN ONE INPUT. Two orphans, one excused: the
  # excused one must vanish from the verdict AND the other must still refuse. A
  # case asserting only that the excused path goes green cannot tell a narrow
  # entry from a blanket skip.
  set +e
  out="$(ORPHANS_SEEN=(); ALLOWLIST=("fixture	b.txt"); orphan_verdict fixture $'a.txt\nb.txt\nc.txt' $'a.txt' 2>&1)"
  rc=$?
  set -e
  assert_rc 1 "$rc" "an allowlisted orphan does not excuse its NEIGHBOUR"
  assert_silent "$out" 'output/fixture/b.txt' "the allowlisted path is not reported"
  assert_names "$out" 'output/fixture/c.txt' "the un-allowlisted neighbour still refuses"

  set +e
  out="$(ORPHANS_SEEN=(); ALLOWLIST=("fixture	b.txt"); orphan_verdict fixture $'a.txt\nb.txt' $'a.txt' 2>&1)"
  rc=$?
  set -e
  assert_rc 0 "$rc" "an allowlisted hand-maintained path does NOT red"

  # The entry keys on BOTH halves. An entry for another leg must not excuse the
  # same relative path here, or one entry is a whole-tree skip in a per-leg
  # entry's clothes.
  set +e
  out="$(ORPHANS_SEEN=(); ALLOWLIST=("otherleg	b.txt"); orphan_verdict fixture $'a.txt\nb.txt' $'a.txt' 2>&1)"
  rc=$?
  set -e
  assert_rc 1 "$rc" "an entry for a DIFFERENT leg does not excuse this one"

  # FLOORS, both directions — an empty committed population and an empty produced
  # population fail identically to a union floor and are different defects.
  set +e; out="$(ORPHANS_SEEN=(); orphan_verdict fixture "" $'a.txt' 2>&1)"; rc=$?; set -e
  assert_rc 2 "$rc" "an empty committed population is an ERROR"
  assert_names "$out" 'ZERO committed files' "the ERROR names the committed side"
  set +e; out="$(ORPHANS_SEEN=(); orphan_verdict fixture $'a.txt' "" 2>&1)"; rc=$?; set -e
  assert_rc 2 "$rc" "an empty produced population is an ERROR"
  assert_names "$out" 'produced ZERO files' "the ERROR names the produced side"

  # STALENESS, through the real predicate. Run OUTSIDE a command substitution:
  # ORPHANS_SEEN is accumulated by orphan_verdict, and a subshell would discard
  # it — leaving an assertion that passes because nothing happened.
  ORPHANS_SEEN=()
  set +e; orphan_verdict fixture $'a.txt\nb.txt' $'a.txt\nb.txt' >/dev/null 2>&1; rc=$?; set -e
  assert_rc 0 "$rc" "staleness fixture: a fully-produced leg is clean"
  out="$(ALLOWLIST=("fixture	b.txt"); stale_allowlist_entries)"
  assert_names "$out" 'fixture	b.txt' "an entry excusing a path that WAS produced reads STALE"
  ORPHANS_SEEN=()
  set +e; orphan_verdict fixture $'a.txt\nb.txt' $'a.txt' >/dev/null 2>&1; rc=$?; set -e
  assert_rc 1 "$rc" "staleness fixture: an unproduced path is still a FAIL"
  out="$(ALLOWLIST=("fixture	b.txt"); stale_allowlist_entries)"
  assert_silent "$out" 'fixture	b.txt' "an entry excusing a path that was NOT produced is live"
  ORPHANS_SEEN=()

  # ── the three mechanisms, each a real generation over a mutated proto copy ──
  require_docker
  printf '\n[orphan-scan canary] one real instance per mechanism, through the real legs\n'

  # MECHANISM 1 — the declaring .proto was DELETED. jon_can_stream.proto is
  # imported by nothing, so removing it cannot break the compile for an
  # unrelated reason and turn the expected FAIL into an ERROR.
  m1_delete_proto() {
    rm -f "$1/jon_can_stream.proto"
    if [ -e "$1/jon_can_stream.proto" ]; then red "  m1: the deletion did not land"; return 1; fi
  }

  # MECHANISM 2 — the output PATH MOVED while the declaring .proto stayed ALIVE
  # at its original path. Editing go_package relocates the go leg's output
  # directory; the emitted file still carries a `source:` header naming a live
  # proto, which is precisely why a declared-source scan reports this clean.
  m2_move_output_path() {
    local f="$1/jon_shared_cmd_gps.proto"
    sed -i 's|jonp/cmd/gps"|jonp/cmd/gps_relocated"|' "$f"
    if [ "$(grep -c 'jonp/cmd/gps_relocated"' "$f")" -lt 1 ]; then red "  m2: the new go_package did not land"; return 1; fi
    if [ "$(grep -c 'jonp/cmd/gps"' "$f")" -ne 0 ]; then red "  m2: the old go_package survived"; return 1; fi
    if [ ! -f "$f" ]; then red "  m2: the declaring proto must stay ALIVE for this mechanism"; return 1; fi
  }

  # MECHANISM 3 — a MESSAGE left a still-live .proto, stranding one file of a
  # PER-MESSAGE emitter: the kotlin leg writes <Message>Kt.kt. A rename rather
  # than a deletion because Root's oneof references the type — deleting it would
  # break protoc and yield an ERROR wearing a FAIL's colour.
  m3_remove_message() {
    local f="$1/jon_shared_cmd_gps.proto"
    sed -i 's/GetMeteo/GetMeteoRenamed/g' "$f"
    if [ "$(grep -c 'GetMeteoRenamed' "$f")" -lt 2 ]; then red "  m3: the rename did not land"; return 1; fi
    if [ "$(grep -cE '\bGetMeteo\b' "$f")" -ne 0 ]; then red "  m3: the old message name survived"; return 1; fi
  }

  # mech_case <name> <lang> <expected stranded path> <mutation fn>
  mech_case() {
    local name="$1" lang="$2" expect="$3" mutate="$4"
    local pdir="$work/proto-$name" odir="$work/out-$name" base="$work/base-$lang"
    local cstr

    cstr="$(git ls-files -- "output/$lang" | sed "s|^output/$lang/||")"

    if [ ! -d "$base" ]; then generate_leg "$lang" "$ROOT/proto" "$base"; fi
    set +e
    out="$(ORPHANS_SEEN=(); orphan_verdict "$lang" "$cstr" "$(cd "$base" && find . -type f | sed 's|^\./||' | sort)" 2>&1)"
    rc=$?
    set -e
    if [ "$rc" -eq 2 ]; then bad "$name control: the UNMUTATED $lang leg ERRORed (exit 2)"; else ok "$name control: the UNMUTATED $lang leg ran (exit $rc, not an ERROR)"; fi
    assert_silent "$out" "output/$lang/$expect" "$name control: the UNMUTATED leg does not already strand $expect"

    cp -r "$ROOT/proto" "$pdir"
    if ! "$mutate" "$pdir"; then bad "$name: the mutation did not land"; return 0; fi
    generate_leg "$lang" "$pdir" "$odir"
    set +e
    out="$(ORPHANS_SEEN=(); orphan_verdict "$lang" "$cstr" "$(cd "$odir" && find . -type f | sed 's|^\./||' | sort)" 2>&1)"
    rc=$?
    set -e
    assert_rc 1 "$rc" "$name: the stranded committed file is a FAIL"
    assert_names "$out" "output/$lang/$expect" "$name: the FAIL names $expect"
    assert_silent "$out" 'ERROR' "$name: a FAIL, not an ERROR"
  }

  mech_case m1-proto-deleted   python jon_can_stream_pb2.py m1_delete_proto
  mech_case m2-output-moved    go     'git-codecommit.eu-central-1.amazonaws.com/v1/repos/jettison/jonp/cmd/gps/jon_shared_cmd_gps.pb.go' m2_move_output_path
  mech_case m3-message-removed kotlin cmd/Gps/GetMeteoKt.kt m3_remove_message

  printf '\n'
  if [ "$fail" -eq 0 ]; then green "[orphan-scan canary] ALL GREEN — $pass assertion(s)"; return 0; fi
  red "[orphan-scan canary] $fail assertion(s) FAILED, $pass passed"
  return 1
}

# ── main ──────────────────────────────────────────────────────────────────────

SCRATCH="$(mktemp -d)"
EARNED=0
# THE TRAP MUST NOT REWRITE THE VERDICT. A bare `trap rm -rf ... EXIT` hands the
# cleanup command's status to the caller, so a tree this script cannot delete
# reports as a FAIL of the check. Save the real status, report a cleanup failure
# LOUDLY, and exit with the status that was actually earned — the same split
# tools/uber.sh makes between "the command's verdict" and "ownership went
# wrong". Neither is allowed to impersonate the other.
trap 'EARNED=$?; rm -rf "$SCRATCH" \
        || printf "orphan-scan: WARNING — could not remove %s (verdict unaffected: %s)\n" "$SCRATCH" "$EARNED" >&2
      exit "$EARNED"' EXIT

set +e
if [ "$MODE" = "canary" ]; then canary; else run_check; fi
RC=$?
set -e
exit "$RC"
