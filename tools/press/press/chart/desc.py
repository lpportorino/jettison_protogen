"""The chart description: `<id>.fig.json`, canonical JSON exactly as a diagram's (fig.desc.read:
keys sorted by code point, no whitespace, UTF-8 unescaped, one trailing newline, integers only and
each within +-100000), so the problem hash is the sha256 of the file.

    {"id": "fig6", "kind": "chart", "chart": "bar" | "stacked" | "timeline", "data": {...}}

A number in `data` is an integer, or -- when it has a fractional part -- a decimal STRING such as
"19.5" (within +-100000 too). It is drawn exactly as written. A colour (`tone`, `fill`, `outline`)
is a house colour key (house.json `colors`: NAVY, TEAL, AMBER, ...). Every id matches
`[A-Za-z0-9][A-Za-z0-9_-]*` on the WHOLE string. An unknown key anywhere is an error.

bar       title (text), rows [{label, value, tone, note?, emphasis?}] (at least 1; value >= 0),
          notes? [text]
stacked   bars [{title, total (> 0), segments, free-label?, legend? [{of, text}], note?}] (at least 1),
          notes? [text]; a segment is {id, size (> 0), fill, outline?, label?, inside? [segments]};
          an inside segment is drawn over the start of its parent and may not have its own inside;
          segment sizes add up to at most the total, inside sizes to at most their parent
timeline  lifelines [{id, title, sub?}] (at least 2), steps (at least 1), each one of
            {"step": "msg", from, to, text [lines], look? "solid"|"dashed", tone?, text-tone?}
            {"step": "box", at, title [lines], sub?, tone?}
            {"step": "break", text}
            {"step": "boxes", items [{at, title [lines], sub?}]}
            {"step": "panel", text}
          a message's tone is one with an arrowhead: NAVY, MUTED, TEAL, AMBER or RED
"""

import decimal
import re

from fig import house
from fig.desc import INT_MAX, DescError, _id, _keys, _str

KINDS = ("bar", "stacked", "timeline")
DECIMAL = re.compile(r"-?(?:0|[1-9][0-9]*)\.[0-9]+")
ARROW_TONES = ("NAVY", "MUTED", "TEAL", "AMBER", "RED")  # the colours fig.render has arrowheads for


def number(v, where, positive=False):
    """-> (Decimal value, the text it is drawn as). An int, or a decimal string with a fraction."""
    if isinstance(v, int) and not isinstance(v, bool):
        d, text = decimal.Decimal(v), str(v)
    elif isinstance(v, str) and DECIMAL.fullmatch(v):
        d, text = decimal.Decimal(v), v
        if abs(d) > INT_MAX:
            raise DescError(f"{where}: {v} is out of range (numbers lie within +-{INT_MAX})")
    else:
        raise DescError(
            f'{where}: expected an integer or a decimal string such as "19.5", got {v!r}'
        )
    if d < 0 or (positive and d == 0):
        raise DescError(f"{where}: expected a number {'> 0' if positive else '>= 0'}, got {text}")
    return d, text


def _colour(v, where, allowed=None):
    allowed = allowed or tuple(house.COLORS)
    if not isinstance(v, str) or v not in allowed:
        raise DescError(f"{where}: {v!r} is not one of {sorted(allowed)}")
    return v


def _lines(v, where):
    if not isinstance(v, list) or not v:
        raise DescError(f"{where}: expected a non-empty list of text lines")
    for i, s in enumerate(v):
        _str(s, f"{where}[{i}]")
    return v


def _texts(v, where):
    if not isinstance(v, list):
        raise DescError(f"{where}: expected a list of texts")
    for i, s in enumerate(v):
        _str(s, f"{where}[{i}]")
    return v


def _list(v, where, least=1):
    if not isinstance(v, list) or len(v) < least:
        raise DescError(f"{where}: expected a list of at least {least}")
    return v


