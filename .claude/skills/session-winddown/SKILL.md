---
name: session-winddown
description: Pre-handoff state consolidation for protogen. Use when the operator asks to wind down / consolidate / prepare a sendoff, or when context is PROVABLY near-full (the /context gauge or a harness warning — never a guess). Accounts for background workers, reconciles the fork roster, regenerates artifacts into the commits that carry them, rolls findings onto durable surfaces, runs the gates and PROVES the push landed, purges and regenerates the task list, and emits a sendoff a fresh session can resume from with zero re-derivation.
---

# Session winddown — consolidate, gate, send off

Everything load-bearing must reach a durable surface before the session ends.
The sendoff is DERIVED from those surfaces; it is never a substitute for them.

**protogen's durable surfaces, and the ones that are not:**

| surface | survives | for |
|---|---|---|
| `git log` / commit messages | yes, and reaches every consumer | measured facts, decisions, per-consumer CONSEQUENCES |
| `CLAUDE.md`, `.claude/rules/*.md` | yes | rules and posture that bind future sessions |
| `docs/UI-QUALITY-CONTRACTS.md`, `docs/INTERFACE-CONTRACTS.md` | yes | what a consumer owes |
| the fork roster (`tools/claude/forks.sh list`) | on disk only — its state dir lives under gitignored `.protogen/` | who owns which tree, under which task id, and what released it |
| `.protogen/research/*.md` | **on disk only — GITIGNORED** | measurements, fork reports, probe write-ups |
| `.protogen/plans/TODO.md` | **on disk only — GITIGNORED** | the session-crossing plan |
| the harness task list | **dies with the session** | in-flight tracking only |
| the sendoff message itself | **dies with the conversation** | a convenience copy, never a record |

**That split is the whole reason this procedure exists.** The plan and the
research directory are the session-crossing memory and they are NOT in git, so
they are invisible to a fresh clone and to every consumer. A fact that belongs
to the repo — a rule, a contract clause, a measured constraint — must be
committed, or it is lost to everyone but this machine.

**The sendoff ranks BELOW every row above it.** It is a chat message: it is not
fetched, not cloned, and not greppable next week. Anything that matters must
already sit on a higher row, which is exactly what the completion check's last
question tests.

## ⛔ PRE-FLIGHT — run this before step 1, and obey it

**MOST OF THIS PROCEDURE WRITES THE TREE** — regeneration, the plan pass, the
commits, the task purge. The battery and the generators read and rewrite that
same tree, so a concurrent run produces a verdict describing neither the tree
before nor the tree after, and can mint goldens from a half-edited corpus.

    docker ps --format '{{.Image}}\t{{.Status}}\t{{.Command}}'
    pgrep -af '[m]ake -f renderer.mk|[g]enerate-protos.sh|[u]ber.sh'

**If a producing or verifying run is live, the winddown is BLOCKED, not
partial.** Choose ONE, out loud:

- **WAIT** for it, and say so — do not fill the wait by drafting the sendoff; or
- **KILL IT** when its result is already known (it is already red, or you have
  since changed what it was testing). A run you must repeat anyway is pure
  blocker.

**Identify before you signal.** `.claude/rules/fork-isolation.md` is binding
here: a container may belong to a sibling checkout, and a pattern-matched kill
has already taken out another session's processes. Confirm the mount points at
THIS checkout first —

    docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' <container>

After killing, `docker ps` must be empty and `git status --porcelain` must show
no half-written artifact: the container is `--rm` so nothing survives it, but a
lane interrupted mid-install can leave a truncated `output/manifests/*.json`
(the manifests lane installs by non-atomic copy) or a partial mint under
`tools/devcards/`. Restore only files you did not hand-author.

**Never run the read-only half and emit the sendoff anyway.** That produces a
sendoff derived from conversation instead of from committed surfaces — this
file's first anti-pattern — and it reads as a completed winddown to everyone,
including you.

**SEQUENCING follows from this.** The battery belongs at step 7, after every
tree write. Regenerate → commit → gate → push is the order that leaves the tree
frozen exactly when the gates need it.

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
   must not be touched — see `.claude/rules/fork-isolation.md`, where that
   notification is the ONLY sufficient idle signal; a clean status, an idle
   process table and a quiet mtime each fail to establish it. Name every one
   still in flight in the sendoff, with what to re-dispatch.

2. **RECONCILE the fork roster — it should already exist.** Run
   `tools/claude/forks.sh list`, then reconcile its roster against the tree each
   entry names AND against every clone this session created that has no entry.
   Each is in exactly one state, and the sendoff says which: lifted+preserved+
   GC'd; lifted but awaiting GC; in flight and OWNED; or held for code nobody
   has lifted yet. A fork that appears in no list is work about to be forgotten.
   Preserve before deleting, including the untracked sweep a bundle cannot carry
   — and read the WHOLE porcelain, since a STAGED file reports `A `, not `??`:

       git -C <fork> status --porcelain --untracked-files=all

   **Winddown is where the roster is RECONCILED, never where it is first
   written.** `.claude/rules/fork-isolation.md` requires the entry at the moment
   of handover, because the task list dies with the session and a crash before
   winddown would otherwise leave owned trees with no durable owner. If you
   reach this step and a live fork has no roster entry, that is itself a
   finding: write it, and note that the discipline slipped.

   **The same applies to DISPATCHED AGENTS.** They die with the session. Record
   what each is writing and where, at dispatch — an agent that was mid-edit when
   the session ended leaves uncommitted changes whose provenance nothing
   explains.

