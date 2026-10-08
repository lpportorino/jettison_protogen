#!/usr/bin/env bash
# private_names_test.sh — canaries for tools/lint/private_names.sh.
#
# Every input the scanner reads is planted ALONE in its own fixture — an added
# line, a message, the author, the committer, a renamed path, an empty file, a
# NUL-carrying text file, a ref name, an annotated tag, a commit only a private
# remote-tracking ref holds — so a red names the input that carried the name.
# Each clause is then broken ALONE in a copy of the scanner (lib_mutate.sh
# proves the edit landed): its own case must go GREEN while a neighbouring case
# still FAILs, so no neighbour can be doing the work. Exit codes separate a
# verdict (1) from a precondition (3); every case asserts code AND message.
#
# The names below are synthetic. Nothing here names a real private repository.
#
# Usage: bash tools/lint/test/private_names_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/../private_names.sh"
MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
for f in "$SUT" "$MUTATE_LIB"; do
	[ -f "$f" ] || { printf '\033[31mCANNOT RUN\033[0m — missing %s\n' "$f" >&2; exit 3; }
done
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/private-names-test.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
PASS=0 FAILED=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; }
banner() { printf '\n== %s\n' "$*"; }
# HERMETIC: the machine's own git config, list variable and lists must not reach a
# fixture — a global protogen.privateNamesRequired, or a .private-names above the
# scratch directory, would change verdicts the suite asserts.
HERMETIC=(env -u GIT_DIR -u GIT_WORK_TREE -u GIT_CEILING_DIRECTORIES -u PROTOGEN_PRIVATE_NAMES
	GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1)
g() {
	local repo="$1"; shift
	GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 env -u GIT_DIR -u GIT_WORK_TREE -u GIT_CEILING_DIRECTORIES -u PROTOGEN_PRIVATE_NAMES git -C "$repo" "$@"
}

d="$WORK"
while :; do
	[ -e "$d/.private-names" ] && { printf 'CANNOT RUN — %s/.private-names exists above the scratch directory; every walk-up case would see it\n' "$d" >&2; exit 3; }
	[ "$d" = / ] && break; d="$(dirname -- "$d")"
done
LIST="$WORK/names"
printf '# synthetic private names\n\nquill-forge\nPLUME-[0-9]+\n' >"$LIST"
NAMED="--list $LIST"

fixture() { # fixture <name> — a repo whose root commit is clean; prints its path
	local repo="$WORK/$1"
	rm -rf -- "$repo"; mkdir -p "$repo"
	g "$repo" init -q
	g "$repo" config user.email t@example.invalid
	g "$repo" config user.name t
	printf 'clean\n' >"$repo/a.txt"
	g "$repo" add -A; g "$repo" commit -qm base
	printf '%s' "$repo"
}
commit_file() { # commit_file <repo> <path> <content> <message>
	printf '%s\n' "$3" >"$1/$2"
	g "$1" add -A; g "$1" commit -qm "$4"
}

# run <sut> <repo> <stdin> <args…> — merged output; returns the exit code.
run() {
	local sut="$1" repo="$2" input="$3"; shift 3
	(cd "$repo" && printf '%s' "$input" | "${HERMETIC[@]}" bash "$sut" "$@" 2>&1)
}
expect() { # expect <sut> <repo> <stdin> <code> <needle> <label> <args…>
	local sut="$1" repo="$2" input="$3" want="$4" needle="$5" label="$6" out code; shift 6
	out="$(run "$sut" "$repo" "$input" "$@")" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"; printf '%s\n' "$out" | sed 's/^/       | /' >&2; return
	fi
	if ! contains "$out" "$needle"; then
		bad "$label — exit $code but the message never said: $needle"; printf '%s\n' "$out" | sed 's/^/       | /' >&2; return
	fi
	ok "$label"
}
LAST='--range HEAD~1..HEAD'
PUBLISH='would publish a private name'

