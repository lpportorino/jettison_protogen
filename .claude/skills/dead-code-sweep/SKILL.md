---
name: dead-code-sweep
description: Find and remove unused code in protogen — Clojure vars and namespaces, C functions, whole seams. Use when asked to find or delete dead code, to clean up unused vars/functions, or to act on an unused-* lint finding. Carries why a zero-caller symbol here can be the consumer-visible ABI, the reference classes no analyser in this repo can follow, what detection actually exists, and the delete-then-run-the-battery discipline that keeps a deletion from shipping a breaking change to the fleet.
---

# dead-code-sweep — "nothing calls it" is not a verdict here

A sweep in an application repo reasons from a caller count. protogen is the
pinned upstream for a consumer fleet and is additive-first by contract
(`CLAUDE.md`), so the caller count answers the wrong question: **a symbol with
zero callers in this repository can be a published interface.** Deleting one is
a breaking change owed a lockstep fleet bump, not a tidy commit — and nothing
here detects it. The delete compiles, every gate stays green, and the failure
surfaces in a consumer that rebuilt from the pin, at a distance from the commit
that caused it.

So the detector — such as it is — produces a PROPOSAL. Five layers stand between
a proposal and a deletion, and any one of them vetoes.

## The reference classes no analyser in this repo can follow

Three of them. Each is a real, load-bearing reference that no compiler and no
call graph can see, so each is a way for a live symbol to look dead.

### 1. A wasm export is a LINKER FLAG on one side and a STRING on the other

`renderer/wasm.mk` roots every C entry point with a `-Wl,--export=controls_*`
line in `LDFLAGS`. `tools/devcards/src/devcards/host.clj` reaches them BY NAME
through its `call!` closure — `(call! "controls_init" w h)` — and not always as a
literal: `draw-palette-export` holds the name in a var, and `export?` probes the
instance for its presence before the call. One side is a link-time flag, the
other a runtime member lookup. Nothing joins the two.

Cross-check the export list against every string in the Clojure host and the
Rust harness:

    for n in $(sed -n 's/.*--export=\(controls_[a-z_]*\).*/\1/p' renderer/wasm.mk | sort -u); do
      if ! grep -rqF "$n" --include='*.clj' --include='*.rs' tools renderer/wasm_harness; then
        echo "no caller: $n"; fi
    done

Measured, that prints `controls_fb_width`, `controls_fb_height`,
`controls_fb_bpp`, `controls_get_dirty_rect` and `controls_get_dirty_rect_ptr`.
Each occurs only in `renderer/src/main.c` — its definition and that file's own
ABI comment — in its `wasm.mk` export line, and in `docs/INTERFACE-CONTRACTS.md`,
which documents them as the ABI a native embedder blits a frame through. **The
wire contract is the caller**, and it is the only caller there is going to be in
this tree.

**Know which of the two edits is the dangerous one.** Deleting the FUNCTION and
leaving its export line reds the link, loudly — verified against the pinned
linker, in a scratch directory outside the checkout, with `WASI_SDK` set to what
`wasm.mk` defaults it to:

    printf 'int present(void){return 1;}\n' > t.c
    "$WASI_SDK"/bin/clang --target=wasm32-wasip1 --sysroot="$WASI_SDK"/share/wasi-sysroot \
      -nostartfiles -Wl,--no-entry -Wl,--export=present -Wl,--export=absent -o t.wasm t.c

    wasm-ld: error: symbol exported via --export not found: absent   → exit 1

Swap that flag for `--export-if-defined=absent` and the same link succeeds
silently at exit 0. **`wasm.mk` uses the strict form throughout**, so the
dangerous edit is the TIDY one: removing the export line and the function
together. Both look like dead weight, the link succeeds, no golden moves, and
what breaks is a native embedder rebuilding from the pin.

### 2. A var whose only caller is an alias, a recipe or a workflow step

The `:main-opts` aliases in `tools/devcards/deps.edn` name their namespace as a
STRING — `-m overlap-canary` — and the alias itself is invoked from `renderer.mk`
and from `.github/workflows/`. Every link in that chain is a string in EDN, make
or YAML, so the `-main` at the end of it has no in-language caller at all.

The same shape one layer deeper, where even a string grep needs help: the
`devcards.findings` producer registry, where a rule is DATA in a vector and its
`:fn` is invoked through the registry rather than by name; `defmulti` dispatch;
and `requiring-resolve` (`tools/renderer-gen/src/asgard/api/backend.clj`), which
resolves a symbol assembled at runtime.

### 3. Code that is inert ON PURPOSE

`.claude/rules/renderer-gen.md` §"Load-bearing subset vs. inert weight" declares
most of the relocated `asgard.*` / `uigen` / `lvgl_codegen` closure unreachable
from the battery and KEPT — relocating the whole closure is what keeps it in step
with the shared upstream it mirrors, and its retirement is the convergence event
named there, not a caller count. A sweep must not eat it. Read that section
before proposing anything under `tools/renderer-gen/`.

