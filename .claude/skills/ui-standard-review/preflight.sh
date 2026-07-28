#!/usr/bin/env bash
# ui-standard-review PREFLIGHT — resolve the review's inputs, or refuse LOUDLY.
#
# WHY THIS EXISTS. The VLM review is MANDATORY before any push that changes what
# a ui_ast SURFACE renders (CLAUDE.md §"Consuming the UI standard"), and it is
# not a gate — nothing mechanical checks that it ran, so its whole failure mode
# is a GREEN: a discharged obligation over a surface nobody looked at. Three
# ways to reach that green, all silent:
#
#   * the gallery path resolves to NOTHING at a consumer, so the batch is empty
#     and the reviewer reports clean over zero images;
#   * the path resolves UNDER THE PIN MOUNT, where protogen's own committed
#     gallery lives, so the reviewer produces a plausible-looking review of the
#     WRONG surface;
#   * the surface has no harness yet, so the reviewer concludes "out of scope"
#     from an obligation that is merely NOT YET DISCHARGED.
#
# This script makes all three loud, each with its own exit code so a red is
# attributable to the clause that produced it. It needs no toolchain, no
# container and no git: a consumer that has not yet stood up a harness must
# still get a straight answer.
#
# USAGE, from the SURFACE's own repo root:
#
#   <pin>/.claude/skills/ui-standard-review/preflight.sh [GALLERY_DIR]
#
# Environment:
#   UI_REVIEW_SURFACE_ROOT  the repo whose ui_ast surface is under review
#                           (default: $PWD)
#   UI_REVIEW_GALLERY       that surface's committed gallery root
#                           (default: $UI_REVIEW_SURFACE_ROOT/tools/devcards/docs/widgets)
#   UI_REVIEW_PIN_ROOT      the protogen pin's root. Only needed when this script
#                           was COPIED rather than symlinked — see exit 8.
#
# Exit codes:
#   0  inputs resolved; the printed unit roster IS the batch
#   2  usage error
#   3  NOT DISCHARGED — no gallery at the resolved path
#   4  WRONG SURFACE  — the gallery resolved inside the PIN
#   5  NOT DISCHARGED — gallery exists but holds no unit with renders
#   6  NO LAUNCHER    — the agent/skill is not installed at the surface root
#   7  STALE LAUNCHER — installed, but it has drifted from the pin
#   8  PIN UNRESOLVED — cannot tell which checkout is the pin
#
# A clean run of this script is NOT a discharged review. It says the reviewer
# would look at the right images; it says nothing about whether anyone ran it.
set -euo pipefail

PROG=ui-review-preflight

say() { printf '%s: %s\n' "$PROG" "$*"; }
err() { printf '%s: %s\n' "$PROG" "$*" >&2; }

usage() {
  err "usage: <pin>/.claude/skills/ui-standard-review/preflight.sh [GALLERY_DIR]"
  err "  run it from the SURFACE repo root; see the header of this file."
  exit 2
}

