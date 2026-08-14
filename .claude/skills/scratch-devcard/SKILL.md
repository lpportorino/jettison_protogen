---
name: scratch-devcard
description: Render an LVGL/ui_ast screen and see what it actually looks like and where it is defective. Use when iterating on a screen or widget layout, when asked to try/preview/show a UI change, when a layout looks wrong and you need the DOM behind it, when comparing two versions of a screen, or when reading a dump_tree. Drives a warm per-fork render daemon that renders 3 theme families x light/dark x every resolution in about a second, runs this repo's own quality lanes over every cell, and writes a gitignored run directory with PNGs, dump_tree JSON, stats, findings and a manifest that a later run can be diffed against. Carries the dump-key absence table, where an absent key means a DEFAULT rather than "no information".
allowed-tools: Bash, Read, Glob, Grep
---

# scratch-devcard — write a screen, see it, find out what is wrong with it

One command. It blocks, then returns paths and counts.

```bash
tools/scratchcard/bin/scratchcard regenerate --file <screen.edn>
```

Warm, that is around a second for the full default matrix. The first call after a
cold boot pays ~10-30s for the container and JVM; every call after is warm.

## Build the two artifacts FIRST — a fresh clone has neither

The daemon needs a compiled Java proto classpath and a built wasm, and neither is
committed. Build both once, in the toolchain container:

```bash
tools/uber.sh 'make -f renderer.mk proto-classes'   # tools/renderer-gen/target/proto-classes
tools/uber.sh 'make -f renderer.mk wasm'            # renderer/output/controls.wasm
```

**Nothing checks them before the call**, so each absence reaches you as a symptom
that does not name what is missing — and the two arrive in this order, because
the first stops the daemon booting at all and the second only stops it rendering:

| missing | what you actually get |
|---|---|
| `tools/renderer-gen/target/proto-classes` | `starting warm daemon`, then a block until the client's spawn deadline (`wait-up 180000` in `bin/scratchcard.bb`) expires — three minutes of nothing. The refusal then points at `docker logs <container>`, which answers `No such container`: the daemon is spawned `--rm`, so the one hint it offers has already been reaped. The boot died on `ClassNotFoundException: pronto.ProtoMap`. |
| `renderer/output/controls.wasm` | the daemon boots and answers in seconds, and EVERY cell fails — `failed` equals the cell count, each with `RENDER_FAILED` and `controls.wasm (No such file or directory)`. |

Neither is a scratchcard bug to work around. `proto-classes` compiles
`output/java` plus pronto's Java helpers; `wasm` is the reference interpreter
this tool renders through. Both are `renderer.mk` targets the render lanes
already depend on.

**On such a run `findings.clean?` is `null`, not `true`.** Findings are lanes
over SUCCESSFUL renders, so a matrix in which everything failed yields the same
empty vector a flawless screen yields; `null` means the question was not
answered, and `findings.judged-cells` beside it is the denominator that says
why. Read `ok` and `failed` too — a screen is sound only when cells were
actually judged and none of them complained.

## The workflow

1. Copy `tools/scratchcard/example/hello.edn` and edit it.
2. `regenerate`. Read the returned JSON: `ok`, `dir`, `run`, `cells`,
   `failed`, `findings`. `run` is the run's numeric id — the `N` the diff
   command below takes.
3. **Read `<dir>/report.md` FIRST.** The verdict is the line under the title.
4. Look at `<dir>/renders/<cell>.png` for the cells you care about.
5. Fix, regenerate, and compare against the previous run.

Other commands: `status`, `up`, `stop`, `restart`, `ping`.
`--res 800x480,390x844` narrows the matrix; `--card NAME` names the archive.

## The dialect — renderer-gen, NOT devcards

```clojure
{:type :screen
 :events {}
 :subjects {:bp {:type :int} :theme_dark {:type :int}}
 :tree {:tag :lv_obj
        :class "flex-col w-content h-content"
        :children [{:tag :lv_label :class "font-font-heading text-fg-0" :text "hi"}
                   {:tag :lv_slider :class "w-120"
                    :slider_props {:min_value 0 :max_value 100 :value 65}}]}}
```

`:tag :lv_slider`, not `:type :WIDGET_SLIDER`. Two vocabularies exist for the
same widgets; this pipeline speaks the first and will refuse the second.

**Give containers a layout.** A root with no `flex-col`/`flex-row` lays its
children out at default positions and overflows, which the geometry lanes
report as `:clipped` on every text node plus `:scrollable_overflow` on the
root.

The authoring pipeline validates at every stage before a byte is emitted, and
each stage has its own error code: `INPUT_SCHEMA_INVALID`,
`INPUT_COMPONENT_UNRESOLVED`, `INPUT_SEMANTICS_INVALID`, `INPUT_ASSET_MISSING`,
`INPUT_EXPAND_FAILED`, `INPUT_FONT_UNRESOLVED`, `INPUT_CAPACITY_EXCEEDED`,
`INPUT_IR_INVALID`. The code tells you WHICH stage rejected the screen.

## What a run directory holds

