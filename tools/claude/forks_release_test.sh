#!/usr/bin/env bash
# forks_release_test.sh — canaries for `tools/claude/forks.sh release`, and
# specifically for the two clauses that decide whether a fork can be DELETED at
# all.
#
# WHAT THESE CANARIES HAVE TO PROVE, and it is more than "it went red".
#
#   A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. release already refuses forks
#   for a reason that would also refuse most of the fixtures below — uncommitted
#   residue — so a red run here is compatible with the clause under test being
#   dead. Each of the TWO CLAUSES under test is therefore run against the real
#   script, where it must FAIL naming its OWN clause, and again against a MUTANT
#   in which that one clause is silenced, where the refusal must either vanish
#   or change its name. A neighbour doing the work shows up as a mutant that
#   refuses for the same reason. The remaining cases here are negatives and
#   controls, which need no mutant: their job is to fail if the predicate is
#   too WIDE, and a mutant only ever makes it narrower.
#
#   THE MUTATION MUST BE PROVEN TO HAVE LANDED. A sed that matched nothing
#   produces a mutant identical to the original, whose result is then read as
#   attribution while proving the exact opposite. Each mutation asserts the new
#   text is present AND the old text is gone AND the mutant still parses.
#
#   FAIL IS NOT ERROR. A mutant that breaks the script's syntax reds every case
#   while executing none of them, and that red says nothing about any clause. So
#   every mutant is `bash -n`-checked before use, and every refusal below asserts
#   the EXACT exit code 1 rather than merely non-zero.
#
#   THE FALSE-POSITIVE FLOOR IS PART OF THE PROOF. A clearability guard that
#   simply refused everything would pass every canary above. Three negatives
#   pin the predicate's edges: a healthy fork, an EMPTY unwritable directory,
#   and an unwritable FILE in a writable directory. Each of the last two is a
#   shape `rm -rf` genuinely clears, measured, and each is the exact false
#   positive a lazier predicate would produce.
#
#   THE HAZARD IS PROBED, NOT ASSUMED. Every permission case here needs this
#   process to actually be refused by mode bits. Run as root — or on a
#   filesystem that ignores them — `chmod 555` blocks nothing, `rm -rf`
#   succeeds, and the canaries would go GREEN having tested nothing at all.
#   `hazard_is_real` MEASURES that precondition instead of inferring it from
#   the uid, and when it does not hold the affected cases are reported UNJUDGED
#   and the suite refuses to print ALL GREEN.
#
# WHY chmod STANDS IN FOR A ROOT-OWNED CONTAINER ARTEFACT. The production defect
# is a directory a toolchain container created in a bind mount, left root-owned
# on the host. What refuses the unlink is not the ownership but the mode bits
# the caller does not hold: a root-owned directory at 755 and a self-owned one
# at 555 both return EACCES to uid != 0, with the identical
# "cannot remove ...: Permission denied". Both were measured; the chmod form is
# the one a canary can stage without docker and without root, which is what lets
# this ride `lint` on any host.
#
# Usage: tools/claude/forks_release_test.sh
set -euof pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/forks.sh"
BRIEF_CHECK="$SCRIPT_DIR/brief-check.sh"

# THE SCRIPT UNDER TEST IS RUN FROM A COPY SITED IN THE SYNTHETIC ROOT, never
# from $SUT in place. forks.sh derives ROOT as `<its own dir>/../..` and CLONES
# that root for every claim, so running it in place made each fixture a clone of
# the real repository and left build_root decorative. Three assertions below
# silently stopped meaning what they said, and each went GREEN anyway:
#   - "modified tracked file" appended to tracked.txt, which the real repo does
#     not have, so it staged UNTRACKED residue while claiming a TRACKED change;
#   - the ignored-path control passed on the real repo's `*.bak` rule rather
#     than the `/buildout/` rule it was written to exercise — a green attributed
#     to the wrong clause is the same defect as a red attributed to one;
#   - and under root the real repo's ownership tripped git's dubious-ownership
#     refusal, turning fixtures into ERRORs.
# The copy is byte-identical to $SUT (build_root puts it there), so this changes
# what the script can SEE and nothing about what it IS.
SUT_SITED=""

