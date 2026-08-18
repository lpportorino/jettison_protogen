#!/usr/bin/env bash
# c_tidy_size_test.sh — canaries for the SIX-AXIS SIZE CHECK that `lint-c-tidy`
# enforces over the hand-authored C: `readability-function-size`'s Line,
# Statement, Branch, Nesting, Variable and Parameter thresholds, declared in
# renderer/.clang-tidy.
#
# WHY THIS EXISTS. That lane runs on every push and in CI, and until this file
# landed its ability to FAIL had been demonstrated exactly once, by hand, in a
# commit message. `.claude/rules/gate-enforcement.md` §2 calls that a
# prescription rather than a gate: green is uninformative until failure has been
# demonstrated, because the commonest way a gate dies is by silently checking
# nothing — and that state emits exactly the output a clean run does. Here the
# ways to die are concrete: `readability-function-size` is enabled by NAME in a
# `Checks:` list whose other `readability-*` entries are deliberately inert, so a
# one-token edit disables it silently; the six thresholds are six numbers whose
# CheckOptions keys clang-tidy ignores without complaint if misspelled; and the
# whole run is driven off a compile database regenerated on every invocation,
# which can come back empty.
#
# SCOPE, STATED SO A GREEN IS NOT READ AS MORE THAN IT IS. This suite covers the
# six size axes ONLY. The rest of the check set that lane enables — bugprone,
# cert, the four clang-analyzer sub-namespaces, performance, concurrency, misc —
# is NOT driven here, and a green below says nothing about whether any of those
# can still refuse. What it does say is that the lane reaches the analysis, that
# the size check is live, and that each of the six axes is read from the config
# and reported on its own.
#
# WHAT EACH CASE PROVES, and it is more than that the lane went red:
#
#   THE MUTATION LANDED. Every threshold edit goes through `mutate_file`, which
#   refuses an absent or ambiguous anchor. A mutation that silently matched
#   nothing yields a mutant identical to the original, whose unchanged verdict
#   would then read as attribution while proving the exact opposite.
#
#   THE LANE READ THE MUTATED CONFIG. clang-tidy echoes the threshold it applied
#   back into its own note — "13 lines including whitespace and comments
#   (threshold 1)" — so each case asserts the note carries the value THIS case
#   wrote. That is what distinguishes "the config under test was read" from "some
#   other .clang-tidy up the tree was".
#
#   THE AXIS IS ATTRIBUTED. All six axes are one check with one name, so
#   `[readability-function-size]` in the output attributes nothing. The
#   discriminator is the note wording, and each case requires ITS note present
#   AND THE OTHER FIVE ABSENT — the neighbours are relaxed to 9999 in the same
#   mutant, so a note from one of them would mean the axis under test is not the
#   one doing the work.
#
#   THE REDS COME FROM THE THRESHOLDS. One further case relaxes all six at once
#   and requires the lane GREEN with zero size findings. Without it, a lane that
#   refused every input would satisfy all six cases above.
#
#   THE TRACKED TREE STILL PASSES. The baseline case runs the lane over an
#   unmutated copy and requires exit 0. A canary that only proves failure cannot
#   catch a lane that fails on everything.
#
# FAIL vs ERROR, AND WHY IT IS READ OFF THE OUTPUT RATHER THAN THE EXIT CODE.
# §2 asks a gate for exit codes that separate a verdict from a precondition
# failure, and asks the canary to assert the exact code. THAT IS UNAVAILABLE
# HERE, for a mechanical reason rather than an oversight: the lane is a `make`
# TARGET, and GNU make collapses every recipe failure to its own exit 2 — a
# clang-tidy finding and the lane's own "no run-clang-tidy" refusal are
# indistinguishable by status. Measured: both produce `Error 1` from the recipe
# and exit 2 from make. So this suite does two things instead. It resolves the
# tool ITSELF, up front, the way lint.mk resolves it, and exits 3 (CANNOT RUN)
# when it is absent — so a missing toolchain can never reach a case and be read
# as a verdict. And every case then classifies the run on OUTPUT: the analysis
# must have been REACHED (run-clang-tidy's own progress line, over a non-empty
# file set) and must carry no `[clang-diagnostic-error]`, which is what a bad
# compile database looks like. A red that fails either test is reported as an
# ERROR, distinctly, and never counted as the clause firing.
#
# HERMETIC — no plant-and-restore, and therefore no restore anybody has to take
# on trust. The lane hardcodes `cd renderer` and regenerates the compile database
# from that directory's own make variables, so it cannot be pointed at a config
# elsewhere; what it CAN be pointed at is a copy of the tree. Every case runs the
# real lane inside a throw-away copy under a temp directory, and the only tracked
# file any case names for writing is that copy's `.clang-tidy`. The tracked
# original is byte-compared at the end against a pristine copy taken at the
# start, so the claim is asserted rather than asserted-in-prose.
#
# COST, MEASURED rather than estimated. Eight lane runs, each regenerating the
# compile database and analysing the whole hand-authored C set: roughly 6 s per
# run on a 32-core host, so about 50 s for the suite, plus one tree copy. The
# figure that matters to a person waiting on a push is the pair — canary then
# gate, in one container — at 58 s through tools/uber.sh. It is wired beside the
# gate in the push hook's docker-gated block rather than folded into `lint`
# because it needs exactly what the gate needs; see the lane comment in lint.mk.
#
# Usage: bash tools/lint/test/c_tidy_size_test.sh
#   in-container: tools/uber.sh 'make -f lint.mk lint-c-tidy-test'
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
LANE_MK="$ROOT/lint.mk"
TIDY_CFG="$ROOT/renderer/.clang-tidy"
WASM_MK="$ROOT/renderer/wasm.mk"

