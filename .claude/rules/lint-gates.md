---
description: The format/lint lanes over hand-authored Clojure, C, shell and workflows — where each runs, why the split, and the never-suppress rule. Loads when editing gated source or the gate config itself.
paths:
  # No "**/*.cljc" entry: this repo tracks zero .cljc files, and the md gate
  # refuses a glob that matches nothing — a dead glob is a rule that can never load.
  - "**/*.clj"
  - "**/*.sh"
  - "renderer/src/**"
  - "renderer/config/**"
  - "renderer/.clang-tidy"
  - ".clang-format"
  - ".clj-kondo/**"
  - ".cljfmt.edn"
  - ".splint.edn"
  - "tools/lint/**"
  - ".githooks/**"
  - ".github/workflows/**"
  - "*.mk"
---
<!-- LOAD-TEST: lint-gates -->

# The format/lint gate — auto-fix what is mechanical, block so it gets committed

Hand-authored Clojure and C are gated on formatting and lint. The gate is ARMED:
`.githooks/pre-push` blocks locally (once `make -f lint.mk install-hooks` has run
— the SessionStart banner reports whether it has), and CI blocks authoritatively.

## The loop when the hook blocks you

A pre-push hook CANNOT amend what git has already prepared for transfer. So when
it applies the formatters it must fail the push anyway — otherwise the objects
being sent still contain the unformatted code and CI goes red on exactly what
the hook just "fixed". The block is the design, not a bug in it:

1. `git push` → hook formats, lists the rewritten files, exits 1.
2. **Read the diff.** It is formatter output, but read it — this is how a
   destructive reformat gets caught before it lands.
3. Commit it (a follow-up commit, or amend if the commit is yours and unshared).
4. Push again.

Two guards you will meet:
- **Dirty tree → check-only, no auto-fix.** `fmt-fix` walks whole paths, so on a
  tree with work in progress it would entangle your edits with formatter output
  and "read the diff and commit it" would stop being safe advice.
- **No docker → C is check-only.** The pinned clang-format lives in the WASI-SDK
  inside the uber image. Formatting C with the host's copy rewrites the tree into
  something CI rejects — the exact drift the gate exists to catch.

## Where each lane runs, and why it is split

| lane | runs | why |
|---|---|---|
| `cljfmt`, `clj-kondo`, `lint-sh` (`bash -n` + payload apostrophes), `actionlint`, `lint-clj-gate-test`, `wasm-provenance-test`, the namespace-size ceiling, spec presence | `lint.yml`, plain runner | fast; kondo is a native binary, cljfmt and the two structural lanes named here need only the CLI, and the provenance canary needs neither toolchain — it stubs the compiler, so it is bash and make over a `mktemp` fixture |
| `clang-format`, `clang-tidy` | `renderer.yml`, inside the pinned image — and `clang-tidy` also from the pre-push hook, docker-gated, via `tools/uber.sh` | the only PINNED clang tooling is the WASI-SDK's; clang-tidy also needs a compile database emitted from the build's own flags, so it cannot join the bare-invoked `lint` aggregate |
| the WHOLE-TREE scans — the leak ban, the markdown gate, the file-size ceiling | `hygiene.yml`, plain runner, **no `paths:` filter** | see below — a path filter over a tree-wide scan is a false skip by construction |

**THREE STRUCTURAL CHECKS AND FIVE FORK/LEG CANARIES ARE HOOK-ONLY, NOT
CI-ENFORCED, and that is a gap rather than a documented decision.**
`lint-fn-size`, `lint-docstrings` and `lint-spec-shape` — plus `fork-hazards`,
`brief-check-test`, `forks-release-test`, `leg-strictness-test` and
`uber-chown-test` — all sit in `lint.mk`'s `lint-lanes` aggregate, so
`.githooks/pre-push` runs every one of them. No workflow calls any of the
eight: `lint.yml` runs only `lint-ns-size` and `lint-spec-presence` from the
structural family, `hygiene.yml` covers the whole-tree scans, and neither
touches the rest. `.claude/rules/gate-enforcement.md` §6 makes local and CI
enforcement complements rather than alternatives precisely because a
client-side hook only protects whoever armed it — `--no-verify` bypasses it
outright, by that hook's own header — so these eight currently have no
authoritative half. Read any collective phrase for this family — "the
structural checks", "the structural gates", "the structural lanes" — as naming
all five; which of them CI runs is the enumeration above, not something a name
carries.

