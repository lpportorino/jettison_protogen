#!/usr/bin/env bash
# lib_mutate.sh — the shared primitives every gate canary suite here is built on:
# the MUTATION primitive that breaks one clause, the SUBSTRING primitive that reads
# the resulting diagnosis, and the self-tests that prove each can refuse.
#
# The filename predates the second primitive and is kept deliberately: it is cited
# by `# shellcheck source=` directives, by `.claude/rules/regression-test-first.md`
# and by a CI workflow, and a rename buys nothing the header cannot say.
#
# WHY THIS IS SHARED RATHER THAN COPIED. Every canary suite here proves a clause by
# breaking it alone and requiring the gate to change verdict. That proof is worth
# exactly as much as the guarantee that the break LANDED — a mutation that silently
# matched nothing yields a mutant identical to the original, and its unchanged
# verdict then reads as attribution while proving the opposite. Two independent
# copies of a subtle 40-line primitive is how one of them quietly stops refusing.
#
# WHY NOT `grep -F`. grep is LINE-ORIENTED: handed a multi-line anchor it treats
# each line as a separate ALTERNATIVE and reports a match on any surviving
# fragment. An anchor containing a blank line therefore matches every file in
# existence, and both the precondition and the landed-check become vacuous. That
# defect was live in this repo: a mutation that HAD landed was reported as not
# landed, and the canary it guarded never ran at all.
#
# WHY NOT PYTHON. Nothing in this repo lints python, so a harness written in it is
# unjudged code that every verdict depends on — `.claude/rules/gate-enforcement.md`
# §5. Bash does exact literal replacement natively.
#
# THE MECHANISM IS ONE PAIR OF QUOTES. `${var//"$pat"/"$rep"}` is a LITERAL
# replacement when the pattern is QUOTED inside the expansion, and a GLOB when it
# is not. Unquoted, an anchor containing `*`, `?` or `[` silently matches something
# else — so the quotes are the correctness property, not style. `mutate_selftest`
# pins exactly that with an `a*b` anchor that must NOT match `aXb`.
#
# CONTRACT. `mutate_file <path> <old> <new>` replaces the anchor and returns 0, or
# prints a one-line diagnosis to stderr and returns non-zero. It REFUSES when:
#   - the anchor is absent (precondition)
#   - the anchor is ambiguous (more than one occurrence — an ambiguous mutation
#     cannot attribute a verdict to one clause)
#   - the file did not change
#   - the replacement is absent afterwards
#   - the anchor survives afterwards, EXCEPT where the replacement deliberately
#     contains it (widening a form rather than removing it), where that assertion
#     is inapplicable and "the file changed" is the check with teeth.
#
# Callers report through their own `bad`/`ok`; this file only returns a status and
# writes a reason to stderr, so it imposes no reporting convention.
#
# ---------------------------------------------------------------------------
# CONTRACT, the second primitive. `contains <haystack> <needle>` returns 0 when
# the needle occurs LITERALLY in the haystack and 1 otherwise. It forks nothing,
# writes nothing, and reads no file.
#
# IT EXISTS BECAUSE `printf '%s' "$out" | grep -qF -- "$needle"` IS A RACE, and
# that race made this repo's own lint aggregate nondeterministic. `grep -q` exits
# the instant it matches, WITHOUT draining stdin; the `printf` on the left is then
# killed by SIGPIPE mid-write. Every suite here runs `set -o pipefail`, which
# reports the pipeline as the RIGHTMOST NON-ZERO status — so the pipeline yields
# 141 even though grep found the needle and exited 0. Negated, as every diagnosis
# assertion is, that reads as "the needle was absent" and the case reports a FAIL
# the gate never earned.
#
# WHETHER THE RACE LANDS IS PURE SCHEDULING: it needs the writer to be descheduled
# between grep's exit and its own write. On an idle machine it is close to never;
# under the CPU pressure the parallel aggregate creates by design, it is routine.
# Measured on this repo's own suites, on one loaded 12-core host: `PIPESTATUS=[141
# 0]` — writer killed, reader satisfied — at 2 to 14 occurrences per 8000
# executions, against roughly 240 executions per aggregate run.
#
# THE MECHANISM IS AGAIN ONE PAIR OF QUOTES, the same property `mutate_file` turns
# on. `case $h in *"$n"*)` is a LITERAL substring test because the needle is
# QUOTED inside the pattern; unquoted it would be a GLOB, and a needle containing
# `*`, `?` or `[` would silently match something else. `contains_selftest` pins
# both directions.
#
# IT IS STRICTER THAN `grep -qF`, ON PURPOSE, and the difference is the one this
# file's own header already warns about: grep is LINE-ORIENTED, so a multi-line
# needle is matched as separate alternatives and any surviving line satisfies it.
# `contains` requires the whole needle, newlines included.
#
# WHEN A REGEX IS GENUINELY NEEDED, use a here-string — `grep -qE 're' <<< "$out"`
# — never a pipe. A here-string is a one-element pipeline, so there is no writer
# process for SIGPIPE to reach and nothing for `pipefail` to promote.

