#!/usr/bin/env bash
# Canary for the controls.wasm CONTENT-PROVENANCE stamp and its verifier.
#
# WHAT IS GUARDED, and the defect it exists to keep dead:
#
#   renderer/output/controls.wasm.build-sha records which commit HEAD was at
#   when make last ran. A consumer compares it against its gitlink, which is the
#   one check standing between a stale interpreter and everything that trusts
#   it. But "when did make last run" is a different question from "what was
#   compiled", and the gap is REACHABLE. Measured on the real makefile, on a
#   warm object tree: commit a change to the LINK FLAGS in renderer/wasm.mk —
#   which is a prerequisite of nothing — then `make -f wasm.mk all`. Exit 0, no
#   output, no compiler invoked, controls.wasm byte-identical with its mtime
#   unmoved, and the stamp rewritten to the new commit. The stamp then names a
#   tree that would produce a different binary, and the consumer's gate passes.
#
#   The fix is a SECOND sidecar, controls.wasm.build-inputs, written by the LINK
#   RECIPE and by nothing else: a digest over the exact file set that link
#   compiles plus the description that compiles it. Because only the link writes
#   it, a no-op rebuild leaves it TRUE rather than making it a fresh lie, and a
#   tree that moved since the link disagrees with a recomputation.
#
# CASE 4 IS THE ONE THAT MATTERS. It reproduces the defect end to end inside the
# fixture — the sha stamp moving to a commit whose tree was never compiled — and
# requires the new gate to refuse it. Every other case exists to make that red
# attributable rather than incidental.
#
# HERMETIC: every case runs against a throw-away tree under $(mktemp -d),
# assembled from the REAL renderer/wasm.mk, the REAL producer and the REAL
# verifier (copied at run time, so the bytes under test are always current) and
# driven through the REAL renderer.mk with its renderer directory overridden.
# The tracked tree is READ and never written. No WASI-SDK is needed: the
# compiler is stubbed, because what is under test is what MAKE DECIDES and what
# the recipes WRITE, never what clang emits.
#
# EXIT: 0 all green, 1 a case failed, 3 CANNOT RUN (a subject is missing, so the
# cases would be testing nothing).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAILED=0

ok() {
	printf '  \033[32mok\033[0m   %s\n' "$1"
	PASS=$((PASS + 1))
}
bad() {
	printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2
	FAILED=$((FAILED + 1))
}

# --- NON-VACUITY: every subject must exist, or every case below tests nothing --
for f in renderer.mk renderer/wasm.mk \
	renderer/tools/wasm_input_digest.sh renderer/tools/wasm_inputs_verify.sh \
	tools/lint/test/lib_mutate.sh; do
	if [ ! -r "$ROOT/$f" ]; then
		printf '\033[31m[wasm-provenance] CANNOT RUN\033[0m — subject missing: %s\n' "$f" >&2
		printf '  If it moved, move this canary with it. Do not delete the assertion.\n' >&2
		exit 3
	fi
done

# shellcheck source=tools/lint/test/lib_mutate.sh
. "$ROOT/tools/lint/test/lib_mutate.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FX="$WORK/fx"
RDIR="$FX/renderer"
ART="$RDIR/output/controls.wasm"
SIDE="$ART.build-inputs"
SHAF="$ART.build-sha"
VERIFY="$RDIR/tools/wasm_inputs_verify.sh"

# The mutation primitive is what makes every attribution below mean anything: a
# mutation that silently matched nothing yields a mutant identical to the
# original, whose unchanged verdict then reads as proof of the opposite. A red
# here fails the suite.
if ! mutate_selftest "$WORK/mutate"; then
	bad "the shared mutation primitive does not refuse — no attribution below is trustworthy"
fi

# --- the fixture -------------------------------------------------------------
# `-Wl` never runs here. The stub honours -o and writes a marker, so a link or a
# compile that make DECIDED to run is visible in the output, and one it skipped
# leaves the previous bytes exactly where they were.
cat > "$WORK/stubcc" << 'STUB'
#!/usr/bin/env bash
out=""; prev=""
for a in "$@"; do
  if [ "$prev" = "-o" ]; then out="$a"; fi
  prev="$a"
done
[ -n "$out" ] || exit 0
mkdir -p "$(dirname "$out")"
printf 'stub-output %s\n' "$out" > "$out"
STUB
chmod +x "$WORK/stubcc"

cat > "$WORK/print.mk" << 'PRINT'
print-%:
	@printf '%s\n' "$($*)"
PRINT

