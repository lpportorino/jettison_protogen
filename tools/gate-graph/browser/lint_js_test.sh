#!/usr/bin/env bash
# lint_js_test.sh — canaries for lint_js.sh, run through the real lane: the
# pinned Playwright image, the pinned ESLint, this repo's eslint.config.mjs.
#
# Planted files sit in a scratch directory under the browser tree (removed on
# exit), because the ESLint imports resolve from there; one more file sits OUTSIDE
# the harness tree, to prove the rules reach every file passed. Three config
# clauses — the recommended rules, the browser globals, the `files` scope — and
# the lane's `--max-warnings 0` are then broken ALONE in a copy, each changing
# its own case's verdict while a neighbouring case keeps its own. Exit
# codes separate a verdict (1) from a precondition (3), and every case asserts
# both the code and the message.
#
# Usage: bash tools/gate-graph/browser/lint_js_test.sh   (HOST-ONLY: docker)
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
root="$(env -u GIT_DIR -u GIT_WORK_TREE git -C "$here" rev-parse --show-toplevel)"
LANE="$here/lint_js.sh"
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$root/tools/lint/test/lib_mutate.sh"
command -v docker >/dev/null 2>&1 || { printf 'CANNOT RUN — docker is not on PATH\n' >&2; exit 3; }

rel="tools/gate-graph/browser/lint-canary.tmp"
scratch="$root/$rel"
out_rel="tools/gate-graph/lint-canary-outside.tmp"
outside="$root/$out_rel"
rm -rf -- "$scratch" "$outside"; mkdir -p "$scratch" "$outside"
trap 'rm -rf -- "$scratch" "$outside"' EXIT
PASS=0 FAILED=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; }

# expect <code> <needle> <label> [env…] -- <files…>
expect() {
  local want="$1" needle="$2" label="$3" out code; shift 3
  local envs=(); while [ "$1" != -- ]; do envs+=("$1"); shift; done; shift
  out="$(env "${envs[@]}" bash "$LANE" "$@" 2>&1)" && code=0 || code=$?
  if [ "$code" != "$want" ]; then bad "$label — expected exit $want, got $code"; printf '%s\n' "$out" | tail -8 | sed 's/^/       | /' >&2; return; fi
  contains "$out" "$needle" || { bad "$label — exit $code but no \"$needle\""; printf '%s\n' "$out" | tail -8 | sed 's/^/       | /' >&2; return; }
  ok "$label"
}

printf 'undefinedThing();\n' >"$scratch/undef.mjs"
printf 'const unused = 1;\nexport const used = 2;\n' >"$scratch/unused.mjs"
printf 'export const both = [document.title, process.pid];\n' >"$scratch/globals.mjs"
printf 'undefinedElsewhere();\n' >"$outside/far.mjs"
printf '// eslint-disable-next-line no-undef\nexport const fine = 1;\n' >"$scratch/stale-disable.mjs"

printf '\n== VERDICTS (FAIL = 1, naming the rule)\n'
expect 1 'no-undef' 'an undefined name fails' -- "$rel/undef.mjs"
expect 1 'no-unused-vars' 'an unused binding fails' -- "$rel/unused.mjs"
expect 0 'clean — 1 file' 'CONTROL: browser and Node globals are both defined' -- "$rel/globals.mjs"
expect 1 'no-undef' 'a file OUTSIDE the harness tree is judged by the same rules' -- "$out_rel/far.mjs"
expect 1 'Unused eslint-disable directive' 'a disable directive that suppresses nothing fails' -- "$rel/stale-disable.mjs"

printf '\n== PRECONDITIONS (CANNOT RUN = 3)\n'
expect 3 'does not exist' 'a named file that is missing' -- "$rel/absent.mjs"
expect 3 'no JavaScript discovered' 'discovery that finds only the excluded bundle' \
  LINT_JS_PATHSPEC=tools/gate-graph/resources/gate/viewer/main.js --
expect 3 'matches no tracked file' 'a declared exclusion that matches nothing' LINT_JS_PATHSPEC='*.nothing' --

printf '\n== ATTRIBUTION — break ONE config clause; its case passes, a neighbour still fails\n'
attribute() { # attribute <label> <old> <new> <file-that-must-pass> <file-that-must-still-fail> <needle>
  local m="$scratch/eslint.config.mjs" err
  cp "$here/eslint.config.mjs" "$m"
  err="$(mutate_file "$m" "$2" "$3" 2>&1)" || { bad "$1 — mutation did not land: $err"; return; }
  local green="$rel/$4" red="$rel/$5"
  case "$4" in */*) green="$4" ;; esac
  expect 0 'clean' "$1: broken, its own case passes" "LINT_JS_CONFIG=$rel/eslint.config.mjs" -- "$green"
  expect 1 "$6" "$1: CONTROL — a neighbouring rule still fails" "LINT_JS_CONFIG=$rel/eslint.config.mjs" -- "$red"
}
attribute 'recommended rules' '    ...js.configs.recommended,' '    rules: { "no-unused-vars": "error" },' undef.mjs unused.mjs no-unused-vars
# The globals clause fails the OTHER way when broken: browser code stops resolving.
m="$scratch/eslint.config.mjs"; cp "$here/eslint.config.mjs" "$m"
if err="$(mutate_file "$m" '...globals.node, ...globals.browser' '...globals.node' 2>&1)"; then
  printf 'export const pid = process.pid;\n' >"$scratch/node-only.mjs"
  expect 1 'no-undef' 'browser globals: broken, page code using document fails' "LINT_JS_CONFIG=$rel/eslint.config.mjs" -- "$rel/globals.mjs"
  expect 0 'clean' 'browser globals: CONTROL — Node-only code still passes' "LINT_JS_CONFIG=$rel/eslint.config.mjs" -- "$rel/node-only.mjs"
else bad "browser globals — mutation did not land: $err"; fi

attribute 'files scope' "files: ['**/*.mjs', '**/*.js', '**/*.cjs']," "files: ['tools/gate-graph/browser/**/*.mjs']," "$out_rel/far.mjs" undef.mjs no-undef
# --max-warnings 0 lives in the lane, not the config: break it in a copy of the lane.
lane_copy="$scratch/lint_js.sh"; cp "$LANE" "$lane_copy"
if err="$(mutate_file "$lane_copy" ' --max-warnings 0 ' ' ' 2>&1)"; then
  out="$(bash "$lane_copy" "$rel/stale-disable.mjs" 2>&1)" && code=0 || code=$?
  if [ "$code" = 0 ]; then ok 'warnings-block clause: broken, a warning-only file passes'
  else bad "warnings-block clause: broken, still exit $code"; printf '%s\n' "$out" | tail -4 | sed 's/^/       | /' >&2; fi
  out="$(bash "$lane_copy" "$rel/undef.mjs" 2>&1)" && code=0 || code=$?
  if [ "$code" = 1 ] && contains "$out" 'no-undef'; then ok 'warnings-block clause: CONTROL — an error still fails'
  else bad "warnings-block clause: CONTROL — expected exit 1 naming no-undef, got $code"; fi
else bad "warnings-block mutation did not land: $err"; fi

printf '\n%s passed, %s failed\n' "$PASS" "$FAILED"
[ "$PASS" -gt 0 ] || { printf 'CANNOT RUN — no case executed\n' >&2; exit 3; }
[ "$FAILED" -eq 0 ]
