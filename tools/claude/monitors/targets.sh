#!/usr/bin/env bash
# Shared arm-time preconditions for the monitor scripts and monitor-nudge hook.
# The hook decides whether a monitor is owed by calling the same functions the
# monitor calls before polling; keeping that decision here prevents a deliberate
# refusal from turning into an unbreakable WARN -> arm -> refuse loop.

CI_WATCH_OWNER_REPO=""
CI_WATCH_REFUSAL_REASON=""
GIT_BEHIND_REFUSAL_REASON=""

ci_watch_resolve_target() {
  local origin_url remote_path owner repo
  CI_WATCH_OWNER_REPO=""
  CI_WATCH_REFUSAL_REASON=""

  # Empty is not an override: ci-watch itself uses the same `:-` semantics.
  if [ -n "${OWNER_REPO:-}" ]; then
    CI_WATCH_OWNER_REPO="$OWNER_REPO"
    return 0
  fi

  if ! git rev-parse --git-dir >/dev/null 2>&1; then
    CI_WATCH_REFUSAL_REASON="not a git checkout, and OWNER_REPO is not set"
    return 1
  fi

  origin_url="$(git remote get-url origin 2>/dev/null)" || {
    CI_WATCH_REFUSAL_REASON="origin is not configured, and OWNER_REPO is not set"
    return 1
  }

  # The default target comes from the remote, never from a hardcoded upstream.
  # Accept the ordinary GitHub HTTPS and SSH spellings; local paths, other
  # hosts, extra path components, queries and fragments are all refusals.
  case "$origin_url" in
  https://github.com/*)
    remote_path="${origin_url#https://github.com/}"
    ;;
  http://github.com/*)
    remote_path="${origin_url#http://github.com/}"
    ;;
  ssh://git@github.com/*)
    remote_path="${origin_url#ssh://git@github.com/}"
    ;;
  git@github.com:*)
    remote_path="${origin_url#git@github.com:}"
    ;;
  *)
    CI_WATCH_REFUSAL_REASON="origin is not a github.com repository URL: $origin_url"
    return 1
    ;;
  esac

  remote_path="${remote_path%/}"
  remote_path="${remote_path%.git}"
  owner="${remote_path%%/*}"
  repo="${remote_path#*/}"
  if [ -z "$owner" ] || [ -z "$repo" ] || [ "$repo" = "$remote_path" ] \
    || [ "${repo#*/}" != "$repo" ]; then
    CI_WATCH_REFUSAL_REASON="origin does not contain one GitHub owner/repo pair: $origin_url"
    return 1
  fi
  case "$owner$repo" in
  *[!A-Za-z0-9._-]*)
    CI_WATCH_REFUSAL_REASON="origin contains characters outside a GitHub owner/repo pair: $origin_url"
    return 1
    ;;
  esac

  CI_WATCH_OWNER_REPO="$owner/$repo"
  return 0
}

git_behind_remote_is_configured() {
  local remote
  GIT_BEHIND_REFUSAL_REASON=""
  remote="${1:-origin}"

  if ! git rev-parse --git-dir >/dev/null 2>&1; then
    GIT_BEHIND_REFUSAL_REASON="not a git checkout"
    return 1
  fi
  if ! git remote get-url "$remote" >/dev/null 2>&1; then
    GIT_BEHIND_REFUSAL_REASON="remote '$remote' is not configured"
    return 1
  fi
  return 0
}

monitor_sleep() {
  # The shell retains its locks. The child must not: if the monitor is stopped
  # during a sleep, inherited descriptors would otherwise make the dead
  # monitor look armed until the orphaned child exits.
  sleep "$1" 8>&- 9>&-
}
