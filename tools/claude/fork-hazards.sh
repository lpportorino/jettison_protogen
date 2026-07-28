#!/usr/bin/env bash
# fork-hazards.sh — the two structural traps a fork LIFT keeps re-introducing.
#
# Both are mechanical, both recurred repeatedly inside a single ten-fork wave,
# and prose did not stop either. Neither is about a fork's CONTENT; both are
# about what happens when a fork's files land in this tree at a different path
# from the one they were written at.
#
# EXIT 0 clean · 1 findings · 2 usage · 3 internal error.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
cd -- "$ROOT" || { echo "[fork-hazards] ERROR — cannot enter repo root" >&2; exit 3; }

findings=0
note() { printf '[fork-hazards] FAIL — %s\n' "$*" >&2; findings=$((findings + 1)); }

# ---------------------------------------------------------------------------
# HAZARD 1 — anything TRACKED under the per-fork scratch directory.
#
# `.fork-scratch/` is deleted by `forks.sh release`, so tracked content there is
# destroyed by the harness's own cleanup, and a worker's `git add -A` sweeps
# repository files into its deliverable. It is gitignored — but a GIT IGNORE
# CANNOT STOP A CHERRY-PICK, which carries tracked content regardless, and that
# is exactly how it came back after being cleared. So the ignore rule is the
# guard for authoring and this check is the guard for LIFTING.
# ---------------------------------------------------------------------------
tracked_scratch="$(git ls-files '.fork-scratch' '.fork-scratch/**' 2>/dev/null)"
if [ -n "$tracked_scratch" ]; then
  note "tracked files under .fork-scratch/ — release deletes that directory, so these
  would be destroyed by the harness's own cleanup. A cherry-pick carries tracked
  content past the ignore rule; move them beside the code they measure:"
  printf '    %s\n' $tracked_scratch >&2
fi

# ---------------------------------------------------------------------------
# HAZARD 2 — a script whose repo root does not resolve FROM WHERE IT SITS.
#
# A tool that computes its root from its own location silently retargets when
# moved, and a lift moves nearly every probe a fork writes. The failure is the
# expensive kind: every leg fails for a reason unrelated to what is under test,
# and that red is indistinguishable from a caught defect.
#
# The check RUNS the script's own ROOT expression from the script's real
# directory and asserts it lands on this repo. That is the property; reading the
# number of `..` segments would be a second spelling of it.
# ---------------------------------------------------------------------------
while IFS= read -r f; do
  [ -n "$f" ] || continue
  expr_line="$(grep -m1 -E '^[[:space:]]*ROOT=.*BASH_SOURCE' -- "$f" || true)"
  [ -n "$expr_line" ] || continue
  # Substitute the real path for ${BASH_SOURCE[0]} and evaluate. Faking the
  # runtime instead — eval'ing the line as-is, or assigning BASH_SOURCE inside
  # `bash -c` — does NOT work: BASH_SOURCE refers to the evaluating context, not
  # to the file, so the expression silently resolves somewhere else and the check
  # reports a plausible wrong answer. That is the same class of error this check
  # exists to catch, and it was made once while writing it.
  subst="${expr_line#*ROOT=}"
  subst="${subst//\$\{BASH_SOURCE\[0\]\}/$ROOT/$f}"
  got="$(eval "printf '%s' ${subst}" 2>/dev/null)"
  if [ -z "$got" ]; then
    note "$f: its ROOT expression did not evaluate; cannot tell what it resolves to"
    continue
  fi
  if [ "$got" != "$ROOT" ]; then
    note "$f: ROOT resolves to '$got', not the repo root '$ROOT' — every path below it
  is retargeted, and the resulting red looks exactly like a caught defect"
  fi
done < <(git ls-files '*.sh' | grep -E '/(dev|proofs|wire-contract-proofs)/' || true)

if [ "$findings" -gt 0 ]; then
  printf '[fork-hazards] %d finding(s)\n' "$findings" >&2
  exit 1
fi
printf '[fork-hazards] clean — no tracked scratch, and every dev/proof script resolves this repo root\n'
