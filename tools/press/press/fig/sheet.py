"""Pages built from figures load nothing but their own file: every other request is aborted.

The numbered candidate sheet: every candidate that passed the checker, each tile at the same
168 mm print width, numbered 1..N in large type, with its height and score — the picture an
author (or a vision model) picks from. At most 8 tiles: more and they get too small to judge."""

import html
import pathlib

from playwright.sync_api import sync_playwright

CSS = (
    '.svgtxt{font-family:"Inter","Liberation Sans",Arial,sans-serif} .svgtxt,.svgtxt text{font-feature-settings:"calt" 0}'
    "body{margin:10px;background:#e9edf1;font-family:Inter,sans-serif}"
    ".g{display:grid;grid-template-columns:repeat(2,168mm);gap:7mm 8mm;align-items:start}"
    ".t{background:#fff;padding:3mm;border:1px solid #9aa}"
    ".t svg{display:block;width:100%;height:auto}"
    ".n{font:800 30px Inter;color:#a8352f;margin:0 3mm 1mm 0;display:inline-block}"
    ".lab{font:500 12px Inter;color:#333;display:inline-block;vertical-align:6px}"
)


def build(out_dir, title, tiles):
    """tiles = [(tile number, svg text, caption)] -> <out_dir>/sheet.html + sheet.png"""
    out = pathlib.Path(out_dir)
    body = "".join(
        f"<div class='t'><span class='n'>{n}</span><span class='lab'>{html.escape(cap)}</span>{svg}</div>"
        for n, svg, cap in tiles
    )
    page = out / "sheet.html"
    page.write_text(
        f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS}</style></head><body>"
        f"<h3 style='font:700 15px Inter;margin:0 0 8px'>{html.escape(title)}</h3>"
        f"<div class='g'>{body}</div></body></html>",
        encoding="utf-8",
    )
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(device_scale_factor=1.5)
        pg.set_viewport_size({"width": 1340, "height": 900})
        pg.route(
            "**/*", lambda r: r.continue_() if r.request.url.startswith("file:") else r.abort()
        )
        pg.goto("file://" + str(page.resolve()))
        pg.evaluate("document.fonts.ready.then(() => 1)")
        pg.screenshot(path=str(out / "sheet.png"), full_page=True)
        b.close()
    return str(out / "sheet.png")


def shoot(svg_path, png_path):
    """One figure alone at 168 mm print width, 3x device scale — for looking at a pick."""
    page = pathlib.Path(png_path).with_suffix(".shot.html")
    page.write_text(
        f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS} svg{{display:block;width:168mm;height:auto}}"
        f"</style></head><body style='margin:12px;background:#fff'>{pathlib.Path(svg_path).read_text()}</body></html>",
        encoding="utf-8",
    )
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        pg = b.new_page(device_scale_factor=3)
        pg.set_viewport_size({"width": 700, "height": 1200})
        pg.route(
            "**/*", lambda r: r.continue_() if r.request.url.startswith("file:") else r.abort()
        )
        pg.goto("file://" + str(page.resolve()))
        pg.evaluate("document.fonts.ready.then(() => 1)")
        pg.query_selector("svg").screenshot(path=str(png_path))
        b.close()
    page.unlink()
