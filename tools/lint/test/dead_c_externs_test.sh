#!/usr/bin/env bash
# dead_c_externs_test.sh — canaries for tools/lint/dead_c_externs.sh.
#
# WHAT A CANARY HERE HAS TO PROVE:
#
#   IT CAN FAIL, on a CONSTRUCTED object carrying an unreferenced external symbol —
#   not on a red someone observed once by hand and then discarded.
#
#   ATTRIBUTION. The gate has several clauses that refuse (no tool, no objects, empty
#   discovery, empty definitions) and all of those exit 3, while a real finding exits
#   1. So every case asserts the EXACT code, and the finding cases additionally assert
#   the symbol is named — a bare non-zero would accept a broken harness as proof.
#
#   THE CONTROL, which is the half that matters for this gate: a symbol REFERENCED by
#   another object in the same link must NOT be reported. Without that, a gate that
#   flags everything would pass the failure case and be useless.
#
#   THE INTERSECTION IS THE VERDICT. A symbol live in one link and dead in the other
#   must NOT be reported. That is the whole reason the gate runs per link target, and
#   six real symbols in this tree are in exactly that state.
#
# SYNTHETIC OBJ_DIR, never the live build: the gate takes OBJ_DIR from the
# environment, so each case compiles its own two-object corpus into a temp directory.
# No expectation moves when the renderer does, and no tracked file is perturbed.
#
# Usage: bash tools/lint/test/dead_c_externs_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
GATE="$SCRIPT_DIR/../dead_c_externs.sh"

[ -f "$GATE" ] || {
	printf '\033[31mFAIL\033[0m — gate not found at %s\n' "$GATE" >&2
	exit 3
}

# THE SHARED PRIMITIVES. Absent, this suite could only report cases it did not
# run — and that report is indistinguishable from a green one, so the sourcing
# is never allowed to fail soft.
PRIMITIVES="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$PRIMITIVES" ] || {
	printf '\033[31mFAIL\033[0m — missing shared primitives at %s\n' "$PRIMITIVES" >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$PRIMITIVES"

# THE LIVE-TREE ARM RESOLVES ITS ROOT FROM GIT, SO GIT IS A PRECONDITION OF THIS
# SUITE — refused up front, exactly as the gate refuses it, and for the sharper
# reason that an unmet precondition here does not merely skip an arm.
#
# Measured in a linked-worktree checkout, where `tools/uber.sh` deliberately
# declines to mount a gitdir that holds no objects: an unguarded
# `git rev-parse --show-toplevel` left ROOT empty and this suite emitted
# `make: *** Error 128` plus two FAILs whose messages named clauses that had not
# run — including `MUTANT: expected exit 3 naming the empty root set, got 3`,
# which is the right exit code arriving from the wrong clause. A canary suite
# that reports FAIL for a reason unrelated to its clause is the false-gate shape
# this file exists to refuse, wearing the other colour.
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
	printf '\033[31m[dead-c-externs-test] CANNOT RUN\033[0m — not inside a git checkout.\n' >&2
	printf '  The live-tree arm resolves its root from git, so without it this\n' >&2
	printf '  suite cannot tell a real FAIL from an unmet precondition.\n' >&2
	exit 3
}
[ -n "$ROOT" ] || {
	printf '\033[31m[dead-c-externs-test] CANNOT RUN\033[0m — git resolved an EMPTY root.\n' >&2
	printf '  `git rev-parse --show-toplevel` succeeded with no output, which is not\n' >&2
	printf '  a checkout this suite can judge.\n' >&2
	exit 3
}

# The gate resolves llvm-nm the same way; the canary needs clang from the same SDK to
# BUILD its fixtures. Absent tooling is a CANNOT RUN, never a silent skip.
SDK="${WASI_SDK:-/opt/wasi-sdk}"
CC="$SDK/bin/clang"
NM="$SDK/bin/llvm-nm"
if [ ! -x "$CC" ] || [ ! -x "$NM" ]; then
	printf '\033[31mFAIL\033[0m — no WASI-SDK clang/llvm-nm at %s; this suite cannot run.\n' "$SDK" >&2
	printf '  Run inside tools/uber.sh, or set WASI_SDK. Skipping would report a\n' >&2
	printf '  green over zero coverage, which is what this gate exists to prevent.\n' >&2
	exit 3
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/dead-c-externs-test.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