**THIS LIST SHRANK, AND WHAT LEFT IT IS WORTH NAMING — because a count that
only ever grows is one nobody is maintaining.** `wasm-provenance-test` is now a
step in `lint.yml`, and `lint-file-size` with its canary `lint-file-size-test`
are steps in `hygiene.yml`; each sits in the workflow its INPUT SET picks, not
the one its subject matter suggests — the provenance canary is hermetic over
six enumerable files so a filter is honest for it, while the size ceiling scans
the checkout so no filter can be. **The size ceiling was never in the
enumeration above at all**: it landed after that paragraph was written, was
hook-only for the whole of its life until now, and nothing reported the
omission. So read the enumeration as only as current as the last person who
remembered to edit it while adding a lane to `lint-lanes` — the aggregate is
the fact, this paragraph is a copy of it, and `grep lint-lanes lint.mk`
against the steps in `.github/workflows/` is what settles a disagreement.

**THE THIRD WORKFLOW IS NOT A TIDINESS SPLIT.** Every lane in the first row is
handed a positive allowlist, so a path filter naming those file types is complete
for it. A whole-tree scan is the opposite: its input set is the checkout, and its
verdict can turn on a file of any type. A leak landing in a `.toml`, a
`.gitattributes` or a `Dockerfile` matches none of `lint.yml`'s patterns, so that
job would never fire on the commit it exists to catch — and the markdown gate is
sharper still, because two of its clauses resolve citations against the whole
tracked universe, so deleting a cited `.c` file changes its answer with no markdown
touched at all.

Widening `lint.yml`'s filter instead would be a list that rots AND would drag a JDK
setup, cljfmt and clj-kondo onto doc-only pushes. `hygiene.yml` therefore carries
no filter, and that absence is load-bearing: **do not add one.** A future scan that
is genuinely path-scoped belongs in `lint.yml` rather than there.

## `lint-sh` proves the payload PARSES — not that it FAILS when it should

`generate-protos.sh` builds each language leg as a single-quoted `bash -c`
payload, and both of that lane's checks ask only whether the string is still a
program. Neither can see a payload that runs, breaks, and exits 0. Two ways that
happens, and the first is live in this tree:

- **A pipeline reports the FILTER's exit code, not the command's** — unless
  `set -o pipefail` is on, and only the host script sets it; every container
  payload sets bare `set -e`. So the rust leg's closing `cargo build 2>&1 |
  tail -5` exits 0 on a failed build, `set -e` never fires, and the leg prints
  "completed successfully". Nothing downstream catches it either: the summary's
  "no files generated" warning cannot fire, because `output/rust/` is tracked
  and the mounted volume is never empty. The go leg is the shape to copy — it
  asserts its OWN artifact (`*.pb.go` found, else `exit 1`) instead of trusting
  a builder's status. Run the command bare and filter its output afterwards; or
  assert the artifact.
- **A background job that mutates tracked files OWNS them until it exits**, so
  `git add -A` while one runs stages a tree that is correct for that job and
  wrong for a commit — and the commit looks ordinary. This is the mirror of the
  dirty-tree guard above: that one stops `fmt-fix` entangling your edits, this
  one stops your commit capturing `fmt-fix`'s. Stage explicit paths, or wait for
  the completion signal — an unchanged output file is not one, since a filtered
  pipeline buffers until it exits.

## clang-tidy: driven by a REAL compile database, never hand-assembled flags

`make -f lint.mk lint-c-tidy` (container-only) runs clang-tidy over the
hand-authored C under `renderer/.clang-tidy` (WarningsAsErrors:'*', the fleet's
jettison check set). It first emits `renderer/compile_commands.json` via
`make -f wasm.mk compile-db`, which writes the build's OWN flags — including
`-std=c23` from the single-sourced `APP_STD`. This is not optional polish: a
hand-assembled flag list silently disagrees with the build and clang-tidy then
reports diagnostics the compiler never sees. Measured: omitting `-std=c23` gave
three phantom parse errors on a `static_assert` that compiles cleanly, and
doubled a macro-parentheses count. If clang-tidy output looks like a parse
error, suspect the flags before the code.

The config DECLINES `bugprone-narrowing-conversions` on purpose, cross-
referencing wasm.mk's `-Wconversion`-off decision: the renderer is built on
intentional proto-int <-> LVGL numeric casts, so every hit is that one
documented class and none is a defect. Declining it (vs 18 site NOLINTs) keeps
the gate from re-litigating a made decision every build — and it would not earn
its keep anyway, since the one real narrowing bug found this session was an
EXPLICIT cast the check does not flag. The mine, not the gate, is where narrowing
gets caught.

clj-kondo is deliberately NOT in the uber image and does not need to be: a linter
emits findings, never a committed artifact, so the uber-container rule does not
reach it. This is why `tools/uber.sh 'make -f lint.mk lint'` fails on `lint-clj`
while every other lane runs there happily.

## Never suppress; fix at the source

No `#_:clj-kondo/ignore`, no `:config-in-ns`, no widening an ignore list to make
a finding go away. Scoping is a POSITIVE allowlist for every lane but one —
lint.mk hands each tool an explicit list of hand-authored paths, so generated
and vendored TREES are excluded by never being passed in. The C formatter is
the deliberate exception: its discovery is git-derived minus the declared
FMT_C_EXCLUDES, so for THAT lane an exclusion entry is scope declaration, and
widening it to make a finding go away is exactly the suppression this section
bans. Not every generated FILE is:
one emitter projection sits under a gated `src` root and IS linted on purpose,
because a projection must stay canonical and lint-clean and regenerating it
satisfies both. The structural lanes hold it out instead, by a derived path
predicate rather than a list — `lint.mk`'s header carries which file and why.

