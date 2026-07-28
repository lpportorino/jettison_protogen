#!/usr/bin/env bash
# REPRODUCTION (not a fix) — brief-check.sh's citation extractor reads only
# backticked spans plus the OWNED/FORBIDDEN section bodies, so a source cited in
# a Markdown code block, or in bare prose, is invisible to it. claim then ships
# NOTHING and still reports CLAIMED. tools/claude/brief-check.sh is fenced for
# this fork, so this probe records the defect rather than closing it.
#
# Two briefs, byte-different only in HOW they cite the same two existing files.
set -uo pipefail
REPO="$1"; S="$REPO/.fork-scratch"
# Sibling proof files live beside THIS script; $S stays the scratch area.
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"; RIG="$S/cbrig"
bash "$HERE/forks_rig.sh" "$REPO" "$RIG" >/dev/null
for form in inline codeblock; do
  printf '===== citation form: %s =====\n' "$form"
  printf '  extractor (brief-check.sh ship-list) sees:\n'
  bash "$RIG/tools/claude/brief-check.sh" ship-list "$HERE/forks_proof_$form.brief" \
    --root "$RIG" | sed 's/^/    /'
  export PROTOGEN_FORKS_STATE_DIR="$S/cbstate-$form"
  bash "$RIG/tools/claude/forks.sh" claim "$S/cbfork-$form" T own \
    "$HERE/forks_proof_$form.brief" 2>&1 | grep -E '^\[forks\]|shipped \(|^    - ' | sed 's/^/  /'
  printf '  worker inputs present in the fork: %s\n' \
    "$([ -d "$S/cbfork-$form/.protogen/research" ] && echo YES || echo 'NO — dangling reference')"
done