def _bar(data):
    _keys(data, {"title", "rows", "notes"}, "data", required=("title", "rows"))
    _str(data["title"], "data.title")
    for i, r in enumerate(_list(data["rows"], "data.rows")):
        w = f"data.rows[{i}]"
        _keys(
            r,
            {"label", "value", "tone", "note", "emphasis"},
            w,
            required=("label", "value", "tone"),
        )
        _str(r["label"], f"{w}.label")
        number(r["value"], f"{w}.value")
        _colour(r["tone"], f"{w}.tone")
        if "note" in r:
            _str(r["note"], f"{w}.note")
        if "emphasis" in r and not isinstance(r["emphasis"], bool):
            raise DescError(f"{w}.emphasis: expected true or false")
    _texts(data.get("notes", []), "data.notes")


def _segments(segs, where, ids, depth):
    total = decimal.Decimal(0)
    for i, s in enumerate(_list(segs, where)):
        w = f"{where}[{i}]"
        _keys(
            s,
            {"id", "size", "fill", "outline", "label", "inside"},
            w,
            required=("id", "size", "fill"),
        )
        sid = _id(s["id"], f"{w}.id")
        if sid in ids:
            raise DescError(f"{w}: segment id {sid!r} is used twice")
        ids.add(sid)
        size, _ = number(s["size"], f"{w}.size", positive=True)
        total += size
        _colour(s["fill"], f"{w}.fill")
        if "outline" in s:
            _colour(s["outline"], f"{w}.outline")
        if "label" in s:
            _str(s["label"], f"{w}.label")
        if "inside" in s:
            if depth:
                raise DescError(f"{w}.inside: an inside segment cannot hold segments of its own")
            inner = _segments(s["inside"], f"{w}.inside", ids, depth + 1)
            if inner > size:
                raise DescError(
                    f"{w}.inside: the inside sizes add up to {inner}, more than the segment ({size})"
                )
    return total


def _stacked(data):
    _keys(data, {"bars", "notes"}, "data", required=("bars",))
    ids = set()
    for i, b in enumerate(_list(data["bars"], "data.bars")):
        w = f"data.bars[{i}]"
        _keys(
            b,
            {"title", "total", "segments", "free-label", "legend", "note"},
            w,
            required=("title", "total", "segments"),
        )
        _str(b["title"], f"{w}.title")
        total, _ = number(b["total"], f"{w}.total", positive=True)
        mine = set()
        used = _segments(b["segments"], f"{w}.segments", mine, 0)
        if used > total:
            raise DescError(f"{w}: the segments add up to {used}, more than the total ({total})")
        if ids & mine:
            raise DescError(f"{w}: segment id(s) {sorted(ids & mine)} are used in another bar")
        ids |= mine
        if "free-label" in b:
            _str(b["free-label"], f"{w}.free-label")
        if not isinstance(b.get("legend", []), list):
            raise DescError(f"{w}.legend: expected a list")
        for j, it in enumerate(b.get("legend", [])):
            _keys(it, {"of", "text"}, f"{w}.legend[{j}]", required=("of", "text"))
            if not isinstance(it["of"], str) or it["of"] not in mine:
                raise DescError(f"{w}.legend[{j}].of: {it['of']!r} is not a segment of this bar")
            _str(it["text"], f"{w}.legend[{j}].text")
        if "note" in b:
            _str(b["note"], f"{w}.note")
    _texts(data.get("notes", []), "data.notes")


STEP_KEYS = {
    "msg": ({"step", "from", "to", "text", "look", "tone", "text-tone"}, ("from", "to", "text")),
    "box": ({"step", "at", "title", "sub", "tone"}, ("at", "title")),
    "break": ({"step", "text"}, ("text",)),
    "boxes": ({"step", "items"}, ("items",)),
    "panel": ({"step", "text"}, ("text",)),
}


