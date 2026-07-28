#!/usr/bin/env bash
# uber_chown_test.sh — canaries for the CHOWN-BACK REPORT in tools/uber.sh.
#
# WHAT IS UNDER TEST. uber.sh wraps every containerised command so the bind
# mount is chowned back to the invoking uid/gid on the way out. That chown used
# to be `chown -R … 2>/dev/null || true`: a failure printed nothing and changed
# nothing, so root-owned residue in a checkout was undetectable by the caller,
# by CI and by every gate. The wrapper now captures the chown status and its
# stderr and REPORTS a failure, while deliberately leaving the run status alone.
# These canaries pin both halves: it must say so when the chown fails, and it
# must not touch the exit status either way.
#
# THE PAYLOAD IS NOT COPIED — IT IS CAPTURED FROM THE REAL SCRIPT. A canary
# holding its own transcription of the wrapper would assert the author model of
# it and stay green through any edit to the production text. So a stub `docker`
# is put first on PATH, tools/uber.sh is RUN, and the exact argv it hands docker
# is recorded; the payload executed below is the string that run passed after
# `-lc`. The stub also proves it was reached (a marker file): if the real docker
# were found instead, the cases would be judging nothing and must ERROR rather
# than pass.
#
# THE ONE SUBSTITUTION, NAMED. A hermetic case cannot create `/workspace` on the
# host, so it overrides the `UBER_WORKSPACE` value uber.sh passes. That is the
# whole reason the mount path became one variable rather than five literals.
# Everything else — the payload text, the env var NAMES, the uid/gid — is
# production. The substitution is closed by the last case, which runs the same
# captured payload in the REAL pinned container against the REAL /workspace with
# a read-only submount, and is reported UNJUDGED (never green) when docker or
# the image is absent.
#
# FAIL IS NOT ERROR, AND A RED MUST NAME ITS CLAUSE. Each mutant is `bash -n`
# checked before use and its mutation is asserted to have LANDED — the new text
# present AND the old text gone — because a sed that matched nothing produces a
# mutant identical to the original whose green then reads as attribution while
# proving the opposite. Each mutant must flip the REPORT canaries and leave the
# two status-propagation CONTROLS green; a control that flips means the red came
# from somewhere other than the clause under test and this suite fails on it.
#
# THREE COUNTS, NOT TWO. A case whose precondition does not hold is UNJUDGED and
# says so; "nothing ran" must never read as "all green".
#
# Usage: tools/uber_chown_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/uber.sh"
IMG="jettison-proto-generator-base:latest"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/uber-chown-test.XXXXXX")"
trap 'chmod -R u+rwX -- "$WORK" 2>/dev/null || true; rm -rf -- "$WORK"' EXIT

PASS=0
FAIL=0
UNJUDGED=0

ok()       { PASS=$((PASS + 1));     printf '  \033[32mok\033[0m       %s\n' "$*"; }
bad()      { FAIL=$((FAIL + 1));     printf '  \033[31mFAIL\033[0m     %s\n' "$*"; }
unjudged() { UNJUDGED=$((UNJUDGED + 1)); printf '  \033[33mUNJUDGED\033[0m %s\n' "$*"; }
note()     { printf '           %s\n' "$*"; }

UID_NOW="$(id -u)"
GID_NOW="$(id -g)"

# ---------------------------------------------------------------------------
# The stub docker, and the capture it performs.
# ---------------------------------------------------------------------------
STUB_BIN="$WORK/stubbin"
mkdir -p "$STUB_BIN"
cat > "$STUB_BIN/docker" <<'STUB'
#!/usr/bin/env bash
# Stub docker for uber_chown_test.sh. It answers `image inspect` so uber.sh
# believes the image is present, and RECORDS a `run` instead of performing one.
set -euo pipefail
case "${1:-}" in
  image) exit 0 ;;
  run)   ;;
  *)     printf 'stub docker: unexpected subcommand: %s\n' "$*" >&2; exit 99 ;;
esac
: > "$UBER_STUB_MARKER"
printf '%s\n' "$@" > "$UBER_STUB_ARGV"
: > "$UBER_STUB_SCRIPT"
prev=""
for arg in "$@"; do
  if [ "$prev" = "-lc" ]; then printf '%s' "$arg" > "$UBER_STUB_SCRIPT"; fi
  prev="$arg"
