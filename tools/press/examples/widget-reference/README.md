# Widget reference example

[doc.edn](doc.edn) builds a paged reference for three widgets from the public
[devcards gallery](../../../devcards/docs/widgets/README.md). It uses the original
JPEGs in their existing directories. The captions identify each card and theme;
the descriptions cite the public widget READMEs. This is a selection, not a full
widget catalog or an assessment of a physical display.

The `:doc/image` block requires a document-relative local `:src` and descriptive
`:alt`. Optional `:caption` accepts the same inline runs as a table caption.
`:width-percent` (1–100, default 100) and `:max-height-mm` (1–180, default 120)
bound the image while keeping its aspect ratio. Images are allowed on the cover
and at page top level, and their captions are unnumbered. PNG/JPEG inputs must
fully decode without warnings, fit within 20 MiB and 16 million pixels, and
resolve inside the configured repository root. The builder embeds their bytes
with MIME determined by decoding. Their file hashes participate in the build
stamp, so changing or deleting a picture requires rebuilding before printing.

Build the tool images from the Protogen root:

```sh
tools/uber.sh --build
make -f press.mk image
```

Build and check the page with the pinned Clojure owner:

```sh
UBER_NETWORK=none tools/uber.sh 'cd tools/press && clojure -M -e '\''(require (quote [press.doc :as doc])) (prn (doc/check "tools/press/examples/widget-reference")) (prn (doc/build! "tools/press/examples/widget-reference" ".press/doc-build/widget-reference")) (prn (doc/runbook! "tools/press/examples/widget-reference"))'\'''
tools/press/bin/press pdf .press/doc-build/widget-reference/widget-reference.html .press/doc-build/widget-reference/pdf --name widget-reference
```

[RUNBOOK.md](RUNBOOK.md) gives the library forms and returned print command.
Read `pdf_report.json` and inspect every `page-NN.png` in the build directory
before copying the final PDF beside the source. The HTML, page rasters and
reports remain ignored build output; only the reviewed PDF is retained here.

The regression suite includes resolved-house identity, breakable-caption,
local/decode/schema/layout and image-freshness assertions. Run it offline with
`UBER_NETWORK=none tools/uber.sh 'cd tools/press && clojure -M:test'`.
For an affected-scope mutation assessment, copy `tools/press` into ignored
`.press/doc-mutations/<fault>` directories and run each copy in a fresh process
with `clojure -J-Dpress.root=/workspace -M:test` inside the same owner. Exercise
resolved path comparison, sibling caption placement, alt propagation, raster
stamp inclusion, unsupported format refusal, canonical-root containment,
decode-warning refusal and nonblank alt schema separately. Keep a passing
baseline and final control, exact substitutions and source/test/tool hashes,
and named failing assertions plus neighboring passing controls in the ignored
campaign report. An assertion failure establishes a kill; an exception or
empty suite does not.