# mutate_file <path> <old> <new>
mutate_file() {
	local f="$1" old="$2" new="$3"
	local content rest out count=0

	[ -f "$f" ] || {
		printf 'mutate: subject does not exist: %s\n' "$f" >&2
		return 1
	}

	# Command substitution strips EVERY trailing newline, so a sentinel is appended
	# and removed. Without it the mutant loses its final newline, and the difference
	# between mutant and original is then not the mutation under test.
	content="$(cat -- "$f" && printf 'X')"
	content="${content%X}"

	# Literal occurrence COUNT. `${rest#*"$old"}` is a shortest-prefix strip whose
	# needle is quoted, hence literal; the loop is the portable way to count rather
	# than merely detect.
	rest="$content"
	while [ -n "$old" ] && [ "${rest#*"$old"}" != "$rest" ]; do
		rest="${rest#*"$old"}"
		count=$((count + 1))
	done

	if [ "$count" -eq 0 ]; then
		printf 'mutate: precondition — anchor absent from the subject\n' >&2
		return 1
	fi
	if [ "$count" -ne 1 ]; then
		printf 'mutate: anchor is ambiguous — matched %d times, want exactly 1\n' "$count" >&2
		return 1
	fi

	out="${content//"$old"/"$new"}"
	if [ "$out" = "$content" ]; then
		printf 'mutate: did not land — file unchanged\n' >&2
		return 1
	fi
	case "$out" in
	*"$new"*) ;;
	*)
		printf 'mutate: did not land — replacement absent afterwards\n' >&2
		return 1
		;;
	esac
	case "$new" in
	*"$old"*) ;; # replacement contains the anchor; see the header
	*)
		case "$out" in
		*"$old"*)
			printf 'mutate: did not land — anchor still present afterwards\n' >&2
			return 1
			;;
		esac
		;;
	esac

	printf '%s' "$out" > "$f"
	return 0
}

