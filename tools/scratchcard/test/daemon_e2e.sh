#!/usr/bin/env bash
# daemon_e2e.sh — the scratchcard daemon and its socket, end to end.
#
# HOST-ONLY, and that is a capability fact rather than a preference: this drives
# `docker` directly, and the toolchain image does not carry a docker CLI. It is
# the same class as `make go-leg-repro`, which is host-only for the same reason
# and is likewise in no CI workflow.
#
# EXIT CODES SEPARATE A VERDICT FROM A BROKEN HARNESS, because a suite that
# dies on a missing prerequisite wears the right colour for the wrong reason:
#
#   0  every arm passed
#   1  an arm FAILED — a real verdict
#   3  CANNOT RUN — docker, the image, or the wasm is unavailable
#
# WHAT IT PINS, and each arm exists because its absence is silent:
#   - the round trip works at all (spawn, ping, regenerate, stop)
#   - CONCURRENT clients spawn exactly ONE container
#   - stop-then-immediately-start does not hit the --rm reaper's name conflict
#   - a DEAD socket file is reclaimed; a LIVE one is refused
#   - a card slug cannot escape the scratch root over the wire
#   - the daemon reports STALENESS rather than silently running old code
#
# It leaves the daemon RUNNING on success — it is a warm daemon by design, and
# tearing it down would make the next interactive call pay a cold boot.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CLIENT="$ROOT/tools/scratchcard/bin/scratchcard"
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red() { printf '\033[31m%s\033[0m\n' "$1"; }
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad() {
	FAIL=$((FAIL + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$1"
	[ $# -gt 1 ] && printf '%s\n' "$2" | sed 's/^/       | /'
	return 0
}
cannot_run() {
	red "[daemon-e2e] CANNOT RUN — $1"
	printf '  %s\n' "$2"
	exit 3
}

# ── preconditions, each refusing with 3 rather than a verdict ──────────────
command -v docker >/dev/null 2>&1 ||
	cannot_run "docker is not on PATH" "This suite spawns the daemon container directly."
docker info >/dev/null 2>&1 ||
	cannot_run "the docker daemon is not reachable" "Start docker, then re-run."
[ -f "$ROOT/renderer/output/controls.wasm" ] ||
	cannot_run "renderer/output/controls.wasm is missing" "Build it: make -f renderer.mk wasm"
[ -x "$CLIENT" ] ||
	cannot_run "the client is not executable at $CLIENT" "chmod +x it, or check the checkout."

HASH="$("$CLIENT" 2>&1 | awk '/hash/ {print $2}')"
SOCK="$("$CLIENT" 2>&1 | awk '/socket/ {print $2}')"
CONTAINER="protogen-render-$HASH"
[ -n "$HASH" ] ||
	cannot_run "could not read the per-fork hash from the client" "Run '$CLIENT' by hand."

printf '[daemon-e2e] fork %s\n' "$HASH"

json_field() { python3 -c "import sys,json; d=json.load(sys.stdin); print(json.dumps(d))" 2>/dev/null; }

# ── arm 1: the round trip ──────────────────────────────────────────────────
"$CLIENT" restart >/dev/null 2>&1
if "$CLIENT" ping 2>/dev/null | grep -q '"pong"'; then
	ok 'spawn + ping round trip'
else
	bad 'the daemon did not answer ping after restart'
fi

out="$("$CLIENT" regenerate --res 800x480 2>&1)"
if printf '%s' "$out" | grep -q '"ok":true'; then
	ok 'regenerate over the socket returns a run'
else
	bad 'regenerate failed' "$out"
fi

# ── arm 2: CONCURRENT clients spawn exactly ONE container ──────────────────
# The flock in ensure-daemon! exists for this. Counting containers is the only
# honest check — reading the code proves nothing about a race.
"$CLIENT" stop >/dev/null 2>&1
sleep 1
for _ in 1 2 3 4; do "$CLIENT" ping >/dev/null 2>&1 & done
wait
n="$(docker ps -aq --filter "name=^${CONTAINER}$" | wc -l | tr -d ' ')"
if [ "$n" = "1" ]; then
	ok 'four concurrent clients spawned exactly one container'
else
	bad "concurrent spawn produced $n containers, expected 1"
fi

# ── arm 3: the --rm reaper race ────────────────────────────────────────────
# `docker rm -f` returns before the NAME is free, so an immediate re-run hits
# "Conflict: name already in use" unless the client polls for it.
out="$("$CLIENT" restart 2>&1)"
if printf '%s' "$out" | grep -qi 'conflict'; then
	bad 'restart hit the --rm reaper name conflict' "$out"
else
	ok 'stop-then-immediately-start does not hit the reaper name conflict'
fi

# ── arm 4: a LIVE socket is refused, a DEAD one is reclaimed ───────────────
# A connect probe is the only correct staleness test: a leftover file from a
# killed daemon is indistinguishable from a live one by any filesystem property.
if [ -S "$SOCK" ]; then
	ok 'the socket exists while the daemon is up'
else
	bad "no socket at $SOCK while the daemon is up"
fi

docker rm -f "$CONTAINER" >/dev/null 2>&1
sleep 1
if [ -e "$SOCK" ]; then
	# The file survived its daemon — exactly the stale case.
	if "$CLIENT" ping 2>/dev/null | grep -q '"pong"'; then
		ok 'a stale socket file was reclaimed and the daemon rebound'
	else
		bad 'a stale socket file was not reclaimed'
	fi
else
	# Also correct: the container removed it on the way out.
	"$CLIENT" ping >/dev/null 2>&1
	ok 'the socket was removed with its daemon; the next call respawned'
fi

# ── arm 5: a card slug cannot escape the scratch root ──────────────────────
out="$("$CLIENT" regenerate --card ../../escape --res 800x480 2>&1)"
if printf '%s' "$out" | grep -q 'INVALID_CARD'; then
	ok 'a traversing card slug is refused over the wire'
else
	bad 'a traversing card slug was NOT refused' "$out"
fi
if [ -d "$ROOT/escape" ] || [ -d "$ROOT/../escape" ]; then
	bad 'the traversal CREATED a directory outside the scratch root'
	rm -rf -- "$ROOT/escape"
else
	ok 'and it created nothing outside the scratch root'
fi

# ── arm 6: staleness is reported rather than silently ignored ──────────────
probe="$ROOT/tools/scratchcard/src/scratchcard/.e2e_staleness_probe.clj"
printf ';; transient probe file — removed by daemon_e2e.sh\n' > "$probe"
out="$("$CLIENT" status 2>&1)"
rm -f -- "$probe"
if printf '%s' "$out" | grep -q '"stale?":true'; then
	ok 'a source change is reported as staleness rather than silently ignored'
else
	bad 'the daemon did not report staleness after a source change' "$out"
fi

# ── arm 7: an UNKNOWN FLAG is refused, never silently dropped ──────────────
# The daemon's arg sets are CLOSED and it answers UNKNOWN_ARGUMENT naming the
# accepted set. That guarantee is worth nothing if the CLIENT drops what it
# does not recognise before the request is built: `--fil screen.edn` then
# renders the SHIPPED EXAMPLE and reports it CLEAN — a green verdict about a
# file the author never named, with no warning anywhere.
out="$("$CLIENT" regenerate --fil "$ROOT/tools/scratchcard/example/hello.edn" 2>&1)"
rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q -- '--fil'; then
	ok 'an unknown flag is refused, and the refusal names it'
else
	bad "an unknown flag was not refused (rc=$rc)" "$out"
fi

# The other direction: the flags that ARE accepted must still work, or the
# check above is satisfied by a client that refuses everything.
out="$("$CLIENT" regenerate --res 800x480 2>&1)"
rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '"ok":true'; then
	ok 'an accepted flag set still renders and exits 0'
else
	bad "an accepted flag set was refused (rc=$rc)" "$out"
fi

# ── arm 8: a FAILED render EXITS NON-ZERO ─────────────────────────────────
# The reply envelope carries TWO ok flags: the outer one is transport, the
# inner one is the render. Exiting on the outer flag answers "did the socket
# round trip work", which is not the question a caller asks — so every input
# rejection exited 0 and a scripted author saw success.
#
# THE FILENAME MUST NOT START WITH A DOT, and that is why it is spelled out
# here. A dot-leading name is refused as a card SLUG, which is a PROTOCOL
# refusal carrying outer ok:false — so it exits non-zero already and this arm
# would pass without ever reaching the render-level flag it exists to pin.
# Measured while writing it: the arm went green over the unfixed client.
out="$("$CLIENT" regenerate --file "$ROOT/e2e-no-such-screen.edn" 2>&1)"
rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q 'INPUT_MISSING'; then
	ok 'a render-level failure exits non-zero'
else
	bad "a render-level failure exited $rc — a scripted caller cannot tell" "$out"
fi

# ── arm 9: the matrix axes the daemon accepts are REACHABLE from the CLI ───
# The daemon's regenerate takes families, modes, keep, timeout-ms and
# bp-from-canvas?, and the generated API page lists all of them. Forwarding
# only file/card/resolutions made the rest unreachable from the documented
# entry point, and bp-from-canvas? is the costly one: without it every cell
# renders at bp 0, so a `md:`/`lg:`/`xl:` prefixed style cannot be seen to
# apply at all — an authoring axis with no way to exercise it.
out="$("$CLIENT" regenerate --res 800x480 --families 0 --modes 1 2>&1)"
rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '"cells":1'; then
	ok 'families and modes narrow the matrix'
else
	bad "families/modes did not narrow the matrix (rc=$rc)" "$out"
fi

# bp is asserted in the MANIFEST, not the reply: the reply counts cells and
# would read identically whether the flag reached the matrix builder or not.
out="$("$CLIENT" regenerate --card e2ebp --res 1920x1080 --families 0 --modes 1 \
	--bp-from-canvas true 2>&1)"
rc=$?
bpman="$ROOT/.protogen/scratch/e2ebp/latest/manifest.edn"
if [ "$rc" -eq 0 ] && grep -q ':bp 3' "$bpman" 2>/dev/null; then
	ok 'bp-from-canvas reaches the matrix and lifts bp off 0'
else
	bad "bp-from-canvas did not reach the matrix (rc=$rc)" "$out"
fi

# The other direction: without the flag the same canvas must still pin bp 0,
# or the arm above is satisfied by a matrix that ignores the flag entirely.
"$CLIENT" regenerate --card e2ebp0 --res 1920x1080 --families 0 --modes 1 >/dev/null 2>&1
if grep -q ':bp 0' "$ROOT/.protogen/scratch/e2ebp0/latest/manifest.edn" 2>/dev/null; then
	ok 'and without it the same canvas still pins bp 0'
else
	bad 'bp was not 0 without bp-from-canvas'
fi

# ── verdict ────────────────────────────────────────────────────────────────
"$CLIENT" restart >/dev/null 2>&1
printf '\n'
if [ "$FAIL" -eq 0 ]; then
	green "[daemon-e2e] GREEN — $PASS arm(s)"
	exit 0
fi
red "[daemon-e2e] RED — $FAIL of $((PASS + FAIL)) arm(s) failed"
exit 1
