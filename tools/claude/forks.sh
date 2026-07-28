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
BRIEF_CHECK="$SCRIPT_DIR/brief-check.sh"

# Sources a brief cites that git cannot carry are copied into the fork, and the
# list of what was copied is recorded so release can exclude those exact paths.
# It is deliberately not an ignore-based exclusion: a clone carries its own
# .gitignore, so whether a shipped file shows up in porcelain depends on the tree
# being shipped INTO rather than on anything release controls. Path exclusion is
# the only form that holds for a cited source anywhere in the tree.
#
# THE RECORD IS THE COORDINATOR'S AND LIVES IN THE COORDINATOR'S STATE DIR. It
# used to live only at the in-fork path below — inside the ONE directory the
# owner marker tells the worker is theirs, and which `release` itself `rm -rf`s.
# So a worker that tidied its own scratch deleted the only record of what claim
# had put in the tree, and release then refused the fork over residue THE
# COORDINATOR HAD CREATED, with no state an operator could reach that made it
# pass. Measured: a fork citing one untracked, non-ignored source released
# cleanly with its scratch intact and deadlocked on `?? tools/scratch-note.md`
# once the worker removed `.fork-scratch/`. That is the same false premise as
# the tracked-files-under-.fork-scratch deletion this script already carries a
# repair for — a cleanup step deciding, wrongly, what is disposable — reappearing
# one level up, in what the cleanup step is allowed to destroy.
#
# The in-fork copy is still written, but purely as INFORMATION for the worker.
# Nothing in release reads it while the coordinator's record exists, so deleting
# it is now harmless.
SHIPPED_MANIFEST_REL=".fork-scratch/shipped-sources.txt"

# One record per fork, keyed by the fork's absolute path with `%` and `/`
# percent-encoded. That encoding is injective (so two forks can never share a
# record) and needs no hashing tool, keeping this script's dependency set as it
# was.
shipped_record_path() {
  local fork="$1" key
  key="${fork//%/%25}"
  key="${key//\//%2F}"
  printf '%s/shipped/%s.txt\n' "$STATE_DIR" "$key"
}

