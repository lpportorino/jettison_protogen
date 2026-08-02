#!/usr/bin/env bash
# file_size_ceiling.sh — SIZE CEILING over the HAND-AUTHORED population.
#
# protogen is PUBLIC and every consumer that pins it checks this tree out. A
# large file committed here is materialised in every one of those checkouts, in
# every Docker build context, and in the corpus every whole-tree gate reads —
# forever, because deleting it later does not remove it from history.
#
# This gate exists because that already happened: a 23,515,423-byte JSON schema
# sat at the repository root for a year, committed by a change whose subject was
# a single word, referenced by nothing. It was 83x the largest hand-authored
# file in the tree and it was PLAIN TEXT, so no extension filter and no
# binary-file heuristic would ever have looked at it.
#
# ── WHY SIZE x SCOPE, AND NOT SIZE x REACHABILITY ──────────────────────────
#
# The obvious design is "big AND nothing references it". It is the wrong design
# HERE, and the reason is specific rather than aesthetic.
#
# A reachability predicate must decide whether some file is referenced, and the
# only cheap evidence is its NAME. That is unsound in both directions in this
# tree. TOO LOOSE: the motivating file's basename was a strict SUFFIX of its
# neighbour `protobuf-json-descriptor.schema.json`, so a substring predicate
# scored the 22 MiB file with four referrers, every one of which was about the
# 97 KiB one. TOO TIGHT: this repo's large legitimate files are consumed at
# DIRECTORY granularity — `cp -r output/<lang>/*` in the release workflow,
# `glob("output/json-descriptors/*.json")` in a script, a snapshot directory
# walked by a test — so a per-file name predicate reports them all as
# unreferenced. A gate that floods on `output/` and `renderer/lvgl/` is a gate
# somebody switches off.
#
# The property that actually separates the accident from the legitimate file is
# not reference count. It is PROVENANCE: a multi-megabyte file is fine when the
# repository PRODUCES it and knows that it does. That is decidable here, exactly
# and without heuristics, because the generated and vendored trees are declared
# — see EXCLUDE_RE. So the question this gate asks is "is this file large AND
# outside every tree we declare that we generate or vendor", and a file that
# passes both halves is by construction a hand-committed artifact.
#
# ── THE TWO NUMBERS, AND THEIR MEASURED PROVENANCE ────────────────────────
#
# `.claude/rules/gate-enforcement.md` § A METRIC CEILING IS NOT A PARKED
# FINDING permits a threshold seeded from a measurement, requires it to be
# green with ZERO exemptions, requires it to move DOWN only, and requires a
# stricter non-blocking tier reported every run. All four hold here.
#
#   MAX_BYTES = 1 MiB. The hand-authored population's measured maximum is
#     283,229 bytes (renderer/wasm_harness/tests/visual_regression.rs).
#     Reproduce it with the command in § REPRODUCING THE SEED below. The band
#     between that maximum and the 23,515,423-byte file that motivated this
#     gate contains NO tracked file at all — an 83x span that is completely
#     empty — so every number in the band is green today and the choice within
#     it is only about which error is cheaper. 1 MiB sits at 3.70x the measured
#     maximum: enough that the largest hand-authored file can roughly QUADRUPLE
#     before a human has to act, and still 22.4x below the file that motivated
#     the gate.
#
#     WHY NOT SEED AT THE MEASURED MAXIMUM, which that rule otherwise prefers:
#     seeding at the max is the honest move when the alternative is a borrowed
#     threshold that lands RED and needs invented waivers. Nothing lands red
#     here at any number in an 83x empty band, so that argument does not apply
#     — and the file at the maximum is a TEST FILE under active authorship,
#     measured growing 240,466 -> 283,229 bytes in about two weeks. A ceiling
#     seeded at its current size would go red on the next test added, for no
#     defect, which is how a gate gets disabled.
#
#   WATCH_BYTES = 256 KiB, NON-BLOCKING. Deliberately just BELOW the measured
#     maximum, so today's largest hand-authored file appears in the report
#     instead of being silently redefined as fine. This is the visibility tier
#     the rule requires; it never affects the exit code.
#
# BOTH NUMBERS MOVE DOWN ONLY. Raising either is a gate bypass in source form.
# If a hand-authored file legitimately exceeds MAX_BYTES, the fixes are: split
# it, delete it, or — if it is genuinely produced rather than authored — move it
# under a declared generated tree, which is a statement about what it IS.
#
# ── REPRODUCING THE SEED ──────────────────────────────────────────────────
#
# The measurement behind MAX_BYTES, runnable from the repo root. It must print
# the current hand-authored maximum; if that number has drifted upward toward
# MAX_BYTES, the right response is to look at the file, not to raise the number.
#
#   git ls-files \
#     | grep -Ev '^(output/|renderer/lvgl/|renderer/generated/|tools/renderer-gen/generated/|tools/renderer-gen/src/lvgl_codegen/generated/|docs/\.protodoc/proto-db\.edn$)' \
#     | tr '\n' '\0' | xargs -0 stat -c '%s %n' | sort -rn | head -5
#
# ── EXIT CODES — a FINDING and a BROKEN GATE are not the same red ──────────
#
#   0  clean (the watchlist may still have printed; it never blocks)
#   1  FINDINGS — a hand-authored file exceeds MAX_BYTES. The gate ran and
#      reached a verdict. This is a FAIL.
#   3  CANNOT RUN — no resolvable checkout, or discovery returned nothing.
#      Still blocks, but it is a vacuity/precondition failure rather than a
#      verdict about the tree.
#
# Both block. They are split so a canary can assert WHICH happened: a suite that
# accepted any non-zero code would accept a bash syntax error (exit 2) as proof
# that a clause fired. `tools/lint/test/file_size_ceiling_test.sh` asserts the
# exact code AND a substring of the diagnosis that names the clause.
#
# ── ARMING — HOOK-ONLY TODAY, AND THAT IS A DECLARED GAP ───────────────────
#
# `lint.mk`'s `lint-lanes` aggregate runs this, so `.githooks/pre-push` runs it.
# NOTHING IN CI DOES. `.claude/rules/gate-enforcement.md` §6 makes local and CI
# enforcement complements rather than alternatives — a client-side hook only
# protects whoever armed it and `--no-verify` bypasses it — so this gate has no
# authoritative half yet, exactly like the eight lanes `.claude/rules/
# lint-gates.md` already names as a gap.
#
# It is stated here rather than left for a reader to discover because the fix is
# one step, and the workflow that should carry it already exists. This gate's
# input set is the whole checkout, so it belongs in `hygiene.yml` — the
# no-`paths:`-filter workflow built for exactly that — and NOT in `lint.yml`,
# whose path filter would be a false skip by construction. Two steps, canary
# first, matching the ordering argument hygiene.yml already carries:
#
#     - name: file-size canaries (mutation-proven clause attribution)
#       run: make -f lint.mk lint-file-size-test
#
#     - name: file size ceiling (hand-authored tree)
#       run: make -f lint.mk lint-file-size
#
# They need bash and git only — both already on the runner, nothing to install.
set -euo pipefail

