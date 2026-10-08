#!/usr/bin/env bash
# uber_safe_directory_test.sh — canaries for the safe.directory declaration in
# tools/uber.sh: git inside the container must both DISCOVER the mounted
# checkout and LOCAL-CLONE it, and must still refuse a repository that is not
# the mount.
#
# WHAT IS UNDER TEST. The uber container runs as root over files the invoking
# user owns, so git refuses the checkout as dubiously owned unless uber.sh
# declares it safe. Git names the repository DIFFERENTLY on two paths, and a
# declaration that satisfies one can miss the other:
#   - DISCOVERY (`git ls-files` from the worktree) checks the WORKTREE path.
#   - A LOCAL CLONE checks the GITDIR path (`<worktree>/.git`), twice: once in
#     `git clone` itself and once in the `git-upload-pack` it starts in the
#     source repository. Git's local transport UNSETS GIT_CONFIG_COUNT and
#     GIT_CONFIG_PARAMETERS for that child (its `local_repo_env` list), so a
#     declaration made through those variables never reaches it at all.
# The reactive mutation campaign's worker clone died on exactly that, under a
# declaration that let every discovery-based lane pass.
#
# THE DECLARATION IS NOT COPIED — IT IS CAPTURED FROM THE REAL SCRIPT, the same
# way tools/uber_chown_test.sh captures the chown payload: a stub `docker` is
# first on PATH, uber.sh is RUN, and every `-e K=V` it hands docker plus the
# `-lc` payload are recorded. A replay then applies exactly that environment
# and that payload. A canary holding its own transcription of the declaration
# would assert the author's model of it.
#
# THE OWNERSHIP MISMATCH, HERMETICALLY. `GIT_TEST_ASSUME_DIFFERENT_OWNER=1` is
# git's own switch for treating every repository as foreign-owned, which is the
# condition uber.sh's container creates by running as root. It needs no root
# and no docker, and it is not in git's stripped list, so the upload-pack child
# sees it too. Ambient configuration is isolated (empty HOME, no system file,
# `GIT_CONFIG_GLOBAL=/dev/null` unless the replayed environment sets its own),
# so a developer's own `safe.directory` cannot turn a red green. The workspace
# path is the ONE substitution, as in the chown canary; the last case closes it
# by running uber.sh for REAL against a checkout owned by the invoking user.
#
# PRECONDITIONS ARE PROBED, NOT ASSUMED. Before any verdict, the same probes run
# with NO declaration and must be REFUSED as dubiously owned. A git that does
# not refuse cannot show the hazard, so every hermetic case would pass for the
# wrong reason: the suite then stops with CANNOT RUN (exit 3), naming which
# probe was not refused, rather than reporting the cases it could still reach.
# Only the real-container case may be UNJUDGED, as in uber_chown_test.sh, and
# the suite then says so rather than ALL GREEN. Each mutant must flip ITS
# clause's canary while the other canaries hold, after its mutation is shown to
# have LANDED and to parse.
#
# EXIT 0 judged (the real-container case possibly UNJUDGED) · 1 a FAIL ·
# 3 CANNOT RUN (no git, a git that shows no hazard, or no capturable payload).
#
# Usage: tools/uber_safe_directory_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/uber.sh"
IMG="jettison-proto-generator-base:latest"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/uber-safe-directory-test.XXXXXX")"
trap 'chmod -R u+rwX -- "$WORK" 2>/dev/null || true; rm -rf -- "$WORK"' EXIT

PASS=0
FAIL=0
UNJUDGED=0

ok()       { PASS=$((PASS + 1));     printf '  \033[32mok\033[0m       %s\n' "$*"; }
bad()      { FAIL=$((FAIL + 1));     printf '  \033[31mFAIL\033[0m     %s\n' "$*"; }
unjudged() { UNJUDGED=$((UNJUDGED + 1)); printf '  \033[33mUNJUDGED\033[0m %s\n' "$*"; }
note()     { printf '           %s\n' "$*"; }
has()      { grep -qF -- "$2" "$1"; }