usage() {
  cat >&2 <<'EOF'
usage:
  tools/claude/forks.sh claim <path> <task-id> <owner> [brief-path]
  tools/claude/forks.sh release <path> --owner-signalled <signal>
  tools/claude/forks.sh check <path>
  tools/claude/forks.sh list

claim GATES THE BRIEF FIRST (tools/claude/brief-check.sh, against this tree and
against every sibling brief the manifest still records as OWNED) and creates
nothing if it is refused. It then clones this repository, strips and verifies
the remote, checks that every symlink stays inside the clone, commits
DONATION_BRIEF.md plus DONATION_OWNER.md, and finally copies in every cited
source that git cannot carry because it is untracked. If brief-path is omitted,
claim reads <path>.brief.

What claim shipped is recorded under .protogen/forks/shipped/, on the
COORDINATOR's side, so release can tell a shipped input from worker residue even
after the worker has cleaned out its own scratch directory.

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

# Latest-wins over the append-only manifest, same semantics as cmd_list. A fork
# still OWNED is a live sibling, and its committed brief is what its worker is
# actually reading — so that, not the coordinator-side source file, is what the
# cross-brief overlap check must compare against.
collect_owned_siblings() {
  local path task owner brief claimed_at state signal
  [ -f "$MANIFEST" ] || return 0
  declare -A latest_state=()
  declare -A latest_brief=()
  local -a order=()
  while IFS=$'\t' read -r path task owner brief claimed_at state signal; do
    case "$path" in
      "" | \#*) continue ;;
    esac
    if [ -z "${latest_state[$path]+x}" ]; then
      order+=("$path")
    fi
    latest_state["$path"]="$state"
    latest_brief["$path"]="$brief"
  done < "$MANIFEST"
  local p
  for p in ${order+"${order[@]}"}; do
    [ "${latest_state[$p]}" = "OWNED" ] || continue
    if [ -f "$p/DONATION_BRIEF.md" ]; then
      printf '%s\n' "$p"
    elif [ -f "${latest_brief[$p]}" ]; then
      printf '%s\n' "${latest_brief[$p]}"
    else
      printf '[forks] note — sibling %s is OWNED but neither its fork brief nor %s is readable; its OWNED set is UNJUDGED\n' \
        "$p" "${latest_brief[$p]}" >&2
    fi
  done
}

# A cited source is a REPOSITORY-RELATIVE path, so its copy must land inside the
# fork. This is checked textually rather than by resolving the destination,
# because the destination does not exist yet — and the property wanted is "this
# path has no way to leave the tree", for which `..` and an absolute root are the
# only two mechanisms a relative path has.
#
# It is the same invariant assert_clone_links enforces for symlinks, arriving
# through a door that check cannot cover: assert_clone_links inspects the tree
# AFTER shipping and sees only symlinks, and `cp -p` dereferences, so a traversal
# leaves nothing for it to find. Measured before this guard existed: a brief
# citing `tools/../../pwn/loot.txt` made claim create a directory and write a
# file OUTSIDE the fork, then report CLAIMED with "shipped: 1" and exit 0.
assert_shippable_path() {
  local rel="$1" fork="$2"
  case "$rel" in
    "" | /*)
      fail "cited source is not a repository-relative path: '$rel'; the fork is retained and must not be dispatched: $fork"
      ;;
  esac
  case "/$rel/" in
    */../*)
      fail "cited source '$rel' has a '..' component and would be written outside $fork; nothing outside the fork was created"
      ;;
  esac
  return 0
}

# Copy every cited source that exists in this repository but is not tracked, so
# a brief may cite gitignored evidence at all. `.protogen/` is gitignored, which
# means `git clone` cannot carry it: four wave-5 forks were dispatched with
# their cited research absent, and one was told to make a finding durable while
# the finding itself sat in space the clone could not reach.
ship_cited_sources() {
  local fork="$1" brief="$2" rel dest record list list_status=0 count=0
  local -a shipped=()

  # THE PRODUCER'S EXIT STATUS IS PART OF THE ANSWER. brief-check.sh documents
  # exit 3 as an INTERNAL ERROR that is never a verdict about the brief, and
  # cmd_claim honours that contract on the `check` leg — then this leg read the
  # list through a process substitution, whose status bash discards. An ERROR
  # therefore arrived as ZERO LINES, which is byte-identical to the honest "this
  # brief cites nothing git cannot carry": claim printed CLAIMED, printed
  # "shipped: 0", recorded the fork OWNED and exited 0 with the worker's inputs
  # missing. Measured with a ship-list forced onto its own exit-3 path.
  list="$("$BRIEF_CHECK" ship-list "$brief" --root "$ROOT")" || list_status=$?
  [ "$list_status" -eq 0 ] ||
    fail "brief-check ship-list FAILED (exit $list_status) for $brief; that is an ERROR, not an empty ship list — the fork is retained and must not be dispatched: $fork"

  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    assert_shippable_path "$rel" "$fork"
    # Re-checked here rather than trusted from the check pass: claim holds the
    # manifest lock, not a lock on the filesystem.
    [ -f "$ROOT/$rel" ] ||
      fail "cited source vanished between check and ship: $rel; the fork is retained and must not be dispatched: $fork"
    dest="$fork/$rel"
    mkdir -p -- "$(dirname -- "$dest")"
    cp -p -- "$ROOT/$rel" "$dest" ||
      fail "could not ship cited source $rel into $fork"
    shipped+=("$rel")
    count=$((count + 1))
  done <<< "$list"

  record="$(shipped_record_path "$fork")"
  mkdir -p -- "$(dirname -- "$record")" ||
    fail "could not create the shipped-source record directory for $fork"
  : > "$record" ||
    fail "could not record the shipped sources for $fork at $record"
  mkdir -p -- "$fork/.fork-scratch"
  : > "$fork/$SHIPPED_MANIFEST_REL"
  if [ "$count" -gt 0 ]; then
    printf '%s\n' "${shipped[@]}" > "$record"
    printf '%s\n' "${shipped[@]}" > "$fork/$SHIPPED_MANIFEST_REL"
  fi
  SHIPPED_COUNT="$count"
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

  # THE BRIEF IS GATED BEFORE ANYTHING IS CREATED. A defective brief refused
  # here costs one command; refused after dispatch it costs a worker-session,
  # which is what every wave-4 and wave-5 fork paid. Sibling briefs come from
  # the manifest, so the cross-brief overlap check sees every fork that is live
  # right now rather than only the one being claimed.
  [ -x "$BRIEF_CHECK" ] ||
    fail "brief gate is missing or not executable: $BRIEF_CHECK; refusing to dispatch an unchecked brief"
  local -a sibling_args=()
  local sib
  while IFS= read -r sib; do
    [ -n "$sib" ] || continue
    sibling_args+=(--sibling "$sib")
  done < <(collect_owned_siblings)
  local check_status=0
  "$BRIEF_CHECK" check "$brief" --root "$ROOT" ${sibling_args+"${sibling_args[@]}"} || check_status=$?
  case "$check_status" in
    0) ;;
    1) fail "brief-check REFUSED $brief; nothing was cloned and no fork exists" ;;
    *) fail "brief-check could not reach a verdict on $brief (exit $check_status); that is an ERROR, not a clean brief" ;;
  esac

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
    printf 'Scratch: ./.fork-scratch/ inside this checkout. Never use a shared temp path.\n\n'
    printf 'A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. Proving "the guard fires" is\n'
    printf 'under-specified: a neighbouring clause usually refuses too, so a red run can\n'
    printf 'come from a clause other than the one under test and still look like proof.\n'
    printf 'Prove each guard by BREAKING ITS OWN CLAUSE alone and watching that clause\n'
    printf 'name itself in the failure — and assert the mutation LANDED (grep the new\n'
    printf 'text, require a non-zero count) before believing any colour. Demand a FAIL,\n'
    printf 'not an ERROR: a non-zero exit from a syntax error wears the right colour.\n'
    printf 'This requirement stands for every fork and is emitted HERE, by the harness,\n'
    printf 'because a standing requirement that depends on an author remembering to type\n'
    printf 'it is exactly the kind of rule that has already failed twice.\n'
  } > "$fork/DONATION_OWNER.md"
  mkdir -p -- "$fork/.fork-scratch"

  git_in "$fork" add DONATION_BRIEF.md DONATION_OWNER.md
  if ! git_in "$fork" -c user.name=protogen -c user.email=protogen@localhost \
    commit --quiet -m "donate: seed brief and owner for '$task' (owner: $owner)"; then
    fail "owner-marker seed commit failed; fork is retained and must not be dispatched: $fork"
  fi

  # Shipped AFTER the seed commit, so a cited source can never be swept into the
  # fork's history: these are INPUTS, not deliverables.
  SHIPPED_COUNT=0
  ship_cited_sources "$fork" "$brief"

  assert_clone_links "$fork"
  append_record "$fork" "$task" "$owner" "$brief" "$claimed_at" "OWNED" ""

  printf '[forks] CLAIMED\n'
  printf '  path: %s\n' "$fork"
  printf '  task: %s\n' "$task"
  printf '  owner: %s\n' "$owner"
  printf '  marker: committed before dispatch\n'
  printf '  remote: stripped and git push verified to refuse\n'
  printf '  brief: passed tools/claude/brief-check.sh\n'
  # THE PATHS, not only the count. This is a DIAGNOSTIC and not a gate — it
  # cannot tell a brief that legitimately ships nothing from one whose citations
  # the extractor did not read, and nothing here should be taken to claim it can.
  # What it does buy is the only signal available at the one moment a human is
  # looking: the coordinator who meant to ship two files sees the list they meant
  # to ship, rather than a bare `0` that reads the same as a brief with no
  # gitignored inputs at all.
  printf '  cited sources shipped (untracked, git could not carry them): %s\n' "$SHIPPED_COUNT"
  if [ "$SHIPPED_COUNT" -gt 0 ]; then
    sed 's/^/    - /' -- "$(shipped_record_path "$fork")"
  fi
}

print_residue() {
  local residue="$1"
  printf '%s\n' "$residue" | sed 's/^/  /' >&2
}

preserve_scratch_scripts() {
  local fork="$1" preserve_dir="$2" scratch="$fork/.fork-scratch"
  local source relative destination
  PRESERVED_SCRIPT_COUNT=0
  # `return 0`, never a bare `return`: a bare one propagates the FAILED test's
  # status out of a bare call site under `set -e`, so a fork with no scratch
  # directory aborts release with exit 1 and NO message at all.
  [ -d "$scratch" ] || return 0

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

# A directory is empty when a dotglob'd `*` expands to nothing. Done with shell
# builtins inside a subshell so the two `shopt`s cannot leak, and so this needs
# neither `ls` nor a nested `find` — both of which a caller's shell environment
# can shadow, and neither of which is needed to answer a question readdir
# already answers.
dir_is_empty() ( shopt -s nullglob dotglob; set -- "$1"/*; [ "$#" -eq 0 ] )

# CAN THIS FORK BE DELETED AT ALL? Asked BEFORE the first destructive step, and
# never inferred from that step's exit status.
#
# BOTH destructive steps in release are `rm -rf`, and `rm -rf` DELETES WHAT IT
# CAN BEFORE IT FAILS. Measured on this tree against a directory a toolchain
# container created in a bind mount: the sibling scratch log beside it was
# removed, the root-owned subtree survived, and rm exited 1. Reacting to that
# exit status therefore means reacting after part of the tree is already gone —
# and at the FINAL `rm -rf "$fork"` it would mean reacting to a fork shredded
# down to whatever refused, with its manifest still saying OWNED and the
# operator's only intact copy the bundle.
#
# WHAT REFUSES AN UNLINK IS THE PARENT DIRECTORY, NOT THE FILE. Unlinking needs
# write permission on the directory holding the entry and nothing whatever on
# the entry itself, so root-owned FILES in a directory we own delete cleanly —
# measured in that same run, where a root-owned `plain-scratch.log` sitting in a
# user-owned directory went away without complaint. The predicate is DIRECTORIES
# WE CANNOT WRITE. A scan for root-owned files would name the wrong set in both
# directions at once: it would list that log, and it would miss an unwritable
# directory whose contents we own.
#
# NOR IS OWNERSHIP THE PREDICATE. A directory root created at mode 755 and a
# directory we own at mode 555 refuse our unlink with the identical EACCES —
# both measured, both producing the same "cannot remove ...: Permission denied".
# `test -w` asks the question the kernel is going to answer anyway, so it
# honours root and ACLs for free, and it is why this guard is VACUOUS rather
# than WRONG when release itself runs as uid 0.
#
# EMPTY IS NOT NON-EMPTY. `rm -rf` clears an EMPTY unwritable directory through
# its PARENT's write bit and exits 0 — measured at mode 000 and at mode 555
# alike — so emptiness belongs in the predicate. Dropping it turns every such
# directory into a refusal release cannot justify, which is the false-positive
# direction this guard has to be trusted not to take.
assert_fork_is_clearable() {
  local fork="$1" listing dir reason meta status=0 i owner_spec
  local -a blockers=() reasons=()
  owner_spec="$(id -u):$(id -g)" || owner_spec="$EUID"

  # `-prune` so the scan never descends into a directory it has just flagged.
  # That keeps find's own exit status meaningful, instead of drowning it in the
  # very permission errors the flag already accounts for.
  listing="$(find "$fork" -type d \
    \( ! -readable -o ! -writable -o ! -executable \) -print -prune)" || status=$?
  # THE PRODUCER'S EXIT STATUS IS PART OF THE ANSWER — the same lesson
  # ship_cited_sources carries, in the one place where getting it wrong deletes
  # something. A find that died emits ZERO LINES, which is byte-identical to
  # "every directory here is clearable"; treating that as a verdict would delete
  # a fork on the strength of a scan that never ran.
  [ "$status" -eq 0 ] ||
    fail "unclearable-residue: the clearability scan of $fork FAILED (find exit $status); that is an ERROR, not a clearable fork — nothing was deleted"

  while IFS= read -r dir; do
    [ -n "$dir" ] || continue
    if [ -r "$dir" ] && [ -x "$dir" ]; then
      if dir_is_empty "$dir"; then
        continue
      fi
      reason="not writable by uid $EUID — rm cannot unlink the entries it holds"
    else
      # Unreadable is the one case this scan cannot decide: an empty one would
      # in fact clear, but we cannot look inside to find out. Reported rather
      # than assumed either way, because "I could not look" and "there is
      # nothing there" must not arrive as the same answer.
      reason="not readable or not traversable by uid $EUID — its contents are UNJUDGED"
    fi
    blockers+=("$dir")
    reasons+=("$reason")
  done <<< "$listing"

  if [ "${#blockers[@]}" -eq 0 ]; then
    return 0
  fi

  printf '[forks] FAIL — unclearable-residue: %s cannot be deleted by uid %s\n' \
    "$fork" "$EUID" >&2
  printf '  Nothing was deleted and the fork is still OWNED. This is refused BEFORE\n' >&2
  printf '  the first `rm -rf`, because `rm -rf` removes what it can before it fails.\n' >&2
  printf '  %s director(ies) in the way:\n' "${#blockers[@]}" >&2
  for i in "${!blockers[@]}"; do
    meta="$(stat -c '%A %U:%G' -- "${blockers[$i]}" 2>/dev/null)" || meta="<mode/owner unreadable>"
    printf '    %s  %s\n' "$meta" "${blockers[$i]}" >&2
    printf '      %s\n' "${reasons[$i]}" >&2
  done
  printf '  A toolchain container runs as root, so whatever it wrote into this bind\n' >&2
  printf '  mount stayed root-owned on the host. Clear it with the SAME privilege\n' >&2
  printf '  that created it. What to run, in order of preference:\n' >&2
  if [ -x "$fork/tools/uber.sh" ]; then
    # VERIFIED LIVE, not inferred from reading it: tools/uber.sh wraps every
    # command so that /workspace is chowned back to the invoking uid/gid
    # afterwards, on success and failure alike, and its /workspace mount is the
    # checkout the script itself sits in — which for the fork's own copy is the
    # fork. A no-op command is therefore a complete repair, and it took 0.31s
    # against a planted root-owned subtree here.
    printf '    %s/tools/uber.sh true\n' "$fork" >&2
    printf '      (no sudo, and no image name to remember: uber.sh chowns its whole\n' >&2
    printf '       /workspace mount back to your uid after EVERY run, so a no-op\n' >&2
    printf '       command is enough. It builds the pinned image first if absent.)\n' >&2
  else
    printf '    (this fork ships no tools/uber.sh, so the container route needs the\n' >&2
    printf '     image and mount you used by hand)\n' >&2
  fi
  printf '    chmod -R u+rwX <path>\n' >&2
  printf '      (only where the path above is already yours — chmod cannot help you\n' >&2
  printf '       with one owned by root)\n' >&2
  # OWNER AND GROUP, not just the uid: `chown 1000` leaves the group alone, so a
  # path left group-root stays a trap for the next tool that checks it. Resolved
  # once, with a numeric fallback, so a stripped-down environment without `id`
  # still prints something runnable rather than an empty field.
  printf '    sudo chown -R %s <path>\n' "$owner_spec" >&2
  printf '      (last resort, and ONLY the paths listed above — never the whole fork)\n' >&2
  printf '  Then re-run this exact command:\n' >&2
  printf '    tools/claude/forks.sh release %s --owner-signalled <signal>\n' "$fork" >&2
  exit 1
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

  # Sources shipped in by claim are the coordinator's INPUTS, not the worker's
  # output, so they must not read as residue. They are excluded by exact PATH
  # and never by relying on the fork's .gitignore: `.protogen/` happens to be
  # ignored here, so today those files would not surface in porcelain at all —
  # but that is a property of the tree being shipped into, not a guarantee this
  # script controls. A cited source under, say, `output/` or a path no ignore
  # rule covers would surface, and release would then refuse a fork whose only
  # "residue" was what claim itself put there — which is exactly what happened
  # while this list lived in worker-writable space; see SHIPPED_MANIFEST_REL.
  # Still read BEFORE .fork-scratch is removed, because the transitional in-fork
  # fallback below is inside it and the post-preservation check needs the same
  # list.
  local -a shipped_excludes=()
  local shipped_rel shipped_count=0 shipped_record shipped_source="none"
  shipped_record="$(shipped_record_path "$fork")"
  # TRANSITIONAL. A fork claimed before the record moved into the state
  # directory has only the in-fork copy, and refusing to read it would strand
  # every fork already in flight at the moment this landed.
  # RETIRES WHEN: no fork claimed by the previous version of claim is still
  # OWNED — `forks.sh list` is the check.
  if [ -f "$shipped_record" ]; then
    shipped_source="coordinator state"
  elif [ -f "$fork/$SHIPPED_MANIFEST_REL" ]; then
    shipped_record="$fork/$SHIPPED_MANIFEST_REL"
    shipped_source="in-fork copy (pre-state-dir claim)"
  else
    shipped_record=""
  fi
  if [ -n "$shipped_record" ]; then
    while IFS= read -r shipped_rel; do
      [ -n "$shipped_rel" ] || continue
      shipped_excludes+=(":(exclude,literal)$shipped_rel")
      shipped_count=$((shipped_count + 1))
    done < "$shipped_record"
  fi

  if ! residue="$(git_in "$fork" status --porcelain --untracked-files=all)"; then
    fail "git status failed; this is an ERROR, not evidence that release is safe"
  fi
  if ! outside_residue="$(git_in "$fork" status --porcelain --untracked-files=all -- \
    . ':(exclude).fork-scratch' ':(exclude).fork-scratch/**' \
    ${shipped_excludes+"${shipped_excludes[@]}"})"; then
    fail "scoped git status failed; this is an ERROR, not evidence that release is safe"
  fi
  if [ -n "$outside_residue" ]; then
    printf '[forks] FAIL — release found uncommitted residue in the whole porcelain:\n' >&2
    print_residue "$residue"
    printf '  owner signal was recorded by the caller but nothing was deleted\n' >&2
    exit 1
  fi

  # DELIBERATELY AFTER THE RESIDUE CHECK, not before it. Uncommitted work is the
  # question that decides whether this fork may be destroyed at all; whether the
  # filesystem will let us destroy it is only worth asking once the answer to
  # that one is yes. Keeping the order also keeps the residue clause the FIRST
  # thing that speaks, so a fork carrying both faults is refused by the gate that
  # was already load-bearing. Consequence, and it is intended: one fault is
  # reported per run, residue first.
  #
  # Placed before preserve_dir is created, so a refusal here leaves no orphaned
  # directory in the coordinator's state dir.
  assert_fork_is_clearable "$fork"

  safe_task="${RECORD_TASK//[^a-zA-Z0-9._-]/_}"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  preserve_dir="$STATE_DIR/preserved/$safe_task-$stamp-$$"
  mkdir -p -- "$preserve_dir"
  PRESERVED_SCRIPT_COUNT=0
  preserve_scratch_scripts "$fork" "$preserve_dir"

  # THE REPORT IS LIFTED HERE, NOT BY HAND. It is the one artifact a lift reads
  # most and the one a coordinator is most likely to skip, because the fork
  # LOOKS finished by then. Doing it by hand failed exactly that way once: two
  # `git show` calls failed against a clobbered ref, their errors scrolled past,
  # and release ran anyway — leaving the report recoverable only from the bundle.
  # It is recorded under .protogen/research/ (gitignored, machine-local) rather
  # than committed, because it is a worker's own account and the DURABLE half of
  # what it says belongs in commit messages and rules instead.
  #
  # Absence is NOT an error: a fork can legitimately be released before writing
  # one, and a third-party worker is bound by no such convention. So this reports
  # what it did and never refuses.
  REPORT_PRESERVED="none (the fork committed no FINAL_REPORT.md)"
  if git_in "$fork" cat-file -e master:FINAL_REPORT.md 2>/dev/null; then
    report_dest="$ROOT/.protogen/research/$(basename -- "$fork")-final-report.md"
    mkdir -p -- "$(dirname -- "$report_dest")"
    if git_in "$fork" show master:FINAL_REPORT.md > "$report_dest" 2>/dev/null &&
       [ -s "$report_dest" ]; then
      REPORT_PRESERVED="$report_dest ($(wc -l < "$report_dest" | tr -d ' ') lines)"
    else
      fail "FINAL_REPORT.md exists at master but could not be copied out; the fork is retained: $fork"
    fi
  fi

  # Scratch is disposable only after its source-like proofs have been copied.
  # Removing it also drops regenerable logs, renders, and images.
  #
  # GUARDED, THOUGH assert_fork_is_clearable HAS ALREADY PASSED. Unguarded, this
  # line's failure propagated out under `set -e` with no `[forks]` line at all:
  # release stopped mid-way carrying only rm's own one-line complaint, which
  # names a FILE and so points at the wrong object entirely (the directory
  # holding it is what refused). The scan upstream screens the whole permission
  # class, so anything reaching here is NOT that class — a writer racing the
  # scan, a mount point, or an I/O error — and the message says so rather than
  # repeating a diagnosis that has already been ruled out.
  if ! rm -rf -- "$fork/.fork-scratch"; then
    fail "scratch-cleanup-failed: could not remove $fork/.fork-scratch; the fork is retained and stays OWNED. rm's own message is above. The clearability scan passed, so this is NOT the unwritable-directory class it screens for — suspect a writer racing this command, a mount point, or an I/O error."
  fi

  # ...BUT `.fork-scratch/` IS NOT NECESSARILY ALL SCRATCH. The base tree may
  # TRACK files there, and this repo does: a rule elsewhere requires a probe to
  # be tracked next to the claim it supports, and probes were committed here.
  # A blanket rm then deletes base-tree content, the post-preservation check
  # below sees those deletions as residue, and release refuses — permanently,
  # for EVERY fork cut from such a tree, with no state the operator can reach
  # that makes it pass. Restoring what git tracks is what keeps "scratch is
  # disposable" true of the scratch and only the scratch.
  git_in "$fork" checkout -- .fork-scratch 2>/dev/null || true

  if ! post_residue="$(git_in "$fork" status --porcelain --untracked-files=all -- \
    . ${shipped_excludes+"${shipped_excludes[@]}"})"; then
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
  printf '  final report: %s\n' "$REPORT_PRESERVED"
  printf '  shipped sources excluded from the residue check by path: %s (record: %s)\n' \
    "$shipped_count" "$shipped_source"
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