for f in "$LANE_MK" "$TIDY_CFG" "$WASM_MK"; do
	[ -f "$f" ] || {
		printf '\033[31mFAIL\033[0m — not found: %s\n' "$f" >&2
		printf '  This suite drives the real lane over a copy of the tree, so all three\n' >&2
		printf '  of lint.mk, renderer/.clang-tidy and renderer/wasm.mk must be present.\n' >&2
		exit 3
	}
done

# THE TOOL, RESOLVED AT THE SEAM. lint.mk resolves the driver as
# `$(firstword $(wildcard /opt/wasi-sdk/bin/run-clang-tidy) run-clang-tidy)`;
# the same rule is applied here so that an absent toolchain is reported as
# CANNOT RUN by this suite rather than surfacing as a make failure inside a case,
# where it would wear the same exit status as a finding (see the header).
# `$(wildcard …)` matches on EXISTENCE, and the fallback is the BARE WORD rather
# than a resolved path — which is what the lane goes on to print. Resolving to
# `command -v`'s absolute path here instead would make the "same driver"
# assertion below a false red on any machine without the SDK, where the lane
# prints `run-clang-tidy` and this suite would have been comparing `/usr/bin/…`.
if [ -e /opt/wasi-sdk/bin/run-clang-tidy ]; then
	RESOLVED_DRIVER=/opt/wasi-sdk/bin/run-clang-tidy
else
	RESOLVED_DRIVER=run-clang-tidy
fi
# The lane's OWN presence test, applied to the same resolved name.
if ! command -v "$RESOLVED_DRIVER" > /dev/null 2>&1; then
	printf '\033[31mFAIL\033[0m — cannot run: no run-clang-tidy at /opt/wasi-sdk/bin and none on PATH.\n' >&2
	printf '  The pinned one ships with the WASI-SDK, so run this inside the toolchain\n' >&2
	printf '  container: tools/uber.sh '\''make -f lint.mk lint-c-tidy-test'\''\n' >&2
	exit 3
fi

# THE SHARED MUTATION PRIMITIVE. Absence is a HARD failure, never a skip: every
# proof below is a mutation, so a soft-failing source would leave this suite
# reporting cases it never ran, in a shape indistinguishable from green.
MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every case here proves an axis by moving its threshold alone, so without\n' >&2
	printf '  this file the suite cannot break anything and its green means nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/c-tidy-size-test.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

fails=0
ok() { printf '  \033[32mok\033[0m   %s\n' "$1"; }
bad() {
	printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2
	fails=$((fails + 1))
}
banner() { printf '\n\033[1m[c-tidy-size] %s\033[0m\n' "$*"; }

# THE CONFIG KEY PREFIX, and the six axes with the note wording each one is
# reported through. The note is the ONLY discriminator: all six are the same
# check with the same name, so `[readability-function-size]` attributes nothing.
# Each marker is deliberately written WITHOUT its value, so a neighbour that
# fired at its relaxed threshold would still be caught by the absence assertions.
KEY='readability-function-size'
AXES='LineThreshold StatementThreshold BranchThreshold NestingThreshold VariableThreshold ParameterThreshold'

