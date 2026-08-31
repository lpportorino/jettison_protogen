#!/usr/bin/env bash
# cmd_no_any_bytes_test.sh — canaries for tools/lint/cmd_no_any_bytes.sh.
#
# WHAT A CANARY HERE HAS TO PROVE, and it is more than "it went red".
#
#   A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. This gate has EIGHT clauses that
#   can each refuse — five construct clauses, two non-vacuity floors and the
#   convention cross-check — and several of them refuse overlapping inputs. So
#   each case below runs TWICE: once against the real script, where it must fail
#   with ITS OWN tag or message, and once against a MUTANT in which that one
#   clause alone is silenced, where the verdict must change. Where a neighbour
#   can still fire on the same mutant, that is asserted too, so "the whole
#   scanner broke" is distinguishable from "this clause fired".
#
#   THE SANITIZER NEEDS THE OPPOSITE PROOF. Comment stripping and string
#   blanking are clauses whose correct behaviour is a GREEN, and a green is what
#   a dead scanner also prints. Each is therefore proved by mutating the
#   sanitizer so that stripping stops, and requiring the same fixture to go RED.
#   Without that pair, "a comment mentioning bytes is not a finding" is
#   indistinguishable from "nothing is ever a finding".
#
#   THE MUTATION MUST BE PROVEN TO HAVE LANDED. A replacement that silently
#   matched nothing yields a mutant identical to the original, whose unchanged
#   verdict then reads as attribution while proving the exact opposite.
#   `mutate_file` asserts the anchor was present, unambiguous, and replaced.
#
#   FAIL IS NOT ERROR. The gate exits 1 for findings and 3 for cannot-run, so a
#   bash syntax error (exit 2) or a missing command (127) can never be mistaken
#   for a clause firing. Every case asserts the EXACT code plus a substring of
#   the diagnosis that names the clause.
#
#   THE FIXTURE IS A SYNTHETIC GIT REPO, not this one. The gate resolves its root
#   with `git rev-parse --show-toplevel`, so a copy of it placed inside a throw-
#   away repo scans THAT repo. A suite driven against the live tree could not
#   perturb inputs without editing tracked protos — which this worker is
#   forbidden to do, and which would be wrong anyway: the command family is a
#   frozen wire contract, not a test fixture.
#
#   IDENTITY COMES FROM THE ENVIRONMENT, not `git config`. The fixtures need an
#   author to commit at all; setting it per repo would be three config writes per
#   fixture, and `GIT_AUTHOR_*`/`GIT_COMMITTER_*` reach the same place while
#   mutating no config file anywhere.
#
# Usage: bash tools/lint/test/cmd_no_any_bytes_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/../cmd_no_any_bytes.sh"
SUT_REL='tools/lint/cmd_no_any_bytes.sh'

[ -f "$SUT" ] || {
	printf '\033[31mFAIL\033[0m — subject under test not found at %s\n' "$SUT" >&2
	exit 3
}

# THE SHARED PRIMITIVES. Absence is a HARD failure, never a skip: every
# attribution proof below is a mutation, so a soft-failing source would leave
# this suite reporting cases it never ran — in a shape indistinguishable from
# green.
MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every canary here proves a clause by breaking it alone, so without\n' >&2
	printf '  this file the suite cannot break anything and its green means nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cmd-no-any-bytes-test.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

export GIT_AUTHOR_NAME=canary GIT_AUTHOR_EMAIL=canary@example.invalid
export GIT_COMMITTER_NAME=canary GIT_COMMITTER_EMAIL=canary@example.invalid

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

# The gate is invoked with a BARE `git`, and this suite builds throw-away repos.
# GIT_DIR/GIT_WORK_TREE in the environment (tools/uber.sh exports both) would
# override every -C and make each fixture resolve to the real checkout instead,
# so they are dropped for every git call the suite makes AND for the gate itself.
git_at() {
	local repo="$1"
	shift
	env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"
}