# One fixture per input, each the ONLY place its name appears.
R_LINE="$(fixture line)";       commit_file "$R_LINE" b.txt 'uses quill-forge here' 'add b'
R_MSG="$(fixture msg)";         commit_file "$R_MSG" b.txt 'clean' 'ported from Quill-Forge'
R_AUTHOR="$(fixture author)";   printf 'x\n' >"$R_AUTHOR/b.txt"; g "$R_AUTHOR" add -A
g "$R_AUTHOR" commit -qm 'add b' --author 'x <x@quill-forge.example>'
R_COMMITTER="$(fixture committer)"; printf 'x\n' >"$R_COMMITTER/b.txt"; g "$R_COMMITTER" add -A
GIT_COMMITTER_EMAIL='c@quill-forge.example' g "$R_COMMITTER" commit -qm 'add b'
# An extra header in the raw commit object, as a merged signed tag leaves one.
R_HEADER="$(fixture header)"; hdr_tree="$(g "$R_HEADER" rev-parse HEAD^{tree})" hdr_parent="$(g "$R_HEADER" rev-parse HEAD)"
hdr_commit="$(printf 'tree %s\nparent %s\nauthor t <t@example.invalid> 0 +0000\ncommitter t <t@example.invalid> 0 +0000\nmergetag object %s\n type commit\n tag quill-forge-release\n\nclean message\n' "$hdr_tree" "$hdr_parent" "$hdr_parent" | g "$R_HEADER" hash-object -t commit -w --stdin)"
g "$R_HEADER" update-ref HEAD "$hdr_commit"
R_PATH="$(fixture path)";       commit_file "$R_PATH" quill-forge.txt 'clean' 'add a file'
R_RENAME="$(fixture rename)";   g "$R_RENAME" mv a.txt quill-forge.txt; g "$R_RENAME" commit -qm 'rename'
R_EMPTY="$(fixture empty)";     : >"$R_EMPTY/quill-forge.txt"; g "$R_EMPTY" add -A; g "$R_EMPTY" commit -qm 'empty'
R_NUL="$(fixture nul)";         printf 'quill-forge\0x\n' >"$R_NUL/b.txt"; g "$R_NUL" add -A; g "$R_NUL" commit -qm 'nul'
R_REGEX="$(fixture regex)";     commit_file "$R_REGEX" b.txt 'see plume-4711' 'add b'
R_CLEAN="$(fixture clean)";     commit_file "$R_CLEAN" b.txt 'nothing to see' 'add b'
R_REMOVE="$(fixture remove)";   commit_file "$R_REMOVE" b.txt 'quill-forge' 'old'
commit_file "$R_REMOVE" b.txt 'gone' 'drop it'
# Stdin form: the name sits in a commit the remote already holds; only the newer one is pushed.
R_PUSH="$(fixture push)";       commit_file "$R_PUSH" b.txt 'quill-forge' 'already public'
commit_file "$R_PUSH" b.txt 'gone' 'new work'
PUSH_OLD="$(g "$R_PUSH" rev-parse HEAD~1)" PUSH_NEW="$(g "$R_PUSH" rev-parse HEAD)" PUSH_ROOT="$(g "$R_PUSH" rev-parse HEAD~2)"
CLEAN_HEAD="$(g "$R_CLEAN" rev-parse HEAD)" CLEAN_BASE="$(g "$R_CLEAN" rev-parse HEAD~1)"
g "$R_CLEAN" tag -a v1 -m 'release notes mention quill-forge'
CLEAN_TAG="$(g "$R_CLEAN" rev-parse v1)"
# A private remote-tracking ref holds a commit with the name; a NEW ref is pushed to a public remote.
R_PRIV="$(fixture private-remote)"; commit_file "$R_PRIV" b.txt 'quill-forge' 'private work'
g "$R_PRIV" update-ref refs/remotes/priv/main HEAD
g "$R_PRIV" remote add priv /nonexistent/priv; g "$R_PRIV" remote add pub /nonexistent/pub
PRIV_HEAD="$(g "$R_PRIV" rev-parse HEAD)"
# An unreadable object: the added blob is deleted from the object store.
R_BROKEN="$(fixture broken)"; commit_file "$R_BROKEN" b.txt 'some content' 'add b'
blob="$(g "$R_BROKEN" rev-parse HEAD:b.txt)"; rm -f "$R_BROKEN/.git/objects/${blob:0:2}/${blob:2}"
Z=0000000000000000000000000000000000000000

