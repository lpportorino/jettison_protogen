#!/usr/bin/env bash
# cmd_no_any_bytes.sh — the COMMAND FAMILY may carry no `google.protobuf.Any`
# and no `bytes` field.
#
# WHY. A command arriving from a peer is validated against its schema before
# anything acts on it, and that validation is only as strong as the schema's
# ability to describe every byte it admits. A `bytes` field and a
# `google.protobuf.Any` are the two constructs that defeat that by design: each
# admits a payload the schema says nothing about, so a scanner walking a command
# has no way to decide what it is holding. Today no command proto declares
# either. That was true by accident; this gate makes it true by enforcement.
#
# The two constructs are banned TOGETHER because they are one hazard wearing two
# shapes — an opaque octet run, and an opaque octet run with a type URL stapled
# to it. Banning one and not the other buys nothing.
#
# ── WHAT THIS GATE DOES **NOT** ESTABLISH ─────────────────────────────────
#
# READ THIS BEFORE CITING A GREEN RUN FOR ANYTHING. The property proven here is
# FILE-LOCAL: no file OF the command family declares a banned construct. It is
# NOT the reachability property, and the difference is live in this tree rather
# than theoretical.
#
#   `cmd.Root` carries `repeated ser.JonOpaquePayload opaque_payloads`, and
#   `ser.JonOpaquePayload` declares `bytes payload`. So an opaque octet run IS
#   reachable from the command root, one hop away, through a message that lives
#   in the state family and is therefore outside this gate's corpus.
#
# That path is DELIBERATE — the message's own comment calls it an extension
# point the transport passes through without interpreting — so a reachability
# check would land RED on a designed feature, and
# `.claude/rules/gate-enforcement.md` § A METRIC CEILING is explicit that
# landing a check against a baseline of known misses is not on the menu. The
# honest move of the three that rule permits is the second: NARROW THE DECLARED
# SCOPE and say what was left out. This paragraph is that statement.
#
# What the file-local property still buys, and it is not nothing: the opaque
# path that exists is ONE, it is named, it is bounded by a type UUID and a
# version, and a reviewer can enumerate it. A `bytes` field appearing directly
# on a command message would be a SECOND such path, arriving without any of
# that. This gate is what stops the second one landing quietly.
#
# ── THE CORPUS, AND HOW IT IS DERIVED ─────────────────────────────────────
#
# The corpus is every tracked-or-untracked `.proto` under `proto/` whose
# `package` declaration is `cmd` or a `cmd.<Subsystem>` child. Derived from the
# tree on every run, never from a list in this file — a hand-typed roster of the
# family would silently stop covering the next command proto added.
#
# Reproduce it:
#
#   git ls-files --cached --others --exclude-standard -- 'proto/*.proto' \
#     | while read -r f; do \
#         grep -qE "^[[:space:]]*package[[:space:]]+cmd[.;[:space:]]" "$f" \
#           && echo "$f"; \
#       done
#
# (git's pathspec `*` crosses `/`, so that one pathspec reaches the nested proto
# directories too — it is not the shell glob it looks like.)
#
# PACKAGE, NOT FILENAME, is the derivation, because the package IS the family:
# a command proto added under an unconventional filename is still a command
# proto and must still be scanned. The filename convention is used only as a
# CROSS-CHECK, in one direction — see the convention clause below.
#
# ── WHAT THE PATTERNS CAN AND CANNOT SEE ──────────────────────────────────
#
# Every clause runs over SANITIZED text, not raw source: a one-pass scanner
# strips line comments and block comments and (for the field clauses) blanks
# string contents, tracking string state as it goes so a `//` inside a string
# does not open a comment. That is not decoration. The live tree contains
#
#   proto/jon_shared_cmd_heater.proto — a comment reading "… would take
#   fixed-width 32-bit gain bytes and …"
#
# which a substring scan reports as a finding, and comments in this family
# legitimately contain apostrophes (`Earth's`, `message's`) which a
# strip-strings-first scanner would misread as opening a string literal. The
# scanner reads left to right in one pass, so a comment opened outside a string
# wins and its apostrophes never enter string state.
#
# WHAT IS MATCHED — a field DECLARATION, never a word:
#
#   bytes-field    an optional `repeated`/`optional`/`required`, then the token
#                  `bytes`, then an identifier, then `=`. So `bytes payload = 3`
#                  is a finding and `uint32 bytes = 1` (a field NAMED bytes) is
#                  not, because `=` is not an identifier. A `bytes` preceded by
#                  a `.` is excluded, which is what keeps
#                  `(buf.validate.field).bytes.min_len` and any qualified
#                  message name out.
#   bytes-in-map   `map<K, bytes>`, which the field clause cannot see: the type
#                  there is followed by `>` rather than by whitespace and a
#                  name. proto forbids a `bytes` map KEY, so only the value
#                  position is matched.
#   any-field      `google.protobuf.Any` (with or without a leading `.`) in the
#                  same field-declaration position.
#   any-in-map     the same type in a map value position.
#   any-import     `import "google/protobuf/any.proto"`. DELIBERATELY STRICTER
#                  than "an import a field uses": an unused import is a
#                  one-line deletion, so the strict form costs nothing to
#                  satisfy, and it is the only clause that would still fire on a
#                  reference shape the four type patterns did not anticipate.
#
# WHAT IT CANNOT SEE, stated rather than hidden:
#
#   * A field declaration SPLIT ACROSS LINES — `bytes` on one line, the name on
#     the next. Legal proto, written nowhere in this tree; the clauses are
#     line-oriented and would miss it.
#   * REACHABILITY through an imported type — the residual named at the top,
#     and the one a reader is most likely to assume is covered.
#   * A message in this family NAMED `Any`, used unqualified. Nothing here
#     declares one.
#   * Anything outside the command family. The state and video families declare
#     `bytes` fields legitimately and are not this gate's subject.
#
# ── NO ALLOWLIST, ON PURPOSE ──────────────────────────────────────────────
#
# The tree carries zero findings, so an allowlist would ship EMPTY — untested
# machinery whose first real use would be its first exercise, and a standing
# invitation to park a finding rather than fix one. `gate-enforcement.md` § 1
# leaves three moves for a future true false positive: fix it, add the
# exemption machinery then with its four proof fields, or decline the clause on
# the record. None of them is easier for the allowlist existing now.
#
# ── EXIT CODES — a FINDING and a BROKEN GATE are not the same red ──────────
#
#   0  clean
#   1  FINDINGS — a banned construct in a command proto. The gate ran and
#      reached a verdict about the tree. This is a FAIL.
#   3  CANNOT RUN — no resolvable checkout, discovery returned no protos at
#      all, the family filter selected none, or the corpus disagrees with the
#      naming convention. Still blocks, but it is a precondition failure: the
#      gate cannot vouch for the population it would have judged.
#
# Both block. They are split so a canary can assert WHICH happened: a suite that
# accepted any non-zero code would accept a syntax error (bash exits 2) as proof
# that a clause fired. `tools/lint/test/cmd_no_any_bytes_test.sh` asserts the
# exact code AND the tag that names the clause.
set -euo pipefail

