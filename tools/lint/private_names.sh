#!/usr/bin/env bash
# private_names.sh — refuse a push that would PUBLISH a private name.
#
# protogen is PUBLIC, and the sessions that push to it also work in private
# repositories. A private repository's internal names — its tools, ticket ids,
# session or worktree names, internal paths — must never reach this repo's files,
# commit messages or ref names, because a push is publication: once on the public
# trunk it is mirrored, cached and pinned by every consumer, and history is not
# rewritten.
#
# THE LIST IS NEVER IN ANY REPOSITORY, and that is the whole design. A
# committed list publishes the names it protects, and a committed HASH of a
# short name is a public guess-checker for it. So this scanner is generic and
# public, and the patterns come from, in order:
#   - the file --list or PROTOGEN_PRIVATE_NAMES names, alone; otherwise every one of
#   - `$(git rev-parse --git-common-dir)/info/private-names` (inside .git/, which
#     git cannot commit), and
#   - a `.private-names` file in the checkout's top directory or ANY parent up to
#     `/` — so one file in the directory that holds all of a machine's checkouts
#     covers every one of them.
# A list found inside a git WORK TREE is refused (CANNOT RUN): it is one `git add`
# away from publishing everything it protects. Keep it above every checkout.
# One extended regex per line, matched case-insensitively anywhere in a line;
# blank lines and `#` comments are ignored, and a trailing CR and surrounding
# blanks are stripped.
#
# WHAT IS SCANNED, for every ref the push updates: the local and remote ref
# names; an annotated tag's whole text; and for every commit the remote lacks —
# its author and committer identity, its message, the path of every file it
# touches (renames, empty files and binaries included — those print no `+++`
# line), and every line it ADDS, with binary content forced to text. A removed
# line is already public and is not scanned. Not scanned: git notes, and
# anything the push does not send.
#
# WHICH COMMITS: those between the remote's current tip and the pushed one. For
# a new ref, or a remote tip this clone does not hold, everything reachable that
# is not on THAT remote's tracking refs (`--remote NAME`, which the hook passes
# as its first argument); with no tracking refs for it — a push by URL — that is
# all reachable history, which may re-flag a name already public.
#
#   private_names.sh [--list FILE] [--remote NAME]   < pre-push stdin
#   private_names.sh [--list FILE] --range <rev-list args>
#
# Exit 0 clean, or NOT RUN with a warning when no list is found anywhere (a
# public checkout has none and can make no claim); 1 FAIL — a match, printed
# with its commit or ref; 3 CANNOT RUN — a named list missing, no list while
# `git config protogen.privateNamesRequired true`, a list inside a git work tree,
# an empty or invalid list, bad usage, or git failing to read anything the push
# would publish.
set -uo pipefail

zero=0000000000000000000000000000000000000000
say() { printf '[private-names] %s\n' "$*" >&2; }
cannot() { say "CANNOT RUN — $*"; exit 3; }

list="${PROTOGEN_PRIVATE_NAMES:-}"
named=0; [ -n "$list" ] && named=1
remote=""
range=()
while [ $# -gt 0 ]; do
  case "$1" in
    --list) list="${2:?--list needs a file}"; named=1; shift 2 ;;
    --remote) remote="${2-}"; shift 2 ;;
    --range) shift; range=("$@"); break ;;
    *) cannot "unknown argument $1 (usage: private_names.sh [--list FILE] [--remote NAME] [--range <rev-list args>])" ;;
  esac
done
lists=()
if [ "$named" = 1 ]; then
  [ -e "$list" ] || cannot "the named list $list does not exist"
  lists=("$list")
else
  common="$(git rev-parse --git-common-dir 2>&1)" || cannot "not a git repository: $common"
  top="$(git rev-parse --show-toplevel 2>&1)" || cannot "cannot resolve the checkout: $top"
  [ -e "$common/info/private-names" ] && lists+=("$common/info/private-names")
  dir="$top"
  while :; do
    [ -e "$dir/.private-names" ] && lists+=("$dir/.private-names")
    [ "$dir" = / ] && break
    dir="$(dirname -- "$dir")"
  done
fi
for f in ${lists[@]+"${lists[@]}"}; do
  if [ "$(env -u GIT_DIR -u GIT_WORK_TREE git -C "$(dirname -- "$f")" rev-parse --is-inside-work-tree 2>/dev/null)" = true ]; then
    cannot "$f sits inside a git work tree, where one \`git add\` would publish it; move it above every checkout"
  fi