WORK="$(mktemp -d "${TMPDIR:-/tmp}/forks-release-test.XXXXXX")"
# Deliberately chmod-then-remove: this suite plants directories it has made
# unwritable on purpose, and a plain `rm -rf` would leak every one of them into
# the temp filesystem — the very failure under test, committed by the test.
trap 'chmod -R u+rwX -- "$WORK" 2>/dev/null || true; rm -rf -- "$WORK"' EXIT

PASS=0
FAIL=0
UNJUDGED=0

ok() {
  PASS=$((PASS + 1))
  printf '  \033[32mok\033[0m   %s\n' "$*"
}

bad() {
  FAIL=$((FAIL + 1))
  printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2
}

unjudged() {
  UNJUDGED=$((UNJUDGED + 1))
  printf '  \033[33mUNJUDGED\033[0m %s\n' "$*" >&2
}

banner() { printf '\n== %s\n' "$*"; }

git_at() {
  local repo="$1"
  shift
  env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"
}

# ---------------------------------------------------------------------------
# Does an unwritable directory actually refuse THIS process? Measured, never
# inferred from $EUID: root is the obvious way for the answer to be "no", but a
# filesystem mounted without permission enforcement gets there too, and both
# would otherwise hand this suite a green over zero coverage.
# ---------------------------------------------------------------------------
hazard_is_real() {
  local d="$WORK/hazard-probe"
  rm -rf -- "$d" 2>/dev/null || true
  mkdir -p "$d/bad"
  : > "$d/bad/entry"
  chmod 555 "$d/bad"
  local removed=0
  rm -rf -- "$d" 2>/dev/null && removed=1
  chmod -R u+rwX -- "$d" 2>/dev/null || true
  rm -rf -- "$d" 2>/dev/null || true
  [ "$removed" -eq 0 ]
}

HAZARD=1
if hazard_is_real; then
  HAZARD=1
else
  HAZARD=0
fi

# ---------------------------------------------------------------------------
# A synthetic base tree — small enough that every expectation below is decidable
# by reading this function, rather than by trusting the real repository to hold
# still under the suite.
# ---------------------------------------------------------------------------
ROOT="$WORK/root"
build_root() {
  mkdir -p "$ROOT/tools/claude" "$ROOT/docs"
  cp -- "$SUT" "$ROOT/tools/claude/forks.sh"
  cp -- "$BRIEF_CHECK" "$ROOT/tools/claude/brief-check.sh"
  chmod +x "$ROOT/tools/claude/forks.sh" "$ROOT/tools/claude/brief-check.sh"
  printf '# docs\n' > "$ROOT/docs/y.md"
  printf 'tracked\n' > "$ROOT/tracked.txt"
  # A real fork is a clone of this repository and therefore always ships
  # tools/uber.sh, which is the remedy the refusal prefers. Only its presence
  # and executability are consulted, never its contents, so a stub is a faithful
  # fixture — and it keeps this suite from ever launching a container.
  printf '#!/usr/bin/env bash\n# stub: forks.sh tests for -x, never runs it\nexit 0\n' \
    > "$ROOT/tools/uber.sh"
  chmod +x "$ROOT/tools/uber.sh"
  # `/buildout/` is IGNORED on purpose. It is how this suite reaches the half of
  # the fork the residue check cannot see: ignored paths never appear in
  # `git status --untracked-files=all`, so an unwritable directory there is
  # invisible to every pre-existing gate and lands squarely on the FINAL
  # `rm -rf "$fork"`.
  printf '.fork-scratch/\n/buildout/\n' > "$ROOT/.gitignore"
  git_at "$ROOT" init --quiet -b master
  git_at "$ROOT" add -A
  git_at "$ROOT" -c user.name=t -c user.email=t@localhost commit --quiet -m base
}

