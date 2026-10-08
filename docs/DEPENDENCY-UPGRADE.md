# Dependency upgrade audit

Checked on 2026-10-08 against the integrated public baseline `0a052cdda212fa7b3d65324320f07c2ca951904a`. Version selection alone does not establish build or behavioral acceptance; the acceptance record below does, and names what it could not reach.

## Clojure and JVM libraries

Release metadata comes from the publishing repository. Stable versions exclude alpha, beta, release candidate, snapshot, milestone and preview labels. Every tracked `deps.edn` Maven coordinate is included below. Git dependencies and toolchains are tracked separately.

| Coordinate | Previous declared versions | Selected stable | Primary release metadata |
| --- | --- | --- | --- |
| `dev.weavejester/cljfmt` | 0.16.4 | 0.16.6 | [metadata](https://repo.clojars.org/dev/weavejester/cljfmt/maven-metadata.xml) |
| `io.github.noahtheduke/splint` | 1.24.0 | 1.25.0 | [metadata](https://repo.clojars.org/io/github/noahtheduke/splint/maven-metadata.xml) |
| `org.clojure/clojure` | 1.12.0, 1.12.2, 1.12.5 | 1.12.6 | [metadata](https://repo.maven.apache.org/maven2/org/clojure/clojure/maven-metadata.xml) |
| `metosin/malli` | 0.20.0, 0.20.2 | 0.20.3 | [metadata](https://repo.clojars.org/metosin/malli/maven-metadata.xml) |
| `org.clojure/data.json` | 2.5.1 | 2.5.2 | [metadata](https://repo.maven.apache.org/maven2/org/clojure/data.json/maven-metadata.xml) |
| `markdown-clj/markdown-clj` | 1.12.2 | 1.12.10 | [metadata](https://repo.clojars.org/markdown-clj/markdown-clj/maven-metadata.xml) |
| `selmer/selmer` | 1.12.61 | 1.13.5 | [metadata](https://repo.clojars.org/selmer/selmer/maven-metadata.xml) |
| `com.taoensso/telemere` | 1.1.0 | 1.4.0 | [metadata](https://repo.clojars.org/com/taoensso/telemere/maven-metadata.xml) |
| `org.clojure/test.check` | 1.1.1, 1.1.3 | 1.1.3 | [metadata](https://repo.maven.apache.org/maven2/org/clojure/test.check/maven-metadata.xml) |
| `com.rpl/specter` | 1.1.4, 1.1.6 | 1.1.6 | [metadata](https://repo.clojars.org/com/rpl/specter/maven-metadata.xml) |
| `com.google.jimfs/jimfs` | 1.3.0 | 1.3.2 | [metadata](https://repo.maven.apache.org/maven2/com/google/jimfs/jimfs/maven-metadata.xml) |
| `com.google.protobuf/protobuf-java` | 4.35.0 | 4.36.2 | [metadata](https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java/maven-metadata.xml) |
| `build.buf/protovalidate` | 1.2.2 | 1.3.0 | [metadata](https://repo.maven.apache.org/maven2/build/buf/protovalidate/maven-metadata.xml) |
| `com.gfredericks/test.chuck` | 0.2.14 | 0.2.15 | [metadata](https://repo.clojars.org/com/gfredericks/test.chuck/maven-metadata.xml) |
| `io.github.clojure/tools.build` | 0.10.10 | 0.10.14 | [metadata](https://repo.maven.apache.org/maven2/io/github/clojure/tools.build/maven-metadata.xml) |
| `org.graalvm.polyglot/polyglot` | 25.0.2 | 25.4.4.1.1 | [metadata](https://repo.maven.apache.org/maven2/org/graalvm/polyglot/polyglot/maven-metadata.xml) |
| `org.graalvm.wasm/wasm` | 25.0.2 | 25.4.4.1.1 | [metadata](https://repo.maven.apache.org/maven2/org/graalvm/wasm/wasm/maven-metadata.xml) |
| `metosin/jsonista` | 1.0.0 | 1.0.2 | [metadata](https://repo.clojars.org/metosin/jsonista/maven-metadata.xml) |
| `hiccup/hiccup` | 2.0.0 | 2.0.0 | [metadata](https://repo.clojars.org/hiccup/hiccup/maven-metadata.xml) |
| `thheller/shadow-cljs` | 3.5.3 | 3.5.5 | [metadata](https://repo.clojars.org/thheller/shadow-cljs/maven-metadata.xml) |
| `lambdaisland/kaocha` | 1.91.1392 | 1.91.1392 | [metadata](https://repo.clojars.org/lambdaisland/kaocha/maven-metadata.xml) |
| `camel-snake-kebab/camel-snake-kebab` | 0.4.3 | 0.4.3 | [metadata](https://repo.clojars.org/camel-snake-kebab/camel-snake-kebab/maven-metadata.xml) |
| `org.clojure/tools.logging` | 1.3.1 | 1.3.1 | [metadata](https://repo.maven.apache.org/maven2/org/clojure/tools.logging/maven-metadata.xml) |

## Required acceptance

- [x] Establish the exact integrated source baseline without importing coordination history.
- [x] Inventory all tracked Maven declarations and verify published stable versions.
- [x] Inventory Git dependencies, generator packages, container images, native tools and derived pins (see "Git, native and workflow pins").
- [x] Reproduce the clj-kondo type-analysis defect on the latest unmodified release and verify the patch against positive and negative regression cases.
- [x] Rebuild isolated toolchain images and regenerate every affected committed artifact.
- [x] Run module, generator, renderer, lint, freshness and mutation gates against the selected toolchains.
- [x] Inspect rendered desktop and phone reports and affected renderer images; disposition findings.
- [x] Record exact source, artifact and test identities and downstream consequences (see "Final combined build").

Historical viewer evidence remains tied to its recorded original toolchain; the final-build evidence is recorded under "Final combined build" below.

## Zig removal

Zig support is retired by explicit maintainer decision; there are no consumers.
The compiler and plugin installs, generation leg, release advertisements and
archive enrollment, version probes and 22 generated binding files are removed.
Protobuf ZigZag integer encoding and unrelated vendored text are not language
support and remain.

- [x] Inventory and remove executable and advertised Zig support.
- [x] Remove the tracked Zig output files.
- [x] Verify generation enrollment and orphan checks.
- [x] Regenerate all ten remaining output legs; all ten reproduce without unallowlisted orphans.
- [x] Compile the regenerated bindings where the generator image carries a compiler (see "Final combined build"); C++ and Kotlin have no compiler in that image and compile only at a consumer.

## Initial verification

The stock clj-kondo 2026.08.04 and Malli 0.20.3 regression run executed two
tests and 17 assertions: five assertion failures, zero errors. It reproduces
false constant-condition findings for nullable map returns, false required-key
findings for open map returns, and missed type errors for optional-key lookup.
The focused regression lives in `tools/lint/kondo/test/lint_gate/kondo_types_test.clj`.
The patched artifact passes the same two tests and 17 assertions, with zero
failures or errors. Its source patch, upstream identity, reproducible builder,
license and artifact-integrity negative controls live in
[`tools/lint/kondo`](../tools/lint/kondo/README.md). No warning suppression is used.
The source-only patched JAR SHA-256 is
`750eb891710f52522a836acb6743967ff59c09d1d249e8f0556e837381cde2be`.
The expanded suite now passes three tests and 22 assertions. Reverting only
nullable-union absorption in a scratch JAR fails the new nil-preservation
assertion, with 21 passing controls and zero errors. The preceding 20-assertion
suite did not detect that mutation; it is recorded as a coverage gap repaired
by the additional test, not as a successful mutation kill. The final campaign
reverts eight separate behaviors in disposable source JARs: nullable truthiness,
nullable union absorption, nullable lookup, map openness, map nilability,
optional value types, optional nil alternatives and direct keyword lookup.
All eight fail attributed assertions with passing neighboring controls and
zero errors; three tests and 22 assertions pass before and after. There are
zero surviving eligible faults in this patch scope. The upgraded Python also
rebuilds the exact declared JAR bytes and passes both integrity canaries.

The first isolated Linux amd64 toolchain build completed with Ubuntu 26.04,
GraalVM 25.4.4.1.1, Node 26.11.1, npm 12.2.0, Maven 3.10.0, Clojure CLI
1.12.6.1673, protoc 36.2, Go 1.27.1, Rust 1.99.0, and WASI SDK 34.
The isolated Linux amd64 base image identity is
`sha256:a2961481083312999c5e0ec5800dff620a7f75aee9840071d6af194b8d008f56`.
All ten language/descriptor generation legs completed. The orphan check
independently regenerated all ten, with zero unallowlisted orphans, one explicit
allowlist entry and one declared out-of-scope directory. Broader behavioral
acceptance remains open.

The coordinated API source-inventory adapter now declares `borkdude/edamame`
1.6.44 directly, verified against its [publisher metadata](https://repo.clojars.org/borkdude/edamame/maven-metadata.xml).
Its parser semantics and complete export reconciliation are a separate acceptance
requirement owned by the API implementation.

## Viewer dependency checkpoint

The rebuilt viewer passed 329 tests and 19,028 assertions, with zero failures or
errors. This precedes the coordinated API source-inventory addition and is not
its acceptance result. The intermediate browser bundle SHA-256 is
`d5696701e769b8e2b4c560f08166b34472a0c5036ab8c18c1318d52304769042`.

Playwright 1.64.0 ran 24 passing fixture/device cases each in Chromium
156.0.8078.4 and WebKit 27.2. The public synthetic captures include desktop and
iPhone 13 portrait/landscape emulation. All 57 changed images and one current
desktop representative were actually opened and inspected; 90 further images
were verified byte-for-byte against previously inspected captures. That
checkpoint's per-image record was overwritten by the final build's
[manifest](../tools/gate-graph/docs/viewer-upgrade-visual-manifest.json), which
retains each image's actual-opening record inline as `priorReview`.
No new visual defect was found. WebKit's separate rotation probe still fails
layout-width checks; fixed orientations are accepted, rotation is not. These
are emulated browsers, not physical-device evidence.
The 79-assertion API parity corpus also matches byte-for-byte across JVM
Clojure, unoptimized ClojureScript and advanced ClojureScript under the new
dependencies (canonical output SHA-256
`722db54b7a61d4ad4c3e9f4000f2a2ad3d9263631f3ed437cdcd4a11dcded742`).

## Renderer dependency checkpoint

WASI SDK 34 builds the C/C++ renderer after removing an unused DPI cache and
explicitly including the standard allocation declarations for the existing
ThorVG compressor translation unit. Vendored source is unchanged. Wasmtime 49
uses the new read-only filesystem permission type; PNG 0.18 requires handling
an unrepresentable output-buffer size. All Rust test binaries now compile.

The first renderer battery reached 478 devcards tests and 5,057 assertions;
three assertions failed because the old WASI `fd_write` fixture now returns
`EFAULT` rather than trapping. The upstream published GraalWasm 25.4.4.1.1
source validates the iovec and returns that error. The diagnostic-frame test
now provokes an out-of-bounds `fd_fdstat_get` result write, preserving its
original purpose. Its focused control passes four assertions; a scratch
mutation disabling internal frames fails exactly the builtin-frame assertion
while the caller-frame control passes (three passes, one failure, zero errors).
The next battery passes the 478 devcards tests and stops at 16 changed golden
entries: four cards across four manifests. The corresponding gallery rebuild
completes its 818-path audit with no missing or orphaned entries and changes
12 JPEGs in the label and target-overlay units.

The batched visual reviewer opened all 78 current JPEGs across the complete
label and target-overlay units, plus all 12 corresponding previous images,
and inspected 78 fresh DOM dumps. No new visual defect was found. One
pre-existing faint vanilla outline finding remains uncertain and is deferred
to the instrument tracked in
[RENDER-CONTRAST-001](RENDERER-INSTRUMENT-WORK.md#render-contrast-001--resolved-non-text-boundary-contrast).
This is not a readability pass or a waiver. The other 23 units (714 renders),
hardware conditions and interactive state transitions were outside this review.
Raw captures and dumps remain in the checkout's ignored evidence store.
Remaining renderer lanes and final golden/gallery acceptance remain open.

The final isolated Linux amd64 toolchain image also builds successfully, with
Python 3.14.8, pip 26.2.1, NumPy 2.5.3, SciPy 1.18.1, Pillow 12.3.0,
actionlint 1.7.12 and Babashka 1.13.225. Its image identity is
`sha256:34360e7c5c554f4862be083f0134f4003a942c5a608625c28978d15f2d923001`.
Combined API, renderer and artifact validation against that image remains open.

## Git, native and workflow pins

Git dependencies (`:git/sha` / `:git/tag` coordinates across the tracked
`deps.edn` files — `io.github.cognitect-labs/test-runner` and
`com.appsflyer/pronto`) are unchanged by this upgrade and were not re-pinned.
Native toolchains have exactly one home, `Dockerfile.base`; the two image
identities above are what those pins built. Every GitHub Actions `uses:` across
`.github/workflows/` and `.github/actions/` is pinned to an exact release tag
rather than a floating major. The runner-side JDK for the devcards and renderer
fixture jobs is the IMAGE's JDK byte for byte: each job resolves
`GRAALVM_VERSION` / `GRAALVM_JDK_VERSION` from `Dockerfile.base`, downloads that
release asset, verifies the release's own sha256 and installs it through
`actions/setup-java`'s `jdkfile` distribution — `graalvm/setup-graalvm`'s
composed download URL does not exist for this release (HTTP 404 against the
`jdk-<v>/…` path it builds; 302 for the `graal-<version>` tag the image uses). The wasmtime harness (`renderer/wasm_harness/Cargo.toml`)
and the Rust generation leg (`prost` in `generate-protos.sh`) carry exact crate
versions. The browser acceptance runtime is pinned by
`tools/gate-graph/browser/package.json` and ran in
`mcr.microsoft.com/playwright:v1.64.0-noble`
(`sha256:06a9939e57531807f8d5fd76ce44b53165ffb7d7501d87ab10e285c20b1e971f`).

## Final combined build

Source baseline `0a052cdda212fa7b3d65324320f07c2ca951904a` plus this change
set. Viewer bundle `tools/gate-graph/resources/gate/viewer/main.js` SHA-256
`145767e3caa3954734aefcac3468eddc979f9734d97589eda8226d8e95cb8935`; its asset
manifest SHA-256 `4b3ca026a210ae054f5aba8c918a42453058fdf3b668f1ab2172b1cb30802c7c`;
`renderer/output/controls.wasm` SHA-256
`142c3443c25ee447b56953cc546a738794974bf1c3bbec7ff7211379abb17f63`, byte-identical
before and after the C diagnostic cleanup.

Gates run against the final images, all green:

- `make -f lint.mk lint` (the full aggregate) in the base image. Two guards
  landed with it. The new `tools/lint/bin/clj-kondo` wrapper (this change
  introduces it; nothing like it was tracked before) must run the linter from
  the CALLER's directory: a draft that changed to the repository root first made
  every fixture suite's relative `--lint src` analyse nothing, so every
  structural canary refused with CANNOT RUN. `tools/lint/kondo/test-wrapper-cwd.sh`
  guards that — red on the draft, green on the shipped wrapper, with an
  absolute-path control run first so a broken JVM reads as ERROR rather than as
  the clause's FAIL. `tools/leg_strictness_test.sh` hard-coded the retired
  eleven-leg floor; it now pairs each `<LEG>_SCRIPT='` declaration with the
  `set -e` on its next line, so a stray `set -e` elsewhere cannot mask a dropped
  one, and keeps a synthetic canary for the dropped and the masked case.
  `tools/leg_strictness_test.sh` hard-coded the retired eleven-leg floor; it now
  derives the floor from the `<LEG>_SCRIPT='` payload declarations, and dropping
  one payload's `set -e` in a scratch copy produces a FAIL naming that clause.
- The docker-gated pre-push lanes (`lint-c-tidy-test lint-c-tidy lint-python
  gate-trace-test gate-graph-test`), `kondo-regression` and `wire-contract`.
- Host-only: `tools/ts_validated_repro.sh --image <generator image>` (every
  committed validated-TypeScript file byte-identical) and its canary;
  `tools/image_pin_check.sh --image <generator image>` and its canary; the go-leg
  reproduction and orphan scan recorded above.
- `make -f renderer.mk check-renderer` in the base image, including the
  devcards, renderer-generator and coverage-matrix lanes, with the goldens
  verified fresh before the mint.
- The documentation-tool suite in the protodoc image after the golden refresh.

Regenerated bindings compiled in the final generator image, one verdict per leg:
C compiles every nanopb unit under `gcc -std=c11 -Wall -Werror`; both Go modules
pass `go vet`; every Python `*_pb2` module imports; every JSON descriptor parses;
the plain TypeScript output type-checks with zero errors given its declared
`long` dependency; the validated TypeScript output reports only the `TS2307`
for the consumer-supplied `./buf/validate/validate_pb` module, and that error
set is identical to the baseline's; Rust is compiled by the generation leg's own
`cargo build` under `pipefail`; Java is compiled by the renderer battery's
`construct-bindings` lane. C++ and Kotlin have no compiler in the generator
image and are compiled only by consumers.

Browser acceptance on the final bundle: 24 cases per engine, 148 captures, of
which 145 are byte-identical to captures already opened and accepted and three
were opened and pixel-diffed against their prior capture — a ≤19/255 tone shift
in the evidence highlight band of one Chromium phone capture, and WebKit now
painting the emphasised-edge outline Chromium already painted on the two
`branch` detail captures. No layout or text changed. The per-image record is the
[visual manifest](../tools/gate-graph/docs/viewer-upgrade-visual-manifest.json).

Not accepted, by name: WebKit active rotation (its probe still fails the
layout-width check); C++ and Kotlin compilation; the renderer finding tracked
as RENDER-CONTRAST-001; and the browser acceptance suite itself, which no gate
runs (`tools/gate-graph/README.md`).
