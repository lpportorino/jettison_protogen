---
paths:
  - "*.md"
  - "**/*.md"
---
<!-- LOAD-TEST: claude-md-policy -->

# CLAUDE.md / Rules / Skills — authoring policy

Auto-loaded when editing ANY `.md` file. Codifies how this repo organises its
Claude-Code-facing docs and the markdown-authoring discipline — **no drift-prone
enumerations, no historical narrative** — that keeps them true and lean.
This repo is additive-first and backward-compatible by contract — the pinned
upstream for its consumer fleet (see `CLAUDE.md`); a no-backward-compat posture
must not be imported into these docs.

## The 5-tier model

| Tier | Location | What auto-loads | When |
|---|---|---|---|
| 1. **CLAUDE.md** | repo root | full content, rule priority | every session |
| 2. **Rule (unscoped)** | `.claude/rules/*.md` without `paths:` | full content | every session |
| 3. **Rule (path-scoped)** | `.claude/rules/*.md` with `paths:` | full content | only when a matching file is read/written |
| 4. **Skill** | `.claude/skills/*/SKILL.md` | the `description:` line (in the skill listing) | description always; body on invoke/semantic match |
| 5. **Agent** | `.claude/agents/*.md` | the `description:` line (in the agent-type listing) | description always; body becomes the SUBAGENT's system prompt |

## When to use which tier
- **CLAUDE.md** — the project charter (what this repo is, the fleet/consumer
  model, the top-level engineering invariants). Do NOT enumerate or duplicate
  the `.claude/` tree in it — the harness auto-loads rules and surfaces
  skill/command descriptions on its own — and do not restate a tooling
  walkthrough that a README, a `make help` or a schema file already owns. It
  holds LAW; wherever a fact has an executable owner, it names that owner
  instead of copying it. There is no standing exception to this.
- **Unscoped rule** — cross-cutting posture that applies regardless of file type.
- **Path-scoped rule** (`paths:`) — deep guidance that only matters when editing
  matching files (`renderer.md` → `renderer/**`; `devcards.md` →
  `tools/devcards/**`). Zero context cost until matched.
- **Skill** — a task playbook triggered by intent or `/name`. `paths:` on a skill
  gates auto-activation but not the always-loaded description, and suppresses
  intent-triggered firing — so prefer a path-scoped rule for "only while editing
  these files" guidance, a skill for "when the user asks to do X".
- **Agent** — a LAUNCHER for work that must run in its own context, with its own
  model tier and tool set. Pair it with a skill rather than inlining the
  playbook: the agent body says which model, what to load and what to return;
  the skill stays the single copy of the content. `model:` belongs here (agent
  and command frontmatter), never in a skill, which has no such key. Use the
  stable ALIAS (`sonnet`, `opus`), never a pinned version string — a version is
  the drift-prone form this file bans everywhere else.

## No drift-prone enumerations
Anything a source of truth already advertises MUST NOT be re-stated in prose —
the copy goes stale the moment the source changes. Point at the source instead:
- test/case counts → "the protodoc suite (`make docs-test`)", never "N tests".
- toolchain versions → "the versions pinned in `Dockerfile.base`", never numbers.
- protocol / ABI constants → name the pinned symbol (`render-protocol` in
  `tools/devcards/src/devcards/core.clj`, `CONTROLS_ABI_VERSION` in
  `renderer/src/main.c`), never a copy.
- tool / skill / rule catalogs → cross-reference by name; the harness surfaces them.

Prefer neutral language: "every WidgetType", "the pinned tick budget", "the
supported-ABI set in host.clj". A generated, drift-gated index (regenerated from
the source so it cannot go stale) is the one sanctioned exception.

### A number is a TALLY or a MEASUREMENT — only one of them keeps
A cardinality answers "how many right now?", which durable prose cannot answer
honestly; a measurement answers "what did this run produce?", which it can, so
long as it carries what re-derives it. Three dispositions, no fourth:
- **Bare tally of external state** (cards in a directory, entries in a registry,
  cases in a suite) → DROP it and name the owner. Never "correct" a stale count;
  the fresh one rots on the identical mechanism. `lint.mk`'s `splint-clj` block
  is the model: where raw finding counts would sit, it states the shape of the
  split and hands over the command that re-derives it.
- **Count restating an enumeration ON THE SAME PAGE** → keep. The list is its
  home and the reader verifies it by looking — and it stops being self-verifying
  the moment the enumeration moves away from it.
- **Measurement carrying the condition that reproduces it** → keep. `lint-gates.md`'s
  clang-tidy finding (omitting `-std=c23` yields phantom `static_assert` parse
  errors) is a number a reader can re-obtain on demand, not a claim about now.

The tell for the first is a bare count sitting one clause away from prose saying
the set is discovered at runtime — the pointer is already there, so the number
is pure drift for nothing. `CLAUDE.md`'s cross-engine mirror sentence was the
live instance and has since been repaired: it now says the comparison runs "over
every card discovered from the card directory so a new one joins by itself" and
names no number. Cited as the SHAPE to copy, not as an outstanding defect —
which is itself the discipline, since leaving it described as live would be the
same drift one level up.

