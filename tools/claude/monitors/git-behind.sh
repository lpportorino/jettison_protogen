#!/usr/bin/env bash
# git-behind.sh — Monitor-tool observer that reports when this checkout falls
# BEHIND origin (someone else pushed), with the new commits' messages AND their
# per-commit changed-file lists, so divergence surfaces in seconds instead of at
# push-reject time.
#
# Arm it (lifetime-of-session) via the Monitor tool:
#   Monitor(command="tools/claude/monitors/git-behind.sh", persistent=true)
# See `.claude/rules/monitor-discipline.md`.
#
# protogen is TRUNK-ONLY and the pinned upstream for a consumer fleet, so a
# push that has to be reject-then-rebased is the common failure this prevents —
# and a rebase onto a trunk that moved under you can silently invert a doc claim
# against freshly-landed code.
#
# Repo-scoped + multi-session safe: `git fetch` only updates remote-tracking
# refs, never the working tree or local branch, so it cannot disturb a
# concurrent rebase/push. Emits ONLY when the upstream SHA changes AND we are
# actually behind (our own pushes advancing origin are silent).
#
# Tunables (env): POLL_S (default 60), REMOTE (default origin),
# BRANCH (default master), MAXLOG (commits to list, default 10),
# MAXFILES (files per commit, default 10).
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || echo "${CLAUDE_PROJECT_DIR:-$PWD}")"
cd "$repo_root"

# ── self-dedup: at most ONE git-behind per checkout, any arming channel ──────
# Context compaction loses the Monitor-tool task handle (and TaskList does not
# surface armed Monitors at all), so the agent re-arms post-compaction; a second
# arm would otherwise stack duplicate pollers. `flock -n` makes every arm past
# the first a no-op; the kernel lock auto-releases on process exit (no stale
# pidfile to reap). The lock lives in THIS checkout's .protogen/ — its location
# is the per-checkout key, so other worktrees never collide. fd 9 is held for
# the process lifetime by the exec redirect; the UserPromptSubmit hook probes
# the SAME lock to decide whether to warn.
mkdir -p "$repo_root/.protogen"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$repo_root/.protogen/git-behind.lock"
  if ! flock -n 9; then
    echo "[git-behind] already armed for this checkout (lock held) — second arm is a no-op"
    exit 0
  fi
else
  # flock absent: no kernel-lock self-dedup available, so poll WITHOUT it. A
  # post-compaction re-arm may briefly stack a second poller; both are harmless
  # read-only `git fetch` pollers. Critically this avoids the failure mode where
  # a missing `flock` under `set -euo pipefail` inverts the test into the
  # "already armed" branch, so the monitor exits immediately and NEVER polls.
  echo "[git-behind] flock unavailable (no kernel-lock self-dedup) — polling without it"
fi

interval="${POLL_S:-60}"
remote="${REMOTE:-origin}"
branch="${BRANCH:-master}"
maxlog="${MAXLOG:-10}"
maxfiles="${MAXFILES:-10}"
last_seen=""
first=1

while :; do
  # a transient fetch failure must never kill the monitor
  git fetch "$remote" "$branch" --quiet 2>/dev/null || true
  upstream="$(git rev-parse --verify --quiet "$remote/$branch" 2>/dev/null || true)"

  # Arm-time current-state read: on the FIRST poll only, emit the current
  # verdict ONCE — independent of the change-detection below — so arming this
  # observer AFTER someone already pushed is not silent.
  if [ "$first" -eq 1 ]; then
    first=0
    if [ -z "$upstream" ]; then
      echo "[git-behind] armed; no ${remote}/${branch} ref yet (fetch pending)"
    else
      armed_behind="$(git rev-list --count "HEAD..$remote/$branch" 2>/dev/null || echo 0)"
      if [ "$armed_behind" -gt 0 ]; then
        echo "[git-behind] armed; HEAD is ${armed_behind} behind ${remote}/${branch} — git pull --rebase ${remote} ${branch}"
      else
        echo "[git-behind] armed; HEAD in sync with ${remote}/${branch}"
      fi
    fi
  fi

  if [ -n "$upstream" ] && [ "$upstream" != "$last_seen" ]; then
    behind="$(git rev-list --count "HEAD..$remote/$branch" 2>/dev/null || echo 0)"
    if [ "$behind" -gt 0 ]; then
      ahead="$(git rev-list --count "$remote/$branch..HEAD" 2>/dev/null || echo 0)"
      # Newest-first full SHAs of the commits we are behind by; the listing
      # count tracks `behind` 1:1 (no --no-merges) so "N of M" never lies.
      mapfile -t shas < <(git log --format='%H' "HEAD..$remote/$branch" 2>/dev/null || true)
      total="${#shas[@]}"
      shown=$((total < maxlog ? total : maxlog))
      echo "[git-behind] HEAD is ${behind} behind ${remote}/${branch} (ahead ${ahead}) — newest ${shown} of ${total} new commit(s):"
      i=0
      for sha in "${shas[@]}"; do
        [ "$i" -ge "$maxlog" ] && break
        i=$((i + 1))
        git show -s --format='  %h %s  (%an)' "$sha" 2>/dev/null || true
        # Capture once + truncate/indent via awk (NOT `| head`, which would
        # SIGPIPE the producer and trip `set -o pipefail`). Merge commits
        # diff-tree to nothing — they simply list no files.
        files_all="$(git diff-tree --no-commit-id --name-only -r "$sha" 2>/dev/null || true)"
        if [ -n "$files_all" ]; then
          printf '%s\n' "$files_all" | awk -v n="$maxfiles" \
            '{ c++; if (c <= n) print "      " $0 }
             END { if (c > n) print "      … (+" (c - n) " more file(s))" }'
        fi
      done
      if [ "$total" -gt "$shown" ]; then
        echo "  … and $((total - shown)) more commit(s)"
      fi
      echo "  where to dig:"
      echo "    what changed : git diff --stat HEAD..${remote}/${branch}   (full: git log -p HEAD..${remote}/${branch})"
      if [ "$ahead" -gt 0 ]; then
        echo "    you also have ${ahead} local commit(s) → rebase (not merge): git pull --rebase ${remote} ${branch}"
        echo "    RE-CHECK any doc claim your commits make about code that just landed upstream."
      else
        echo "    fast-forward is clean: git pull --rebase ${remote} ${branch}"
      fi
    fi
    last_seen="$upstream"
  fi
  sleep "$interval"
done
