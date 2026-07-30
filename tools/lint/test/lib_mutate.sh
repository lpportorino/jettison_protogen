#!/usr/bin/env bash
# lib_mutate.sh — the shared MUTATION primitive for this repo's gate canary suites,
# plus the self-test that proves it can refuse.
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
