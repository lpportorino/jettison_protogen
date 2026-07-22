<!-- LOAD-TEST: uber-container -->

# Builds & generation run in the pinned toolchain — never the host

Every operation that PRODUCES a committed or gate-checked artifact runs on the
PINNED toolchain, never the host's: binding generation, the renderer/wasm build,
the devcards render + golden mint + JPEG gallery, docs generation, and the test
suites.

## The ergonomic local entry point
`tools/uber.sh` runs any command inside the "uber" container built from
`Dockerfile.base` — the one image that carries EVERY pinned toolchain (WASI-SDK
clang, GraalVM Community JDK 25 + Clojure, protoc + plugins). It detects the host
arch and pins docker `--platform` so a local run matches CI.

```
tools/uber.sh 'make generate'
tools/uber.sh 'make -f renderer.mk gallery-prebuilt'
tools/uber.sh --check          # verify the image builds AND executes here
```

Do NOT run `clojure` / `javac` / `protoc` / `make` on the host to produce a
committed artifact — the host's toolchain versions (JDK, WASI-SDK, protoc) differ
from the pins, so the output diverges from CI and from what consumers vendor.
Concretely: the host JDK's `javax.imageio` encoder rewrites every committed
gallery JPEG byte-for-byte. Pure host-side work (git, grep, reading files,
editing source) produces no artifact and is fine.

## Why the gallery still matches even though CI splits the work
CI (`.github/workflows/renderer.yml`) splits for speed: the wasm build runs in
the uber container (it needs the WASI-SDK); `fixtures-prebuilt` / `gallery-prebuilt`
run on a plain runner with `setup-graalvm` **JDK 25** consuming the prebuilt wasm.
Both legs pin the SAME versions, and the uber image bundles that same GraalVM 25,
so a local `tools/uber.sh` gallery run and CI's GraalVM-25 gallery run emit
byte-identical JPEGs. Locally, one container does everything; don't reproduce the
CI split on your machine.

## Arch / platform gotcha
If `tools/uber.sh --check` reports the image built but "cannot execute", your
docker/buildkit is cross-building for a non-native platform. That is a host
builder problem (fix the native `docker buildx` builder or
`DOCKER_DEFAULT_PLATFORM`), not a repo problem — the Dockerfile and helper are
correct. If you must run the JDK-only legs (devcards / gallery / docs) before
fixing that, use **GraalVM Community JDK 25** — the exact pin CI's
`setup-graalvm` uses, which JIT-compiles GraalWasm — not the host JDK. A stock
OpenJDK 25 renders byte-identical output (GraalWasm runs interpreted; the JPEG
`javax.imageio` encoder is the same OpenJDK-25 code) but is much slower and
isn't the pinned toolchain.

## Do not change CI or the consumer integration
`renderer.yml` and the consumer repos' proto build already work — this helper is a
LOCAL convenience layered on top, not a change to those flows.