banner 'EACH INPUT IS SCANNED (FAIL = 1, naming where)'
expect "$SUT" "$R_LINE"      '' 1 "added lines $PUBLISH" 'a name in an ADDED LINE fails' $NAMED $LAST
expect "$SUT" "$R_MSG"       '' 1 "headers) $PUBLISH" 'a name in the COMMIT MESSAGE fails' $NAMED $LAST
expect "$SUT" "$R_AUTHOR"    '' 1 "headers) $PUBLISH" 'a name in the AUTHOR identity fails' $NAMED $LAST
expect "$SUT" "$R_COMMITTER" '' 1 "headers) $PUBLISH" 'a name in the COMMITTER identity fails' $NAMED $LAST
expect "$SUT" "$R_HEADER"    '' 1 "headers) $PUBLISH" 'a name in an EXTRA commit header fails (e.g. a merged tag)' $NAMED $LAST
expect "$SUT" "$R_PATH"      '' 1 "paths $PUBLISH" 'a name in a NEW PATH fails' $NAMED $LAST
expect "$SUT" "$R_RENAME"    '' 1 "paths $PUBLISH" 'a name in a pure RENAME target fails' $NAMED $LAST
expect "$SUT" "$R_EMPTY"     '' 1 "paths $PUBLISH" 'a name as an EMPTY file fails (no +++ line)' $NAMED $LAST
expect "$SUT" "$R_NUL"       '' 1 "added lines $PUBLISH" 'a name in NUL-carrying content fails (forced to text)' $NAMED $LAST
expect "$SUT" "$R_REGEX"     '' 1 "$PUBLISH" 'a line is an extended regex, case-insensitive' $NAMED $LAST

banner 'CONTROLS — what must NOT fail'
expect "$SUT" "$R_CLEAN"  '' 0 'clean — 1 commit(s) scanned' 'a clean commit passes and says how much it scanned' $NAMED $LAST
expect "$SUT" "$R_REMOVE" '' 0 'clean — 1 commit(s)' 'a REMOVED line is already public and is not scanned' $NAMED $LAST

banner 'PRE-PUSH STDIN — only what the push would publish'
expect "$SUT" "$R_PUSH" "refs/heads/master $PUSH_NEW refs/heads/master $PUSH_OLD
" 0 'clean — 1 commit(s)' 'a name only in a commit the remote holds is not re-flagged' $NAMED
expect "$SUT" "$R_PUSH" "refs/heads/master $PUSH_NEW refs/heads/master $PUSH_ROOT
" 1 "$PUBLISH" 'the same name in a commit the remote LACKS fails' $NAMED
expect "$SUT" "$R_PUSH" "(delete) $Z refs/heads/gone $PUSH_NEW
" 0 'clean — no new commits' 'a deletion publishes nothing' $NAMED
expect "$SUT" "$R_CLEAN" "refs/heads/quill-forge-wip $CLEAN_HEAD refs/heads/quill-forge-wip $CLEAN_BASE
" 1 "ref name or tag $PUBLISH" 'a name in a pushed REF NAME fails' $NAMED
expect "$SUT" "$R_CLEAN" "refs/tags/v1 $CLEAN_TAG refs/tags/v1 $Z
" 1 "ref name or tag $PUBLISH" 'a name in an annotated TAG message fails' $NAMED --remote pub
expect "$SUT" "$R_PRIV" "refs/heads/new $PRIV_HEAD refs/heads/new $Z
" 1 "$PUBLISH" 'a new ref to one remote is not excused by ANOTHER remote'"'"'s tracking refs' $NAMED --remote pub
expect "$SUT" "$R_PRIV" "refs/heads/new $PRIV_HEAD refs/heads/new $Z
" 0 'clean — no new commits' 'CONTROL: the same push to the remote that holds it is clean' $NAMED --remote priv

