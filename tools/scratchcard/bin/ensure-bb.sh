#!/usr/bin/env bash
# ensure-bb.sh — resolve a pinned babashka for the scratchcard client.
#
# WHY VENDOR RATHER THAN COMMIT. bb is a large statically-linked binary and it
# is PLATFORM-SPECIFIC: the sibling repos in this fleet fetch x86-64 and
# aarch64 builds. Committing one would pin this repo to a single architecture —
# which defeats portability rather than helping it — and would put the binary
# into the history of a submodule that ten consumer repos clone. Fetching per
# platform is the portable answer.
#
# WHY PINNED RATHER THAN LATEST. The sibling repos pin this version in most of
# their own homes, so matching it keeps the machine close to one bb. (They are
# not perfectly self-consistent, which is an argument FOR pinning rather than
# against it.) A floating version lets two forks differ silently, and a client
# regression then has no bisect. bb gates nothing here, so floating would be
# survivable — the cost is debuggability — but a pin costs one constant.
#
# KNOWN GAP: the installer is fetched from a MOVING ref and neither it nor the
# resulting binary is checksummed; only the reported version is verified.
#
# The install target is GITIGNORED (.protogen/bin). Nothing here is tracked.
#
# Every entry point that needs bb calls this rather than assuming a predecessor
# ran: a caller reaching for the binary before an installer finished hits
# ENOENT, which surfaces far from its cause.

set -euo pipefail

BB_VERSION="${SCRATCHCARD_BB_VERSION:-1.12.217}"
INSTALLER_URL="https://raw.githubusercontent.com/babashka/babashka/master/install"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BB_DIR="$ROOT/.protogen/bin"
BB="$BB_DIR/bb"

bb_ok() {
  [ -x "$BB" ] && "$BB" --version 2>/dev/null | grep -q "$BB_VERSION"
}

if bb_ok; then
  printf '%s\n' "$BB"
  exit 0
fi

# An EXISTING bb at the wrong version is replaced, not reused: two forks on one
# machine running different client versions is the condition the pin prevents.
if [ -x "$BB" ]; then
  echo "ensure-bb: replacing $("$BB" --version 2>/dev/null || echo unknown) with $BB_VERSION" >&2
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "ensure-bb: FATAL: curl not found — needed to fetch babashka $BB_VERSION." >&2
  echo "  install curl, or place a bb $BB_VERSION binary at $BB yourself." >&2
  exit 4
fi

mkdir -p "$BB_DIR"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "ensure-bb: fetching babashka $BB_VERSION into $BB_DIR" >&2
if ! curl -fsSL "$INSTALLER_URL" -o "$tmp/install"; then
  echo "ensure-bb: FATAL: could not download the babashka installer from $INSTALLER_URL" >&2
  echo "  this step needs network access once; the binary is cached afterwards." >&2
  exit 4
fi

chmod +x "$tmp/install"
if ! bash "$tmp/install" --version "$BB_VERSION" --dir "$BB_DIR" >&2; then
  echo "ensure-bb: FATAL: babashka install failed for version $BB_VERSION" >&2
  exit 4
fi

if ! bb_ok; then
  echo "ensure-bb: FATAL: installed bb is not version $BB_VERSION" >&2
  echo "  got: $("$BB" --version 2>/dev/null || echo 'no runnable binary')" >&2
  exit 4
fi

printf '%s\n' "$BB"
