#!/usr/bin/env python3
"""Independent TTF table reader: head/hhea/OS2/VDMX, no dependencies.

Re-derives the font-metric claims in .claude/rules/renderer.md '## Fonts'
against renderer/assets/fonts/*.ttf and the compiled tables in
renderer/src/font_*.c.  Measurement only; writes nothing.
"""
import glob
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def tables(buf):
    numt = struct.unpack(">H", buf[4:6])[0]
    out = {}
    for i in range(numt):
        off = 12 + 16 * i
        tag = buf[off:off + 4].decode("latin1")
        o, ln = struct.unpack(">II", buf[off + 8:off + 16])
        out[tag] = (o, ln)
    return out


def read(path):
    with open(path, "rb") as f:
        buf = f.read()
    t = tables(buf)
    upem = struct.unpack(">H", buf[t["head"][0] + 18:t["head"][0] + 20])[0]
    h = t["hhea"][0]
    asc, desc, gap = struct.unpack(">hhh", buf[h + 4:h + 10])
    return buf, t, upem, asc, desc, gap


def vdmx(buf, t):
    """Return {ppem: (yMax, yMin)} from the first VDMX group, or None."""
    if "VDMX" not in t:
        return None
    o = t["VDMX"][0]
    version, numRecs, numRatios = struct.unpack(">HHH", buf[o:o + 6])
    # ratio records are 4 bytes each, then numRatios uint16 group offsets
    p = o + 6 + 4 * numRatios
    offsets = struct.unpack(">%dH" % numRatios, buf[p:p + 2 * numRatios])
    g = o + offsets[0]
    recs, startsz, endsz = struct.unpack(">HBB", buf[g:g + 4])
    out = {}
    q = g + 4
    for _ in range(recs):
        ppem, ymax, ymin = struct.unpack(">Hhh", buf[q:q + 6])
        out[ppem] = (ymax, ymin)
        q += 6
    return out


def compiled():
    """(name -> line_height) grepped from the generated font C arrays."""
    out = {}
    for p in sorted(glob.glob(os.path.join(ROOT, "renderer/src/font_*.c"))):
        src = open(p, encoding="utf-8", errors="replace").read()
        m = re.search(r"\.line_height\s*=\s*(\d+)", src)
        if m:
            out[os.path.basename(p)[:-2]] = int(m.group(1))
    return out


def main():
    faces = {}
    for p in sorted(glob.glob(os.path.join(ROOT, "renderer/assets/fonts/*.ttf"))):
        buf, t, upem, asc, desc, gap = read(p)
        base = os.path.basename(p)
        faces[base] = (upem, asc, desc, gap)
        print("%-22s upem=%-5d hhea asc=%-5d desc=%-5d gap=%d  tables=%s"
              % (base, upem, asc, desc, gap, ",".join(sorted(t))))
        v = vdmx(buf, t)
        if v:
            keys = sorted(v)
            print("    VDMX present: %d ppem records, %d..%d; sample "
                  % (len(keys), keys[0], keys[-1])
                  + ", ".join("ppem %d -> yMax %d / yMin %d" % (k, v[k][0], v[k][1])
                              for k in keys if k in (12, 20, 26, 32)))
        else:
            print("    VDMX: absent")

    print()
    print("%-26s %8s %12s %8s" % ("compiled font", "line_h", "hhea*sz/upem", "delta"))
    for name, lh in sorted(compiled().items()):
        m = re.match(r"font_(b612mono_bold|orbitron_bold)_(\d+)", name)
        if not m:
            print("%-26s %8d   (unmatched)" % (name, lh))
            continue
        face = {"b612mono_bold": "b612mono_bold.ttf",
                "orbitron_bold": "Orbitron-Bold.ttf"}[m.group(1)]
        size = int(m.group(2))
        upem, asc, desc, gap = faces[face]
        scaled = (asc - desc + gap) * size / upem
        print("%-26s %8d %12.2f %8.2f" % (name, lh, scaled, lh - scaled))
    return 0


if __name__ == "__main__":
    sys.exit(main())
