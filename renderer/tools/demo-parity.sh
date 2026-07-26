#!/usr/bin/env bash
# Demo-parity differential (plan D5/D6) — the capstone oracle.
#
# Renders lv_demo_widgets BOTH ways at the demo viewport (960x540,
# DISP_LARGE) and asserts BIT EQUALITY per tab:
#   proto path      controls.wasm  + edn/screens/demo_widgets.edn -> .pb
#                   (one fixture per tab: active_index 0/1/2)
#   reference path  reference.wasm + the 2-byte [REF_PROP_DEMO_WIDGETS, tab]
#                   selector (the REAL vendored demo, PRNG seeded — see
#                   src/reference_ui.c DEMO_RAND_SEED)
# Both sides render THEME FAMILY 1 (vanilla): the asgard child theme's
# stock-restatement layer, so the capstone stays a same-vs-same regression
# guard under the child theme (the vanilla==stock proof itself lives in the
# per-card family=1 vs family=2 hash gate, not here — the vendored demo
# re-inits the stock PARENT mid-run on the reference side, which reaches
# both sides identically through the shared singleton).
# Oracle per tab: the framebuffer, BIT-EQUAL (imgdiff --max-diff 0). The
# semantic trees are dumped as diagnostics but NOT byte-compared: the two
# sides differ structurally by design — the demo floats its color changer
# on the tabview itself, while the tabview wire arm zips every child to a
# tab slot, so ours lives on the bare full-screen root (a recorded design
# decision; pixel-identical placement). Both sides render
# under the harness's pinned tick budget (lvgl_harness::RENDER_TICKS), so
# the anim-frozen EDN values stay valid.
#
# Usage: bash tools/demo-parity.sh
# Exit:  0 = all three tabs bit-equal; 1 = at least one diverged.
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$(pwd)"
# Root-anchored exec helpers (dev / dev_w / rgen), derived from $ROOT — the
# renderer tree root (WS=$ROOT). In-container only; fails loud on the host.
source "$ROOT/tools/in-container.sh"

# Build BOTH wasm paths + the harness here (the stale-artifact trap: a
# fresh EDN against a stale wasm reads as a rendering bug).
echo "── building controls.wasm + reference.wasm + harness ──"
# -j$(nproc), matching renderer.mk's wasm lane and coverage_matrix/run.sh. This
# was the last of THREE call sites building these same two targets, and the only
# one still serial (measured elsewhere: 80.6s -> 22.9s on a clean tree, 3.5x).
dev make -f wasm.mk -j"$(nproc)" all reference
dev_w wasm_harness bash -c 'PATH=$HOME/.cargo/bin:$PATH cargo build --quiet --release'

OUT=output/demo-parity
rm -rf "$ROOT/$OUT"
mkdir -p "$ROOT/$OUT/fix"

# One fixture per tab: the live screen EDN with active_index swapped (the
# only authoring delta between the tabs is which page is active).
for tab in 0 1 2; do
  sed "s/:active_index 0\$/:active_index $tab/" edn/screens/demo_widgets.edn \
    >"$ROOT/$OUT/fix/demo_tab$tab.edn"
done

echo "── codegen demo_widgets per-tab fixtures ──"
rgen clojure -M:codegen --tokens ../../output/manifests/design-tokens.json --input "$ROOT/$OUT/fix" \
  --output "$ROOT/$OUT" >/dev/null

fail=0
for tab in 0 1 2; do
  printf "$(printf '\\x%02x\\x%02x' 12 "$tab")" >"$ROOT/$OUT/sel_tab$tab.bin"

  dev_w wasm_harness ./target/release/lvgl_harness --wasm "$WS/output/controls.wasm" --wasi-root "$WS/assets" \
    --pb "$WS/$OUT/demo_tab$tab.pb" --output "$WS/$OUT" --dump-tree \
    --theme-family 1 \
    --assert-content >/dev/null 2>&1 ||
    {
      echo "  tab$tab: PROTO RENDER/CONTENT FAILED"
      fail=$((fail + 1))
      continue
    }
  dev_w wasm_harness ./target/release/lvgl_harness --wasm "$WS/output/reference.wasm" --wasi-root "$WS/assets" \
    --pb "$WS/$OUT/sel_tab$tab.bin" --output "$WS/$OUT" --dump-tree \
    --theme-family 1 \
    --assert-content >/dev/null 2>&1 ||
    {
      echo "  tab$tab: REFERENCE RENDER/CONTENT FAILED"
      fail=$((fail + 1))
      continue
    }

  # Capture imgdiff's exit separately from the report pipe — a piped
  # `cmd | sed` would mask the gate's exit status.
  px_ok=yes
  diff_report=$(dev_w output/demo-parity "$WS/wasm_harness/target/release/imgdiff" "sel_tab${tab}_bp0_light.png" \
    "demo_tab${tab}_bp0_light.png" --max-diff 0 \
    --diff-out "diff_tab${tab}.png") || px_ok=no
  echo "$diff_report" | sed "s/^/  tab$tab: /"
  if [ "$px_ok" = yes ]; then
    echo "  tab$tab: px=ok (bit-equal)"
  else
    echo "  tab$tab: DIVERGED (see $OUT/diff_tab$tab.png + the tree dumps)"
    fail=$((fail + 1))
  fi
done

echo "── demo-parity: $((3 - fail)) of 3 tabs bit-equal ──"
[ "$fail" -eq 0 ]