done
exit 0
STUB
chmod +x "$STUB_BIN/docker"

# capture <uber.sh path> <tag> -> $WORK/<tag>.script, $WORK/<tag>.argv
# Returns non-zero (and reports) when the stub was not the docker that ran, or
# when no `-lc` payload was passed at all. Both are ERRORs, not verdicts.
capture() {
  local script="$1" tag="$2"
  local marker="$WORK/$tag.marker"
  rm -f -- "$marker"
  if ! PATH="$STUB_BIN:$PATH" \
       UBER_STUB_MARKER="$marker" \
       UBER_STUB_ARGV="$WORK/$tag.argv" \
       UBER_STUB_SCRIPT="$WORK/$tag.script" \
       bash "$script" true >"$WORK/$tag.capture.out" 2>&1; then
    bad "capture[$tag]: running $script under the stub docker failed"
    note "$(head -3 "$WORK/$tag.capture.out")"
    return 1
  fi
  if [ ! -e "$marker" ]; then
    bad "capture[$tag]: the stub docker was never reached — a real docker may have run"
    return 1
  fi
  if [ ! -s "$WORK/$tag.script" ]; then
    bad "capture[$tag]: no -lc payload was captured from $script"
    return 1
  fi
  return 0
}

# run_payload <script-file> <tag> <workspace> <uber_cmd> <uid> <gid>
# Leaves stdout/stderr in $WORK/<tag>.out / .err and the status in RP_STATUS.
RP_STATUS=0
run_payload() {
  local payload="$1" tag="$2" ws="$3" cmd="$4" uid="$5" gid="$6"
  RP_STATUS=0
  env UBER_CMD="$cmd" UBER_UID="$uid" UBER_GID="$gid" UBER_WORKSPACE="$ws" \
    bash -lc "$(cat "$payload")" >"$WORK/$tag.out" 2>"$WORK/$tag.err" || RP_STATUS=$?
}

has()  { grep -qF -- "$2" "$1"; }

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
WS_OK="$WORK/ws-ok"
mkdir -p "$WS_OK/sub"
: > "$WS_OK/sub/f"
WS_GONE="$WORK/ws-does-not-exist"

printf '\n\033[1muber_chown_test.sh\033[0m — the chown-back must report, and must not touch the status\n\n'
printf 'capture — the payload under test comes from %s\n' "$SUT"

if ! capture "$SUT" prod; then
  printf '\n  the payload could not be captured; every case below would be vacuous\n\n'
  exit 1
fi
PAYLOAD="$WORK/prod.script"
ok "captured the -lc payload uber.sh hands docker ($(wc -c <"$PAYLOAD") bytes)"

# ---------------------------------------------------------------------------
# CASE mount-single-source — the mount destination, -w, and UBER_WORKSPACE agree
# ---------------------------------------------------------------------------
printf '\ncase mount-single-source\n'
ARGV="$WORK/prod.argv"
WS_DECLARED="$(awk '/^-e$/ { want=1; next } want { if ($0 ~ /^UBER_WORKSPACE=/) { sub(/^UBER_WORKSPACE=/, "", $0); print; exit } want=0 }' "$ARGV")"
MOUNT_DEST="$(awk '/^-v$/ { getline v; n=split(v, p, ":"); if (p[n] !~ /^(ro|rw)$/) print p[n]; else print p[n-1] }' "$ARGV" | head -1)"
WORKDIR="$(awk '/^-w$/ { getline; print; exit }' "$ARGV")"
if [ -n "$WS_DECLARED" ] && [ "$WS_DECLARED" = "$MOUNT_DEST" ] && [ "$WS_DECLARED" = "$WORKDIR" ]; then
  ok "UBER_WORKSPACE=$WS_DECLARED equals the mount destination and -w"
else
  bad "mount-single-source: UBER_WORKSPACE=[$WS_DECLARED] mount=[$MOUNT_DEST] -w=[$WORKDIR] disagree"
fi

# ---------------------------------------------------------------------------
# CONTROL quiet-on-success — a chown that works says NOTHING and returns 0
# (This control must stay GREEN under every mutant below: a silenced report is
# indistinguishable from a working one HERE, which is exactly what makes it a
# control rather than a second canary.)
# ---------------------------------------------------------------------------
printf '\ncontrol quiet-on-success\n'
run_payload "$PAYLOAD" ctl-quiet "$WS_OK" "exit 0" "$UID_NOW" "$GID_NOW"
if [ "$RP_STATUS" -ne 0 ]; then
  bad "quiet-on-success: a successful command exited $RP_STATUS"
