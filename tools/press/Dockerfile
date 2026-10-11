# press: the deck shooter + defect detectors (tools/press/press/deck_qa.py) —
# headless Chromium from the Playwright image, Pillow for the contact sheet and
# the contrast plates. Nothing is fetched at run time: the decks it checks are
# self-contained by contract, and the image is the only network-touching step.
# fonts-inter: the house font, so text metrics (and therefore the overflow
# numbers) match a desktop browser; the rest is the fallback stack the CSS names.
# The image ships the browsers (PLAYWRIGHT_BROWSERS_PATH=/ms-playwright) but not
# the python package: pin it to the image tag so the driver matches the
# installed chromium build. This Chromium has NO H.264 — a deliberate property
# the D8 detector reports as codec-unsupported-by-checker, never as broken.
#
# The figure pipeline (`press fig …`, tools/press/press/fig/) adds:
#   node + libavoid-js  the orthogonal edge router (WebAssembly build of libavoid)
#   ortools (CP-SAT)    the placer — every candidate layout comes from it
#   z3-solver           only to name the smallest conflicting constraint set of an
#                       unsatisfiable description
#   uharfbuzz+fonttools text measurement: HarfBuzz shaping of Inter, the shaper
#                       Chromium uses, so a box is sized from its real text
# The paged-layout pass of `press pdf` (tools/press/press/paged/) adds ONE PDF reader:
#   pdfplumber 0.11.4   glyphs with their marked-content ids, drawings, and (through
#   + pdfminer.six      pdfminer's objects) the structure tree of Chromium's tagged
#                       PDF; pypdfium2, charset-normalizer, cryptography, cffi and
#                       pycparser are its declared dependencies (pip check holds them)
# node is copied from the official image (a glibc build that runs on this jammy
# base), pinned by digest. The npm and pip installs read committed lock files
# (pins/package-lock.json with integrity hashes, pins/requirements.txt with a
# sha256 per wheel). These application dependencies are locked; apt still
# resolves the system fonts and PDF utilities from Ubuntu's package repositories.
FROM node:20.20.2-bookworm-slim@sha256:2cf067cfed83d5ea958367df9f966191a942351a2df77d6f0193e162b5febfc0 AS node
FROM mcr.microsoft.com/playwright/python:v1.47.0-jammy@sha256:f3a3d2e0332df4c7b6992db0ad0687df1653efd4671612b8ca1be6ae5fc06448
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
# poppler-utils: `press pdf` rasterises the FINISHED PDF into one PNG per page with
# pdftoppm, so the eyeball sees the browser's own pagination rather than a DOM
# screenshot of the page the author hoped for.
COPY pins/ /opt/pins/
RUN apt-get update && apt-get install -y --no-install-recommends \
      fonts-inter fonts-liberation fonts-dejavu-core poppler-utils \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir --require-hashes --no-deps -r /opt/pins/requirements.txt
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && mkdir -p /opt/js && cd /opt/js \
    && cp /opt/pins/package.json /opt/pins/package-lock.json . \
    && npm ci --no-audit --no-fund
# ES modules ignore NODE_PATH: the router imports the package entry by its absolute
# path, and the check below loads the WebAssembly the same way the router does.
RUN python3 -m pip check \
    && python3 -c "from playwright.sync_api import sync_playwright; from PIL import Image; print('press ok')" \
    && python3 -c "import ortools, z3, uharfbuzz, fontTools; print('fig solvers ok', ortools.__version__, z3.get_version_string())" \
    && python3 -c "import pdfplumber, pdfminer.pdfdocument; print('paged reader ok', pdfplumber.__version__)" \
    && node --input-type=module -e "import {AvoidLib} from '/opt/js/node_modules/libavoid-js/dist/index-node.mjs'; await AvoidLib.load('/opt/js/node_modules/libavoid-js/dist/libavoid.wasm'); console.log('libavoid ok', typeof AvoidLib.getInstance().Router)" \
    && pdftoppm -v
WORKDIR /work
ENTRYPOINT ["python3", "/work/press/deck_qa.py"]