note_for() {
	case "$1" in
	LineThreshold) printf 'lines including whitespace and comments (threshold' ;;
	StatementThreshold) printf 'statements (threshold' ;;
	BranchThreshold) printf 'branches (threshold' ;;
	NestingThreshold) printf 'starts here (threshold' ;;
	VariableThreshold) printf 'variables (threshold' ;;
	ParameterThreshold) printf 'parameters (threshold' ;;
	*)
		printf 'UNKNOWN-AXIS-%s' "$1"
		return 1
		;;
	esac
}

# The primitives prove they can REFUSE before anything depends on them.
banner 'the mutation and substring primitives must be able to REFUSE'
mutate_selftest "$WORK/mutate-selftest" || fails=$((fails + 1))
contains_selftest "$WORK/contains-selftest" || fails=$((fails + 1))

# ---------------------------------------------------------------------------
# The hermetic tree. ONE copy, reused across cases: the only thing that differs
# between them is .clang-tidy, and the lane regenerates the compile database on
# every invocation by design, so nothing carries over.
banner 'the hermetic copy'
TREE="$WORK/tree"
mkdir -p "$TREE"
cp -a -- "$ROOT/lint.mk" "$TREE/lint.mk"
cp -a -- "$ROOT/renderer" "$TREE/renderer"
COPY_CFG="$TREE/renderer/.clang-tidy"
PRISTINE="$WORK/clang-tidy.pristine"
cp -a -- "$TIDY_CFG" "$PRISTINE"

if cmp -s -- "$TIDY_CFG" "$COPY_CFG"; then
	ok 'the copied tree starts byte-identical to the tracked .clang-tidy'
else
	bad 'the copy does not match the tracked .clang-tidy — every case below would
  then be judging a config nobody committed'
fi

# axis_value <file> <axis> — the value currently declared for one axis.
# DERIVED rather than hardcoded on purpose: a mutation anchored on a literal
# would break on a legitimate LOWERING of that ceiling, and the case would then
# red for maintenance rather than for a defect. The literals live in one place
# instead — the ratchet block at the end, which is independent of everything
# here precisely so it can detect the numbers moving.
axis_value() {
	local f="$1" axis="$2" v
	v="$(sed -n "s/^[[:space:]]*${KEY}\.${axis}: '\([0-9][0-9]*\)'.*/\1/p" "$f")"
	case "$v" in
	'')
		printf 'axis_value: %s declares no %s.%s\n' "$f" "$KEY" "$axis" >&2
		return 1
		;;
	*[!0-9]*)
		printf 'axis_value: %s.%s is not a single number: %s\n' "$KEY" "$axis" "$v" >&2
		return 1
		;;
	esac
	printf '%s' "$v"
}

# set_axis <axis> <new> — make the COPY declare one threshold, proving it did.
#
# THE NO-OP BRANCH IS NOT A SHORTCUT. `mutate_file` REFUSES a replacement that
# leaves the file unchanged, which is right for a mutation and wrong for an
# assignment: a tracked threshold that already equals the value a case wants —
# a neighbour genuinely declared at 9999, say — is the state the case is asking
# for, and refusing it would report "nothing was proven" about a config that
# says exactly what was asked. Measured: with one ceiling standing at 9999 the
# earlier form reported five spurious failures beside the one real finding, and
# the noise was what obscured it. What the caller needs guaranteed is the state
# AFTERWARDS, so that is what is asserted, on a re-read, in both branches.
set_axis() {
	local axis="$1" new="$2" cur after
	cur="$(axis_value "$COPY_CFG" "$axis")" || return 1
	if [ "$cur" != "$new" ]; then
		mutate_file "$COPY_CFG" "$KEY.$axis: '$cur'" "$KEY.$axis: '$new'" || return 1
	fi
	after="$(axis_value "$COPY_CFG" "$axis")" || return 1
	if [ "$after" != "$new" ]; then
		printf 'set_axis: %s reads %s after being set to %s\n' "$axis" "$after" "$new" >&2
		return 1
	fi
	return 0
}

# reset_cfg — restore the copy's config from the tracked original.
reset_cfg() { cp -a -- "$TIDY_CFG" "$COPY_CFG"; }

