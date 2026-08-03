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
#   ...AND THE SCRIPT MUST BE ABLE TO SAY WHICH IT IS. That paragraph was true
#   of this suite and FALSE of forks.sh, which is the defect this file grew to
#   cover. Every guard there printed `[forks] FAIL —` and exited 1, including
#   three that said in their own message "that is an ERROR, not a verdict", so
#   asserting exit 1 could not distinguish a guard that looked and refused from
#   one whose machinery had collapsed. forks.sh now carries brief-check.sh's
#   codes — 1 FAIL, 2 USAGE, 3 ERROR — and this suite asserts BOTH the code and
#   the stderr prefix through ONE function, verdict_is, so the two channels can
#   never drift apart in an assertion.
#
#   THE ACCEPTANCE PROOF IS A SWAP, NOT A RED. A suite that pinned "non-zero"
#   would pass with FAIL and ERROR exchanged, which is the whole bug wearing the
#   right colour. The section at the foot of this file builds three mutants —
#   codes swapped, prefixes swapped, and both — runs a known-refusal fixture and
#   a known-breakage fixture through each, and asserts that verdict_is, the very
#   predicate every expectation above is built on, goes FALSE. Each of the three
#   is separate on purpose: a regression is far more likely to move one channel
#   than both, and a canary that only detects a simultaneous swap would sleep
#   through it.
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

# run_claim <sut> — claims into a fresh path and JUDGES NOTHING. claim_fork
# calls bad() when a claim fails, which is right for a fixture and wrong for the
# cases below, where the claim failing IS the thing under test.
run_claim() {
  local sut="$1"
  N=$((N + 1))
  FORK="$WORK/fork$N"
  write_brief "$N"
  RUN_CODE=0
  RUN_OUT="$("$sut" claim "$FORK" "t$N" tester "$BRIEF" 2>&1)" || RUN_CODE=$?
}

# stub_gate_root <name> <ship-list-exit> <check-exit> — a COMPLETE synthetic root
# whose brief-check.sh is a stub with chosen exit codes, and whose forks.sh is
# the REAL one, byte for byte.
#
# THIS IS NOT A MUTATION AND MUST NOT BE READ AS ONE. Nothing in the script
# under test is altered; what changes is the environment it runs in. That is the
# faithful reproduction of the production hazard, which is a brief-check that
# breaks — and it means these cases cannot be dismissed as artefacts of an edit.
#
# A STUB RATHER THAN A REAL brief-check DRIVEN ONTO ITS OWN die() PATH, which is
# also reachable. What is under test here is that forks.sh HONOURS the exit code
# it is handed, and a stub states that code outright instead of inheriting it
# from another script's internals — internals that are fenced for this task and
# free to move without warning. A canary that depended on them would go quiet
# the day they changed.
stub_gate_root() {
  local name="$1" ship_exit="$2" check_exit="$3"
  # A SECOND `local`, deliberately: the builtin expands ALL its arguments before
  # it assigns any of them, so `root="$WORK/stubroot-$name"` on the line above
  # reads $name while it is still unset and dies under `set -u`.
  local root="$WORK/stubroot-$name"
  rm -rf -- "$root"
  cp -a -- "$ROOT" "$root"
  cat > "$root/tools/claude/brief-check.sh" <<EOF
#!/usr/bin/env bash
# stub brief-check: check exits $check_exit, ship-list exits $ship_exit.
case "\${1:-}" in
  check)     exit $check_exit ;;
  ship-list) exit $ship_exit ;;
  *)         exit 0 ;;
esac
EOF
  chmod +x "$root/tools/claude/brief-check.sh"
  STUB_SUT="$root/tools/claude/forks.sh"
  STUB_ROOT="$root"
}
STUB_SUT=""
STUB_ROOT=""

# plant_unwritable_dir <fork> <relative-dir>  — non-empty, mode 555.
# Mode 555 mirrors the measured production shape: a root-owned directory at 755
# is r-x to uid != 0, which is exactly r-x here.
plant_unwritable_dir() {
  local fork="$1" rel="$2"
  mkdir -p "$fork/$rel"
  : > "$fork/$rel/controls.wasm.bak"
  chmod 555 "$fork/$rel"
}

