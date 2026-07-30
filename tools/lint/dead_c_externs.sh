#!/usr/bin/env bash
# dead_c_externs.sh — no hand-authored C symbol carries EXTERNAL LINKAGE that
# nothing exports and nothing else references.
#
# WHAT THIS CATCHES THAT THE COMPILER CANNOT. `-Wunused-function` under `-Werror`
# already makes an unused `static` a hard build failure, so dead internal C cannot
# exist here. It says nothing about a symbol with EXTERNAL linkage: the linker must
# keep it, so the compiler has no complaint, and no caller can reach it either. That
# is `misc-use-internal-linkage`'s class — and clang-tidy cannot answer it, because
# clang-tidy is PER TRANSLATION UNIT and cannot know whether another object uses the
# symbol. Only a sweep over the whole link set can.
#
# THE COMBINATOR IS INTERSECTION, NOT UNION, and getting this wrong is the whole
# difficulty. Two link targets exist and they share most objects:
#     controls.wasm   = … + src/renderer.o        (the deployed module)
#     reference.wasm  = … + src/reference_ui.o    (the diff oracle)
# They are NEVER linked together — eight symbols are defined in BOTH renderer.c and
# reference_ui.c. So a symbol unreferenced in ONE link may be live in the other, and
# a symbol is dead only when it is unreferenced in EVERY link its defining object
# participates in. Measured: the reference link alone shows six candidates, all six
# of them live in the controls link. A union reports six false positives; a
# whole-tree merge hides real ones.
#
# THE ROOT SET IS DERIVED, never hand-copied: the `-Wl,--export=` names are read out
# of renderer/wasm.mk. A transcribed copy of an export list is a second source free
# to disagree with the linker, which is the anti-pattern this repo bans.
#
# EXIT CODES — a FINDING and a BROKEN GATE are different reds:
#     0  clean
#     1  FINDINGS — a verdict about the tree
#     3  CANNOT RUN — a precondition failed (no llvm-nm, no objects, empty discovery)
#
# Usage: bash tools/lint/dead_c_externs.sh
set -euo pipefail

RED=$'\033[31m'
GREEN=$'\033[32m'
YELLOW=$'\033[33m'
OFF=$'\033[0m'

EXIT_FINDINGS=1
EXIT_CANNOT_RUN=3

die() {
	printf '%s[dead-c-externs] CANNOT RUN%s — %s\n' "$RED" "$OFF" "$1" >&2
	shift
	for line in "$@"; do printf '  %s\n' "$line" >&2; done
	exit "$EXIT_CANNOT_RUN"
}

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || die \
	'not inside a git checkout.' \
	'Discovery is git-based, so a checkout git cannot resolve means the gate' \
	'has no corpus — not that there is nothing to check.'
R="$ROOT/renderer"

# ── the tool. A MISSING TOOL IS A HARD FAILURE WITH AN INSTALL HINT: a gate that
# passes because its analyser is absent is the defect it exists to prevent.
NM="${LLVM_NM:-${WASI_SDK:-/opt/wasi-sdk}/bin/llvm-nm}"
if [ ! -x "$NM" ]; then
	command -v llvm-nm > /dev/null 2>&1 && NM="$(command -v llvm-nm)" || die \
		"llvm-nm not found (looked at $NM)." \
		'It ships inside the WASI-SDK the renderer is built with, so the pinned' \
		'copy is the right one — run this inside tools/uber.sh, or set' \
		'WASI_SDK / LLVM_NM. CI resolves it the same way renderer.yml does.'
fi

MK="$R/wasm.mk"
[ -f "$MK" ] || die "no $MK." 'The export list is derived from it; without it there is no root set.'

WORK="$(mktemp -d "${TMPDIR:-/tmp}/dead-c-externs.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

# ── the ROOT SET, derived from the linker flags themselves.
# `|| true` is load-bearing under `set -o pipefail`: a grep that matches NOTHING
# exits 1, which would abort the script with the FINDINGS code (1) before the
# explicit floor below could report CANNOT RUN (3). A broken derivation would then
# be indistinguishable from a real finding — the precise confusion the exit-code
# split exists to prevent, and the canary caught it here rather than in the field.
{ grep -o 'Wl,--export=[A-Za-z0-9_]*' "$MK" || true; } | sed 's/.*=//' | sort -u > "$WORK/exports"
n_exports="$(wc -l < "$WORK/exports" | tr -d ' ')"
[ "$n_exports" -gt 0 ] || die \
	'derived ZERO exported symbols from wasm.mk.' \
	'The link flags carry an --export list, so an empty root set means the' \
	'pattern stopped matching — and with no roots EVERY symbol would look dead.'

OBJ_DIR="${OBJ_DIR:-$R/build/release}"
[ -d "$OBJ_DIR" ] || die \
	"no object directory at $OBJ_DIR." \
	'This gate reads compiled objects, so it needs a build first:' \
	'  cd renderer && make -f wasm.mk objects && make -f wasm.mk build/release/src/reference_ui.o'

find "$OBJ_DIR" -name '*.o' | sort > "$WORK/all-objs"
n_objs="$(wc -l < "$WORK/all-objs" | tr -d ' ')"
[ "$n_objs" -gt 1 ] || die \
	"found $n_objs object file(s) under $OBJ_DIR." \
	'A link set of one object cannot answer a cross-TU question, so this is a' \
	'partial build rather than a clean tree. Build both link targets first.'

