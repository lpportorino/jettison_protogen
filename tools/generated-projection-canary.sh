#!/usr/bin/env bash
# generated-projection-canary.sh — prove each `generated-projection` clause
# fails for ITS OWN reason, and that the lane still speaks when it cannot write.
#
# A GATE NOBODY HAS WATCHED FAIL IS A PRESCRIPTION, NOT A GATE. This lane is a
# cmp loop over committed files, so its clean output and its checked-nothing
# output are the same sentence; green says nothing until failure has been
# demonstrated, per .claude/rules/gate-enforcement.md §2.
#
# A RED IS NOT ENOUGH; IT MUST NAME ITS CLAUSE. Five of this lane's clauses
# refuse overlapping inputs — a DELETED destination fails `cmp` exactly as a
# STALE one does — so a bare red is compatible with the clause under test being
# dead. Every case below therefore:
#
#   1. builds a fresh synthetic tree and asserts the lane is GREEN on it, so the
#      red that follows is attributable to the mutation and not to the fixture;
#   2. asserts the mutation LANDED — the new state present AND the old state
#      absent, on the exact bytes. A mutation that matched nothing yields a
#      mutant identical to the original, whose result then reads as attribution
#      while proving the opposite;
#   3. requires the EXACT exit code, never merely non-zero: 1 is a staleness
#      VERDICT and 3 is a PRECONDITION this lane could not judge, and a suite
#      that accepts either accepts a broken harness as proof a clause fired;
#   4. requires the expected clause's own message, AND requires a NAMED
#      NEIGHBOUR clause to stay SILENT on the same mutant.
#
# HERMETIC, and deliberately not driven against the checkout: every case runs
# `make -f <repo>/renderer.mk generated-projection` with the cwd set to a
# throw-away tree built from the lane's OWN roster
# (`generated-projection-roster`). Nothing here reads, writes or restores a
# tracked file, so the suite runs on a dirty checkout and cannot strand the tree.
#
# THE READ-ONLY CASE INJECTS AT THE SEAM RATHER THAN MOUNTING READ-ONLY, and
# that substitution is the one thing in here that is not the real thing. The
# behaviour under test is what renderer.yml's `:ro` mount produces: the heal's
# `mktemp` fails, and the lane must still print its staleness verdict. Two ways
# to reproduce that are unavailable — `chmod a-w` does nothing to root, which is
# what the pinned image runs as, and a real read-only bind mount needs a docker
# CLI the toolchain image does not carry (.claude/rules/uber-container.md). So a
# failing `mktemp` is placed ahead of the real one on PATH: the same seam, the
# same failure, the same exit status, and the production recipe is otherwise
# untouched. Its control — the same stub over an UNMUTATED tree, which must stay
# GREEN — is what keeps the stub from being able to manufacture the red.
#
#   0  every case attributed        1  a case FAILED        3  CANNOT RUN

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MK="$ROOT/renderer.mk"
PASS=0
FAIL=0
WORK=""

ok() {
	PASS=$((PASS + 1))
	printf '  ok   %s\n' "$1"
}