# mutate_selftest <scratch-dir> — prove the primitive can REFUSE, and can be
# literal. Echoes one `ok`/`FAIL` line per case; returns non-zero if any failed.
#
# THIS IS NOT CEREMONY. Every mutation canary in every suite is worth nothing if
# this primitive accepts a mutation that did not land, and the failure is SILENT:
# a helper that always returns 0 turns every attribution proof into a tautology
# while the suites all still print green. So the suites run it before their own
# cases, and a red here fails the suite.
mutate_selftest() {
	local w="$1"
	local fails=0
	local f

	_st_ok() { printf '  \033[32mok\033[0m   mutate: %s\n' "$1"; }
	_st_bad() {
		printf '  \033[31mFAIL\033[0m mutate: %s\n' "$1" >&2
		fails=$((fails + 1))
	}

	mkdir -p "$w"

	# 1. An absent anchor must REFUSE. Without this the primitive would silently
	#    accept every anchor that had drifted out of the subject.
	f="$w/absent"
	printf 'alpha\nbeta\n' > "$f"
	if mutate_file "$f" 'NOT-PRESENT' 'x' 2> /dev/null; then
		_st_bad 'accepted an absent anchor'
	else
		_st_ok 'refuses an absent anchor'
	fi

	# 2. An ambiguous anchor must REFUSE — a mutation applied in two places cannot
	#    attribute a verdict to one clause.
	f="$w/ambiguous"
	printf 'dup\ndup\n' > "$f"
	if mutate_file "$f" 'dup' 'y' 2> /dev/null; then
		_st_bad 'accepted an ambiguous anchor'
	else
		_st_ok 'refuses an ambiguous anchor'
	fi

	# 3. A MULTI-LINE anchor must work — this is the case `grep -F` cannot judge.
	f="$w/multiline"
	printf 'alpha\nbeta\ngamma\n' > "$f"
	if mutate_file "$f" 'alpha
beta' 'ALPHA
BETA' && [ "$(cat "$f")" = 'ALPHA
BETA
gamma' ]; then
		_st_ok 'replaces a multi-line anchor exactly'
	else
		_st_bad 'multi-line anchor replacement is wrong'
	fi

	# 4. The trailing newline must SURVIVE, or every mutant differs from its
	#    original by one byte that is not the mutation.
	f="$w/newline"
	printf 'one\ntwo\n' > "$f"
	mutate_file "$f" 'one' 'ONE' > /dev/null 2>&1
	if [ "$(od -An -c "$f" | tr -s ' ' | grep -c '\\n$')" -ge 1 ]; then
		_st_ok 'preserves the trailing newline'
	else
		_st_bad 'stripped the trailing newline'
	fi

	# 5. A replacement CONTAINING the anchor must be ACCEPTED — rejecting it would
	#    be the false red this primitive exists to prevent.
	f="$w/contains"
	printf 'ALLOW=(\n)\n' > "$f"
	if mutate_file "$f" 'ALLOW=(' 'ALLOW=(); _parked=(' 2> /dev/null; then
		_st_ok 'accepts a replacement that contains the anchor'
	else
		_st_bad 'rejected a replacement containing the anchor'
	fi

	# 6/7. THE LITERAL PROPERTY, both directions. An anchor with a glob
	#      metacharacter must match ITSELF and must NOT match what the glob would.
	#      This is what the quoting inside the expansion buys, and the only case
	#      that fails loudly if someone "simplifies" those quotes away.
	f="$w/glob-literal"
	printf 'a*b\n' > "$f"
	if mutate_file "$f" 'a*b' 'OK' 2> /dev/null && [ "$(cat "$f")" = 'OK' ]; then
		_st_ok 'a glob metacharacter in the anchor matches itself'
	else
		_st_bad 'failed to match a literal anchor containing *'
	fi
	f="$w/glob-nomatch"
	printf 'aXb\n' > "$f"
	if mutate_file "$f" 'a*b' 'OK' 2> /dev/null; then
		_st_bad 'anchor "a*b" matched "aXb" — the pattern is being GLOBBED, not compared'
	else
		_st_ok 'a glob metacharacter does NOT match as a glob'
	fi

	unset -f _st_ok _st_bad
	[ "$fails" -eq 0 ]
}

# contains <haystack> <needle> — LITERAL substring test. No fork, no pipe, no
# temporary file. Returns 0 on a hit, 1 on a miss.
#
# An EMPTY needle matches everything, exactly as `grep -F ''` does. Callers that
# treat "no needle given" as "assert nothing" must guard with `[ -n "$needle" ]`
# before calling, which is what every `expect` here does.
contains() {
	case "$1" in
	*"$2"*) return 0 ;;
	esac
	return 1
}

