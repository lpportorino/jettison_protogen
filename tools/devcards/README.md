# devcards — the public UI-contract proof venue

The devcard corpus runner: `tools/devcards/` beside the `renderer/`
interpreter tree, run by `make -f renderer.mk fixtures` locally (or
`fixtures-prebuilt` in CI, which reuses the battery's already-built
`controls.wasm`). The
charter lives in the repo's `CLAUDE.md` § "The reference interpreter + the
devcards proof".

## What this is

protogen generates the language bindings of `ui_ast.proto` and proves the
contract at the wire level (§9 golden byte vectors); devcards adds render-level
proof — **proof from three ends**:

1. **Schema end** — every fixture `.pb` validates against the schema.
2. **Pixel end** — the renderer (the repo's OWN `controls.wasm`, built by
   `renderer/wasm.mk`) renders each fixture to a raw RGBA framebuffer whose
   **hash** must match the committed golden manifest. Raw bytes, never
   encoded images — encoder-independent determinism: pinned wasm + pinned
   fixture + pinned tick count ⇒ bit-identical framebuffer.
3. **DOM end** — `dump_tree` invariants per card: no layout defect flag (the
   `defect-flags` set in `invariants.clj`); no zero-area node; no
   zero-VISIBLE-area node (`vis_px`); no unexpected host_command/report emissions.

## Committed vs transient artifacts

|                                       Artifact                                        |                       Status                       |
|---------------------------------------------------------------------------------------|----------------------------------------------------|
| fixture `.pb` + fixture source (EDN → public builder)                                 | committed                                          |
| golden manifest (per-card raw-framebuffer sha256 + the render protocol it was minted under) | committed                                          |
| **gallery JPEGs (high quality)** — the render set in `gallery.clj` `family-renders` (3 sheets: vanilla + asgard dark/light) | **committed** (online docs render from the repo)   |
| PNGs / raw dumps / diff overlays                                                      | transient (CI + local inspection only, gitignored) |

## The corpus (secret-free — §9's own rule, extended)

Generic widgets × state × size, kitchen-sink composites, and GENERIC meta-node
composition examples only. Device-specific meta nodes (DDE elements, camera
controls — proprietary interfaces) NEVER land here; private consumers run THIS
runner via their protogen pin against their own private fixture corpora.

## Adding a rule — the finding-producer registry

A consumer that finds a new class of interface defect gates on it HERE, through
the registry, rather than implementing the rule in its own repo (a quality rule
living in one consumer is a defect live in all the others — see `CLAUDE.md`
§"Consuming the UI standard").

| namespace | what it owns |
|---|---|
| `devcards.findings` | the producer registry, the shared per-card context, exemption application |
| `devcards.classify` | the consumer's widget table — `:interactive?` (mechanism) and `:role` (intent) |
| `devcards.lvgl-classes` | the STARTER table for the classes this renderer emits, plus `merge-consumer` |
| `devcards.geometry` | exact integer rect arithmetic on INCLUSIVE coords |
| `devcards.overlap` | no two pointer-taking elements share a pixel (opt-in) |
| `devcards.layers` | the layer contract — declared z vs observed stacking (opt-in) |

A producer is `{:id :fn :requires :thresholds}` and its `:fn` takes ONE context
map, never a bare tree — a rule such as the layer contract needs the tree, the
consumer's z DECLARATION and the compositor's proxy rects together, plus
ancestry. Read the `devcards.findings` docstring for the contract; it is the
authority, and the fields above are a routing table, not a copy of it.

Wiring a private corpus in:

```clojure
(findings/card-findings
  {:card-id     id
   :tree        tree
   ;; every input a producer READS must be supplied — there are no
   ;; defaults, because a defaulted input silently weakens the lane that
   ;; reads it. `false` and `[]` are claims; omitting a key throws.
   :emissions   emissions
   :host-proxy? false
   :caps        {:vis-px? true}
   :classes     (lvgl-classes/merge-consumer {:types {"fx_dock" {...}}})
   :declaration {:layers {12 {:z 10 :id "chrome"}}}   ; layers/producer only
   :proxy-rects []                                    ; layers/producer only
   :producers   (conj findings/builtin-producers
                      overlap/producer
                      layers/producer)
   ;; gap-px 0 is strict overlap (a SHARED pixel). Raising it to 1 also fires
   ;; on boxes that merely TOUCH, which on protogen's own corpus takes the lane
   ;; from 0 findings to 80 — none of them a hazard, all of them layout
   ;; abutting at 0px (UI-QUALITY-CONTRACTS §2.3 has the breakdown and the
   ;; mechanism). Measure your own corpus rather than porting that count.
   ;; Start at 0.
   :thresholds  {:overlap/gap-px 0 :layers/gap-px 0}})
```

`devcards.corpus/render-corpus` drives the screens; neither takes anything
protogen-specific, so nothing here needs patching to run a private corpus.

### Read-only probe

`clojure -M:bindings:class-census` (`dev/class_census.clj`) reports which LVGL
classes the renderer actually emits and what the overlap rule says about the
real corpus. It gates nothing — it exists so table and rule decisions are made
on output rather than on argument.

## Runner mechanism (`src/devcards/host.clj`)

GraalWasm, plain Maven deps — but GraalVM CE is REQUIRED, not merely preferred:
a stock JDK has no JVMCI/Graal compiler, so the polyglot host would interpret
the wasm, and the runner hard-fails rather than degrade silently to that.
ONE shared Engine + content-keyed Source cache (warm instantiation
~1-4ms), a FRESH Context per card (hermetic — no state bleed), the renderer's
four mandatory `env` imports captured, WASI assets preopen for fonts/icons, ABI
gate on start (`supported-abis`), raw-framebuffer read + dump_tree copy-out.