# ── the two ceilings (down-only; see the header for provenance) ────────────
MAX_BYTES=1048576  # 1 MiB   — BLOCKING
WATCH_BYTES=262144 # 256 KiB — report only, never blocks

root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
	printf '\033[31m[file-size] CANNOT RUN\033[0m — not a git repository.\n' >&2
	printf '  Discovery here is `git ls-files`, so without a resolvable checkout\n' >&2
	printf '  this gate cannot enumerate anything to measure.\n' >&2
	exit 3
}
cd "$root"

# ── scope exclusions: the DECLARED generated and vendored trees ────────────
#
# These are a statement about PROVENANCE, not about size, and that is the whole
# design (see the header). A file here is not hand-authored, so its size is a
# property of its producer and no human can act on a finding against it.
#
# Every entry names what produces it, so a later reader can check the claim
# rather than inherit it:
#
#   output/                    generated language bindings for ten targets,
#                              rebuilt wholesale by `make generate`. Contains
#                              this repo's largest files (a generated Java
#                              binding measured 1,875,453 bytes and growing:
#                              985,275 -> 1,875,453 in five months). No fixed
#                              ceiling can bound a mechanically-growing
#                              generated artifact, which is precisely why this
#                              tree is out of scope rather than the ceiling
#                              being raised to clear it.
#   renderer/lvgl/             vendored upstream, byte-exact per .gitattributes
#                              (see renderer/lvgl/.ported-from.edn). Editing it
#                              to satisfy a gate here is forbidden anyway.
#   renderer/generated/        emitted proto headers for the renderer build.
#   tools/renderer-gen/generated/
#   tools/renderer-gen/src/lvgl_codegen/generated/
#                              emitted projections from the LVGL codegen.
#   docs/.protodoc/proto-db.edn
#                              the proto database emitted by the protodoc
#                              gencorpus tool (`make docs-generate` writes it
#                              via --db-path). Measured growing 232,323 ->
#                              826,507 bytes over seven months — the same
#                              unboundable shape as output/, which is why it is
#                              named here even though it is a lone file inside
#                              an otherwise hand-authored tree.
#
# NOT EXCLUDED, deliberately, and worth stating because each looks like a
# candidate: the generated font tables under renderer/src/ (largest measured
# 142,538 bytes), the vendored TTF under renderer/assets/fonts/ (139,456), and
# the PNG snapshots under renderer/wasm_harness/ (99,194). All sit an order of
# magnitude below MAX_BYTES and none has a growth mechanism that approaches it,
# so excluding them would widen the unchecked surface for nothing.
#
# THIS GATE DOES NOT EXCLUDE ITSELF OR ITS CANARY, and the contrast with
# tools/lint/no_host_paths.sh is instructive rather than incidental: that gate
# must exclude both because it scans for patterns its own source necessarily
# contains. A size gate has no such self-reference — it measures bytes, not
# content — so both files are in scope and are held to the ceiling like
# anything else.
EXCLUDE_RE='^(output/|renderer/lvgl/|renderer/generated/|tools/renderer-gen/generated/|tools/renderer-gen/src/lvgl_codegen/generated/|docs/\.protodoc/proto-db\.edn$)'

