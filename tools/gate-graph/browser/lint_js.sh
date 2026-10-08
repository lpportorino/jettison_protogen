#!/usr/bin/env bash
# lint_js.sh — ESLint over this repository's hand-authored JavaScript.
#
#   bash tools/gate-graph/browser/lint_js.sh [FILE...]
#
# The files are DISCOVERED from git (`*.mjs`, `*.js`, `*.cjs`), minus the
# declared scope exclusions below, unless FILE arguments name them. ESLint is the
# version package-lock.json pins, run in the same digest-pinned Playwright image
# as run.sh (whose PLAYWRIGHT_IMAGE line is read, never copied), with the network
# removed; eslint.config.mjs holds the rules, and every warning blocks
# (`--max-warnings 0`). A tracked script outside the config's `files` globs is
# not skipped: ESLint warns that no configuration matched it, and that blocks.
#
# `npm ci` (lockfile-exact, with the network) runs only when node_modules was not
# installed from the current package-lock.json.
#
# Exit 0 clean; 1 FAIL (findings); 2 ERROR (ESLint crashed or its config is
# broken — not a verdict); 3 CANNOT RUN (no docker, a declared exclusion that
# matches nothing, nothing discovered, a named file missing, npm ci failed).
# HOST-ONLY: it drives docker, which the toolchain image does not carry.
set -uo pipefail
say() { printf '[lint-js] %s\n' "$*" >&2; }
cannot() { say "CANNOT RUN — $*"; exit 3; }

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
root="$(env -u GIT_DIR -u GIT_WORK_TREE git -C "$here" rev-parse --show-toplevel 2>&1)" || cannot "not in a git checkout: $root"
browser="tools/gate-graph/browser"
config="${LINT_JS_CONFIG:-$browser/eslint.config.mjs}"
image="$(sed -n 's/^PLAYWRIGHT_IMAGE="\(.*\)"$/\1/p' "$root/$browser/run.sh")"
[ -n "$image" ] || cannot "no PLAYWRIGHT_IMAGE line in $browser/run.sh to take the pinned image from"
command -v docker >/dev/null 2>&1 || cannot "docker is not on PATH (install Docker; this lane runs ESLint in the pinned Playwright image)"

# DECLARED SCOPE: generated or vendored JavaScript is judged by its generator or
# its upstream, never here. Each entry must still match a tracked path, so an
# exclusion cannot outlive what it excused.
excluded=(
  tools/gate-graph/resources/gate/viewer/main.js   # generated: clojure -M:viewer-build
)
files=()
if [ $# -gt 0 ]; then
  for f in "$@"; do [ -f "$root/$f" ] || cannot "named file $f does not exist"; files+=("$f"); done
else
  pathspec=('*.mjs' '*.js' '*.cjs')
  [ -n "${LINT_JS_PATHSPEC:-}" ] && pathspec=("$LINT_JS_PATHSPEC")
  listing="$(env -u GIT_DIR -u GIT_WORK_TREE git -C "$root" ls-files -- "${pathspec[@]}" 2>&1)" \
    || cannot "git ls-files failed: $listing"
  for x in "${excluded[@]}"; do
    grep -qxF "$x" <<<"$listing" || cannot "declared exclusion $x matches no tracked file; remove it"
  done
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in renderer/lvgl/*) continue ;; esac
    skip=0; for x in "${excluded[@]}"; do [ "$f" = "$x" ] && skip=1; done
    [ "$skip" = 1 ] || files+=("$f")
  done <<<"$listing"
fi
[ ${#files[@]} -gt 0 ] || cannot "no JavaScript discovered; a lint over nothing would pass"

uid="$(id -u):$(id -g)"
mount=(-v "$root:/w" -w /w --user "$uid" -e HOME=/tmp -e npm_config_cache=/tmp/.npm)
stamp="$root/$browser/node_modules/.installed-from"
want="$(sha256sum "$root/$browser/package-lock.json" | cut -d' ' -f1)"
if [ "$(cat "$stamp" 2>/dev/null)" != "$want" ]; then
  docker run --rm "${mount[@]}" -w "/w/$browser" "$image" npm ci --no-audit --no-fund --loglevel=error >&2 \
    || cannot "npm ci failed (it needs the network once per lockfile change)"
  printf '%s\n' "$want" >"$stamp"
fi

docker run --rm --network none "${mount[@]}" "$image" \
  "$browser/node_modules/.bin/eslint" --config "$config" --max-warnings 0 "${files[@]}"
rc=$?
case "$rc" in
  0) say "clean — ${#files[@]} file(s)"; exit 0 ;;
  1) say "FAIL — findings above (${#files[@]} file(s) judged)"; exit 1 ;;
  *) say "ERROR — ESLint exited $rc: a crash or a broken config, not a verdict"; exit 2 ;;
esac