# ---------------------------------------------------------------------------
# THE ONE DISCRIMINATOR. Every FAIL-or-ERROR expectation in this file, and the
# swap canary at its foot, go through THIS function — which is the only way the
# swap canary can prove something about the assertions the suite actually makes
# rather than about a predicate written to resemble them. (Cases that assert
# something else entirely — a successful release, the ABSENCE of any verdict
# line under mutant B — do not use it, because they are not asking which class a
# verdict was.)
#
# It reads BOTH channels, because a caller has both and either alone can rot:
# `$?` for a machine, the stderr prefix for a human. `1`/`FAIL` is a verdict the
# guard reached; `3`/`ERROR` is a guard that reached none. They are forks.sh's
# codes and brief-check.sh's codes, deliberately the same set.
#
# verdict_is <FAIL|ERROR> <clause-substring> — true iff BOTH channels agree.
# ---------------------------------------------------------------------------
verdict_is() {
  local kind="$1" clause="$2" want
  case "$kind" in
    FAIL) want=1 ;;
    ERROR) want=3 ;;
    *) bad "verdict_is called with an unknown kind '$kind'"; return 1 ;;
  esac
  [ "$RUN_CODE" -eq "$want" ] || return 1
  grep -q -- "\[forks\] $kind — $clause" <<< "$RUN_OUT"
}

# Which half disagreed, so a red says what moved rather than only that something did.
verdict_diagnosis() {
  local kind="$1" clause="$2" want
  case "$kind" in FAIL) want=1 ;; ERROR) want=3 ;; *) want=-1 ;; esac
  if [ "$RUN_CODE" -ne "$want" ]; then
    case "$RUN_CODE" in
      0) printf 'exit 0 — the command SUCCEEDED where a %s was expected' "$kind" ;;
      1) printf 'exit 1 (FAIL, a verdict) where %s (%s) was expected' "$kind" "$want" ;;
      2) printf 'exit 2 (USAGE) where %s (%s) was expected' "$kind" "$want" ;;
      3) printf 'exit 3 (ERROR, no verdict reached) where %s (%s) was expected' "$kind" "$want" ;;
      *) printf 'exit %s, outside this script contract, where %s (%s) was expected' "$RUN_CODE" "$kind" "$want" ;;
    esac
    return
  fi
  printf 'exit %s was right but no "[forks] %s — %s" line was printed' "$RUN_CODE" "$kind" "$clause"
}

# expect_refusal <label> <clause-substring> — exit EXACTLY 1, naming the clause.
expect_refusal() {
  local label="$1" clause="$2"
  if verdict_is FAIL "$clause"; then
    ok "$label -> FAIL $clause"
    printf '       %s\n' "$(grep -m1 -- "FAIL — $clause" <<< "$RUN_OUT")"
    return 0
  fi
  bad "$label: not a clean refusal — $(verdict_diagnosis FAIL "$clause")"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
}

