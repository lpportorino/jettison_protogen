#!/usr/bin/env bash
# no_host_paths.sh — the LEAK BAN for operator-host absolute paths.
#
# protogen is PUBLIC and is cloned by ten binding repositories plus every
# consumer that pins it. An absolute machine-local path in a checked-in file
# bakes ONE operator's home layout into a tree everybody else clones: it is
# wrong for every reader whose username or clone root differs, and when the
# path names a PRIVATE sibling checkout it also leaks that the sibling exists
# and where it sits.
#
# `.claude/rules/claude-md-policy.md` § Discouraged already DECLARES this ban,
# in those words, for "ANY checked-in file — rule, skill, script, workflow".
# Nothing enforced it. This script is that rule's gate; the first run found a
# real offender (renderer/.clang-tidy cited a private sibling by operator-home
# path) and it is fixed in the same commit that adds this file.
#
# ── THE TWO PATTERNS, AND WHY THE SECOND IS NARROWER THAN IT LOOKS ─────────
#
#   1. /home/<user>/…   an absolute operator-host path.
#   2. ~/<word>…        a tilde path naming a DIRECTORY, e.g. ~/git/cc/….
#
# Pattern 2 deliberately requires a WORD character after the slash, so a
# DOT-prefixed tilde path (~/.ssh, ~/.m2, ~/.gitlibs, ~/.cache) does NOT match.
# That is not an accident inherited from elsewhere — it is the measured shape of
# this repo. Reproduce it:
#
#   git ls-files -z | xargs -0 grep -nE '~/[^[:space:]]' \
#     | grep -vE '^(output/|renderer/lvgl/)' | wc -l
#
# At the time of writing that strict form reported 95 hits and the word-char
# form reported 2. Every one of the 93 it drops is the GitHub Actions
# runner-home idiom — `path: ~/.m2/repository` in an actions/cache step,
# `~/.ssh/id_ed25519` in the deploy-key steps of build-and-release.yml. Those
# resolve against an EPHEMERAL runner home, so they bake in nothing and are
# portable by construction. Banning them would mean ~93 exemptions of a
# correct idiom, which is how a gate gets switched off. A named directory after
# the tilde is the shape that actually encodes an operator's layout.
#
# The residual is stated rather than hidden: `~/.somedir` naming an operator's
# real dotfile directory would not be caught. Nothing in this tree does that,
# and widening to catch it costs the 93 false positives above.
#
# ── SCOPE EXCLUSION vs ALLOWLIST — DIFFERENT OBJECTS, DIFFERENT DECAY ──────
#
# These two are kept apart on purpose, because only one of them can rot.
#
#   SCOPE EXCLUSIONS reduce WHAT IS SCANNED. Generated and vendored trees are
#   out of scope for every check in this repo, so an operator path inside one
#   is not this gate's finding. An exclusion that matches nothing is vacuously
#   safe — it excuses no finding — so exclusions are NOT staleness-checked.
#
#   ALLOWLIST ENTRIES excuse a SPECIFIC live finding. One that matches nothing
#   is silently widening the unchecked surface, so a stale entry is a hard
#   FAILURE here. That is what keeps the ratchet down-only: you cannot fix an
#   offender and leave its excuse behind.
#
# Conflating the two is what makes a skip list grow forever, and this gate has
# a concrete case of each — see ALLOWLIST and EXCLUDE_RE below.
#
# ── EXIT CODES — a FINDING and a BROKEN GATE are not the same red ──────────
#
#   0  clean
#   1  FINDINGS — offending lines, or a stale allowlist entry. The gate ran and
#      reached a verdict. This is a FAIL.
#   3  CANNOT RUN — no resolvable checkout, or discovery returned nothing. Still
#      blocks, but it is a vacuity/precondition failure rather than a verdict
#      about the tree.
#
# Both block. They are split so a canary can assert WHICH happened: a suite that
# accepted any non-zero code would accept a syntax error (bash exits 2) as proof
# that a clause fired. `tools/lint/test/no_host_paths_test.sh` asserts the exact
# code AND the message that names the clause.
set -euo pipefail

ME='tools/lint/no_host_paths.sh'
MY_CANARIES='tools/lint/test/no_host_paths_test.sh'

root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
	printf '\033[31m[no-host-paths] CANNOT RUN\033[0m — not a git repository.\n' >&2
	printf '  Discovery here is `git ls-files`, so without a resolvable checkout\n' >&2
	printf '  this gate cannot enumerate anything to scan.\n' >&2
	exit 3
}
cd "$root"

