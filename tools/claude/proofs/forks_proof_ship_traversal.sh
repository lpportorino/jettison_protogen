#!/usr/bin/env bash
# CLAUSE B — a shipped source must not be written outside the fork.
set -uo pipefail
REPO="$1"; S="$REPO/.fork-scratch"
# Sibling proof files live beside THIS script; $S stays the scratch area.
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"; MODE="$2"
RIG="$S/pB-$MODE"; HOLD="$S/holdB-$MODE"
bash "$HERE/forks_rig.sh" "$REPO" "$RIG" >/dev/null
[ "$MODE" = mutated ] && python3 "$HERE/forks_mutate_ship_traversal.py" "$RIG/tools/claude/forks.sh"
printf '  mutation landed? traversal-arm lines = %s | absolute-arm lines = %s (neighbour, must stay 1)\n' \
  "$(grep -c "has a '\.\.' component" "$RIG/tools/claude/forks.sh")" \
  "$(grep -c 'is not a repository-relative path' "$RIG/tools/claude/forks.sh")"
mkdir -p "$HOLD" "$S/pwnB-$MODE"
printf 'SOURCE\n' > "$S/pwnB-$MODE/loot.txt"
cat > "$S/travB.brief" <<BRF
# BRIEF
Read \`tools/../../pwnB-$MODE/loot.txt\`.
## FILES YOU OWN
- \`tools/hello.sh\`
BRF
export PROTOGEN_FORKS_STATE_DIR="$S/stB-$MODE"
out="$(bash "$RIG/tools/claude/forks.sh" claim "$HOLD/fk" TB own "$S/travB.brief" 2>&1)"; rc=$?
printf '%s\n' "$out" | grep -vE 'UNJUDGED|^  - ' | sed 's/^/  | /'
printf '  >>> claim exit status: %s\n' "$rc"
printf '  >>> file written OUTSIDE the fork? %s\n' \
  "$([ -e "$HOLD/pwnB-$MODE/loot.txt" ] && echo "YES -> $HOLD/pwnB-$MODE/loot.txt" || echo NO)"