# expect_error <label> <clause-id> — exit EXACTLY 3, naming the clause.
# THE HALF THAT DID NOT EXIST. Every guard in forks.sh used to exit 1 with a
# `FAIL` prefix, including the ones whose own message said "that is an ERROR,
# not a verdict", so this assertion had nothing to assert and the suite's model
# (1 = verdict, anything else = broken) agreed with the script only by accident.
expect_error() {
  local label="$1" clause="$2"
  if verdict_is ERROR "$clause"; then
    ok "$label -> ERROR $clause"
    printf '       %s\n' "$(grep -m1 -- "ERROR — $clause" <<< "$RUN_OUT")"
    return 0
  fi
  bad "$label: not a clean breakage — $(verdict_diagnosis ERROR "$clause")"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
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
  if grep -q 'tools/uber.sh true' <<< "$RUN_OUT"; then
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
  if grep -q 'tools/uber.sh true' <<< "$RUN_OUT"; then
    bad "the refusal told the operator to run a script this fork does not have"
  elif grep -q 'ships no tools/uber.sh' <<< "$RUN_OUT"; then
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
  # Captured, not piped: `grep -q` quits on its first match without draining, so
  # check-ignore can die of SIGPIPE and `pipefail` then reports the whole thing
  # non-zero — a false `bad` about the fixture rather than a verdict about the
  # ignore rule. check-ignore prints nothing when it does not match, so an empty
  # capture reaches the same else branch the failing pipeline used to.
  CHECK_IGNORE_OUT="$(git_at "$FORK" check-ignore -v "buildout/obj/controls.wasm.bak" 2>/dev/null || true)"
  if grep -q ':/buildout/' <<< "$CHECK_IGNORE_OUT"; then
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
  if grep -q 'unclearable-residue' <<< "$RUN_OUT"; then
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
  #
  # AN ERROR, AND THIS ASSERTION IS WHERE THE CLASSIFICATION IS PINNED. The
  # guard that owns clearability passed on this fork moments ago; reaching the
  # rm means that ruling was falsified, and a falsified guard is broken
  # machinery rather than a second opinion. Had the classification gone the
  # other way — scratch-cleanup-failed left as a FAIL — this line would be
  # expect_refusal and the suite would go red on the change that flipped it.
  expect_error "mutant: the guard beneath it names itself" "scratch-cleanup-failed"
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
  # WIDENED TO BOTH PREFIXES WHEN THE SECOND ONE WAS INTRODUCED. This case
  # asserts an ABSENCE — that reverting the guard restores the silent abort —
  # and an absence assertion is only as complete as the set of things it looks
  # for. It used to grep `[forks] FAIL` alone, which was total while FAIL was
  # the only verdict prefix this script could print. The moment
  # scratch-cleanup-failed became an ERROR, that same grep would have gone GREEN
  # over a guard that spoke perfectly clearly, just in the other class.
  if [ "$RUN_CODE" -eq 0 ]; then
    bad "mutant B released a fork it could not delete — worse than either red"
  elif grep -q -- 'scratch-cleanup-failed' <<< "$RUN_OUT"; then
    bad "mutant B still names scratch-cleanup-failed — the revert did not take"
  elif grep -qE '\[forks\] (FAIL|ERROR)' <<< "$RUN_OUT"; then
    bad "mutant B emitted some other [forks] verdict line; attribution is unclear"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  else
    ok "mutant B: NO '[forks] FAIL' and no '[forks] ERROR' line at all — the pre-fix silent abort, restored"
    printf '       release stopped carrying only rm own words: %s\n' \
      "$(grep -m1 'Permission denied' <<< "$RUN_OUT" || echo '(none)')"
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

# ===========================================================================
# BREAKAGES. Every case below reaches a site where a guard could NOT look, and
# each must exit 3 under an `[forks] ERROR` prefix. Before this file grew them,
# every one of these exited 1 saying FAIL — indistinguishable, on both channels,
# from the refusals above.
# ===========================================================================

banner "ERROR: the brief gate itself cannot run — claim reached no verdict"
# Not a mutation of forks.sh: the gate binary is simply not executable, which is
# what a bad checkout, a lost +x bit or a partial sync looks like.
NOEXEC_ROOT="$WORK/noexec-root"
rm -rf -- "$NOEXEC_ROOT"
cp -a -- "$ROOT" "$NOEXEC_ROOT"
chmod -x "$NOEXEC_ROOT/tools/claude/brief-check.sh"
run_claim "$NOEXEC_ROOT/tools/claude/forks.sh"
expect_error "brief gate not executable" "brief-gate-missing"
if [ -e "$FORK" ]; then
  bad "an ERROR before the gate still created $FORK"
else
  ok "no path was created — the ERROR fired before anything was cloned"
fi

banner "ERROR: the brief gate ran and could not decide — exit 3 is PROPAGATED IN KIND"
# brief-check.sh documents 3 as an internal error that is never a verdict about
# the brief. forks.sh must not launder that into a verdict of its own.
stub_gate_root "noverdict" 0 3
run_claim "$STUB_SUT"
expect_error "brief-check exits 3 on check" "brief-gate-no-verdict"
if [ -e "$FORK" ]; then
  bad "an ERROR at the gate still created $FORK"
else
  ok "no path was created"
fi

banner "CONTROL: the same gate REFUSING is still a FAIL — 1 and 3 are not merged"
# Without this the section above is compatible with forks.sh having simply
# started calling everything an ERROR. brief-check's 1 is a verdict about the
# brief and must survive as one.
stub_gate_root "refuses" 0 1
run_claim "$STUB_SUT"
expect_refusal "brief-check exits 1 on check" "brief-check REFUSED"
if grep -q '\[forks\] ERROR' <<< "$RUN_OUT"; then
  bad "a clean refusal also emitted an ERROR line"
else
  ok "the refusal carried no ERROR line"
fi

banner "ERROR: the ship list broke — zero lines is not an empty ship list"
# The measured production shape: a producer's failure arriving as ZERO LINES,
# byte-identical to the honest "this brief cites nothing git cannot carry".
stub_gate_root "shiplist" 3 0
run_claim "$STUB_SUT"
expect_error "brief-check ship-list exits 3" "ship-list-failed"
if grep -q 'CLAIMED' <<< "$RUN_OUT"; then
  bad "claim reported CLAIMED after its ship list broke"
else
  ok "claim did not report CLAIMED"
fi
# The fork exists by now (the clone runs before shipping) and is deliberately
# RETAINED. It must not have been recorded OWNED, or the path would be stranded.
if [ -d "$FORK" ]; then
  ok "the fork is retained for inspection, as the message says"
  SHIPFAIL_FORK="$FORK"
  RUN_CODE=0
  RUN_OUT="$("$STUB_SUT" list 2>&1)" || RUN_CODE=$?
  if grep -q "OWNED.*$SHIPFAIL_FORK" <<< "$RUN_OUT"; then
    bad "the ERROR still recorded the fork OWNED"
  else
    ok "no OWNED record was written for a fork the ERROR abandoned"
  fi
else
  bad "the fork was not retained, contradicting the ERROR message"
fi

banner "ERROR: the manifest holds a state this script cannot read"
# cmd_check asks 'would a claim collide?'. An unparseable state token is neither
# yes nor no, and answering either would be a guess.
BADSTATE_STATE="$WORK/badstate"
mkdir -p "$BADSTATE_STATE"
printf '# protogen fork manifest v1\n' > "$BADSTATE_STATE/manifest.tsv"
printf '%s\tt9\ttester\t/dev/null\t2026-01-01T00:00:00Z\tWAT\t\n' \
  "$WORK/badstate-fork" >> "$BADSTATE_STATE/manifest.tsv"
RUN_CODE=0
RUN_OUT="$(PROTOGEN_FORKS_STATE_DIR="$BADSTATE_STATE" "$SUT_SITED" check "$WORK/badstate-fork" 2>&1)" || RUN_CODE=$?
expect_error "unparseable manifest state" "manifest-state-invalid"

banner "CONTROL: a manifest this script CAN read still yields verdicts"
# Same command, same manifest shape, one token changed. If the case above went
# red for any reason other than the token, this one goes red too.
GOODSTATE_STATE="$WORK/goodstate"
mkdir -p "$GOODSTATE_STATE"
printf '# protogen fork manifest v1\n' > "$GOODSTATE_STATE/manifest.tsv"
printf '%s\tt9\ttester\t/dev/null\t2026-01-01T00:00:00Z\tOWNED\t\n' \
  "$WORK/badstate-fork" >> "$GOODSTATE_STATE/manifest.tsv"
RUN_CODE=0
RUN_OUT="$(PROTOGEN_FORKS_STATE_DIR="$GOODSTATE_STATE" "$SUT_SITED" check "$WORK/badstate-fork" 2>&1)" || RUN_CODE=$?
expect_refusal "OWNED manifest state" "claim would collide"

banner "ERROR: the residue scan itself failed — silence is not a clean tree"
# git status printing nothing is byte-identical to a clean worktree, so its exit
# status is the only thing between a verdict and a guess. Staged by making .git
# unreadable, which `[ -d "$fork/.git" ]` upstream cannot see.
if [ "$HAZARD" -eq 0 ]; then
  unjudged "residue-scan-failed canary: mode bits do not refuse this process"
elif claim_fork "$SUT_SITED"; then
  SCANFAIL_FORK="$FORK"
  chmod 000 "$SCANFAIL_FORK/.git"
  # THE STAGE IS PROBED, NOT ASSUMED. git walks UP when it cannot open .git, so
  # a temp directory that happens to sit inside another repository would let
  # `git status` succeed and this canary would go red for a reason having
  # nothing to do with the clause. That is a case where the suite cannot look,
  # and it says so instead of reporting either colour.
  if git_at "$SCANFAIL_FORK" status --porcelain >/dev/null 2>&1; then
    unjudged "residue-scan-failed canary: git status still succeeds with .git unreadable (is $WORK inside another repository?)"
  else
    ok "stage: git status genuinely cannot run in this fork"
    run_release "$SUT_SITED" "$SCANFAIL_FORK"
    expect_error "git status cannot run" "residue-scan-failed"
    if [ -d "$SCANFAIL_FORK" ]; then
      ok "nothing was deleted on a scan that never ran"
    else
      bad "release deleted a fork whose residue was never judged"
    fi
  fi
  chmod 755 "$SCANFAIL_FORK/.git" 2>/dev/null || true
fi

banner "MUTANT — break the clearability scan's MACHINERY, leaving its verdict clause intact"
# THE PAIR THAT IS THE WHOLE POINT. The section far above proves the scan
# FINDING blockers is a FAIL naming unclearable-residue. This proves the scan
# DYING is an ERROR naming clearability-scan-failed. Same function, same fork
# shape, two different answers — and before this change both printed
# `[forks] FAIL — unclearable-residue` and exited 1, sharing an exit code AND a
# clause id, so no caller could separate them on either channel.
MUTANT_C=""
if mutate "broken-clearability-find" \
  's|^\([[:space:]]*\)listing="\$(find "\$fork" -type d|\1listing="$(find "$fork/no-such-probe-dir" -type d|' \
  '^[[:space:]]*listing="\$(find "\$fork/no-such-probe-dir" -type d' \
  '^[[:space:]]*listing="\$(find "\$fork" -type d'; then
  MUTANT_C="$MUTANT"
fi

if [ -n "$MUTANT_C" ] && claim_fork "$MUTANT_C"; then
  SCANBROKEN_FORK="$FORK"
  : > "$SCANBROKEN_FORK/.fork-scratch/sentinel.log"
  run_release "$MUTANT_C" "$SCANBROKEN_FORK"
  expect_error "clearability scan died" "clearability-scan-failed"
  # ATTRIBUTION, and it is the reason the id was split. A grep for the refusal's
  # own clause must find nothing here.
  if grep -q 'unclearable-residue' <<< "$RUN_OUT"; then
    bad "the broken scan still reported 'unclearable-residue' — the two clauses are still merged"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  else
    ok "the broken scan does NOT say 'unclearable-residue' — a grep can now tell them apart"
  fi
  if [ -f "$SCANBROKEN_FORK/.fork-scratch/sentinel.log" ]; then
    ok "nothing was deleted on a scan that reached no verdict"
  else
    bad "the fork was destroyed by a release whose scan never ran"
  fi
fi

banner "CONTROL — that mutant is not simply broken: this fork was HEALTHY"
# Without this, the red above is compatible with mutant C failing for any
# reason at all. The fixture it just refused would have released cleanly under
# the real script, which the very first case of this file establishes.
if [ -n "$MUTANT_C" ] && [ -n "${SCANBROKEN_FORK:-}" ] && [ -d "${SCANBROKEN_FORK:-/nonexistent}" ]; then
  if [ -z "$(git_at "$SCANBROKEN_FORK" status --porcelain --untracked-files=all)" ]; then
    ok "control: the refused fork's porcelain is CLEAN, so no residue clause could have spoken"
  else
    bad "control failed: the fixture carried residue, so the ERROR is not attributable to the scan"
  fi
fi

banner "ERROR: the FINAL rm failed — a fork no verdict describes"
# Reachable only with the scan in front of it silenced, exactly as mutant B is:
# in production nothing gets past the scan. The unwritable directory sits in an
# IGNORED path, so the residue gate cannot see it either, and the fault lands on
# the last `rm -rf` — after the deletion was authorised and after rm has already
# removed what it could.
if [ "$HAZARD" -eq 0 ]; then
  unjudged "fork-deletion-failed canary: mode bits do not refuse this process"
elif [ -n "$MUTANT_A" ] && claim_fork "$MUTANT_A"; then
  SHRED_FORK="$FORK"
  plant_unwritable_dir "$SHRED_FORK" "buildout/obj"
  run_release "$MUTANT_A" "$SHRED_FORK"
  expect_error "final rm -rf could not complete" "fork-deletion-failed"
  # The claim the ERROR text makes, checked rather than trusted. This is what
  # makes the class matter: a FAIL promises the fork is as it was, and this
  # outcome cannot promise that.
  if [ -e "$SHRED_FORK/tracked.txt" ]; then
    bad "the fork was NOT partially deleted, so the message overstates the damage"
  elif [ -d "$SHRED_FORK" ]; then
    ok "the fork IS partially deleted — tracked.txt is gone, the unwritable dir remains"
  else
    bad "the fork is entirely gone, so the rm did not actually fail"
  fi
  chmod -R u+rwX -- "$SHRED_FORK" 2>/dev/null || true
fi

# ===========================================================================
# THE ACCEPTANCE CANARY — would this suite CATCH a FAIL/ERROR swap?
#
# Everything above asserts that forks.sh classifies correctly today. None of it
# shows that the ASSERTIONS would notice if the classification were inverted,
# and a suite pinning only "non-zero" would not. So: swap the two, and require
# verdict_is — the single predicate every expectation above is built on — to go
# FALSE for a known refusal and for a known breakage alike.
#
# THREE SWAPS, NOT ONE. verdict_is reads two channels, and a regression is far
# likelier to move one than both. A canary that only fired on a simultaneous
# swap would sleep through the realistic case.
#
# BOTH FIXTURES ROUTE THROUGH fail()/error() THEMSELVES, and the reason is
# specific rather than tidy-mindedness. Three refusals in forks.sh print a
# multi-line body with the prefix written INLINE and exit 1 directly. The code
# swap below reaches their `exit 1` like any other; the PREFIX swap does not
# touch them, because it rewrites the two `[forks] … — %s` format strings and
# theirs carry a clause name where the %s would be. So a fixture routed through
# one of those would leave SWAP 2 with nothing to detect, and its green would
# mean only that the mutation never reached the fixture.
# ===========================================================================
SWAP_STATE="$WORK/swapstate"
mkdir -p "$SWAP_STATE"
printf '# protogen fork manifest v1\n' > "$SWAP_STATE/manifest.tsv"
printf '%s\tt9\ttester\t/dev/null\t2026-01-01T00:00:00Z\tWAT\t\n' \
  "$WORK/swap-fork" >> "$SWAP_STATE/manifest.tsv"

# run_known_refusal <sut> — release of a path with no manifest claim. Goes
# through fail(), needs no clone, and is a verdict by any reading.
run_known_refusal() {
  RUN_CODE=0
  RUN_OUT="$(PROTOGEN_FORKS_STATE_DIR="$SWAP_STATE" "$1" release "$WORK/never-claimed" --owner-signalled done 2>&1)" || RUN_CODE=$?
}
# run_known_breakage <sut> — check against an unreadable manifest state. Goes
# through error(), needs no clone.
run_known_breakage() {
  RUN_CODE=0
  RUN_OUT="$(PROTOGEN_FORKS_STATE_DIR="$SWAP_STATE" "$1" check "$WORK/swap-fork" 2>&1)" || RUN_CODE=$?
}

banner "PRE-CONTROL — unswapped, both fixtures classify as the suite expects"
run_known_refusal "$SUT_SITED"
expect_refusal "swap fixture (refusal), unswapped" "fork has no manifest claim"
run_known_breakage "$SUT_SITED"
expect_error "swap fixture (breakage), unswapped" "manifest-state-invalid"

# assert_swap_detected <label> <sut>
# Runs both fixtures against a swapped mutant and requires the suite's own
# discriminator to reject both. It calls verdict_is directly rather than
# expect_refusal/expect_error, because a detected swap must count as a PASS here
# while those helpers count it as a failure.
assert_swap_detected() {
  local label="$1" sut="$2"
  run_known_refusal "$sut"
  if verdict_is FAIL "fork has no manifest claim"; then
    bad "$label: the suite STILL accepts the refusal fixture as a clean FAIL — a swap would ship unnoticed"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  else
    ok "$label: refusal fixture rejected — $(verdict_diagnosis FAIL 'fork has no manifest claim')"
  fi
  run_known_breakage "$sut"
  if verdict_is ERROR "manifest-state-invalid"; then
    bad "$label: the suite STILL accepts the breakage fixture as a clean ERROR — a swap would ship unnoticed"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  else
    ok "$label: breakage fixture rejected — $(verdict_diagnosis ERROR manifest-state-invalid)"
  fi
}

# ---------------------------------------------------------------------------
# A SWAP CANNOT USE mutate(), and the reason is worth writing down rather than
# working around quietly. mutate() proves an edit landed by requiring the old
# text to be GONE — which is exactly what an exchange does not do. After
# swapping FAIL and ERROR the file still contains one of each, so mutate's
# `$left -eq 0` check fails on a mutation that landed perfectly. A weaker check
# would be the wrong repair: the property a swap has to prove is not that a
# string is absent but that the ASSOCIATION moved.
#
# So this asserts on the two function BODIES by name: after the mutation, the
# line at a known offset inside `fail()` must carry ERROR's text and the line at
# the same offset inside `error()` must carry FAIL's. That is stronger than any
# count — a sed that rewrote some other line in the file cannot satisfy it.
#
# The seds go through a PLACEHOLDER because a naive two-step exchange
# (s/A/B/; s/B/A/) rewrites A to B and then straight back to A, landing nothing
# while every count looks right.
# ---------------------------------------------------------------------------
# fn_line <file> <fn-name> <offset> — the offset'th line of a function body.
fn_line() { grep -A"$3" -- "^$2() {" "$1" | tail -n 1; }

# mutate_swap <name> <sed-expr> <fail-must-contain> <error-must-contain> <offset> [base]
mutate_swap() {
  local name="$1" expr="$2" want_in_fail="$3" want_in_error="$4" off="$5"
  local base="${6:-$SUT}" mutroot="$WORK/mutroot-$name" got_fail got_error
  rm -rf -- "$mutroot"
  cp -a -- "$ROOT" "$mutroot"
  MUTANT="$mutroot/tools/claude/forks.sh"
  cp -- "$base" "$MUTANT"
  sed -i "$expr" "$MUTANT"
  got_fail="$(fn_line "$MUTANT" fail "$off")"
  got_error="$(fn_line "$MUTANT" error "$off")"
  case "$got_fail" in
    *"$want_in_fail"*) ;;
    *) bad "swap '$name' did NOT land: fail() line $off is '$got_fail', wanted it to contain '$want_in_fail'"
       return 1 ;;
  esac
  case "$got_error" in
    *"$want_in_error"*) ;;
    *) bad "swap '$name' did NOT land: error() line $off is '$got_error', wanted it to contain '$want_in_error'"
       return 1 ;;
  esac
  if ! bash -n "$MUTANT"; then
    bad "swap '$name' does not parse; any colour from it would prove nothing"
    return 1
  fi
  chmod +x "$MUTANT"
  ok "swap landed: '$name' — fail() now carries '$want_in_fail', error() now carries '$want_in_error', mutant parses"
  return 0
}