def _timeline(data):
    _keys(data, {"lifelines", "steps"}, "data", required=("lifelines", "steps"))
    lids = []
    for i, ll in enumerate(_list(data["lifelines"], "data.lifelines", least=2)):
        w = f"data.lifelines[{i}]"
        _keys(ll, {"id", "title", "sub"}, w, required=("id", "title"))
        lid = _id(ll["id"], f"{w}.id")
        if lid in lids:
            raise DescError(f"{w}: lifeline id {lid!r} is used twice")
        lids.append(lid)
        _str(ll["title"], f"{w}.title")
        if "sub" in ll:
            _str(ll["sub"], f"{w}.sub")

    def life(v, where):
        if not isinstance(v, str) or v not in lids:
            raise DescError(f"{where}: {v!r} is not a lifeline")
        return v

    for i, st in enumerate(_list(data["steps"], "data.steps")):
        w = f"data.steps[{i}]"
        if (
            not isinstance(st, dict)
            or not isinstance(st.get("step"), str)
            or st["step"] not in STEP_KEYS
        ):
            raise DescError(f'{w}: a step is an object whose "step" is one of {sorted(STEP_KEYS)}')
        keys, req = STEP_KEYS[st["step"]]
        _keys(st, keys, w, required=req)
        k = st["step"]
        if k == "msg":
            if life(st["from"], f"{w}.from") == life(st["to"], f"{w}.to"):
                raise DescError(f"{w}: a message from {st['from']!r} to itself")
            _lines(st["text"], f"{w}.text")
            if "look" in st and st["look"] not in ("solid", "dashed"):
                raise DescError(f'{w}.look: {st["look"]!r} is not one of ["dashed", "solid"]')
            if "tone" in st:
                _colour(st["tone"], f"{w}.tone", ARROW_TONES)
            if "text-tone" in st:
                _colour(st["text-tone"], f"{w}.text-tone")
        elif k == "box":
            life(st["at"], f"{w}.at")
            _lines(st["title"], f"{w}.title")
            if "sub" in st:
                _str(st["sub"], f"{w}.sub")
            if "tone" in st:
                _colour(st["tone"], f"{w}.tone")
        elif k in ("break", "panel"):
            _str(st["text"], f"{w}.text")
        else:
            seen = set()
            for j, it in enumerate(_list(st["items"], f"{w}.items")):
                wj = f"{w}.items[{j}]"
                _keys(it, {"at", "title", "sub"}, wj, required=("at", "title"))
                if life(it["at"], f"{wj}.at") in seen:
                    raise DescError(f"{wj}.at: two boxes on lifeline {it['at']!r} in one row")
                seen.add(it["at"])
                _lines(it["title"], f"{wj}.title")
                if "sub" in it:
                    _str(it["sub"], f"{wj}.sub")


def validate(tree):
    """Validate a chart description tree (already canonical, see fig.desc.read); returns it."""
    _keys(
        tree,
        {"id", "kind", "chart", "data"},
        "description",
        required=("id", "kind", "chart", "data"),
    )
    _id(tree["id"], "id")
    if tree["kind"] != "chart":
        raise DescError(
            f'kind: {tree["kind"]!r} -- `press chart` draws kind "chart"; a diagram goes to `press fig solve`'
        )
    if not isinstance(tree["chart"], str) or tree["chart"] not in KINDS:
        raise DescError(f"chart: {tree['chart']!r} is not one of {list(KINDS)}")
    {"bar": _bar, "stacked": _stacked, "timeline": _timeline}[tree["chart"]](tree["data"])
    return tree


def load(path):
    """-> (problem_hash, chart description). The file must be named `<id>.fig.json`."""
    import pathlib
    from fig.desc import problem_hash, read

    raw, tree = read(path)
    validate(tree)
    if pathlib.Path(path).name != f"{tree['id']}.fig.json":
        raise DescError(
            f"{path}: the description id {tree['id']!r} is not the file stem; name the file "
            f"{tree['id']}.fig.json"
        )
    return problem_hash(raw), tree
