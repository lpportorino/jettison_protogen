---
description: The renderer-gen fixture/codegen seam — the relocated asgard.*/uigen/lvgl_codegen closure, why its namespace names are provenance not private leaks, and what a real leak looks like there. Loads when editing tools/renderer-gen.
paths:
  - "tools/renderer-gen/**"
  # scratchcard.input is the seam's second in-process caller of
  # lvgl-codegen.core/process-screen — see "A second caller, outside the CLI
  # aliases" below. It depends on this seam's CWD contract exactly as
  # fixtures.clj and -main do, so an editor of it owes this rule too.
  - "tools/scratchcard/src/scratchcard/input.clj"
---
<!-- LOAD-TEST: renderer-gen -->

# renderer-gen — the fixture/codegen seam

`tools/renderer-gen/` turns EDN screens + this repo's proto manifests into the
fixtures the renderer battery consumes — the `:codegen` / `:fixtures` /
`:morph-fixtures` aliases in `deps.edn`, driven by `renderer.mk`. Its
`src/{asgard,uigen,lvgl_codegen}` namespaces are a relocated copy of the same
closure a private source repo carries, kept in sync so their pins and behaviour
match. Provenance and the pin rationale live in `tools/renderer-gen/deps.edn`.

## The `asgard.*` name is provenance, not a leak
The `asgard.*` namespace names are lineage artifacts of that shared closure —
NOT a private backend service that leaked into this public repo. The code is
generic proto↔EDN + manifest machinery over THIS repo's OWN public schema
(`cmd.JonSharedCmd$Root`, `ser.JonGUIState`, the `output/manifests` +
`proto-db.edn` this repo generates). Do NOT re-flag the namespace name, the
directory, or a bare "asgard" (it also names the sanctioned public UI theme) as
a private reference — that mistake has cost review cycles before.

## What a real leak looks like here
A genuine leak is a PRIVATE-INFRASTRUCTURE name in prose or a value — a private
consumer app, a transport/broker, or a storage component from the private stack.
Those belong nowhere in this public tree. They sit only in comments, docstrings,
and dead schemas — never in generated `output/**` or `controls.wasm` (the seam's
private-topology surface is inert here), so a plain source edit is the whole fix:
genericize the name in place, no proto change or regen. Sweep the tree before a
push; this seam is the one place strays recur.

NOT leaks — do NOT "scrub" these. `asgard` is also the sanctioned public UI theme
name, and `cmd_server` is named by this repo's OWN public `proto/ui/ui_input.proto`
and its generated docs. A term this repo publishes itself cannot be a leak, and
genericizing it only makes the comment less accurate than the schema it describes.

## Two cited doc trees are NOT VENDORED HERE — do not hunt for them

Many files in this seam carry a header citing `docs/lvgl-factory/NN-*.md`, and a
handful cite `docs/ui-nodes/README`. **Neither directory exists in this
repository**, and neither is going to: they are the upstream design tree this
closure was relocated from, and the citations are PROVENANCE for where a
decision was made, not a pointer you can follow here.

Verified: `docs/lvgl-factory` and `docs/ui-nodes` are both absent, cited from
roughly twenty files between them.

Left in place deliberately rather than stripped — an external design document we
do not control is exactly the citation `.claude/rules/claude-md-policy.md`
sanctions, provided the reader is told where it lives. This paragraph is that
telling. What you must NOT do is treat one as a live path: do not `ls` for it,
do not conclude the file is stale because the path 404s, and do not add a new
citation in that shape without saying, at the citation, that it is external.

## Load-bearing subset vs. inert weight
Only a small generic subset is reachable from the battery — the manifest joins,
the `cmd.Root` pronto wrap, and the Malli primitives. The video/log schemas, the
state-decode path, and the runtime command backends are inert in this tree,
carried by the relocate-whole decision and retired when the seam converges onto
`tools/devcards`' public UiAst builder. `renderer.md` covers the C interpreter +
battery contract this seam feeds.

## A second caller, outside the CLI aliases

`tools/scratchcard` reaches `lvgl-codegen.core/process-screen` (plus
`load-ui-defs` / `validate-class-defs!` / `component/load-components`)
in-process, not through `:codegen` / `:fixtures` / `:morph-fixtures` — its
`deps.edn` takes `protogen/renderer-gen` as a `:local/root` edge and calls the
namespace directly. It is a caller of the SAME reachable subset above, over an
author-supplied screen rather than the committed `renderer/edn/screens/`
corpus, never a wider one. Two invocation shapes reach it: `scratchcard-lane`,
inside `check-renderer`, renders the shipped example; the live per-fork daemon,
gated by nothing, renders whatever an author points it at
(`.claude/rules/scratch-devcard.md` covers that side).

This is why the `:codegen` alias's CWD contract — `edn/` + `assets/` resolved
against the process's own working directory — is now load-bearing for two
invocation shapes rather than one. A long-lived daemon has no meaningful
per-request CWD, so this caller cannot inherit it: `scratchcard.input` resolves
every path absolutely instead. Anyone changing what this seam assumes about its
working directory now has every in-process call site to satisfy — `core.clj`'s
own `-main`, `fixtures.clj`, and `scratchcard.input` — not the first two alone.