fx_make() { make -C "$RDIR" -f wasm.mk CC="$WORK/stubcc" CXX="$WORK/stubcc" "$@"; }

# Bulk enough to clear renderer/wasm.mk's own per-prefix discovery floors. The
# split is deliberate: headers cost nothing to create and are never compiled, so
# the floor is cleared without paying for hundreds of stub compiles.
mkdir -p "$RDIR/src" "$RDIR/generated" "$RDIR/lvgl/src" "$RDIR/tools" "$RDIR/output"
for i in $(seq 1 60); do printf 'lvgl src %s\n' "$i" > "$RDIR/lvgl/src/gen_$i.c"; done
for i in $(seq 1 360); do printf 'lvgl hdr %s\n' "$i" > "$RDIR/lvgl/src/hdr_$i.h"; done
# Header counts matter: renderer/wasm.mk floors each discovery root, so the
# fixture models a tree whose header roots are populated rather than the bare
# minimum. Real counts at the time of writing are src/ 10 and generated/ 12.
printf 'fixture lv_conf\n' > "$RDIR/lv_conf.h"
printf 'fixture renderer header\n' > "$RDIR/src/renderer.h"
for h in theme gesture cmd_patch; do printf 'fixture %s header\n' "$h" > "$RDIR/src/$h.h"; done
for h in ui_ast.pb ui_input.pb jon_shared_data_types.pb pb_common; do
	printf 'fixture %s header\n' "$h" > "$RDIR/generated/$h.h"
done
# NOT a declared input: the scope control in case 7 turns on this file being
# reachable, present, and outside BUILD_INPUT_FILES.
printf 'fixture reference ui\n' > "$RDIR/src/reference_ui.c"
cp "$ROOT/renderer/wasm.mk" "$RDIR/wasm.mk"
cp "$ROOT/renderer/tools/wasm_input_digest.sh" "$RDIR/tools/"
cp "$ROOT/renderer/tools/wasm_inputs_verify.sh" "$RDIR/tools/"

# Every path renderer/wasm.mk names LITERALLY (the app sources, the nanopb
# runtime, the generated bindings) is created by asking that file for its own
# declaration rather than re-typing it here, so a new source group added there
# joins this fixture by itself instead of failing it.
declared="$(make -C "$RDIR" -f wasm.mk -f "$WORK/print.mk" --no-print-directory \
	print-BUILD_INPUT_FILES 2> /dev/null | tr ' ' '\n' | grep -c . || true)"
make -C "$RDIR" -f wasm.mk -f "$WORK/print.mk" --no-print-directory \
	print-BUILD_INPUT_FILES 2> /dev/null | tr ' ' '\n' | grep . |
	while read -r rel; do
		[ -e "$RDIR/$rel" ] && continue
		mkdir -p "$RDIR/$(dirname "$rel")"
		printf 'fixture %s\n' "$rel" > "$RDIR/$rel"
	done
if [ "${declared:-0}" -lt 400 ]; then
	printf '\033[31m[wasm-provenance] CANNOT RUN\033[0m — the fixture declares only %s inputs.\n' \
		"$declared" >&2
	exit 3
fi

# --- CASE 1: the LINK writes the content stamp -------------------------------
if fx_make all > "$WORK/build1.log" 2>&1; then
	if [ -s "$SIDE" ] && [[ "$(cat "$SIDE")" =~ ^[0-9a-f]{64}$ ]]; then
		ok "the link writes controls.wasm.build-inputs as a bare 64-hex digest"
	else
		bad "the link did not write a well-formed sidecar: $(cat "$SIDE" 2> /dev/null)"
	fi
	if [ -e "$SIDE.tmp" ]; then
		bad "the staging file survived the link — the rename did not happen"
	else
		ok "no staging residue beside the sidecar"
	fi
else
	bad "the fixture build failed; every case below is meaningless"
	sed -n '1,15p' "$WORK/build1.log" >&2
fi

# --- CASE 2: the CLEAN direction passes, through both entry points -----------
set +e
bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > "$WORK/clean.out" 2>&1
clean_rc=$?
set -e
if [ "$clean_rc" -eq 0 ] && grep -q 'content provenance OK' "$WORK/clean.out"; then
	ok "clean tree: the verifier passes (rc=0)"
else
	bad "clean tree did NOT pass (rc=$clean_rc) — a gate that fails on everything is no gate"
	sed -n '1,8p' "$WORK/clean.out" >&2
fi

