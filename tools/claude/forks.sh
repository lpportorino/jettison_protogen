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
  # TAGGED like every other outcome, so a caller filtering this script's stderr
  # on `^\[forks\]` sees all four classes and not just three.
  printf '[forks] USAGE — the invocation is malformed; nothing was inspected\n' >&2
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

EXIT CODES — a refusal and a breakage are different answers:
  0  done
  1  [forks] FAIL  — REFUSED. A guard looked and the answer is no. The named
                     condition is real and will hold on a re-run: fix it. A
                     driver working a wave blocks THIS fork and continues.
  2  [forks] USAGE — the invocation is malformed; nothing was inspected.
  3  [forks] ERROR — NO VERDICT. A guard could not look, or an action failed.
                     Infer NOTHING about the fork. Read the underlying tool's
                     message printed above it. A driver working a wave STOPS,
                     because an ERROR usually indicts something shared.
On FAIL the fork still exists and stays OWNED; on ERROR it may be in a state no
guard has described (see fork-deletion-failed). These are brief-check.sh's codes.
EOF
  exit 2
}

# ---------------------------------------------------------------------------
# EXIT CODES — A REFUSAL AND A BREAKAGE ARE DIFFERENT ANSWERS, so they get
# different codes and different prefixes. They used to share both: every guard
# in this file printed `[forks] FAIL —` and exited 1, including the three that
# said in their own message "that is an ERROR, not a verdict". A caller reading
# `$?` could not tell them apart, and neither could one reading stderr.
#
#   0  the command did what it was asked
#   1  [forks] FAIL  — REFUSED. A guard looked, and the answer is no.
#   2  [forks] USAGE — the invocation is malformed; nothing was inspected.
#   3  [forks] ERROR — NO VERDICT WAS REACHED. A guard could not look, or an
#                      action this command had to perform failed.
#
# THESE ARE brief-check.sh's CODES ON PURPOSE. cmd_claim already had to
# special-case that script's exit 3 so an internal error there would not arrive
# here as a clean brief; having done that, emitting a DIFFERENT code for the
# same distinction would leave a coordinator pipeline remembering which of two
# neighbouring tools uses which number. One rule, both scripts.
#
# THE RULE THAT DECIDES A SITE, mechanical enough to apply to a new one: was the
# failing operation an INSPECTION whose result IS the answer, or an ACTION this
# command needed to perform?
#
#   inspection, result is the answer    -> FAIL   (`git status` reported residue)
#   inspection, machinery failed        -> ERROR  (`git status` itself failed)
#   inspection, result uninterpretable  -> ERROR  (a manifest state we cannot parse)
#   action failed                       -> ERROR  (clone, commit, cp, rm, bundle)
#
# IT IS NOT "a re-check that fails is an ERROR". That mechanical shorthand fits
# scratch-cleanup-failed and gets `cited source vanished between check and ship`
# exactly wrong; both call sites carry the argument.
#
# WHAT A CALLER DOES DIFFERENTLY, because a distinction nobody acts on is
# decoration:
#
#   FAIL (1)  The named condition is REAL and will still hold on a re-run. Fix
#             it, or accept the verdict. A driver working a wave marks THIS fork
#             blocked and carries on with the others.
#
#   ERROR (3) Infer NOTHING about the fork from this — not that it is clean, not
#             that it is dirty, not that it is releasable. The underlying tool's
#             own message is printed above; read that one. A driver working a
#             wave STOPS, because an ERROR usually indicts something SHARED (the
#             state dir, git, the disk, the toolchain), so the next fork's
#             verdict cannot be trusted either.
#
# AND THE STATE THEY LEAVE BEHIND DIFFERS. On FAIL the fork still exists and
# stays OWNED. On ERROR it may be in a state no guard has described: the final
# `rm -rf "$fork"` is an ACTION, and `rm -rf` removes what it can before it
# fails, so fork-deletion-failed reports a fork shredded down to whatever
# refused. Inspect before retrying.
#
# ONE FAIL SITE IS NOT AS TIDY AS THAT SENTENCE, and saying so here is cheaper
# than the next reader finding out: the POST-PRESERVATION residue refusal fires
# after `.fork-scratch` has already been removed and its tracked content
# restored. It is still a verdict — the guard looked and found residue — but it
# has consumed untracked scratch by the time it speaks.
#
# ERROR SITES CARRY A CLAUSE ID; FAIL SITES KEEP THEIR PROSE. Both halves are
# deliberate. The ERROR text is new, so a greppable id costs nothing; rewriting
# the FAIL messages would break greps in tools/claude/brief_check_test.sh, which
# asserts this script's refusal text, and a canary broken to tidy a message is a
# bad trade. `unclearable-residue` is the one id that had to be SPLIT — the scan
# FAILING and the scan FINDING BLOCKERS shared it, so no grep could separate
# them even before the exit codes could not.
# ---------------------------------------------------------------------------
fail() {
  printf '[forks] FAIL — %s\n' "$*" >&2
  exit 1
}

