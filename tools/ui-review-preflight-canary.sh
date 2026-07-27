#!/usr/bin/env bash
# CANARY for .claude/skills/ui-standard-review/preflight.sh.
#
# The preflight exists because the mandatory VLM review fails GREEN: an empty
# batch, or a batch of the PIN's own renders, both report clean. A refusal that
# refuses for the wrong reason is the same defect one level up, so every arm
# here asserts the EXACT exit code rather than merely "non-zero" — break one
# clause and only its arm goes red.
#
# It builds throwaway consumer mounts under `mktemp -d` (never beside the
# checkout), each holding a simulated protogen submodule at proto/protogen and a
# generic, secret-free gallery of its own. No toolchain, no container, no
# network, no git.
#
# Run:  tools/ui-review-preflight-canary.sh
set -uo pipefail

HERE=$(cd -P "$(dirname "$0")" && pwd)
REPO=$(cd -P "$HERE/.." && pwd)
PREFLIGHT_REL=.claude/skills/ui-standard-review/preflight.sh
[ -x "$REPO/$PREFLIGHT_REL" ] || {
  printf 'canary: FATAL — %s is missing or not executable\n' "$REPO/$PREFLIGHT_REL" >&2
  exit 1
}

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/ui-review-canary.XXXXXX")
cleanup() { rm -rf "$ROOT"; }
trap cleanup EXIT

# ── mount builders ─────────────────────────────────────────────────────────
mk_pin() { # $1 = consumer root; installs a submodule-shaped pin at proto/protogen
  mkdir -p "$1/proto/protogen/tools/devcards/src/devcards"
  cp -a "$REPO/.claude" "$1/proto/protogen/.claude"
  cp -a "$REPO/tools/devcards/docs" "$1/proto/protogen/tools/devcards/docs"
  # the pin-root marker the preflight sniffs for
  cp "$REPO/tools/devcards/src/devcards/standard_brief.clj" \
     "$1/proto/protogen/tools/devcards/src/devcards/standard_brief.clj"
}
mk_symlink_launcher() { # the RECOMMENDED install
  mkdir -p "$1/.claude/agents" "$1/.claude/skills"
  ln -sf ../../proto/protogen/.claude/agents/ui-standard-review.md \
         "$1/.claude/agents/ui-standard-review.md"
  ln -sfn ../../proto/protogen/.claude/skills/ui-standard-review \
          "$1/.claude/skills/ui-standard-review"
}
mk_copy_launcher() { # the fallback install
  mkdir -p "$1/.claude/agents" "$1/.claude/skills"
  cp "$1/proto/protogen/.claude/agents/ui-standard-review.md" "$1/.claude/agents/"
  cp -a "$1/proto/protogen/.claude/skills/ui-standard-review" "$1/.claude/skills/"
}
mk_own_gallery() { # a generic, secret-free consumer surface
  mkdir -p "$1/ui/gallery/widgets/CONSUMER_GAUGE" \
           "$1/ui/gallery/widgets/consumer-dashboard"
  for f in default-vanilla default-asgard-dark default-asgard-light; do
    : > "$1/ui/gallery/widgets/CONSUMER_GAUGE/CONSUMER_GAUGE-$f.jpg"
    : > "$1/ui/gallery/widgets/consumer-dashboard/consumer-dashboard-$f.jpg"
  done
}

PASS=0
FAIL=0
run() { # $1 name  $2 expected-exit  $3 cwd  rest: env assignments then argv
  name=$1
  want=$2
  cwd=$3
  shift 3
  out=$(cd "$cwd" && env "$@" 2>&1)
  rc=$?
  if [ "$rc" -eq "$want" ]; then
    printf '  PASS  %-52s exit=%s\n' "$name" "$rc"
    PASS=$((PASS + 1))
  else
    printf '  FAIL  %-52s exit=%s want=%s\n' "$name" "$rc" "$want"
    printf '%s\n' "$out" | sed 's/^/        | /'
    FAIL=$((FAIL + 1))
  fi
}