3. **Regenerate what this session's source changes invalidated, BEFORE the
   commit that carries them — and in the pinned toolchain, never on the host.**
   A generated artifact belongs in the SAME commit as the source change that
   moved it. Split them and CI's runner-side freshness diff reddens a commit
   that is otherwise correct, while a consumer that pins the intermediate sha
   vendors a binding that does not match the proto it was generated from.

   | what changed | regenerate with | runs |
   |---|---|---|
   | any `.proto` | `make generate`, then `make docs-docker-generate` → write descriptions → `make docs-docker-lint` | HOST (these orchestrate docker themselves) |
   | renderer, theme, or corpus pixels | `tools/uber.sh 'make -f renderer.mk check-renderer'` (mints + verifies goldens) and `tools/uber.sh 'make -f renderer.mk gallery-prebuilt'` | the pinned container |

   Docs are rendered from the descriptor set `make generate` rebuilds, so the
   docs leg runs after it and never instead of it. The gallery leg is
   mint-only and is not a `check-renderer` lane, so prove freshness the way CI
   does, from the host:

       git diff --exit-code tools/devcards/goldens tools/devcards/docs

   `.claude/rules/uber-container.md` is why the container is not optional: the
   host JDK's JPEG encoder rewrites every gallery sheet byte-for-byte, so a
   host-side regen produces artifacts CI rejects — all of them.

4. **Land in-flight edits.** Every completed logical change becomes a commit
   with a real message and a per-consumer CONSEQUENCES beat — that beat is what
   each bump author executes verbatim, so it is written even when the answer is
   "bump only".

   **Trunk-only changes what "commit it anyway" means.** There is no feature
   branch to park on: a commit on `master` is a push away from ten consumers.
   So land only what is coherent on its own. Work that is genuinely half-done
   goes to a fork with a roster entry, or stays uncommitted and is named in the
   sendoff with the file paths it touches — never onto trunk as a partial
   change wearing a finished message.

5. **Roll findings onto durable surfaces.** A measurement that lives only in
   chat is gone. Route each one:
   - it constrains future work anywhere → a commit message, or a rule
   - it is a repo-wide posture → `CLAUDE.md` or `.claude/rules/`
   - it is what a consumer owes → the contract docs
   - it is evidence for a decision → `.protogen/research/`, and say in the
     sendoff that this is gitignored

   Prefer the most durable surface a fact will fit on. **A correction to a claim
   this session made is itself a finding** — record it, especially where the
   correction reverses an earlier conclusion, and carry the same list into the
   sendoff's "what to distrust in my own record". The successor inherits an
   uncorrected claim as a fact.

6. **Quality pass on the plan — read `.protogen/plans/TODO.md` WHOLE, not the
   sections you touched.** Nothing gates this file, so it carries stale claims
   easily. Trim executed sections to result-liners and delete what git now
   records better. Then hunt three specific shapes:
   - **a claim that ASSERTS A STATE instead of naming the command that derives
     it** — a sha, a count, an "N unpushed", a "nothing is in flight". Delete
     the value and leave the query in its place. Refreshing it is not a fix:
     the fresh one rots on the identical mechanism, which is
     `.claude/rules/claude-md-policy.md` § "A number is a TALLY or a
     MEASUREMENT". A measurement that carries the condition reproducing it
     stays; a bare tally of external state does not.
   - **a path that no longer exists** — `ls` each one.
   - **a claim THIS SESSION falsified.** You are the worst-placed reader to
     notice these, so grep the plan for the nouns you changed rather than
     re-reading it for vibes.

7. **Run the gates, then PROVE the push landed.** `make -f lint.mk lint` on the
   host, the battery in the pinned container
   (`tools/uber.sh 'make -f renderer.mk check-renderer'`), and the pre-push
   hook, which auto-formats and then blocks BY DESIGN — it cannot amend what git
   has already prepared for transfer, so its exit 1 is the expected outcome of a
   rewrite, not a failure to interpret. Commit the rewrite and push again.

   If a ui_ast SURFACE render changed, the VLM review is MANDATORY and its
   findings are dispositioned before the push. Resolve the batch with
   `.claude/skills/ui-standard-review/preflight.sh`; its exit 3 means NOT
   DISCHARGED — an obligation you still owe, never a clean run.

   **A push is not landed because `git push` printed something.** It can be
   rejected non-fast-forward (trunk moved under you), blocked by the hook, or
   have pushed a sha earlier than the one you gated. Prove it:

       git fetch origin && git rev-parse HEAD origin/master   # one sha, twice
       git status -sb                                          # ahead 0

   **When a gate is RED at winddown time, the disposition is explicit and
   written BEFORE the push, not after.** protogen is the pinned upstream and CI
   is the authoritative gate, so a red trunk is every consumer's problem. Either
   fix it, or do not push — and if you push a tail anyway on the operator's
   call, the commit message and the sendoff both name: which lane is red, the
   exact command that reproduces it, the sha the red was measured at, and why
   the push is still correct. If you do not push at all, say why. Never leave
   either ambiguous.

   **The push starts a CI fan-out that this session will not see.** `ci-watch`
   dies with the session, so the run your winddown triggered has no observer
   once the sendoff is sent. That is not a reason to skip the push; it is a
   reason the sendoff says which sha is unverified and that the successor's
   FIRST act is re-arming the monitors — `ci-watch` emits an arm-time
   current-state read, which is what recovers the result.

