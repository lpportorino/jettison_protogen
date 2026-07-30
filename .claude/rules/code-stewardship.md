---
description: Boy Scout rule for protogen's HAND-AUTHORED source — leave what you touch cleaner, and the two trees where that instruction is actively wrong. Loads only when editing hand-authored source, never for generated output or the wire-locked proto.
paths:
  - "renderer/src/**"
  - "renderer/config/**"
  - "renderer/lv_conf.h"
  - "tools/devcards/src/**"
  - "tools/devcards/dev/**"
  - "tools/devcards/test/**"
  - "tools/renderer-gen/src/**"
  - "tools/renderer-gen/dev/**"
  - "tools/renderer-gen/test/**"
  - "docs/.protodoc/tools/**"
  - "tools/lint/**"
  - "tools/claude/**"
  - "scripts/**"
  - "*.mk"
---
<!-- LOAD-TEST: code-stewardship -->

# Boy Scout — leave what you touch cleaner, EXCEPT where cleaning is the defect

Every file you touch leaves cleaner than you found it. Rot you encounter is your
problem regardless of who introduced it, and "I only came here to change one line"
is not an exemption — the next reader inherits whatever you walked past.

**This rule is PATH-SCOPED and the scope is load-bearing, not token economy.** Two
of protogen's trees make "tidy what you touch" actively wrong, and an unscoped
stewardship rule would instruct exactly the wrong behaviour there. The frontmatter
above is therefore a correctness boundary. See § "Where this rule does not reach".

## What cleaning means here

- **Fix the rot you can prove.** A stale comment, a dead binding, a citation naming
  a path that moved, a name that says something the code no longer does. If you can
  demonstrate it is wrong, correct it in the same change.
- **Prefer removing the special case to adding a guard.** The bar is that the class
  cannot recur, not that the symptom went (`.claude/rules/review-discipline.md`).
- **Make both sides agree in the SAME commit.** Code fixed with the sentence that
  described the old behaviour left standing is the next defect, already queued.
- **Cut the reasoning into the commit message, never into the void.** `git log`
  reaches every consumer, which is what makes deleting chronicle cheap enough to
  actually do (`.claude/rules/claude-md-policy.md`).

## What it does NOT license

- **Not a drive-by refactor.** Cleaning is bounded by what you can VERIFY in the
  same change. A rename you cannot run the battery over is not stewardship, it is a
  second unreviewed change riding a first.
- **Not reformatting for taste.** The formatters are pinned and gated; if
  `make -f lint.mk lint` is green, the formatting is correct by definition and an
  aesthetic rewrite is churn that hides the real diff.
- **Not silencing.** A finding is FIXED or EXEMPTED with proof. Deleting an
  offending line to quiet a gate is the opposite of stewardship
  (`.claude/rules/gate-enforcement.md` §1).

## Where this rule does not reach, and why each exclusion is a HARD one

The `paths:` list above is a positive allowlist of hand-authored source. Three
classes are outside it, and in each the instruction would be harmful rather than
merely useless:

- **`proto/**` — THE WIRE IS BACKWARD-COMPATIBLE BY CONTRACT.** protogen is the
  pinned upstream for ten consumer repos. Renaming a message, field or enum, or
  renumbering a field, breaks every one of them — and those are exactly the edits
  "tidy this file" invites. `CalibrateCencel` / `calibrate_cencel` is a PUBLISHED
  wire name and a typo, and it STAYS: a spell checker gets an allowlist entry, never
  a rename. A tidying instruction loaded over `proto/` is a loaded gun.
- **Generated projections — `output/**`, `docs/proto/**`, `renderer/generated/**`,
  and `renderer/src/font_*.c`.** A cleanup here is destroyed at the next
  regeneration, or survives as DRIFT that a freshness gate then reports as a
  defect. The fix for anything wrong in a projection is in its generator. Note the
  font tables sit INSIDE an allowlisted directory, so the glob cannot exclude them
  — that one is on the reader.
- **`renderer/lvgl/**` — vendored upstream, byte-exact per `.gitattributes`.**
  Cleaning it forks the pin, and the enum extraction that derives this repo's wire
  numbering reads those exact header bytes.

**The tell, when you are unsure:** ask what happens to your improvement at the next
`make generate` or the next submodule bump. If the answer is "it is overwritten" or
"it breaks a consumer", you are outside this rule.

## Why this file exists separately from the upstream copy

The superproject that vendors protogen carries its own stewardship rule, unscoped.
That is correct there and would be wrong here: agents and skills resolve from the
PROJECT ROOT and never from a submodule mount, so a reader working IN protogen gets
this file and a reader working in the superproject gets that one — never both.
Keeping this scoped is what stops the two from being loaded together and disagreeing
about whether `proto/` may be tidied.

What is SPECIFIC to protogen lives here: the wire-compat exclusion, the generated
projections, the vendored tree. The generic obligation is stated compactly rather
than re-derived, per `.claude/rules/widget-consumer-duty.md` §12 — a consumer's own
rules may state what is specific to it and must point upstream for the rest.