banner 'PRECONDITIONS — NOT RUN only for an absent DEFAULT list; CANNOT RUN (3) otherwise'
expect "$SUT" "$R_LINE" '' 4 'WARNING — NOT RUN' 'no list anywhere: NOT RUN (4) — checked nothing, blocks nothing' $LAST
expect "$SUT" "$R_LINE" '' 3 'named list' 'a NAMED list that is missing is CANNOT RUN' --list "$WORK/absent" $LAST
g "$R_CLEAN" config protogen.privateNamesRequired true
expect "$SUT" "$R_CLEAN" '' 3 'privateNamesRequired' 'a REQUIRED default list that is missing is CANNOT RUN' $LAST
g "$R_CLEAN" config --unset protogen.privateNamesRequired
printf '# only comments\n\n   \n' >"$WORK/list-empty"
expect "$SUT" "$R_LINE" '' 3 'holds no patterns' 'a list with no patterns cannot pass everything' --list "$WORK/list-empty" $LAST
printf 'quill(\n' >"$WORK/list-broken"
expect "$SUT" "$R_LINE" '' 3 'invalid extended regex' 'an invalid pattern is refused, never ignored' --list "$WORK/list-broken" $LAST
printf '  quill-forge  \r\n' >"$WORK/list-crlf"
expect "$SUT" "$R_LINE" '' 1 "$PUBLISH" 'a CRLF-saved, padded list still matches' --list "$WORK/list-crlf" $LAST
expect "$SUT" "$R_BROKEN" '' 3 'could not read commit' 'an unreadable object is CANNOT RUN, never clean' $NAMED $LAST
expect "$SUT" "$R_LINE" '' 3 'unknown argument' 'bad usage is CANNOT RUN' --bogus