# ---------------------------------------------------------------------------
# The synthetic base tree: ONE clean command proto, plus one state-family proto
# that legitimately declares a `bytes` field.
#
# THE STATE PROTO IS LOAD-BEARING, not scenery. It is the standing proof that
# the corpus filter narrows rather than merely existing: every case below would
# read identically if the gate scanned nothing at all, EXCEPT that this file
# carries a construct the gate must decline to report. If the package filter
# were dropped, the base case goes red and says so.
# ---------------------------------------------------------------------------
build_repo() {
	local repo="$1"
	mkdir -p "$repo/proto" "$repo/tools/lint"
	cp "$SUT" "$repo/$SUT_REL"
	cat > "$repo/proto/jon_shared_cmd_probe.proto" << 'PROTO'
syntax = "proto3";
package cmd.Probe;

// A comment mentioning fixed-width gain bytes, and Earth's surface, and an
// apostrophe in the message's name. A substring scan reports all of it.
message Root {
  option deprecated_note = "bytes payload = 99";
  uint32 bytes = 1;
  string type_uuid = 2;
}
PROTO
	cat > "$repo/proto/jon_shared_data_types.proto" << 'PROTO'
syntax = "proto3";
package ser;

message JonOpaquePayload {
  string type_uuid = 1;
  bytes payload = 3;
}
PROTO
	git_at "$repo" init -q
	git_at "$repo" add -A
	git_at "$repo" commit -qm base
}

# Run the gate inside a fixture repo. Captures merged output; returns the code.
run_gate() {
	local repo="$1"
	(cd "$repo" && env -u GIT_DIR -u GIT_WORK_TREE bash "$SUT_REL" 2>&1) || return $?
}