A hand-authored file can be held out of the structural lanes for a DIFFERENT
reason than that one — not because no finding against it could ever be
satisfied, but because those lanes never DISCOVER it. `lint-gate.util/clj-files`
matches `\.clj[cs]?$`, so an extension outside that set yields no file however
it is configured. It is not about being a lone file: a bare
`docs/.protodoc/tools/build.clj` sits in `LINT_CLJ_PATHS` and is judged
normally. `tools/scratchcard/bin/scratchcard.bb` is the instance: cljfmt and
clj-kondo take it, so `lint.mk`'s `LINT_CLJ_FILES` gates it with both, while
`lint-ns-size`, `lint-fn-size`, `lint-docstrings`, `lint-spec-shape` and
`lint-spec-presence` never see it at all. It is not a suppression — nothing
excuses a finding a `LINT_CLJ_FILES` member produces under either tool — it is
a narrower judgment, on the record at `LINT_CLJ_FILES`'s declaration in
`lint.mk`.

If a rule is genuinely wrong for this repo, disable the RULE with its reasoning in
the config (see `.splint.edn`), which is a decision on the record rather than a
silenced symptom.

## splint is report-only, and that was measured

`make -f lint.mk splint-clj` runs it; nothing blocks on it. The vast majority of
raw findings are a single rule whose suggestion is the literal placeholder
`(CLASS/.method …)` — disabled in `.splint.edn` — and `--autocorrect` clears a
chunk of what remains. The measured counts behind both claims live at the
`splint-clj` recipe in `lint.mk`, which is their one home: they are decision
evidence (they are what makes the disable a measured choice rather than an
omission), so read them there rather than trusting a copy here that cannot
track the next splint run.

What matters at this tier is the disposition, which does not rot:
`--autocorrect`'s output does NOT survive our own gates — on this tree it
dropped a load-bearing comment, blew readable `str` calls out to one argument
per line, flattened hand-aligned map literals, and emitted a fully-qualified
`clojure.string/join` into a namespace with no such require, which clj-kondo
then rejected. **Never run splint in fix mode here.** The findings that survive
the disable need judgement (namespace renames, alias conventions,
catch-throwable), and that is why splint is not yet a gate.

## Renaming a binding that shadows clojure.core is the sharp edge

`shadowed-var` findings are fixed by renaming the LOCAL, never the keyword key
(`:name`, `:type`, `:count` are data — the on-disk EDN/proto vocabulary). The
trap: a reference you MISS silently resolves to the `clojure.core` var instead.
clj-kondo stays green. It breaks at runtime. This has bitten twice — a missed
`comp` died with a NullPointerException at render time. After such a rename,
grep the whole enclosing form for the old name, and prove it by RUNNING the
thing (`make docs-docker-test`, the renderer battery), not by re-linting.