cannot_run() {
  printf '\033[31m[uber-safe-directory] CANNOT RUN\033[0m — %s\n' "$1" >&2
  shift
  for line in "$@"; do printf '  %s\n' "$line" >&2; done
  exit 3
}

if ! command -v git >/dev/null 2>&1; then
  cannot_run "git is not on PATH" "install git (any version with safe.directory and" \
    "GIT_TEST_ASSUME_DIFFERENT_OWNER, 2.35.2 or later) to run this canary"
fi

# ---------------------------------------------------------------------------
# Fixtures: the workspace repository, and a second repository that is NOT the
# workspace (the narrowness canary must see it refused).
# ---------------------------------------------------------------------------
mkrepo() {
  local dir="$1"
  mkdir -p "$dir"
  (
    cd "$dir"
    env -i PATH="$PATH" HOME="$WORK" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
      git init -q .
    printf 'seed\n' > seed.txt
    env -i PATH="$PATH" HOME="$WORK" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
      git add seed.txt
    env -i PATH="$PATH" HOME="$WORK" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
      git -c user.name=canary -c user.email=canary@invalid commit -q -m seed
  )
}
WS="$WORK/ws"
OTHER="$WORK/other"
mkrepo "$WS"
mkrepo "$OTHER"
mkdir -p "$WORK/home" "$WORK/tmp"

# ---------------------------------------------------------------------------
# Capture: the stub docker records argv and the -lc payload instead of running.
# ---------------------------------------------------------------------------
STUB_BIN="$WORK/stubbin"
mkdir -p "$STUB_BIN"
cat > "$STUB_BIN/docker" <<'STUB'
#!/usr/bin/env bash
# Stub docker for uber_safe_directory_test.sh: answers `image inspect` so
# uber.sh believes the image is present, and RECORDS a `run`.
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

# capture <uber.sh path> <tag> -> $WORK/<tag>.argv, $WORK/<tag>.script, $WORK/<tag>.env
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
  # Every `-e K=V` pair, in order. A bare `-e K` (pass-through) is not a value
  # uber.sh declares, so it is not replayed.
  awk '/^-e$/ { getline v; if (v ~ /=/) print v }' "$WORK/$tag.argv" > "$WORK/$tag.env"
  return 0
}

# replay <tag> <capture-tag> <workspace> <uber_cmd> [K=V ...]
# Runs the captured payload under the captured environment, as root-over-a-
# foreign-checkout looks to git. Trailing K=V pairs are applied LAST, for the
# case that needs a broken temp directory. Leaves .out/.err and RP_STATUS.
RP_STATUS=0
replay() {
  local tag="$1" cap="$2" ws="$3" cmd="$4"
  shift 4
  local -a envs=(
    PATH="$PATH" HOME="$WORK/home" TMPDIR="$WORK/tmp"
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
  )
  # THE ONE SUBSTITUTION is the mount path, and it is applied to EVERY value
  # uber.sh derived from it, not only to UBER_WORKSPACE: a declaration that
  # bakes the literal mount path into a value would otherwise be judged against
  # a path the hermetic workspace does not have, and fail for that reason
  # instead of its own.
  local line key val mount
  mount="$(sed -n 's/^UBER_WORKSPACE=//p' "$WORK/$cap.env" | head -n 1)"
  while IFS= read -r line; do
    key="${line%%=*}"
    val="${line#*=}"
    if [ -n "$mount" ] && { [ "$val" = "$mount" ] || [ "${val#"$mount"/}" != "$val" ]; }; then
      val="$ws${val#"$mount"}"
    fi
    envs+=("$key=$val")
  done < "$WORK/$cap.env"
  envs+=(
    UBER_CMD="$cmd" UBER_WORKSPACE="$ws"
    UBER_UID="$(id -u)" UBER_GID="$(id -g)"
    UBER_TEST_DST="$WORK/dst-$tag"
    GIT_TEST_ASSUME_DIFFERENT_OWNER=1
    "$@"
  )
  rm -rf -- "$WORK/dst-$tag"
  RP_STATUS=0
  (cd "$ws" && env -i "${envs[@]}" bash -lc "$(cat "$WORK/$cap.script")") \
    >"$WORK/$tag.out" 2>"$WORK/$tag.err" || RP_STATUS=$?
}

