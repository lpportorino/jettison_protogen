#!/usr/bin/env python3
"""Census a controls_dump_tree artifact: what does a node actually COST?

WHY. A pool cap derived from "bytes per node" assumes a per-node constant.
`dump_obj` does not emit one. Almost every key it writes is conditional —
emitted only when the value DIFFERS from an inherited or default one — so a
node's cost depends on where it sits (coordinate digit count, whether an
ancestor clips it), on its class, and on its content. This tool reads a real
dump and reports the distribution instead of a mean, so a claim of the form
"about N bytes per box" can be checked against the spread it hides.

It also serves as the falsification instrument for the hidden-children
question: `hidden` and `vis_px` are keys, so if a hidden node cost nothing
they would not appear.

Usage:
  dump_tree_census.py TREE.json [TREE.json ...]

A TRUNCATED dump is refused rather than parsed. Truncation is not a clean
prefix here (see the probe's findings), so any census of one would be a
census of fragments wearing a tree's clothes.
"""

import collections
import json
import sys

SENTINEL = ',"truncated":true'


def walk(node, depth=0):
    yield depth, node
    for child in node.get("children", []):
        yield from walk(child, depth + 1)


def node_self_bytes(node):
    """Bytes this node contributes EXCLUDING its subtree.

    Serialised with the dump's own separator-free compact form, then the
    children array's contents removed, which is exactly the split a
    per-node cost claim is making implicitly.

    Measured in BYTES, not characters, because the buffer this is a census
    of is a byte array — `ensure_ascii=False` then a UTF-8 encode, so a
    non-ASCII label costs what it costs the renderer.
    """
    shallow = {k: v for k, v in node.items() if k != "children"}
    # `{...}` minus the braces, plus the `,"children":[]}` tail dump_obj
    # always writes.
    body = json.dumps(shallow, separators=(",", ":"), ensure_ascii=False)
    return len(body.encode("utf-8")) - 1 + len(',"children":[]}')


def census(path):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    if text.endswith(SENTINEL):
        print(f"{path}: TRUNCATED ({len(raw)} bytes) — refusing to census fragments")
        return
    root = json.loads(text)
    nodes = list(walk(root))
    keys = collections.Counter()
    by_type = collections.defaultdict(list)
    sizes = []
    for _depth, node in nodes:
        for key in node:
            if key != "children":
                keys[key] += 1
        size = node_self_bytes(node)
        sizes.append(size)
        by_type[node["type"]].append(size)
    total = len(raw)
    print(f"{path}")
    print(
        f"  bytes={total} nodes={len(nodes)} "
        f"mean_bytes_per_node={total / len(nodes):.1f}"
    )
    print("  per-type self-bytes (min/median/max, count):")
    for typ, vals in sorted(by_type.items()):
        vals.sort()
        med = vals[len(vals) // 2]
        print(f"    {typ:<12} {vals[0]:>4} / {med:>4} / {vals[-1]:>4}   n={len(vals)}")
    print("  key frequency (key: nodes emitting it / total nodes):")
    for key, count in keys.most_common():
        print(f"    {key:<22} {count:>5} / {len(nodes)}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    for path in sys.argv[1:]:
        census(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
