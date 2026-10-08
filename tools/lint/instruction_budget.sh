#!/usr/bin/env bash
# instruction_budget.sh — the ALWAYS-LOADED instruction set must fit the harness.
#
# WHAT IS GUARDED. Claude Code loads CLAUDE.md and every `.claude/rules/*.md`
# WITHOUT a `paths:` key in its frontmatter into EVERY session, and refuses the
# set once it exceeds 150,000 characters ("N instruction files add up to … over
# the 150.0k-char total limit"). Past that line the repo's law is partly or
# wholly unloaded and nothing in a session says which part. A path-scoped rule
# loads only when a matching file is read, so it is outside the budget — which
# is also the remedy: SPLIT a section that only matters while editing certain
# files into a `paths:`-scoped rule, or TRIM chronicle (the deletion test in
# .claude/rules/claude-md-policy.md). Never raise the budget.
#
# HOW IT COUNTS. The harness measures JavaScript string length, i.e. UTF-16 code
# units, so this gate counts the same quantity (`iconv … UTF-16LE | wc -c` / 2)
# rather than bytes or code points — an em dash is one unit, a byte count of the
# same file is three.
#
# BUDGET: 149,000 — the harness's 150,000 hard limit less 1,000 of headroom so a
# one-paragraph edit cannot land exactly on the line. Measured provenance for
# the seed: the set stood at 151,394 the day this gate was written, which is the
# red that motivated it. A WATCH tier at 145,000 is reported every run and never
# blocks (.claude/rules/gate-enforcement.md §1).
#
# EXIT CODES. 0 within budget; 1 FAIL (over budget — a verdict); 3 CANNOT RUN
# (no CLAUDE.md, no rule discovered, an unreadable file, or no iconv — a
# precondition, never a verdict). `--canary` proves each clause can fail for its
# own reason on synthetic fixtures. `--root DIR` points discovery elsewhere.
set -euo pipefail

BUDGET=149000
WATCH=145000
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE=check

while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="${2:?--root needs a value}"; shift 2 ;;
    --canary) MODE=canary; shift ;;
    *) printf 'instruction-budget: unknown argument %s\n' "$1" >&2; exit 2 ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
cannot_run() { red "[instruction-budget] CANNOT RUN — $*" >&2; exit 3; }

# A rule is path-scoped iff a `paths:` key sits INSIDE its YAML frontmatter
# (a leading `---` line through the next `---` line). A `paths:` mention in the
# body is prose and does not scope anything.
scoped() {
  awk 'NR==1 { if ($0 != "---") exit 1; next }
       /^---[[:space:]]*$/ { exit 1 }
       /^paths:/ { found = 1; exit 0 }
       END { exit found ? 0 : 1 }' "$1"
}

utf16_units() {
  local bytes
  bytes="$(iconv -f UTF-8 -t UTF-16LE "$1" | wc -c)" || return 1
  printf '%d' $(( bytes / 2 ))
}