# ── scope exclusions (never staleness-checked; see the header) ─────────────
#
#   output/            generated bindings for ten languages. Upstream example
#                      data in the protovalidate descriptors carries private
#                      IP literals and the like; none of it is hand-authored.
#   renderer/lvgl/     vendored upstream, byte-exact per .gitattributes.
#   DONATION_*.md      the donation-fork record. A donation checkout is seeded
#                      with a brief that necessarily names absolute scratch and
#                      source paths, and the seed commit is never lifted to
#                      master — so on master this pattern matches NOTHING, which
#                      is exactly why it is an exclusion and not an allowlist
#                      entry. Staleness-checking it would turn the normal
#                      upstream state into a red gate.
#   this script        the two patterns are spelled out above, so the file
#                      necessarily contains them.
#   its canary suite   the fixtures it plants ARE offending paths, and its
#                      commentary quotes both patterns verbatim to explain what
#                      each canary proves. Obfuscating either — building the
#                      literals by concatenation so the scanner cannot see
#                      them — would buy a passing scan with an unreadable test,
#                      which is the trade this repo refuses everywhere else.
#                      COST, stated rather than buried: a real leak written into
#                      that one file would not be caught. Its fixtures therefore
#                      name a deliberately non-existent user.
#
# NAMED AS ONE FILE, NOT AS ITS DIRECTORY. `tools/lint/test/` will hold canary
# suites for later gates, and those have no reason to contain an operator path —
# so a directory-wide exclusion would silently stop scanning files that ought to
# be scanned. A future suite that genuinely needs the same carve-out adds its own
# line here, which is a decision on the record.
EXCLUDE_RE='^(output/|renderer/lvgl/|DONATION_[A-Z]+\.md|'"$ME"'|'"$MY_CANARIES"')'

# ── allowlist — a TRUE false positive, narrowly scoped, staleness-checked ──
#
# Format: <repo-relative path><TAB><literal substring that must appear on the
# offending line>. An entry excuses exactly the lines of that one file which
# contain that one substring; it is not a whole-file skip.
#
# ENTRY: .github/actions/build-controls-wasm/action.yml — `path: ~/wasi-sdk`
#   :rationale     This is an actions/cache `path:` value. `~` there is
#                  expanded by the runner against its own ephemeral HOME, so
#                  the string encodes no operator's layout — it is the same
#                  idiom as the ~/.m2 cache paths the pattern already declines
#                  to match, and is only caught because the directory name
#                  happens not to start with a dot.
#   :retires-when  the composite action stops caching the WASI-SDK under a
#                  home-relative path (e.g. it moves to a workspace-relative
#                  cache dir, or the SDK ships in the toolchain image instead).
#   :owner         gate-port
#   :expires       2026-10-27
ALLOWLIST=(
	".github/actions/build-controls-wasm/action.yml	path: ~/wasi-sdk"
)

# ── discovery ─────────────────────────────────────────────────────────────
# `--cached --others --exclude-standard` widens the INDEX to UNTRACKED-but-not-
# ignored files, and that widening is the whole point rather than a detail. The
# index alone gives an author who has written a new file and not staged it a
# GREEN THAT NEVER READ IT — and a leak arrives in a NEW file far more often than
# it is added to an old one, so the index-only form is blind in exactly the
# common case. lint.mk's lint-sh lane carries the same argument for the same
# reason. Ignored paths (scratch, preserved forks, caches) stay out, which is
# what keeps the widening free.
#
# git's stderr is CAPTURED rather than discarded: when discovery fails the
# reason IS the diagnosis, and the guard below prints it instead of guessing.
#
# ONE INVOCATION, ASKED TWICE, and it must be the SAME invocation both times.
# make's $(shell) and command substitution alike yield one stream per call, so
# the diagnosis is a second call — but if that call dropped the flags it would be
# a DIFFERENT EXPERIMENT from the one that failed, and any fault specific to
# those flags would produce an empty file list AND an empty diagnosis. lint-sh
# records that exact misdiagnosis; it is not repeated here.
DISCOVERY_ARGS=(--cached --others --exclude-standard)
discovery_err="$(git ls-files "${DISCOVERY_ARGS[@]}" 2>&1 >/dev/null)" || true
files="$(git ls-files "${DISCOVERY_ARGS[@]}" 2>/dev/null | grep -Ev "$EXCLUDE_RE" || true)"

# NON-VACUITY GUARD. This gate's PASS value is "no offending lines", which is
# also exactly what a BROKEN DISCOVERY prints. An empty file list is a green
# tick over zero coverage, so it must fail loud instead.
if [ -z "$files" ]; then
	printf '\033[31m[no-host-paths] CANNOT RUN\033[0m — discovered ZERO files to scan.\n' >&2
	printf '  This repo tracks hundreds of files, so an empty set means DISCOVERY\n' >&2
	printf '  broke, not that there is nothing to check.\n' >&2
	if [ -n "$discovery_err" ]; then
		printf '  git said: %s\n' "$discovery_err" >&2
	fi
	printf '  THE LINE ABOVE IS THE DIAGNOSIS, if there is one. The commonest cause\n' >&2
	printf '  is git being unable to resolve this checkout — see lint.mk lint-sh.\n' >&2
	exit 3
fi

scanned=$(printf '%s\n' "$files" | wc -l | tr -d ' ')

