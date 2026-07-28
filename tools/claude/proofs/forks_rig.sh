#!/usr/bin/env bash
# Build a minimal, faithful ROOT for exercising tools/claude/forks.sh end to end.
# ROOT is derived from the script's own location, so copying forks.sh here
# deliberately retargets it at the rig instead of the real repo.
set -euo pipefail
SRC="$1"; RIG="$2"
rm -rf -- "$RIG"
mkdir -p -- "$RIG/tools/claude" "$RIG/renderer/src" "$RIG/.protogen/research" "$RIG/.fork-scratch"
cp -- "$SRC/tools/claude/forks.sh" "$RIG/tools/claude/forks.sh"
cp -- "$SRC/tools/claude/brief-check.sh" "$RIG/tools/claude/brief-check.sh"
chmod +x "$RIG/tools/claude/forks.sh" "$RIG/tools/claude/brief-check.sh"
printf 'echo hi\n' > "$RIG/tools/hello.sh"
printf 'int main(void){return 0;}\n' > "$RIG/renderer/src/renderer.c"
printf 'all:\n\t@true\n' > "$RIG/renderer.mk"
printf '.protogen/\n' > "$RIG/.gitignore"
printf 'THE EVIDENCE THE WORKER NEEDS\n' > "$RIG/.protogen/research/evidence-a.md"
printf 'MORE EVIDENCE\n' > "$RIG/.protogen/research/evidence-b.md"
env -u GIT_DIR -u GIT_WORK_TREE git -C "$RIG" init --quiet -b master
env -u GIT_DIR -u GIT_WORK_TREE git -C "$RIG" add -A
env -u GIT_DIR -u GIT_WORK_TREE git -C "$RIG" -c user.name=t -c user.email=t@l commit --quiet -m base