# contains_selftest <scratch-dir> — prove the primitive is literal, and DEMONSTRATE
# the pipeline form it replaces producing a false verdict.
#
# THE LAST CASE IS THE WHOLE POINT AND IT IS DETERMINISTIC. In production the race
# needs a scheduling accident, which no suite can require. Here it is forced: the
# needle is a COMPLETE FIRST LINE of a payload far larger than a pipe's capacity,
# so the reader can decide on its very first read and exit while the writer is
# still blocked with megabytes to go. The writer then dies of SIGPIPE and
# `pipefail` promotes 141 over the reader's own 0 — the false verdict, on demand.
#
# THE TRAILING NEWLINE ON THE NEEDLE IS LOAD-BEARING, and leaving it out is how a
# first draft of this case failed to demonstrate anything. Without it the match
# sits on an unterminated line, so the reader must consume to EOF before it can
# call the line a match; it therefore drains the whole payload, the writer
# completes, and the pipeline reports a serene 0. Measured: 0 of 300 forced with
# no newline, 300 of 300 forced with one.
#
# So this case asserts the DEFECT, not the fix: `PIPESTATUS[1]` must be 0 (the
# reader was satisfied) while the pipeline status is non-zero (the assertion built
# on it would have gone red). If it ever stops holding, the hazard has changed
# shape on this platform and the demonstration — not the tree — is what is wrong.
# Its message says so, so nobody reads it as a finding about the repository.
#
# `pipefail` is set here rather than assumed. Every suite that sources this file
# sets it, but the case is a claim ABOUT that option, so reading it off the
# caller's state would make the demonstration silently vacuous for anyone who did
# not — and vacuous is the one outcome a canary may not have.
#
# The scratch dir is taken for signature symmetry with `mutate_selftest`; nothing
# here writes to disk, because writing is what the primitive exists to avoid.
contains_selftest() {
	local w="$1"
	local fails=0
	local big pf ps

	_ct_ok() { printf '  \033[32mok\033[0m   contains: %s\n' "$1"; }
	_ct_bad() {
		printf '  \033[31mFAIL\033[0m contains: %s\n' "$1" >&2
		fails=$((fails + 1))
	}

	mkdir -p "$w"

	# 1/2. The ordinary directions.
	if contains 'alpha beta gamma' 'beta'; then _ct_ok 'finds a needle that is present'; else
		_ct_bad 'missed a needle that is present'
	fi
	if contains 'alpha beta gamma' 'delta'; then _ct_bad 'matched a needle that is absent'; else
		_ct_ok 'refuses a needle that is absent'
	fi

	# 3/4. THE LITERAL PROPERTY, both directions — what the quoting inside the
	#      `case` pattern buys, and the only pair that fails loudly if someone
	#      "simplifies" those quotes away.
	if contains 'a*b' 'a*b'; then _ct_ok 'a glob metacharacter in the needle matches itself'; else
		_ct_bad 'failed to match a literal needle containing *'
	fi
	if contains 'aXb' 'a*b'; then
		_ct_bad 'needle "a*b" matched "aXb" — the pattern is being GLOBBED, not compared'
	else
		_ct_ok 'a glob metacharacter does NOT match as a glob'
	fi

	# 5. STRICTER THAN grep -F on a multi-line needle: grep would accept this on
	#    the strength of the surviving first line alone.
	if contains 'one
three' 'one
two'; then
		_ct_bad 'a multi-line needle matched on one surviving line — grep semantics leaked in'
	else
		_ct_ok 'a multi-line needle is matched whole, not line by line'
	fi

	# 6. THE RACE, forced. See the header of this function.
	printf -v big 'NEEDLE-AT-THE-FRONT\n%*s' 2000000 ''
	pf=off
	if [[ -o pipefail ]]; then pf=on; fi
	set -o pipefail
	if printf '%s' "$big" | grep -qF -- 'NEEDLE-AT-THE-FRONT'; then
		_ct_bad 'the retired pipe form did NOT misreport here — this platform no longer
       reproduces the SIGPIPE/pipefail hazard, so this demonstration is stale.
       It is NOT a finding about this repository.'
	else
		# FIRST command in the branch: a bare `rc=$?` is itself a pipeline and would
		# overwrite PIPESTATUS with its own one-element status.
		ps="${PIPESTATUS[*]}"
		case "$ps" in
		*' 0') _ct_ok "the retired pipe form misreports a PRESENT needle (PIPESTATUS=[$ps])" ;;
		*) _ct_bad "the pipe form failed, but the READER did not succeed (PIPESTATUS=[$ps]) —
       that is some other failure, not the SIGPIPE/pipefail hazard" ;;
		esac
	fi
	if [ "$pf" = off ]; then set +o pipefail; fi
	if contains "$big" 'NEEDLE-AT-THE-FRONT'; then
		_ct_ok 'contains reads the same payload correctly'
	else
		_ct_bad 'contains missed a needle the pipe form found'
	fi

	unset -f _ct_ok _ct_bad
	[ "$fails" -eq 0 ]
}
