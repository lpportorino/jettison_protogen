#!/usr/bin/env bash
# campaign_test.sh — the canary for tools/mutation/campaign.py, on a synthetic repo.
#
# A tiny repository holds one function and a suite that prints a passing control
# case and fails a named case when the function is wrong. One fault per outcome:
# a real fault (KILLED), a fault in a comment (SURVIVED), an anchor that is not
# there, a replacement equal to its anchor, a red naming the wrong case, and a
# hung suite (all INVALID). Each outcome is asserted, then the exit-code
# precedence (0 all killed, 1 a survivor, 2 untrusted evidence over a survivor)
# and every CANNOT RUN (3): outside a checkout, a dirty tree, a failing prepare,
# a faults file without suites. Needs bash, git and python3.
#
# Usage: bash tools/mutation/campaign_test.sh
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CAMPAIGN="$here/campaign.py"
command -v python3 >/dev/null 2>&1 || { echo 'CANNOT RUN — python3 is not on PATH' >&2; exit 3; }
work="$(mktemp -d)"; trap 'rm -rf -- "$work"' EXIT
PASS=0 FAILED=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; }
g() { GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"; }

repo="$work/repo"; mkdir -p "$repo"
cat >"$repo/src.sh" <<'SRC'
# comment-x
answer() { echo 42; }
SRC
cat >"$repo/suite.sh" <<'SUITE'
. ./src.sh
echo "ok control-case"
[ "$(answer)" = 42 ] && echo "ok answer-case" || { echo "FAIL answer-case"; exit 1; }
SUITE
printf 'sleep 30\n' >"$repo/slow.sh"
g init -q; g config user.email t@example.invalid; g config user.name t; g add -A; g commit -qm base
cat >"$work/faults.json" <<'JSON'
{"scope": "canary",
 "suites": {"s": {"command": "bash suite.sh", "timeout": 60},
            "slow": {"command": "bash slow.sh", "timeout": 1},
            "badprep": {"command": "bash suite.sh", "timeout": 60, "prepare": "false"}},
 "faults": [
  {"id": "real", "source": "src.sh", "anchor": "echo 42", "replacement": "echo 41", "suite": "s",
   "kill": "FAIL answer-case", "control": "ok control-case"},
  {"id": "comment", "source": "src.sh", "anchor": "# comment-x", "replacement": "# comment-y", "suite": "s",
   "kill": "FAIL answer-case", "control": "ok control-case"},
  {"id": "absent", "source": "src.sh", "anchor": "echo 99", "replacement": "echo 98", "suite": "s",
   "kill": "FAIL answer-case", "control": "ok control-case"},
  {"id": "noop", "source": "src.sh", "anchor": "echo 42", "replacement": "echo 42", "suite": "s",
   "kill": "FAIL answer-case", "control": "ok control-case"},
  {"id": "wrongname", "source": "src.sh", "anchor": "echo 42", "replacement": "echo 41", "suite": "s",
   "kill": "FAIL other-case", "control": "ok control-case"},
  {"id": "hang", "source": "src.sh", "anchor": "echo 42", "replacement": "echo 41", "suite": "slow",
   "kill": "FAIL answer-case", "control": "ok control-case"},
  {"id": "prep", "source": "src.sh", "anchor": "echo 42", "replacement": "echo 41", "suite": "badprep",
   "kill": "FAIL answer-case", "control": "ok control-case"}]}
JSON
n=0
# camp <want-exit> <label> <campaign args...> — run inside the repo; sets $OUT to the run dir.
camp() {
  local want="$1" label="$2" code; shift 2; n=$((n + 1)); OUT="$work/out$n"
  (cd "$repo" && python3 "$CAMPAIGN" "$work/faults.json" "$OUT" "$@" >"$work/log$n" 2>&1) && code=0 || code=$?
  if [ "$code" = "$want" ]; then ok "$label (exit $want)"; else bad "$label — expected exit $want, got $code"; sed 's/^/       | /' "$work/log$n" >&2; fi
}
outcome() { python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print({x["fault"]["id"]: x["outcome"] for x in r["results"]}[sys.argv[2]])' "$1/report.json" "$2"; }
expect_outcome() { [ "$(outcome "$OUT" "$1")" = "$2" ] && ok "$1 is $2" || bad "$1 is $(outcome "$OUT" "$1"), not $2"; }

printf '\n== outcomes\n'
camp 0 'a real fault, killed with its control' --only real; expect_outcome real killed
camp 1 'a fault the suite cannot see survives' --only real --only comment; expect_outcome comment survived
camp 2 'an anchor that is not there is invalid' --only absent; expect_outcome absent invalid
camp 2 'a replacement equal to its anchor is invalid' --only noop; expect_outcome noop invalid
camp 2 'a red naming the wrong case is invalid' --only wrongname; expect_outcome wrongname invalid
camp 2 'a hung suite is invalid' --only hang; expect_outcome hang invalid
camp 2 'untrusted evidence outranks a survivor' --only comment --only absent
grep -q '"passed": true' "$work/out1/report.json" && ok 'the all-killed report says passed' || bad 'the all-killed report does not say passed'

printf '\n== CANNOT RUN (3)\n'
camp 3 'a failing prepare' --only prep
printf 'dirt\n' >>"$repo/src.sh"; camp 3 'a dirty tree' --only real; g checkout -q -- src.sh
printf '{"faults": []}\n' >"$work/nosuites.json"
out="$( (cd "$repo" && python3 "$CAMPAIGN" "$work/nosuites.json" "$work/outx") 2>&1)" && code=0 || code=$?
[ "$code" = 3 ] && ok "a faults file without faults or suites (exit 3)" || bad "a faults file without suites — exit $code"
out="$( (cd "$work" && python3 "$CAMPAIGN" "$work/faults.json" "$work/outy" --only real) 2>&1)" && code=0 || code=$?
[ "$code" = 3 ] && ok "outside a checkout (exit 3)" || bad "outside a checkout — exit $code"

printf '\n%s passed, %s failed\n' "$PASS" "$FAILED"
[ "$PASS" -gt 0 ] || { echo 'CANNOT RUN — no case executed' >&2; exit 3; }
[ "$FAILED" -eq 0 ]