# ── discovery ─────────────────────────────────────────────────────────────
# `--cached --others --exclude-standard` widens the INDEX to UNTRACKED-but-not-
# ignored files, and here that widening is not a refinement but the primary
# case: the failure being prevented is an author redirecting a generator into
# the tree and committing without looking. An index-only scan cannot see that
# file until it has already been staged, which is one keystroke from being
# history. Ignored paths (scratch, caches, preserved forks) stay out.
#
# git's stderr is CAPTURED rather than discarded: when discovery fails the
# reason IS the diagnosis, and the guard below prints it instead of guessing.
# The diagnosis re-runs the SAME invocation — dropping a flag would be a
# different experiment, so a fault specific to those flags would produce an
# empty file list AND an empty diagnosis.
DISCOVERY_ARGS=(--cached --others --exclude-standard)
discovery_err="$(git ls-files "${DISCOVERY_ARGS[@]}" 2>&1 >/dev/null)" || true
candidates="$(git ls-files "${DISCOVERY_ARGS[@]}" 2>/dev/null | grep -Ev "$EXCLUDE_RE" || true)"

# Keep only paths that exist as regular files in the working tree. A path can be
# in the index and absent from disk (staged deletion, sparse checkout), and a
# path can be a symlink, whose size is the length of its target and meaningless
# here. `[ -f ]` and `[ -L ]` are builtins, so this costs no processes.
files=''
while IFS= read -r f; do
	[ -n "$f" ] || continue
	[ -L "$f" ] && continue
	[ -f "$f" ] || continue
	files+="$f"$'\n'
done <<< "$candidates"
files="${files%$'\n'}"

# NON-VACUITY GUARD. This gate's PASS value is "no file over the ceiling", which
# is also exactly what a BROKEN DISCOVERY prints. An empty file list is a green
# tick over zero coverage, so it must fail loud instead.
if [ -z "$files" ]; then
	printf '\033[31m[file-size] CANNOT RUN\033[0m — discovered ZERO files to measure.\n' >&2
	printf '  This repo tracks thousands of files, so an empty set means DISCOVERY\n' >&2
	printf '  broke, not that there is nothing to check.\n' >&2
	if [ -n "$discovery_err" ]; then
		printf '  git said: %s\n' "$discovery_err" >&2
	fi
	printf '  THE LINE ABOVE IS THE DIAGNOSIS, if there is one. The commonest cause\n' >&2
	printf '  is git being unable to resolve this checkout.\n' >&2
	exit 3
fi

scanned=$(printf '%s\n' "$files" | wc -l | tr -d ' ')