# EVERY FIXTURE NEEDS A WHOLLY DISJOINT OWNED SET. A refused fork stays OWNED,
# and claim runs brief-check against every still-OWNED sibling — so a brief
# reused across fixtures, or even one shared path in otherwise different briefs,
# makes each later fixture die on `sibling-owned-overlap` instead of testing
# anything. Both happened here: first an identical brief, then a common
# `FINAL_REPORT.md` line that every fixture had copied. The gate is doing
# exactly its job in both cases; the fixtures were the defect. So the owned set
# is ONE path and it carries the fixture's number.
write_brief() {
  local n="$1"
  BRIEF="$WORK/brief$n.md"
  cat > "$BRIEF" <<EOF
# BRIEF
Harden the lifecycle gate.

## FILES YOU OWN
  tools/claude/gen$n.sh   (NEW)
EOF
}
BRIEF=""

# ---------------------------------------------------------------------------
# Runner + fixtures
# ---------------------------------------------------------------------------
RUN_OUT=""
RUN_CODE=0
FORK=""
N=0

# claim_fork <sut> — claims a fresh fork from $ROOT and sets FORK.
claim_fork() {
  local sut="$1" out code=0
  N=$((N + 1))
  FORK="$WORK/fork$N"
  write_brief "$N"
  out="$("$sut" claim "$FORK" "t$N" tester "$BRIEF" 2>&1)" || code=$?
  if [ "$code" -ne 0 ]; then
    bad "fixture setup: claim of $FORK failed (exit=$code)"
    printf '%s\n' "$out" | sed 's/^/       /' >&2
    return 1
  fi
  mkdir -p "$FORK/.fork-scratch"
  return 0
}

# run_release <sut> <fork>
run_release() {
  local sut="$1" fork="$2"
  RUN_CODE=0
  RUN_OUT="$("$sut" release "$fork" --owner-signalled done 2>&1)" || RUN_CODE=$?
}

# plant_unwritable_dir <fork> <relative-dir>  — non-empty, mode 555.
# Mode 555 mirrors the measured production shape: a root-owned directory at 755
# is r-x to uid != 0, which is exactly r-x here.
plant_unwritable_dir() {
  local fork="$1" rel="$2"
  mkdir -p "$fork/$rel"
  : > "$fork/$rel/controls.wasm.bak"
  chmod 555 "$fork/$rel"
}

# expect_refusal <label> <clause-substring> — exit EXACTLY 1, naming the clause.
expect_refusal() {
  local label="$1" clause="$2"
  if [ "$RUN_CODE" -ne 1 ]; then
    bad "$label: expected exit 1 (FAIL), got $RUN_CODE — that is an ERROR, not a verdict"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
    return 0
  fi
  if ! printf '%s\n' "$RUN_OUT" | grep -q -- "FAIL — $clause"; then
    bad "$label: exit 1 but nothing named '$clause' — the refusal is NOT attributed"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
    return 0
  fi
  ok "$label -> FAIL $clause"
  printf '       %s\n' "$(printf '%s\n' "$RUN_OUT" | grep -m1 -- "FAIL — $clause")"
}

expect_release_ok() {
  local label="$1" fork="$2"
  if [ "$RUN_CODE" -ne 0 ]; then
    bad "$label: expected exit 0, got $RUN_CODE"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
    return 0
  fi
  if [ -e "$fork" ]; then
    bad "$label: exit 0 but the fork is still on disk"
    return 0
  fi
  ok "$label -> released"
}

