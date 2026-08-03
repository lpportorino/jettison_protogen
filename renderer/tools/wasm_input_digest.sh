#!/usr/bin/env bash
# The ONE home for "what was actually compiled" — the content digest wasm.mk
# stamps beside controls.wasm and renderer.mk's verifier recomputes.
#
# WHY IT IS NOT INLINE IN wasm.mk: two callers need the identical answer (the
# link recipe that WRITES the stamp, and the verifier that RECOMPUTES it), and
# a digest spelled twice is a comparison that can pass for the wrong reason.
# A recipe also runs under /bin/sh with no pipefail, where `sha256sum missing |
# sha256sum` exits 0 and prints a perfectly well-formed digest over a SHORT
# stream — a silent under-hash, which is the exact class this whole stamp
# exists to catch. Here the strict mode is real and every refusal is explicit.
#
# CONTRACT
#   stdin  : one path per line, relative to the caller's cwd, already ordered
#            (wasm.mk wraps every list in make's own $(sort), which is strcmp
#            and therefore locale-invariant — the same property that file's
#            header establishes for link order).
#   args   : --floor <prefix>=<n>   repeatable; at least <n> input paths must
#                                   start with <prefix>. See NON-VACUITY.
#   stdout : the 64-hex digest, alone, so a caller can capture it bare.
#   stderr : every diagnostic.
#   exit 0 : digest printed.
#   exit 2 : PRECONDITION — empty input, a floor breach, or an unreadable file.
#            Never a digest. "I could not look" must not print what "they
#            match" prints.
#
# NON-VACUITY IS THE POINT, NOT A COURTESY. This producer's failure mode is not
# a wrong digest, it is a SHORT one: if `find` is run from the wrong directory,
# or a vendored tree is absent, the list collapses to the handful of paths
# wasm.mk names literally — and the writer and the verifier then compute the
# SAME short digest and agree. Green, over almost nothing. The floors are what
# stand between that and a comparison that means what it says, so they are
# per-prefix rather than a single total: any one populated root satisfies a
# union floor, so a root going dark would be invisible under one.
#
# WHAT THE DIGEST DOES NOT COVER, stated so no caller over-reads it: the
# TOOLCHAIN. Identical inputs compiled by a different WASI-SDK give a different
# binary and the same digest. That axis is the provisioning-parity pair's
# (`wasm-sha-record` / `wasm-sha-verify` in renderer.mk), which compares the
# ARTIFACT's own sha256 across two build paths, and the version itself is
# pinned in Dockerfile.base.
set -euo pipefail

PROG="wasm-input-digest"

die() {
	printf '\033[31m[%s] FAIL\033[0m — %s\n' "$PROG" "$1" >&2
	shift
	for line in "$@"; do printf '  %s\n' "$line" >&2; done
	exit 2
}

floors=()
while [ $# -gt 0 ]; do
	case "$1" in
	--floor)
		[ $# -ge 2 ] || die "--floor needs a <prefix>=<n> argument"
		floors+=("$2")
		shift 2
		;;
	*) die "unknown argument: $1" "usage: $PROG [--floor <prefix>=<n>]... < paths" ;;
	esac
done

mapfile -t files

# Drop blank lines: `printf '%s\n' $(EMPTY_MAKE_VAR)` emits one, and an empty
# path would otherwise reach sha256sum as the cwd.
kept=()
for f in "${files[@]}"; do
	[ -n "$f" ] && kept+=("$f")
done
files=("${kept[@]:-}")
[ "${#files[@]}" -gt 0 ] && [ -n "${files[0]}" ] ||
	die "the input list is EMPTY." \
		"Discovery collapsed — this is never 'nothing to hash'. The usual cause is a" \
		"caller running from the wrong directory, so every relative path resolves to" \
		"nothing and the digest would describe an empty build."

for spec in "${floors[@]:-}"; do
	[ -n "$spec" ] || continue
	prefix="${spec%%=*}"
	want="${spec#*=}"
	case "$want" in
	'' | *[!0-9]*) die "malformed --floor '$spec' — expected <prefix>=<integer>" ;;
	esac
	have=0
	for f in "${files[@]}"; do
		case "$f" in "$prefix"*) have=$((have + 1)) ;; esac
	done
	if [ "$have" -lt "$want" ]; then
		die "only $have input path(s) under '$prefix', and the floor is $want." \
			"A root of this build went dark. That is a DISCOVERY failure, not a small" \
			"build: hashing what survived would produce a digest both the writer and" \
			"the verifier agree on while describing almost nothing."
	fi
done

missing=()
for f in "${files[@]}"; do
	[ -r "$f" ] || missing+=("$f")
done
if [ "${#missing[@]}" -gt 0 ]; then
	die "${#missing[@]} declared input(s) are missing or unreadable, first: ${missing[0]}" \
		"A declared input that is not on disk means the DECLARATION and the tree" \
		"disagree. Hashing the rest would silently narrow what the stamp covers."
fi

# `sha256sum` over the list, then over its own output: the inner pass binds each
# file's CONTENT to its PATH (so a rename moves the digest, correctly), the
# outer pass folds the whole ordered stream into one value. The assignment's
# exit status IS the command's, so a failure here cannot reach the outer pass.
inner="$(printf '%s\0' "${files[@]}" | xargs -0 sha256sum)" ||
	die "sha256sum refused the input set — the digest was NOT computed"

printf '%s\n' "$inner" | sha256sum | cut -d' ' -f1