set +e
make -C "$ROOT" -f renderer.mk R="$RDIR" wasm-inputs-verify > "$WORK/wire.out" 2>&1
wire_rc=$?
set -e
if [ "$wire_rc" -eq 0 ] && grep -q 'content provenance OK' "$WORK/wire.out"; then
	ok "renderer.mk's wasm-inputs-verify reaches the gate with the right paths"
else
	bad "the renderer.mk wiring is broken (rc=$wire_rc)"
	sed -n '1,10p' "$WORK/wire.out" >&2
fi

# --- CASE 3: a NO-OP rebuild leaves the content stamp ALONE ------------------
# The sha stamp's whole failure is that it refreshes here. This asserts the
# content stamp does not: it must stay TRUE rather than become a fresh claim.
side_before="$(cat "$SIDE")"
mtime_before="$(stat -c %Y "$SIDE")"
sleep 1
fx_make all > "$WORK/build2.log" 2>&1
if [ "$(cat "$SIDE")" = "$side_before" ] && [ "$(stat -c %Y "$SIDE")" = "$mtime_before" ]; then
	ok "a no-op rebuild does not touch the content stamp (value and mtime both held)"
else
	bad "a no-op rebuild rewrote the content stamp — it can outrun the binary like the sha stamp"
fi

# --- CASE 4: THE DEFECT. The sha stamp moves; the content stamp catches it ---
# A build-description change with no relink: exactly what was measured against
# the real makefile. PROTOGEN_SHA is the variable the stamp recipe itself reads,
# so setting it models HEAD moving without needing a git repository here.
art_before="$(sha256sum "$ART" | cut -d' ' -f1)"
if ! mutate_file "$RDIR/wasm.mk" \
	'-Wl,--initial-memory=8388608' '-Wl,--initial-memory=16777216' 2> "$WORK/mut.err"; then
	bad "the LDFLAGS mutation did not land: $(cat "$WORK/mut.err")"
else
	fx_make all PROTOGEN_SHA=cafebabecafebabecafebabecafebabecafebabe > "$WORK/build3.log" 2>&1
	art_after="$(sha256sum "$ART" | cut -d' ' -f1)"

	if [ "$art_after" = "$art_before" ]; then
		ok "defect reproduced: a link-flag change relinked NOTHING"
	else
		bad "the fixture relinked, so this build does not reproduce the defect"
	fi
	if [ "$(cat "$SHAF")" = "cafebabecafebabecafebabecafebabecafebabe" ]; then
		ok "defect reproduced: build-sha now names a tree that was never compiled"
	else
		bad "build-sha did not move — the fixture no longer models the sha stamp"
	fi

	set +e
	bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" \
		> "$WORK/drift.out" 2>&1
	drift_rc=$?
	set -e
	if [ "$drift_rc" -eq 1 ] && grep -q 'CONTENT DRIFT' "$WORK/drift.out"; then
		ok "the content gate REFUSES the artifact the sha stamp just blessed (rc=1, CONTENT DRIFT)"
	else
		bad "the content gate did not report drift (rc=$drift_rc) — it cannot catch the defect"
		sed -n '1,8p' "$WORK/drift.out" >&2
	fi

	# --- CASE 5: ATTRIBUTION. A NEIGHBOURING clause must still REFUSE on this
	# same mutant, in a DIFFERENT class — a control that merely stayed green
	# would be satisfied by a dead gate.
	mv "$SIDE" "$WORK/side.bak"
	set +e
	bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" \
		> "$WORK/nosidecar.out" 2>&1
	nos_rc=$?
	set -e
	mv "$WORK/side.bak" "$SIDE"
	if [ "$nos_rc" -eq 3 ] && grep -q 'DID NOT RUN' "$WORK/nosidecar.out"; then
		ok "attribution: on the same mutant a missing sidecar still refuses, as rc=3 CANNOT RUN"
	else
		bad "the precondition clause did not refuse in its own class (rc=$nos_rc, want 3)"
	fi

	# Restore the description and relink: the verdict must go back to green, so
	# case 4's red is attributable to the change rather than to the fixture.
	mutate_file "$RDIR/wasm.mk" \
		'-Wl,--initial-memory=16777216' '-Wl,--initial-memory=8388608' > /dev/null 2>&1
	rm -f "$ART"
	fx_make all > "$WORK/build4.log" 2>&1
	set +e
	bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > /dev/null 2>&1
	back_rc=$?
	set -e
	if [ "$back_rc" -eq 0 ]; then
		ok "relinking clears the red — the verdict tracks the tree, not the fixture"
	else
		bad "still red after restoring and relinking (rc=$back_rc)"
	fi