# ---------------------------------------------------------------------------
# Mutation. Silences exactly one clause and PROVES the edit landed.
# ---------------------------------------------------------------------------
# mutate <name> <sed-expr> <must-appear-regex> <must-vanish-regex> [base]
# Sets MUTANT. Not via command substitution: the progress lines print to stdout,
# and capturing them alongside the path is how a harness ends up executing a log
# line as a filename.
#
# EACH MUTANT IS SITED IN ITS OWN REPO ROOT, never dropped in a scratch
# directory. forks.sh computes ROOT as `<its own dir>/../..` and clones that,
# so a mutant parked in $WORK resolved ROOT to `/` and died with
# "not a git repository" — an ERROR that reddens every case downstream while
# executing none of the clause under test. Copying the whole synthetic root and
# replacing forks.sh inside it keeps the mutant's ROOT resolving exactly as the
# real script's does.
MUTANT=""
mutate() {
  local name="$1" expr="$2" appear="$3" vanish="$4" base="${5:-$SUT}"
  local before after left mutroot="$WORK/mutroot-$name"
  rm -rf -- "$mutroot"
  cp -a -- "$ROOT" "$mutroot"
  MUTANT="$mutroot/tools/claude/forks.sh"
  cp -- "$base" "$MUTANT"
  before="$(grep -c -- "$vanish" "$MUTANT" || true)"
  if [ "$before" -eq 0 ]; then
    bad "mutation '$name' cannot land: nothing matches /$vanish/ in $(basename -- "$base")"
    return 1
  fi
  sed -i "$expr" "$MUTANT"
  after="$(grep -c -- "$appear" "$MUTANT" || true)"
  left="$(grep -c -- "$vanish" "$MUTANT" || true)"
  if [ "$after" -ne "$before" ] || [ "$left" -ne 0 ]; then
    bad "mutation '$name' did NOT land (expected $before rewritten, got $after; $left remain)"
    return 1
  fi
  if ! bash -n "$MUTANT"; then
    bad "mutant '$name' does not parse; any colour from it would prove nothing"
    return 1
  fi
  chmod +x "$MUTANT"
  ok "mutation landed: '$name' — $before site(s) rewritten, 0 remain, mutant parses"
  return 0
}

# ---------------------------------------------------------------------------
build_root
SUT_SITED="$ROOT/tools/claude/forks.sh"
export PROTOGEN_FORKS_STATE_DIR="$WORK/state"

banner "the false-positive floor — a healthy fork still releases"
if claim_fork "$SUT_SITED"; then
  : > "$FORK/.fork-scratch/run.log"
  printf 'probe\n' > "$FORK/.fork-scratch/probe.sh"
  run_release "$SUT_SITED" "$FORK"
  expect_release_ok "healthy fork" "$FORK"
fi

banner "false positive #1 — an EMPTY unwritable directory is NOT in the way"
# Measured: `rm -rf` clears an empty unwritable directory through its PARENT's
# write bit and exits 0, at mode 000 and 555 alike. A predicate of bare
# `! -writable` would refuse this fork; the real one must not.
if [ "$HAZARD" -eq 0 ]; then
  unjudged "empty-unwritable negative: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  mkdir -p "$FORK/.fork-scratch/emptydir"
  chmod 555 "$FORK/.fork-scratch/emptydir"
  run_release "$SUT_SITED" "$FORK"
  expect_release_ok "empty unwritable dir (rm -rf clears it)" "$FORK"
fi

banner "false positive #2 — an unwritable FILE in a writable directory is NOT in the way"
# This is the brief's own framing of the defect ("three FILES were owned by
# root") tested directly. Unlinking needs write permission on the DIRECTORY and
# nothing on the file, so this shape clears — measured with a genuinely
# root-owned file, and again here with mode 000.
if [ "$HAZARD" -eq 0 ]; then
  unjudged "unwritable-file negative: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  : > "$FORK/.fork-scratch/controls.wasm.bak"
  chmod 000 "$FORK/.fork-scratch/controls.wasm.bak"
  run_release "$SUT_SITED" "$FORK"
  expect_release_ok "unwritable file, writable parent (rm -rf clears it)" "$FORK"
fi