### The same law reaches a literal list in CODE
Where the members can be DERIVED — from a directory, a call, a schema — the
derivation is the only permitted form. A literal beside a live source is not a
cache of it, it is a second silently divergent source, and comparing the two is
exactly what nobody does. When both exist, the LIST is the bug: delete it, never
edit it to match, which leaves the mechanism intact for the next divergence.
"Keep in sync with X" as a comment is the same defect wearing a promise — an
assertion nothing executes. No open violation here (the mirror discovers its
cards; the per-language output dirs live only in `generate-protos.sh`'s own
`mkdir`), so this holds a practice nothing currently enforces.

## No historical narrative — git is the history store
Docs describe the CURRENT state; `git log` / `git blame` hold history. Do NOT
write, in any `.md`:
- "previously / used to / now / was ported from / supersedes / renamed" framing.
- Dated incident logs, "Migration history" / "Recently closed" / "Pre-X"
  sections, `as of YYYY-MM-DD` / `verified YYYY` pins.
- Phase / workstream scaffolding markers (`Phase 7`, `T2.5`, `F2-POC`, `W6`)
  UNLESS the marker is a permanent structural label the code or its output uses.

**The deletion test**, before keeping any sentence that reads like history:
remove it. If the doc still commands the same behaviour with the same precision,
it was chronicle — cut it. If precision drops, it was law — keep it. This is
what separates a failure MECHANISM (law: it defines what the rule forbids) from
the debugging session that found it (chronicle).

**Cut reasoning into the COMMIT MESSAGE, never into the void.** A chronicle
being deleted that exists nowhere else lands in the message of the commit that
deletes it. `git log` is already this repo's history store and reaches every
consumer, so the cut loses nothing — which is what makes cutting cheap enough to
actually do.

Narrow exception: an EXTERNAL contract we don't control (a consumer's name, an
upstream bug, a third-party API shape) may be cited as a load-bearing constraint,
with a pointer to where it lives. A structured provenance pin
(`.ported-from.edn`) is the sanctioned home for port lineage — prose never
restates it. That pin carries a closed fact set: `:upstream`, `:upstream-sha`
QUERIED from the upstream at write time rather than recalled, `:upstream-path`,
`:flavor`, `:note`. A `:binary-vendor` entry that was BUILT rather than copied
owes one more — `:image`, the container image the build ran in — because where an
upstream does not commit its build output, "rebuild and compare" IS the recovery
route and a tool version alone does not say what to re-run. A COPIED binary
discharges the same duty with the artifact's own hash instead
(`renderer/assets/fonts/.ported-from.edn` records the release TTF's sha256).
Nothing in this repo resolves any of these shas, so a wrong pin sits undetected —
and the LVGL pin is the one whose exact header bytes the enum extraction, hence
the wire numbering, depends on.

## A pointer carries its coordinate
A `path:line` citation is true only at the instant it is written: an edit
anywhere ABOVE it shifts every citation below out from under the text it named,
and nothing reports that. A durable citation into code therefore names its rev —
`path@<sha>:line`, or one sentence in the document header saying that its bare
`path:line` cites resolve against a named sha. Into VENDORED code the rev is
already pinned and free to reuse (`renderer/lvgl/.ported-from.edn`'s
`:upstream-sha`), which makes the bare form there a pure omission. Prefer the
most stable anchor available — a symbol or function name outlives any line
number — and reserve `file:line` for where the line itself is the fact. A
GENERATED document inherits its citations from its sources and is never where
this gets fixed: change the source, regenerate.

## `paths:` frontmatter discipline
- Shape is the YAML list above; anchor globs to the repo root; one rule, one
  scope theme (don't union unrelated globs).
- No prose `**Scope:**` block when `paths:` exists — the frontmatter IS the scope.
- Every path-scoped rule embeds `<!-- LOAD-TEST: <rule-name> -->` immediately
  after the frontmatter, so loading is smoke-testable ("Which LOAD-TEST
  sentinels do you see in context?").

## Naming
kebab-case `<concept>.md`; a language/scope split keeps the base name and suffixes
the child (`code-quality.md` → `code-quality-c.md`).

## Discouraged
- `paths: "**/*"` — that's an unscoped rule; omit `paths:` instead.
- `paths:` frontmatter AND a prose `**Scope:**` block — redundant.
- NEW CLAUDE.md text that enumerates or paraphrases the `.claude/` tree — the harness already loads it.
- An absolute machine-local path (`~/…`, `/home/…`) in ANY checked-in file —
  rule, skill, script, workflow. It bakes one operator's home layout into a tree
  every consumer clones. Use repo-relative paths, or "this repo"; name a fork or
  sibling checkout by the path its own roster entry records
  (`tools/claude/forks.sh list`), because forks here are operator-chosen clone
  targets with no sibling-directory convention to fall back on.