bad() {
	FAIL=$((FAIL + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$1"
	[ $# -gt 1 ] && printf '%s\n' "$2" | sed 's/^/       | /'
	return 0
}

cannot_run() {
	printf '\033[31m[generated-projection-canary] CANNOT RUN\033[0m — %s\n' "$1" >&2
	printf '  %s\n' "$2" >&2
	exit 3
}

cleanup() { [ -n "$WORK" ] && rm -rf "$WORK"; }
trap cleanup EXIT

# ── roster, from the lane itself ────────────────────────────────────────────
[ -f "$MK" ] || cannot_run "renderer.mk is not at $MK" \
	"This script resolves the repo root from its own location; it must stay in tools/."

ROSTER="$(make -s -f "$MK" generated-projection-roster 2>/dev/null)"
ROSTER_RC=$?
[ "$ROSTER_RC" -eq 0 ] || cannot_run \
	"'make -f renderer.mk generated-projection-roster' exited $ROSTER_RC" \
	"The canary derives its fixture from that target; without it there is nothing to build."

ROOT_FILES="$(printf '%s\n' "$ROSTER" | sed -n 's/^root //p')"
UI_FILES="$(printf '%s\n' "$ROSTER" | sed -n 's/^ui //p')"
UI_LINKS="$(printf '%s\n' "$ROSTER" | sed -n 's/^link //p')"

n_root=$(printf '%s\n' "$ROOT_FILES" | grep -c .)
n_ui=$(printf '%s\n' "$UI_FILES" | grep -c .)
n_link=$(printf '%s\n' "$UI_LINKS" | grep -c .)

# NON-VACUITY, floored per line rather than as a union: any populated line
# satisfies a total, so one list going dark would be invisible.
for pair in "root:$n_root" "ui:$n_ui" "link:$n_link"; do
	if [ "${pair##*:}" -lt 1 ]; then
		cannot_run "the projection roster's '${pair%%:*}' list is EMPTY" \
			"Every case below would then build a fixture with nothing in it and pass over zero coverage."
	fi
done
n_files=$((n_root + n_ui))

WORK="$(mktemp -d)" || cannot_run "mktemp -d failed" "The suite needs a writable temp dir."

# A failing mktemp, ahead of the real one on PATH — see the header.
STUBBIN="$WORK/stub-bin"
mkdir -p "$STUBBIN"
cat >"$STUBBIN/mktemp" <<'STUB'
#!/usr/bin/env bash
echo "mktemp: failed to create file via template '$*': Read-only file system" >&2
exit 1
STUB
chmod 755 "$STUBBIN/mktemp"

# ── fixture ─────────────────────────────────────────────────────────────────
# Distinct bytes per file, so a pair wired to the wrong partner cannot compare
# equal by accident.
mkfixture() {
	local d="$1" f
	rm -rf "$d"
	mkdir -p "$d/output/c/ui" "$d/renderer/generated/ui"
	for f in $ROOT_FILES; do
		printf 'root-payload %s\n' "$f" >"$d/output/c/$f"
		cp "$d/output/c/$f" "$d/renderer/generated/$f"
	done
	for f in $UI_FILES; do
		printf 'ui-payload %s\n' "$f" >"$d/output/c/ui/$f"
		cp "$d/output/c/ui/$f" "$d/renderer/generated/$f"
	done
	for f in $UI_LINKS; do
		ln -sf "../$f" "$d/renderer/generated/ui/$f"
	done
}

# Runs the lane in $1, leaving OUT (merged streams), RC (make's own, unpiped
# status) and LANE_RC (the RECIPE's status) for the caller. Never pipe make: a
# pipeline reports the last command's status, and this suite's whole subject is
# which code came back.
#
# MAKE NORMALISES EVERY RECIPE FAILURE TO 2 — it does not propagate the recipe's
# own status, and measuring that is what stopped this suite asserting a code no
# invocation can return. The recipe's code survives in exactly one place, make's
# own `*** [<file>:<line>: generated-projection] Error N` line, so that is where
# the split is read. A CALLER must never branch on make's status expecting to see
# 1 or 3; renderer.mk records what to do instead.
OUT=""
RC=0
LANE_RC=0
run_lane() {
	local d="$1"
	shift
	OUT="$(cd "$d" && env "$@" make -s -f "$MK" generated-projection 2>&1)"
	RC=$?
	if [ "$RC" -eq 0 ]; then
		LANE_RC=0
	else
		LANE_RC="$(printf '%s\n' "$OUT" |
			sed -n 's/.*generated-projection\] Error \([0-9][0-9]*\).*/\1/p' | tail -n 1)"
		[ -n "$LANE_RC" ] || LANE_RC="unreported"
	fi
}

# first name in each list, resolved once
first_root="$(printf '%s\n' "$ROOT_FILES" | head -n 1)"
first_ui="$(printf '%s\n' "$UI_FILES" | head -n 1)"
first_link="$(printf '%s\n' "$UI_LINKS" | head -n 1)"

# Substring tests use bash's own pattern matching, never `grep`, and that is not
# a style preference. `grep` answers with 1 for "no match" and 2 for "I failed",
# and a helper that treats any non-zero as absent CANNOT TELL THE TWO APART — so
# a grep that errors for a reason nobody sees is reported as a missing message,
# which is a FAIL blaming the gate for a fault in the suite. One such
# unreproducible FAIL was observed here (the asserted text was plainly present in
# the very output the failure printed) and its cause was never established; the
# SIGPIPE-under-pipefail theory was tested at 10 MB with an immediate match and
# REFUTED, so this is not that fix. It removes the external process instead of
# explaining it: `case` runs in the shell, has no exit status to misread, and no
# PATH, no fork and no resource limit stands between the question and the answer.
has() { case "$OUT" in *"$1"*) return 0 ;; *) return 1 ;; esac; }

# Same reasoning, for a file's contents.
file_has() {
	local body
	body="$(cat "$1")" || return 2
	case "$body" in *"$2"*) return 0 ;; *) return 1 ;; esac
}

