#!/usr/bin/env python3
"""Exhaustive search over the CLOSED token table: is there a (band fill, glyph
tone) pair that keeps a DISABLED roller's selected band distinguishable?

The two floors are the ones docs/UI-QUALITY-CONTRACTS.md fixes:
  - band vs field: WCAG 2.2 1.4.11 non-text component boundary, 3:1
  - glyph on band: MIL-STD-1472H 5.2.2.7, 6:1 shall (10:1 should)

The table is renderer/generated/theme_tokens.h, which is the CLOSED projection
`tools/renderer-gen/src/lvgl_codegen/theme_tokens.clj` emits. A value outside
it is not available to renderer/src/theme.c at all.

Same WCAG arithmetic as tools/devcards/dev/palette-audit.py and
dev/disabled_pair_probe.clj, so the numbers are comparable digit for digit.
"""

TOKENS = {
    "surface-1":     (0x12121F, 0xE0E0D4),
    "surface-2":     (0x1E1E2E, 0xD0D0C0),
    "edge-0":        (0x6B6B8A, 0x70705A),
    "fg-0":          (0xE8E8F0, 0x1A1A28),
    "accent":        (0x7C3AED, 0x7C3AED),
    "focused-edge":  (0x22D3EE, 0x0891B2),
    "checked":       (0x0E7490, 0x0E7490),
    "disabled-fg":   (0x9A9BB6, 0x3D3C2C),
}
MODES = ("dark", "light")


def rgb(v):
    return ((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(v):
    r, g, b = rgb(v)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def val(name, mi):
    return TOKENS[name][mi]


BAND_FLOOR = 3.0
GLYPH_FLOOR = 6.0

print("== the claim theme.c makes, re-derived ==")
for mi, mode in enumerate(MODES):
    print("  surface-1 band on surface-2 field, %-5s: %.2f:1"
          % (mode, ratio(val("surface-1", mi), val("surface-2", mi))))

print("\n== what the SHIPPED enabled band measures ==")
for mi, mode in enumerate(MODES):
    print("  checked band vs surface-1 field, %-5s: %.2f:1   white glyph on checked: %.2f:1"
          % (mode, ratio(val("checked", mi), val("surface-1", mi)),
             ratio(0xFFFFFF, val("checked", mi))))

print("\n== exhaustive: (band fill F, glyph tone T) over the closed table ==")
print("   field = surface-2 (what disabled_fill drains the roller MAIN to)")
print("   need contrast(F, surface-2) >= %.1f AND contrast(T, F) >= %.1f, in BOTH modes\n"
      % (BAND_FLOOR, GLYPH_FLOOR))

survivors = []
for f in TOKENS:
    if f == "surface-2":
        continue
    band_ok = all(ratio(val(f, mi), val("surface-2", mi)) >= BAND_FLOOR
                  for mi in range(2))
    for t in TOKENS:
        if t == f:
            continue
        glyph_ok = all(ratio(val(t, mi), val(f, mi)) >= GLYPH_FLOOR
                       for mi in range(2))
        if band_ok and glyph_ok:
            survivors.append((f, t))

# The full grid, so the failures are auditable and not merely asserted.
hdr = "%-14s %-14s %9s %9s %9s %9s" % (
    "band F", "glyph T", "F|s2 dk", "F|s2 lt", "T|F dk", "T|F lt")
print(hdr)
print("-" * len(hdr))
for f in TOKENS:
    if f == "surface-2":
        continue
    for t in TOKENS:
        if t == f:
            continue
        bd = ratio(val(f, 0), val("surface-2", 0))
        bl = ratio(val(f, 1), val("surface-2", 1))
        gd = ratio(val(t, 0), val(f, 0))
        gl = ratio(val(t, 1), val(f, 1))
        mark = "  <== PASSES" if (f, t) in survivors else ""
        print("%-14s %-14s %9.2f %9.2f %9.2f %9.2f%s"
              % (f, t, bd, bl, gd, gl, mark))

print("\nSURVIVORS: %d" % len(survivors))
for f, t in survivors:
    print("  band=%s glyph=%s" % (f, t))

print("\n== and the same search with the band floor RELAXED to 2:1 ==")
relaxed = []
for f in TOKENS:
    if f == "surface-2":
        continue
    band_ok = all(ratio(val(f, mi), val("surface-2", mi)) >= 2.0
                  for mi in range(2))
    for t in TOKENS:
        if t == f:
            continue
        if band_ok and all(ratio(val(t, mi), val(f, mi)) >= GLYPH_FLOOR
                           for mi in range(2)):
            relaxed.append((f, t))
print("SURVIVORS at 2:1: %d  %s" % (len(relaxed), relaxed))
