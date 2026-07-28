#!/usr/bin/env bash
# CLAUSE C — the coordinator's shipped-source record must not live in
# worker-writable, release-deleted space.
set -uo pipefail
REPO="$1"; S="$REPO/.fork-scratch"
# Sibling proof files live beside THIS script; $S stays the scratch area.
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"; MODE="$2"; SCENARIO="$3"
RIG="$S/pC-$MODE-$SCENARIO"; FK="$S/fkC-$MODE-$SCENARIO"
bash "$HERE/forks_rig.sh" "$REPO" "$RIG" >/dev/null
[ "$MODE" = mutated ] && python3 "$HERE/forks_mutate_record_location.py" "$RIG/tools/claude/forks.sh"
printf '  mutation landed? state-dir record lines = %s | residue-check lines = %s (neighbour, must stay 2)\n' \
  "$(grep -c 'shipped/%s.txt' "$RIG/tools/claude/forks.sh")" \
  "$(grep -c 'uncommitted residue' "$RIG/tools/claude/forks.sh")"
printf 'UNTRACKED, NON-IGNORED EVIDENCE\n' > "$RIG/tools/scratch-note.md"
cat > "$S/nonignC.brief" <<'BRF'
# BRIEF
Read `tools/scratch-note.md` before starting.
## FILES YOU OWN
- `tools/hello.sh`
BRF
export PROTOGEN_FORKS_STATE_DIR="$S/stC-$MODE-$SCENARIO"
bash "$RIG/tools/claude/forks.sh" claim "$FK" TC own "$S/nonignC.brief" 2>&1 | grep -E 'shipped \(|FAIL' | sed 's/^/  | /'
G() { env -u GIT_DIR -u GIT_WORK_TREE git -C "$FK" "$@"; }
printf 'echo done\n' >> "$FK/tools/hello.sh"; printf 'report\n' > "$FK/FINAL_REPORT.md"
G add tools/hello.sh FINAL_REPORT.md; G -c user.name=w -c user.email=w@l commit -qm work
case "$SCENARIO" in
  tidy)     rm -rf "$FK/.fork-scratch" ;;                                  # worker tidies its own scratch
  residue)  rm -rf "$FK/.fork-scratch"; printf 'x\n' > "$FK/tools/REAL_LEFTOVER.md" ;;
esac
out="$(bash "$RIG/tools/claude/forks.sh" release "$FK" --owner-signalled done 2>&1)"; rc=$?
printf '%s\n' "$out" | sed 's/^/  | /'
printf '  >>> release exit status: %s\n' "$rc"