## What detection actually exists — and what it cannot answer

**Clojure.** clj-kondo's `:unused-*` family, armed at `:error` in
`.clj-kondo/config.edn` and run over `LINT_CLJ_PATHS` by
`make -f lint.mk lint-clj`. Read the armed set from that config; what matters to
a sweep is the boundary it draws. It reports an uncalled PRIVATE var and says
nothing about an uncalled PUBLIC one — measured, in a scratch directory outside
the checkout:

    printf '(ns probe.a)\n(defn nobody-calls-me [x] (inc x))\n(defn- private-uncalled [y] (dec y))\n' > a.clj
    clj-kondo --lint a.clj

    warning: Unused private var probe.a/private-uncalled

— and nothing at all for `nobody-calls-me`. So "which public var has no caller"
has no analyser here; it is grep — which is also the only instrument that can see
the three classes above, since all three are strings.

The privacy boundary cuts the other way too: **an `:unused-private-var` finding
is a defect to fix, not a sweep candidate.** None of the three classes reaches a
private var — `-m` resolves a public `-main`, and a `:fn` in a registry map is a
reference clj-kondo already counts. The one escape is a runtime
`requiring-resolve` of a private symbol; where that is the root, the resolve site
is where the root gets written down.

**C.** Internal-linkage dead code already fails the BUILD: `wasm.mk`'s
`WARN_FLAGS` carry `-Werror -Wunused-function`, which are FRONTEND diagnostics
emitted while compiling, so **a tree that builds has no dead `static` C**. Above
internal linkage there is **no C reachability graph in this repository** — the
substrate is the export list plus grep. Do not write instructions implying one,
and do not read a green build as coverage of external-linkage functions: those
are exported, and being exported is exactly what makes them reachable from
outside the module and invisible inside it.

Nothing here computes a call graph, a reverse-dependency index or a reachability
set over either language. A sweep is grep plus reading, and its output is a
hypothesis.

## The five layers — any one vetoes

1. **A detector OVER-APPROXIMATES, so its output is a PROPOSAL and never a
   verdict.** Whatever produced the list — a linter, a grep, a reading — rooted
   less than the real program does, because the roots it missed are the ones
   above. Every entry arrives with a named refutation available: the reference
   the detector could not see. Look for it before believing the entry.
2. **Verify each candidate FIRST-HAND, and verify it as a STRING.** grep the bare
   name across `*.mk`, `*.edn`, `*.yml`, `*.clj`, `*.rs`, `*.c` and
   `docs/INTERFACE-CONTRACTS.md`; a resolved-symbol search cannot match any of
   the three classes. A name occurring only at its own definition is a candidate.
   A name occurring in the wire contract is ROOTED BY THE DOCUMENT and the sweep
   stops there.
3. **Delete, then RUN the battery in the pinned container**
   (`.claude/rules/uber-container.md`) — never reason about what it would say.
   Check the COMPILE COUNT as `.claude/rules/renderer.md` requires: the battery
   builds incrementally, so a C deletion that recompiled nothing was never
   judged, and its green describes the previous tree.
4. **A red means the symbol was LIVE — revert AND record the root.** A bare
   revert loses the finding, so the next sweep re-proposes the identical
   candidate and the next worker spends a battery re-learning it. Where the root
   goes depends on what it is: a new CLASS of invisible reference belongs in the
   classes above, a single string reference belongs in a comment at the
   REFERENCE, where whoever follows it next will already be standing.
5. **Never delete a symbol rooted from another language, however green the
   battery.** Nothing in this repo consumes the native ABI and nothing here runs
   a consumer's build, so green is the EXPECTED colour for precisely the deletion
   that breaks the fleet. Layer 3 cannot see past the module boundary; it gets no
   vote on a symbol that crosses one.

## What a candidate that is not deletable becomes

It becomes a ROOT, written down — not an exemption.
`.claude/rules/gate-enforcement.md`'s waiver machinery is for a check that is
WRONG about a specific case; here the check is right that nothing calls the
symbol and wrong that this makes it dead, and no armed gate emits the finding at
all, so there is nothing to waive and no place to record it as one. Make the
invisible reference visible instead: the comment at the reference site, the class
above, the sentence in the wire contract that says who calls it.

A genuine `:unused-*` finding gets the two dispositions every lint finding gets —
fix it, or retire the rule with its reasoning where the rule is configured
(`.claude/rules/lint-gates.md` §"Never suppress; fix at the source",
`.claude/rules/gate-enforcement.md` §1). An inline suppression that keeps a var
nothing calls is the worst available outcome: it hides the symbol from the linter
AND still tells no reader why the symbol exists.

## When the sweep proposes a proto or a binding

It does not. `proto/`, `output/`, `renderer/generated/`, `renderer/lvgl/` and
`docs/proto/` are generated or vendored, so an unused field, message or generated
function there is not a sweep target in either direction: a field number is
consumed forever by the additive-first contract even after its last reader goes
away, and a generated artifact changes only by regenerating its source.
