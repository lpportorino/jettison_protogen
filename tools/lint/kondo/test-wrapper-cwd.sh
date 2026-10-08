#!/usr/bin/env bash
# The wrapper must lint RELATIVE paths against the CALLER's directory.
#
# The structural gates' fixture suites run clj-kondo from a scratch directory with
# `--lint src`. A wrapper that changed into the repository root resolved that path
# against the root instead, so kondo analysed nothing and every fixture reported
# CANNOT RUN (empty analysis) while the repo-root lint lane stayed green.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)
# CLJ_KONDO_WRAPPER points the suite at another wrapper (a draft, a mutant).
wrapper="${CLJ_KONDO_WRAPPER:-$root/tools/lint/bin/clj-kondo}"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/src"
printf '(ns probe-cwd)\n(defn f [] probe-undefined-symbol)\n' >"$work/src/probe_cwd.clj"

# Control FIRST: an absolute path is location-independent and must be judged by any
# working wrapper, so a wrapper broken for an unrelated reason (no JVM, classpath)
# reads as ERROR here rather than as the positive clause's FAIL below.
"$wrapper" --cache false --lint "$work/src" >"$work/absolute.out" 2>&1 || true
if ! grep -q 'probe_cwd.clj.*probe-undefined-symbol' "$work/absolute.out"; then
    echo 'ERROR: absolute-path control produced no finding; the wrapper itself is broken' >&2
    sed 's/^/  | /' "$work/absolute.out" >&2
    exit 2
fi
# Positive: a relative path names the fixture in the caller's directory.
(cd "$work" && "$wrapper" --cache false --lint src >"$work/relative.out" 2>&1) || true
if ! grep -q 'src/probe_cwd.clj.*probe-undefined-symbol' "$work/relative.out"; then
    echo 'FAIL: relative --lint path was not resolved against the caller directory' >&2
    sed 's/^/  | /' "$work/relative.out" >&2
    exit 1
fi

echo 'PASS: relative paths resolve from the caller; absolute-path control judged'