ME='tools/lint/cmd_no_any_bytes.sh'
TAB=$'\t'

root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
	printf '\033[31m[cmd-no-any-bytes] CANNOT RUN\033[0m — not a git repository.\n' >&2
	printf '  Discovery here is `git ls-files`, so without a resolvable checkout\n' >&2
	printf '  this gate cannot enumerate anything to scan.\n' >&2
	exit 3
}
cd "$root"

# ── discovery ─────────────────────────────────────────────────────────────
# `--cached --others --exclude-standard` widens the INDEX to UNTRACKED-but-not-
# ignored files, the same argument lint-sh and the leak ban make: a new proto
# written and not yet staged would otherwise get a GREEN THAT NEVER READ IT, and
# a new file is exactly where a new field arrives.
#
# git's stderr is CAPTURED rather than discarded, and asked with the SAME
# argument vector: a diagnosis that dropped the pathspec would be a different
# experiment, so a fault specific to it would yield an empty file list AND an
# empty diagnosis.
DISCOVERY_ARGS=(--cached --others --exclude-standard -- 'proto/*.proto')
discovery_err="$(git ls-files "${DISCOVERY_ARGS[@]}" 2>&1 > /dev/null)" || true
protos="$(git ls-files "${DISCOVERY_ARGS[@]}" 2> /dev/null || true)"

