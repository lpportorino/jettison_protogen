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
| `cljfmt`, `clj-kondo`, `bash -n` | `lint.yml`, plain runner | fast; kondo is a native binary, cljfmt needs only the CLI |
| `clang-format`, `clang-tidy` | `renderer.yml`, inside the pinned image | the only PINNED clang tooling is the WASI-SDK's; clang-tidy also needs a compile database emitted from the build's own flags |

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
a finding go away. Scoping is a POSITIVE allowlist — lint.mk hands each tool an
explicit list of hand-authored paths and no tool walks the tree, so generated and
vendored code is excluded by never being passed in. If a rule is genuinely wrong
for this repo, disable the RULE with its reasoning in the config (see
`.splint.edn`), which is a decision on the record rather than a silenced symptom.

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