# A BREAKAGE. Never call this where a guard reached an answer, however
# unwelcome that answer is.
error() {
  printf '[forks] ERROR — %s\n' "$*" >&2
  exit 3
}

cleanup() {
  if [ "$LOCK_HELD" -eq 1 ]; then
    rm -f -- "$LOCK_DIR/pid"
    rmdir -- "$LOCK_DIR" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# FAIL, NOT USAGE, and that was a decision rather than an omission. An empty
# task id or one carrying a tab is a malformed ARGUMENT, so exit 2 is arguable —
# but the split this file is built on is what the CALLER DOES NEXT, and it is
# the same here as for any other refusal: the input is not acceptable, do not
# retry it, fix it and re-run. Exit 2 is reserved for the case where the answer
# is the usage block itself (a wrong subcommand, a wrong argument count), and
# these messages are diagnoses instead. A tab or newline here would also corrupt
# the TSV manifest, which makes this a verdict about the state of the world and
# not merely about the command line.
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

# A HELD LOCK IS A VERDICT; A mkdir THAT FAILED FOR ANY OTHER REASON IS NOT, and
# this function used to report them as the same thing. `mkdir "$LOCK_DIR"`
# returns non-zero for EEXIST — the answer "another coordinator holds it" — and
# also for ENOSPC, EACCES and EROFS on the state directory, where no lock exists
# and none was inspected. The old code took every one of those to the same
# `manifest is locked by pid unknown` refusal: a FAIL naming a holder that does
# not exist, sending the operator to look for a coordinator instead of at a full
# disk. Which one it was is decidable after the fact, and cheaply — EEXIST is
# the only branch that leaves the directory THERE.
acquire_lock() {
  mkdir -p -- "$STATE_DIR" ||
    error "lock-create-failed: could not create the coordinator state directory $STATE_DIR; no claim was inspected and no lock is held"
  if ! mkdir -- "$LOCK_DIR" 2>/dev/null; then
    [ -d "$LOCK_DIR" ] ||
      error "lock-create-failed: could not create $LOCK_DIR and it does not exist, so nothing holds it; this is a fault of the state directory, not a live coordinator"
    local holder="unknown"
    if [ -r "$LOCK_DIR/pid" ]; then
      IFS= read -r holder < "$LOCK_DIR/pid" || holder="unknown"
    fi
    fail "manifest is locked by pid $holder; refuse to guess whether a coordinator is live"
  fi
  LOCK_HELD=1
  printf '%s\n' "$$" > "$LOCK_DIR/pid"
}

# The manifest is this script's own state. Writing it is an ACTION, and an
# unguarded one aborted under `set -e` with no [forks] line at all — which reads
# to a caller as a FAIL whose message got lost. append_record in particular runs
# in cmd_release AFTER `rm -rf "$fork"`, so a silent abort there leaves the fork
# deleted and the manifest still saying OWNED.
ensure_manifest() {
  [ -f "$MANIFEST" ] && return
  local tmp
  tmp="$(mktemp "$STATE_DIR/manifest.XXXXXX")" ||
    error "manifest-write-failed: could not create a temporary file in $STATE_DIR"
  manifest_header > "$tmp" ||
    error "manifest-write-failed: could not write the manifest header to $tmp"
  mv -- "$tmp" "$MANIFEST" ||
    error "manifest-write-failed: could not move $tmp into place at $MANIFEST"
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
    error "ship-list-failed: brief-check ship-list FAILED (exit $list_status) for $brief; that is an ERROR, not an empty ship list — the fork is retained and must not be dispatched: $fork"

  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    assert_shippable_path "$rel" "$fork"
    # Re-checked here rather than trusted from the check pass: claim holds the
    # manifest lock, not a lock on the filesystem.
    #
    # A FAIL, THOUGH AN EARLIER CHECK SAID YES — which is the shorthand
    # "a re-check that fails is an ERROR" refusing to hold. The shorthand fits
    # scratch-cleanup-failed, where the guard that OWNS removability had already
    # ruled and reality falsified its ruling. Here the earlier yes came from a
    # DIFFERENT guard answering a DIFFERENT question at a different time, and
    # the comment above says outright that this script never held a lock on the
    # filesystem — so no invariant of ours has been broken. `[ -f ]` is an
    # INSPECTION and its result IS the answer: the cited source is not there.
    # The operator's move is the refusal move, restore the file or fix the brief
    # and re-run, not the ERROR move of going to look at the machinery.
    [ -f "$ROOT/$rel" ] ||
      fail "cited source vanished between check and ship: $rel; the fork is retained and must not be dispatched: $fork"
    dest="$fork/$rel"
    mkdir -p -- "$(dirname -- "$dest")" ||
      error "ship-copy-failed: could not create $(dirname -- "$dest") for cited source $rel; the fork is retained and must not be dispatched: $fork"
    cp -p -- "$ROOT/$rel" "$dest" ||
      error "ship-copy-failed: could not ship cited source $rel into $fork; the fork is retained and must not be dispatched"
    shipped+=("$rel")
    count=$((count + 1))
  done <<< "$list"

  record="$(shipped_record_path "$fork")"
  mkdir -p -- "$(dirname -- "$record")" ||
    error "shipped-record-failed: could not create the shipped-source record directory for $fork"
  : > "$record" ||
    error "shipped-record-failed: could not record the shipped sources for $fork at $record"
  mkdir -p -- "$fork/.fork-scratch" ||
    error "shipped-record-failed: could not create $fork/.fork-scratch"
  : > "$fork/$SHIPPED_MANIFEST_REL" ||
    error "shipped-record-failed: could not write the in-fork shipped-source list at $fork/$SHIPPED_MANIFEST_REL"
  if [ "$count" -gt 0 ]; then
    printf '%s\n' "${shipped[@]}" > "$record"
    printf '%s\n' "${shipped[@]}" > "$fork/$SHIPPED_MANIFEST_REL"
  fi
  SHIPPED_COUNT="$count"
}

# AN UNREADABLE ANSWER IS NOT AN ANSWER. The question here is "would a claim
# collide", and a state token this script does not recognise means the manifest
# has been hand-edited, truncated, or written by a version that never existed.
# The guard cannot say yes and cannot say no, so it says neither.
state_blocks_claim() {
  case "$1" in
    OWNED | RELEASED | LIFTED) return 0 ;;
    GCd) return 1 ;;
    *) error "manifest-state-invalid: $MANIFEST contains a state this script cannot interpret: '$1'; no collision verdict was reached" ;;
  esac
}

