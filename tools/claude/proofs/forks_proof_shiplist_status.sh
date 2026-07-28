#!/usr/bin/env bash
# CLAUSE A — ship-list's exit status must be honoured.
set -uo pipefail
REPO="$1"; S="$REPO/.fork-scratch"
# Sibling proof files live beside THIS script; $S stays the scratch area.
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"; MODE="$2"
RIG="$S/pA-$MODE"
bash "$HERE/forks_rig.sh" "$REPO" "$RIG" >/dev/null
[ "$MODE" = mutated ] && python3 "$HERE/forks_mutate_shiplist_status.py" "$RIG/tools/claude/forks.sh"
printf '  mutation landed? guard lines = %s | process-substitution feed lines = %s\n' \
  "$(grep -c 'ship-list FAILED' "$RIG/tools/claude/forks.sh")" \
  "$(grep -c 'done < <("\$BRIEF_CHECK" ship-list' "$RIG/tools/claude/forks.sh")"
mv "$RIG/tools/claude/brief-check.sh" "$RIG/tools/claude/real-brief-check.sh"
printf '#!/usr/bin/env bash\nset -uo pipefail\nif [ "${1:-}" = "ship-list" ]; then\n  printf "[brief-check] ERROR — forced internal error\\n" >&2\n  exit 3\nfi\nexec "$(dirname -- "$0")/real-brief-check.sh" "$@"\n' > "$RIG/tools/claude/brief-check.sh"
chmod +x "$RIG/tools/claude/brief-check.sh"
export PROTOGEN_FORKS_STATE_DIR="$S/stA-$MODE"
out="$(bash "$RIG/tools/claude/forks.sh" claim "$S/fkA-$MODE" TA own "$HERE/forks_proof_inline.brief" 2>&1)"; rc=$?
printf '%s\n' "$out" | grep -vE 'UNJUDGED|^  - ' | sed 's/^/  | /'
printf '  >>> claim exit status: %s\n' "$rc"
printf '  >>> manifest says: %s\n' "$(bash "$RIG/tools/claude/forks.sh" list | tail -n +2 | tr '\t' ' ' | cut -d' ' -f1-2)"
printf '  >>> worker inputs present in fork: %s\n' \
  "$([ -d "$S/fkA-$MODE/.protogen/research" ] && echo YES || echo NO)"