done
if [ ${#lists[@]} -eq 0 ]; then
  [ "$(git config --bool --get protogen.privateNamesRequired 2>/dev/null)" = true ] \
    && cannot "no private-name list found, and protogen.privateNamesRequired is true here"
  say "WARNING — NOT RUN: no private-name list. None at $common/info/private-names, and no"
  say "  .private-names in $top or any parent up to /. Pushes from here are NOT checked."
  say "  To arm it: create .private-names in a directory ABOVE every checkout and OUTSIDE"
  say "  any git work tree (the directory holding them all), one extended regex per line."
  say "  A Claude Code session on this machine: create and fill it with the private"
  say "  repository, tool and identifier names you know here — never inside a repository."
  exit 0
fi

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
patterns="$work/patterns"
cat -- "${lists[@]}" 2>/dev/null | sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
  | grep -vE '^(#|$)' >"$patterns" || true
[ -s "$patterns" ] || cannot "${lists[*]} holds no patterns; an empty list would pass everything"
grep -E -i -q -f "$patterns" </dev/null 2>/dev/null
[ $? -eq 2 ] && cannot "${lists[*]} holds an invalid extended regex: $(grep -E -i -f "$patterns" </dev/null 2>&1 | head -1)"

hits=0
# report <where> <file> — FAIL lines for every match in one scanned section.
report() {
  local found matched
  found="$(grep -a -E -i -f "$patterns" "$2")"
  [ -n "$found" ] || return 0
  hits=$((hits + 1))
  matched="$(grep -a -E -i -o -f "$patterns" "$2" | sort -u | paste -sd ' ' -)"
  say "FAIL — $1 would publish a private name (matched: $matched):"
  printf '%s\n' "$found" | cut -c1-240 | sed 's/^/    /' >&2
}

# Every commit the push would publish, and the ref-level text it carries.
: >"$work/refs"
commits=""
exclude=(--not --remotes)
[ -n "$remote" ] && exclude=(--not "--remotes=$remote")
[ -n "$remote" ] && ! git remote | grep -qxF "$remote" && exclude=()
if [ ${#range[@]} -gt 0 ]; then
  commits="$(git rev-list "${range[@]}" 2>&1)" || cannot "git rev-list ${range[*]}: $commits"
else
  while read -r lref lsha rref rsha; do
    [ -n "${lsha:-}" ] || continue
    [ "$lsha" = "$zero" ] && continue            # a deletion publishes nothing
    printf '%s\n%s\n' "$lref" "$rref" >>"$work/refs"
    if [ "$(git cat-file -t "$lsha" 2>/dev/null)" = tag ]; then
      git cat-file -p "$lsha" >>"$work/refs" 2>"$work/err" || cannot "cannot read tag $lsha: $(cat "$work/err")"
    fi
    if [ "$rsha" != "$zero" ] && git cat-file -e "$rsha^{commit}" 2>/dev/null; then
      new="$(git rev-list "$rsha..$lsha" 2>&1)" || cannot "git rev-list $rsha..$lsha: $new"
    else
      new="$(git rev-list "$lsha" ${exclude[@]+"${exclude[@]}"} 2>&1)" || cannot "git rev-list $lsha: $new"
    fi
    commits="$commits${commits:+$'\n'}$new"
  done
  report "a pushed ref name or tag" "$work/refs"
fi

n=0
while read -r c; do
  [ -n "$c" ] || continue
  n=$((n + 1))
  short="$(git rev-parse --short "$c" 2>/dev/null || printf '%s' "$c")"
  git show -s --format='%an <%ae>%n%cn <%ce>' "$c" >"$work/identity" 2>"$work/err" \
    && git show -s --format='%B' "$c" >"$work/message" 2>>"$work/err" \
    && git diff-tree -r -m --root --no-commit-id --name-only --no-renames "$c" >"$work/paths" 2>>"$work/err" \
    && git show --format= -m -U0 --no-color --no-ext-diff --no-textconv --text --no-renames "$c" >"$work/diff" 2>>"$work/err" \
    || cannot "git could not read commit $short: $(head -3 "$work/err")"
  grep -a '^+' "$work/diff" >"$work/added" || true
  report "commit $short's identity" "$work/identity"
  report "commit $short's message" "$work/message"
  report "commit $short's paths" "$work/paths"
  report "commit $short's added lines" "$work/added"
done <<<"$commits"

if [ "$hits" -gt 0 ]; then
  say "blocked. Remove the name (amend or rewrite the unpushed commits, rename the ref) and push again."
  exit 1
fi
[ "$n" -gt 0 ] || { say "clean — no new commits; ref names scanned"; exit 0; }
say "clean — $n commit(s) scanned against $(wc -l <"$patterns") pattern(s) from ${#lists[@]} list(s)"
exit 0