banner 'WALK-UP DISCOVERY — a .private-names above the checkout; never one inside a work tree'
OUTER="$WORK/outer"; mkdir -p "$OUTER"; printf 'quill-forge\n' >"$OUTER/.private-names"
R_UP="$OUTER/up"; mkdir -p "$R_UP"; g "$R_UP" init -q; g "$R_UP" config user.email t@example.invalid; g "$R_UP" config user.name t
commit_file "$R_UP" a.txt 'clean' base; commit_file "$R_UP" b.txt 'uses quill-forge' 'add b'
expect "$SUT" "$R_UP" '' 1 "$PUBLISH" 'a .private-names in a PARENT directory arms the scan' $LAST
printf 'plume-[0-9]+\n' >"$R_UP/.git/info/private-names"
commit_file "$R_UP" c.txt 'see plume-7' 'add c'
expect "$SUT" "$R_UP" '' 1 'pattern at .git/info/private-names:1' 'UNION: the .git/info list applies while a parent list exists' $LAST
expect "$SUT" "$R_UP" '' 1 "pattern at $OUTER/.private-names:1" 'UNION: the parent list applies while a .git/info list exists' --range HEAD~2..HEAD~1
R_TOP="$WORK/top"; mkdir -p "$R_TOP"; g "$R_TOP" init -q; g "$R_TOP" config user.email t@example.invalid; g "$R_TOP" config user.name t
commit_file "$R_TOP" a.txt 'clean' base; commit_file "$R_TOP" b.txt 'uses quill-forge' 'add b'
printf 'quill-forge\n' >"$R_TOP/.private-names"
expect "$SUT" "$R_TOP" '' 3 'inside a git work tree' 'a list at the checkout TOP is refused: one git add would publish it' $LAST
PARENT_REPO="$WORK/parent-repo"; mkdir -p "$PARENT_REPO"; g "$PARENT_REPO" init -q
printf 'quill-forge\n' >"$PARENT_REPO/.private-names"
R_NESTED="$PARENT_REPO/nested"; mkdir -p "$R_NESTED"; g "$R_NESTED" init -q; g "$R_NESTED" config user.email t@example.invalid; g "$R_NESTED" config user.name t
commit_file "$R_NESTED" a.txt 'clean' base; commit_file "$R_NESTED" b.txt 'clean too' 'add b'
expect "$SUT" "$R_NESTED" '' 3 'inside a git work tree' 'a list in a parent that is itself a REPOSITORY is refused' $LAST
printf 'nomatch-pattern' >"$R_UP/.git/info/private-names"            # no trailing newline
printf 'quill-forge' >"$OUTER/.private-names"
expect "$SUT" "$R_UP" '' 1 "pattern at $OUTER/.private-names:1" 'lists without trailing newlines do not FUSE' --range HEAD~2..HEAD~1
printf 'quill-forge\n' >"$OUTER/.private-names"; printf 'plume-[0-9]+\n' >"$R_UP/.git/info/private-names"
out="$(run "$SUT" "$R_UP" '' --range HEAD~2..HEAD~1)" || true
if contains "$out" '[private name]' && ! contains "$out" 'quill-forge'; then ok 'a FAIL masks the matched text: the name is never printed'
else bad 'a FAIL printed the matched name, or masked nothing'; printf '%s\n' "$out" | sed 's/^/       | /' >&2; fi
R_DIRLIST="$WORK/dirlist"; mkdir -p "$R_DIRLIST/.private-names"; R_DL="$R_DIRLIST/repo"; mkdir -p "$R_DL"; g "$R_DL" init -q
g "$R_DL" config user.email t@example.invalid; g "$R_DL" config user.name t; commit_file "$R_DL" a.txt 'clean' base
expect "$SUT" "$R_DL" '' 3 'not a regular file' 'a list that is a DIRECTORY is CANNOT RUN, never skipped' --range HEAD
R_LINKLIST="$WORK/linklist"; mkdir -p "$R_LINKLIST"; ln -s "$WORK/nowhere/.private-names" "$R_LINKLIST/.private-names"
R_LL="$R_LINKLIST/repo"; mkdir -p "$R_LL"; g "$R_LL" init -q; g "$R_LL" config user.email t@example.invalid; g "$R_LL" config user.name t
commit_file "$R_LL" a.txt 'clean' base
expect "$SUT" "$R_LL" '' 3 'not a regular file' 'a BROKEN SYMLINK list is CANNOT RUN, never "no list"' --range HEAD

m="$WORK/mutant-walk.sh"; cp "$SUT" "$m"
if err="$(mutate_file "$m" '    { [ -e "$dir/.private-names" ] || [ -L "$dir/.private-names" ]; } && lists+=("$dir/.private-names")' '    :' 2>&1)"; then
	expect "$m" "$R_UP" '' 1 'pattern at .git/info/private-names:1' 'walk-up clause: CONTROL — the .git/info list still arms the scan' $LAST
	rm -f "$R_UP/.git/info/private-names"
	expect "$m" "$R_UP" '' 4 'WARNING — NOT RUN' 'walk-up clause: broken, a parent list is never found' $LAST
else bad "walk-up mutation did not land: $err"; fi
m="$WORK/mutant-worktree.sh"; cp "$SUT" "$m"
if err="$(mutate_file "$m" 'rev-parse --is-inside-work-tree 2>/dev/null)" = true ]' 'rev-parse --is-inside-work-tree 2>/dev/null)" = never ]' 2>&1)"; then
	expect "$m" "$R_TOP" '' 1 "$PUBLISH" 'work-tree clause: broken, a committable list is silently used' $LAST
	expect "$m" "$R_UP" '' 1 "$PUBLISH" 'work-tree clause: CONTROL — a safe parent list still works' --range HEAD~2..HEAD~1
else bad "work-tree mutation did not land: $err"; fi