# The three probes. Each is the shape a real lane uses.
DISCOVER='git ls-files --error-unmatch seed.txt'
CLONE='git clone -q --local --no-hardlinks "$UBER_WORKSPACE" "$UBER_TEST_DST" && test -f "$UBER_TEST_DST/seed.txt"'

# verdicts <capture-tag> <label> -> sets V_DISCOVER, V_CLONE, V_NARROW to pass|red
V_DISCOVER=red
V_CLONE=red
V_NARROW=red
verdicts() {
  local cap="$1" label="$2"
  replay "$label-discover" "$cap" "$WS" "$DISCOVER"
  if [ "$RP_STATUS" -eq 0 ]; then V_DISCOVER=pass; else V_DISCOVER=red; fi
  replay "$label-clone" "$cap" "$WS" "$CLONE"
  if [ "$RP_STATUS" -eq 0 ] && [ -f "$WORK/dst-$label-clone/seed.txt" ]; then V_CLONE=pass; else V_CLONE=red; fi
  # NARROWNESS: the declaration is for the MOUNT. Cloning a repository that is
  # not the workspace must still be refused, as dubious ownership specifically.
  replay "$label-narrow" "$cap" "$WS" \
    'git clone -q --local --no-hardlinks '"'$OTHER'"' "$UBER_TEST_DST"'
  if [ "$RP_STATUS" -ne 0 ] && has "$WORK/$label-narrow.err" "dubious ownership"; then
    V_NARROW=pass
  else
    V_NARROW=red
  fi
}

printf '\n\033[1muber_safe_directory_test.sh\033[0m — git must discover AND local-clone the mount, and nothing else\n'

# ---------------------------------------------------------------------------
# PRECONDITION — with NO declaration, this git must refuse all three probes.
# ---------------------------------------------------------------------------
printf '\nprecondition — the hazard exists on this git\n'
printf '' > "$WORK/bare.env"
printf 'bash -lc "$UBER_CMD"\n' > "$WORK/bare.script"
verdicts bare bare
missing=()
if [ "$V_DISCOVER" = red ] && has "$WORK/bare-discover.err" "dubious ownership"; then
  ok "undeclared discovery is refused as dubious ownership"
else
  missing+=("undeclared discovery of a foreign worktree was NOT refused")
fi
if [ "$V_CLONE" = red ] && has "$WORK/bare-clone.err" "dubious ownership"; then
  ok "undeclared local clone is refused as dubious ownership"
else
  missing+=("an undeclared local clone of a foreign repository was NOT refused")
fi
if [ "$V_NARROW" = pass ]; then
  ok "an undeclared non-workspace repository is refused (the narrowness canary can see a refusal)"
else
  missing+=("an undeclared clone of a foreign non-workspace repository was NOT refused")
fi
if [ "${#missing[@]}" -gt 0 ]; then
  cannot_run "this git shows no ownership hazard, so no case below could fail for its own reason" \
    "${missing[@]}" \
    "$(git --version) — it must honour GIT_TEST_ASSUME_DIFFERENT_OWNER and refuse a" \
    "foreign local clone (clone-side ownership checks); nothing below was judged"
fi

# ---------------------------------------------------------------------------
# PRODUCTION — the declaration uber.sh actually hands the container.
# ---------------------------------------------------------------------------
printf '\ncapture — the declaration under test comes from %s\n' "$SUT"
mkdir -p "$WORK/prod/tools"
cp -- "$SUT" "$WORK/prod/tools/uber.sh"
if ! UBER_NETWORK='' capture "$WORK/prod/tools/uber.sh" prod; then
  cannot_run "the production declaration could not be captured; every case below would be vacuous"
fi
ok "captured $(wc -l <"$WORK/prod.env") env pair(s) and a $(wc -c <"$WORK/prod.script")-byte payload"