# Asserts a case in one place, so no case can forget the neighbour half.
# $1 label  $2 want-rc  $3 want-substring  $4.. must-be-absent substrings
expect() {
	local label="$1" want_rc="$2" want="$3"
	shift 3
	local problem=""
	if [ "$LANE_RC" != "$want_rc" ]; then
		problem="the recipe exited $LANE_RC, wanted $want_rc"
	elif [ "$RC" -ne 2 ]; then
		# Pins the normalisation itself: if make ever started propagating the
		# recipe's code, LANE_RC above would keep passing while every caller's
		# assumption silently changed.
		problem="make's own status was $RC, wanted the 2 it normalises every recipe failure to"
	fi
	if [ -z "$problem" ] && ! has "$want"; then
		problem="output never said: $want"
	fi
	local absent
	for absent in "$@"; do
		[ -n "$problem" ] && break
		if has "$absent"; then
			problem="neighbour clause spoke on this mutant: $absent"
		fi
	done
	if [ -n "$problem" ]; then
		bad "$label — $problem" "$OUT"
	else
		ok "$label"
	fi
}

# A case's fixture must be GREEN before it is mutated, or its red proves nothing.
green_baseline() {
	local d="$1" label="$2"
	shift 2
	run_lane "$d" "$@"
	if [ "$RC" -ne 0 ]; then
		bad "$label (baseline) — a freshly built fixture was not green (exit $RC)" "$OUT"
		return 1
	fi
	return 0
}

printf '[generated-projection-canary] roster: %s root + %s ui = %s files, %s flatten symlinks\n' \
	"$n_root" "$n_ui" "$n_files" "$n_link"

# ── case 1: the GREEN direction, and its counts ─────────────────────────────
# Both directions in one suite: a canary that only asserts failure cannot catch
# a lane that fails on EVERYTHING, and that lane is just as useless.
D="$WORK/c1"
mkfixture "$D"
run_lane "$D" IGNORE=1
if [ "$RC" -ne 0 ]; then
	bad "green: a fresh fixture is accepted" "$OUT"
elif ! has "fresh ($n_files files + $n_link flatten symlinks vs output/c)"; then
	bad "green: the pass line reports the roster's own counts" "$OUT"
else
	ok "green: a fresh fixture is accepted, and the pass line reports $n_files files + $n_link symlinks"
fi

# ── case 2: STALE — the verdict clause ──────────────────────────────────────
D="$WORK/c2"
mkfixture "$D"
if green_baseline "$D" "stale" IGNORE=1; then
	T="$D/renderer/generated/$first_ui"
	printf 'drifted\n' >"$T"
	if ! file_has "$T" 'drifted' || file_has "$T" 'ui-payload'; then
		bad "stale: the mutation did not land in $first_ui"
	else
		run_lane "$D" IGNORE=1
		expect "stale: exit 1, names the file, and heals it" 1 \
			"FATAL: renderer/generated/$first_ui is STALE vs output/c/ui/$first_ui." \
			"CANNOT RUN" "is not the symlink ->"
		# The heal is the other half of this clause's contract.
		if ! has "Regenerated in place"; then
			bad "stale: a writable tree must be healed in place" "$OUT"
		elif ! cmp -s "$D/output/c/ui/$first_ui" "$T"; then
			bad "stale: the heal did not make the pair byte-identical"
		else
			ok "stale: the drifted file was rewritten to its source's bytes"
		fi
	fi
fi

# ── case 3: STALE with the heal unable to write — the read-only clause ──────
# CONTROL FIRST: the stub alone, over an unmutated tree, must stay GREEN. Without
# it, case 3's red is equally explained by "the stub breaks the lane".
D="$WORK/c3ctl"
mkfixture "$D"
run_lane "$D" "PATH=$STUBBIN:$PATH"
if [ "$RC" -eq 0 ]; then
	ok "read-only control: the failing-mktemp stub alone does not red a fresh tree"
else
	bad "read-only control: the stub reddened an unmutated tree, so case 3 proves nothing" "$OUT"
fi

D="$WORK/c3"
mkfixture "$D"
if green_baseline "$D" "read-only" IGNORE=1; then
	T="$D/renderer/generated/$first_root"
	printf 'drifted\n' >"$T"
	if ! file_has "$T" 'drifted' || file_has "$T" 'root-payload'; then
		bad "read-only: the mutation did not land in $first_root"
	else
		run_lane "$D" "PATH=$STUBBIN:$PATH"
		expect "read-only: exit 1 and the STALENESS verdict survives an unwritable tree" 1 \
			"FATAL: renderer/generated/$first_root is STALE vs output/c/$first_root." \
			"Regenerated in place" "CANNOT RUN"
		if has "could NOT be regenerated here"; then
			ok "read-only: the lane says the heal did not happen, without retracting the verdict"
		else
			bad "read-only: nothing told the reader the heal was skipped" "$OUT"
		fi
	fi
