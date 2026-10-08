#!/usr/bin/env bash
# Canary for run.sh's verdict mapping, with `docker` stubbed on PATH (hermetic).
#
# The mapping is the gate's whole meaning: 1 FAIL must mean an assertion about
# the viewer failed, and Node also exits 1 on ANY uncaught exception. So a node
# exit of 1 is FAIL only when the run's manifest records "kind": "FAIL".
# Exit: 0 all green; 1 a case FAILED; 3 CANNOT RUN.
set -uo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
root=$(cd -- "$here/../../.." && pwd -P)
subject="$here/run.sh"
[ -r "$subject" ] || { echo "[run.sh canary] CANNOT RUN — $subject missing" >&2; exit 3; }
mkdir -p "$root/.fork-scratch" && work=$(mktemp -d "$root/.fork-scratch/run-canary.XXXXXX") || { echo "[run.sh canary] CANNOT RUN — no scratch dir" >&2; exit 3; }
trap 'rm -rf "$work"' EXIT
pass=0 fail=0
ok()  { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2; fail=$((fail + 1)); }

mkdir -p "$work/fixtures" "$work/bin"
for f in branch dense signals incomplete malformed leaf open-signals; do
  mkdir -p "$work/fixtures/$f"; printf '<html></html>' >"$work/fixtures/$f/index.html"
done
# The stub docker: `npm ci` succeeds; `node acceptance.mjs FIX OUT ENGINE` behaves per $STUB_MODE.
cat >"$work/bin/docker" <<'EOF'
#!/usr/bin/env bash
args="$*"
case "$args" in *"npm ci"*) exit 0 ;; esac
out=""; prev=""
for a in "$@"; do [ "$prev" = acceptance.mjs ] && fix="$a"; [ -n "${fix:-}" ] && [ "$prev" = "$fix" ] && out="$a"; prev="$a"; done
host_out="${STUB_ROOT}/${out#/w/}"
case "$STUB_MODE" in
  green) mkdir -p "$host_out"; printf '{"completed": true, "failure": null}\n' >"$host_out/manifest.json"; exit 0 ;;
  fail)  mkdir -p "$host_out"; printf '{"completed": false, "failure": {"kind": "FAIL"}}\n' >"$host_out/manifest.json"; exit 1 ;;
  crash) echo "SyntaxError: Unexpected token" >&2; exit 1 ;;
  error) mkdir -p "$host_out"; printf '{"completed": false, "failure": {"kind": "ERROR"}}\n' >"$host_out/manifest.json"; exit 2 ;;
  compact) mkdir -p "$host_out"; printf '{"completed":false,"failure":{"kind":"FAIL"}}' >"$host_out/manifest.json"; exit 1 ;;
esac
EOF
chmod +x "$work/bin/docker"

case_run() { # case_run <mode> <expected-exit> <label>
  local rc=0 ev="$work/ev-$1"
  STUB_MODE=$1 STUB_ROOT="$root" PATH="$work/bin:$PATH" \
    bash "$subject" "$ev" --fixtures "$work/fixtures" >/dev/null 2>&1 || rc=$?
  if [ "$rc" -eq "$2" ]; then ok "$3 (exit $2)"; else bad "$3 — expected exit $2, got $rc"; fi
}
echo "== run.sh verdict mapping"
case_run green 0 "both engines green is green"
case_run fail 1 "an assertion failure recorded in the manifest is FAIL"
case_run crash 2 "node exiting 1 with NO manifest (a crash) is ERROR, never FAIL"
case_run error 2 "a recorded harness ERROR is ERROR"
case_run compact 1 "a FAIL in COMPACT JSON is still FAIL (the verdict is parsed, not grepped)"

echo
if [ "$fail" -eq 0 ]; then printf '\033[32m[run.sh canary] ALL GREEN — %d assertion(s)\033[0m\n' "$pass"; exit 0; fi
printf '\033[31m[run.sh canary] %d FAILED, %d passed\033[0m\n' "$fail" "$pass" >&2; exit 1
