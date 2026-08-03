#!/usr/bin/env bash
# brief_check_test.sh — canaries for tools/claude/brief-check.sh.
#
# WHAT A CANARY HERE HAS TO PROVE, and it is more than "it went red".
#
#   A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED. Most of these clauses have a
#   NEIGHBOUR that would also refuse the same brief, so a red run is compatible
#   with the clause under test being dead. Every canary below is therefore run
#   TWICE: once against the real script, where it must FAIL with its own clause
#   id, and once against a MUTANT in which that one clause has been silenced,
#   where it must go GREEN. If a neighbour were doing the work, the mutant run
#   would stay red and this suite fails.
#
#   THE MUTATION MUST BE PROVEN TO HAVE LANDED. A sed that silently matched
#   nothing produces a mutant identical to the original, whose green is then
#   read as proof of attribution while proving the exact opposite. Each mutation
#   asserts the new text is present AND the old text is gone before it is run.
#
#   FAIL IS NOT ERROR. brief-check exits 1 for findings, 2 for usage and 3 for
#   internal errors. Asserting "non-zero" would accept a syntax error wearing
#   the right colour, so every canary asserts the exact code and the clause id.
#
# Usage: tools/claude/brief_check_test.sh
set -euof pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUT="$SCRIPT_DIR/brief-check.sh"
FORKS="$SCRIPT_DIR/forks.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/brief-check-test.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

PASS=0
FAIL=0

ok() {
  PASS=$((PASS + 1))
  printf '  \033[32mok\033[0m   %s\n' "$*"
}

bad() {
  FAIL=$((FAIL + 1))
  printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2
}

banner() { printf '\n== %s\n' "$*"; }

git_at() {
  local repo="$1"
  shift
  env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"
}

# ---------------------------------------------------------------------------
# A synthetic base tree. Small, so every expectation below is decidable by
# reading this function rather than by trusting the real repository not to move
# under the suite.
# ---------------------------------------------------------------------------
ROOT="$WORK/root"
build_root() {
  mkdir -p "$ROOT"/{tools/claude,renderer/src,docs,.claude/skills/ui-standard-review}
  mkdir -p "$ROOT/tools/devcards/src/devcards" "$ROOT/.protogen/research"

  printf 'echo forks\n' > "$ROOT/tools/claude/forks.sh"
  printf 'int main(void) { return 0; }\n' > "$ROOT/renderer/src/a.c"
  printf '#define LV_X 1\n' > "$ROOT/renderer/lv_conf.h"
  printf '# docs\n' > "$ROOT/docs/y.md"
  printf ';; card\n' > "$ROOT/tools/devcards/x.clj"
  printf '# STANDARD\n' > "$ROOT/.claude/skills/ui-standard-review/STANDARD.md"
  printf '(def out-path ".claude/skills/ui-standard-review/STANDARD.md")\n' \
    > "$ROOT/tools/devcards/src/devcards/standard_brief.clj"
  {
    printf 'standard-brief-generate:\n\tclojure -M -m devcards.standard-brief\n\n'
    printf 'check-renderer: graal-check standard-brief-generate wasm\n'
    printf '\t@echo battery green\n'
  } > "$ROOT/renderer.mk"
  printf '.protogen/\n' > "$ROOT/.gitignore"

  # Untracked AND gitignored: the class a clone cannot carry. This is the file
  # four wave-5 forks were dispatched without.
  printf '# evidence\n' > "$ROOT/.protogen/research/evidence.md"

  git_at "$ROOT" init --quiet -b master
  git_at "$ROOT" add -A
  git_at "$ROOT" -c user.name=t -c user.email=t@localhost commit --quiet -m base
}

# ---------------------------------------------------------------------------
# Runner. Captures stdout+stderr and the exact exit code.
# ---------------------------------------------------------------------------
RUN_OUT=""
RUN_CODE=0
run_check() {
  local sut="$1"
  shift
  RUN_CODE=0
  RUN_OUT="$("$sut" check "$@" 2>&1)" || RUN_CODE=$?
}