append_record() {
  local path="$1" task="$2" owner="$3" brief="$4"
  local claimed_at="$5" state="$6" signal="$7" tmp
  # NOT A VERDICT ABOUT ANYTHING THE OPERATOR DID. Every caller of this function
  # is inside this file and passes a literal, so reaching this line means
  # forks.sh has a bug. There is no world the operator can change to make it
  # pass, which is the tell.
  case "$state" in
    OWNED | RELEASED | LIFTED | GCd) ;;
    *) error "internal-invalid-state: append_record was called with '$state', which is a defect in forks.sh itself" ;;
  esac
  tmp="$(mktemp "$STATE_DIR/manifest.XXXXXX")" ||
    error "manifest-write-failed: could not create a temporary file in $STATE_DIR; the manifest was NOT updated"
  cp -- "$MANIFEST" "$tmp" ||
    error "manifest-write-failed: could not copy $MANIFEST to $tmp; the manifest was NOT updated"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$path" "$task" "$owner" "$brief" "$claimed_at" "$state" "$signal" >> "$tmp" ||
    error "manifest-write-failed: could not append the '$state' record to $tmp; the manifest was NOT updated"
  mv -- "$tmp" "$MANIFEST" ||
    error "manifest-write-failed: could not move $tmp into place at $MANIFEST; the manifest was NOT updated"
}