# run_lane — invoke the REAL lane in the copied tree.
# The lane is invoked BARE and its own status is read on the very next line.
# Piping it into anything would report the FILTER's status, which is the defect
# this repo has paid for more than once.
RUN_OUT=''
RUN_RC=0
RUN_FILES=0
RUN_DB=0
run_lane() {
	set +e
	RUN_OUT="$(cd "$TREE" && make -f lint.mk lint-c-tidy 2>&1)"
	RUN_RC=$?
	set -e
	RUN_FILES=0
	RUN_DB=0
	if [[ "$RUN_OUT" =~ for\ ([0-9]+)\ files\ out\ of\ ([0-9]+)\ in\ compilation\ database ]]; then
		RUN_FILES="${BASH_REMATCH[1]}"
		RUN_DB="${BASH_REMATCH[2]}"
	fi
}

# reached_analysis <case-name> — did the run get as far as analysing anything?
# This is what separates a VERDICT from an ERROR, given that make cannot (see
# the header). A run that never printed run-clang-tidy's progress line, analysed
# zero files, or reported a clang-diagnostic-error has not exercised the size
# check at all, whatever colour it wore.
reached_analysis() {
	local name="$1"
	if [ "$RUN_FILES" -lt 1 ]; then
		bad "$name: the lane analysed ZERO files (its progress line is absent or
  reports none) — an empty input set is a failure, never a verdict"
		return 1
	fi
	if [ "$RUN_FILES" -ne "$RUN_DB" ]; then
		bad "$name: analysed $RUN_FILES of $RUN_DB files in the database — the lane
  passes no filter, so a shortfall means files were skipped, not judged"
		return 1
	fi
	if contains "$RUN_OUT" '[clang-diagnostic-error]'; then
		bad "$name: the run carries a clang-diagnostic-error — that is a broken
  compile database inventing parse errors, not the size check refusing"
		return 1
	fi
	return 0
}

# ---------------------------------------------------------------------------
banner 'baseline — the tracked config must PASS over the tracked C'
reset_cfg
run_lane
if [ "$RUN_RC" -eq 0 ]; then
	ok "tracked config: the lane exits 0 ($RUN_FILES files analysed)"
else
	bad "tracked config: expected exit 0, got $RUN_RC — nothing below can attribute
  anything while the unmutated tree is already red. The lane said:
$RUN_OUT"
fi
if reached_analysis 'baseline'; then
	ok "baseline is non-vacuous ($RUN_FILES of $RUN_DB database entries analysed)"
fi
BASE_FILES="$RUN_FILES"
if ! contains "$RUN_OUT" "$RESOLVED_DRIVER"; then
	bad "baseline: the lane did not report the driver this suite resolved
  ($RESOLVED_DRIVER) — the suite and the lane are not agreeing on which
  run-clang-tidy is under test"
else
	ok "the lane ran the same driver this suite resolved ($RESOLVED_DRIVER)"
fi

# ---------------------------------------------------------------------------
banner 'each axis refuses for its OWN reason, with its five neighbours silent'

# case_axis <axis> — tighten ONE axis to 1, relax the other five to 9999, and
# require a refusal attributable to that axis alone. This is the mutation shape
# the thresholds were originally measured with, which is what makes it the right
# one to keep: it is the same experiment, kept runnable.
case_axis() {
	local axis="$1" other note landed=1
	reset_cfg
	if ! set_axis "$axis" 1; then
		bad "$axis: the mutation did not land — nothing was proven"
		return 0
	fi
	for other in $AXES; do
		if [ "$other" = "$axis" ]; then continue; fi
		if ! set_axis "$other" 9999; then
			bad "$axis: could not relax the neighbour $other — nothing was proven"
			landed=0
		fi
	done
	if [ "$landed" -ne 1 ]; then return 0; fi

	run_lane
	reached_analysis "$axis" || return 0
	if [ "$RUN_RC" -eq 0 ]; then
		bad "$axis: the lane exited 0 with that threshold at 1 — the axis is not
  enforced, or the config under test is not the one being read"
		return 0
	fi
	if [ "$RUN_FILES" -ne "$BASE_FILES" ]; then
		bad "$axis: analysed $RUN_FILES files against a baseline of $BASE_FILES — the
  mutant is not judging the same population, so the red is not comparable"
		return 0
	fi
	if ! contains "$RUN_OUT" "[$KEY"; then
		bad "$axis: it went red, but no finding names $KEY — some other check refused,
  which says nothing about this one"
		return 0
	fi
	note="$(note_for "$axis" || true)"
	if ! contains "$RUN_OUT" "$note 1)"; then
		bad "$axis: no finding reports [$note 1)] — either the axis did not fire, or
  clang-tidy applied a threshold this case did not write, which would mean it
  read some other config"
		return 0
	fi
	for other in $AXES; do
		if [ "$other" = "$axis" ]; then continue; fi
		if contains "$RUN_OUT" "$(note_for "$other")"; then
			bad "$axis: the neighbour $other also reported, at its relaxed 9999 — the red
  is not attributable to $axis"
			return 0
		fi
	done
	ok "$axis: refuses at 1, reports its own note, five neighbours silent"
}