fi

# ── case 4: a DELETED destination — the precondition clause ─────────────────
# The sharpest attribution case in the suite. `cmp` fails on a missing
# destination exactly as it does on a stale one, so without its own clause this
# input would be reported as STALE and healed at the source's mode — the very
# silent mode-rewrite hazard 5 exists to prevent.
D="$WORK/c4"
mkfixture "$D"
if green_baseline "$D" "deleted destination" IGNORE=1; then
	T="$D/renderer/generated/$first_ui"
	rm -f "$T"
	if [ -e "$T" ]; then
		bad "deleted destination: the mutation did not land"
	else
		run_lane "$D" IGNORE=1
		expect "deleted destination: exit 3, and the STALE clause stays silent" 3 \
			"CANNOT RUN: renderer/generated/$first_ui is MISSING, not stale" \
			"is STALE vs"
		if [ -e "$T" ]; then
			bad "deleted destination: the lane recreated a file it must refuse to create"
		else
			ok "deleted destination: the lane refused rather than creating it at the source's mode"
		fi
	fi
fi

# ── case 5: a DELETED source — the other precondition clause ────────────────
D="$WORK/c5"
mkfixture "$D"
if green_baseline "$D" "deleted source" IGNORE=1; then
	T="$D/output/c/$first_root"
	rm -f "$T"
	if [ -e "$T" ]; then
		bad "deleted source: the mutation did not land"
	else
		run_lane "$D" IGNORE=1
		expect "deleted source: exit 3, and the destination clause stays silent" 3 \
			"CANNOT RUN: output/c/$first_root is missing" \
			"is MISSING, not stale" "is STALE vs"
	fi
fi

# ── case 6: a flattened symlink — the flatten-shim clause ───────────────────
D="$WORK/c6"
mkfixture "$D"
if green_baseline "$D" "flattened symlink" IGNORE=1; then
	T="$D/renderer/generated/ui/$first_link"
	rm -f "$T"
	cp "$D/renderer/generated/$first_link" "$T"
	if [ -L "$T" ] || [ ! -f "$T" ]; then
		bad "flattened symlink: the mutation did not land — $T is not a regular file"
	else
		run_lane "$D" IGNORE=1
		expect "flattened symlink: exit 1, and the file clauses stay silent" 1 \
			"is not the symlink -> ../$first_link" \
			"is STALE vs" "CANNOT RUN"
		if [ -L "$T" ] && [ "$(readlink "$T")" = "../$first_link" ]; then
			ok "flattened symlink: the shim was recreated as a symlink"
		else
			bad "flattened symlink: the shim was not restored to a symlink"
		fi
	fi
fi

# ── case 7: 3 DOMINATES 1 when both fire ────────────────────────────────────
# The clause a caller leans on. `build-and-release.yml` treats 1 as "the rewrite
# this lane just made is the fix" and 3 as "stop"; if a precondition failure
# could be masked by a staleness verdict elsewhere in the same run, that caller
# would commit a projection it never finished judging.
D="$WORK/c7"
mkfixture "$D"
if green_baseline "$D" "precedence" IGNORE=1; then
	printf 'drifted\n' >"$D/renderer/generated/$first_ui"
	rm -f "$D/renderer/generated/$first_root"
	if file_has "$D/renderer/generated/$first_ui" 'ui-payload' ||
		[ -e "$D/renderer/generated/$first_root" ]; then
		bad "precedence: one of the two mutations did not land"
	else
		run_lane "$D" IGNORE=1
		expect "precedence: a precondition anywhere in the run wins over a verdict" 3 \
			"CANNOT RUN: renderer/generated/$first_root is MISSING, not stale"
		if has "is STALE vs"; then
			ok "precedence: both findings were still reported, and the run exited 3"
		else
			bad "precedence: the staleness finding was swallowed" "$OUT"
		fi
	fi
fi

# ── verdict ─────────────────────────────────────────────────────────────────
printf '\n[generated-projection-canary] %s ok, %s FAIL\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
[ "$PASS" -ge 13 ] || {
	printf '\033[31m[generated-projection-canary] CANNOT RUN\033[0m — only %s assertion(s) ran.\n' "$PASS" >&2
	printf '  Every case is guarded by a baseline that can skip it, so a suite that\n' >&2
	printf '  reports zero failures having run almost nothing is a green over nothing.\n' >&2
	exit 3
}
exit 0