assert_clone_links() {
  local fork="$1" bad resolved link

  # THE SAME find, TWICE OVER, IN TWO CLASSES. A find that RAN and printed a
  # path is an inspection whose result is the answer — FAIL. A find that DIED
  # prints nothing, which is byte-identical to "this clone is clean", so its
  # exit status is the only thing separating a verdict from silence — ERROR.
  if ! bad="$(find "$fork" -type l -lname '/*' -print -quit)"; then
    error "symlink-scan-failed: could not inspect $fork for absolute symlinks; the clone is UNJUDGED and must not be dispatched"
  fi
  [ -z "$bad" ] || fail "clone contains an absolute symlink: $bad"

  if ! bad="$(find "$fork" -xtype l -print -quit)"; then
    error "symlink-scan-failed: could not inspect $fork for dangling symlinks; the clone is UNJUDGED and must not be dispatched"
  fi
  [ -z "$bad" ] || fail "clone contains a dangling symlink: $bad"

  while IFS= read -r -d '' link; do
    # A dangling link was already refused above, so a readlink that fails HERE
    # is not the "target does not exist" case: it is the resolver itself giving
    # up, and no containment verdict follows from it.
    if ! resolved="$(readlink -f -- "$link")"; then
      error "symlink-resolve-failed: cannot resolve $link, so it cannot be judged against the clone root; the clone must not be dispatched"
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
  # UNVERIFIED IS NOT REFUTED, and this is the sharpest pair in the file: the
  # clause just above reports a push that SUCCEEDED, which is a verdict, and a
  # damning one. This clause reports a push that failed for a reason we do not
  # recognise — so the safety property the whole donation scheme rests on was
  # neither confirmed nor denied. Its own word for itself has always been
  # "unverified"; the exit code now agrees. The operator's move is to read git's
  # actual output, printed immediately above, which is the ERROR move.
  case "$push_output" in
    *"No configured push destination"* | *"No remote configured to push to"*) ;;
    *)
      printf '%s\n' "$push_output" | sed 's/^/  /' >&2
      error "remote-strip-unverified: git push failed for a reason this script does not recognise, so the strip is neither confirmed nor refuted: $fork"
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
  # THE GATE IS THE MACHINERY, NOT THE SUBJECT. `[ -x ]` is an inspection with a
  # perfectly legible result, but it is not an answer to the question claim is
  # asking — "is this brief acceptable?" — which goes unanswered. The message
  # has always said "unchecked"; that is an ERROR by construction.
  [ -x "$BRIEF_CHECK" ] ||
    error "brief-gate-missing: the brief gate is missing or not executable: $BRIEF_CHECK; refusing to dispatch an unchecked brief"
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
    # PROPAGATED IN KIND, which is the whole point of sharing brief-check's
    # code set: its 1 is a verdict about the brief and stays a FAIL here, its 2
    # and 3 are not verdicts and stay ERRORs here. The message text of this FAIL
    # is asserted by tools/claude/brief_check_test.sh; leave it alone.
    1) fail "brief-check REFUSED $brief; nothing was cloned and no fork exists" ;;
    *) error "brief-gate-no-verdict: brief-check could not reach a verdict on $brief (exit $check_status); that is an ERROR, not a clean brief" ;;
  esac

  if ! env -u GIT_DIR -u GIT_WORK_TREE git clone --quiet --no-hardlinks "$ROOT" "$fork"; then
    error "clone-failed: git clone of $ROOT did not complete; any partial path is preserved for inspection: $fork"
  fi
  if ! git_in "$fork" remote remove origin 2>/dev/null; then
    error "origin-strip-failed: could not remove the origin remote; the fork is retained and must not be dispatched: $fork"
  fi
  verify_remote_is_stripped "$fork"

  claimed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  base="$(git_in "$fork" rev-parse --short HEAD)"
  cp -- "$brief" "$fork/DONATION_BRIEF.md" ||
    error "seed-write-failed: could not copy the brief into $fork; the fork is retained and must not be dispatched"
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
  } > "$fork/DONATION_OWNER.md" ||
    error "seed-write-failed: could not write the owner marker into $fork; the fork is retained and must not be dispatched"
  mkdir -p -- "$fork/.fork-scratch" ||
    error "seed-write-failed: could not create $fork/.fork-scratch; the fork is retained and must not be dispatched"

  git_in "$fork" add DONATION_BRIEF.md DONATION_OWNER.md ||
    error "seed-commit-failed: could not stage the owner marker; the fork is retained and must not be dispatched: $fork"
  if ! git_in "$fork" -c user.name=protogen -c user.email=protogen@localhost \
    commit --quiet -m "donate: seed brief and owner for '$task' (owner: $owner)"; then
    error "seed-commit-failed: the owner-marker seed commit did not complete; the fork is retained and must not be dispatched: $fork"
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
    # PRESERVATION IS AN ACTION, AND ITS FAILURE USED TO BE SILENT. Unguarded,
    # `set -e` aborted here with no [forks] line — a bare non-zero exit that a
    # caller reads as a refusal whose message got lost, moments before release
    # would have deleted the very file it failed to copy.
    mkdir -p -- "$(dirname -- "$destination")" ||
      error "preserve-failed: could not create $(dirname -- "$destination") to preserve $source; the fork is retained and nothing was deleted"
    cp -p -- "$source" "$destination" ||
      error "preserve-failed: could not preserve $source to $destination; the fork is retained and nothing was deleted"
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
  #
  # AND IT GETS ITS OWN CLAUSE ID. This used to be reported as
  # `unclearable-residue` at exit 1 — the SAME id and the SAME code as the
  # refusal a hundred lines below, which is the finding this scan exists to
  # report. A caller could separate a scan that died from a scan that found
  # blockers by neither `$?` nor a grep of stderr, and the two demand opposite
  # responses: one says clear the named directories, the other says nothing was
  # judged at all.
  [ "$status" -eq 0 ] ||
    error "clearability-scan-failed: the clearability scan of $fork FAILED (find exit $status); no directory was judged, so this is NOT a clearable fork and NOT an unclearable one — nothing was deleted"

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

  # THE SCAN RAN AND FOUND SOMETHING — an inspection whose result IS the answer,
  # so a FAIL. It names directories, and clearing them is a thing the operator
  # can do; contrast clearability-scan-failed above, where the same function
  # reached no answer at all. Emitted as a multi-line body rather than through
  # fail(), which takes one line; same class, same code, same prefix.
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

  # BOTH CLASSES, ONE INSPECTION. A git status that RAN and printed residue is
  # the answer — the FAIL below. A git status that DIED prints nothing, which is
  # byte-identical to a clean tree, and reading that as "clean" would delete a
  # worker's uncommitted work on the strength of a check that never ran.
  if ! residue="$(git_in "$fork" status --porcelain --untracked-files=all)"; then
    error "residue-scan-failed: git status failed in $fork; this is an ERROR, not evidence that release is safe — nothing was deleted"
  fi
  if ! outside_residue="$(git_in "$fork" status --porcelain --untracked-files=all -- \
    . ':(exclude).fork-scratch' ':(exclude).fork-scratch/**' \
    ${shipped_excludes+"${shipped_excludes[@]}"})"; then
    error "residue-scan-failed: the scoped git status failed in $fork; this is an ERROR, not evidence that release is safe — nothing was deleted"
  fi
  # A FAIL emitted as a multi-line body rather than through fail(), which takes
  # one line. Same class, same code, same prefix.
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
  mkdir -p -- "$preserve_dir" ||
    error "preserve-failed: could not create the preservation directory $preserve_dir; the fork is retained and nothing was deleted"
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
    mkdir -p -- "$(dirname -- "$report_dest")" ||
      error "report-copy-failed: could not create $(dirname -- "$report_dest"); the fork is retained: $fork"
    # cat-file -e SAID IT IS THERE. Failing to extract it after that is the
    # extraction machinery giving up, not a verdict about the fork — and the
    # absence case above is deliberately not an error at all.
    if git_in "$fork" show master:FINAL_REPORT.md > "$report_dest" 2>/dev/null &&
       [ -s "$report_dest" ]; then
      REPORT_PRESERVED="$report_dest ($(wc -l < "$report_dest" | tr -d ' ') lines)"
    else
      error "report-copy-failed: FINAL_REPORT.md exists at master but could not be copied out; the fork is retained: $fork"
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
  #
  # THE CONTESTED ONE, AND IT IS AN ERROR. Read on its outcome alone it looks
  # like a refusal: nothing is deleted, the fork is retained, it stays OWNED —
  # exactly what a FAIL leaves behind. But the outcome is not the axis. Two
  # things decide it, and they agree.
  #
  # FIRST, `rm -rf` IS AN ACTION, not an inspection. Its failure answers no
  # question release was asking; it reports that a step release had already been
  # cleared to take did not go through.
  #
  # SECOND, THE GUARD THAT OWNS THIS QUESTION ALREADY SAID YES.
  # assert_fork_is_clearable ran a few lines up and ruled this fork clearable.
  # Reaching here means that ruling was falsified — by a writer racing the scan,
  # a mount point, or an I/O fault — and a guard whose verdict reality has just
  # contradicted is broken machinery, not a second opinion.
  #
  # The message settles it either way, because it cannot name a condition to
  # fix: it says SUSPECT a race, a mount, an I/O error. "Go and find out what is
  # wrong" is the ERROR instruction. A refusal names the thing and expects it
  # gone on the re-run.
  #
  # (When release itself runs as uid 0 the scan is vacuous rather than wrong,
  # since `test -w` is always true for root. The classification is unchanged:
  # what is left to reach this line is still EROFS, EIO or a mount, none of them
  # a legible verdict about the fork.)
  #
  # `rm -rf` REMOVES WHAT IT CAN BEFORE IT FAILS, so scratch may be PARTLY gone
  # by the time this speaks — one more reason a caller must inspect rather than
  # assume the fork is as it left it.
  if ! rm -rf -- "$fork/.fork-scratch"; then
    error "scratch-cleanup-failed: could not remove $fork/.fork-scratch; the fork is retained and stays OWNED, and scratch may be PARTLY removed because rm deletes what it can before it fails. rm's own message is above. The clearability scan passed, so this is NOT the unwritable-directory class it screens for — suspect a writer racing this command, a mount point, or an I/O error."
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
    error "post-residue-scan-failed: the post-preservation git status failed in $fork; the fork remains in place and its residue is UNJUDGED"
  fi
  # A FAIL — and the one FAIL in this file that has already consumed something.
  # `.fork-scratch` is gone by now (its script-like proofs preserved, its
  # tracked content restored), so "a refusal leaves the fork as it found it" is
  # true of every other FAIL site and not of this one.
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
    error "bundle-create-failed: could not preserve the fork's committed work; the fork remains in place"
  fi
  # NOT A VERDICT ABOUT THE FORK. The bundle is an artifact this command created
  # two lines ago, so a bundle that does not verify indicts the preservation
  # step, not the worker's tree.
  if ! git_in "$fork" bundle verify "$bundle" >/dev/null; then
    error "bundle-verify-failed: the bundle this run just created does not verify; the fork remains in place"
  fi

  # THE WORST STATE THIS SCRIPT CAN REACH, and the clearest ERROR in it. Every
  # guard has passed, the deletion was authorised, and `rm -rf` removed what it
  # could before failing — so the fork is now shredded down to whatever refused,
  # the manifest still says OWNED, and NO guard's verdict describes what is on
  # disk. There is nothing here for a caller to "fix and re-run"; the bundle
  # beside this message is the intact copy.
  if ! rm -rf -- "$fork"; then
    error "fork-deletion-failed: rm -rf of $fork did not complete, and rm deletes what it can before it fails — the fork is PARTIALLY DELETED and no verdict describes its current state. The manifest remains OWNED for fail-safe recovery and the committed work is in the bundle above."
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
