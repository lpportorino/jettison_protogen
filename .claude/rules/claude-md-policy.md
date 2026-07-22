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

## The 4-tier model

| Tier | Location | What auto-loads | When |
|---|---|---|---|
| 1. **CLAUDE.md** | repo root | full content, rule priority | every session |
| 2. **Rule (unscoped)** | `.claude/rules/*.md` without `paths:` | full content | every session |
| 3. **Rule (path-scoped)** | `.claude/rules/*.md` with `paths:` | full content | only when a matching file is read/written |
| 4. **Skill** | `.claude/skills/*/SKILL.md` | the `description:` line (in the skill listing) | description always; body on invoke/semantic match |

## When to use which tier
- **CLAUDE.md** — the project charter (what this repo is, the fleet/consumer
  model, the top-level engineering invariants). Prefer NOT to enumerate or
  duplicate the `.claude/` tree in it — the harness auto-loads rules and surfaces
  skill/command descriptions on its own (CLAUDE.md's existing proto-docs tooling
  walkthrough is a standing exception, a candidate for later trimming).
- **Unscoped rule** — cross-cutting posture that applies regardless of file type.
- **Path-scoped rule** (`paths:`) — deep guidance that only matters when editing
  matching files (`renderer.md` → `renderer/**`; `devcards.md` →
  `tools/devcards/**`). Zero context cost until matched.
- **Skill** — a task playbook triggered by intent or `/name`. `paths:` on a skill
  gates auto-activation but not the always-loaded description, and suppresses
  intent-triggered firing — so prefer a path-scoped rule for "only while editing
  these files" guidance, a skill for "when the user asks to do X".

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

## No historical narrative — git is the history store
Docs describe the CURRENT state; `git log` / `git blame` hold history. Do NOT
write, in any `.md`:
- "previously / used to / now / was ported from / supersedes / renamed" framing.
- Dated incident logs, "Migration history" / "Recently closed" / "Pre-X"
  sections, `as of YYYY-MM-DD` / `verified YYYY` pins.
- Phase / workstream scaffolding markers (`Phase 7`, `T2.5`, `F2-POC`, `W6`)
  UNLESS the marker is a permanent structural label the code or its output uses.

Narrow exception: an EXTERNAL contract we don't control (a consumer's name, an
upstream bug, a third-party API shape) may be cited as a load-bearing constraint,
with a pointer to where it lives. A structured provenance pin
(`.ported-from.edn`) is the sanctioned home for port lineage — prose never
restates it.

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
