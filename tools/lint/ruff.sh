#!/usr/bin/env bash
# Reproducible standalone Ruff, installed only inside the development container.
# Official release assets and their extracted binary digests are both pinned.
# An image may prewarm RUFF_CACHE_DIR; normal use needs no network afterward.
set -euo pipefail

version=0.16.10
case "$(uname -m)" in
  x86_64)
    arch=x86_64
    archive_sha=9567ff1201e2fb3da31ff04c35587d768c66d6cb42dfa84de474e2bfe360b608
    binary_sha=14704de5ea0d07038512de127bd52d6a169fb70bd70ddfa25bf39e21fed6b2b6
    ;;
  aarch64 | arm64)
    arch=aarch64
    archive_sha=dc0d74de837ef0a7bcc62ce98c48a622b075d057161f13b958be2934becd55a6
    binary_sha=1bd5df1e12e223c7bcd3c8545f48fccf5e2598db12c57b9f6184e16bca8e9ba3
    ;;
  *)
    printf 'Ruff: unsupported architecture: %s\n' "$(uname -m)" >&2
    exit 2
    ;;
esac

cache=${RUFF_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/protogen/ruff}
install_dir="$cache/$version/$arch"
binary="$install_dir/ruff"
if [[ ! -f "$binary" ]]; then
  [[ -e /.dockerenv || -e /run/.containerenv || ${RUFF_CONTAINER_BUILD:-} == 1 ]] || {
    printf 'Ruff: populate its pinned cache inside the official development container.\n' >&2
    exit 2
  }
  mkdir -p "$install_dir"
  stage=$(mktemp -d "$install_dir/.download.XXXXXX")
  trap 'rm -rf -- "$stage"' EXIT
  archive="ruff-$arch-unknown-linux-gnu.tar.gz"
  curl --fail --silent --show-error --location --retry 3 \
    "https://github.com/astral-sh/ruff/releases/download/$version/$archive" \
    --output "$stage/archive.tar.gz"
  printf '%s  %s\n' "$archive_sha" "$stage/archive.tar.gz" | sha256sum --check --status
  tar -xzf "$stage/archive.tar.gz" -C "$stage"
  extracted="$stage/ruff-$arch-unknown-linux-gnu/ruff"
  printf '%s  %s\n' "$binary_sha" "$extracted" | sha256sum --check --status
  chmod 755 "$extracted"
  mv -- "$extracted" "$binary"
fi
# Verify cached bytes as well: a version string cannot authenticate a cached tool.
printf '%s  %s\n' "$binary_sha" "$binary" | sha256sum --check --status || {
  printf 'Ruff: cached binary checksum mismatch: %s\n' "$binary" >&2
  exit 2
}
exec "$binary" "$@"