# ── scan ──────────────────────────────────────────────────────────────────
# Two greps rather than one alternation, so each pattern's hits can be
# reported under its own heading with its own fix advice.
# `-H` IS NOT OPTIONAL. grep prints the filename prefix only when it is given
# MORE THAN ONE file operand; hand it exactly one and the output is `line:text`
# with no path. `xargs` batches by total argv length, so whether any batch ends
# up holding a single file depends on how many files there are and how long
# their paths are — i.e. it changes as the tree changes, and the LAST batch is
# the one that varies. Everything downstream parses `file:line:text`: the
# allowlist matches on BOTH path and substring, and the report groups by file.
# Without `-H` an unlucky batch silently loses attribution, which surfaces as a
# gate that refuses correctly while its diagnosis names no clause.
raw_home="$(printf '%s\n' "$files" | tr '\n' '\0' | xargs -0 grep -HnE '/home/[a-z]' 2>/dev/null || true)"
raw_tilde="$(printf '%s\n' "$files" | tr '\n' '\0' | xargs -0 grep -HnE '~/[A-Za-z0-9_]' 2>/dev/null || true)"

# ── allowlist application, and its staleness check ────────────────────────
#
# An entry matches a finding line only when BOTH halves agree: the line is in
# THAT file and contains THAT substring. Matching on the substring alone would
# make one entry excuse the same text anywhere in the tree, which is a
# whole-repo skip wearing a per-file entry's clothes.
#
# `index()` is used rather than a regex on both halves so a path containing a
# regex metacharacter (a `+` or a `.`) is compared literally.
drop_allowlisted() {
	local blob="$1" entry path sub
	for entry in "${ALLOWLIST[@]}"; do
		path="${entry%%$'\t'*}"
		sub="${entry#*$'\t'}"
		blob="$(printf '%s\n' "$blob" |
			awk -v p="$path:" -v s="$sub" 'index($0, p) != 1 || index($0, s) == 0')"
	done
	printf '%s\n' "$blob"
}

# Staleness is judged against the UNFILTERED union of both patterns' hits: an
# entry is USED if it matched either one.
declare -a stale=()
for entry in "${ALLOWLIST[@]}"; do
	path="${entry%%$'\t'*}"
	sub="${entry#*$'\t'}"
	if ! printf '%s\n%s\n' "$raw_home" "$raw_tilde" |
		awk -v p="$path:" -v s="$sub" 'index($0, p) == 1 && index($0, s) > 0 { found = 1 }
		     END { exit !found }'; then
		stale+=("$path -> $sub")
	fi
done

home_offenders="$(drop_allowlisted "$raw_home" | grep -v '^$' || true)"
tilde_offenders="$(drop_allowlisted "$raw_tilde" | grep -v '^$' || true)"

rc=0

if [ "${#stale[@]}" -gt 0 ]; then
	{
		printf '\033[31m[no-host-paths] FAIL\033[0m — %d STALE allowlist entry/entries:\n' "${#stale[@]}"
		printf '    %s\n' "${stale[@]}"
		printf '  Each excuses a finding that no longer exists, so it is now widening\n'
		printf '  the unchecked surface for nothing. DELETE the entry from ALLOWLIST in\n'
		printf '  %s. This is the down-only half of the\n' "$ME"
		printf '  ratchet: an offender cannot be fixed while its excuse stays behind.\n'
	} >&2
	rc=1
fi

if [ -n "$home_offenders" ] || [ -n "$tilde_offenders" ]; then
	{
		printf '\033[31m[no-host-paths] FAIL\033[0m — operator-host paths in checked-in files:\n'
		if [ -n "$home_offenders" ]; then
			printf '\n  /home/<user>/ references:\n'
			printf '%s\n' "$home_offenders" | sed 's/^/    /'
		fi
		if [ -n "$tilde_offenders" ]; then
			printf '\n  ~/<dir> references:\n'
			printf '%s\n' "$tilde_offenders" | sed 's/^/    /'
		fi
		cat <<'EOF'

protogen is PUBLIC and is cloned by every consumer that pins it, so a path
under one operator's home is wrong for every other reader — and when it names
a private sibling checkout it leaks that the sibling exists.

Fix patterns:
  bash script :  root="$(git rev-parse --show-toplevel)"
  Makefile    :  $(shell git rev-parse --show-toplevel)
  prose       :  drop the absolute prefix and cite the repo-relative path, or
                 name the sibling the way .claude/rules/claude-md-policy.md
                 requires — by the path its own roster entry records, never by
                 an operator's home layout.

If a hit is a TRUE false positive, add a narrowly-scoped ALLOWLIST entry in
tools/lint/no_host_paths.sh carrying all four proof fields (:rationale,
:retires-when, :owner, :expires). Do NOT widen the pattern.
EOF
	} >&2
	rc=1
fi

if [ "$rc" != 0 ]; then
	exit 1
fi

printf '\033[32m[no-host-paths]\033[0m no operator-host paths (%s files, %s allowlist entr%s)\n' \
	"$scanned" "${#ALLOWLIST[@]}" "$([ "${#ALLOWLIST[@]}" = 1 ] && echo y || echo ies)"
