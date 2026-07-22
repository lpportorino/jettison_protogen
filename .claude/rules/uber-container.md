<!-- LOAD-TEST: uber-container -->

# Builds & generation run in the pinned toolchain — never the host

Every operation that PRODUCES a committed or gate-checked artifact runs on the
PINNED toolchain, never the host's: binding generation, the renderer/wasm build,
the devcards render + golden mint + JPEG gallery, docs generation, and the test
suites.

## The ergonomic local entry point
`tools/uber.sh` runs any command inside the "uber" container built from
`Dockerfile.base` — the one image that carries EVERY pinned toolchain (WASI-SDK
clang, GraalVM Community JDK + Clojure, protoc + plugins). It pins docker
`--platform` to the host's native arch; CI parity comes from the pinned
toolchain versions (below), not the platform flag.

```
tools/uber.sh 'make -f renderer.mk check-renderer'    # full renderer proof battery
tools/uber.sh 'make -f renderer.mk gallery-prebuilt'  # devcards JPEG gallery
tools/uber.sh --check                                 # build AND verify it executes
```

Run the toolchain directly (`make -f renderer.mk …`, `make docs-generate`,
`clojure`, `cargo`, `protoc`). Targets that themselves orchestrate docker
(`make generate`, `docs-docker-*`, `binary-dedup`) can't run in here — the base
image ships no docker CLI; those stay on the host.

Do NOT run `clojure` / `javac` / `protoc` / `make` on the host to produce a
committed artifact — the host's toolchain versions (JDK, WASI-SDK, protoc) differ
from the pins, so the output diverges from CI and from what consumers vendor.
Concretely: the host JDK's `javax.imageio` encoder rewrites every committed
gallery JPEG byte-for-byte. Pure host-side work (git, grep, reading files,
editing source) produces no artifact and is fine.

## What a local run reproduces (goldens + text, not JPEGs)
CI (`.github/workflows/renderer.yml`) splits for speed: the wasm build runs in
the uber container (it needs the WASI-SDK); `fixtures-prebuilt` /
`gallery-prebuilt` run on a plain runner with `setup-graalvm` consuming the
prebuilt wasm. Both legs pin the same toolchain versions (the uber image bundles
that same GraalVM), so a local run and CI produce identical goldens (raw
framebuffer sha256) and identical generated doc TEXT.

The gallery **JPEGs are NOT reproducible across machines** — `javax.imageio`'s
encoder output depends on the OS's native libs, so the same fixture re-encodes to
different bytes locally vs CI. A JPEG-changing gallery edit is therefore re-minted
by CI, never committed from a local run; a text-only gallery edit commits just the
`README.md`. Goldens and doc text are what a local run verifies.

## Running the base image directly
`Dockerfile.base` sets no ENTRYPOINT: `docker run <base> <cmd>` runs `<cmd>`
directly — exactly how `renderer.yml` invokes it (`docker run <base> make -f
renderer.mk …`). A bare `docker run <base>` still opens a shell (ubuntu's default
`CMD`). `tools/uber.sh` passes a `-lc "…"` script string, so it names the shell
explicitly via `--entrypoint bash`. The MAIN image (`Dockerfile`) keeps its own
bash ENTRYPOINT for `generate-protos.sh`'s `-c` flow — leave that one in place.

## Shared with CI, not a fork of it
`renderer.yml` runs the same battery in this same base image, and the consumer
repos' proto build is unaffected. This helper is a LOCAL entry point onto that
shared image — not a parallel flow. Fix the image itself (e.g. its ENTRYPOINT
contract) in `Dockerfile.base` so CI and local stay in step.