# Hand-authored candidates: renderer/src/*.o at depth 1, less the GENERATED font
# tables. Every other object under build/ is vendored LVGL, nanopb, thorvg or a
# generated projection — not this gate's population.
find "$OBJ_DIR/src" -maxdepth 1 -name '*.o' ! -name 'font_*' 2> /dev/null | sort > "$WORK/cand-objs"
n_cand="$(wc -l < "$WORK/cand-objs" | tr -d ' ')"
[ "$n_cand" -gt 0 ] || die \
	"found ZERO hand-authored object files under $OBJ_DIR/src." \
	'This repo has hand-authored C, so an empty candidate set means DISCOVERY' \
	'broke — a moved build directory, a renamed source root — not that there is' \
	'nothing to judge. A gate over zero objects reports a perfect score.'

# analyse <label> <exclude-regex> <out>
# The exclude-regex removes the object belonging to the OTHER link target, so each
# run sees exactly one link's object set.
analyse() {
	local label="$1" exclude="$2" out="$3"
	local defs="$WORK/defs.$label" undef="$WORK/undef.$label"

	: > "$defs"
	while read -r o; do
		"$NM" --defined-only "$o" 2> /dev/null \
			| awk -v O="$o" '$2=="T"||$2=="D"{print $3"\t"O}'
	done < <(grep -vE "$exclude" "$WORK/cand-objs") | sort -u > "$defs"

	[ -s "$defs" ] || die \
		"llvm-nm reported ZERO external definitions for the $label link." \
		'Hand-authored C defines external symbols, so an empty set means llvm-nm' \
		'could not read these objects (an LTO/bitcode mismatch, a truncated build).'

	: > "$undef"
	while read -r o; do
		"$NM" --undefined-only "$o" 2> /dev/null \
			| awk -v O="$o" '{print $NF"\t"O}'
	done < <(grep -vE "$exclude" "$WORK/all-objs") | sort -u > "$undef"

	# A symbol is a candidate when it is neither EXPORTED nor referenced by an
	# object OTHER than its own definer. Self-reference does not count: a symbol
	# used only inside its own TU needs no external linkage at all.
	awk -F'\t' '
		FILENAME==ARGV[1] { expd[$1]=1; next }
		FILENAME==ARGV[2] { ref[$1]=ref[$1]" "$2; next }
		{ sym=$1; owner=$2; needed=0
		  n=split(ref[sym], a, " ")
		  for (i=1;i<=n;i++) if (a[i]!="" && a[i]!=owner) { needed=1; break }
		  if (!expd[sym] && !needed) print sym }
	' "$WORK/exports" "$undef" "$defs" | sort -u > "$out"
}

analyse controls 'reference_ui\.o' "$WORK/cand-controls"
analyse reference '/renderer\.o' "$WORK/cand-reference"

comm -12 "$WORK/cand-controls" "$WORK/cand-reference" > "$WORK/dead"
n_dead="$(wc -l < "$WORK/dead" | tr -d ' ')"

# The per-target counts are reported EVERY run, not just on failure. They are what
# makes the intersection legible: a large per-target count with an empty
# intersection is the NORMAL state here, and a reader who saw only the verdict
# would have no way to tell that from a gate that judged nothing.
printf '  per link target: controls=%s reference=%s (intersection is the verdict)\n' \
	"$(wc -l < "$WORK/cand-controls" | tr -d ' ')" \
	"$(wc -l < "$WORK/cand-reference" | tr -d ' ')"

if [ "$n_dead" -gt 0 ]; then
	printf '%s[dead-c-externs] FAIL%s — %s symbol(s) with external linkage that nothing exports and nothing else references:\n' \
		"$RED" "$OFF" "$n_dead" >&2
	# The definer comes from the symbol->object map already built for the controls
	# link, so no second llvm-nm pass is needed and the two cannot disagree.
	while read -r sym; do
		definer="$(awk -F'\t' -v S="$sym" '$1==S {print $2; exit}' "$WORK/defs.controls")"
		printf '    %-44s defined in %s\n' "$sym" "${definer:-<unknown>}" >&2
	done < "$WORK/dead"
	{
		printf '\n  Make it `static` and drop its header declaration. The linker must keep an\n'
		printf '  external symbol, so one no caller can reach is pure cost — and an external\n'
		printf '  declaration reads as a contract with something that does not exist.\n'
		printf '\n  If it is external ON PURPOSE — a seam an export or a probe genuinely uses —\n'
		printf '  then EXPORT it in wasm.mk, which is what makes the intent real. Do NOT\n'
		printf '  waive this: the check is not wrong about an unreachable symbol, so a waiver\n'
		printf '  here would be excusing a defect rather than a false positive.\n'
	} >&2
	exit "$EXIT_FINDINGS"
fi

printf '%s[dead-c-externs]%s clean — %s hand-authored object(s), %s export(s), no unreachable external symbol\n' \
	"$GREEN" "$OFF" "$n_cand" "$n_exports"
if [ "$(wc -l < "$WORK/cand-reference" | tr -d ' ')" -gt 0 ]; then
	printf '%s  note:%s %s symbol(s) look dead in the reference link alone and are LIVE in the\n' \
		"$YELLOW" "$OFF" "$(wc -l < "$WORK/cand-reference" | tr -d ' ')"
	printf '  controls link — which is why the verdict is the INTERSECTION and not a union.\n'
fi