SWAP_CODES_SED='s/^\([[:space:]]*\)exit 1$/\1exit @@S@@/; s/^\([[:space:]]*\)exit 3$/\1exit 1/; s/^\([[:space:]]*\)exit @@S@@$/\1exit 3/'
SWAP_PREFIX_SED='s/\[forks\] FAIL — %s/[forks] @@S@@ — %s/; s/\[forks\] ERROR — %s/[forks] FAIL — %s/; s/\[forks\] @@S@@ — %s/[forks] ERROR — %s/'

banner "SWAP 1 — the EXIT CODES are exchanged, the prefixes are untouched"
# The half a suite asserting only "non-zero" is blind to — which is every suite
# that predates this contract.
if mutate_swap "swap-codes" "$SWAP_CODES_SED" "exit 3" "exit 1" 2; then
  assert_swap_detected "codes swapped" "$MUTANT"
fi

banner "SWAP 2 — the PREFIXES are exchanged, the exit codes are untouched"
# The half a suite asserting only the exit code is blind to. A caller reading
# stderr has exactly the problem a caller reading $? has, which is why
# verdict_is reads both channels and this canary breaks them one at a time.
if mutate_swap "swap-prefixes" "$SWAP_PREFIX_SED" "ERROR — %s" "FAIL — %s" 1; then
  assert_swap_detected "prefixes swapped" "$MUTANT"
fi

banner "SWAP 3 — both channels exchanged at once"
if mutate_swap "swap-both-codes" "$SWAP_CODES_SED" "exit 3" "exit 1" 2; then
  SWAP_BOTH_BASE="$MUTANT"
  if mutate_swap "swap-both" "$SWAP_PREFIX_SED" "ERROR — %s" "FAIL — %s" 1 "$SWAP_BOTH_BASE"; then
    assert_swap_detected "both swapped" "$MUTANT"
  fi
fi

banner "POST-CONTROL — the real script still classifies both fixtures correctly"
# Proves the swap section left nothing behind and that the discriminator above
# is not simply rejecting everything it is handed.
run_known_refusal "$SUT_SITED"
expect_refusal "swap fixture (refusal), after the swaps" "fork has no manifest claim"
run_known_breakage "$SUT_SITED"
expect_error "swap fixture (breakage), after the swaps" "manifest-state-invalid"

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
