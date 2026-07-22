---
description: The renderer-gen fixture/codegen seam — the relocated asgard.*/uigen/lvgl_codegen closure, why its namespace names are provenance not private leaks, and what a real leak looks like there. Loads when editing tools/renderer-gen.
paths:
  - "tools/renderer-gen/**"
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

## Load-bearing subset vs. inert weight
Only a small generic subset is reachable from the battery — the manifest joins,
the `cmd.Root` pronto wrap, and the Malli primitives. The video/log schemas, the
state-decode path, and the runtime command backends are inert in this tree,
carried by the relocate-whole decision and retired when the seam converges onto
`tools/devcards`' public UiAst builder. `renderer.md` covers the C interpreter +
battery contract this seam feeds.
