#!/usr/bin/env bash
# monitor-nudge.sh <session-start|user-prompt>
#
# The enforcement half of the monitor pattern (`.claude/rules/monitor-discipline.md`).
# A hook CANNOT invoke the Monitor tool — only the agent can — so arming is an
# agent obligation that these two events carry mechanically:
#
#   session-start : print the trunk sync state + an explicit "arm these NOW"
#                   instruction naming the exact Monitor(...) recipes.
#   user-prompt   : VIOLATION-ONLY — one [WARN] line per applicable monitor that
#                   is not live. Silent once all applicable monitors are armed.
#
# Liveness is a `flock` LOCK-PROBE of each monitor's self-dedup lock, NOT the
# harness task API: TaskList/TaskGet do not surface armed Monitor tasks, and a
# pgrep+cwd heuristic false-negatives on a live monitor. Probing the lock the
# monitor itself holds is the one signal that cannot drift.
#
# FAIL-OPEN by construction: every failure path exits 0 with no output. A broken
# nudge must never block a session.
set -uo pipefail

EVENT="${1:-}"

root="${CLAUDE_PROJECT_DIR:-}"
[ -n "$root" ] || root="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
cd "$root" 2>/dev/null || exit 0

hook_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
targets="$hook_dir/../../tools/claude/monitors/targets.sh"
[ -r "$targets" ] && source "$targets" 2>/dev/null || exit 0

# name|recipe|why — the session-monitor tier
MONITORS=(
  "git-behind|Monitor(command=\"tools/claude/monitors/git-behind.sh\", persistent=true)|trunk-only + a pinned consumer fleet: it catches someone else's push before your next push rejects, and before a rebase silently inverts a doc claim"
  "ci-watch|Monitor(command=\"tools/claude/monitors/ci-watch.sh\", persistent=true)|the battery is the gate: it catches a red run at push time instead of at the next manual check"
)

# owed <name> -> 0 when this checkout has a target the monitor can observe.
# These are the monitor scripts' own preconditions, sourced from targets.sh.
owed() {
  case "$1" in
  ci-watch) ci_watch_resolve_target ;;
  git-behind) git_behind_remote_is_configured "${REMOTE:-origin}" ;;
  *) return 0 ;;
  esac
}

# armed <name> -> 0 when a live monitor holds the lock
armed() {
  local lock="$root/.protogen/$1.lock"
  [ -e "$lock" ] || return 1
  command -v flock >/dev/null 2>&1 || return 0 # cannot probe: assume armed, stay quiet
  if flock -n "$lock" -c true 2>/dev/null; then
    return 1 # acquired => nobody holds it => NOT armed
  fi
  return 0 # could not acquire => held => armed
}

emit() { # emit <hookEventName> <text>
  command -v python3 >/dev/null 2>&1 || exit 0
  printf '%s' "$2" | python3 -c '
import json, sys
name = sys.argv[1]
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": name,
    "additionalContext": sys.stdin.read(),
}}))
' "$1" 2>/dev/null || true
}

missing=()
for m in "${MONITORS[@]}"; do
  n="${m%%|*}"
  owed "$n" >/dev/null || continue
  armed "$n" || missing+=("$m")
done

case "$EVENT" in
session-start)
  # Freshen origin in the background — never block session start on the network.
  (nohup git fetch --quiet origin master >/dev/null 2>&1 &) >/dev/null 2>&1 || true

  body="protogen session-start @ $root"
  if git rev-parse --verify --quiet origin/master >/dev/null 2>&1; then
    behind="$(git rev-list --count HEAD..origin/master 2>/dev/null || echo 0)"
    ahead="$(git rev-list --count origin/master..HEAD 2>/dev/null || echo 0)"
    if [ "$behind" = "0" ] && [ "$ahead" = "0" ]; then
      body="$body
git: in sync with origin/master (as of last fetch; arm git-behind for live detail)"
    else
      body="$body
[INFO] git: $behind behind / $ahead ahead of origin/master — rebase BEFORE pushing:
       git pull --rebase origin master"
    fi
  fi

  # Report the pre-push gate's state rather than arming it: core.hooksPath is a
  # mutation of the operator's own git config, and silently arming a gate is the
  # kind of surprise a session-start banner should surface, not perform.
  if [ "$(git config --get core.hooksPath 2>/dev/null)" = ".githooks" ]; then
    body="$body
hooks: pre-push format/lint gate ARMED (.githooks)"
  else
    body="$body
[INFO] hooks: pre-push format/lint gate NOT armed — \`make -f lint.mk install-hooks\`"
  fi

  body="$body

[INFO] ARM EACH APPLICABLE MONITOR NOW — turn one, every session:"
  for m in "${MONITORS[@]}"; do
    n="${m%%|*}"
    rest="${m#*|}"
    recipe="${rest%%|*}"
    why="${rest#*|}"
    if owed "$n" >/dev/null; then
      state="NOT armed"
      armed "$n" && state="already armed — do NOT re-arm"
    else
      case "$n" in
      ci-watch) state="not owed here — $CI_WATCH_REFUSAL_REASON" ;;
      git-behind) state="not owed here — $GIT_BEHIND_REFUSAL_REASON" ;;
      esac
    fi
    body="$body
  $recipe
      ($n: $state) — $why"
  done
  body="$body

Re-arming an applicable monitor after a /compact is a harmless no-op (each
script flock-self-dedups), but a SESSION RESTART kills them for real — re-arm
then. TaskList does not show armed Monitors; the per-prompt [WARN] is your
liveness signal.
See .claude/rules/monitor-discipline.md"
  emit SessionStart "$body"
  ;;

user-prompt)
  [ "${#missing[@]}" -gt 0 ] || exit 0 # violation-only: silent when all armed
  body=""
  for m in "${missing[@]}"; do
    n="${m%%|*}"
    rest="${m#*|}"
    recipe="${rest%%|*}"
    why="${rest#*|}"
    body="$body[WARN] $n monitor NOT armed — arm it now: $recipe — $why
"
  done
  emit UserPromptSubmit "$body"
  ;;
esac

exit 0
