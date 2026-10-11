"""The house constants — the one copy the browser half reads.

`press/house.json` is the DEFAULT house. A consumer with its own style names its file in
`PRESS_HOUSE` (the launcher mounts that file and sets the variable inside the container); it must
carry every REQUIRED key. A missing or malformed key fails loudly on import: every module relies
on them."""

import json
import os
import pathlib

DEFAULT_PATH = pathlib.Path(__file__).resolve().parent.parent / "house.json"
PATH = pathlib.Path(os.environ.get("PRESS_HOUSE") or DEFAULT_PATH)
H = json.loads(PATH.read_text())
REQUIRED = (
    "canvas-width",
    "page-budget",
    "text-floor",
    "body-size",
    "line-height",
    "pad",
    "note-pad",
    "column-gap",
    "clearance",
    "arrow-room",
    "tap-room",
    "corridor",
    "colors",
    "text",
    "looks",
    "edge-looks",
    "font-family",
    "text-ink",
)
_missing = [k for k in REQUIRED if k not in H]
if _missing:
    raise SystemExit(f"house.json is missing {_missing} ({PATH})")

W = H["canvas-width"]
BUDGET_W, BUDGET_H = H["page-budget"]
TEXT_FLOOR = H["text-floor"]
LINE = H["line-height"]
PAD = H["pad"]
NOTE_PAD = H["note-pad"]
COLGAP = H["column-gap"]
CLEAR = H["clearance"]
ARROW_ROOM = H["arrow-room"]
TAP_ROOM = H["tap-room"]
CORRIDOR = H["corridor"]
COLORS = H["colors"]
TEXT = H["text"]
LOOKS = H["looks"]
EDGE_LOOKS = H["edge-looks"]
FONT_FAMILY = H["font-family"]
TEXT_INK = H["text-ink"]


def ink(colour_key):
    """The colour key to draw TEXT in for an element tone: its darker ink variant when it has one."""
    return TEXT_INK.get(colour_key, colour_key)


def style(key):
    """(size pt, weight, colour hex) of a text style key."""
    size, weight, col = TEXT[key]
    return size, weight, COLORS[col]
