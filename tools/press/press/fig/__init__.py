"""The figure pipeline of press: a coordinate-free figure description in, a checked
house-style SVG out.

  desc     read + validate the description, its canonical bytes and problem hash, views
  measure  text measured with HarfBuzz (Inter, calt off) -> every box's size
  place    CP-SAT placement, groups bottom-up, diverse candidates
  explain  Z3: the smallest set of description constraints that cannot hold together
  route    libavoid (node) plus the four house rules -> orthogonal edge paths
  render   resolved geometry -> SVG (a pure function: the rebuild path)
  check    the figure checker, in Chromium
  sheet    the numbered contact sheet of candidates
  budget   the house page budget: refuse with what is needed, or propose a split
  cli      `press fig solve | pick | rebuild | check`
"""
