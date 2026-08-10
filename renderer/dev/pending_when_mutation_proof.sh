#!/usr/bin/env bash
# pending_when_mutation_proof.sh — the ATTRIBUTED red for the pending_when
# reactive binding (WidgetNode.pending_when -> LV_STATE_USER_1).
#
# WHY THIS EXISTS RATHER THAN A BARE RED. The two harness tests in
# `renderer/wasm_harness/tests/visual_regression.rs` (mod value_conditional_style)
# were watched to FAIL against the unwired renderer and to pass once it was
# wired. That is a red, and `.claude/rules/regression-test-first.md` is explicit
# that a red is not enough: this feature has FOUR independent clauses that would
# each make the same test fail, so a bare red is compatible with three of them
# being dead. Each case below breaks exactly ONE of them and requires a named
# CONTROL — a sibling test that must stay GREEN — so the red is attributable.
#
# THE FOUR CLAUSES, and why one test cannot separate them:
#   C1  the full-load DRAIN loop in renderer.c            (nothing attaches)
#   C2  the EQ native-bind arm  (lv_obj_bind_state_if_eq) (EQ screens only)
#   C3  the range OBSERVER arm  (pendstate_observer_cb)   (GT/GTE/LT/LTE only)
#   C4  the dump_tree ORACLE in main.c                    (the bit is invisible)
# C1 and C4 both redden BOTH tests, so neither is separable from the other by
# the harness alone; they are separated here by WHICH FILE is mutated. C2 and C3
# are separated by the compare op, which is why the suite carries an EQ test and
# a GT test rather than two of one kind.
#
# EVERY MUTATION IS WRONG-BUT-LEGAL — LV_STATE_USER_2 for USER_1, a drained
# count of zero — never a syntax error. A mutation that fails to COMPILE reddens
# the run while executing nothing, which is an ERROR wearing a FAIL's colour and
# proves the opposite of what it appears to.
#
# MUTATIONS GO THROUGH `mutate_file`, which REFUSES an anchor that is absent or
# AMBIGUOUS. That refusal is the point: `LV_STATE_USER_1` occurs four times in
# renderer.c, so a naive replace would break several clauses at once and the
# resulting red would attribute to none of them. Every anchor below therefore
# carries enough surrounding text to be unique, and the primitive proves it.
#
# COST. Each case rebuilds the wasm, so this is minutes, not seconds. It is a
# dev proof, not a battery lane — `check-renderer` runs the tests, this script
# is what says the tests have teeth.
#
# Usage (from the repo root, on the host — it shells into the pinned image):
#   bash renderer/dev/pending_when_mutation_proof.sh
# ROOT is $1 (default $PWD) and is VERIFIED: a script that derives its root from
# its own location silently retargets the moment it is copied somewhere else.
set -uo pipefail

ROOT="${1:-$PWD}"
[ -f "$ROOT/renderer.mk" ] && [ -d "$ROOT/renderer/wasm_harness" ] || {
	echo "FATAL: $ROOT is not a protogen checkout root" >&2
	exit 2
}

MUTATE_LIB="$ROOT/tools/lint/test/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	echo "FATAL: missing mutation primitive at $MUTATE_LIB — every proof here is a" >&2
	echo "  mutation, so without it this suite cannot break anything and a green" >&2
	echo "  would mean nothing." >&2
	exit 2
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

RENDERER="$ROOT/renderer/src/renderer.c"
MAIN="$ROOT/renderer/src/main.c"
FILES=("$RENDERER" "$MAIN")

BK="$(mktemp -d)"
OUT="$ROOT/.fork-scratch/pending-when"
mkdir -p "$OUT"
for f in "${FILES[@]}"; do cp "$f" "$BK/$(basename "$f")"; done

restore() {
	for f in "${FILES[@]}"; do cp "$BK/$(basename "$f")" "$f"; done
}
trap 'restore; rm -rf "$BK"' EXIT

fails=0
bad() {
	printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2
	fails=$((fails + 1))
}
ok() { printf '  \033[32mok\033[0m   %s\n' "$1"; }

# Build the wasm and run ONE test filter. Sets RUN_OUT / RUN_RC.
# The two commands are separate so a BUILD failure is never reported as a test
# verdict: a mutation that broke the compile would otherwise look exactly like
# the clause firing.
build_wasm() {
	bash "$ROOT/tools/uber.sh" 'make -f renderer.mk wasm' >"$OUT/build.log" 2>&1
}
run_tests() {
	RUN_OUT="$(bash "$ROOT/tools/uber.sh" \
		"cd renderer/wasm_harness && PATH=\$HOME/.cargo/bin:\$PATH cargo test \
		 --test visual_regression $1 2>&1")"
	RUN_RC=$?
}

# Read the kaocha-equivalent summary line cargo prints. Reported for every run,
# because "0 passed; 0 failed" is a filter that matched NOTHING and is otherwise
# indistinguishable from a pass.
summary() { printf '%s\n' "$RUN_OUT" | grep -E '^test result:' | tail -1; }
passed_count() { printf '%s\n' "$RUN_OUT" | sed -nE 's/^test result:.* ([0-9]+) passed.*/\1/p' | tail -1; }
failed_count() { printf '%s\n' "$RUN_OUT" | sed -nE 's/^test result:.*; ([0-9]+) failed.*/\1/p' | tail -1; }

