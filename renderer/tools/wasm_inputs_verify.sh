#!/usr/bin/env bash
# CONTENT-PROVENANCE GATE for controls.wasm: does the artifact on disk actually
# correspond to the renderer sources now in the tree?
#
# The full WHY — the four ways a stale binary keeps a fresh-looking stamp, each
# measured — lives at wasm.mk's content-stamp block and at renderer.mk's
# `wasm-inputs-verify`. In one line: `controls.wasm.build-sha` records which
# commit HEAD was at when make last ran, which is a different question from what
# was compiled, and a warm tree can move the first without moving the second.
#
# WHY A SCRIPT AND NOT A MAKE RECIPE. Two reasons, both mechanical:
#   - GNU make exits 2 for ANY failed target, so a recipe cannot expose its own
#     exit code to a caller. The verdict/precondition split below would collapse
#     into one value, and a canary could then take a missing artifact as proof
#     that drift detection works — the exact weakness .claude/rules/
#     gate-enforcement.md §2 refuses.
#   - `lint-sh` discovers `*.sh`, so shell living in a Makefile is gated by
#     nothing. A gate must itself be judged (§5).
#
# EXIT CODES — the caller may rely on these.
#   0  the digests agree.
#   1  VERDICT: content drift. The artifact predates the sources.
#   2  usage.
#   3  CANNOT RUN: an input was absent, or the digest could not be computed, so
#      NO comparison happened. "I could not look" never prints what "they match"
#      prints.
set -euo pipefail

PROG="wasm-inputs-verify"
ARTIFACT=""
SIDECAR=""
RENDERER_DIR=""

usage() {
	printf 'usage: %s --artifact <path> --sidecar <path> --renderer-dir <dir>\n' "$PROG" >&2
	exit 2
}

while [ $# -gt 0 ]; do
	case "$1" in
	--artifact)
		[ $# -ge 2 ] || usage
		ARTIFACT="$2"
		shift 2
		;;
	--sidecar)
		[ $# -ge 2 ] || usage
		SIDECAR="$2"
		shift 2
		;;
	--renderer-dir)
		[ $# -ge 2 ] || usage
		RENDERER_DIR="$2"
		shift 2
		;;
	*) usage ;;
	esac
done
[ -n "$ARTIFACT" ] && [ -n "$SIDECAR" ] && [ -n "$RENDERER_DIR" ] || usage

cannot_run() {
	printf 'FATAL: %s: %s\n' "$PROG" "$1" >&2
	shift
	for line in "$@"; do printf '  %s\n' "$line" >&2; done
	exit 3
}

if [ ! -s "$ARTIFACT" ]; then
	cannot_run "$ARTIFACT is missing or empty, so the content-provenance" \
		"comparison DID NOT RUN. Build it first (make -f renderer.mk wasm)." \
		"This is a sequencing failure, never a pass."
fi

if [ ! -s "$SIDECAR" ]; then
	cannot_run "$SIDECAR is missing or empty, so the content-provenance" \
		"comparison DID NOT RUN." \
		"That sidecar is written by the LINK and by nothing else — deliberately, so" \
		"no rule can mint a fresh claim over an older binary. A warm tree whose last" \
		"link predates this stamp therefore has none, and no make invocation can" \
		"produce one without relinking: a truthful sidecar cannot exist without the" \
		"binary it describes. Force the link:" \
		"  rm -f $ARTIFACT && make -f renderer.mk wasm"
fi

# The expected value comes from wasm.mk's `input-digest`, which is the SAME file
# list, producer and floors the link's own write uses — one home, so the two
# halves of this comparison cannot disagree about what "the inputs" are. Its
# refusals (empty list, a discovery floor breach, a declared input not on disk)
# arrive here as a non-zero status and become CANNOT RUN, never a mismatch:
# a broken producer must not read as drift.
#
# MAKEFLAGS IS UNSET FOR THE CHILD. The caller's recipe does not mention $(MAKE)
# — it invokes this script — so make marks it a non-recursive line and hands the
# child no jobserver, while still exporting the parent's -j in the environment.
# The child then prints a jobserver warning on stderr on every run of an
# otherwise clean gate. The digest is serial anyway, so the flags buy nothing.
if ! want="$(env -u MAKEFLAGS -u MFLAGS make --no-print-directory -C "$RENDERER_DIR" -f wasm.mk input-digest)"; then
	cannot_run "the input digest could not be computed, so the comparison DID NOT RUN." \
		"The producer's own diagnosis is above: it refuses an empty input list, a" \
		"discovery floor breach, and a declared input that is not on disk, rather" \
		"than hashing whatever survived."
fi
want="$(printf '%s' "$want" | tr -d '[:space:]')"
have="$(tr -d '[:space:]' <"$SIDECAR")"

if [ -z "$want" ] || [ -z "$have" ]; then
	cannot_run "one side of the comparison is EMPTY (stamped='$have' computed='$want')." \
		"Two empty strings compare equal, so this must refuse rather than agree."
fi

if [ "$have" != "$want" ]; then
	printf 'FATAL: %s: CONTENT DRIFT — controls.wasm was NOT built from the\n' "$PROG" >&2
	printf '  renderer sources now on disk.\n' >&2
	printf '    stamped in the artifact : %s\n' "$have" >&2
	printf '    this tree computes      : %s\n' "$want" >&2
	printf '  The build-sha stamp cannot see this. It records which commit HEAD was at,\n' >&2
	printf '  not what was compiled, so it goes green over exactly this state. Something\n' >&2
	printf '  moved a build input without the link re-running: a flag in renderer/wasm.mk\n' >&2
	printf '  (which is a prerequisite of nothing), a source restored with an older mtime,\n' >&2
	printf '  or a source deleted from the link set.\n' >&2
	printf '  The fix is a RELINK, never a re-mint — there is no committed expectation\n' >&2
	printf '  here to bless, because the right-hand side is derived from the tree:\n' >&2
	printf '    rm -f %s && make -f renderer.mk wasm\n' "$ARTIFACT" >&2
	exit 1
fi

printf '%s: content provenance OK (%s)\n' "$PROG" "$have"