# NON-VACUITY, FLOOR 1 — the whole proto population.
if [ -z "$protos" ]; then
	printf '\033[31m[cmd-no-any-bytes] CANNOT RUN\033[0m — discovered ZERO .proto files.\n' >&2
	printf '  This repo is a proto repository, so an empty set means DISCOVERY broke,\n' >&2
	printf '  not that there is nothing to check.\n' >&2
	if [ -n "$discovery_err" ]; then
		printf '  git said: %s\n' "$discovery_err" >&2
	fi
	printf '  THE LINE ABOVE IS THE DIAGNOSIS, if there is one.\n' >&2
	exit 3
fi

# ── the sanitizer ─────────────────────────────────────────────────────────
#
# ONE LEFT-TO-RIGHT PASS with four states: code, double-quoted string,
# single-quoted string, block comment. Order of precedence falls out of the pass
# rather than being asserted: whichever opener is met first wins, so `//` inside
# a string is not a comment and an apostrophe inside a comment is not a string.
# A two-step "strip strings, then strip comments" would get the second case
# wrong on this very tree.
#
# KEEPSTR=1 leaves string contents intact (the import clause needs the path);
# KEEPSTR=0 replaces each string with an empty one of the same quote character,
# so `option x = "bytes payload = 1";` cannot read as a field declaration.
#
# Emits `<lineno><TAB><sanitized text>`, one record per input line, so a finding
# can name a line that still exists in the file the reader opens.
#
# NO APOSTROPHE APPEARS IN THIS PROGRAM. It is a single-quoted shell payload, and
# `tools/payload_apostrophes.awk` bans a bare apostrophe inside one for a
# measured reason — an even count rebalances the quoting, `bash -n` stays green
# and the variable ends up EMPTY. The quote character is built with sprintf.
SANITIZE_AWK='
BEGIN { inblock = 0; SQ = sprintf("%c", 39); DQ = sprintf("%c", 34) }
{
  line = $0; out = ""; n = length(line); i = 1
  while (i <= n) {
    c = substr(line, i, 1); d = substr(line, i, 2)
    if (inblock) {
      if (d == "*/") { inblock = 0; i += 2 } else { i += 1 }
      out = out " "
      continue
    }
    if (c == DQ || c == SQ) {
      q = c; j = i + 1; body = ""
      while (j <= n) {
        e = substr(line, j, 1)
        if (e == "\\") { body = body substr(line, j, 2); j += 2; continue }
        if (e == q) break
        body = body e; j += 1
      }
      # An unterminated string means invalid proto; the rest of the line is
      # treated as string content, which is the conservative reading.
      if (KEEPSTR == 1) out = out q body ((j <= n) ? q : "")
      else out = out q q
      i = (j <= n) ? j + 1 : n + 1
      continue
    }
    if (d == "//") break
    if (d == "/*") { inblock = 1; i += 2; out = out " "; continue }
    out = out c; i += 1
  }
  printf "%d\t%s\n", NR, out
}
'

sanitize() { # <file> <keepstr 0|1>
	awk -v KEEPSTR="$2" "$SANITIZE_AWK" "$1"
}

