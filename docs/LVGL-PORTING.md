# Porting the renderer to LVGL 9.6

This guide covers the strict migration of the renderer from LVGL 9.5 to
[the official 9.6.0 release](https://github.com/lvgl/lvgl/releases/tag/v9.6.0).
The protocol numbers, `controls_*` ABI, framebuffer format and release compiler
warning policy do not change. A successful compile is only the first check:
configuration, reactive lifetime, generated bindings, stock-theme parity and
consumer packages all cross this boundary.

## Source ownership and reproducibility

Do the work in a claimed, remote-stripped protogen fork using
[`tools/claude/forks.sh`](../tools/claude/forks.sh). Read the fork brief and the
renderer, regression-test-first and UI review rules before editing. Preserve
local changes and never use a consumer's tracked submodule as scratch space.
This repository owns the interpreter and the canonical projections; consumers
adopt its accepted revision and regenerate their own packages.

The runtime vendor surface includes `src/`, the **`include/lvgl/`** public
header tree, the top-level build/license files and the selected widget demo.
Copy from the complete official source checkout, preserve the license, and
update both vendor provenance manifests (`renderer/lvgl/.ported-from.edn` is the
one home of the upstream revision). Do not replace just `src/`: 9.6 leaves
compatibility shims there that produce header deprecation warnings.

The release build uses wasi-sdk and the ordinary strict application flags. Run
inside the pinned development image, with GraalVM selected for GraalWasm lanes.
`JAVA_HOME`, the Java selected by Clojure and `java` on `PATH` must agree; the
native `graal-check` deliberately refuses the stock JDK. A separate container
and an isolated Cargo target keep another checkout's artifacts from becoming the
test subject. Several scripts use the conventional
`renderer/wasm_harness/target/release` path; preserve it if Cargo's target
directory is relocated. Do not share package fingerprints between two source
roots without rebuilding: the embedded `CARGO_MANIFEST_DIR` decides which WASM
and fixtures the test binary opens.

## Configuration and headers

Apply the configuration changes to both `renderer/lv_conf.h` and
`renderer/config/dev/lv_conf.h`:

- Replace `LV_COLOR_DEPTH 32` with
  `LV_COLOR_FORMAT_DEFAULT LV_COLOR_FORMAT_XRGB8888`. This is the value
  upstream's compatibility mapping assigns to the old setting. The overlay
  display separately and explicitly selects ARGB8888; changing the default to
  ARGB8888 would be a different configuration decision.
- Enable `LV_USE_THORVG` as well as `LV_USE_THORVG_INTERNAL`, and remove the
  external-selector setting. SVG and vector rendering still need real tests.
- Enable object properties for the three wire flag bits that have no dedicated
  setter. Property-name lookup is unnecessary and stays off.
- Use public headers under `lvgl/include/lvgl/`. Genuinely private headers stay
  under `src/` only at the diagnostic and native-projection seams. The SVG
  decoder's private header lives under `src/image/`.

Do not silence `-Wdeprecated-declarations`, `#warning` or runtime logs to obtain
an upgrade pass. Static first-party flag operations use the dedicated setters
and predicates. Dynamic wire masks go through `apply_wire_flags` in
`renderer/src/renderer.c`, which dispatches every ordinary bit to its setter
(flex-new-track included), the four user flags to `lv_obj_set_user_flag`, and
the three reserved layout/widget bits to their object properties — 9.6 offers
no public setter for those, and upstream logs its own deprecation diagnostic
when one is requested, so ordinary authored flags never reach that path. The
undeclared high bit is ignored, and clear flags are applied after set flags.

## Reactive subjects and teardown

LVGL 9.6 allocates subjects. Registry entries and the two global subjects hold
`lv_subject_t *`, created with `lv_subject_create` and released with
`lv_subject_delete`. A failed allocation must produce a failed load or init
result, never a half-live registry entry, and a declaration increments the
registry count only after initialization succeeds.

Registry string storage stays bounded and registry-owned. Install both buffers
with `lv_subject_set_string_buffer_static`, then set the initial value with
`lv_subject_set_string`; the order matters because buffer installation clears
the buffers. Updates use the same setter. Delete widgets before subjects so
their object observers detach while the subjects are live. Full reload resets
every entry; full destruction also deletes and nulls the global subjects.

9.6 removes the conditional-bind helpers, so all six comparison operators ride
the one comparison observer (`compare_binding_observer_cb`). Registration
performs the initial callback; later updates keep direct polarity for
checked/pending and inverted polarity for visibility/enabled. Reserve the
cleanup event descriptor before subscribing: if that allocation fails, free the
data and fail the load before any observer exists; if observer registration
fails, remove exactly that descriptor before freeing the data.
`lv_observer_delete` is not permitted for widget-bound observers by the public
API, and removing all of a widget's observers would erase unrelated bindings.
Tests exercise initial values, below/equal/above transitions, empty strings and
repeated reloads; those behavior checks are distinct from the native lifecycle
probe, which observes calls at the allocation and deletion boundary.

## Native values and protocol numbers

The live extractor discovers negative native property enum values. They are
legitimate signed C values, not negative protocol allocations: keep them in
`:resolved-int`. Only direct-cast enum families provisionally reuse that number;
other families use zero until the assign-once registry supplies their wire
number. Do not weaken the nonnegative wire-number schema or filter unfamiliar
extracted enums away to make validation pass.

`construct-bindings` still requires every consumed typedef and compares both
canonical generated projections, and a 9.6 run must leave the wire bindings
unchanged. The independent Clojure header oracle and the circle-sentinel test
read the public header tree. The compiled child-theme emitter checks the
`LV_CHECK_ARG` accessor guards and models their null-class behavior before
executing `theme.c`.

The frozen demo recreation displays the upstream version string. That is
visible content: the native-demo comparisons differ at the heading until the EDN
label in `renderer/edn/screens/demo_widgets.edn` matches. The reference demo
itself stays upstream source.

## Theme and fixture sizing

The vanilla child theme must reproduce stock 9.6. Its button shadow uses one
3-DPX offset, matching upstream; a nested 4-DPX formula fails exact parity.
Regenerate `tools/renderer-gen/generated/theme-style-groups.json` through its
compiled emitter after changing the theme or any of its declared inputs, never
by editing the JSON.

9.6 reports intrinsic widths that the positive fixture sizes must honour.
Measured across all three families with the sizing campaign below, the dropdown
options need at least 119px, so the positive small/medium/large tiers use
120/128/200px; the small vertical scale fails through 35px and fits at 36px, and
its wrapper grows to 68px to keep the 16px padding. Update every state, wrapper
and declared size together. The geometry judge and its axes stay fully armed.

The 48px stock roller is an intentional negative fixture: its selected band can
lack the glyph that fits under the asgard theme. Do not enlarge it or turn it
into a positive fixture when migrating goldens; its diagnostic and its
positive-width experiment are separate evidence.

## Regeneration order

1. Vendor the release and update both provenance manifests.
2. Apply the configuration and header changes; build `wasm` and `reference`
   under the strict flags with zero warnings.
3. Run `construct-bindings` and confirm the committed wire projections are
   unchanged.
4. Regenerate the compiled theme projection, then mint fixtures and goldens only
   once the real-render fixture lane reports zero non-golden findings.
5. Regenerate the generated documents whose sources moved
   (`docs/PROVEN-PAIRS.md` through the `:proven-pairs` alias in
   `tools/devcards/deps.edn`).
6. Review fresh renders against the UI standard before accepting new raw hashes.

Raw framebuffer and JPEG differences are separate facts: a frame can change only
in pixels that are transparent in both renders, so JPEG equality cannot
establish raw golden equality. Review visible changes and deterministic
invariants before accepting raw hashes.

## Validation

Run from the repository root in the pinned image:

```sh
export JAVA_CMD="$GRAALVM_HOME/bin/java"
export PATH="$GRAALVM_HOME/bin:$PATH"
make -C renderer -f wasm.mk -j8 all reference
make -f renderer.mk construct-bindings clj-schema-test
make -f renderer.mk check-renderer
make -f lint.mk lint-clj fmt-clj fmt-c lint-sh
make -f lint.mk lint-python
```

The battery owns generated-projection freshness, schema coverage, native
rendering, the independent coverage matrix, the demo tabs, reload/morph behavior
and the wire/decode contracts. Do not rebuild the Java bindings while another
renderer process is reading the same classes directory.

`lvgl-api-selftest` and `lvgl-global-subject-selftest` are battery lanes and
also run individually in `renderer.yml`. They compile the real interpreter
translation units against LVGL boundary doubles, with real nanopb decoding
where applicable; [the probe guide](../renderer/tools/lvgl-api-selftest.md)
states their populations and canaries. Native `cc -std=c2x` uses the image's
build-essential toolchain; its ignored WASM import attributes and unreachable
32-bit pointer casts are native-probe exceptions only, and production WASM flags
stay strict. The global probe calls the real `controls_init` and
`controls_destroy` entry points, so omitting an allocation or cleanup call at
its real call site is observable. Boundary doubles observe interpreter call
sites; they never substitute for the real WASM render lanes.

`lint-python` runs pinned Ruff over the enrolled Python drivers and the
wire-contract checker; `tools/lint/ruff.sh` pins the release archives and the
extracted executables by digest, and the image prewarms its cache, so it runs in
the image (and from the pre-push hook through `tools/uber.sh`), with a read-only
checkout and no network.

## Manual mutation campaigns

At the epic boundary, run the scoped manual campaigns required by
[the mutation rule](../.claude/rules/mutation-testing.md) against the final
renderer and WASM bytes; a receipt for an older renderer cannot establish a new
artifact:

```sh
python3 renderer/tools/lvgl-api-selftest.py --mutations --out <fresh-dir>
python3 renderer/tools/lvgl-global-subject-selftest.py --mutations --out <fresh-dir>
python3 renderer/tools/lvgl-reactive-mutations.py --out <fresh-dir> --jobs 1
python3 renderer/tools/renderer-gen-schema-mutations.py --out <fresh-dir>
(cd tools/devcards && clojure -M:bindings dev/native_size_probe.clj)
bash renderer/dev/pending_when_mutation_proof.sh
```

The reactive campaign ([its guide](../renderer/tools/lvgl-reactive-mutations.md))
mutates the comparison predicates, the shared polarity, each state destination,
integer and string initial values, string updates and buffer capacity, and flag
clear ordering in real WASM. The sizing campaign renders the positive cards and
deliberately undersized twins. These are selected first-party faults, not a
claim to mutate upstream LVGL or every possible program change.
The schema campaign
([its guide](../renderer/tools/renderer-gen-schema-mutations.md)) mutates the
generator's printer comparator and value domain, the palette scanner's
instrumented root and pretty's coverage enrolment, against the `:test` suite.

## Consumer adoption

Adopt the accepted revision through the consumer's ordinary submodule workflow.
Regenerate bindings and run the consumer's source-mirror gates before rebuilding
its bundled WASM package. A consumer that packages the interpreter with its own
screen protobufs and assets re-mints and verifies its own screen corpus and
viewport lanes, then runs its ordinary application, mirror, lint and freshness
gates. A new renderer can change consumer pixels even when the protocol bindings
are unchanged: inspect every changed consumer image and keep the consumer's own
baseline. Prove freshness against a reviewed baseline rather than disabling a
gate because regenerated tracked files are dirty, and record the adopted
renderer/package hashes with the source revision.

The 9.6 stock theme gives the dropdown a 14px column gap; a consumer whose
layout depends on a narrower gap sets it explicitly. Native consumers rebuild
the renderer at their real include and package paths with warnings as errors
and locked dependencies; a software-container pass makes no claim about GPU,
hardware, physical display readability or native ARM execution.

Dependency pins move with the release and stay in lockstep: malli in the
renderer-gen, devcards and protocol-gen `deps.edn` files (the protodoc tool
pins its own, older malli and is not part of the renderer's lockstep),
clj-kondo in `.github/workflows/lint.yml`. An analyzer patch reported by a
consumer is only adopted here once its trigger is reproduced here.

## Known gaps

- `proto/ui/ui_ast.proto` still describes EQ/NOT_EQ state bindings as using the
  removed `lv_obj_bind_state_if_*` helpers, and the `obj_flags` comment calls
  the field a direct cast. Correcting those comments is a proto change and
  regenerates every binding, so it lands separately.
- The rendered evidence is bounded gallery and behavior coverage, not
  certification of arbitrary layouts, animation, backdrops, contrast or
  physical-display readability.
