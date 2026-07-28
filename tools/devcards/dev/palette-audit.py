#!/usr/bin/env python3
"""Reproduce this session's palette measurements from the committed token manifest.

    python3 tools/devcards/dev/palette-audit.py

Reads output/manifests/design-tokens.json ONLY — no rendering, no wasm, no
container. Every number quoted in .protogen/plans/TODO.md section "Measurements"
comes from here, so re-run it rather than trusting the copy.

This is throwaway measurement code, NOT the gate. The gate is PDL-1 T0 (task T4),
Clojure in tools/devcards/ through the finding-producer registry, and it owes its
own canary at the values PDL-1 specifies. This exists so the numbers in the plan
can be re-derived by anyone who doubts them.
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
MANIFEST = os.path.join(ROOT, "output/manifests/design-tokens.json")

# THE GOVERNING THRESHOLD IS MIL-STD-1472H §5.2.2.7, NOT WCAG.
# PDL-1's precedence rule: MIL-STD-1472H governs wherever it states a threshold;
# WCAG 2.2 AA fills gaps only where 1472H is silent; where both state one for the
# same quantity, THE STRICTER BINDS. WCAG's 4.5:1 is an indoor number and is
# explicitly rejected as the floor. Measured cost of having used it: 18 of 60
# declared text pairs fail at 4.5:1 and 37 fail at 6:1 — nineteen pairs pass WCAG
# and fail the standard that binds.
#   ~/git/cc/jettison_eudoxia/docs/research/2026-07-25-pdl1-readability-standard.md
SHALL = 6.0   # MIL-STD-1472H §5.2.2.7 — build FAILS below
SHOULD = 10.0  # §5.2.2.7 — WARN below, not blocking
# WCAG 2.2 §1.4.11 is a legitimate GAP-FILL: 1472H states nothing for generic
# non-coded component boundaries, and WCAG is the only candidate that does.
NON_TEXT = 3.0
# Kept only to quantify the gap between the two, never as a bar.
WCAG_AA = 4.5

FG = ["fg-0", "fg-1", "fg-2", "accent-text", "disabled-fg"]
BG = ["surface-0", "surface-1", "surface-2", "surface-overlay",
      "accent-bg", "pressed-surface"]
EDGES = ["edge-0", "edge-1", "focused-edge", "hud-border", "checked-accent",
         "status-error", "status-success", "status-warning"]
SURFACES = ["surface-0", "surface-1", "surface-2", "surface-overlay"]


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(c):
    r, g, b = c
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def blend(fg, bg, opa):
    """LVGL composites in 8-bit sRGB, not linear light — match it exactly."""
    a = opa / 255.0
    return tuple(round(f * a + b * (1 - a)) for f, b in zip(fg, bg))


def oklch(c):
    r, g, b = (_lin(x) for x in c)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = (x ** (1 / 3) if x > 0 else 0.0 for x in (l, m, s))
    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    return L, math.hypot(A, B), math.degrees(math.atan2(B, A)) % 360


def hue_gap(a, b):
    d = abs(a - b)
    return min(d, 360 - d)


def rule(title):
    print(f"\n{'=' * 78}\n{title}\n{'=' * 78}")


def main():
    if not os.path.exists(MANIFEST):
        sys.exit(f"missing {MANIFEST} — run `make generate` first")
    tokens = json.load(open(MANIFEST))["tokens"]
    colors = {k: v for k, v in tokens.items() if v["kind"] == "color"}
    opa = tokens["disabled-opa"]["dark"]
    fails = 0

    rule("1. TEXT CONTRAST — declared (fg, bg) pairs, vs MIL-STD-1472H 6:1 shall")
    for mode in ("dark", "light"):
        print(f"\n-- {mode} --")
        print(f"{'':>13}" + "".join(f"{b:>12}" for b in BG))
        for f in FG:
            row = f"{f:>13}"
            for b in BG:
                v = contrast(rgb(colors[f][mode]), rgb(colors[b][mode]))
                mark = " " if v >= SHOULD else ("~" if v >= SHALL else "!")
                fails += mark == "!"
                row += f"{mark}{v:>11.2f}"
            print(row)
    print("\n  ! FAILS the 6:1 shall — build fails    ~ clears 6:1, below the 10:1 should")
    n_wcag = sum(1 for m in ("dark", "light") for f in FG for b in BG
                 if contrast(rgb(colors[f][m]), rgb(colors[b][m])) < WCAG_AA)
    n_shall = sum(1 for m in ("dark", "light") for f in FG for b in BG
                  if contrast(rgb(colors[f][m]), rgb(colors[b][m])) < SHALL)
    print(f"  {n_wcag}/60 fail WCAG 4.5:1 (the REJECTED floor); "
          f"{n_shall}/60 fail the governing 6:1 — the gap is what a "
          f"WCAG-built gate would have blessed")

    rule("2. NON-TEXT CONTRAST — WCAG 1.4.11 wants >=3:1 for boundaries")
    for mode in ("dark", "light"):
        print(f"\n-- {mode} --")
        print(f"{'':>16}" + "".join(f"{s:>12}" for s in SURFACES))
        for e in EDGES:
            row = f"{e:>16}"
            for s in SURFACES:
                v = contrast(rgb(colors[e][mode]), rgb(colors[s][mode]))
                bad = v < NON_TEXT
                fails += bad
                row += f"{'!' if bad else ' '}{v:>11.2f}"
            print(row)

    rule(f"3. THE DISABLED FADE — whole-widget opa {opa} over the parent surface")
    print("styles.disabled sets lv_style_set_opa(THEME_DISABLED_OPA) and reaches FOUR")
    print("glyph-bearing classes: lv_label (theme.c:963), lv_dropdown (:800),")
    print("lv_roller (:833), lv_tabview (:939).")
    for mode in ("dark", "light"):
        dfg = rgb(colors["disabled-fg"][mode])
        print(f"\n-- {mode} --   {'surface':>16} {'no opa':>10} {'with opa':>10}")
        for s in SURFACES:
            bg = rgb(colors[s][mode])
            clean = contrast(dfg, bg)
            faded = contrast(blend(dfg, bg, opa), bg)
            fails += faded < SHALL
            print(f"{'':>10}   {s:>16} {clean:>10.2f} {faded:>10.2f}")

    rule("4. ALIASES — tokens that are the SAME colour")
    for mode in ("dark", "light"):
        seen = {}
        for k, v in colors.items():
            seen.setdefault(v[mode], []).append(k)
        dups = {h: ks for h, ks in seen.items() if len(ks) > 1}
        print(f"\n-- {mode} --")
        for h, ks in sorted(dups.items()):
            print(f"    {h}  =  {', '.join(sorted(ks))}")
            fails += 1

    rule("5. OKLCH LADDER — contrast is a function of L, so this is the design")
    for mode in ("dark", "light"):
        print(f"\n-- {mode} --")
        rows = sorted(((oklch(rgb(v[mode])), k) for k, v in colors.items()),
                      key=lambda r: -r[0][0])
        for (L, C, h), k in rows:
            print(f"    {k:>18}  L={L:.3f}  C={C:.3f}  h={h:6.1f}   {colors[k][mode]}")

    rule("6. HUE SEPARATION in the accent space")
    fam = ["accent-bg", "media-accent", "focused-edge", "checked-accent"]
    hs = {k: oklch(rgb(colors[k]["dark"]))[2] for k in fam}
    for i, a in enumerate(fam):
        for b in fam[i + 1:]:
            d = hue_gap(hs[a], hs[b])
            print(f"    {a:>15} <-> {b:<15} {d:5.1f} deg"
                  + ("   <-- CONFUSABLE" if d < 40 else ""))

    rule("7. SALIENCE vs surface-0 — is the primary action the loudest thing?")
    for mode in ("dark", "light"):
        s0 = rgb(colors["surface-0"][mode])
        print(f"\n-- {mode} --")
        for k in ["accent-bg", "focused-edge", "checked-accent", "media-accent",
                  "hud-border", "pressed-accent", "edge-0"]:
            print(f"    {k:>16} {contrast(rgb(colors[k][mode]), s0):>6.2f}:1")

    rule("8. STOCK LEAKAGE — LVGL default-theme colours protogen never overrode")
    print("lv_theme_default.c:535-538 gives the textarea placeholder its tone;")
    print("grep TEXTAREA_PLACEHOLDER renderer/src/ is empty.")
    for name, fg_hex, bg_tok, mode in [
        ("placeholder on asgard-dark", "#616161", "surface-1", "dark"),
        ("placeholder on asgard-light", "#BDBDBD", "surface-1", "light"),
    ]:
        v = contrast(rgb(fg_hex), rgb(colors[bg_tok][mode]))
        fails += v < SHALL
        print(f"    {name:>30}  {fg_hex} on {colors[bg_tok][mode]}  {v:>6.3f}:1")
    print(f"    {'placeholder on stock light':>30}  #BDBDBD on #FFFFFF  "
          f"{contrast(rgb('#BDBDBD'), (255, 255, 255)):>6.3f}:1")

    print(f"\n{'=' * 78}\n{fails} failing cells / aliases across the audit.\n{'=' * 78}")


if __name__ == "__main__":
    main()