elif [ -s "$WORK/ctl-quiet.err" ]; then
  bad "quiet-on-success: a successful chown-back wrote to stderr:"
  note "$(head -3 "$WORK/ctl-quiet.err")"
else
  ok "successful command + successful chown: exit 0, stderr empty"
fi

# ---------------------------------------------------------------------------
# CONTROL status-propagates — the command status is what the run returns
# ---------------------------------------------------------------------------
printf '\ncontrol status-propagates\n'
run_payload "$PAYLOAD" ctl-status "$WS_OK" "exit 7" "$UID_NOW" "$GID_NOW"
if [ "$RP_STATUS" -eq 7 ]; then
  ok "a command exiting 7 makes the run exit 7"
else
  bad "status-propagates: expected 7, got $RP_STATUS"
fi

# ---------------------------------------------------------------------------
# CANARY reports-failure — THE CLAUSE UNDER TEST
# ---------------------------------------------------------------------------
printf '\ncanary reports-failure\n'
run_payload "$PAYLOAD" canary-report "$WS_GONE" "exit 0" "$UID_NOW" "$GID_NOW"
report_seen=0
if has "$WORK/canary-report.err" "chown-back FAILED"; then
  report_seen=1
  ok "a failing chown-back is announced on stderr"
else
  bad "reports-failure: chown failed and the run said nothing — this is the silence the change removes"
  note "stderr was: [$(head -2 "$WORK/canary-report.err")]"
fi
if has "$WORK/canary-report.err" "No such file or directory"; then
  ok "the report carries chown OWN message, not a paraphrase"
else
  bad "reports-failure/message: chown own stderr is missing from the report"
fi
if has "$WORK/canary-report.err" "tools/uber.sh true"; then
  ok "the report names the repair"
else
  bad "reports-failure/repair: the report does not name the repair command"
fi

# ---------------------------------------------------------------------------
# CANARY reports-without-masking — a chown failure changes no status, in either
# direction: it must not turn a good run bad, nor a bad run good.
# ---------------------------------------------------------------------------
printf '\ncanary reports-without-masking\n'
if [ "$RP_STATUS" -eq 0 ]; then
  ok "a failing chown-back left a successful run at 0"
else
  bad "reports-without-masking: a failing chown-back changed a successful run to $RP_STATUS"
fi
run_payload "$PAYLOAD" canary-mask "$WS_GONE" "exit 7" "$UID_NOW" "$GID_NOW"
if [ "$RP_STATUS" -eq 7 ]; then
  ok "a failing chown-back did not swallow a command failure (status 7 kept)"
else
  bad "reports-without-masking/status: a failing chown-back moved a failing run from 7 to $RP_STATUS"
fi
if has "$WORK/canary-mask.err" "chown-back FAILED"; then
  ok "the chown failure is reported even when the command itself failed"
else
  bad "reports-without-masking/report: the chown failure went unreported because the command also failed"
fi

# ---------------------------------------------------------------------------
# CANARY lists-residue — the report NAMES what is still foreign-owned.
# THE HAZARD IS PROBED, NOT ASSUMED: this case needs a chown this process is
# refused. Run as root, chown to 0:0 succeeds and the case would be green having
# measured nothing, so it reports UNJUDGED instead.
# ---------------------------------------------------------------------------
printf '\ncanary lists-residue\n'
PROBE="$WORK/probe-file"
: > "$PROBE"
if chown 0:0 -- "$PROBE" 2>/dev/null; then
  unjudged "lists-residue: this process can chown to 0:0 (running as root?) — no refusal to observe"
else
  run_payload "$PAYLOAD" canary-residue "$WS_OK" "exit 0" 0 0
  if has "$WORK/canary-residue.err" "Still not owned by 0:0" \
     && has "$WORK/canary-residue.err" "$WS_OK/sub/f"; then
    ok "the report lists the paths that are still foreign-owned"
  else
    bad "lists-residue: the residue listing is missing or does not name the file"
    note "$(head -12 "$WORK/canary-residue.err")"
  fi