banner 'ATTRIBUTION — break ONE clause; its case goes green, a neighbour still fails'
# attribute <label> <old> <new> <green-repo> <green-stdin> <red-repo> <red-stdin> <args…>
attribute() {
	local label="$1" old="$2" new="$3" green="$4" gin="$5" red="$6" rin="$7" m err; shift 7
	m="$WORK/mutant.sh"; cp "$SUT" "$m"
	if ! err="$(mutate_file "$m" "$old" "$new" 2>&1)"; then bad "$label — mutation did not land: $err"; return; fi
	expect "$m" "$green" "$gin" 0 'clean' "$label: with it broken, its own case passes" "$@"
	expect "$m" "$red" "$rin" 1 "$PUBLISH" "$label: CONTROL — a neighbouring input still fails" "$@"
}
for planted in "$R_MSG" "$R_AUTHOR" "$R_COMMITTER" "$R_HEADER"; do
	attribute "commit-object clause ($(basename "$planted"))" 'report "commit $short'"'"'s object (identity, message, headers)" "$work/object"' ':' "$planted" '' "$R_LINE" '' $NAMED $LAST
done
attribute 'paths clause'     'report "commit $short'"'"'s paths" "$work/paths"' ':' "$R_EMPTY" '' "$R_LINE" '' $NAMED $LAST
attribute 'added-line clause' 'report "commit $short'"'"'s added lines" "$work/added"' ':' "$R_LINE" '' "$R_RENAME" '' $NAMED $LAST
attribute 'forced-text clause' ' --text --no-renames "$c"' ' --no-renames "$c"' "$R_NUL" '' "$R_LINE" '' $NAMED $LAST
attribute 'ref-name clause'  'report "a pushed ref name or tag" "$work/refs"' ':' "$R_CLEAN" "refs/heads/quill-forge-wip $CLEAN_HEAD refs/heads/quill-forge-wip $CLEAN_BASE
" "$R_PUSH" "refs/heads/master $PUSH_NEW refs/heads/master $PUSH_ROOT
" $NAMED
attribute 'remote-tip clause' 'git rev-list "$rsha..$lsha"' 'git rev-list "$lsha" --not --all' "$R_PUSH" "refs/heads/master $PUSH_NEW refs/heads/master $PUSH_ROOT
" "$R_PRIV" "refs/heads/new $PRIV_HEAD refs/heads/new $Z
" $NAMED --remote pub
attribute 'per-remote exclusion' 'exclude=(--not "--remotes=$remote")' 'exclude=(--not --remotes)' "$R_PRIV" "refs/heads/new $PRIV_HEAD refs/heads/new $Z
" "$R_PUSH" "refs/heads/master $PUSH_NEW refs/heads/master $PUSH_ROOT
" $NAMED --remote pub
# Clauses whose broken form passes on a PRECONDITION input; each control is a planted name still caught.
m="$WORK/mutant-empty.sh"; cp "$SUT" "$m"
if err="$(mutate_file "$m" '[ -s "$patterns" ] ||' 'true ||' 2>&1)"; then
	expect "$m" "$R_LINE" '' 0 'clean' 'empty-list clause: broken, an empty list passes a planted name' --list "$WORK/list-empty" $LAST
	expect "$m" "$R_LINE" '' 1 "$PUBLISH" 'empty-list clause: CONTROL — a real list still fails it' $NAMED $LAST
else bad "empty-list mutation did not land: $err"; fi
m="$WORK/mutant-read.sh"; cp "$SUT" "$m"
if err="$(mutate_file "$m" '|| cannot "git could not read commit $short' '|| true "git could not read commit $short' 2>&1)"; then
	expect "$m" "$R_BROKEN" '' 0 'clean' 'read-failure clause: broken, an unreadable commit reports clean' $NAMED $LAST
	expect "$m" "$R_LINE" '' 1 "$PUBLISH" 'read-failure clause: CONTROL — a readable name still fails' $NAMED $LAST
else bad "read-failure mutation did not land: $err"; fi

printf '\n%s passed, %s failed\n' "$PASS" "$FAILED"
[ "$PASS" -gt 0 ] || { printf 'CANNOT RUN — no case executed\n' >&2; exit 3; }
[ "$FAILED" -eq 0 ]
