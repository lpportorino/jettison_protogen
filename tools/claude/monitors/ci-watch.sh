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
# cadence below. Set GH_TOKEN to lift the ceiling to 5000/hour.
#
# TRACKS EVERY RUN INDIVIDUALLY, BY RUN ID. The first version filtered to the
# runs sharing the NEWEST run's head_sha. Two things went wrong, both observed
# live rather than theorised:
#   - An in-flight run on a slightly older commit became INVISIBLE. A push that
#     touches one workflow's path filter creates runs for that commit while an
#     earlier commit's slower battery is still going; the newest-sha filter
#     dropped the battery — exactly the run worth watching — and the silence
#     read as "nothing is running".
#   - It labelled events "master @ <sha>" using the newest RUN's commit, which
#     is not the branch tip: a docs-only push triggers no workflow at all, so
#     the label kept naming a commit several pushes behind while implying the
#     tip was in that state.
# Now every run in the window is tracked by id, every line carries its own
# sha7, and nothing is hidden for belonging to an older commit.
#
# EMITS ONLY MEANINGFUL TRANSITIONS. The first version reprinted the whole run
# set whenever anything changed, so queued -> in_progress churn produced full,
# nearly identical events — three landed in one session carrying no new
# information. A monitor that repeats itself trains you to skim it. Now: one
# line when a run STARTS, one when it REACHES A CONCLUSION, nothing else.
#
# Covers EVERY terminal conclusion (success, failure, cancelled, timed_out,
# action_required, neutral, skipped, stale) — a filter matching only `failure`
# would stay silent on a timeout, and silence must never look like green.
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
API="https://api.github.com/repos/${OWNER_REPO}/actions/runs?branch=${BRANCH}&per_page=20"

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

# "<run_id>\t<sha7>\t<name>\t<state>\t<url>" for EVERY run in the window.
# `state` is the conclusion once finished, else the status.
snapshot() {
  curl -sS --max-time 25 "${auth[@]}" -H 'Accept: application/vnd.github+json' "$API" 2>/dev/null |
    python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for r in d.get("workflow_runs") or []:
    state = r.get("conclusion") or r.get("status") or "unknown"
    print("\t".join([
        str(r.get("id", "")),
        (r.get("head_sha") or "")[:7],
        r.get("name", "?"),
        state,
        r.get("html_url", ""),
    ]))
' 2>/dev/null
}

terminal() { # terminal <state> -> 0 when the run has finished
  case "$1" in
    queued | in_progress | requested | waiting | pending) return 1 ;;
    *) return 0 ;;
  esac
}

state_file="$(mktemp)"
trap 'rm -f "$state_file"' EXIT
: >"$state_file"

armed=0
while :; do
  cur="$(snapshot)" || cur=""

  if [ -n "$cur" ]; then
    if [ "$armed" -eq 0 ]; then
      # ARM-TIME SNAPSHOT: a pure change-detector only ever catches the NEXT
      # transition, so arming after a run had already gone red would be silent
      # forever. Report what is live now plus the newest conclusion per
      # workflow, then fall through to transition-only reporting.
      printf '[ci-watch] armed on %s — current state:\n' "$BRANCH"
      printf '%s\n' "$cur" | awk -F'\t' '
        { if (!seen[$3]++) latest[$3] = $2 "\t" $4 "\t" $5
          if ($4 ~ /^(queued|in_progress|requested|waiting|pending)$/) live[++n] = $2 "\t" $3 "\t" $4 }
        END {
          for (i = 1; i <= n; i++) { split(live[i], f, "\t"); printf "  [%s] %s (%s) — running now\n", f[3], f[2], f[1] }
          for (w in latest) { split(latest[w], f, "\t")
            if (f[2] !~ /^(queued|in_progress|requested|waiting|pending)$/) {
              printf "  [%s] %s (%s) — last conclusion\n", f[2], w, f[1]
              if (f[2] != "success" && f[3] != "") printf "      -> %s\n", f[3] } }
        }'
      armed=1
    else
      # TRANSITION-ONLY from here: one line per run that started, one per run
      # that finished. Nothing is reprinted because something else moved.
      while IFS=$'\t' read -r id sha name state url; do
        [ -n "$id" ] || continue
        was="$(awk -F'\t' -v k="$id" '$1 == k { print $2; exit }' "$state_file")"
        [ "$was" = "$state" ] && continue
        if terminal "$state"; then
          printf '[ci-watch] %s (%s) — %s\n' "$name" "$sha" "$state"
          if [ "$state" != "success" ] && [ -n "$url" ]; then
            printf '    -> %s\n' "$url"
          fi
        elif [ -z "$was" ]; then
          # Announce a run ONCE, when first seen. queued -> in_progress on an
          # already-announced run is not news, and is deliberately dropped.
          printf '[ci-watch] %s (%s) — started\n' "$name" "$sha"
        fi
      done <<<"$cur"
    fi

    # Rewrite the id->state table from the current snapshot.
    printf '%s\n' "$cur" | awk -F'\t' 'NF >= 4 { print $1 "\t" $4 }' >"$state_file"
  fi

  # Active while ANY run in the window is non-terminal, regardless of which
  # commit it belongs to — which is the whole point of the rewrite.
  if printf '%s\n' "$cur" | awk -F'\t' '
       $4 ~ /^(queued|in_progress|requested|waiting|pending)$/ { found = 1 }
       END { exit(found ? 0 : 1) }'; then
    sleep "$POLL_ACTIVE_S"
  else
    sleep "$POLL_IDLE_S"
  fi
done
