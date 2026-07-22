#!/usr/bin/env bash
# ci-watch.sh — Monitor-tool observer for this repo's GitHub Actions runs on the
# trunk, so a red battery surfaces as an event instead of at the next manual
# check (or not at all).
#
# Arm it (lifetime-of-session) via the Monitor tool:
#   Monitor(command="tools/claude/monitors/ci-watch.sh", persistent=true)
# See `.claude/rules/monitor-discipline.md`.
#
# NO TOKEN REQUIRED: jettison_protogen is public, and the Actions REST API
# serves run/job status for a public repo unauthenticated. That also caps us at
# 60 requests/hour per IP, and — verified against the live API — an
# unauthenticated conditional (ETag / If-None-Match) request STILL costs a
# request, so 304-polling buys nothing here. Hence the deliberately slow
# cadence below, and the rate-limit backoff. Set GH_TOKEN to lift the ceiling
# to 5000/hour if you ever want a tighter loop.
#
# Emits an arm-time snapshot, then ONE event per transition of the newest
# trunk commit's run set. Covers EVERY terminal conclusion (success, failure,
# cancelled, timed_out, …) — a filter that only matched success would stay
# silent through a red run, and silence must never look like green.
#
# Tunables (env): OWNER_REPO, BRANCH (default master),
# POLL_ACTIVE_S (default 120), POLL_IDLE_S (default 300), GH_TOKEN (optional).
set -uo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || echo "${CLAUDE_PROJECT_DIR:-$PWD}")"
cd "$repo_root"

OWNER_REPO="${OWNER_REPO:-lpportorino/jettison_protogen}"
BRANCH="${BRANCH:-master}"
POLL_ACTIVE_S="${POLL_ACTIVE_S:-120}"
POLL_IDLE_S="${POLL_IDLE_S:-300}"
API="https://api.github.com/repos/${OWNER_REPO}/actions/runs?branch=${BRANCH}&per_page=15"

mkdir -p "$repo_root/.protogen"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$repo_root/.protogen/ci-watch.lock"
  if ! flock -n 9; then
    echo "[ci-watch] already armed for this checkout (lock held) — second arm is a no-op"
    exit 0
  fi
else
  # See git-behind.sh: never let a missing flock invert into an immediate exit.
  echo "[ci-watch] flock unavailable (no kernel-lock self-dedup) — polling without it"
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "[ci-watch] python3 unavailable — cannot parse the Actions API; not polling"
  exit 0
fi

auth=()
[ -n "${GH_TOKEN:-}" ] && auth=(-H "Authorization: Bearer ${GH_TOKEN}")

# Emit "<sha7>\t<name>\t<state>\t<url>" per run of the NEWEST head_sha, sorted.
snapshot() {
  curl -sS --max-time 25 "${auth[@]}" -H 'Accept: application/vnd.github+json' "$API" 2>/dev/null \
    | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
runs = d.get("workflow_runs") or []
if not runs:
    sys.exit(0)
head = runs[0].get("head_sha", "")
rows = []
for r in runs:
    if r.get("head_sha") != head:
        continue
    state = r.get("conclusion") or r.get("status") or "unknown"
    rows.append("\t".join([head[:7], r.get("name", "?"), state, r.get("html_url", "")]))
print("\n".join(sorted(rows)))
' 2>/dev/null
}

prev=""
armed=0
while :; do
  cur="$(snapshot)" || cur=""

  if [ -n "$cur" ] && [ "$cur" != "$prev" ]; then
    sha="$(printf '%s\n' "$cur" | head -n 1 | cut -f1)"
    if [ "$armed" -eq 0 ]; then
      printf '[ci-watch] armed; %s @ %s — current run state:\n' "$BRANCH" "$sha"
      armed=1
    else
      printf '[ci-watch] %s @ %s — run state changed:\n' "$BRANCH" "$sha"
    fi
    # Print each run; attach the URL for anything that is not a clean success.
    printf '%s\n' "$cur" | while IFS=$'\t' read -r _sha name state url; do
      printf '  [%s] %s\n' "$state" "$name"
      case "$state" in
        success | queued | in_progress | requested | waiting | pending) : ;;
        *) [ -n "$url" ] && printf '      -> %s\n' "$url" ;;
      esac
    done
    prev="$cur"
  fi

  # Active while anything is still non-terminal; otherwise idle cadence.
  if printf '%s\n' "$cur" | grep -qP '\t(queued|in_progress|requested|waiting|pending)\t' 2>/dev/null ||
    printf '%s\n' "$cur" | grep -q -e '	queued	' -e '	in_progress	' -e '	requested	' -e '	waiting	' -e '	pending	'; then
    sleep "$POLL_ACTIVE_S"
  else
    sleep "$POLL_IDLE_S"
  fi
done