fi

# --- CASE 6: HEADERS are covered ---------------------------------------------
# The half a source-list-only digest would miss, and the half the compiler
# reaches through -I. No relink, so only the digest can notice.
printf 'fixture renderer header MUTATED\n' > "$RDIR/src/renderer.h"
set +e
bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > "$WORK/hdr.out" 2>&1
hdr_rc=$?
set -e
if [ "$hdr_rc" -eq 1 ] && grep -q 'CONTENT DRIFT' "$WORK/hdr.out"; then
	ok "a HEADER edit with no relink is caught (rc=1)"
else
	bad "a header edit was invisible to the digest (rc=$hdr_rc) — the -I roots are not covered"
fi
printf 'fixture renderer header\n' > "$RDIR/src/renderer.h"

# --- CASE 7: SCOPE — the digest is a DECLARED set, not "everything" ----------
# Without this, case 6's red is compatible with a digest over the whole tree,
# which would red on any unrelated edit and be ignored within a week.
printf 'fixture reference ui MUTATED\n' > "$RDIR/src/reference_ui.c"
set +e
bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > "$WORK/scope.out" 2>&1
scope_rc=$?
set -e
if [ "$scope_rc" -eq 0 ]; then
	ok "a file outside the link's input set does NOT move the digest (rc=0)"
else
	bad "an undeclared file moved the digest (rc=$scope_rc) — the set is not scoped"
fi
printf 'fixture reference ui\n' > "$RDIR/src/reference_ui.c"

# --- CASE 8: the NON-VACUITY floor, which is what keeps this gate honest -----
# If discovery collapses, the writer and the verifier compute the SAME short
# digest and AGREE — green over almost nothing, the very defect this stamp
# exists to catch, reintroduced inside it. A collapsed root must therefore
# refuse as CANNOT RUN and never as either a match or a drift.
mv "$RDIR/lvgl" "$WORK/lvgl.away"
set +e
bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > "$WORK/floor.out" 2>&1
floor_rc=$?
set -e
mv "$WORK/lvgl.away" "$RDIR/lvgl"
if [ "$floor_rc" -eq 3 ] && grep -q 'floor is' "$WORK/floor.out"; then
	ok "a collapsed discovery root refuses as rc=3 CANNOT RUN, naming the floor"
elif [ "$floor_rc" -eq 0 ]; then
	bad "a collapsed discovery root PASSED — writer and verifier agreed on nothing"
else
	bad "a collapsed root refused for the wrong reason (rc=$floor_rc, want 3 naming the floor)"
	sed -n '1,8p' "$WORK/floor.out" >&2
fi

# --- CASE 9: a missing ARTIFACT is a precondition failure, never a pass ------
mv "$ART" "$WORK/art.bak"
set +e
bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > "$WORK/noart.out" 2>&1
noart_rc=$?
set -e
mv "$WORK/art.bak" "$ART"
if [ "$noart_rc" -eq 3 ] && grep -q 'DID NOT RUN' "$WORK/noart.out"; then
	ok "a missing artifact refuses as rc=3 CANNOT RUN"
else
	bad "a missing artifact did not refuse in its own class (rc=$noart_rc, want 3)"
fi

# --- CASE 10: two empty strings must not compare equal -----------------------
# `test -s` passes on whitespace, and trimming it then leaves "" == "". A gate
# whose pass value is reachable from a blank file is a gate that passes on
# nothing.
cp "$SIDE" "$WORK/side.keep"
printf '   \n' > "$SIDE"
set +e
bash "$VERIFY" --artifact "$ART" --sidecar "$SIDE" --renderer-dir "$RDIR" > "$WORK/blank.out" 2>&1
blank_rc=$?
set -e
cp "$WORK/side.keep" "$SIDE"
if [ "$blank_rc" -eq 3 ]; then
	ok "a whitespace-only sidecar refuses as rc=3, never as a match"
elif [ "$blank_rc" -eq 0 ]; then
	bad "a whitespace-only sidecar PASSED — empty compares equal to empty"
else
	bad "a whitespace-only sidecar gave rc=$blank_rc, want 3"
fi

printf '\n  passed: %d\n  failed: %d\n' "$PASS" "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[wasm-provenance]\033[0m %d case(s) failed\n' "$FAILED" >&2
	exit 1
fi
printf '\033[32m[wasm-provenance]\033[0m ALL GREEN (%d cases)\n' "$PASS"
