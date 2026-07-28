#!/usr/bin/env bash
# atomic-install-probe.sh — the evidence under renderer.mk's INSTALL_ATOMIC.
#
# WHY THIS IS A TRACKED FILE. renderer.mk claims three things a reader cannot
# check by reading: that /tmp and the repo are different filesystems in the
# pinned container, that `mv` across that boundary tears exactly like the `cp`
# it would replace, and that staging inside the destination directory does not.
# Those are the whole argument for the helper being five lines instead of the
# word `mv`, and a number with no re-runnable probe behind it is recall, not
# evidence.
#
# Usage (from the repo root, inside the toolchain container):
#   tools/uber.sh 'tools/perf/atomic-install-probe.sh'
# Exit: 0 = the claim reproduced; 1 = it did not, and renderer.mk's comment
#       needs rewriting rather than trusting.
#
# ── WHAT IS ASSERTED, AND WHY IT IS NOT THE TEAR COUNT ───────────────────────
# The obvious probe — race a reader against each method and count torn reads —
# was written first and is KEPT BELOW as evidence, but it must not be the
# assertion. Measured across four runs of exactly that form, `mv` from
# $(mktemp -d) tore 5, then 95, then 0, then 0 times: a tear is a race the
# sampler wins only sometimes, so an assertion on it is a gate that fails one
# run in some — the nondeterministic red this repo refuses everywhere else, and
# it would have been introduced by the very probe defending a correctness fix.
#
# INODE IDENTITY IS THE DETERMINISTIC INSTRUMENT, and it measures the mechanism
# rather than a symptom of it. rename(2) replaces the directory entry, so the
# destination path resolves to a DIFFERENT inode afterwards. Any in-place write
# — open/truncate/write — keeps the SAME inode, and every reader holding or
# opening that path can observe an intermediate state. So:
#   same inode after the install -> in-place write -> tearable
#   new inode after the install  -> rename(2)      -> atomic
# That answer is identical on every run. The tear counts are reported
# underneath it because a mechanism plus an observed symptom is worth more than
# either alone — but only the mechanism is asserted.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$ROOT/.protogen/atomic-probe"
rm -rf "$WORK"; mkdir -p "$WORK"

SIZE_MB=24   # big enough that the copy is not a single write(2)
OLD="$WORK/old.bin"; NEW="$WORK/new.bin"
head -c $((SIZE_MB * 1024 * 1024)) /dev/zero | tr '\0' 'A' >"$OLD"
head -c $((SIZE_MB * 1024 * 1024)) /dev/zero | tr '\0' 'B' >"$NEW"
H_OLD="$(sha256sum <"$OLD" | cut -d' ' -f1)"
H_NEW="$(sha256sum <"$NEW" | cut -d' ' -f1)"

# ── the three install methods under test ────────────────────────────────────
install_cp()     { cp "$1" "$2"; }
install_mv_tmp() { local t; t="$(mktemp -d)"; cp "$1" "$t/f"; mv -f "$t/f" "$2"; rm -rf "$t"; }
# The shape renderer.mk uses: stage in the DESTINATION's own directory.
install_atomic() { local _t; _t="$(mktemp "$(dirname "$2")/.probe.XXXXXX")"; cat "$1" >"$_t" && mv -f "$_t" "$2" || rm -f "$_t"; }

echo "── 1. are /tmp and the repo the same filesystem? ──"
D_TMP="$(stat -c %d /tmp)"; D_REPO="$(stat -c %d "$ROOT")"
printf '  /tmp   device %s  (%s)\n' "$D_TMP" "$(stat -f -c %T /tmp)"
printf '  %-6s device %s  (%s)\n' "repo" "$D_REPO" "$(stat -f -c %T "$ROOT")"
if [ "$D_TMP" = "$D_REPO" ]; then
  echo "  SAME device — on THIS host \`mv\` from \$(mktemp -d) WOULD be a rename,"
  echo "  so arm 2's mv row is expected to come out ATOMIC here. renderer.mk's"
  echo "  helper is then redundant on this host but not wrong; the pinned"
  echo "  container is the environment the gate actually runs in."
  CROSS=no
else
  echo "  DIFFERENT devices — \`mv\` from \$(mktemp -d) into the repo cannot be a"
  echo "  rename(2). Arm 2 measures what it degrades to."
  CROSS=yes
