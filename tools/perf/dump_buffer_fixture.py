#!/usr/bin/env python3
"""Emit a ui.Screen .pb holding a POOL of N identically-shaped boxes.

WHY THIS EXISTS. `controls_dump_tree` writes into ONE static buffer whose
size is a compile-time constant (renderer/src/main.c, TREE_BUF_SIZE), and
`dump_obj` recurses into every lv_obj child unconditionally. A consumer
sizing a statically-allocated widget pool therefore needs the OCCUPANCY
CURVE — dump bytes as a function of pooled child count — not a guess. This
generator is the independent variable of that measurement; the harness's
`--dump-tree` is the dependent one.

THE SHAPE IS THE ONE UNDER STUDY, not a synthetic node: each pooled element
is a container carrying a background fill and a border, holding one text
child. That is two lv_obj nodes per element, which is the first thing a
per-element estimate gets wrong.

Every knob that moves per-node dump bytes is a flag here rather than a
constant, because the per-node cost is NOT a constant and pretending it is
was the stated hazard:

  --hidden     set LV_OBJ_FLAG_HIDDEN on every pooled container, to test
               whether an unshown pool still pays. The dump emits a
               `hidden` key AND a `vis_px` key when a node is not fully
               visible, so the hypothesis "hidden is cheaper" has to beat
               a mechanism that makes it DEARER.
  --uid-scope  which nodes carry a uid: none / container / all. This is not
               a cosmetic knob — the renderer registers every NONZERO uid in
               a fixed 1024-slot table and REFUSES the whole load when it
               fills, so uid density decides whether the dump buffer is the
               binding ceiling at all.
  --label-text the label's string. The dump caps an emitted string at 64
               chars, so this separates "text length costs bytes" from
               "text length costs bytes without limit".
  --no-label   omit the text child, halving nodes per element — the cheapest
               way to show that a per-ELEMENT cost is a per-NODE cost in
               disguise.
  --absolute   place boxes at explicit x/y instead of flex-wrapping them,
               so coordinate MAGNITUDE (decimal digit count) is separable
               from layout.

Usage:
  dump_buffer_fixture.py --count N --out FILE [flags]
"""

import argparse
import os
import sys

# The generated bindings live under output/python; this script is invoked
# from the repository root inside the pinned toolchain container.
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "output", "python"))

from ui import ui_ast_pb2 as ast  # noqa: E402

# lv_obj.h: LV_OBJ_FLAG_HIDDEN = (1u << 0). Direct-cast by the renderer
# (ui_ast.proto WidgetNode.obj_flags).
LV_OBJ_FLAG_HIDDEN = 1


def _prop(kind, **kw):
    p = ast.StyleProperty(type=kind)
    for key, value in kw.items():
        if key == "color":
            p.color_value.r, p.color_value.g, p.color_value.b = value
        elif key == "u":
            p.uint_value = value
        elif key == "i":
            p.int_value = value
    return p


def _group(props):
    g = ast.StyleGroup(state_selector=0)
    v = g.variants.add()
    v.variant_index = 0
    v.properties.extend(props)
    return g


def make_box(index, args, uid_base):
    """One pooled element: bordered, filled container + one text child."""
    box = ast.WidgetNode(type=ast.WIDGET_OBJ)
    box.style_groups.append(
        _group(
            [
                # Slot choice is NOT free: the renderer refuses a value in
                # the wrong `oneof` arm (renderer.c slot_ok), so each of
                # these matches the arm that arm's apply case demands.
                _prop(ast.PROP_WIDTH, u=args.box_w),
                _prop(ast.PROP_HEIGHT, u=args.box_h),
                _prop(ast.PROP_BG_COLOR, color=(0x20, 0x30, 0x40)),
                _prop(ast.PROP_BG_OPA, u=255),
                _prop(ast.PROP_BORDER_COLOR, color=(0xC0, 0xD0, 0xE0)),
                _prop(ast.PROP_BORDER_WIDTH, u=2),
                _prop(ast.PROP_PAD_ALL, u=2),
            ]
        )
    )
    if args.absolute:
        # Deterministic tiling that deliberately runs PAST the display, so a
        # large pool exercises the same off-screen arms a real one would.
        cols = max(1, args.screen_w // (args.box_w + 4))
        box.x = (index % cols) * (args.box_w + 4)
        box.y = (index // cols) * (args.box_h + 4)
    if args.hidden:
        box.obj_flags = LV_OBJ_FLAG_HIDDEN
    if args.uid_scope in ("container", "all"):
        box.uid = uid_base

    if not args.no_label:
        label = ast.WidgetNode(type=ast.WIDGET_LABEL)
        label.text = args.label_text
        if args.uid_scope == "all":
            label.uid = uid_base + 1
        box.children.append(label)
    return box


def build(args):
    root = ast.WidgetNode(type=ast.WIDGET_OBJ)
    root.style_groups.append(
        _group(
            [
                _prop(ast.PROP_WIDTH, u=args.screen_w),
                _prop(ast.PROP_HEIGHT, u=args.screen_h),
                _prop(ast.PROP_PAD_ALL, u=0),
                _prop(ast.PROP_BG_OPA, u=0),
            ]
        )
    )
    if not args.absolute:
        root.layout.flow = ast.FLEX_FLOW_ROW_WRAP
    for i in range(args.count):
        # 100-apart so a uid never collides with a neighbour's label.
        root.children.append(make_box(i, args, 1000 + i * 100))
    screen = ast.Screen()
    screen.root.CopyFrom(root)
    return screen


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--hidden", action="store_true")
    ap.add_argument(
        "--uid-scope", choices=("none", "container", "all"), default="none"
    )
    ap.add_argument("--no-label", action="store_true")
    ap.add_argument("--absolute", action="store_true")
    ap.add_argument("--label-text", default="B")
    ap.add_argument("--box-w", type=int, default=60)
    ap.add_argument("--box-h", type=int, default=40)
    ap.add_argument("--screen-w", type=int, default=960)
    ap.add_argument("--screen-h", type=int, default=540)
    args = ap.parse_args()
    data = build(args).SerializeToString()
    with open(args.out, "wb") as fh:
        fh.write(data)
    print(f"{args.out} count={args.count} wire_bytes={len(data)}")


if __name__ == "__main__":
    main()
