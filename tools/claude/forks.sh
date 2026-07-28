#!/usr/bin/env bash
# forks.sh — mechanically own the lifecycle of isolated donation clones.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
STATE_DIR="${PROTOGEN_FORKS_STATE_DIR:-$ROOT/.protogen/forks}"
case "$STATE_DIR" in
  /*) ;;
  *) STATE_DIR="$ROOT/$STATE_DIR" ;;
esac
MANIFEST="$STATE_DIR/manifest.tsv"
LOCK_DIR="$STATE_DIR/manifest.lock"
LOCK_HELD=0

usage() {
  cat >&2 <<'EOF'
usage:
  tools/claude/forks.sh claim <path> <task-id> <owner> [brief-path]
  tools/claude/forks.sh release <path> --owner-signalled <signal>
  tools/claude/forks.sh check <path>
  tools/claude/forks.sh list

claim clones this repository, strips and verifies the remote, checks that every
symlink stays inside the clone, and commits DONATION_BRIEF.md plus
DONATION_OWNER.md before returning the path for dispatch. If brief-path is
omitted, claim reads <path>.brief.

release preserves scratch scripts and a self-contained Git bundle under
.protogen/forks/preserved/, requires a completely clean worktree after scratch
cleanup, and only then deletes the fork.

PROTOGEN_FORKS_STATE_DIR may select another state directory for an isolated
test. Relative values are resolved from the repository root.
EOF
  exit 2
}

fail() {
  printf '[forks] FAIL — %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [ "$LOCK_HELD" -eq 1 ]; then
    rm -f -- "$LOCK_DIR/pid"
    rmdir -- "$LOCK_DIR" 2>/dev/null || true
  fi
}
trap cleanup EXIT

validate_field() {
  local label="$1" value="$2"
  [ -n "$value" ] || fail "$label must not be empty"
  case "$value" in
    *$'\t'* | *$'\n'* | *$'\r'*)
      fail "$label must not contain tabs or newlines"
      ;;
  esac
}

canonical_target() {
  local raw="$1" parent base
  [ -n "$raw" ] || fail "fork path must not be empty"
  parent="$(dirname -- "$raw")"
  base="$(basename -- "$raw")"
  case "$base" in
    "" | "." | "..") fail "fork path must name a child directory: $raw" ;;
  esac
  [ -d "$parent" ] || fail "fork parent does not exist: $parent"
  parent="$(cd -- "$parent" && pwd -P)"
  printf '%s/%s\n' "$parent" "$base"
}

canonical_file() {
  local raw="$1" parent base
  [ -f "$raw" ] || fail "brief is not a regular file: $raw"
  parent="$(dirname -- "$raw")"
  base="$(basename -- "$raw")"
  parent="$(cd -- "$parent" && pwd -P)"
  printf '%s/%s\n' "$parent" "$base"
}

git_in() {
  local repo="$1"
  shift
  env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"
}

manifest_header() {
  cat <<'EOF'
# protogen fork manifest v1
# ONE-SIDED PROTECTION: this gitignored coordinator manifest only stops US from
# colliding with a worker we placed. It cannot stop a third-party harness from
# wandering into a fork. The worker-side half is the committed
# DONATION_OWNER.md marker inside each fork. A clean or absent manifest proves
# only that this coordinator recorded no claim; it does not prove a tree is
# unowned.
# fields: fork-path	task-id	owner	brief-path	claimed-at	state	release-signal
EOF
}

acquire_lock() {
  mkdir -p -- "$STATE_DIR"
  if ! mkdir -- "$LOCK_DIR" 2>/dev/null; then
    local holder="unknown"
    if [ -r "$LOCK_DIR/pid" ]; then
      IFS= read -r holder < "$LOCK_DIR/pid" || holder="unknown"
    fi
    fail "manifest is locked by pid $holder; refuse to guess whether a coordinator is live"
  fi
  LOCK_HELD=1
  printf '%s\n' "$$" > "$LOCK_DIR/pid"
}

ensure_manifest() {
  [ -f "$MANIFEST" ] && return
  local tmp
  tmp="$(mktemp "$STATE_DIR/manifest.XXXXXX")"
  manifest_header > "$tmp"
  mv -- "$tmp" "$MANIFEST"
}

RECORD_PATH=""
RECORD_TASK=""
RECORD_OWNER=""
RECORD_BRIEF=""
RECORD_CLAIMED_AT=""
RECORD_STATE=""
RECORD_SIGNAL=""

lookup_record() {
  local wanted="$1"
  RECORD_PATH=""
  RECORD_TASK=""
  RECORD_OWNER=""
  RECORD_BRIEF=""
  RECORD_CLAIMED_AT=""
  RECORD_STATE=""
  RECORD_SIGNAL=""
  [ -f "$MANIFEST" ] || return 1

  local path task owner brief claimed_at state signal
  while IFS=$'\t' read -r path task owner brief claimed_at state signal; do
    case "$path" in
      "" | \#*) continue ;;
    esac
    if [ "$path" = "$wanted" ]; then
      RECORD_PATH="$path"
      RECORD_TASK="$task"
      RECORD_OWNER="$owner"
      RECORD_BRIEF="$brief"
      RECORD_CLAIMED_AT="$claimed_at"
      RECORD_STATE="$state"
      RECORD_SIGNAL="$signal"
    fi
  done < "$MANIFEST"
  [ -n "$RECORD_PATH" ]
}

state_blocks_claim() {
  case "$1" in
    OWNED | RELEASED | LIFTED) return 0 ;;
    GCd) return 1 ;;
    *) fail "manifest contains invalid state: $1" ;;
  esac
}

append_record() {
  local path="$1" task="$2" owner="$3" brief="$4"
  local claimed_at="$5" state="$6" signal="$7" tmp
  case "$state" in
    OWNED | RELEASED | LIFTED | GCd) ;;
    *) fail "refusing invalid manifest state: $state" ;;
  esac
  tmp="$(mktemp "$STATE_DIR/manifest.XXXXXX")"
  cp -- "$MANIFEST" "$tmp"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$path" "$task" "$owner" "$brief" "$claimed_at" "$state" "$signal" >> "$tmp"
  mv -- "$tmp" "$MANIFEST"
}

assert_clone_links() {
  local fork="$1" bad resolved link

  if ! bad="$(find "$fork" -type l -lname '/*' -print -quit)"; then
    fail "could not inspect clone for absolute symlinks"
  fi
  [ -z "$bad" ] || fail "clone contains an absolute symlink: $bad"

  if ! bad="$(find "$fork" -xtype l -print -quit)"; then
    fail "could not inspect clone for dangling symlinks"
  fi
  [ -z "$bad" ] || fail "clone contains a dangling symlink: $bad"

  while IFS= read -r -d '' link; do
    if ! resolved="$(readlink -f -- "$link")"; then
      fail "cannot resolve symlink: $link"
    fi
    case "$resolved" in
      "$fork" | "$fork"/*) ;;
      *) fail "symlink escapes clone root: $link -> $resolved" ;;
    esac
  done < <(find "$fork" -type l -print0)
}

verify_remote_is_stripped() {
  local fork="$1" remotes push_output
  remotes="$(git_in "$fork" remote -v)"
  [ -z "$remotes" ] || fail "remote was not stripped from $fork: $remotes"

  if push_output="$(git_in "$fork" push 2>&1)"; then
    fail "remote strip is not safe: git push unexpectedly succeeded in $fork"
  fi
  case "$push_output" in
    *"No configured push destination"* | *"No remote configured to push to"*) ;;
    *)
      printf '%s\n' "$push_output" | sed 's/^/  /' >&2
      fail "remote strip is unverified: git push failed for an unexpected reason"
      ;;
  esac
}

cmd_claim() {
  local raw_path="${1:-}" task="${2:-}" owner="${3:-}"
  local raw_brief="${4:-}" fork brief claimed_at base
  [ "$#" -ge 3 ] && [ "$#" -le 4 ] || usage
  validate_field "task id" "$task"
  validate_field "owner" "$owner"
  fork="$(canonical_target "$raw_path")"
  validate_field "fork path" "$fork"

  acquire_lock
  ensure_manifest

  if lookup_record "$fork" && state_blocks_claim "$RECORD_STATE"; then
    fail "fork is already $RECORD_STATE at $fork (task=$RECORD_TASK owner=$RECORD_OWNER)"
  fi
  if [ -e "$fork" ] || [ -L "$fork" ]; then
    if [ -n "$RECORD_PATH" ]; then
      fail "path already exists and was last recorded $RECORD_STATE by $RECORD_OWNER: $fork; it was NOT removed"
    fi
    fail "path already exists with no manifest owner: $fork; it was NOT removed"
  fi
  if [ -z "$raw_brief" ]; then
    raw_brief="$fork.brief"
  fi
  brief="$(canonical_file "$raw_brief")"
  validate_field "brief path" "$brief"

  if ! env -u GIT_DIR -u GIT_WORK_TREE git clone --quiet --no-hardlinks "$ROOT" "$fork"; then
    fail "clone failed; any partial path is preserved for inspection: $fork"
  fi
  if ! git_in "$fork" remote remove origin 2>/dev/null; then
    fail "could not strip origin; fork is retained and must not be dispatched: $fork"
  fi
  verify_remote_is_stripped "$fork"

  claimed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  base="$(git_in "$fork" rev-parse --short HEAD)"
  cp -- "$brief" "$fork/DONATION_BRIEF.md"
  {
    printf 'owner: %s\n' "$owner"
    printf 'fork: %s\n' "$(basename -- "$fork")"
    printf 'task: %s\n' "$task"
    printf 'brief-source: %s\n' "$brief"
    printf 'claimed-at: %s\n' "$claimed_at"
    printf 'base: %s\n\n' "$base"
    printf 'THIS CHECKOUT HAS EXACTLY ONE WORKER. Ownership began when this path was handed over.\n'
    printf 'If you meet a file you did not write: PRESERVE it, exclude it, and report its\n'
    printf 'provenance as unprovable. Do NOT adjudicate it.\n\n'
    printf 'THE REMOTE IS REMOVED ON PURPOSE. Do not add one.\n'
    printf 'Scratch: ./.fork-scratch/ inside this checkout. Never use a shared temp path.\n'
  } > "$fork/DONATION_OWNER.md"
  mkdir -p -- "$fork/.fork-scratch"

  git_in "$fork" add DONATION_BRIEF.md DONATION_OWNER.md
  if ! git_in "$fork" -c user.name=protogen -c user.email=protogen@localhost \
    commit --quiet -m "donate: seed brief and owner for '$task' (owner: $owner)"; then
    fail "owner-marker seed commit failed; fork is retained and must not be dispatched: $fork"
  fi

  assert_clone_links "$fork"
  append_record "$fork" "$task" "$owner" "$brief" "$claimed_at" "OWNED" ""

  printf '[forks] CLAIMED\n'
  printf '  path: %s\n' "$fork"
  printf '  task: %s\n' "$task"
  printf '  owner: %s\n' "$owner"
  printf '  marker: committed before dispatch\n'
  printf '  remote: stripped and git push verified to refuse\n'
}

print_residue() {
  local residue="$1"
  printf '%s\n' "$residue" | sed 's/^/  /' >&2
}

preserve_scratch_scripts() {
  local fork="$1" preserve_dir="$2" scratch="$fork/.fork-scratch"
  local source relative destination
  PRESERVED_SCRIPT_COUNT=0
  [ -d "$scratch" ] || return

  while IFS= read -r -d '' source; do
    relative="${source#"$scratch"/}"
    destination="$preserve_dir/scripts/$relative"
    mkdir -p -- "$(dirname -- "$destination")"
    cp -p -- "$source" "$destination"
    PRESERVED_SCRIPT_COUNT=$((PRESERVED_SCRIPT_COUNT + 1))
  done < <(find "$scratch" -type f \
    \( -name '*.sh' -o -name '*.clj' -o -name '*.py' -o -name '*.edn' -o -name '*.sql' \) \
    -print0)
}

cmd_release() {
  local raw_path="${1:-}" flag="${2:-}" signal="${3:-}"
  local fork marker_owner residue outside_residue post_residue
  local safe_task stamp preserve_dir bundle
  [ "$#" -eq 3 ] || {
    if [ -n "$raw_path" ] && [ -z "$flag" ]; then
      fail "release requires --owner-signalled <signal>; filesystem idleness is not completion"
    fi
    usage
  }
  [ "$flag" = "--owner-signalled" ] ||
    fail "release requires the explicit --owner-signalled <signal> flag"
  validate_field "release signal" "$signal"
  fork="$(canonical_target "$raw_path")"
  validate_field "fork path" "$fork"

  acquire_lock
  ensure_manifest
  if ! lookup_record "$fork"; then
    fail "fork has no manifest claim: $fork"
  fi
  [ "$RECORD_STATE" = "OWNED" ] ||
    fail "fork is not OWNED (state=$RECORD_STATE owner=$RECORD_OWNER): $fork"
  [ -d "$fork" ] && [ ! -L "$fork" ] ||
    fail "claimed fork is missing or is a symlink; nothing was deleted: $fork"
  [ -d "$fork/.git" ] ||
    fail "claimed path is not an isolated clone with its own .git directory: $fork"
  [ "$fork" != "$ROOT" ] ||
    fail "refusing to release the coordinator repository itself"
  [ -f "$fork/DONATION_OWNER.md" ] ||
    fail "owner marker is missing; nothing was deleted: $fork"
  marker_owner="$(sed -n 's/^owner: //p' "$fork/DONATION_OWNER.md" | head -n 1)"
  [ "$marker_owner" = "$RECORD_OWNER" ] ||
    fail "owner marker ($marker_owner) disagrees with manifest owner ($RECORD_OWNER)"

  if ! residue="$(git_in "$fork" status --porcelain --untracked-files=all)"; then
    fail "git status failed; this is an ERROR, not evidence that release is safe"
  fi
  if ! outside_residue="$(git_in "$fork" status --porcelain --untracked-files=all -- \
    . ':(exclude).fork-scratch' ':(exclude).fork-scratch/**')"; then
    fail "scoped git status failed; this is an ERROR, not evidence that release is safe"
  fi
  if [ -n "$outside_residue" ]; then
    printf '[forks] FAIL — release found uncommitted residue in the whole porcelain:\n' >&2
    print_residue "$residue"
    printf '  owner signal was recorded by the caller but nothing was deleted\n' >&2
    exit 1
  fi

  safe_task="${RECORD_TASK//[^a-zA-Z0-9._-]/_}"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  preserve_dir="$STATE_DIR/preserved/$safe_task-$stamp-$$"
  mkdir -p -- "$preserve_dir"
  PRESERVED_SCRIPT_COUNT=0
  preserve_scratch_scripts "$fork" "$preserve_dir"

  # Scratch is disposable only after its source-like proofs have been copied.
  # Removing it also drops regenerable logs, renders, and images.
  rm -rf -- "$fork/.fork-scratch"

  if ! post_residue="$(git_in "$fork" status --porcelain --untracked-files=all)"; then
    fail "post-preservation git status failed; the fork remains in place"
  fi
  if [ -n "$post_residue" ]; then
    printf '[forks] FAIL — release still has uncommitted residue after scratch preservation:\n' >&2
    print_residue "$post_residue"
    printf '  nothing outside .fork-scratch was removed; the fork remains in place\n' >&2
    exit 1
  fi

  # A clean worktree does not prove its commits were lifted. Preserve every ref
  # in a self-contained bundle so deletion cannot discard unique committed work.
  bundle="$preserve_dir/fork.bundle"
  if ! git_in "$fork" bundle create "$bundle" --all; then
    fail "could not preserve committed work; the fork remains in place"
  fi
  if ! git_in "$fork" bundle verify "$bundle" >/dev/null; then
    fail "bundle verification failed; the fork remains in place"
  fi

  if ! rm -rf -- "$fork"; then
    fail "fork deletion failed; manifest remains OWNED for fail-safe recovery"
  fi
  append_record "$RECORD_PATH" "$RECORD_TASK" "$RECORD_OWNER" "$RECORD_BRIEF" \
    "$RECORD_CLAIMED_AT" "GCd" "$signal"

  printf '[forks] RELEASED\n'
  printf '  path: %s\n' "$fork"
  printf '  signal: %s\n' "$signal"
  printf '  scratch scripts preserved: %s\n' "$PRESERVED_SCRIPT_COUNT"
  printf '  committed work bundle: %s\n' "$bundle"
  printf '  state: GCd\n'
}

cmd_check() {
  local raw_path="${1:-}" fork
  [ "$#" -eq 1 ] || usage
  fork="$(canonical_target "$raw_path")"
  validate_field "fork path" "$fork"

  if lookup_record "$fork" && state_blocks_claim "$RECORD_STATE"; then
    fail "claim would collide: $fork is $RECORD_STATE (task=$RECORD_TASK owner=$RECORD_OWNER)"
  fi
  if [ -e "$fork" ] || [ -L "$fork" ]; then
    if [ -n "$RECORD_PATH" ]; then
      fail "claim would collide: path exists; last record is $RECORD_STATE by $RECORD_OWNER: $fork"
    fi
    fail "claim would collide: path exists without a manifest owner: $fork"
  fi
  printf '[forks] AVAILABLE — no coordinator claim and no existing path: %s\n' "$fork"
}

cmd_list() {
  if [ ! -f "$MANIFEST" ]; then
    printf '[forks] no fork claims (manifest does not exist yet)\n'
    return
  fi

  local path task owner brief claimed_at state signal
  local -a order=()
  declare -A latest=()
  declare -A seen=()
  while IFS=$'\t' read -r path task owner brief claimed_at state signal; do
    case "$path" in
      "" | \#*) continue ;;
    esac
    if [ -z "${seen[$path]+x}" ]; then
      order+=("$path")
      seen["$path"]=1
    fi
    latest["$path"]="$path"$'\t'"$task"$'\t'"$owner"$'\t'"$brief"$'\t'"$claimed_at"$'\t'"$state"$'\t'"$signal"
  done < "$MANIFEST"

  if [ "${#order[@]}" -eq 0 ]; then
    printf '[forks] no fork claims (manifest is empty)\n'
    return
  fi

  printf 'STATE\tOWNER\tTASK\tPATH\tRELEASE-SIGNAL\n'
  for path in "${order[@]}"; do
    IFS=$'\t' read -r path task owner brief claimed_at state signal <<< "${latest[$path]}"
    printf '%s\t%s\t%s\t%s\t%s\n' "$state" "$owner" "$task" "$path" "${signal:--}"
  done
}

case "${1:-}" in
  claim)
    shift
    cmd_claim "$@"
    ;;
  release)
    shift
    cmd_release "$@"
    ;;
  check)
    shift
    cmd_check "$@"
    ;;
  list)
    shift
    [ "$#" -eq 0 ] || usage
    cmd_list
    ;;
  *)
    usage
    ;;
esac
