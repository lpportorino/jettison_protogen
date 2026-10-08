#!/usr/bin/env bash
# Canary for tools/ci/annotated-shell.sh. Hermetic: every case runs in a tempdir.
#
# Exit: 0 all green; 1 a case FAILED; 3 CANNOT RUN (the subject is missing).
# Each clause is broken alone in a scratch copy and must take its own case red
# while a neighbour stays green — a red that came from another clause proves
# nothing (.claude/rules/gate-enforcement.md §2).
set -uo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
subject="$root/tools/ci/annotated-shell.sh"
[ -r "$subject" ] || { printf '[annotated-shell] CANNOT RUN — %s is missing\n' "$subject" >&2; exit 3; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pass=0 fail=0
ok()  { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2; fail=$((fail + 1)); }

# run <wrapper> <script-body> -> sets out, rc
run() {
  printf '%s\n' "$2" >"$work/step.sh"
  out=$(GITHUB_JOB=canary bash "$1" "$work/step.sh" 2>&1); rc=$?
}
annotation() { printf '%s\n' "$out" | grep '^::error ' || true; }

cases() { # cases <wrapper> -> runs every case; prints ok/FAIL; returns nothing
  local w="$1"
  run "$w" 'echo fine'
  [ "$rc" -eq 0 ] && [ -z "$(annotation)" ] && ok "a passing step exits 0 and annotates nothing" \
    || bad "passing step: rc=$rc annotation=[$(annotation)]"

  run "$w" $'echo preparing\necho "FAIL clause-seven: the thing broke" >&2\nexit 3'
  [ "$rc" -eq 3 ] && ok "the step's own exit status survives (3)" || bad "exit status: expected 3, got $rc"
  annotation | grep -q 'title=canary: step failed with exit 3::' \
    && ok "the annotation's title names the job and the exit status" || bad "title: [$(annotation)]"
  annotation | grep -q 'FAIL clause-seven: the thing broke' \
    && ok "the annotation carries the line that NAMES the failure, from stderr" || bad "failing line missing: [$(annotation)]"

  run "$w" $'false | true\necho reached'
  [ "$rc" -ne 0 ] && ! printf '%s' "$out" | grep -q '^reached$' \
    && ok "pipefail: a failure on the LEFT of a pipe stops the step (runner default semantics)" \
    || bad "pipefail lost: rc=$rc"

  run "$w" $'false\necho reached'
  [ "$rc" -eq 1 ] && ! printf '%s' "$out" | grep -q '^reached$' \
    && ok "errexit: a failing command stops the step (runner default semantics)" || bad "errexit lost: rc=$rc"

  run "$w" $'for i in $(seq 1 400); do echo "  killed mutant-$i: Test.case FAILED; control passed"; done\necho "VERDICT-LINE: 3 mutants survived"\nexit 1'
  annotation | grep -q 'VERDICT-LINE: 3 mutants survived' \
    && ok "the tail survives a flood of failure-looking success lines (the verdict is last)" \
    || bad "tail lost under noise: [$(annotation | cut -c1-300)]"
  run "$w" $'printf "100%% done\\r\\n"\necho "Error: x"\nexit 1'
  annotation | grep -q '100%25 done%0D' && ! annotation | grep -q '100% done' \
    && ok "workflow-command escaping: % and CR are encoded, so the line cannot end or forge the command" \
    || bad "escaping: [$(annotation)]"

  run "$w" $'printf "\\033[31mERROR coloured\\033[0m\\n"\nexit 1'
  annotation | grep -q 'ERROR coloured' && ! annotation | grep -q $'\033' \
    && ok "ANSI colour is stripped from the annotation" || bad "ansi: [$(annotation)]"
}

echo "== the shipped wrapper"
cases "$subject"
shipped_fail=$fail

# mutate <name> <python-replace-from> <to>: build a mutant and require the
# mutation landed (old text gone, new text present) before believing any colour.
mutate() {
  local m="$work/mutant-$1.sh"
  python3 - "$subject" "$m" "$2" "$3" <<'PY' || return 1
import sys
src, dst, a, b = sys.argv[1:]
s = open(src).read()
assert s.count(a) == 1, f"anchor not unique/absent: {a!r}"
t = s.replace(a, b)
assert a not in t and (b == "" or b in t)
open(dst, "w").write(t)
PY
  printf '%s' "$m"
}
attribute() { # attribute <label> <mutant> <case-regex-that-must-FAIL> <case-regex-that-must-stay-ok>
  local before=$fail res
  res=$( { cases "$2"; } 2>&1 | sed -e 's/\x1b\[[0-9;]*m//g' )
  if printf '%s\n' "$res" | grep -q "FAIL.*$3" && printf '%s\n' "$res" | grep -q "ok .*$4"; then
    fail=$before; ok "MUTANT $1: its own case goes red, the neighbour stays green"
  else
    fail=$before; bad "MUTANT $1 not attributed:"; printf '%s\n' "$res" | sed 's/^/       | /' >&2
  fi
}
echo "== attribution: break each clause alone"
m=$(mutate pipefail 'bash --noprofile --norc -eo pipefail "$script"' 'bash --noprofile --norc -e "$script"') \
  && attribute "pipefail dropped" "$m" "pipefail lost" "errexit:" || bad "mutation pipefail did not land"
m=$(mutate status 'exit "$rc"' 'exit 1') \
  && attribute "status flattened" "$m" "exit status: expected 3" "the annotation carries" || bad "mutation status did not land"
m=$(mutate escape "s=\"\${s//\$'\\r'/'%0D'}\"" 's="$s"') \
  && attribute "CR escape removed" "$m" "escaping:" "ANSI colour" || bad "mutation escape did not land"
m=$(mutate ansi "s/\\x1b\\[[0-9;]*[A-Za-z]//g" "s/NEVERMATCHES//g") \
  && attribute "ANSI strip removed" "$m" "ansi:" "workflow-command escaping" || bad "mutation ansi did not land"

m=$(mutate tail 'tail_part="$(printf '"'"'%s\n'"'"' "$clean" | tail -n 20)"' 'tail_part=""') \
  && attribute "tail dropped" "$m" "tail lost under noise" "pipefail:" || bad "mutation tail did not land"

echo
if [ "$fail" -eq 0 ] && [ "$shipped_fail" -eq 0 ]; then
  printf '\033[32m[annotated-shell canary] ALL GREEN — %d assertion(s)\033[0m\n' "$pass"; exit 0
fi
printf '\033[31m[annotated-shell canary] %d FAILED, %d passed\033[0m\n' "$fail" "$pass" >&2; exit 1