# UBER_NETWORK reaches docker: `--network <mode>` in the argv and the mode in the
# container's env, and neither without it (the capture above set no UBER_NETWORK).
if UBER_NETWORK=none capture "$WORK/prod/tools/uber.sh" prodnet; then
  if grep -qx -- '--network' "$WORK/prodnet.argv" && grep -A1 -x -- '--network' "$WORK/prodnet.argv" | grep -qx none \
     && grep -qx 'UBER_NETWORK_MODE=none' "$WORK/prodnet.env"; then
    ok "UBER_NETWORK=none: docker runs with --network none and the container is told so"
  else bad "UBER_NETWORK=none did not reach docker as --network none"; fi
fi
if ! grep -qx -- '--network' "$WORK/prod.argv"; then ok "without UBER_NETWORK: no --network flag is passed"
else bad "a --network flag was passed without UBER_NETWORK"; fi

printf '\ncanaries — production\n'
verdicts prod prod
if [ "$V_DISCOVER" = pass ]; then ok "discovery: the mounted worktree resolves"
else bad "discovery: git refuses the mounted worktree"; note "$(head -3 "$WORK/prod-discover.err")"; fi
if [ "$V_CLONE" = pass ]; then ok "local clone: the mount clones, upload-pack included"
else bad "local clone: git refuses to clone the mount"; note "$(head -3 "$WORK/prod-clone.err")"; fi
if [ "$V_NARROW" = pass ]; then ok "narrowness: a repository that is not the mount is still refused"
else bad "narrowness: the declaration waives a repository that is not the mount"; fi

# ---------------------------------------------------------------------------
# CASE reports-unwritable — a declaration that cannot be written is ANNOUNCED,
# and the command still runs with its own status. A temp directory that does
# not exist is the failure; the report and the status are judged separately so
# a silenced report cannot hide behind a correct status, or the reverse.
# ---------------------------------------------------------------------------
UNWRITABLE_MARK='safe.directory NOT DECLARED'
# unwritable <capture-tag> <label> -> U_REPORT, U_STATUS = pass|red
U_REPORT=red
U_STATUS=red
unwritable() {
  replay "$2-unwritable" "$1" "$WS" 'exit 7' TMPDIR="$WORK/no-such-dir"
  if [ "$RP_STATUS" -eq 7 ]; then U_STATUS=pass; else U_STATUS=red; fi
  if has "$WORK/$2-unwritable.err" "$UNWRITABLE_MARK"; then U_REPORT=pass; else U_REPORT=red; fi
}
printf '\ncase reports-unwritable\n'
unwritable prod prod
if [ "$U_REPORT" = pass ]; then ok "an unwritable declaration is announced on stderr"
else bad "reports-unwritable: the declaration was not written and nothing said so"; fi
if [ "$U_STATUS" = pass ]; then ok "the command still ran and its status (7) propagated"
else bad "reports-unwritable: status $RP_STATUS, wanted the command's own 7"; fi

# ---------------------------------------------------------------------------
# MUTATION — break one clause at a time in a copy of uber.sh; exactly the
# canaries named for it must flip, and the others must hold.
# ---------------------------------------------------------------------------
# assert_landed <mutant> <added-text> <removed-text>, counted against the
# unmutated script so text the original merely quotes in a comment cannot pass
# for a landed mutation.
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
  if [ "$n_added" -le "$base_added" ]; then
    bad "mutation did not land: [$added] went $base_added -> $n_added"
    return 1
  fi
  if [ "$n_removed" -ne 0 ]; then
    bad "mutation did not land: [$removed] survives ($n_removed occurrence(s))"
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

# expect <label> <want-discover> <want-clone> <want-narrow>
expect() {
  local label="$1" wd="$2" wc="$3" wn="$4" name want got
  for name in discover clone narrow; do
    case "$name" in
      discover) want="$wd"; got="$V_DISCOVER" ;;
      clone)    want="$wc"; got="$V_CLONE" ;;
      narrow)   want="$wn"; got="$V_NARROW" ;;
    esac
    if [ "$want" = "$got" ]; then
      if [ "$want" = red ]; then ok "$label: the $name canary went red for this clause"
      else ok "$label: CONTROL the $name canary still holds"; fi
    else
      bad "$label: the $name canary is $got, wanted $want"
    fi
  done
}

