#!/usr/bin/env bash
# uber_seed_test.sh — the cargo registry seed in tools/uber.sh, run on the host.
#
# The block between uber.sh's `cargo registry seed` markers is extracted and run
# with a fixture registry (UBER_SEED_FROM) and a scratch CARGO_HOME: a first run
# seeds it; three concurrent first runs leave ONE registry, identical to the
# source, and no temp copy behind; an existing registry is never replaced; a
# seed that cannot be made says FAILED; the network-removed mode exports
# CARGO_NET_OFFLINE and the attached mode does not. Needs bash and flock.
#
# Usage: bash tools/uber_seed_test.sh
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
UBER="$here/uber.sh"
command -v flock >/dev/null 2>&1 || { echo 'CANNOT RUN — flock is not on PATH (util-linux)' >&2; exit 3; }
work="$(mktemp -d)"; trap 'rm -rf -- "$work"' EXIT
block="$work/seed.sh"
awk '/---8<--- cargo registry seed BEGIN/ {on=1} on {print} /---8<--- cargo registry seed END/ {on=0}' "$UBER" >"$block"
grep -q 'seed_from=' "$block" && grep -q -- '---8<--- cargo registry seed END' "$block" \
  || { echo 'CANNOT RUN — no cargo registry seed block between the markers in uber.sh' >&2; exit 3; }
PASS=0 FAILED=0
ok() { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2; }

src="$work/registry"; mkdir -p "$src/cache/index" "$src/src/crate-1"
for i in $(seq 1 40); do printf 'crate %s\n' "$i" >"$src/src/crate-1/f$i.rs"; done
printf 'index\n' >"$src/cache/index/x"
# seed <cargo-home> [mode] — run the block; prints what it printed and the offline flag.
# Hermetic: inside a network-removed uber.sh container CARGO_NET_OFFLINE is
# already exported, and the attached-mode case must not inherit it.
seed() { env -u CARGO_NET_OFFLINE CARGO_HOME="$1" UBER_SEED_FROM="$src" UBER_NETWORK_MODE="${2:-}" bash -c '. "$0"; echo "offline=${CARGO_NET_OFFLINE:-unset}"' "$block" 2>&1; }
same_tree() { diff -r "$1" "$2" >/dev/null 2>&1; }

printf '\n== the seed\n'
out="$(seed "$work/h1")"
same_tree "$src" "$work/h1/registry" && ok 'a first run seeds a registry identical to the source' || bad "first seed differs: $out"
[ -z "$(find "$work/h1" -maxdepth 1 -name 'registry.seed.*')" ] && ok '... and leaves no temp copy behind' || bad 'a temp copy was left behind'

printf 'mine\n' >"$work/h1/registry/marker"
seed "$work/h1" >/dev/null
[ -e "$work/h1/registry/marker" ] && ok 'an existing registry is never replaced or merged into' || bad 'an existing registry was replaced'

for i in 1 2 3; do seed "$work/h2" >"$work/c$i.log" & done; wait
same_tree "$src" "$work/h2/registry" && ok 'three concurrent first runs leave ONE registry identical to the source' || bad 'concurrent seeds corrupted the registry'
[ -z "$(find "$work/h2" -maxdepth 1 -name 'registry.seed.*')" ] && ok '... no losing copy is left behind' || bad 'a losing copy was left behind'
! grep -q FAILED "$work"/c*.log && ok '... and none of them reports a failure' || bad "a concurrent seed reported FAILED: $(cat "$work"/c*.log)"

# The lock's job: concurrent first runs COPY exactly once (the rest wait, then
# find the registry). A logging cp shim on PATH counts the copies.
mkdir -p "$work/shim"; real_cp="$(command -v cp)"
printf '#!/usr/bin/env bash\necho copy >>"%s"\nsleep 0.2\nexec %s "$@"\n' "$work/copies" "$real_cp" >"$work/shim/cp"; chmod +x "$work/shim/cp"
for i in 1 2 3; do PATH="$work/shim:$PATH" seed "$work/h6" >/dev/null & done; wait
[ "$(grep -c copy "$work/copies" 2>/dev/null)" = 1 ] && ok 'three concurrent first runs make exactly ONE copy' \
  || bad "concurrent first runs made $(grep -c copy "$work/copies" 2>/dev/null) copies, not one"

# A CARGO_HOME under a regular file cannot be created even by root, which the
# container lanes run as; a read-only directory would not stop root.
printf 'not a directory\n' >"$work/afile"
out="$(seed "$work/afile/home")"
case "$out" in *'seed from the image FAILED'*) ok 'a seed that cannot be made says FAILED, never nothing' ;; *) bad "an impossible seed was silent: $out" ;; esac

printf '\n== the offline flag\n'
case "$(seed "$work/h4" none)" in *offline=true*) ok 'UBER_NETWORK=none exports CARGO_NET_OFFLINE' ;; *) bad 'the network-removed mode did not tell cargo it is offline' ;; esac
case "$(seed "$work/h5")" in *offline=unset*) ok 'with the network attached CARGO_NET_OFFLINE stays unset' ;; *) bad 'the attached mode told cargo it is offline' ;; esac

printf '\n%s passed, %s failed\n' "$PASS" "$FAILED"
[ "$PASS" -gt 0 ] || { echo 'CANNOT RUN — no case executed' >&2; exit 3; }
[ "$FAILED" -eq 0 ]