# ── measure ───────────────────────────────────────────────────────────────
# One `stat` per batch rather than per file. `--` terminates options so a path
# beginning with a dash is a filename, not a flag. Output is `<size>\t<path>`.
#
# THE EMPTINESS TEST IS NOT A DUPLICATE OF THE GUARD ABOVE, and it was added
# because the canary caught its absence. `printf '%s\n' "$empty"` emits ONE
# EMPTY LINE, which `tr` turns into a single NUL — so `xargs -0` sees one empty
# argument rather than no arguments, `-r` does not fire, and `stat` dies with
# "cannot statx ''" and exit 123. The guard above returns first in the real
# gate, so this is unreachable in normal operation; what it buys is that the
# empty case is DEFINED rather than an error of a third kind. That matters
# precisely once, and it is the moment that counts: the canary proves the guard
# by neutering it and requiring the vacuous green to become observable, and an
# undefined xargs failure there is an ERROR wearing the guard's colour — which
# `.claude/rules/gate-enforcement.md` §2 refuses as proof of anything.
sizes=''
if [ -n "$files" ]; then
	sizes="$(printf '%s\n' "$files" | tr '\n' '\0' | xargs -0 -r stat -c '%s	%n' --)"
fi

# Sorted descending so both reports lead with the worst offender.
over="$(printf '%s\n' "$sizes" | awk -F'\t' -v m="$MAX_BYTES" '$1 > m' | sort -rn || true)"
watch="$(printf '%s\n' "$sizes" |
	awk -F'\t' -v w="$WATCH_BYTES" -v m="$MAX_BYTES" '$1 > w && $1 <= m' | sort -rn || true)"

human() { awk -v b="$1" 'BEGIN { printf (b >= 1048576) ? "%.1f MiB" : "%.0f KiB", (b >= 1048576) ? b/1048576 : b/1024 }'; }

# ── the blocking clause ───────────────────────────────────────────────────
if [ -n "$over" ]; then
	{
		printf '\033[31m[file-size] FAIL\033[0m — hand-authored file(s) over %s:\n\n' "$(human "$MAX_BYTES")"
		while IFS=$'\t' read -r sz path; do
			[ -n "$path" ] || continue
			printf '    %10s  %s\n' "$(human "$sz")" "$path"
		done <<< "$over"
		cat <<'EOF'

protogen is PUBLIC. A file this size is materialised in every consumer's
checkout, in every Docker build context, and in the corpus every whole-tree
gate reads — and deleting it later does NOT remove it from history, so the
cost is permanent from the moment it lands.

This gate's scope is the HAND-AUTHORED population: the generated and vendored
trees are already excluded by provenance, so a finding here is a file somebody
committed by hand. Fix it, in preference order:

  1. Is it reachable at all? A file nothing references is the case this gate
     was built from. Establish that with a WHOLE-NAME-COMPONENT search, never
     a substring — a basename that is a suffix of another file's basename
     scores that file's referrers as its own. Then delete it.
  2. Is it PRODUCED rather than authored? Then it belongs under a declared
     generated tree, and moving it there is a statement about what it is.
     Add its tree to EXCLUDE_RE in tools/lint/file_size_ceiling.sh with the
     producer named, the way every entry there already is.
  3. Is it genuinely authored and genuinely this large? Split it.

Do NOT raise MAX_BYTES. It is a down-only ratchet seeded from a measurement
recorded in that script's header, and raising it is a gate bypass in source
form (.claude/rules/gate-enforcement.md § A METRIC CEILING).
EOF
	} >&2
	exit 1
fi

# ── the non-blocking watchlist ────────────────────────────────────────────
# Reported EVERY run, per .claude/rules/gate-enforcement.md's third condition on
# a metric ceiling: without it the distance between today's worst file and where
# the bar should be stops being visible, and the ceiling quietly becomes the
# definition of fine rather than a bound on it.
if [ -n "$watch" ]; then
	printf '\033[33m[file-size] watch\033[0m — over %s, not blocking:\n' "$(human "$WATCH_BYTES")"
	while IFS=$'\t' read -r sz path; do
		[ -n "$path" ] || continue
		printf '    %10s  %s\n' "$(human "$sz")" "$path"
	done <<< "$watch"
fi

printf '\033[32m[file-size]\033[0m no hand-authored file over %s (%s files, %s on watch)\n' \
	"$(human "$MAX_BYTES")" "$scanned" "$(printf '%s' "$watch" | grep -c . || true)"