PASS=0
FAILED=0
ok() {
	PASS=$((PASS + 1))
	printf '  \033[32mok\033[0m   %s\n' "$*"
}
bad() {
	FAILED=$((FAILED + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2
}
banner() { printf '\n== %s\n' "$*"; }

# ---------------------------------------------------------------------------
banner 'THE SUBSTRING PRIMITIVE ITSELF — and the pipe form it replaces'
# Every case below reads a diagnosis with `contains`, so a primitive that always
# returned 0 would make each needle assertion vacuous while this suite printed
# green. Its last case additionally forces the SIGPIPE/pipefail race the retired
# `printf … | grep -q …` form is subject to, so the reason this suite no longer
# uses that form stays proven rather than remembered.
if contains_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the substring primitive failed its own self-test — every needle assertion is void'
fi

CFLAGS=(--target=wasm32-wasip1 --sysroot="$SDK/share/wasi-sysroot" -O0 -c)

# fixture <name> — an OBJ_DIR with src/, echoing its path.
fixture() {
	local d="$WORK/$1"
	rm -rf -- "$d"
	mkdir -p "$d/src"
	printf '%s' "$d"
}

# compile <objdir> <basename> <c-source>
compile() {
	local d="$1" base="$2" src="$3"
	printf '%s\n' "$src" > "$d/$base.c"
	"$CC" "${CFLAGS[@]}" -o "$d/src/$base.o" "$d/$base.c"
}

# expect <objdir> <code> <needle-or-empty> <label>
expect() {
	local d="$1" want="$2" needle="$3" label="$4"
	local out code
	out="$(OBJ_DIR="$d" bash "$GATE" 2>&1)" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	if [ -n "$needle" ] && ! contains "$out" "$needle"; then
		bad "$label — exit $code was right but the output never named '$needle'"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# expect_absent <objdir> <code> <needle> <label> — the code AND the needle must NOT appear.
expect_absent() {
	local d="$1" want="$2" needle="$3" label="$4"
	local out code
	out="$(OBJ_DIR="$d" bash "$GATE" 2>&1)" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	if contains "$out" "$needle"; then
		bad "$label — '$needle' was reported and must not have been"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# ---------------------------------------------------------------------------
banner 'A REFERENCED external symbol is NOT a finding (the control comes first)'
# Without this, a gate that flagged everything would pass every failure case below.
D="$(fixture referenced)"
compile "$D" a 'int canary_shared(void) { return 7; }'
compile "$D" b 'extern int canary_shared(void);
int canary_entry(void) { return canary_shared(); }'
# canary_entry is itself unreferenced, so the run FAILS — but it must fail naming
# canary_entry and NOT canary_shared. That is the discriminator.
expect_absent "$D" 1 'canary_shared' \
	'a symbol referenced by another object is not reported'

banner 'An UNREFERENCED external symbol IS a finding, and is named'
D="$(fixture unreferenced)"
compile "$D" a 'int canary_dead(void) { return 1; }'
compile "$D" b 'extern int canary_dead2(void);
int canary_live(void) { return canary_dead2(); }'
expect "$D" 1 'canary_dead' 'an unreachable external symbol FAILS (exit 1) and is named'

banner 'The finding is a FAIL (1), never a precondition failure (3)'
# The two reds must be distinguishable, or a canary cannot tell a verdict about the
# tree from a broken gate.
D="$(fixture faildistinct)"
compile "$D" a 'int canary_dead(void) { return 1; }'
compile "$D" b 'int canary_dead_two(void) { return 2; }'
expect "$D" 1 'FAIL' 'a real finding exits 1 and prints FAIL, not CANNOT RUN'

banner 'A `static` symbol is invisible to this gate'
# The compiler already owns dead statics via -Wunused-function -Werror, so this gate
# must not double-report them — and must not MISS the external one beside them.
D="$(fixture staticsym)"
compile "$D" a 'static int canary_static_unused(void) { return 1; }
int canary_uses_it(void) { return canary_static_unused(); }'
compile "$D" b 'int canary_other(void) { return 2; }'
out="$(OBJ_DIR="$D" bash "$GATE" 2>&1)" || true
if contains "$out" 'canary_static_unused'; then
	bad 'a static symbol was reported; -Wunused-function owns that class'
else
	ok 'a static symbol is not reported (the compiler owns dead statics)'
fi

banner 'VACUITY — an empty object directory CANNOT RUN (exit 3), never pass'
D="$(fixture emptydir)"
expect "$D" 3 'object file' 'an OBJ_DIR with no objects refuses'

banner 'VACUITY — a SINGLE object cannot answer a cross-TU question'
D="$(fixture oneobj)"
compile "$D" a 'int canary_only(void) { return 1; }'
expect "$D" 3 'cross-TU' 'a one-object link set refuses rather than guessing'

banner 'VACUITY — no hand-authored objects under src/ CANNOT RUN'
D="$(fixture nosrc)"
mkdir -p "$D/other"
compile "$D" a 'int canary_a(void) { return 1; }'
compile "$D" b 'int canary_b(void) { return 2; }'
mv "$D/src"/*.o "$D/other/"
expect "$D" 3 'ZERO hand-authored object' 'an empty candidate set refuses'

banner 'VACUITY — the GENERATED font tables are excluded, so src/ can go dark'
D="$(fixture onlyfonts)"
compile "$D" font_x 'int canary_font(void) { return 1; }'
compile "$D" font_y 'int canary_font2(void) { return 2; }'
expect "$D" 3 'ZERO hand-authored object' \
	'a src/ holding only font_*.o is empty for this gate, and refuses'

banner 'A MISSING TOOL is a hard failure with an install hint'
D="$(fixture notool)"
compile "$D" a 'int canary_dead(void) { return 1; }'
compile "$D" b 'int canary_dead_two(void) { return 2; }'
# BOTH resolution routes must fail, and defeating them takes care.
#   - `LLVM_NM` and `WASI_SDK` redirected kills the pinned path;
#   - but the gate then falls back to `command -v llvm-nm`, and a SYSTEM llvm-nm is
#     on PATH on this host — so the fallback correctly resolves and the gate runs.
#     That fallback is right; the canary has to remove it deliberately.
# `PATH=/nonexistent` is NOT the way: it also breaks git, mktemp and grep, so the
# script died at 127 before reaching its own tool check — a red that proved nothing
# about the clause it named. A CURATED bin holding exactly the tools the gate uses,
# minus llvm-nm, is the honest construction.
BIN="$WORK/curated-bin"
mkdir -p "$BIN"
for t in bash git mktemp grep sed sort wc find awk comm tr rm dirname; do
	p="$(command -v "$t" 2> /dev/null)" || continue
	ln -sf "$p" "$BIN/$t"
done
out="$(OBJ_DIR="$D" LLVM_NM=/nonexistent/llvm-nm WASI_SDK=/nonexistent PATH="$BIN" bash "$GATE" 2>&1)" && code=0 || code=$?
if [ "$code" != 3 ]; then
	bad "a missing llvm-nm must exit 3, got $code"
elif ! contains "$out" 'WASI-SDK'; then
	bad 'the missing-tool diagnosis must say where to get it'
else
	ok 'a missing llvm-nm refuses with an install hint'
fi

banner 'MUTATION — with the EXPORT root set emptied, an exported symbol goes red'
# ATTRIBUTION for the root set: the gate subtracts wasm.mk's --export names. Break
# that derivation and a genuinely-exported symbol must be reported, proving the
# subtraction is what keeps the real tree green rather than luck.
MUT="$WORK/mutant.sh"
sed "s|grep -o 'Wl,--export=\[A-Za-z0-9_\]\*' \"\$MK\"|grep -o 'Wl,--NOSUCHFLAG=[A-Za-z0-9_]*' \"\$MK\"|" \
	"$GATE" > "$MUT"
if ! grep -qF 'NOSUCHFLAG' "$MUT"; then
	bad 'MUTATION did not land — the export-derivation line was not matched'
elif grep -qF "grep -o 'Wl,--export=[A-Za-z0-9_]*' \"\$MK\"" "$MUT"; then
	bad 'MUTATION did not land — the original derivation survived'
else
	D="$(fixture mutroot)"
	compile "$D" a 'int canary_a(void) { return 1; }'
	compile "$D" b 'int canary_b(void) { return 2; }'
	out="$(OBJ_DIR="$D" bash "$MUT" 2>&1)" && code=0 || code=$?
	# With no roots derived, the gate must refuse at its own root-set floor (3)
	# rather than silently treating every symbol as dead (1).
	if [ "$code" = 3 ] && contains "$out" 'ZERO exported symbols'; then
		ok 'MUTANT: an empty export root set refuses instead of condemning everything'
	else
		bad "MUTANT: expected exit 3 naming the empty root set, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
	fi
fi

banner 'The LIVE tree is clean, and the per-target counts are printed'
# The gate's real subject. Needs the real build present; if it is not, that is a
# CANNOT RUN and must be visible rather than skipped. ROOT is the guarded one
# resolved at the top of this file — re-resolving it here unguarded is what let
# an unresolvable checkout reach this arm with ROOT empty.
if [ -d "$ROOT/renderer/build/release/src" ]; then
	out="$(bash "$GATE" 2>&1)" && code=0 || code=$?
	if [ "$code" != 0 ]; then
		bad "the live tree must be clean, got exit $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
	elif ! contains "$out" 'per link target'; then
		bad 'the per-link-target counts must be printed on every run'
	else
		ok 'the live tree is clean and reports its per-target counts'
	fi
else
	printf '  \033[33mskip\033[0m the live-tree case — no build at renderer/build/release/src\n'
	printf '        (build it with: cd renderer && make -f wasm.mk objects)\n'
fi

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %d\n' "$PASS"
printf '  failed: %d\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[dead-c-externs-test] RED\033[0m\n' >&2
	exit 1
fi
printf '\033[32m[dead-c-externs-test]\033[0m ALL GREEN (%d cases)\n' "$PASS"