# $1 label  $2 file  $3 anchor  $4 replacement  $5 red-filter  $6 control-filter
mutate_case() {
	local label="$1" file="$2" old="$3" new="$4" red="$5" control="$6"
	echo
	echo "============================================================"
	echo "MUTATION: $label"
	echo "  file: ${file#"$ROOT"/}"
	restore
	if ! mutate_file "$file" "$old" "$new"; then
		bad "$label: mutation did NOT land — any verdict below would be meaningless"
		restore
		return
	fi
	echo "  landed: anchor replaced (mutate_file verified present-then-absent)"
	if ! build_wasm; then
		bad "$label: the MUTANT DID NOT BUILD — this is an ERROR, not a red. A
       mutation must be wrong-but-legal; see $OUT/build.log"
		restore
		return
	fi
	run_tests "$red"
	echo "  RED arm   ($red): $(summary)"
	if [ "$(failed_count)" -ge 1 ] && [ "$RUN_RC" -ne 0 ]; then
		ok "$label: the clause fired — $(failed_count) failed"
	else
		bad "$label: expected the red arm to FAIL and it did not. The clause under
       test is not what makes the test pass."
	fi
	run_tests "$control"
	echo "  CONTROL   ($control): $(summary)"
	if [ "$(passed_count)" -ge 1 ] && [ "$RUN_RC" -eq 0 ]; then
		ok "$label: control stayed green — the red is attributable to this clause"
	else
		bad "$label: the CONTROL went red too, so the mutation is not scoped to one
       clause and the red above attributes to nothing."
	fi
	restore
}

echo "############ BASELINE (unmutated) ############"
build_wasm || {
	echo "FATAL: the UNMUTATED tree does not build — nothing below is meaningful" >&2
	exit 2
}
run_tests value_conditional_style
echo "baseline: $(summary)"
[ "$RUN_RC" -eq 0 ] || {
	echo "FATAL: the unmutated suite is not green; fix that before reading a mutation" >&2
	exit 2
}

# ── C1: the full-load DRAIN. The queue fills and is never applied, so no
#        binding of either kind is ever attached. Legal C, zero iterations.
mutate_case drain-loop-never-runs "$RENDERER" \
	'  for (int i = 0; i < pending_pendstate_count; i++) {
    apply_pending_when(pending_pendstate[i].obj, &pending_pendstate[i].bind);
  }' \
	'  for (int i = 0; i < 0; i++) {
    apply_pending_when(pending_pendstate[i].obj, &pending_pendstate[i].bind);
  }' \
	value_conditional_style::pending_when \
	value_conditional_style::enabled_when_eq_toggles_disabled_state

# ── C2: the EQ NATIVE-BIND arm binds USER_2 — a real lv_state_t bit, so it
#        type-checks and simply drives the wrong state. Invisible to the GT
#        test (the CONTROL), which never reaches this arm.
mutate_case eq-native-bind-wrong-state "$RENDERER" \
	'    lv_obj_bind_state_if_eq(obj, &entry->subject, LV_STATE_USER_1,
                            bind->ref_value);' \
	'    lv_obj_bind_state_if_eq(obj, &entry->subject, LV_STATE_USER_2,
                            bind->ref_value);' \
	value_conditional_style::pending_when_eq_toggles_pending_state \
	value_conditional_style::pending_when_gt_uses_range_observer

# ── C3: the RANGE OBSERVER sets USER_2. Same shape as C2 on the other arm, and
#        the CONTROL is the EQ test, which takes the native path and cannot see
#        this. Only the ADD is mutated: the remove still clears USER_1, so the
#        mutant is a widget that never becomes pending rather than one that
#        never stops.
mutate_case range-observer-wrong-state "$RENDERER" \
	'    lv_obj_add_state(obj, LV_STATE_USER_1);
  } else {
    lv_obj_remove_state(obj, LV_STATE_USER_1);' \
	'    lv_obj_add_state(obj, LV_STATE_USER_2);
  } else {
    lv_obj_remove_state(obj, LV_STATE_USER_1);' \
	value_conditional_style::pending_when_gt_uses_range_observer \
	value_conditional_style::pending_when_eq_toggles_pending_state

# ── C4: the dump_tree ORACLE reads USER_2. The renderer is wired correctly and
#        the bit IS set; only the observability is broken. This is the clause a
#        framebuffer-based test would not have needed and a dump-based one
#        wholly depends on, so it is the one most worth pinning: without it the
#        other three greens would be reporting on a key nobody emits.
mutate_case dump-oracle-reads-wrong-state "$MAIN" \
	'  if (lv_obj_has_state(obj, LV_STATE_USER_1))
    tree_append(",\"pending\":true");' \
	'  if (lv_obj_has_state(obj, LV_STATE_USER_2))
    tree_append(",\"pending\":true");' \
	value_conditional_style::pending_when \
	value_conditional_style::enabled_when_eq_toggles_disabled_state

echo
echo "############ RESTORED — final control run ############"
restore
build_wasm || bad "the RESTORED tree does not build"
run_tests value_conditional_style
echo "restored: $(summary)"
[ "$RUN_RC" -eq 0 ] || bad "the restored suite is not green — the tree was left mutated"
for f in "${FILES[@]}"; do
	if cmp -s "$BK/$(basename "$f")" "$f"; then
		ok "restored: ${f#"$ROOT"/}"
	else
		bad "NOT restored: ${f#"$ROOT"/}"
	fi
done

echo
if [ "$fails" -eq 0 ]; then
	printf '\033[32mPENDING_WHEN MUTATION PROOF: 4 clauses, each attributed\033[0m\n'
else
	printf '\033[31mPENDING_WHEN MUTATION PROOF: %d failure(s)\033[0m\n' "$fails"
fi
exit $((fails == 0 ? 0 : 1))