# ── corpus derivation: the command FAMILY, by package ─────────────────────
#
# `[.;[:space:]]` after `cmd` is what keeps `package cmdlike;` out while letting
# `package cmd;` and `package cmd.Compass;` in. The check runs on sanitized
# text, so a `package cmd;` written inside a comment cannot enrol a file.
PACKAGE_RE="^[0-9]+[[:space:]]+package[[:space:]]+cmd[.;[:space:]]"

# The naming convention, used ONLY as a one-directional cross-check below.
CONVENTION_RE='(^|/)jon_shared_cmd[A-Za-z0-9_]*\.proto$'

corpus=()
while IFS= read -r f; do
	[ -n "$f" ] || continue
	# A here-string, never `sanitize … | grep -q …`. `grep -q` exits on its
	# first match without draining stdin; under `pipefail` the writer dies of
	# SIGPIPE and the pipeline reports 141 even though the match was found.
	# tools/lint/test/lib_mutate.sh carries the measurement.
	if grep -qE "$PACKAGE_RE" <<< "$(sanitize "$f" 1)"; then
		corpus+=("$f")
	fi
done <<< "$protos"

# NON-VACUITY, FLOOR 2 — the corpus this gate actually judges. Distinct from
# floor 1 on purpose: discovery can be perfectly healthy while the family filter
# selects nothing, and that state prints exactly what a clean run prints.
n_protos="$(grep -c . <<< "$protos" || true)"

if [ "${#corpus[@]}" -eq 0 ]; then
	{
		printf '\033[31m[cmd-no-any-bytes] CANNOT RUN\033[0m — ZERO command protos in a non-empty tree.\n'
		printf '  Discovery found %s .proto file(s), and none of them declares a `cmd`\n' "$n_protos"
		printf '  package. Either the family was renamed or the package filter is wrong;\n'
		printf '  either way this gate would otherwise report a clean run over nothing.\n'
		printf '  The filter is PACKAGE_RE in %s.\n' "$ME"
	} >&2
	exit 3
fi

# ── the convention cross-check, in ONE direction ──────────────────────────
#
# A file NAMED like a command proto that the package derivation did NOT enrol is
# a coverage hole: the corpus has silently shrunk and every clause below is
# green about a file nobody read. The reverse direction is deliberately NOT
# checked — a command proto under an unconventional name is still enrolled by
# its package, so nothing is lost and flagging it would red the gate for a
# legitimate addition.
missing=()
while IFS= read -r f; do
	[ -n "$f" ] || continue
	grep -qE "$CONVENTION_RE" <<< "$f" || continue
	in_corpus=0
	for c in "${corpus[@]}"; do
		[ "$c" = "$f" ] && in_corpus=1 && break
	done
	[ "$in_corpus" -eq 1 ] || missing+=("$f")
done <<< "$protos"

if [ "${#missing[@]}" -gt 0 ]; then
	{
		printf '\033[31m[cmd-no-any-bytes] CANNOT RUN\033[0m — corpus disagrees with the naming convention:\n'
		printf '    %s\n' "${missing[@]}"
		printf '  Each is named like a command proto but declares no `cmd` package, so the\n'
		printf '  package-derived corpus does not cover it. One of the two is wrong, and\n'
		printf '  until that is settled a green here is green about a file nobody read.\n'
		printf '  Fix the package, rename the file, or change PACKAGE_RE in\n'
		printf '  %s — never leave them disagreeing.\n' "$ME"
	} >&2
	exit 3
fi

# ── sanitize the corpus once, into two blobs ──────────────────────────────
# Records are `<path><TAB><lineno><TAB><text>`, so every clause below matches
# against a line that carries its own attribution.
nostr_all=""
keepstr_all=""
for f in "${corpus[@]}"; do
	nostr_all+="$(sanitize "$f" 0 | awk -v p="$f" '{ print p "\t" $0 }')"$'\n'
	keepstr_all+="$(sanitize "$f" 1 | awk -v p="$f" '{ print p "\t" $0 }')"$'\n'