# Follow $0 through any number of symlinks and return the PHYSICAL directory it
# lives in. A consumer is told to symlink the skill directory, so this is the
# normal path and not an edge case: following the link is exactly what turns
# ".claude/skills/... at my root" back into "<pin>/.claude/skills/...".
resolve_script_dir() {
  src=$1
  while [ -L "$src" ]; do
    dir=$(cd -P "$(dirname "$src")" && pwd)
    src=$(readlink "$src")
    case $src in
      /*) ;;
      *) src=$dir/$src ;;
    esac
  done
  (cd -P "$(dirname "$src")" && pwd)
}

phys() { (cd -P "$1" && pwd); }

case ${1:-} in
  -h | --help) usage ;;
esac
[ "$#" -le 1 ] || usage

SKILL_DIR=$(resolve_script_dir "$0")

# The pin root is three levels up from <pin>/.claude/skills/ui-standard-review.
# THE SNIFF IS NOT CEREMONY. When a consumer COPIES the skill instead of
# symlinking it, the walk above lands on the CONSUMER root, and every check
# below that compares against the pin would then compare the copy with itself —
# green, and meaningless. Requiring a file only protogen has turns that into a
# refusal with an instruction, which is the difference between a wrong answer
# and no answer.
PIN_MARKER=tools/devcards/src/devcards/standard_brief.clj
if [ -n "${UI_REVIEW_PIN_ROOT:-}" ]; then
  PIN_ROOT=$(phys "$UI_REVIEW_PIN_ROOT")
else
  PIN_ROOT=$(phys "$SKILL_DIR/../../..")
fi
if [ ! -f "$PIN_ROOT/$PIN_MARKER" ]; then
  err "FATAL: cannot tell which checkout is the protogen PIN."
  err "  walked from: $SKILL_DIR"
  err "  guessed:     $PIN_ROOT   (no $PIN_MARKER there)"
  err "  This is what a COPIED skill directory looks like: the walk lands on"
  err "  your own root, so every pin comparison below would compare the copy"
  err "  with itself and pass green over a drifted launcher. Either symlink the"
  err "  skill directory at the pin (preferred — it cannot drift), or set"
  err "  UI_REVIEW_PIN_ROOT=<path to the protogen submodule>."
  exit 8
fi

SURFACE_ROOT=$(phys "${UI_REVIEW_SURFACE_ROOT:-$PWD}")
GALLERY_IN=${1:-${UI_REVIEW_GALLERY:-$SURFACE_ROOT/tools/devcards/docs/widgets}}

say "PIN_ROOT      $PIN_ROOT"
say "SURFACE_ROOT  $SURFACE_ROOT"

# ── 1. The launcher must exist AT THE SURFACE ROOT ──────────────────────────
# Agents and skills are discovered from the PROJECT ROOT's .claude/, never from
# a submodule mount, so the pin having them is not the consumer having them.
# -e follows symlinks, so a DANGLING link fails here rather than registering as
# a file that exists and cannot be read.
launcher_missing=
for rel in \
  .claude/agents/ui-standard-review.md \
  .claude/skills/ui-standard-review/SKILL.md \
  .claude/skills/ui-standard-review/STANDARD.md; do
  [ -e "$SURFACE_ROOT/$rel" ] || launcher_missing="$launcher_missing $rel"
done
if [ -n "$launcher_missing" ]; then
  err "FATAL: the review has NO LAUNCHER at $SURFACE_ROOT."
  for rel in $launcher_missing; do err "  missing (or dangling): $rel"; done
  err "  Claude Code discovers agents and skills from the PROJECT ROOT only, so"
  err "  the copy inside the pin does not register here and subagent_type"
  err "  ui-standard-review does not exist. Install it from the pin:"
  err ""
  err "    mkdir -p .claude/agents .claude/skills"
  err "    ln -s $PIN_ROOT/.claude/agents/ui-standard-review.md \\"
  err "          .claude/agents/ui-standard-review.md"
  err "    ln -s $PIN_ROOT/.claude/skills/ui-standard-review \\"
  err "          .claude/skills/ui-standard-review"
  err ""
  err "  (relative links are fine and are what you commit; absolute ones are"
  err "  printed here only because they are unambiguous to paste.)"
  exit 6
fi

launcher_stale=
for rel in \
  .claude/agents/ui-standard-review.md \
  .claude/skills/ui-standard-review/SKILL.md \
  .claude/skills/ui-standard-review/STANDARD.md; do
  cmp -s "$SURFACE_ROOT/$rel" "$PIN_ROOT/$rel" || launcher_stale="$launcher_stale $rel"
done
if [ -n "$launcher_stale" ]; then
  err "FATAL: the installed launcher has DRIFTED from the pin."
  for rel in $launcher_stale; do err "  differs from the pin: $rel"; done
  err "  The standard belongs to the pin and may not be forked (CLAUDE.md"
  err "  §\"WHY IT MAY NOT BE FORKED\"): a local edit means two stacks judging the same"
  err "  screen by different rules. Re-sync from $PIN_ROOT, or replace the copy"
  err "  with a symlink so it cannot drift again."
  exit 7
fi
say "launcher      installed and identical to the pin"

# ── 2. The gallery must be the SURFACE's, and must have renders ─────────────
if [ ! -d "$GALLERY_IN" ]; then
  err "FATAL: NOT DISCHARGED — no gallery at: $GALLERY_IN"
  err "  This is NOT the same as being out of scope, and the difference is the"
  err "  whole point of this check. Scope is decided by what PAINTS the pixels:"
  err "  a surface whose pixels come from controls.wasm rendering a ui.Screen is"
  err "  in scope whether or not a harness exists yet. controls_dump_tree is how"
  err "  you INSPECT an in-scope surface, never how you decide scope."
  err "  So: an obligation NOT YET DISCHARGED, owed a gallery."
  err "  If your gallery lives elsewhere, name it:"
  err "    UI_REVIEW_GALLERY=<your gallery root> $0"
  err "  Point it at YOUR renders. Pointing it at the pin is exit 4, not a fix."
  exit 3
fi
GALLERY=$(phys "$GALLERY_IN")

if [ "$SURFACE_ROOT" != "$PIN_ROOT" ]; then
  case $GALLERY/ in
    "$PIN_ROOT"/*)
      err "FATAL: WRONG SURFACE — that gallery belongs to the PIN, not to you."
      err "  gallery: $GALLERY"
      err "  pin:     $PIN_ROOT"
      err "  Those are protogen's own committed widget renders. Reviewing them"
      err "  produces a plausible-looking report that discharges nothing, which"
      err "  is worse than an empty one because it looks like work."
      err "  Review YOUR renders: UI_REVIEW_GALLERY=<your gallery root> $0"
      exit 4
      ;;
  esac
fi

units=0
renders=0
roster=
for d in "$GALLERY"/*/; do
  [ -d "$d" ] || continue
  # A CONSUMER'S GALLERY IS NOT REQUIRED TO BE JPEG, and by this standard's own
  # reasoning it should not be. § "Golden hashes and pixel identity" records that
  # compression ringing, blocking and the black backdrop under transparent pixels
  # are ARTEFACTS OF THE GALLERY rather than renderer defects — a gallery that never
  # encoded to JPEG carries none of them. protogen's own renders are .jpg because
  # its devcards docs pipeline emits that; nothing about the REVIEW requires it.
  # While this matched .jpg alone, a lossless gallery was indistinguishable from no
  # gallery at all: both exited 5, and the refusal blamed the consumer for not
  # minting renders it had in fact already minted.
  n=$(find "$d" -maxdepth 1 -type f \
       \( -name '*.jpg' -o -name '*.jpeg' -o -name '*.png' \) | wc -l)
  [ "$n" -gt 0 ] || continue
  units=$((units + 1))
  renders=$((renders + n))
  roster="$roster$(basename "$d") $n
"
done

if [ "$units" -eq 0 ]; then
  err "FATAL: NOT DISCHARGED — $GALLERY holds no unit directory with renders."
  err "  An empty batch reports clean, and a clean report over zero images is"
  err "  the exact false green this check exists to prevent. Mint the gallery"
  err "  first; until then the obligation is outstanding, not satisfied."
  exit 5
fi

say "GALLERY       $GALLERY"
say "$units unit(s), $renders render(s) — this roster IS the batch; do not glob for more"
printf '%s' "$roster" | sort | while read -r u n; do printf '  %-28s %s\n' "$u" "$n"; done
say "inputs resolved. A clean preflight is NOT a discharged review — it says the"
say "reviewer would look at the right images, not that anyone looked."
