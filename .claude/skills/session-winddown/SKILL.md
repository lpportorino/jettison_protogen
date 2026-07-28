---
name: session-winddown
description: Pre-handoff state consolidation for protogen. Use when the operator asks to wind down / consolidate / prepare a sendoff, or when context is PROVABLY near-full (the /context gauge or a harness warning — never a guess). Lands in-flight work, rolls findings into durable surfaces, reconciles the fork roster, pushes through the gates, purges and regenerates the task list, and emits a sendoff a fresh session can resume from with zero re-derivation.
---

# Session winddown — consolidate, gate, send off

Everything load-bearing must reach a durable surface before the session ends.
The sendoff is DERIVED from those surfaces; it is never a substitute for them.

**protogen's durable surfaces, and the one that is not:**

| surface | survives | for |
|---|---|---|
| `git log` / commit messages | yes, and reaches every consumer | measured facts, decisions, per-consumer CONSEQUENCES |
| `CLAUDE.md`, `.claude/rules/*.md` | yes | rules and posture that bind future sessions |
| `docs/UI-QUALITY-CONTRACTS.md`, `docs/INTERFACE-CONTRACTS.md` | yes | what a consumer owes |
| `.protogen/research/*.md` | **on disk only — GITIGNORED** | measurements, fork reports, probe write-ups |
| `.protogen/plans/TODO.md` | **on disk only — GITIGNORED** | the session-crossing plan |
| the harness task list | **dies with the session** | in-flight tracking only |

**That split is the whole reason this procedure exists.** The plan and the
research directory are the session-crossing memory and they are NOT in git, so
they are invisible to a fresh clone and to every consumer. A fact that belongs
to the repo — a rule, a contract clause, a measured constraint — must be
committed, or it is lost to everyone but this machine.

## Trigger discipline

- **Operator-triggered** — any explicit wind down / consolidate / sendoff, at
  any fill level.
- **Context PROVABLY near-full** — the `/context` gauge or the harness's own
  warning. Never on a feeling; checking costs one command.
- Do NOT run it because a session merely feels long. A premature winddown
  spends budget the session still had.

## The procedure, in order

1. **Account for every background worker FIRST, because they die with the
   session and their work does not come back.** For each dispatched agent,
   workflow and background command: has its completion notification arrived? An
   agent still running when the session ends is lost effort, and a fork it owns
   must not be touched — see `.claude/rules/fork-isolation.md`, where the only
   sufficient idle signal is that notification. Name every one still in flight
   in the sendoff, with what to re-dispatch.

2. **RECONCILE the fork roster — it should already exist.** Every fork under
   `~/git/cc/scratch/` is in exactly one state, and the sendoff says which:
   lifted+preserved+GC'd; lifted but awaiting GC; in flight and OWNED; or held
   for code nobody has lifted yet. A fork that appears in no list is work about
   to be forgotten. Preserve before deleting, including the untracked sweep a
   bundle cannot carry — and read the WHOLE porcelain, since a STAGED file
   reports `A `, not `??`.

   **Winddown is where the roster is RECONCILED, never where it is first
   written.** `.claude/rules/fork-isolation.md` requires the entry at the
   moment of handover, because the task list dies with the session and a crash
   before winddown would otherwise leave owned trees with no durable owner. If
   you reach this step and a live fork has no roster entry, that is itself a
   finding: write it, and note that the discipline slipped.

   **The same applies to DISPATCHED AGENTS.** They die with the session. Record
   what each is writing and where, at dispatch — an agent that was mid-edit when
   the session ended leaves uncommitted changes whose provenance nothing
   explains.

3. **Land in-flight edits.** Every completed logical change becomes a commit
   with a real message and a per-consumer CONSEQUENCES beat. Nothing half-done
   stays uncommitted — either finish it, or commit it and say plainly in the
   sendoff what is unfinished about it.

4. **Roll findings onto durable surfaces.** A measurement that lives only in
   chat is gone. Route each one:
   - it constrains future work anywhere → a commit message, or a rule
   - it is a repo-wide posture → `CLAUDE.md` or `.claude/rules/`
   - it is what a consumer owes → the contract docs
   - it is evidence for a decision → `.protogen/research/`, and say in the
     sendoff that this is gitignored
   Prefer the most durable surface a fact will fit on. **A correction to a claim
   this session made is itself a finding** — record it, especially where the
   correction reverses an earlier conclusion.

5. **Quality pass on the plan.** Trim executed sections to result-liners.
   Reconcile anything a later measurement superseded — the plan carries stale
   claims easily because nothing gates it. Delete what git now records better.

6. **Push through the gates.** `make -f lint.mk lint` on the host, the battery
   in the pinned container (`tools/uber.sh 'make -f renderer.mk check-renderer'`),
   and the pre-push hook, which blocks by design and cannot amend what git has
   already prepared. If a ui_ast SURFACE render changed, the VLM review is
   MANDATORY and its findings are dispositioned before the push. If you do not
   push, say why in the sendoff — never leave it ambiguous.

7. **Purge and regenerate the task list.** Delete completed tasks; git and the
   sendoff carry the record. Rewrite every survivor self-contained: absolute
   paths, exact commands, acceptance criteria, blockers. The plan and the task
   list must agree, and where they disagree the plan wins.

8. **Emit the sendoff** (below), and only then stop.

## The sendoff

- **State** — branch, pushed sha, ahead/behind, suite and battery result, lint,
  which monitors were armed (they die on restart; a fresh session re-arms).
- **What landed** — one line each, with shas.
- **THE TASK LIST, verbatim and complete** — numbered, self-contained, in
  execution order, so the next session recreates it from the sendoff alone.
  Mandatory. A sendoff without it strands the successor.
- **Forks** — every one, its state, its path, what it owes.
- **In flight** — agents/workflows that will not survive, and what to re-run.
- **Open threads and traps** — each with its next concrete action, and any trap
  a fresh context would re-derive expensively.
- **Reading order** — `CLAUDE.md` → `.claude/rules/` → `.protogen/plans/TODO.md`
  → recent `git log`.

## Anti-patterns

- Writing the sendoff first and running out of budget for the durable pass. The
  surfaces are the deliverable; the sendoff is derived from them.
- Leaving a finding in `.protogen/` that belonged in a commit. That directory is
  gitignored — no consumer and no fresh clone will ever see it.
- Stuffing conversation history into the docs. Findings go in as current facts;
  git owns the narrative, and `.claude/rules/claude-md-policy.md` forbids the
  "we tried X then Y" shape outright.
- A task entry that says "continue P5". Every entry must be executable without
  this session's context.
- GC'ing a fork whose worker has not signalled completion. A clean status, an
  idle process table and a quiet mtime all fail to establish it.
- Pushing without saying the gates ran, or not pushing without saying why.