# ---------------------------------------------------------------------------
banner "clause: unclearable-residue — a NON-EMPTY unwritable directory in scratch"
CLEARABILITY_FORK=""
if [ "$HAZARD" -eq 0 ]; then
  unjudged "unclearable-residue canary: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  CLEARABILITY_FORK="$FORK"
  : > "$FORK/.fork-scratch/sentinel.log"
  plant_unwritable_dir "$FORK" ".fork-scratch/mut"
  run_release "$SUT_SITED" "$FORK"
  expect_refusal "non-empty unwritable dir under .fork-scratch" "unclearable-residue"
  if [ -d "$FORK" ]; then
    ok "the fork was NOT deleted"
  else
    bad "the fork was deleted despite the refusal"
  fi
  # The property the whole pre-flight design exists for. `rm -rf` deletes what it
  # can BEFORE it fails, so a guard that merely reacted to rm's exit status would
  # already have destroyed this sentinel by the time it spoke.
  if [ -f "$FORK/.fork-scratch/sentinel.log" ]; then
    ok "nothing under .fork-scratch was destroyed before the refusal"
  else
    bad "scratch was partially destroyed before release refused"
  fi
  if printf '%s\n' "$RUN_OUT" | grep -q 'tools/uber.sh true'; then
    ok "the refusal names the preferred command to run"
  else
    bad "the refusal says what is wrong but not what to run"
  fi
fi

banner "the remedy adapts — a fork with no tools/uber.sh is not told to run it"
# Printing advice that cannot be followed is its own defect, so the else-branch
# gets a canary rather than a reading. The removal is COMMITTED inside the fork,
# which is what a worker legitimately doing it would look like and what keeps
# porcelain clean — otherwise the residue gate speaks first and this case never
# reaches the clause under test.
if [ "$HAZARD" -eq 0 ]; then
  unjudged "no-uber.sh remedy branch: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  NOUBER_FORK="$FORK"
  git_at "$NOUBER_FORK" rm -q -- tools/uber.sh
  git_at "$NOUBER_FORK" -c user.name=t -c user.email=t@localhost \
    commit --quiet -m "drop uber.sh"
  plant_unwritable_dir "$NOUBER_FORK" ".fork-scratch/mut"
  run_release "$SUT_SITED" "$NOUBER_FORK"
  expect_refusal "fork without uber.sh" "unclearable-residue"
  if printf '%s\n' "$RUN_OUT" | grep -q 'tools/uber.sh true'; then
    bad "the refusal told the operator to run a script this fork does not have"
  elif printf '%s\n' "$RUN_OUT" | grep -q 'ships no tools/uber.sh'; then
    ok "the refusal says so, and falls back to chmod/chown advice"
  else
    bad "the refusal neither offered uber.sh nor explained its absence"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  fi
  chmod -R u+rwX -- "$NOUBER_FORK" 2>/dev/null || true
fi

banner "clause: unclearable-residue — also covers the half the residue check cannot see"
# An IGNORED directory never appears in `git status --untracked-files=all`, so
# the pre-existing residue gate is blind to it and the fault lands on the FINAL
# `rm -rf "$fork"` instead — the step whose failure leaves a half-shredded fork.
if [ "$HAZARD" -eq 0 ]; then
  unjudged "ignored-path canary: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  IGNORED_FORK="$FORK"
  plant_unwritable_dir "$FORK" "buildout/obj"
  # Control first: prove the pre-existing gate genuinely cannot see this — AND
  # that it is the intended rule doing the hiding. Asserting only "porcelain is
  # clean" once passed on an unrelated `*.bak` pattern in a different repo,
  # which is a green that came from the wrong clause.
  if [ -z "$(git_at "$FORK" status --porcelain --untracked-files=all)" ]; then
    ok "control: porcelain is CLEAN, so the residue gate cannot be what refuses"
  else
    bad "control failed: the fixture is porcelain-visible, so this case is vacuous"
  fi
  if git_at "$FORK" check-ignore -v "buildout/obj/controls.wasm.bak" 2>/dev/null \
    | grep -q ':/buildout/'; then
    ok "control: it is the /buildout/ rule hiding it, not some other pattern"
  else
    bad "control failed: something OTHER than /buildout/ is hiding the fixture"
    git_at "$FORK" check-ignore -v "buildout/obj/controls.wasm.bak" 2>&1 | sed 's/^/       /' >&2
  fi
  run_release "$SUT_SITED" "$IGNORED_FORK"
  expect_refusal "non-empty unwritable dir in an IGNORED path" "unclearable-residue"
  chmod -R u+rwX -- "$IGNORED_FORK" 2>/dev/null || true