# expect <repo> <expected-code> <expected-substring> <label>
expect() {
	local repo="$1" want="$2" needle="$3" label="$4" out code
	out="$(run_gate "$repo")" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	if [ -n "$needle" ] && ! contains "$out" "$needle"; then
		bad "$label — exit $code was right but the diagnosis never named the clause"
		printf '       | wanted substring: %s\n' "$needle" >&2
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# refute <repo> <substring> <label> — the output must NOT contain the substring.
# Used where a mutant must stop producing one clause's diagnosis while still
# refusing for a neighbour's reason.
refute() {
	local repo="$1" needle="$2" label="$3" out
	out="$(run_gate "$repo")" || true
	if contains "$out" "$needle"; then
		bad "$label — the diagnosis still contained: $needle"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

# mutate <repo> <old> <new> — silence ONE clause, proving the edit landed.
mutate() {
	local repo="$1" old="$2" new="$3"
	local err
	if ! err="$(mutate_file "$repo/$SUT_REL" "$old" "$new" 2>&1)"; then
		bad "$err"
		return 1
	fi
	return 0
}

fresh() {
	# Two `local` statements, not one: bash expands EVERY word of a `local`
	# before performing any of its assignments, so `local a="$1" b="$WORK/$a"`
	# reads `a` while it is still unset and dies under `set -u`.
	local name="$1"
	local repo="$WORK/$name"
	rm -rf -- "$repo"
	build_repo "$repo"
	printf '%s' "$repo"
}

# append_cmd <repo> <lines…> — add a message to the command proto and commit.
append_cmd() {
	local repo="$1"
	shift
	printf '%s\n' "$@" >> "$repo/proto/jon_shared_cmd_probe.proto"
	git_at "$repo" commit -qam offender
}

# ---------------------------------------------------------------------------
banner 'THE MUTATION PRIMITIVE ITSELF — it must be able to REFUSE'
# First, because every attribution proof below depends on it.
if mutate_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the mutation primitive failed its own self-test — every proof below is void'
fi

# ---------------------------------------------------------------------------
banner 'THE SUBSTRING PRIMITIVE ITSELF — and the pipe form it replaces'
if contains_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the substring primitive failed its own self-test — every needle assertion is void'
fi

# ---------------------------------------------------------------------------
banner 'BASE — a clean command family passes, with the noise that fools a substring scan'
# The fixture carries, in the command proto alone: the word `bytes` in a comment
# (the live tree has exactly this, in a heater comment about gain bytes), a
# `bytes` field declaration inside a STRING, a field NAMED bytes, and comment
# apostrophes that a strip-strings-first scanner would read as a string opener.
R="$(fresh base)"
expect "$R" 0 'no Any and no bytes field' 'a clean command family passes despite comment/string/name noise'
refute "$R" 'jon_shared_data_types' 'the state family is OUT of the corpus (its real bytes field is not reported)'

# The corpus really is narrower than discovery, and the message says so.
expect "$R" 0 '(1 of 2 proto file(s)' 'the pass line reports corpus size AND discovery size, so a shrink is visible'

# ---------------------------------------------------------------------------
banner 'CLAUSE 1 — a bytes FIELD is a finding'
R="$(fresh bytes_field)"
append_cmd "$R" 'message Bad {' '  bytes payload = 3 [(buf.validate.field).bytes.min_len = 1];' '}'
expect "$R" 1 'bytes-field' 'a bytes field is a FINDING (exit 1) tagged bytes-field'

# Attribution: silence ONLY this clause's pattern. There is no allowlist here, so
# unlike the leak ban this gate has no stale-entry clause to fire as a neighbour
# when a scan stops matching — the pattern can be silenced directly.
R2="$(fresh bytes_field_mut)"
append_cmd "$R2" 'message Bad {' '  bytes payload = 3 [(buf.validate.field).bytes.min_len = 1];' '}'
if mutate "$R2" "add_clause 'bytes-field' \"\$nostr_all\" \"\$BYTES_FIELD_RE\"" \
	"add_clause 'bytes-field' \"\$nostr_all\" 'ZZ-NEVER-MATCHES-ZZ'"; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: bytes-field silenced -> green (red was clause 1 alone)'
fi

# CONTROL on that same mutant: a DIFFERENT construct must still be caught. This
# is what separates "clause 1 silenced" from "the whole scanner broke".
append_cmd "$R2" 'message Bad2 {' '  google.protobuf.Any thing = 6;' '}'
expect "$R2" 1 'any-field' 'CONTROL: any-field still red on the bytes-field-silenced mutant'

# ---------------------------------------------------------------------------
banner 'CLAUSE 1b — a field NAMED bytes is NOT a finding, and that is the pattern working'
# `uint32 bytes = 1;` sits in the base fixture. If the type/name positions were
# not distinguished it would be reported, and every green above would be a
# green about a pattern matching the wrong token.
R="$(fresh named_bytes)"
append_cmd "$R" 'message Named {' '  uint32 bytes = 7;' '  string bytes_hint = 8;' '}'
expect "$R" 0 'no Any and no bytes field' 'a field NAMED bytes, and one named bytes_hint, are not findings'

# ---------------------------------------------------------------------------
banner 'CLAUSE 2 — bytes in a MAP VALUE, which the field pattern cannot see'
R="$(fresh bytes_map)"
append_cmd "$R" 'message Bad {' '  map<string, bytes> blob = 5;' '}'
expect "$R" 1 'bytes-in-map' 'a map<K, bytes> is a FINDING (exit 1) tagged bytes-in-map'

# ATTRIBUTION IS THE POINT OF THIS CLAUSE EXISTING. `map<string, bytes>` puts a
# `>` where the field pattern wants whitespace and a name, so clause 1 cannot
# see it — silencing clause 2 alone must therefore go GREEN, which is exactly
# the proof that clause 1 was never covering this shape.
R2="$(fresh bytes_map_mut)"
append_cmd "$R2" 'message Bad {' '  map<string, bytes> blob = 5;' '}'
if mutate "$R2" "add_clause 'bytes-in-map' \"\$nostr_all\" \"\$MAP_BYTES_RE\"" \
	"add_clause 'bytes-in-map' \"\$nostr_all\" 'ZZ-NEVER-MATCHES-ZZ'"; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: bytes-in-map silenced -> green (clause 1 does NOT cover map values)'
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE 3 — a google.protobuf.Any FIELD is a finding, qualified or dotted'
R="$(fresh any_field)"
append_cmd "$R" 'message Bad {' '  google.protobuf.Any thing = 6;' '  repeated .google.protobuf.Any more = 7;' '}'
expect "$R" 1 'any-field' 'an Any field is a FINDING (exit 1) tagged any-field'

R2="$(fresh any_field_mut)"
append_cmd "$R2" 'message Bad {' '  google.protobuf.Any thing = 6;' '  repeated .google.protobuf.Any more = 7;' '}'
if mutate "$R2" "add_clause 'any-field' \"\$nostr_all\" \"\$ANY_FIELD_RE\"" \
	"add_clause 'any-field' \"\$nostr_all\" 'ZZ-NEVER-MATCHES-ZZ'"; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: any-field silenced -> green (red was clause 3 alone)'
fi
append_cmd "$R2" 'message Bad2 {' '  bytes trailer = 9;' '}'
expect "$R2" 1 'bytes-field' 'CONTROL: bytes-field still red on the any-field-silenced mutant'

# ---------------------------------------------------------------------------
banner 'CLAUSE 4 — Any in a MAP VALUE'
R="$(fresh any_map)"
append_cmd "$R" 'message Bad {' '  map<string, google.protobuf.Any> anymap = 8;' '}'
expect "$R" 1 'any-in-map' 'a map<K, Any> is a FINDING (exit 1) tagged any-in-map'

R2="$(fresh any_map_mut)"
append_cmd "$R2" 'message Bad {' '  map<string, google.protobuf.Any> anymap = 8;' '}'
if mutate "$R2" "add_clause 'any-in-map' \"\$nostr_all\" \"\$MAP_ANY_RE\"" \
	"add_clause 'any-in-map' \"\$nostr_all\" 'ZZ-NEVER-MATCHES-ZZ'"; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: any-in-map silenced -> green (clause 3 does NOT cover map values)'
fi

# ---------------------------------------------------------------------------
banner 'CLAUSE 5 — the any.proto IMPORT, on its own, with no field using it'
# Deliberately stricter than "an import a field uses": the fixture declares the
# import and NO Any field, so this case fails if the clause is silently folded
# into the field clauses.
R="$(fresh any_import)"
append_cmd "$R" 'import "google/protobuf/any.proto";'
expect "$R" 1 'any-import' 'an UNUSED any.proto import is a FINDING (exit 1) tagged any-import'

R2="$(fresh any_import_mut)"
append_cmd "$R2" 'import "google/protobuf/any.proto";'
if mutate "$R2" "add_clause 'any-import' \"\$keepstr_all\" \"\$ANY_IMPORT_RE\"" \
	"add_clause 'any-import' \"\$keepstr_all\" 'ZZ-NEVER-MATCHES-ZZ'"; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: any-import silenced -> green (no field clause covers a bare import)'
fi

# The import clause reads the STRING-PRESERVING blob. If it were pointed at the
# blanked one the path would be gone and the clause would be permanently green,
# which is a dead clause wearing the colour of a clean tree.
R3="$(fresh any_import_blob)"
append_cmd "$R3" 'import "google/protobuf/any.proto";'
if mutate "$R3" "add_clause 'any-import' \"\$keepstr_all\" \"\$ANY_IMPORT_RE\"" \
	"add_clause 'any-import' \"\$nostr_all\" \"\$ANY_IMPORT_RE\""; then
	expect "$R3" 0 'no Any and no bytes field' 'MUTANT: import clause pointed at the string-blanked blob -> permanently green'
fi

# ---------------------------------------------------------------------------
banner 'THE SANITIZER — comments are stripped, and that green is EARNED'
# The base fixture already passes with `bytes` in a comment. That green is
# indistinguishable from a dead scanner until the stripping is removed and the
# SAME fixture goes red. This is the pair that makes the earlier greens mean
# something.
R="$(fresh comment_strip)"
append_cmd "$R" '// a future field here would be: bytes payload = 3;'
expect "$R" 0 'no Any and no bytes field' 'a bytes field written inside a COMMENT is not a finding'

R2="$(fresh comment_strip_mut)"
append_cmd "$R2" '// a future field here would be: bytes payload = 3;'
if mutate "$R2" 'if (d == "//") break' 'if (0) break'; then
	expect "$R2" 1 'bytes-field' 'MUTANT: line-comment stripping removed -> the SAME fixture goes red'
fi

# Block comments too — a different arm of the same state machine, and the one a
# line-oriented stripper would get wrong across lines.
R="$(fresh block_comment)"
append_cmd "$R" '/*' '  bytes payload = 3;' '*/'
expect "$R" 0 'no Any and no bytes field' 'a bytes field inside a multi-line BLOCK comment is not a finding'

R2="$(fresh block_comment_mut)"
append_cmd "$R2" '/*' '  bytes payload = 3;' '*/'
if mutate "$R2" 'if (d == "/*") { inblock = 1; i += 2; out = out " "; continue }' \
	'if (0) { inblock = 1; i += 2; out = out " "; continue }'; then
	expect "$R2" 1 'bytes-field' 'MUTANT: block-comment stripping removed -> the SAME fixture goes red'
fi

# ---------------------------------------------------------------------------
banner 'THE SANITIZER — string contents are blanked for the field clauses'
R="$(fresh string_blank)"
append_cmd "$R" 'message S {' '  option note = "bytes payload = 3;";' '}'
expect "$R" 0 'no Any and no bytes field' 'a bytes field written inside a STRING is not a finding'

R2="$(fresh string_blank_mut)"
append_cmd "$R2" 'message S {' '  option note = "bytes payload = 3;";' '}'
if mutate "$R2" 'else out = out q q' 'else out = out q body q'; then
	expect "$R2" 1 'bytes-field' 'MUTANT: string blanking removed -> the SAME fixture goes red'
fi

# ---------------------------------------------------------------------------
banner 'THE SANITIZER — an apostrophe inside a COMMENT does not open a string'
# The live command family contains `Earth's` and `message's` in comments. A
# scanner that stripped strings before comments would enter string state at the
# apostrophe and swallow everything to the next one — including real field
# declarations on later lines.
R="$(fresh apostrophe)"
append_cmd "$R" "// the message's own note about Earth's surface" \
	'message Real {' '  bytes payload = 3;' '}'
expect "$R" 1 'bytes-field' 'a real field AFTER a comment apostrophe is still seen'

# ---------------------------------------------------------------------------
banner 'THE CORPUS is derived by PACKAGE, not by filename'
# A command proto under an unconventional name is still a command proto. If the
# derivation keyed on the filename it would drop out silently and its banned
# construct would go unreported.
R="$(fresh package_derived)"
cat > "$R/proto/unconventional_name.proto" << 'PROTO'
syntax = "proto3";
package cmd.Unconventional;
message Bad {
  bytes payload = 3;
}
PROTO
git_at "$R" add -A
git_at "$R" commit -qm unconventional
expect "$R" 1 'unconventional_name.proto' 'a cmd-packaged proto under an odd NAME is still scanned'

R2="$(fresh package_derived_mut)"
cp "$R/proto/unconventional_name.proto" "$R2/proto/unconventional_name.proto"
git_at "$R2" add -A
git_at "$R2" commit -qm unconventional
if mutate "$R2" 'if grep -qE "$PACKAGE_RE" <<< "$(sanitize "$f" 1)"; then' \
	'if grep -qE "$CONVENTION_RE" <<< "$f"; then'; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: corpus keyed on FILENAME -> the offender drops out silently'
fi

# ---------------------------------------------------------------------------
banner 'CONVENTION CROSS-CHECK — a cmd-NAMED file outside the corpus is CANNOT RUN'
# This is the coverage-hole clause: a file named like a command proto that the
# package filter does not enrol. The fixture's orphan carries a real bytes
# field, so the mutant below demonstrates the hole rather than merely losing a
# message.
R="$(fresh convention)"
cat > "$R/proto/jon_shared_cmd_orphan.proto" << 'PROTO'
syntax = "proto3";
package ser;
message Orphan {
  bytes payload = 3;
}
PROTO
git_at "$R" add -A
git_at "$R" commit -qm orphan
expect "$R" 3 'disagrees with the naming convention' 'a cmd-NAMED file with a non-cmd package is CANNOT RUN (exit 3)'

R2="$(fresh convention_mut)"
cp "$R/proto/jon_shared_cmd_orphan.proto" "$R2/proto/jon_shared_cmd_orphan.proto"
git_at "$R2" add -A
git_at "$R2" commit -qm orphan
if mutate "$R2" 'if [ "${#missing[@]}" -gt 0 ]; then' 'if false; then'; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: convention check removed -> GREEN over an unscanned bytes field'
fi

# The reverse direction is deliberately NOT checked: a cmd-packaged file under
# an odd name is enrolled by its package, so nothing is unscanned. The
# package_derived case above is the proof, and this is what stops the check
# being "tightened" into a red on a legitimate addition.
R="$(fresh convention_reverse)"
cat > "$R/proto/unconventional_name.proto" << 'PROTO'
syntax = "proto3";
package cmd.Unconventional;
message Fine {
  string note = 1;
}
PROTO
git_at "$R" add -A
git_at "$R" commit -qm unconventional
expect "$R" 0 'no Any and no bytes field' 'a cmd-packaged file under an odd name is NOT a convention finding'

# ---------------------------------------------------------------------------
banner 'FLOOR 1 — zero .proto discovered is CANNOT RUN, never a clean run'
R="$WORK/no_protos"
rm -rf -- "$R"
mkdir -p "$R/tools/lint"
cp "$SUT" "$R/$SUT_REL"
git_at "$R" init -q
git_at "$R" add -A
git_at "$R" commit -qm base
expect "$R" 3 'discovered ZERO .proto files' 'an empty proto population is exit 3, never a green'

# THE TWO FLOORS ARE STRUCTURALLY ENTANGLED, and pretending otherwise is how a
# canary of this shape goes wrong. Zero protos implies zero command protos, so
# floor 2 refuses the floor-1-neutered mutant as well. That is a fact about the
# gate, so it is pinned in two halves.
#
# 1a — floor 1 neutered. Still RED, but with floor 2's diagnosis and NOT floor
# 1's. This is what attributes the "discovered ZERO .proto files" message to
# floor 1 alone: remove floor 1 and that specific verdict disappears.
R2="$WORK/no_protos_mut"
rm -rf -- "$R2"
mkdir -p "$R2/tools/lint"
cp "$SUT" "$R2/$SUT_REL"
git_at "$R2" init -q
git_at "$R2" add -A
git_at "$R2" commit -qm base
if mutate "$R2" 'if [ -z "$protos" ]; then' 'if false; then'; then
	expect "$R2" 3 'ZERO command protos' 'MUTANT: floor 1 neutered -> floor 2 catches it, with its OWN message'
	refute "$R2" 'discovered ZERO .proto files' 'MUTANT: and floor 1 no longer prints its diagnosis'
fi

# 1b — BOTH floors neutered. NOW the vacuous green is reachable, and it is the
# whole point of the floors: zero files scanned, reported as a clean gate.
R3="$WORK/no_protos_mut2"
rm -rf -- "$R3"
mkdir -p "$R3/tools/lint"
cp "$SUT" "$R3/$SUT_REL"
git_at "$R3" init -q
git_at "$R3" add -A
git_at "$R3" commit -qm base
if mutate "$R3" 'if [ -z "$protos" ]; then' 'if false; then' &&
	mutate "$R3" 'if [ "${#corpus[@]}" -eq 0 ]; then' 'if false; then'; then
	expect "$R3" 0 'no Any and no bytes field' 'MUTANT: both floors neutered -> vacuous GREEN over zero files'
fi

# ---------------------------------------------------------------------------
banner 'FLOOR 2 — protos present but NONE in the command family'
# Distinct from floor 1 on purpose: discovery is perfectly healthy here. This is
# the state that arrives when the family is renamed or PACKAGE_RE drifts, and it
# prints exactly what a clean run prints unless it is floored.
R="$WORK/no_cmd"
rm -rf -- "$R"
mkdir -p "$R/proto" "$R/tools/lint"
cp "$SUT" "$R/$SUT_REL"
cat > "$R/proto/jon_shared_data_types.proto" << 'PROTO'
syntax = "proto3";
package ser;
message JonOpaquePayload {
  bytes payload = 3;
}
PROTO
git_at "$R" init -q
git_at "$R" add -A
git_at "$R" commit -qm base
expect "$R" 3 'ZERO command protos' 'a healthy discovery with no cmd package is exit 3'
refute "$R" 'discovered ZERO .proto files' 'and floor 1 stays silent — discovery was fine'

R2="$WORK/no_cmd_mut"
rm -rf -- "$R2"
cp -a "$R" "$R2"
if mutate "$R2" 'if [ "${#corpus[@]}" -eq 0 ]; then' 'if false; then'; then
	expect "$R2" 0 'no Any and no bytes field' 'MUTANT: floor 2 neutered -> vacuous GREEN over an empty corpus'
fi

# ---------------------------------------------------------------------------
banner 'DISCOVERY — an UNTRACKED proto is scanned, not silently skipped'
# Discovery is `--cached --others --exclude-standard`, and the widening past the
# index is the point: a new field arrives in a new file at least as often as it
# is added to an old one. This canary is what stops the flags being
# "simplified" back later.
R="$(fresh untracked)"
cat > "$R/proto/jon_shared_cmd_new.proto" << 'PROTO'
syntax = "proto3";
package cmd.New;
message Bad {
  bytes payload = 3;
}
PROTO
# Deliberately NOT added to the index.
expect "$R" 1 'bytes-field' 'a never-staged command proto is still scanned'

# And an IGNORED path stays out — that is what keeps the widening cheap.
R="$(fresh ignored)"
printf 'proto/scratch/\n' > "$R/.gitignore"
mkdir -p "$R/proto/scratch"
cat > "$R/proto/scratch/jon_shared_cmd_draft.proto" << 'PROTO'
syntax = "proto3";
package cmd.Draft;
message Bad {
  bytes payload = 3;
}
PROTO
git_at "$R" add .gitignore
git_at "$R" commit -qm ignore
expect "$R" 0 'no Any and no bytes field' 'a gitignored proto is out of scope'

# ---------------------------------------------------------------------------
banner 'THE PATHSPEC reaches NESTED proto directories'
# git pathspec `*` crosses `/`, so `proto/*.proto` covers proto/sub/x.proto. If
# it did not, a whole directory would be silently unscanned — and the gate would
# still print a green with a plausible-looking corpus size.
R="$(fresh nested)"
mkdir -p "$R/proto/sub"
cat > "$R/proto/sub/jon_shared_cmd_nested.proto" << 'PROTO'
syntax = "proto3";
package cmd.Nested;
message Bad {
  bytes payload = 3;
}
PROTO
git_at "$R" add -A
git_at "$R" commit -qm nested
expect "$R" 1 'proto/sub/jon_shared_cmd_nested.proto' 'a proto in a nested directory is scanned'

# ---------------------------------------------------------------------------
banner 'NO CHECKOUT — the gate refuses rather than scanning something else'
R="$WORK/not_a_repo"
rm -rf -- "$R"
mkdir -p "$R/tools/lint"
cp "$SUT" "$R/$SUT_REL"
# Ceiling the search so an enclosing repository (if TMPDIR ever sits inside one)
# cannot make this case resolve somewhere else and pass for the wrong reason.
out="$(cd "$R" && env -u GIT_DIR -u GIT_WORK_TREE GIT_CEILING_DIRECTORIES="$WORK" \
	bash "$SUT_REL" 2>&1)" && code=0 || code=$?
if [ "$code" = 3 ] && contains "$out" 'not a git repository'; then
	ok 'an unresolvable checkout is CANNOT RUN (exit 3)'
else
	bad "no-checkout case — expected exit 3 naming the checkout, got $code"
	printf '%s\n' "$out" | sed 's/^/       | /' >&2
fi

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %d\n' "$PASS"
printf '  failed: %d\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[cmd-no-any-bytes-test] RED\033[0m\n' >&2
	exit 1
fi
printf '\033[32m[cmd-no-any-bytes-test]\033[0m ALL GREEN (%d cases)\n' "$PASS"