8. **Purge and regenerate the task list — LAST, because it is destructive.**
   Deleting entries before the durable pass is finished loses the record if
   budget runs out mid-winddown; git, the plan and the sendoff must already
   carry it.
   - **DELETE completed tasks; do not mark them.** A `completed` entry still
     renders and the next reader still reads it. Deletion is the purge.
   - **Rewrite every survivor self-contained**: repo-relative paths, exact
     commands, the acceptance criterion as a NUMBER where one exists, and its
     blockers stated inline. "Continue P5" is not a task.
   - **RANK by what each MAKES STARTABLE**, not by size — a task that unblocks
     three others outranks a bigger one that unblocks none.
   - **Cross-check every list that cites a task id.** The fork roster's TASK
     column (`tools/claude/forks.sh list`) points at task ids; purging a task
     an OWNED fork still cites orphans that pointer and strands the tree. The
     plan and the task list must agree, and where they disagree the plan wins.

9. **Emit the sendoff** (below), and only then stop.

## The sendoff

- **State** — branch; the pushed sha AND the fetch-and-compare that proved it;
  lint, battery and suite results each with the sha they were measured at; which
  monitors were armed, noting they die on restart so the successor re-arms.
- **What landed** — one line each, with shas.
- **Fleet consequences** — if anything on a wire or generated surface landed,
  which consumers now owe a regenerate/rewire/bump-only, copied from the commit
  messages' CONSEQUENCES beats.
- **Unverified CI** — the sha whose run nothing is watching, and that re-arming
  `ci-watch` is how the successor learns its conclusion.
- **THE TASK LIST, verbatim and complete** — numbered, self-contained, ranked by
  what each makes startable, so the next session recreates it from the sendoff
  alone. Mandatory. A sendoff without it strands the successor.
- **Forks** — every one, its state, the path its roster entry records, what it
  owes.
- **In flight** — agents/workflows that will not survive, and what to re-run.
- **What to distrust in my own record** — every number corrected and every claim
  retracted this session.
- **Open threads and traps** — each with its next concrete action, and any trap
  a fresh context would re-derive expensively.
- **Operator asks** — copy-paste one-liners.
- **Reading order** — `CLAUDE.md` → `.claude/rules/` → `.protogen/plans/TODO.md`
  → recent `git log`.

## Completion check — answer all seven before claiming it is done

Partial execution is invisible without this, because every individual step looks
finished from inside it.

1. Was anything producing or verifying running when you started? (If yes you
   should not have started — see PRE-FLIGHT.)
2. Is `git status --porcelain --untracked-files=all` empty, or does the sendoff
   name every survivor and why it is parked?
3. Does `git diff --exit-code tools/devcards/goldens tools/devcards/docs` pass,
   and did every regenerated artifact ride the commit of its own source change?
4. Did you read the plan WHOLE, and does it still assert a state anywhere
   instead of naming the query that derives it?
5. Does `git rev-parse HEAD origin/master` print one sha twice after a fetch —
   or does the sendoff say plainly why not?
6. Do the plan, the harness task list and the roster's TASK column agree?
7. **Could you regenerate this sendoff from the repo plus `.protogen/` alone,
   with this conversation deleted?** Any line that exists only in your context
   is a step not finished.

## Anti-patterns

- Starting the winddown while a battery or generator runs, then doing the
  read-only half and emitting a sendoff anyway.
- Writing the sendoff first and running out of budget for the durable pass. The
  surfaces are the deliverable; the sendoff is derived from them.
- Treating `git push` output as proof it landed.
- Committing a source change and its regenerated artifact separately.
- Leaving a finding in `.protogen/` that belonged in a commit. That directory is
  gitignored — no consumer and no fresh clone will ever see it.
- "Refreshing" a stale sha or count in the plan instead of replacing it with its
  query.
- Stuffing conversation history into the docs. Findings go in as current facts;
  git owns the narrative, and `.claude/rules/claude-md-policy.md` forbids the
  "we tried X then Y" shape outright.
- Marking harness tasks completed and calling that a purge.
- Purging a task id that an OWNED fork's roster entry still cites.
- A task entry that says "continue P5". Every entry must be executable without
  this session's context.
- GC'ing a fork whose worker has not signalled completion. A clean status, an
  idle process table and a quiet mtime all fail to establish it.
- Killing a container without confirming its mount points at this checkout.
- Pushing without saying the gates ran, pushing a red tail without recording the
  deviation, or not pushing without saying why.