done

# ── the clauses ───────────────────────────────────────────────────────────
#
# Each is a separate pattern with a separate tag, so the report attributes a
# finding to the clause that produced it and the canary can silence one clause
# at a time. The leading `(^|[^A-Za-z0-9_.])` is satisfied by the record's own
# TAB when a construct starts the line.
BYTES_FIELD_RE='(^|[^A-Za-z0-9_.])(repeated[[:space:]]+|optional[[:space:]]+|required[[:space:]]+)?bytes[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*='
MAP_BYTES_RE='map[[:space:]]*<[^>]*,[[:space:]]*bytes[[:space:]]*>'
ANY_FIELD_RE='(^|[^A-Za-z0-9_])\.?google\.protobuf\.Any[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*='
MAP_ANY_RE='map[[:space:]]*<[^>]*,[[:space:]]*\.?google\.protobuf\.Any[[:space:]]*>'
ANY_IMPORT_RE='(^|[[:space:]])import[[:space:]]+(public[[:space:]]+|weak[[:space:]]+)?"google/protobuf/any\.proto"'

findings=''
n_findings=0

# add_clause <tag> <blob> <regex> — append tagged hits, if any.
#
# The awk reformats `<path><TAB><lineno><TAB><text>` into a report line. It
# strips the two tab-delimited prefix fields off `$0` with one `sub`, rather
# than rejoining fields, so a TAB inside the source line survives intact.
add_clause() {
	local tag="$1" blob="$2" re="$3" hits formatted
	hits="$(grep -E "$re" <<< "$blob" || true)"
	[ -n "$hits" ] || return 0
	formatted="$(awk -v t="$tag" -F'\t' \
		'{ line = $0; sub(/^[^\t]*\t[^\t]*\t/, "", line)
		   printf "%-14s %s:%s  %s\n", t, $1, $2, line }' <<< "$hits")"
	findings+="$formatted"$'\n'
	n_findings=$((n_findings + $(grep -c . <<< "$formatted" || true)))
}

add_clause 'bytes-field' "$nostr_all" "$BYTES_FIELD_RE"
add_clause 'bytes-in-map' "$nostr_all" "$MAP_BYTES_RE"
add_clause 'any-field' "$nostr_all" "$ANY_FIELD_RE"
add_clause 'any-in-map' "$nostr_all" "$MAP_ANY_RE"
add_clause 'any-import' "$keepstr_all" "$ANY_IMPORT_RE"

if [ "$n_findings" -gt 0 ]; then
	{
		printf '\033[31m[cmd-no-any-bytes] FAIL\033[0m — %d banned construct(s) in the command family:\n\n' "$n_findings"
		grep -v '^$' <<< "$findings" | sed 's/^/    /' || true
		cat << 'EOF'

The text shown is the line AS THE GATE SEES IT — comments removed and string
contents blanked — so it will not match the source byte for byte.

A `bytes` field and a `google.protobuf.Any` each admit a payload the schema
cannot describe, so a consumer validating a command against its schema learns
nothing about what it is holding. The command family carries neither today.

Fix patterns:
  a structured payload  :  declare the message and reference it by name
  a variant             :  a `oneof` over named messages, which stays closed
  an existing extension :  the one opaque path in the tree already carries a
                           type UUID and a version; another one arriving
                           without either is what this gate refuses

Do NOT widen a pattern in tools/lint/cmd_no_any_bytes.sh to make this pass.
Per .claude/rules/gate-enforcement.md the moves are: fix the proto, or DECLINE
the clause on the record with its reasoning.
EOF
	} >&2
	exit 1
fi

printf '\033[32m[cmd-no-any-bytes]\033[0m no Any and no bytes field in the command family (%s of %s proto file(s), 5 clauses)\n' \
	"${#corpus[@]}" "$n_protos"