fi

banner "MUTANT — silence assert_fork_is_clearable, and only that"
MUTANT_A=""
if mutate "no-clearability-scan" \
  's/^\([[:space:]]*\)assert_fork_is_clearable "/\1: assert_fork_is_clearable "/' \
  '^[[:space:]]*: assert_fork_is_clearable "' \
  '^[[:space:]]*assert_fork_is_clearable "'; then
  MUTANT_A="$MUTANT"
fi

if [ -n "$MUTANT_A" ] && [ "$HAZARD" -eq 1 ] && [ -n "$CLEARABILITY_FORK" ] && [ -d "$CLEARABILITY_FORK" ]; then
  run_release "$MUTANT_A" "$CLEARABILITY_FORK"
  if printf '%s\n' "$RUN_OUT" | grep -q 'unclearable-residue'; then
    bad "mutant still emits unclearable-residue — the mutation did not silence the clause"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  else
    ok "mutant: 'unclearable-residue' is GONE, so the real refusal came from that clause"
  fi
  # ...and what the clause was PREVENTING is now visible: the scratch sentinel
  # is destroyed by the rm that the scan used to run in front of.
  if [ -f "$CLEARABILITY_FORK/.fork-scratch/sentinel.log" ]; then
    bad "mutant did not reach the rm; this fixture proves nothing about the clause"
  else
    ok "mutant: the sentinel WAS destroyed — the scan is what stood between rm and the tree"
  fi
  # The next clause down is now the one that speaks, and it must name itself
  # rather than aborting into silence.
  expect_refusal "mutant: the guard beneath it names itself" "scratch-cleanup-failed"
elif [ "$HAZARD" -eq 0 ]; then
  unjudged "mutant A run: mode bits do not refuse this process"
fi

banner "CONTROL — with the scan silenced, a HEALTHY fork still releases"
# Without this, the mutant's red above is compatible with the mutant simply
# being broken.
if [ -n "$MUTANT_A" ] && claim_fork "$MUTANT_A"; then
  : > "$FORK/.fork-scratch/run.log"
  run_release "$MUTANT_A" "$FORK"
  expect_release_ok "mutant A on a healthy fork" "$FORK"
fi

banner "MUTANT — additionally revert the rm guard to the bare pre-fix statement"
# Built ON TOP of mutant A, because the guard is only REACHABLE once the scan in
# front of it is silenced. That is the honest shape of a defence-in-depth layer:
# in production nothing gets past the scan, so the only way to show this clause
# is live is to remove the thing that shadows it.
MUTANT_B=""
if [ -n "$MUTANT_A" ] && mutate "bare-rm-scratch" \
  's|^\([[:space:]]*\)if ! rm -rf -- "\$fork/\.fork-scratch"; then|\1rm -rf -- "$fork/.fork-scratch"; if false; then|' \
  '^[[:space:]]*rm -rf -- "\$fork/\.fork-scratch"; if false; then' \
  '^[[:space:]]*if ! rm -rf -- "\$fork/\.fork-scratch"; then' \
  "$MUTANT_A"; then
  MUTANT_B="$MUTANT"
fi