# expect_fail <label> <clause-id> <sut> <brief> [extra args...]
expect_fail() {
  local label="$1" clause="$2" sut="$3"
  shift 3
  run_check "$sut" "$@"
  if [ "$RUN_CODE" -ne 1 ]; then
    bad "$label: expected exit 1 (FAIL), got $RUN_CODE — this is an ERROR, not a verdict"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
    return 0
  fi
  if ! grep -q "FAIL — $clause:" <<< "$RUN_OUT"; then
    bad "$label: exit 1 but no finding named '$clause' — the refusal is NOT attributed"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
    return 0
  fi
  if grep -q 'ERROR —' <<< "$RUN_OUT"; then
    bad "$label: an internal ERROR was emitted alongside the finding"
    return 0
  fi
  ok "$label -> FAIL $clause"
  printf '       %s\n' "$(grep -m1 "FAIL — $clause:" <<< "$RUN_OUT")"
}

expect_pass() {
  local label="$1" sut="$2"
  shift 2
  run_check "$sut" "$@"
  if [ "$RUN_CODE" -ne 0 ]; then
    bad "$label: expected exit 0, got $RUN_CODE"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
    return 0
  fi
  ok "$label -> pass"
}

# ---------------------------------------------------------------------------
# Mutation. Silences exactly one clause and PROVES the edit landed.
# ---------------------------------------------------------------------------
# Sets MUTANT. Deliberately NOT via command substitution: the progress lines
# below print to stdout, and capturing them alongside the path is how a test
# harness ends up executing a filename that is really a log line.
MUTANT=""
make_mutant() {
  local clause="$1" before after left
  MUTANT="$WORK/mutant-$clause.sh"
  cp -- "$SUT" "$MUTANT"
  before="$(grep -c "finding $clause " "$MUTANT" || true)"
  if [ "$before" -eq 0 ]; then
    bad "mutation for '$clause' cannot land: no 'finding $clause ' call site exists"
    return 1
  fi
  sed -i "s/^\([[:space:]]*\)finding $clause /\1: $clause /" "$MUTANT"
  after="$(grep -c "^[[:space:]]*: $clause " "$MUTANT" || true)"
  left="$(grep -c "finding $clause " "$MUTANT" || true)"
  if [ "$after" -ne "$before" ] || [ "$left" -ne 0 ]; then
    bad "mutation for '$clause' did NOT land (expected $before silenced, got $after; $left remain)"
    return 1
  fi
  if ! bash -n "$MUTANT"; then
    bad "mutant for '$clause' does not parse; a green from it would prove nothing"
    return 1
  fi
  chmod +x "$MUTANT"
  ok "mutation landed: $before call site(s) of '$clause' silenced, 0 remain, mutant parses"
  return 0
}

# canary <label> <clause> <brief> [extra args...]
canary() {
  local label="$1" clause="$2"
  shift 2
  expect_fail "$label" "$clause" "$SUT" "$@"
  if make_mutant "$clause"; then
    expect_pass "$label [mutant: only '$clause' silenced]" "$MUTANT" "$@"
  fi
}

# ---------------------------------------------------------------------------
build_root
B="$WORK/briefs"
mkdir -p "$B"

banner "a well-formed brief is NOT refused (the false-positive floor)"
cat > "$B/clean.md" <<'EOF'
# BRIEF — a clean one

Read `docs/y.md` and `.protogen/research/evidence.md` before starting.
The battery at your base is RED for a reason that is NOT yours. Ignore it.
Nothing in `renderer/src` calls the old helper and it does not exist any more.

ASSERT-ABSENT: renderer/src/removed.c
ASSERT-NO-CALLERS: lv_totally_absent_symbol