```
manifest.edn   the machine-readable index — read this to diff two runs
report.md      the digest: verdict first, then findings, renders, and what was NOT judged
findings.edn   the FULL findings vector
input/         a verbatim copy of the screen, plus the .pb actually rendered
renders/<cell>.png and <cell>.dump.json
```

`<cell>` is `<family>-<mode>-<w>x<h>`, e.g. `asgard-dark-800x480`.

## Diffing two runs — attribute the change before hunting

```bash
tools/scratchcard/bin/scratchcard diff [--card C] [--from previous|latest|N] [--to ...]
```

Defaults to previous-vs-latest — "what did my last edit change". **`--card`
defaults to the literal `hello`, never to what you last regenerated**, and the
common way to trip this is step 1 above rather than an explicit `--card`:
rendering `--file mycopy.edn` derives the slug from the FILENAME (`mycopy`), so
a bare `diff` afterwards silently compares the shipped example instead. Pass
`--card` whatever your screen file is named. It returns
`:causes`, and that is the field to read first:

| cause | means |
|---|---|
| `:input` | the screen file differs |
| `:renderer` | a different wasm, or a different protogen sha |
| `:judgement` | different producers, thresholds, or class table |
| `:unexplained` | **pixels moved and nothing upstream did** |

`:causes` is a SET — two things can change at once, and picking one to report
would be a guess presented as a finding. `:unexplained` is the most
interesting answer in the vocabulary: it means the renderer is not
deterministic under any input this archive can see.

`:judgement` is the one a naive diff omits, and omitting it makes a threshold
edit read as a pixel regression. A cell present in only one run appears under
`:only-in-to` rather than `:moved` — adding a resolution is a matrix change,
not a rendering regression.

## READING THE DUMP — absence is NOT neutral

This is the part that produces confident wrong answers. Several keys are
emitted only when they carry information, so an absent key means a DEFAULT, not
"no information":

| key | absent means |
|---|---|
| `clickable` | **CLICKABLE** — it is emitted only when the flag is CLEAR |
| `disabled` | enabled |
| `click_area` | **EQUAL to `coords`** — emitted only when it differs |
| `descend_gate` | **EQUAL to `coords`** — emitted only when OVERFLOW_VISIBLE grows it |
| `scroll_dirs` | `scrollable_overflow` did not fire — never "no axis" |
| `text` | only an EXACT `lv_label` emits it; `lv_roller_label` draws glyphs with `text` AND `text_clipped` both absent |
| `gesture_part` | **not a gesture affordance** — and a render is never mid-drag, so it is absent on EVERY card here, including cards whose surface would draw one |
| `paint_box` / `paint_bound` | **BOTH absent = the paint extent IS `coords`, exactly** — `lv_obj_get_ext_draw_size` is 0, so `refr_obj` clips the widget's drawing to its own box and no pixel escapes. One of the two is emitted, never both, and only when it differs from `coords` |

**`paint_bound` is the row to read twice, because it is the one key here whose
presence means IGNORANCE rather than a value.** `paint_box` is the extent the
widget really painted; `paint_bound` is a box the paint provably stays inside
and nothing finer, published where no exact resolver exists for that class. So
a bound is sound for proving a NEGATIVE — nothing outside it was drawn — and
unsound for asserting one, and reading it as an extent will over-report by a
lot: `lv_scale` asks for a blanket 100px (a bare literal in lv_scale.c) and
every `lv_label` for `font_h / 4`, neither of which describes any pixel either
one actually draws. Treat a bound as a question, not an answer.

**`text_wrapped` is the trap in the other direction.** A WRAP-mode label
reflowed onto more lines GROWS rather than clipping, so `text_clipped` (CLIP
mode only) and `text_truncated` (dot_begin) are both correctly absent while the
reader gets a mid-word break. It fires on every theme family.

## What the run does NOT tell you

The report names its declined producers every time — `:layers`, `:palette`,
`:border` — so silence is never coverage.

**It makes no claim about readability, contrast or legibility under any
lighting or panel condition.** Those are properties of a panel and an operator,
measured at a bench, not here. Do not infer one from a clean run.

A **VLM look at the PNGs is a different act** from these deterministic lanes.
Its findings are owed a disposition, never a pass/fail verdict.

## Per-fork isolation

Container, socket and lock are keyed by `sha256(repo-root)[0:16]`, and there
are **no TCP ports**. Several protogen clones run concurrently on one machine
without contending. `status` reports the socket, the workspace, when it
booted, the run count and disk usage; the container name rides its `logs`
hint.

Never create a global PATH symlink to `bin/scratchcard` — it would hardcode one
worktree's hash. Use a shell function instead:

```bash
scratchcard() { "$(git rev-parse --show-toplevel)/tools/scratchcard/bin/scratchcard" "$@"; }
```

## AT A CONSUMER

Skills resolve from the PROJECT ROOT and never from a submodule mount, so this
skill does not exist at a consumer until it is linked in:

```bash
ln -s ../../<pin>/.claude/skills/scratch-devcard .claude/skills/scratch-devcard
```

To drive the daemon from INSIDE a dev container, that container must
identity-mount the runtime dir:

```
-v $XDG_RUNTIME_DIR/protogen/<hash>:$XDG_RUNTIME_DIR/protogen/<hash>
```

Re-check both at every pin bump.