for a in $AXES; do case_axis "$a"; done

# ---------------------------------------------------------------------------
banner 'the reds above come from the THRESHOLDS, not from a lane that refuses everything'

# Relaxing all six at once must leave the lane GREEN. Without this, a lane broken
# in some way that reddens every input would have satisfied all six cases above,
# and their neighbour-silence assertions with them.
reset_cfg
relaxed=1
for a in $AXES; do
	if ! set_axis "$a" 9999; then
		bad "relaxed control: could not relax $a — the control proves nothing"
		relaxed=0
	fi
done
if [ "$relaxed" -eq 1 ]; then
	run_lane
	if [ "$RUN_RC" -ne 0 ]; then
		bad "relaxed control: every threshold at 9999 and the lane still exits $RUN_RC —
  the six reds above are not attributable to the size thresholds. The lane said:
$RUN_OUT"
	elif reached_analysis 'relaxed control'; then
		if contains "$RUN_OUT" "[$KEY"; then
			bad 'relaxed control: a size finding survived a 9999 threshold on every axis'
		else
			ok "every threshold at 9999: the lane is green and reports no $KEY finding"
		fi
	fi
fi

# ---------------------------------------------------------------------------
banner 'THE SIX CEILINGS ARE PINNED to literals — they move DOWN only'

# INDEPENDENT OF EVERYTHING ABOVE, on purpose. The cases above DERIVE each
# threshold from the config so a legitimate lowering does not red them; a check
# built on a derived value cannot detect that value moving, which is exactly the
# bypass a ceiling needs protection from. These literals are the other half:
# raising one to clear a red reds this suite instead, so the change has to be a
# deliberate edit here, on the record — what `.claude/rules/gate-enforcement.md`
# § A METRIC CEILING asks for and what nothing but review would otherwise catch.
#
# THE HONEST SCOPE: an UNCOORDINATED raise reds this block. A COORDINATED one
# must edit two sites, one of which exists only to say the number goes down. It
# is not proof against a determined deliberate edit; nothing a canary can do is.
# That residue belongs to review.
#
# The provenance of each number is recorded beside it in renderer/.clang-tidy —
# each is this tree's measured maximum on that axis, seeded so the check is green
# on arrival with zero exemptions.
while read -r axis pinned; do
	[ -n "$axis" ] || continue
	cur="$(axis_value "$TIDY_CFG" "$axis" || true)"
	if [ "$cur" = "$pinned" ]; then
		ok "renderer/.clang-tidy still declares $axis: '$pinned'"
	else
		bad "$axis is now '$cur', pinned here at '$pinned' — a ceiling moves DOWN only.
  A LOWERING is welcome: update the literal in this block, on the record. A
  RAISE is a gate bypass in source form (gate-enforcement.md § A METRIC CEILING)."
	fi
done <<'PINNED'
LineThreshold 821
StatementThreshold 554
BranchThreshold 110
NestingThreshold 8
VariableThreshold 44
ParameterThreshold 6
PINNED

# ---------------------------------------------------------------------------
banner 'the tracked tree was never written'

# The only tracked file any case names for writing is the COPY's .clang-tidy.
# This asserts it rather than leaving it to be read off the code above.
if cmp -s -- "$PRISTINE" "$TIDY_CFG"; then
	ok 'renderer/.clang-tidy is byte-identical to its pre-run state'
else
	bad 'renderer/.clang-tidy CHANGED during this run — the suite mutated the tracked
  tree instead of its copy, and the tree must be restored by hand before
  anything here is believed'
fi

printf '\n'
if [ "$fails" -gt 0 ]; then
	printf '\033[31m[c-tidy-size] %d case(s) FAILED\033[0m\n' "$fails" >&2
	exit 1
fi
printf '\033[32m[c-tidy-size]\033[0m all six size axes refuse for their own reason, and the tracked config passes\n'