if [ -n "$MUTANT_B" ] && [ "$HAZARD" -eq 1 ] && claim_fork "$MUTANT_B"; then
  BARE_FORK="$FORK"
  plant_unwritable_dir "$BARE_FORK" ".fork-scratch/mut"
  run_release "$MUTANT_B" "$BARE_FORK"
  if [ "$RUN_CODE" -eq 0 ]; then
    bad "mutant B released a fork it could not delete — worse than either red"
  elif printf '%s\n' "$RUN_OUT" | grep -q 'FAIL — scratch-cleanup-failed'; then
    bad "mutant B still names scratch-cleanup-failed — the revert did not take"
  elif printf '%s\n' "$RUN_OUT" | grep -q '\[forks\] FAIL'; then
    bad "mutant B emitted some other [forks] FAIL; attribution is unclear"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  else
    ok "mutant B: NO '[forks] FAIL' line at all — the pre-fix silent abort, restored"
    printf '       release stopped carrying only rm own words: %s\n' \
      "$(printf '%s\n' "$RUN_OUT" | grep -m1 'Permission denied' || echo '(none)')"
  fi
  chmod -R u+rwX -- "$BARE_FORK" 2>/dev/null || true
elif [ "$HAZARD" -eq 0 ] && [ -n "$MUTANT_B" ]; then
  unjudged "mutant B run: mode bits do not refuse this process"
fi

# ---------------------------------------------------------------------------
banner "the residue gate was NOT widened — uncommitted TRACKED work still refuses"
# The non-negotiable. A clearability guard must not have turned release into
# "delete whatever is in the way", and it must not outrank the gate that asks
# whether the worker finished.
if claim_fork "$SUT_SITED"; then
  printf 'worker edited this\n' >> "$FORK/tracked.txt"
  run_release "$SUT_SITED" "$FORK"
  expect_refusal "modified tracked file" "release found uncommitted residue"
  if [ -d "$FORK" ] && [ -f "$FORK/tracked.txt" ]; then
    ok "the fork and its modified file are intact"
  else
    bad "release destroyed a fork carrying uncommitted tracked work"
  fi
fi

banner "both faults at once — the RESIDUE gate speaks first, and repairs nothing"
if [ "$HAZARD" -eq 0 ]; then
  unjudged "both-faults case: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  BOTH_FORK="$FORK"
  printf 'worker edited this\n' >> "$BOTH_FORK/tracked.txt"
  plant_unwritable_dir "$BOTH_FORK" ".fork-scratch/mut"
  run_release "$SUT_SITED" "$BOTH_FORK"
  expect_refusal "residue + unclearable dir" "release found uncommitted residue"
  # The guard must DIAGNOSE, never repair: a release that chmod'ed its way
  # through would be "delete whatever is in the way" wearing a smaller hat.
  MODE_NOW="$(stat -c '%a' -- "$BOTH_FORK/.fork-scratch/mut" 2>/dev/null || echo '?')"
  if [ "$MODE_NOW" = "555" ]; then
    ok "release changed no permissions of its own accord (mut is still 0555)"
  else
    bad "release mutated permissions on a fork it refused (mut is now 0$MODE_NOW)"
  fi
  chmod -R u+rwX -- "$BOTH_FORK" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed:   %s\n' "$PASS"
printf '  failed:   %s\n' "$FAIL"
printf '  unjudged: %s\n' "$UNJUDGED"
[ "$FAIL" -eq 0 ] || exit 1
if [ "$UNJUDGED" -ne 0 ]; then
  printf '  \033[33mGREEN, WITH %s UNJUDGED\033[0m — an unwritable directory does not\n' "$UNJUDGED"
  printf '  refuse this process (running as uid %s, or a filesystem that ignores mode\n' "$EUID"
  printf '  bits), so the permission canaries could not be staged. This is NOT a pass\n'
  printf '  for them: re-run as a non-root user on a permission-enforcing filesystem.\n'
  exit 0
fi
printf '  \033[32mALL GREEN\033[0m\n'