A=$ROOT/a
mkdir -p "$A"
mk_pin "$A"
mk_symlink_launcher "$A"
PF_A=$A/.claude/skills/ui-standard-review/preflight.sh

echo "consumer mount, launcher symlinked into the pin:"
run "no gallery anywhere -> NOT DISCHARGED (3)" 3 "$A" "$PF_A"
run "gallery pointed at the PIN -> WRONG SURFACE (4)" 4 "$A" \
  UI_REVIEW_GALLERY="$A/proto/protogen/tools/devcards/docs/widgets" "$PF_A"
run "same, positional arg -> WRONG SURFACE (4)" 4 "$A" \
  "$PF_A" "$A/proto/protogen/tools/devcards/docs/widgets"
mk_own_gallery "$A"
run "its OWN gallery -> resolved (0)" 0 "$A" \
  UI_REVIEW_GALLERY="$A/ui/gallery/widgets" "$PF_A"
mkdir -p "$A/ui/empty"
run "gallery dir present but empty -> NOT DISCHARGED (5)" 5 "$A" \
  UI_REVIEW_GALLERY="$A/ui/empty" "$PF_A"

E=$ROOT/e
mkdir -p "$E"
mk_pin "$E"
mk_own_gallery "$E"
echo "consumer mount with the launcher NOT installed:"
run "nothing at the project root -> NO LAUNCHER (6)" 6 "$E" \
  UI_REVIEW_GALLERY="$E/ui/gallery/widgets" \
  "$E/proto/protogen/$PREFLIGHT_REL"

F=$ROOT/f
mkdir -p "$F"
mk_pin "$F"
mk_own_gallery "$F"
mk_copy_launcher "$F"
PF_F_PIN=$F/proto/protogen/$PREFLIGHT_REL
PF_F_COPY=$F/$PREFLIGHT_REL
echo "consumer mount with the launcher COPIED rather than symlinked:"
run "fresh copy, run from the pin -> resolved (0)" 0 "$F" \
  UI_REVIEW_GALLERY="$F/ui/gallery/widgets" "$PF_F_PIN"
printf '\nlocally weakened clause\n' >> "$F/.claude/skills/ui-standard-review/SKILL.md"
run "copy edited locally -> STALE LAUNCHER (7)" 7 "$F" \
  UI_REVIEW_GALLERY="$F/ui/gallery/widgets" "$PF_F_PIN"
run "copy invoked through ITSELF -> PIN UNRESOLVED (8)" 8 "$F" \
  UI_REVIEW_GALLERY="$F/ui/gallery/widgets" "$PF_F_COPY"
run "copy invoked through itself + PIN_ROOT -> STALE (7)" 7 "$F" \
  UI_REVIEW_PIN_ROOT="$F/proto/protogen" \
  UI_REVIEW_GALLERY="$F/ui/gallery/widgets" "$PF_F_COPY"

H=$ROOT/h
mkdir -p "$H"
mk_pin "$H"
mk_symlink_launcher "$H"
mk_own_gallery "$H"
rm "$H/proto/protogen/.claude/agents/ui-standard-review.md"
echo "consumer mount whose symlink target went away:"
run "dangling agent link -> NO LAUNCHER (6)" 6 "$H" \
  UI_REVIEW_GALLERY="$H/ui/gallery/widgets" \
  "$H/.claude/skills/ui-standard-review/preflight.sh"

echo
if [ "$FAIL" -ne 0 ]; then
  printf 'canary: %s passed, %s FAILED\n' "$PASS" "$FAIL" >&2
  exit 1
fi
# NON-VACUITY FLOOR, the class lint-sh and fmt-c carry: a canary that ran zero
# arms would print the same cheerful nothing as one that ran them all.
if [ "$PASS" -lt 11 ]; then
  printf 'canary: FAIL — only %s arms ran; the matrix has 11\n' "$PASS" >&2
  exit 1
fi
printf 'canary: %s arms passed, each on its own exit code\n' "$PASS"
