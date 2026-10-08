#!/usr/bin/env bash
# prepush_scan_test.sh — how .githooks/pre-push acts on the private-name scan.
#
# The hook's scan block and final line are extracted between their markers and
# run against a stub scanner returning each exit code the real one has: 0
# (clean) and 4 (NOT RUN, no list) let the push continue, and 4 must make the
# LAST line say the scan did not run; 1 (a match) and 3 (CANNOT RUN) block with
# exit 1. The stub also records the arguments, so the hook's remote reaching the
# scanner as `--remote` is asserted too. Needs bash and awk.
#
# Usage: bash tools/lint/test/prepush_scan_test.sh
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
HOOK="$here/../../../.githooks/pre-push"
[ -f "$HOOK" ] || { echo "CANNOT RUN — no hook at $HOOK" >&2; exit 3; }
work="$(mktemp -d)"; trap 'rm -rf -- "$work"' EXIT
extract() { awk -v b="---8<--- $1 BEGIN" -v e="---8<--- $1 END" 'index($0,b){on=1} on{print} index($0,e){on=0}' "$HOOK"; }
extract 'private-name scan' >"$work/scan.sh"; extract 'final line' >"$work/final.sh"
grep -q 'private_names.sh' "$work/scan.sh" && grep -q 'gates green' "$work/final.sh" \
  || { echo 'CANNOT RUN — the hook has no marked private-name scan block or final line' >&2; exit 3; }
mkdir -p "$work/tree/tools/lint"
cat >"$work/tree/tools/lint/private_names.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" >"$STUB_ARGS"; exit "$STUB_RC"
STUB
chmod +x "$work/tree/tools/lint/private_names.sh"
PASS=0 FAILED=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; }
# hook_with <scanner-rc> — run the scan block then the final line, as the hook does.
hook_with() {
  (cd "$work/tree" && STUB_RC="$1" STUB_ARGS="$work/args" \
    bash -c 'set -uo pipefail; scan="$0" final="$1"; shift; . "$scan"; . "$final"' "$work/scan.sh" "$work/final.sh" origin 2>&1)
}
for rc in 0 4; do
  out="$(hook_with "$rc")" && code=0 || code=$?
  [ "$code" = 0 ] && ok "scanner exit $rc: the push continues" || bad "scanner exit $rc blocked the push (exit $code)"
done
out="$(hook_with 4)" || true
case "$(printf '%s\n' "$out" | tail -1)" in *'did NOT RUN'*) ok 'scanner exit 4: the LAST line says the scan did not run' ;; *) bad "exit 4 left a plain final line: $(printf '%s\n' "$out" | tail -1)" ;; esac
out="$(hook_with 0)" || true
case "$(printf '%s\n' "$out" | tail -1)" in *'did NOT RUN'*) bad 'a clean scan claimed it did not run' ;; *'gates green'*) ok 'scanner exit 0: the last line is plain green' ;; *) bad "exit 0: unexpected final line" ;; esac
for rc in 1 3; do
  out="$(hook_with "$rc")" && code=0 || code=$?
  [ "$code" = 1 ] && case "$out" in *BLOCKED*) true ;; *) false ;; esac && ok "scanner exit $rc: the push is BLOCKED" || bad "scanner exit $rc did not block (exit $code)"
done
hook_with 0 >/dev/null || true
[ "$(paste -sd' ' "$work/args")" = '--remote origin' ] && ok "the hook's remote reaches the scanner as --remote" || bad "scanner got: $(paste -sd' ' "$work/args")"

printf '\n%s passed, %s failed\n' "$PASS" "$FAILED"
[ "$PASS" -gt 0 ] || { echo 'CANNOT RUN — no case executed' >&2; exit 3; }
[ "$FAILED" -eq 0 ]