# run_mutant <tag> <label> <added> <removed> <want-d> <want-c> <want-n> — the
# mutant file is $WORK/<tag>/tools/uber.sh, already edited by the caller.
run_mutant() {
  local tag="$1" label="$2" added="$3" removed="$4"
  local f="$WORK/$tag/tools/uber.sh"
  if ! assert_landed "$f" "$added" "$removed"; then return; fi
  if ! capture "$f" "$tag"; then return; fi
  if cmp -s -- "$WORK/$tag.script" "$WORK/prod.script" && cmp -s -- "$WORK/$tag.env" "$WORK/prod.env"; then
    bad "$label: the captured mutant equals production — the mutation is not in what RAN"
    return
  fi
  verdicts "$tag" "$tag"
  expect "$label" "$5" "$6" "$7"
}

mutant_copy() { mkdir -p "$WORK/$1/tools"; cp -- "$SUT" "$WORK/$1/tools/uber.sh"; }

# replace_line <file> <exact-old-line> <new-line>: a WHOLE-LINE literal match,
# in bash rather than awk or sed, because these lines carry backslash escapes
# that `awk -v` and sed patterns would each rewrite before comparing.
replace_line() {
  local f="$1" old="$2" new="$3" line
  while IFS= read -r line || [ -n "$line" ]; do
    if [ "$line" = "$old" ]; then printf '%s\n' "$new"; else printf '%s\n' "$line"; fi
  done < "$f" > "$f.new"
  mv -- "$f.new" "$f"
}

# The two declaration lines, exactly as tools/uber.sh writes them.
GITDIR_LINE='  printf '"'"'\tdirectory = "%s/.git"\n'"'"' "$UBER_WORKSPACE"'
WORKTREE_LINE='  printf '"'"'\tdirectory = "%s"\n'"'"' "$UBER_WORKSPACE"'
DROPPED_LINE='  : dropped-by-mutant'
WILDCARD_LINE='  printf '"'"'\tdirectory = *\n'"'"''

printf '\nmutation M1 — drop the GITDIR entry (the path a local clone checks)\n'
mutant_copy m1
replace_line "$WORK/m1/tools/uber.sh" "$GITDIR_LINE" "$DROPPED_LINE"
run_mutant m1 M1 "$DROPPED_LINE" "$GITDIR_LINE" pass red pass

printf '\nmutation M2 — drop the WORKTREE entry (the path discovery checks)\n'
mutant_copy m2
replace_line "$WORK/m2/tools/uber.sh" "$WORKTREE_LINE" "$DROPPED_LINE"
run_mutant m2 M2 "$DROPPED_LINE" "$WORKTREE_LINE" red pass pass

printf '\nmutation M3 — restore the historical GIT_CONFIG_* env declaration verbatim\n'
mutant_copy m3
awk '
  /---8<--- safe.directory declaration BEGIN/ { skip=1; next }
  /---8<--- safe.directory declaration END/   { skip=0; next }
  skip { next }
  /^exec docker run / {
    print "GIT_OWNERSHIP=("
    print "  -e GIT_CONFIG_COUNT=1"
    print "  -e GIT_CONFIG_KEY_0=safe.directory"
    print "  -e GIT_CONFIG_VALUE_0=\"$WORKSPACE\""
    print ")"
    print
    print "  \"${GIT_OWNERSHIP[@]}\" \\"
    next
  }
  { print }
' "$WORK/m3/tools/uber.sh" > "$WORK/m3.new" && mv -- "$WORK/m3.new" "$WORK/m3/tools/uber.sh"
run_mutant m3 M3 '-e GIT_CONFIG_KEY_0=safe.directory' '---8<--- safe.directory declaration BEGIN' pass red pass

printf '\nmutation M4 — widen the declaration to the wildcard\n'
mutant_copy m4
replace_line "$WORK/m4/tools/uber.sh" "$WORKTREE_LINE" "$WILDCARD_LINE"
replace_line "$WORK/m4/tools/uber.sh" "$GITDIR_LINE" "$DROPPED_LINE"
run_mutant m4 M4 "$WILDCARD_LINE" "$WORKTREE_LINE" pass pass red

