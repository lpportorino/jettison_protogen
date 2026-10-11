"""Charts from data: a coordinate-free chart description in, a checked house-style SVG out.

  desc    the chart description: canonical JSON (fig.desc.read), strict per-kind data schemas
  render  one renderer for the three kinds -- bar, stacked (a memory map), timeline (lifelines,
          messages, boxes) -- over text measured by fig.measure and drawn by fig.render's element
          writers, in the house constants of house.json; deterministic; never scales text below the
          floor: a chart that cannot fit is refused with what it needs
  cli     `press chart <chart.fig.json> <pick.json> <out.svg>`: render, run the figure checker, write
          the SVG and its pick record {id, problem_hash, svg_sha256, renderer: "chart"}
`press fig rebuild` redraws a chart from its pick record with the same refusals as a diagram.
"""