fi

# ---------------------------------------------------------------------------
# MUTATION — break the clause alone, and watch the canaries flip while the
# controls hold. Two mutants: the report GUARD, and a verbatim restoration of
# the historical silenced one-liner.
# ---------------------------------------------------------------------------
mutant_root() { # tag -> prints the path of a mutant tree holding a copy of uber.sh
  local tag="$1"
  mkdir -p "$WORK/$tag/tools"
  cp -- "$SUT" "$WORK/$tag/tools/uber.sh"
  printf '%s' "$WORK/$tag/tools/uber.sh"
}

# assert_landed <mutant-file> <text-the-mutation-ADDS> <text-the-mutation-REMOVES>
#
# COUNTED AGAINST THE UNMUTATED SCRIPT, not against zero. The added text here is
# the historical silenced one-liner, which this file also QUOTES in a comment —
# so "present" is true before the mutation too, and a bare presence check would
# pass over a sed that matched nothing. The claim that means something is that
# the mutation ADDED one occurrence and REMOVED every occurrence of the other.
# The removed text is also required to exist in the original: if it does not,
# the mutation target has rotted and the mutant proves nothing.
assert_landed() {
  local f="$1" added="$2" removed="$3" base_added base_removed n_added n_removed
  base_added="$(grep -cF -- "$added" "$SUT" || true)"
  base_removed="$(grep -cF -- "$removed" "$SUT" || true)"
  n_added="$(grep -cF -- "$added" "$f" || true)"
  n_removed="$(grep -cF -- "$removed" "$f" || true)"
  if [ "$base_removed" -lt 1 ]; then
    bad "mutation target has rotted: [$removed] is not in $SUT at all"
    return 1
  fi
  if [ "$n_added" -ne "$((base_added + 1))" ]; then
    bad "mutation did not land: [$added] went $base_added -> $n_added, wanted $((base_added + 1))"
    return 1
  fi
  if [ "$n_removed" -ne 0 ]; then
    bad "mutation did not land: [$removed] survives ($n_removed occurrence(s)) in the mutant"
    return 1
  fi
  if ! bash -n "$f" 2>"$WORK/mutant.syntax"; then
    bad "mutant does not parse — that red would be an ERROR, not a FAIL"
    note "$(head -2 "$WORK/mutant.syntax")"
    return 1
  fi
  note "mutation landed: added $base_added -> $n_added, removed $base_removed -> 0, mutant parses"
  return 0
}

# check_mutant <payload> <label> <tag>: the report canary must be GONE and every
# control must still hold.
#
# A SILENCED PAYLOAD AND AN ABSENT ONE LOOK THE SAME FROM THE REPORT ALONE. An
# empty payload prints nothing on a failed chown, exits 0 on `exit 0`, and would
# satisfy a naive version of all three checks below while executing none of the
# production text. Two guards separate those: the captured mutant payload must
# DIFFER from the production one, and it must still run the command and
# propagate a non-zero status — an ERROR wearing the right colour, refused.
check_mutant() {
  local payload="$1" label="$2" tag="$3"
  if cmp -s -- "$payload" "$PAYLOAD"; then
    bad "$label: the captured mutant payload is identical to production — the mutation is not in what RAN"
    return
  fi
  run_payload "$payload" "$tag-report" "$WS_GONE" "exit 7" "$UID_NOW" "$GID_NOW"
  if [ "$RP_STATUS" -ne 7 ]; then
    bad "$label: the mutant payload did not run the command (status $RP_STATUS, want 7) — that is an ERROR, not the silencing under test"
    return
  fi
  ok "$label: CONTROL the mutant still runs the command and returns its status (7)"
  if has "$WORK/$tag-report.err" "chown-back FAILED"; then
    bad "$label: the report SURVIVED the mutation — the reports-failure canary cannot be attributed to this clause"
  else
    ok "$label: the reports-failure canary went red for its own reason (report absent)"
  fi
  run_payload "$payload" "$tag-quiet" "$WS_OK" "exit 0" "$UID_NOW" "$GID_NOW"
  if [ "$RP_STATUS" -eq 0 ] && [ ! -s "$WORK/$tag-quiet.err" ]; then
    ok "$label: CONTROL quiet-on-success still green"
  else
    bad "$label: CONTROL quiet-on-success flipped (status=$RP_STATUS) — the red is not attributable"
  fi
}