printf '\nmutation M5 — silence the unwritable-declaration report\n'
mutant_copy m5
REPORT_LINE="  printf 'uber.sh: $UNWRITABLE_MARK — its config file could not be\\n' >&2"
replace_line "$WORK/m5/tools/uber.sh" "$REPORT_LINE" "$DROPPED_LINE"
if assert_landed "$WORK/m5/tools/uber.sh" "$DROPPED_LINE" "$REPORT_LINE" \
   && capture "$WORK/m5/tools/uber.sh" m5; then
  unwritable m5 m5
  if [ "$U_REPORT" = red ]; then ok "M5: the reports-unwritable canary went red for this clause"
  else bad "M5: the report SURVIVED the mutation — the canary cannot be attributed to this clause"; fi
  if [ "$U_STATUS" = pass ]; then ok "M5: CONTROL the status still propagates"
  else bad "M5: CONTROL the status flipped — the red is not attributable"; fi
fi

# ---------------------------------------------------------------------------
# CASE real-container — uber.sh run for REAL (a byte-identical copy, so the
# mount is a scratch checkout and never this one), against a checkout the
# invoking user owns, in the pinned image as root. No switch, no substitution.
# Preconditions are PROBED; absent, this is UNJUDGED and never green.
# ---------------------------------------------------------------------------
printf '\ncase real-container\n'
REAL="$WORK/real"
mkrepo "$REAL"
mkdir -p "$REAL/tools"
cp -- "$SUT" "$REAL/tools/uber.sh"
if [ "$(id -u)" -eq 0 ]; then
  unjudged "real-container: running as root, so the mount is root-owned and there is no mismatch to observe"
elif ! command -v docker >/dev/null 2>&1; then
  unjudged "real-container: no docker on PATH (this is the normal state inside the uber container)"
elif ! docker image inspect "$IMG" >/dev/null 2>&1; then
  unjudged "real-container: the pinned image $IMG is not present; not building one from a lint lane"
else
  rc_ctl=0
  docker run --rm --entrypoint bash -v "$REAL:/workspace" -w /workspace "$IMG" \
    -c 'git clone -q --local --no-hardlinks /workspace /tmp/w' \
    >"$WORK/real-control.out" 2>"$WORK/real-control.err" || rc_ctl=$?
  if [ "$rc_ctl" -eq 0 ] || ! has "$WORK/real-control.err" "dubious ownership"; then
    unjudged "real-container: an undeclared clone was NOT refused here (rc $rc_ctl) — no ownership mismatch on this daemon"
  else
    ok "real-container CONTROL: an undeclared in-container clone of the mount is refused"
    rc_real=0
    bash "$REAL/tools/uber.sh" \
      'git ls-files --error-unmatch seed.txt >/dev/null && git clone -q --local --no-hardlinks /workspace /tmp/w && test -f /tmp/w/seed.txt && echo REAL_CLONE_OK' \
      >"$WORK/real.out" 2>"$WORK/real.err" || rc_real=$?
    if [ "$rc_real" -eq 0 ] && has "$WORK/real.out" "REAL_CLONE_OK"; then
      ok "real-container: uber.sh discovers and local-clones a checkout the caller owns"
    else
      bad "real-container: under uber.sh the in-container local clone failed (rc $rc_real)"
      note "$(head -4 "$WORK/real.err")"
    fi
  fi
fi

# ---------------------------------------------------------------------------
printf '\n'
printf 'passed: %d   failed: %d   unjudged: %d\n' "$PASS" "$FAIL" "$UNJUDGED"
if [ "$FAIL" -gt 0 ]; then
  printf '\033[31m[uber-safe-directory] FAIL\033[0m\n'
  exit 1
fi
if [ "$PASS" -eq 0 ]; then
  printf '\033[31m[uber-safe-directory] FAIL\033[0m — nothing was judged\n'
  exit 1
fi
if [ "$UNJUDGED" -gt 0 ]; then
  printf '\033[33m[uber-safe-directory] no failures, but %d case(s) were UNJUDGED\033[0m — this is not ALL GREEN\n' "$UNJUDGED"
  exit 0
fi
printf '\033[32m[uber-safe-directory] ALL GREEN\033[0m\n'
