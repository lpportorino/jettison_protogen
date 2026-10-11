"""pdf_links -- do the PDF's internal links point at destinations the file defines?

Chromium (Skia/PDF) writes one `/Link` annotation per `<a href="#id">` it
printed, with `/Dest /<id>` naming a destination -- and it writes the
DEFINITION of that name only when the target element had a layout box on a
printed page. A target hidden under print media (`display:none`), or one that
never existed, leaves the annotation in place and the name undefined: the
mark renders, the click does nothing, and no renderer reports it. Measured on
a long engineering report: 359 annotations, every one naming `/src-S<n>`, and
no `/Dests` dictionary in the catalog at all.

The parser is deliberately tiny and format-specific: Skia emits every object
uncompressed (streams are compressed, dictionaries are not), so a regex pass
over the raw bytes sees every annotation and the catalog. It is NOT a general
PDF reader; a file that stores its annotations inside object streams reads as
"no links", and `audit` says so through `parsed_objects` so the caller can
tell an empty document from an unreadable one.
"""

from __future__ import annotations

import re

_OBJ = re.compile(rb"(\d+)\s+0\s+obj\s*(<<.*?>>)\s*(?:stream|endobj)", re.S)
_LINK = re.compile(rb"/Subtype\s*/Link")
_DEST_NAME = re.compile(rb"/Dest\s*/([^\s/\[\]<>()]+)")
_DEST_STR = re.compile(rb"/Dest\s*\(([^)]*)\)")
_DEST_ARR = re.compile(rb"/Dest\s*\[")
_URI = re.compile(rb"/S\s*/URI")
_DESTS_REF = re.compile(rb"/Dests\s+(\d+)\s+0\s+R")
_DESTS_INLINE = re.compile(rb"/Dests\s*(<<.*?>>)", re.S)
_NAMES_REF = re.compile(rb"/Names\s+(\d+)\s+0\s+R")
_NAME_KEY = re.compile(rb"/([^\s/\[\]<>()]+)\s*(?:\[|\d+\s+0\s+R)")
_NAMES_ARR = re.compile(rb"/Names\s*\[(.*?)\]", re.S)
_STR = re.compile(rb"\(([^)]*)\)")


def _objects(data):
    return {int(n): body for n, body in _OBJ.findall(data)}


def _defined_names(data, objs):
    """Every destination name the catalog defines, through either of the two
    shapes a writer may use: a `/Dests` dictionary (Skia) or a `/Names`
    tree with a `/Dests` leaf."""
    names = set()
    cats = [b for b in objs.values() if re.search(rb"/Type\s*/Catalog", b)]
    for cat in cats:
        m = _DESTS_REF.search(cat)
        body = objs.get(int(m.group(1))) if m else None
        if body is None:
            m = _DESTS_INLINE.search(cat)
            body = m.group(1) if m else None
        if body:
            names.update(
                k.decode("latin-1") for k in _NAME_KEY.findall(body) if k not in (b"Type",)
            )
        m = _NAMES_REF.search(cat)
        if m:
            tree = objs.get(int(m.group(1)), b"")
            leaf = _DESTS_REF.search(tree)
            leaf = objs.get(int(leaf.group(1)), b"") if leaf else tree
            for arr in _NAMES_ARR.findall(leaf):
                names.update(s.decode("latin-1") for s in _STR.findall(arr))
    return names


def audit(data):
    """Audit the PDF bytes. Returns a dict:

    parsed_objects  -- how many top-level objects the scan saw (0 = unreadable)
    links           -- link annotations found
    external        -- of them, URI actions
    explicit        -- of them, explicit `[page /XYZ ...]` destinations
    named           -- of them, named destinations
    defined         -- distinct destination names the catalog defines
    dead            -- sorted distinct names a link uses that nothing defines
    dead_links      -- how many annotations point at a dead name
    """
    objs = _objects(data)
    defined = _defined_names(data, objs)
    used = {}
    external = explicit = links = 0
    for body in objs.values():
        if not _LINK.search(body):
            continue
        links += 1
        if _URI.search(body):
            external += 1
            continue
        m = _DEST_NAME.search(body) or _DEST_STR.search(body)
        if m:
            name = m.group(1).decode("latin-1")
            used[name] = used.get(name, 0) + 1
        elif _DEST_ARR.search(body):
            explicit += 1
    dead = sorted(n for n in used if n not in defined)
    return {
        "parsed_objects": len(objs),
        "links": links,
        "external": external,
        "explicit": explicit,
        "named": sum(used.values()),
        "defined": len(defined),
        "dead": dead,
        "dead_links": sum(used[n] for n in dead),
    }


def audit_file(path):
    with open(path, "rb") as fh:
        return audit(fh.read())
