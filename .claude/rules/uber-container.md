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

**A build that needs a new tool ADDS IT TO `Dockerfile.base`, pinned, in the
same change — and rebuilds the image in that change too.** `uber.sh` is
`present || build`: it reuses whatever base image already exists and never
notices `Dockerfile.base` moved, so a pin bump silently leaves you gating on the
OLD toolchain while CI (a `docker build` per job, on a fresh runner) uses the
new one. `tools/uber.sh --build` is what closes it; nothing else will.

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

## What a container run reproduces — everything, JPEGs included
CI (`.github/workflows/renderer.yml`) splits for speed: the wasm build runs in
the uber container (it needs the WASI-SDK); `fixtures-prebuilt` /
`gallery-prebuilt` run on a plain runner with `setup-graalvm` consuming the
prebuilt wasm. Both legs pin the same toolchain versions (the uber image bundles
that same GraalVM), so a run IN THE CONTAINER reproduces CI byte-for-byte across
all three artifact classes: goldens (raw framebuffer sha256), generated doc
TEXT, **and the gallery JPEGs**.

The proof is CI's own freshness step, and it is NOT a battery lane: after the
containerised regen, `renderer.yml` and `devcards.yml` run
`git diff --exit-code tools/devcards/goldens tools/devcards/docs` on the
RUNNER. It has to be there — git cannot resolve this checkout from inside the
container (`detected dubious ownership`), the same reason `standard-brief`'s
freshness half is a separate non-battery target — and `check-renderer`'s
`fixtures` lane covers only HALF of what that diff spans. Write the boundary,
because it moved: the lane now READS each committed `goldens/manifest-*.edn`
before the mint overwrites it and fails on any drifted, missing or new card
(`gates/golden-drift-findings`), so a green in-container battery DOES assert the
goldens are fresh. It asserts nothing about `tools/devcards/docs` — the JPEG
contact sheets and generated pages come from the separate `gallery` mode and are
still mint-only, and `check-renderer` does not even list `gallery-prebuilt`. So
the runner diff stays load-bearing, for the DOCS half. What makes the claim above
true is the measurement: a full gallery re-mint from this container passed that
CI step unchanged.

So a pixel-shifting renderer or corpus change re-mints goldens AND the gallery
locally in the pinned container, and commits both in the same change — the
devcards rule's "re-mint both together" is a thing you can actually satisfy.

The HOST is what diverges. Its JDK's `javax.imageio` encoder rewrites every
gallery JPEG byte-for-byte, so a host-side gallery run produces sheets CI will
reject — every one of them. That divergence is the reason for the container
requirement above, and it is a property of the HOST toolchain — not of JPEG
encoding as such.

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
