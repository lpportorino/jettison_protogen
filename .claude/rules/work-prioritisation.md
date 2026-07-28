# Choosing what to work and what to donate — leverage first, and scope risk against it

`.claude/rules/fork-isolation.md` governs HOW work is donated. This governs
WHICH work, because the mechanics being right does not stop a wave from moving
nothing.

## The failure this exists to prevent, measured

A wave of five isolated forks all delivered, all lifted, all correct — and the
backlog moved by ZERO. Every one had been chosen for being bounded, verifiable
and safe to hand a stranger, which is what makes a brief work. None had been
chosen for what it UNBLOCKED. The queue is made of large interdependent work, so
a wave selected purely for donatability cannot reduce it, and the task list
GREW: the forks added findings and consumed none of the chain.

The next wave was ranked properly and the ranking exposed a second, sharper
error — see "scope risk inversely" below.

## The order

1. **GATE-BREAKERS FIRST.** Anything blocking a push, a merge or a green
   battery. Everything else queues behind it, so its true cost is the whole
   queue's latency, not its own size. A red gate is never "one task".
2. **DEEPEST UNBLOCKER NEXT**, ranked by TRANSITIVE unblock count, tie-broken by
   chain DEPTH rather than breadth. Three independent leaves parallelise; a
   three-deep chain does not, so depth is latency you cannot buy back with more
   workers.
3. **COMPOUNDING INFRASTRUCTURE** — build/gate speed, tooling that mechanises a
   discipline that has already failed once. It unblocks nothing directly and
   makes everything after it cheaper.
4. **LEAVES LAST.** Real product value, no dependents, freely deferrable.

Compute the counts; do not eyeball them. The harness tracks `blockedBy`, so the
graph is already there — and a task whose blocker is UNRECORDED will be ranked as
a leaf and starve. If you find an edge missing, that is itself a finding.

## SCOPE RISK INVERSELY TO LEVERAGE — this one inverts the instinct

The natural move is to give the most important task the most ambitious brief.
**Do the opposite.**

- A **LEAF** can absorb a risky, open-ended, might-fail brief. Failure is
  contained: nothing waits on it, and an ambitious attempt that returns only a
  design has cost one fork.
- A **DEEP UNBLOCKER must be scoped to SUCCEED.** Tightly specified, bounded,
  with a defined smallest-useful deliverable. If it fails, the entire chain
  behind it waits for the next wave.

Measured here: the deepest unblocker in the graph (four tasks behind it) was
handed the single most open-ended brief of its wave — explicitly permitted to
return an analysis instead of an artifact. **The highest-leverage fork had been
given permission not to deliver.** Meanwhile the largest and riskiest brief went
to a leaf, which by this rule is correct and was luck.

So when a deep unblocker genuinely is too large for one worker, do NOT resolve
that by loosening the brief. Split it: name the SMALLEST deliverable that moves
the chain, demand that, and take the design for the rest as a second output. A
chain that advances one link beats a chain that receives a good essay.

## Completion is part of prioritisation

- **Close or rescope a task the moment its work lands.** A shipped deliverable
  sitting under an open full-scope task makes the queue look static and hides
  what is actually available to work. An integration is not finished until the
  task reflects it.
- **Session bookkeeping is not backlog.** A fork roster, a push reminder, a
  review-the-agents note — these are chores of the machinery, not work. Keeping
  them in the same list as the engineering inflates it and buries the real
  queue. Track them where they belong (the plan, the roster) and delete them on
  completion.
- **A task that only constrains one other task belongs INSIDE that task**, not
  in a general findings list where it will be read too late to matter.

The shorthand: **gate-breakers, then depth, then compounding, then leaves —
and the deeper the unblocker, the tighter the brief.**
