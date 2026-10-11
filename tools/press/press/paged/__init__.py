"""The paged-layout pass of `press pdf` and `press pdfcheck`: where a document's elements landed on the
printed pages, and what went wrong there.

  model    the DOM snapshot joined to Chromium's tagged PDF (node id -> glyphs); the join's own
           proof (`join_check`)
  detect   the fifteen detectors over that model
  pdfonly  `press pdfcheck`: the subset that holds up on a PDF with no HTML
"""