## FILES YOU OWN
  renderer/**
  tools/claude/created.sh   (NEW)
  FINAL_REPORT.md

## FORBIDDEN
  renderer/src/**
  docs/**
EOF
expect_pass "clean brief (carve-out fence, NEW marker, satisfied assertions)" \
  "$SUT" "$B/clean.md" --root "$ROOT"

banner "check 1 — a cited path that does not exist and is not shipped in"
cat > "$B/c1.md" <<'EOF'
# BRIEF
The fix belongs in `renderer/src/nope.c`, next to the existing code.

## FILES YOU OWN
  tools/claude/forks.sh
EOF
canary "check1" cited-path-missing "$B/c1.md" --root "$ROOT"

banner "check 1 — a cited path that is untracked but PRESENT is shipped, not refused"
cat > "$B/c1ok.md" <<'EOF'
# BRIEF
Your evidence base is `.protogen/research/evidence.md`.

## FILES YOU OWN
  tools/claude/forks.sh
EOF
expect_pass "check1 negative (gitignored evidence exists in root)" \
  "$SUT" "$B/c1ok.md" --root "$ROOT"

banner "check 2 — one path in BOTH the OWNED and FORBIDDEN lists"
cat > "$B/c2.md" <<'EOF'
# BRIEF
Fix the construction defect.

## FILES YOU OWN
  renderer/src/a.c

## FORBIDDEN
  renderer/**
EOF
canary "check2" owned-forbidden-conflict "$B/c2.md" --root "$ROOT"

banner "check 3 — OWNED sets overlapping ACROSS sibling briefs"
cat > "$B/c3a.md" <<'EOF'
# BRIEF
## FILES YOU OWN
  tools/claude/forks.sh
EOF
cat > "$B/c3b.md" <<'EOF'
# BRIEF
## FILES YOU OWN
  tools/claude/forks.sh
EOF
canary "check3" sibling-owned-overlap "$B/c3a.md" --root "$ROOT" --sibling "$B/c3b.md"

banner "check 3 — disjoint siblings are NOT refused"
cat > "$B/c3c.md" <<'EOF'
# BRIEF
## FILES YOU OWN
  docs/**
EOF
expect_pass "check3 negative (disjoint OWNED sets)" \
  "$SUT" "$B/c3a.md" --root "$ROOT" --sibling "$B/c3c.md"

banner "check 4 — a declared absence that is not real"
cat > "$B/c4.md" <<'EOF'
# BRIEF
ASSERT-ABSENT: renderer/src/a.c

## FILES YOU OWN
  tools/claude/forks.sh
EOF
canary "check4-absent" assert-absent-violated "$B/c4.md" --root "$ROOT"

cat > "$B/c4b.md" <<'EOF'
# BRIEF
ASSERT-NO-CALLERS: standard-brief-generate

## FILES YOU OWN
  tools/claude/forks.sh
EOF
canary "check4-callers" assert-no-callers-violated "$B/c4b.md" --root "$ROOT"

banner "check 5 — battery permission granted beside a fence the battery WRITES into"
cat > "$B/c5.md" <<'EOF'
# BRIEF
MAY-RUN: make -f renderer.mk check-renderer

## FILES YOU OWN
  tools/claude/forks.sh

## FORBIDDEN
  .claude/**
EOF
canary "check5" permitted-command-writes-into-fence "$B/c5.md" --root "$ROOT"

banner "check 5 — the same permission WITHOUT that fence is not refused"
cat > "$B/c5ok.md" <<'EOF'
# BRIEF
MAY-RUN: make -f renderer.mk check-renderer

## FILES YOU OWN
  tools/claude/forks.sh

## FORBIDDEN
  docs/**
EOF
expect_pass "check5 negative (fence does not cover the write)" \
  "$SUT" "$B/c5ok.md" --root "$ROOT"

banner "check 5 — the write table REFUSES to go quietly stale"
STALE="$WORK/stale-root"
cp -a "$ROOT" "$STALE"
sed -i 's/^check-renderer: graal-check standard-brief-generate wasm$/check-renderer: graal-check wasm/' \
  "$STALE/renderer.mk"
if [ "$(grep -c 'check-renderer: graal-check wasm' "$STALE/renderer.mk")" -eq 0 ]; then
  bad "stale-table mutation did not land"
else
  ok "stale-table mutation landed (standard-brief-generate removed from check-renderer)"
  RUN_CODE=0
  RUN_OUT="$("$SUT" check "$B/c5.md" --root "$STALE" 2>&1)" || RUN_CODE=$?
  if [ "$RUN_CODE" -eq 3 ] && grep -q 'write table is STALE' <<< "$RUN_OUT"; then
    ok "stale write table -> exit 3 ERROR, not a pass"
    printf '       %s\n' "$(grep -m1 'STALE' <<< "$RUN_OUT")"
  else
    bad "stale write table should ERROR (exit 3); got $RUN_CODE"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  fi
fi

banner "check 6 — a base sha whose tree lacks a file the brief says the fork owns"
cat > "$B/c6.md" <<'EOF'
# BRIEF
tools/claude/gate.sh exists and works; harden it.

## FILES YOU OWN
  tools/claude/gate.sh
EOF
canary "check6" owned-path-absent-at-base "$B/c6.md" --root "$ROOT"

banner "check 6 — the same path marked (NEW) is not refused"
cat > "$B/c6ok.md" <<'EOF'
# BRIEF
## FILES YOU OWN
  tools/claude/gate.sh   (NEW)
EOF
expect_pass "check6 negative ((NEW) marker present)" \
  "$SUT" "$B/c6ok.md" --root "$ROOT"

# ---------------------------------------------------------------------------
banner "the UNJUDGED block exists and names what could not be seen"
RUN_CODE=0
RUN_OUT="$("$SUT" check "$B/c3a.md" --root "$ROOT" 2>&1)" || RUN_CODE=$?
if grep -q 'UNJUDGED — what this gate could NOT see' <<< "$RUN_OUT" &&
  grep -q 'no sibling brief was supplied' <<< "$RUN_OUT"; then
  ok "a brief with nothing to compare against reports UNJUDGED, not clean"
else
  bad "UNJUDGED block missing; 'clean' and 'I could not look' are indistinguishable"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
fi

# ---------------------------------------------------------------------------
banner "end to end — forks.sh claim refuses a defective brief and creates nothing"
E2E="$WORK/e2e"
mkdir -p "$E2E"
cp -a "$ROOT" "$E2E/repo"
mkdir -p "$E2E/repo/tools/claude"
cp -- "$FORKS" "$E2E/repo/tools/claude/forks.sh"
cp -- "$SUT" "$E2E/repo/tools/claude/brief-check.sh"
chmod +x "$E2E/repo/tools/claude/forks.sh" "$E2E/repo/tools/claude/brief-check.sh"
git_at "$E2E/repo" add -A
git_at "$E2E/repo" -c user.name=t -c user.email=t@localhost commit --quiet -m tools
export PROTOGEN_FORKS_STATE_DIR="$E2E/state"

cp -- "$B/c2.md" "$E2E/bad.brief"
RUN_CODE=0
RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" claim "$E2E/badfork" t1 tester "$E2E/bad.brief" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -eq 1 ] &&
  grep -q 'owned-forbidden-conflict' <<< "$RUN_OUT" &&
  grep -q 'nothing was cloned' <<< "$RUN_OUT" &&
  [ ! -e "$E2E/badfork" ]; then
  ok "claim refused the defective brief; no path was created"
  printf '       %s\n' "$(grep -m1 'FAIL — owned-forbidden-conflict' <<< "$RUN_OUT")"
else
  bad "claim should have refused the defective brief (exit=$RUN_CODE, path exists=$([ -e "$E2E/badfork" ] && echo yes || echo no))"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
fi

banner "end to end — a good brief claims, and its gitignored evidence ARRIVES"
cat > "$E2E/good.brief" <<'EOF'
# BRIEF
Your evidence base is `.protogen/research/evidence.md`.

## FILES YOU OWN
  tools/claude/forks.sh
  FINAL_REPORT.md

## FORBIDDEN
  docs/**
EOF
RUN_CODE=0
RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" claim "$E2E/goodfork" t2 tester "$E2E/good.brief" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -ne 0 ]; then
  bad "claim of a good brief failed (exit=$RUN_CODE)"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
else
  ok "claim succeeded"
  if [ -f "$E2E/goodfork/.protogen/research/evidence.md" ]; then
    ok "cited gitignored source ARRIVED in the fork (git clone alone cannot carry it)"
  else
    bad "cited gitignored source did NOT arrive — the Part 1 defect is still live"
  fi
  # The control: prove the clone could not have carried it by itself.
  if [ -z "$(git_at "$E2E/goodfork" ls-files -- .protogen 2>/dev/null || true)" ]; then
    ok "control: the shipped file is NOT tracked, so the clone genuinely could not carry it"
  else
    bad "control failed: .protogen is tracked in the synthetic root, so the test proves nothing"
  fi
  if grep -q 'A REFUSAL SHOWN IS NOT A REFUSAL ATTRIBUTED' "$E2E/goodfork/DONATION_OWNER.md"; then
    ok "the attribution requirement is emitted into DONATION_OWNER.md by the harness"
  else
    bad "DONATION_OWNER.md lacks the attribution requirement"
  fi
fi

banner "end to end — release does NOT read shipped sources as worker residue"
if [ -d "$E2E/goodfork" ]; then
  RUN_CODE=0
  RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" release "$E2E/goodfork" --owner-signalled done 2>&1)" || RUN_CODE=$?
  if [ "$RUN_CODE" -eq 0 ] && [ ! -e "$E2E/goodfork" ]; then
    ok "release completed with shipped sources present"
    printf '       %s\n' "$(grep -m1 'shipped sources excluded' <<< "$RUN_OUT")"
  else
    bad "release refused a fork whose only untracked content was shipped in by claim (exit=$RUN_CODE)"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  fi
fi

banner "end to end — a shipped source in NON-ignored space still does not block release"
# The interaction the Part 1 note flagged. `.protogen/` is gitignored, so the
# case above would pass even with no exclusion at all — it proves nothing about
# the mechanism. A cited source under a path no ignore rule covers is the real
# test, and it is the one that distinguishes a PATH exclusion from an
# ignore-based one.
cat > "$E2E/good2.brief" <<'EOF'
# BRIEF
Your evidence base is `docs/untracked-note.md`.

## FILES YOU OWN
  tools/claude/forks.sh
  FINAL_REPORT.md
EOF
printf '# note\n' > "$E2E/repo/docs/untracked-note.md"
RUN_CODE=0
RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" claim "$E2E/gf2" t3 tester "$E2E/good2.brief" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -ne 0 ]; then
  bad "claim with a non-ignored untracked source failed (exit=$RUN_CODE)"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
else
  if [ -f "$E2E/gf2/docs/untracked-note.md" ] &&
    [ -n "$(git_at "$E2E/gf2" status --porcelain --untracked-files=all -- docs)" ]; then
    ok "control: the shipped source IS visible to porcelain (no ignore rule hides it)"
  else
    bad "control failed: the shipped source is not porcelain-visible, so the case is vacuous"
  fi
  RUN_CODE=0
  RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" release "$E2E/gf2" --owner-signalled done 2>&1)" || RUN_CODE=$?
  if [ "$RUN_CODE" -eq 0 ] && [ ! -e "$E2E/gf2" ]; then
    ok "release succeeded: the PATH exclusion holds where an ignore-based one would not"
  else
    bad "release refused a porcelain-visible shipped source (exit=$RUN_CODE)"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  fi
fi

banner "end to end — worker residue is STILL refused (the guard was not weakened)"
cat > "$E2E/good3.brief" <<'EOF'
# BRIEF
Your evidence base is `docs/untracked-note.md`.

## FILES YOU OWN
  tools/claude/forks.sh
  FINAL_REPORT.md
EOF
RUN_CODE=0
RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" claim "$E2E/gf3" t4 tester "$E2E/good3.brief" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -ne 0 ]; then
  bad "claim gf3 failed (exit=$RUN_CODE)"
else
  printf 'worker wrote this\n' > "$E2E/gf3/docs/worker-residue.md"
  RUN_CODE=0
  RUN_OUT="$("$E2E/repo/tools/claude/forks.sh" release "$E2E/gf3" --owner-signalled done 2>&1)" || RUN_CODE=$?
  if [ "$RUN_CODE" -eq 1 ] && [ -d "$E2E/gf3" ] &&
    grep -q 'worker-residue.md' <<< "$RUN_OUT"; then
    ok "release still refuses genuine worker residue beside a shipped source"
  else
    bad "residue guard was WEAKENED: release did not refuse worker residue (exit=$RUN_CODE)"
    printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
  fi
fi

# ---------------------------------------------------------------------------
# THE SCOPE TEST — a WRONG ANSWER AT EXIT 0, which is why these cases assert the
# ANSWER and not the status.
#
# `in_scope` answers "is this token's first segment a real top-level entry of the
# base tree?". Asked through `printf | grep -qxF`, it can answer NO about an
# entry that is plainly there: `grep -q` exits on its first match WITHOUT
# draining stdin, `printf` then dies of SIGPIPE, and `pipefail` promotes 141 over
# grep's 0. Both callers absorb that silently — `ship-list` does
# `in_scope || continue` and still exits 0 with a short list, and `check` files
# the entry as UNJUDGED instead of judging it. So a canary asserting a non-zero
# exit would see nothing at all here: the exit status IS the thing that lies.
#
# THE RATE IS A FUNCTION OF HOW MUCH THE WRITER STILL HAS TO WRITE when the
# reader quits, so the root below carries a TOP_LEVEL far past one pipe buffer
# and the race stops being a one-in-thousands flake. That is a property of the
# FIXTURE, so it is asserted rather than assumed: if the payload no longer
# outruns the buffer, these cases go quiet instead of going wrong, and a quiet
# case is exactly what this suite refuses to count as coverage.
# ---------------------------------------------------------------------------
banner "the scope test answers wrongly at exit 0 when TOP_LEVEL outruns a pipe buffer"

PIPE_BUF_FLOOR=65536 # Linux default pipe capacity; the writer must outrun it.
BIGROOT="$WORK/bigroot"
BIGTL=""
build_big_root() {
  local pad i
  mkdir -p "$BIGROOT/docs"
  printf '# tracked\n' > "$BIGROOT/tracked.md"
  # Untracked on purpose: this is precisely what ship-list exists to find.
  printf '# evidence\n' > "$BIGROOT/docs/evidence.md"
  # Padding that sorts AFTER `docs`, so the reader is satisfied by an early line
  # and the writer still has the whole tail in hand.
  printf -v pad '%0200d' 0
  pad="${pad//0/z}"
  for ((i = 0; i < 500; i++)); do : > "$BIGROOT/zz${i}_$pad"; done
  git_at "$BIGROOT" init --quiet -b master
  git_at "$BIGROOT" add tracked.md
  git_at "$BIGROOT" -c user.name=t -c user.email=t@localhost commit --quiet -m base
  # Enumerated exactly as load_top_level does it.
  BIGTL="$(
    {
      git_at "$BIGROOT" ls-tree --name-only HEAD
      ls -A -- "$BIGROOT"
    } | sort -u
  )"
}
build_big_root

# --- fixture floors, all four, before any verdict is read from this root ------
BIGTAIL=$'\n'"$BIGTL"$'\n'
case "$BIGTAIL" in
  *$'\n'docs$'\n'*)
    ok "control: 'docs' IS a top-level entry of the base tree, so it is genuinely in scope"
    ;;
  *)
    bad "control failed: 'docs' is not in the enumerated TOP_LEVEL; the case cannot distinguish a bug from a correct exclusion"
    ;;
esac
BIGTAIL="${BIGTAIL#*$'\n'docs$'\n'}"
if [ "${#BIGTAIL}" -gt "$PIPE_BUF_FLOOR" ]; then
  ok "control: ${#BIGTAIL} bytes remain to be written after the matching line (> $PIPE_BUF_FLOOR)"
else
  bad "control failed: only ${#BIGTAIL} bytes follow the match, so the writer never blocks and this case is vacuous"
fi
if [ -f "$BIGROOT/docs/evidence.md" ]; then
  ok "control: the cited source exists on disk, so ship-list is obliged to list it"
else
  bad "control failed: the cited source does not exist, so its absence from the list would be correct"
fi
if [ -z "$(git_at "$BIGROOT" ls-files -- docs)" ]; then
  ok "control: the cited source is UNTRACKED, so a clone could not carry it and ship-list must"
else
  bad "control failed: the cited source is tracked, so ship-list is right to omit it"
fi

cat > "$B/big.md" <<'EOF'
# BRIEF
Your evidence base is `docs/evidence.md`.

## FILES YOU OWN
  tracked.md
  FINAL_REPORT.md
EOF

# --- the wrong answer -------------------------------------------------------
RUN_CODE=0
RUN_OUT="$("$SUT" ship-list "$B/big.md" --root "$BIGROOT" 2> "$WORK/big.err")" || RUN_CODE=$?
if [ "$RUN_CODE" -ne 0 ]; then
  bad "ship-list on the big root exited $RUN_CODE; this case is about a WRONG ANSWER AT 0, so a non-zero exit means something else broke"
  sed 's/^/       /' -- "$WORK/big.err" >&2
else
  case $'\n'"$RUN_OUT"$'\n' in
    *$'\n'docs/evidence.md$'\n'*)
      ok "ship-list listed the cited untracked source (exit 0, answer correct)"
      ;;
    *)
      bad "ship-list exited 0 and DROPPED 'docs/evidence.md' — a short list is byte-identical to 'this brief cites nothing', so claim would ship it into no fork and dispatch a worker without its evidence"
      printf '       stdout was: %q\n' "$RUN_OUT" >&2
      ;;
  esac
fi

# CONTROL: the same brief and the same code against a root whose TOP_LEVEL fits
# in one pipe buffer. Without this arm the case above cannot tell a pipe defect
# from a brief the extractor never understood in the first place.
SMALLROOT="$WORK/smallroot"
mkdir -p "$SMALLROOT/docs"
printf '# tracked\n' > "$SMALLROOT/tracked.md"
printf '# evidence\n' > "$SMALLROOT/docs/evidence.md"
git_at "$SMALLROOT" init --quiet -b master
git_at "$SMALLROOT" add tracked.md
git_at "$SMALLROOT" -c user.name=t -c user.email=t@localhost commit --quiet -m base
RUN_CODE=0
RUN_OUT="$("$SUT" ship-list "$B/big.md" --root "$SMALLROOT" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -eq 0 ] && [ "$RUN_OUT" = "docs/evidence.md" ]; then
  ok "control: the identical brief against a small root lists it, so payload size is the only variable"
else
  bad "control failed: the small root did not list the source either (exit=$RUN_CODE), so the case above is not about the pipe"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
fi

# --- the other direction: a DEFECTIVE brief passing ------------------------
# The same dropped answer under `check` does not shorten a list, it retires the
# entry into UNJUDGED — so a brief citing a path that does not exist comes back
# clean. This is the dangerous half: the gate goes green over the defect it was
# built to catch.
cat > "$B/bigbad.md" <<'EOF'
# BRIEF
The fix belongs in `docs/nope.md`, next to the existing code.

## FILES YOU OWN
  tracked.md
EOF
RUN_CODE=0
RUN_OUT="$("$SUT" check "$B/bigbad.md" --root "$SMALLROOT" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -eq 1 ] && grep -q 'FAIL — cited-path-missing:' <<< "$RUN_OUT"; then
  ok "control: the brief IS defective — the small root refuses it with cited-path-missing"
else
  bad "control failed: the brief is not refused even at a small payload (exit=$RUN_CODE), so it cannot show a missed refusal"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
fi
RUN_CODE=0
RUN_OUT="$("$SUT" check "$B/bigbad.md" --root "$BIGROOT" 2>&1)" || RUN_CODE=$?
if [ "$RUN_CODE" -eq 1 ] && grep -q 'FAIL — cited-path-missing:' <<< "$RUN_OUT"; then
  ok "the same defective brief is still refused when TOP_LEVEL outruns the pipe buffer"
else
  bad "a DEFECTIVE brief passed at exit $RUN_CODE: the cited path was filed UNJUDGED instead of judged, so the gate went green over the defect it exists to catch"
  printf '%s\n' "$RUN_OUT" | sed 's/^/       /' >&2
fi

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %s\n' "$PASS"
printf '  failed: %s\n' "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
printf '  \033[32mALL GREEN\033[0m\n'