printf '\nmutation M1 — flip the report GUARD to `if false`\n'
M1="$(mutant_root m1)"
awk '{ if ($0 == "if [ \"$chown_rc\" -ne 0 ]; then") print "if false; then"; else print }' \
  "$M1" > "$M1.new" && mv -- "$M1.new" "$M1"
if assert_landed "$M1" 'if false; then' 'if [ "$chown_rc" -ne 0 ]; then'; then
  if capture "$M1" m1; then check_mutant "$WORK/m1.script" "M1" m1; fi
fi

printf '\nmutation M2 — restore the historical silenced one-liner verbatim\n'
M2="$(mutant_root m2)"
awk '
  /---8<--- chown-back report BEGIN/ { skip=1
    print "chown -R \"$UBER_UID:$UBER_GID\" \"$UBER_WORKSPACE\" 2>/dev/null || true"
    next }
  /---8<--- chown-back report END/   { skip=0; next }
  !skip { print }
' "$M2" > "$M2.new" && mv -- "$M2.new" "$M2"
chmod +x -- "$M2"
if assert_landed "$M2" 'chown -R "$UBER_UID:$UBER_GID" "$UBER_WORKSPACE" 2>/dev/null || true' \
                       'uber.sh: WARNING'; then
  if capture "$M2" m2; then check_mutant "$WORK/m2.script" "M2" m2; fi
fi

# ---------------------------------------------------------------------------
# CASE real-container — the same captured payload, the REAL pinned image, the
# REAL /workspace, and a genuine chown failure (a read-only submount).
# This is what closes the UBER_WORKSPACE substitution the hermetic cases make.
# Preconditions are PROBED; absent, this is UNJUDGED and never green.
# ---------------------------------------------------------------------------
printf '\ncase real-container\n'
REALWS="$WORK/realws"
mkdir -p "$REALWS/ro"
: > "$REALWS/ro/f"
: > "$REALWS/writable"
if ! command -v docker >/dev/null 2>&1; then
  unjudged "real-container: no docker on PATH (this is the normal state inside the uber container)"
elif ! docker image inspect "$IMG" >/dev/null 2>&1; then
  unjudged "real-container: the pinned image $IMG is not present; not building one from a lint lane"
elif ! docker run --rm -v "$REALWS:/probe" --entrypoint bash "$IMG" -c 'test -e /probe/writable' >/dev/null 2>&1; then
  unjudged "real-container: this daemon cannot bind-mount $REALWS, so the case would measure nothing"
else
  rc_real=0
  docker run --rm --entrypoint bash \
    -v "$REALWS:/workspace" -w /workspace \
    -v "$REALWS/ro:/workspace/ro:ro" \
    -e UBER_CMD="exit 0" -e UBER_UID="$UID_NOW" -e UBER_GID="$GID_NOW" \
    -e UBER_WORKSPACE=/workspace \
    "$IMG" -lc "$(cat "$PAYLOAD")" \
    >"$WORK/real.out" 2>"$WORK/real.err" || rc_real=$?
  if [ "$rc_real" -ne 0 ]; then
    bad "real-container: the run exited $rc_real; a chown failure must not change the status"
    note "$(head -3 "$WORK/real.err")"
  elif has "$WORK/real.err" "chown-back FAILED" && has "$WORK/real.err" "Read-only file system"; then
    ok "the real container reports a real chown failure and still exits 0"
  else
    bad "real-container: no report for a chown that really failed"
    note "stderr was: [$(head -3 "$WORK/real.err")]"
  fi
fi

# ---------------------------------------------------------------------------
printf '\n'
printf 'passed: %d   failed: %d   unjudged: %d\n' "$PASS" "$FAIL" "$UNJUDGED"
if [ "$FAIL" -gt 0 ]; then
  printf '\033[31m[uber-chown] FAIL\033[0m\n'
  exit 1
fi
if [ "$UNJUDGED" -gt 0 ]; then
  printf '\033[33m[uber-chown] no failures, but %d case(s) were UNJUDGED\033[0m — this is not ALL GREEN\n' "$UNJUDGED"
  exit 0
fi
printf '\033[32m[uber-chown] ALL GREEN\033[0m\n'