fi
echo

# ── 2. THE ASSERTION: does the destination inode change? ────────────────────
echo "── 2. install mechanism, by inode identity (deterministic) ──"
declare -A MECH
for m in cp mv_tmp atomic; do
  dst="$WORK/dst_$m.bin"
  cp "$OLD" "$dst"
  before="$(stat -c %i "$dst")"
  "install_$m" "$NEW" "$dst"
  after="$(stat -c %i "$dst")"
  if [ "$before" = "$after" ]; then
    MECH[$m]=inplace
    printf '  %-22s inode %s -> %s   SAME  in-place write, TEARABLE\n' "$m" "$before" "$after"
  else
    MECH[$m]=rename
    printf '  %-22s inode %s -> %s   NEW   rename(2), ATOMIC\n' "$m" "$before" "$after"
  fi
  # content must still be correct whichever mechanism was used
  [ "$(sha256sum <"$dst" | cut -d' ' -f1)" = "$H_NEW" ] || { echo "  ✗ $m did not install the new content at all"; exit 1; }
done
echo

# ── 3. corroborating evidence: torn reads seen by a concurrent reader ───────
# Reported, never asserted — see the header. A zero here for a method arm 2
# called in-place means the sampler missed the window, not that the method is
# safe.
probe_tears() { # <method> -> "reads tears absent"
  local fn=$1 rounds=25 tears=0 gone=0 reads=0 h w
  local dst="$WORK/tear_$fn.bin"
  for _ in $(seq "$rounds"); do
    cp "$OLD" "$dst"
    "install_$fn" "$NEW" "$dst" &
    w=$!
    while kill -0 "$w" 2>/dev/null; do
      # The redirect is wrapped in a GROUP so the SHELL's own "No such file"
      # is captured too: `<"$dst"` is evaluated before sha256sum runs, so a
      # 2>/dev/null hung on the command cannot suppress it, and an `[ -e ]`
      # pre-test still races the unlink. Empty output means the path was gone.
      h="$( { sha256sum <"$dst"; } 2>/dev/null | cut -d' ' -f1)"
      [ -n "$h" ] || h=ABSENT
      reads=$((reads + 1))
      case "$h" in
        "$H_OLD"|"$H_NEW") ;;
        ABSENT) gone=$((gone + 1)); tears=$((tears + 1)) ;;
        *) tears=$((tears + 1)) ;;
      esac
    done
    wait "$w" 2>/dev/null
  done
  printf '%s %s %s' "$reads" "$tears" "$gone"
}

echo "── 3. torn reads seen by a concurrent reader (${SIZE_MB}MB x 25 rounds; EVIDENCE, not the assertion) ──"
for m in cp mv_tmp atomic; do
  read -r reads tears gone <<<"$(probe_tears "$m")"
  printf '  %-22s %5d reads  %5d torn (%d of them: destination ABSENT)\n' "$m" "$reads" "$tears" "$gone"
done
echo

# ── 4. verdict, on the mechanism ────────────────────────────────────────────
echo "── 4. verdict ──"
rc=0
[ "${MECH[cp]}" = inplace ] \
  && echo "  ✓ cp is an in-place write — the hazard renderer.mk had is real" \
  || { echo "  ✗ cp came out as a rename — unexpected; the premise needs rechecking"; rc=1; }
if [ "$CROSS" = yes ]; then
  [ "${MECH[mv_tmp]}" = inplace ] \
    && echo "  ✓ mv from \$(mktemp -d) is ALSO an in-place write across devices —" \
    && echo "    substituting \`mv\` for \`cp\` would have changed nothing" \
    || { echo "  ✗ cross-device mv came out atomic — renderer.mk's correction is WRONG"; rc=1; }
else
  echo "  – mv from \$(mktemp -d): same-device on this host (${MECH[mv_tmp]}), not the claim under test"
fi
[ "${MECH[atomic]}" = rename ] \
  && echo "  ✓ stage-in-destination + mv is a rename(2) — atomic" \
  || { echo "  ✗ the helper did NOT rename — renderer.mk's claim is WRONG"; rc=1; }
rm -rf "$WORK"
exit "$rc"