check() {
  local root="$1" total=0 n=0 f units
  command -v iconv >/dev/null 2>&1 || cannot_run "iconv is not on PATH (glibc/libiconv ships it)"
  [ -r "$root/CLAUDE.md" ] || cannot_run "$root/CLAUDE.md is missing or unreadable — the budget starts there"
  local files=("$root/CLAUDE.md")
  local rules=0
  for f in "$root"/.claude/rules/*.md; do
    [ -e "$f" ] || continue
    rules=$((rules + 1))
    [ -r "$f" ] || cannot_run "$f is unreadable"
    scoped "$f" || files+=("$f")
  done
  [ "$rules" -gt 0 ] || cannot_run "no rule discovered under $root/.claude/rules — discovery broke, this repo tracks rules"
  for f in "${files[@]}"; do
    units="$(utf16_units "$f")" || cannot_run "cannot count $f"
    total=$((total + units)); n=$((n + 1))
    printf '  %7d  %s\n' "$units" "${f#"$root"/}"
  done
  printf '  %7d  total over %d always-loaded file(s); budget %d; headroom %d\n' "$total" "$n" "$BUDGET" $((BUDGET - total))
  if [ "$total" -gt "$BUDGET" ]; then
    red "[instruction-budget] FAIL over-budget — $total UTF-16 units across $n always-loaded files exceeds $BUDGET."
    printf '  The harness refuses the set above 150,000. Do not raise the budget: move a\n' >&2
    printf '  section that only matters while editing certain files into a `paths:`-scoped\n' >&2
    printf '  rule (.claude/rules/claude-md-policy.md §"When to use which tier"), or cut\n' >&2
    printf '  chronicle by the deletion test. Largest files are listed above.\n' >&2
    return 1
  fi
  if [ "$total" -gt "$WATCH" ]; then
    printf '\033[33m[instruction-budget]\033[0m WATCH — %d is within %d of the budget; the next rule edit should split, not grow\n' "$total" $((BUDGET - total))
  fi
  green "[instruction-budget] OK — $total UTF-16 units across $n always-loaded files (budget $BUDGET)"
}

canary() {
  local pass=0 fail=0 work
  work="$(mktemp -d)"
  # Expanded now: the EXIT trap runs after this function's locals are gone.
  trap "rm -rf '$work'" EXIT
  ok()  { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass + 1)); }
  bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2; fail=$((fail + 1)); }
  expect() { # expect <code> <label> <root>
    local code rc=0
    "${BASH_SOURCE[0]}" --root "$3" >/dev/null 2>&1 || rc=$?
    if [ "$rc" -eq "$1" ]; then ok "$2 (exit $1)"; else bad "$2 — expected exit $1, got $rc"; fi
  }
  fixture() { # fixture <name> <claude-units> <rule-units> [scoped|body-mention|none]
    local d="$work/$1"; mkdir -p "$d/.claude/rules"
    head -c "$2" /dev/zero | tr '\0' 'a' >"$d/CLAUDE.md"
    {
      case "${4:-none}" in
        scoped) printf -- '---\ndescription: big\npaths:\n  - "x/**"\n---\n' ;;
        body-mention) printf -- '---\ndescription: big\n---\nThe word paths: here is prose, not frontmatter.\n' ;;
        none) printf -- '---\ndescription: big\n---\n' ;;
      esac
      head -c "$3" /dev/zero | tr '\0' 'b'
    } >"$d/.claude/rules/big.md"
    printf '%s' "$d"
  }
  echo "== over budget is a FAIL (exit 1), under is a pass"
  expect 1 "CLAUDE.md + one unscoped rule over $BUDGET is a FINDING" "$(fixture over 100000 60000)"
  expect 0 "the same pair under budget passes" "$(fixture under 100000 40000)"
  echo "== the boundary: exactly AT budget passes, one unit over fails (seeding depends on >)"
  local header; header="$(printf -- '---\ndescription: big\n---\n' | wc -c)"
  expect 0 "total == budget passes" "$(fixture at 100000 $((BUDGET - 100000 - header)))"
  expect 1 "total == budget + 1 fails" "$(fixture at1 100000 $((BUDGET - 100000 - header + 1)))"
  echo "== only FRONTMATTER paths: scopes a rule out of the budget"
  expect 0 "CONTROL: the over-budget rule with paths: in its frontmatter is excluded" "$(fixture scoped 100000 60000 scoped)"
  expect 1 "a paths: mention in the BODY does not exclude it" "$(fixture bodymention 100000 60000 body-mention)"
  echo "== multi-byte text is counted in UTF-16 units, not bytes"
  local d="$work/emdash"; mkdir -p "$d/.claude/rules"; printf -- '---\ndescription: d\n---\n' >"$d/.claude/rules/r.md"
  # 50,000 em dashes are 150,000 UTF-8 bytes but 50,000 units: a byte count would FAIL this
  python3 -c 'import sys; sys.stdout.write("—" * 50000)' >"$d/CLAUDE.md" 2>/dev/null || perl -CS -e 'print "\x{2014}" x 50000' >"$d/CLAUDE.md"
  expect 0 "50,000 em dashes (150,000 bytes) are 50,000 units and pass" "$d"
  echo "== empty discovery is CANNOT RUN (exit 3), never a pass"
  d="$work/noclaude"; mkdir -p "$d/.claude/rules"; printf -- '---\n---\n' >"$d/.claude/rules/r.md"
  expect 3 "missing CLAUDE.md" "$d"
  d="$work/norules"; mkdir -p "$d/.claude/rules"; printf 'x' >"$d/CLAUDE.md"
  expect 3 "zero rules discovered" "$d"
  echo "== the live tree"
  expect 0 "this checkout is within budget" "$ROOT"
  echo
  if [ "$fail" -eq 0 ]; then green "[instruction-budget canary] ALL GREEN — $pass assertion(s)"; return 0; fi
  red "[instruction-budget canary] $fail assertion(s) FAILED, $pass passed"; return 1
}

case "$MODE" in
  check) check "$ROOT" ;;
  canary) canary ;;
esac
