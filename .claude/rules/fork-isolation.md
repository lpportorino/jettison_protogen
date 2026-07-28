# Isolated forks — strip the remote, lift by evidence, verify the restore

Work that needs its own tree runs in a clone of this repo with its **remote
REMOVED**. protogen is the pinned upstream for a consumer fleet and CI fans out
on any push to `master`, so the strip is not tidiness: with no remote configured
a push cannot resolve a destination at all, which is the only thing that makes a
mistake unable to reach the fleet.

## A CLONE, NEVER A `git worktree` — and the harness offers you the wrong one

**A worktree shares this repo's `.git`: its refs, its config, and its REMOTE.**
Everything the paragraph above claims is then false. `git push` from a worktree
resolves `origin` perfectly well, reaches `master`, and fans out to every
consumer repo. The strip is the whole safety property, and a worktree cannot
have it — not because someone forgot, but because there is only one `.git` and
the fork does not own it.

This needs saying explicitly because **the isolation you are offered by default
is the unsafe one**: the Agent tool takes `isolation: "worktree"`, which is the
obvious thing to reach for and is exactly wrong here. Trunk-only does NOT save
you either — `git worktree add --detach` needs no branch, so the branch ban
never fires and nothing warns.

So: donation gets a CLONE, always. If you find yourself in a worktree of this
repo doing work meant to be isolated, you are one `git push` from the fleet;
stop and re-do it as a clone.

(Upstream sibling repos run a two-profile scheme — worktree "camps" for
in-harness subagents, clones for third parties. **That split does not port
here.** It is sound where the remote guards one repository; protogen's remote
detonates across ten. One profile, one mechanism.)

## ONE FORK, ONE OWNER — and write down who it is

A fork has exactly one worker. Two in one tree is not a merge inconvenience, it
is a design failure: neither can see the other's intent, so they produce
competing implementations of the same thing and there is no principled way to
choose between them afterwards. Measured here — one lane ended up with a
committed workflow from one worker, an untracked competing workflow from
another, and a generated artifact modified by neither's brief, all in one tree.

- **Name the owner in the seed commit and in the brief.** "You are the only
  worker in this checkout" is a fact the worker can act on: it licenses
  committing freely and it makes an unexpected file a signal rather than noise.
- **PUBLISHING A FORK'S PATH IS THE HANDOVER.** The moment you give an operator
  the path of a fork you built for them, it is owned — by them, or by whoever
  they point at it. Ownership does not wait for the worker to start, and it
  cannot be reclaimed by inference later. This is the failure that actually
  happened: five forks were built for a third-party harness and their paths
  handed over; a subsequent instruction to "dispatch five agents" was then
  resolved ONTO those same five directories, putting two autonomous workers on
  each identical brief.
- **AN AMBIGUOUS DISPATCH MEANS A NEW FORK.** When an instruction could mean
  "work the forks that exist" or "make new ones", it means new ones. Cloning is
  cheap; two workers in one tree is not recoverable by any amount of care
  afterwards. If new forks are clearly not what was meant, ASK — that question
  costs one turn and the collision costs a session.
- **Record the owner INSIDE the fork, in the seed commit**, not only in your own
  notes. A worker that can read who owns the tree it woke up in can stop before
  its first write. Without that, co-tenancy is discovered the expensive way —
  by a rejected write, a compile error from someone else's test file, or a
  rebase that rewrites your commits.
- **A worker that meets a file it did not write must NOT resolve it.** Preserve
  it, exclude it, and say in the report that its provenance is unprovable. That
  is the correct outcome, not a failure to finish.

## WHO the worker is changes the LIFT

**A Claude Code agent under these rules** produces a predictable shape: a
committed `FINAL_REPORT.md`, per-consumer CONSEQUENCES in the commit message,
enumerated forms for contended files, and an explicit account of what the brief
got wrong. A lift can lean on that structure and spend its effort on the claims.

**A third party — another LLM, another harness, or the user working by hand —
is bound by none of it, by construction.** Expect any of: no report, or one left
uncommitted; commit messages without a CONSEQUENCES beat; untracked files;
generated artifacts modified in passing; work abandoned mid-edit when the
session ends. None of that is misconduct — it is simply a different contract.

So for a third-party fork, **derive everything from the TREE and never from the
report's existence**:

- read `git log`, `git status --porcelain --untracked-files=all`, and the diff
  before forming any view of what was done;
- do not assume a commit boundary means "finished" — it may mean "the session
  ended here";
- re-derive the CONSEQUENCES beat yourself if the message lacks one, because the
  bump author downstream executes that beat verbatim;
- treat a modified generated artifact as **not part of the deliverable** unless
  the change explains it. Regenerate in the receiving checkout instead.

## Creating one

- **USE THE LIFECYCLE GATE.** Never clone a donation except through
  `tools/claude/forks.sh claim <path> <task-id> <owner> [brief-path]`; never
  `rm -rf` a fork except through `tools/claude/forks.sh release`, with
  `<path> --owner-signalled <signal>`. `claim` owns remote stripping, the seed
  commit and the manifest entry; `release` refuses uncommitted residue and
  preserves scratch proofs and commits before deletion. **It VERIFIES its
  bundle, it does not restore it** — so §"Preserving" below is still owed in
  full on any fork whose work has not already been lifted.
- **Do not arm the monitors there.** `git-behind` cannot fetch without a remote,
  so its silence is permanent and reads as coverage; `ci-watch` spends a shared
  per-IP budget on commits that checkout cannot have produced. Both scripts now
  refuse on their own preconditions and the nudge hook sources the same resolver
  (`.claude/rules/monitor-discipline.md`), so the refusal is honest rather than a
  nag loop — but an explicit `OWNER_REPO` override still bypasses it.
- Seed the brief as **its own commit**, so the worker's commits are exactly what
  gets lifted and nothing has to be untangled later.
- **WRITE THE ROSTER ENTRY AT THE HANDOVER, NOT AT WINDDOWN.** The harness task
  list DIES WITH THE SESSION. Between handing over a path and the next winddown,
  it may be the only record that a checkout is owned — and a crash, a restart or
  a `/clear` in that window leaves owned trees on disk with nothing durable
  saying who owns them, what they were given, or what they owe. The next session
  then finds them, and every cheap signal (clean status, idle process table,
  quiet mtime) reports them IDLE, which is exactly the state that gets a live
  worker's tree deleted. Record: path, owner, base sha, seed-commit sha, the
  files it owns, and what it CANNOT verify locally.
  Two surfaces, and they are not interchangeable — **this file is the procedure
  and survives a fresh clone; the roster is machine-local and survives only a
  reboot of this machine.** A fork whose existence is recorded nowhere but in a
  session is a fork about to be orphaned.
- **Never git-ignore the deliverable.** Excluding scratch inputs is right;
  putting the required report in `.git/info/exclude` silently drops the one
  artifact the lift reads. One worker caught it and used `git add -f`; a worker
  who does not notice hands back nothing.
  **This reaches the PROOF, not just the report.** A measurement whose probe
  sits in an ignored path leaves behind only its numbers, and **a number with no
  re-runnable probe is recall, not evidence.** Live example in this tree:
  `palette-audit.py` is gitignored and is cited BY PATH three times from tracked
  `tools/devcards/dev/disabled_pair_probe.clj`, underneath every contrast figure
  that workstream quotes. Track the probe next to the claim it supports.
- **Know which ROOT a script resolves — not merely which repo you are in.** A
  tool that computes its root from its own location (`dirname "$0"/../..`, which
  is how `tools/perf/bench.sh` and `tools/uber.sh` both work) silently retargets
  when copied. A probe copied into a scratch directory then runs against THAT
  directory, finds none of the inputs, and fails every leg for a reason
  unrelated to the perturbation under test — **and that red looks exactly like a
  caught defect.** Copy a tool only to a path whose root resolves the same way,
  or invoke the original with its inputs redirected.
- **Give each fork its OWN scratch subdirectory when more than one runs at a
  time.** The session scratchpad is SHARED, and this failure is silent rather
  than loud: a sibling truncating your log mid-run leaves you reading ANOTHER
  fork's test output as your own, which is indistinguishable from your checkout
  containing a file it does not have. Container and git isolation hold; the
  scratch path is what crosses. Say in the brief that siblings are running.
- **Signal only processes you have identified.** Verify `/proc/<pid>/cwd`, or
  for containers the `docker inspect` mount path, before stopping anything. A
  pattern-matched `pkill` has killed another session's monitors.
- A brief is a HYPOTHESIS about the fix. Ask the worker, in writing, to report
  what in it turned out to be wrong; that is reliably where the best work is.
  Ask also for the fact it could NOT get — a datum the work lacked will be
  lacking next time too, unless it lands somewhere durable.

## What makes a brief work — the measured part

A brief is the only thing a worker has. **A third party inherits NO rule of this
repo, and neither does a subagent at a submodule mount** — agents and skills
resolve from the PROJECT ROOT, so an unstated constraint does not merely go
unemphasised, it does not exist. Every constraint goes inline.

- **"If my framing is wrong or overstated, SAY SO — that is the most valuable
  thing you can return."** Put it in words that direct. This single instruction
  is what produces framing corrections, and they do not arrive without it. It
  has produced outright refusals of briefs whose execution would have made the
  repo worse.
- **WITHHOLD EXPECTED VALUES.** A brief that quotes the command AND its answer
  produces a canary that cannot go red — the worker writes the assertion to
  match the number you handed it, and you learn nothing. Give the command and
  the criterion; never the result.
- **NAME THE FORBIDDEN FILES EXPLICITLY.** Disjointness is what makes parallel
  forks safe, and a worker that does not know what it may not touch will touch
  it. Measured here: five forks, four of them ended up with two workers on one
  brief, and every one of the resulting collisions was in a file no brief had
  fenced.
- **READ-ONLY MEANS NO MUTATING COMMAND, and a forbidden-files list does not say
  that.** A worker told "read-only" can still `rm`, redirect into a tracked
  path, `git checkout`/`restore`/`reset`, or mutate a container — none of which
  any file list fences. Spell it out, and say what to do when a claim can only
  be settled by a mutation: report the limitation and the EXACT probe, never run
  it. Comparing two versions of a file is `git show A:path` against
  `git show B:path`, never restore-then-inspect.
- **DEMAND THE DISCRIMINATOR, NOT JUST THE RED.** "Prove the gate catches it"
  is satisfied by any red. Require: remove the neighbouring guard, keep the
  perturbation, and show a **FAIL, not an ERROR**. An ERROR is a broken
  harness wearing the right colour — the same false-gate failure
  `docs/UI-QUALITY-CONTRACTS.md` refuses everywhere else.
- **SCOPE THE TASK TO WHAT THE TREE CAN VERIFY**, or state plainly the
  prerequisite it cannot satisfy. A fork cannot run GitHub Actions and cannot
  reach a consumer's private corpus; a brief demanding either yields a report
  whose central claim is ARGUED rather than executed, and the lift must then
  know which half is which. Ask for that split explicitly.
- **NAME WHAT MUST BE TRUE IN THIS TREE FOR THE OUTPUT TO GO GREEN.** That is a
  different limit from the one above — SEQUENCING, not capability. A fork whose
  deliverable cannot pass until main-tree work lands burns a whole cycle to
  produce something red on arrival. Ask the question before dispatching; if the
  answer is "a fix that does not exist yet", do the fix first, or brief the fork
  to expect the red and PROVE it.
- **SAY THAT SIBLINGS ARE RUNNING**, and name this fork's own scratch path.

**A fork that REFUSES its brief still delivers.** The refusal, its evidence, and
the corrected framing are the record, and they lift like any other finding. Two
of this session's most useful fork outputs changed no code at all.

## "READY TO LIFT" IS A CLAIM ABOUT A WORKER, NOT ABOUT `git status`

Establish that the work has STOPPED before you read a line of it. Every cheap
signal here is a point sample, and an agent between tool calls looks exactly
like an agent that has finished.

- **A clean `git status` proves nothing.** Measured: a fork checked clean, and
  fifty seconds later carried seventy uncommitted insertions. The check landed
  between two writes.
- **A `/proc/<pid>/cwd` sweep proves nothing either, and this is the trap.**
  Tool-call writes are short-lived processes; between calls there is NO process
  to find, so the sweep reports idle for a fully active worker. It answers "is
  something writing this instant", never "is anyone working here".
- **A recent-mtime sweep proves nothing either.** A worker doing analysis writes
  nothing for minutes at a stretch, so `find -newermt` reports quiet for an agent
  that is simply reading. Measured: a fork passed clean-status, no-process AND
  no-recent-mtime, and was deleted out from under a live agent that had not yet
  written its commits.
- **THE ONLY SUFFICIENT SIGNAL IS THE OWNER SAYING IT IS DONE.** For an agent
  you dispatched, that is its COMPLETION NOTIFICATION arriving — not your
  impression that it looks finished. If you have not received it, the fork is
  still owned, whatever the filesystem says. For a third-party worker it is the
  person who runs it telling you, and a report committed in the tree is
  corroboration rather than proof.
- The cheap checks above are still worth running, but only to catch the case
  where something IS obviously live. **None of them can establish the negative**,
  and treating an absence as completion is what destroys work.
- Use the process sweep only for its real job: confirming nothing is running
  before you `rm -rf`, and identifying a process before you signal it.
- **If you find your own fork deleted, do NOT recreate it.** Preserve everything
  outside the `donate-*` glob so no tooling mistakes it for a fork, and report.
  A session that has just learned its model of the environment is wrong should
  not answer by writing a fresh checkout into shared state.

**The presence-aware byte-check diagnoses this for free.** A CONTENT MISMATCH
between a fork's worktree and its own HEAD means the WORKTREE IS DIRTY — a live
worker, or work abandoned mid-edit. It does not mean the bundle is bad. Check
`git -C <fork> status` before concluding the backup failed.

**WHEN THE COMPLETION NOTIFICATION NEVER COMES.** The law above says wait for
it, so it cannot tell you what to do about a worker that is genuinely dead. For
a dispatched subagent the one first-hand read is its transcript (the harness's
per-session `subagents/agent-*.jsonl`), and it is a POSITIVE-ONLY probe: growth
between two readings proves alive; no growth proves nothing, because a healthy
agent writes nothing for the whole of any tool call and a container battery here
runs for minutes. **Measured in this checkout, `stat` size/mtime and `wc -c`
agree exactly on every live sibling transcript** — neither probe is privileged
here, and a rule copied from a harness whose metadata IS stale must be
re-measured before it is trusted. Never `cat`/`tail` it to decide: it is full
JSONL sized by everything the agent has read, so the probe costs the parent more
context than the answer is worth.

Recovery is `TaskStop` and THEN re-dispatch — never a second worker on top of a
possibly-live one, which is § ONE FORK, ONE OWNER with the collision moved
in-conversation. And **`TaskStop` does not reap what the agent started**: it has
left an orphaned uber container here, safe to remove only by identifying it
first, per the signalling bullet above.

## Lifting

1. **Read the fork's report first**, especially where it says the brief was
   wrong.
2. **Verify the claimed proof rather than trusting it.** Re-run the canary or
   reproduction in the RECEIVING checkout, and assert the mutation landed
   (`grep -c` the new text, require non-zero) before believing any green.
3. **Antagonistic review of the diff AND the commit message**, with more than
   one lens — a claims-and-design lens plus a pure fact-check lens that verifies
   every checkable assertion against the tree. Nothing mechanical gates this.
4. **Walk the failure paths the change introduces**, not just its happy path. A
   shared helper that every caller sources needs its missing-helper case run
   once: the monitors must fail loud and decline, the hook must fail quiet and
   never block a prompt.
5. `git fetch <fork-path> master` **before** cherry-picking. `git fetch <path>
   <sha>` fails with `couldn't find remote ref` unless that sha is an advertised
   ref; fetching the branch makes the object local and the pick resolvable.
6. **CHECK THE MERGE SEAM — what does the lifted text now CONTRADICT?** A clean
   cherry-pick means the lines did not collide; it says nothing about whether
   the claims agree. This repo's docs and docstrings carry long prose arguments,
   so a lifted paragraph can land beside a sentence it silently refutes and
   both will be shipped to every consumer. Concretely: grep the RECEIVING file
   for statements on the same subject before you call the lift done. Measured
   this session — a premise retracted in one section stayed asserted in five
   other tracked homes, and the sweep to fix them was a separate task.
7. **Run in the RECEIVING checkout what the fork could not** — the container-only
   lanes, the gates, anything needing the pinned toolchain. And REGENERATE
   rather than lift any generated artifact: a fork's copy was produced by its
   own toolchain, which is not the pin.

## Preserving — this is where a backup silently fails

- Bundle against `BASE..master`, **never `..HEAD`**. A bundle cut against
  `..HEAD` carries no named ref: `git fetch` matches nothing, `git show` returns
  empty, and the "restored" file hashes to the sha256 of nothing.
- **A `BASE..master` bundle is THIN — it requires BASE, so `git clone <bundle>`
  CANNOT restore it** and fails with `remote transport reported error`. That
  failure is a property of the restore METHOD, not evidence about the bundle.
  The restore is `git fetch <bundle> master` into a repo that already has BASE,
  then read files with `git show FETCH_HEAD:<path>`.
- **`git bundle verify` is not a restore test.** Byte-compare every changed file
  against the live fork before deleting anything. A bundle that verifies and
  restores nothing is the same failure class as a gate that goes green on what
  it never judged.
- **Compare PRESENCE, not only content — a fork that DELETED a file breaks the
  naive check in both directions.** `git diff --name-only BASE..master` lists
  deleted paths too. Hashing "the file on each side" then reports a mismatch for
  a path that is correctly absent from both, which is a false alarm; and the
  obvious repair — skip paths missing locally — silently stops verifying
  deletions, so a bundle that failed to record one would pass. For each changed
  path assert `exists-in-fork == exists-in-bundle` FIRST, and compare hashes
  only when both exist. Use `git cat-file -e FETCH_HEAD:<path>` for the bundle
  side; `git show` piped to a hasher cannot distinguish "absent" from "empty".
- **A BUNDLE CARRIES COMMITS. IT CANNOT CARRY WHAT GIT DOES NOT TRACK**, and
  the verification will not tell you, because it only walks
  `git diff --name-only BASE..master`. Untracked files and uncommitted
  modifications are invisible to both. Measured: a competing implementation of
  a whole CI lane existed only as an untracked file; a verified bundle plus
  `rm -rf` would have destroyed it while every check reported green.
  Before deleting, run `git -C <fork> status --porcelain --untracked-files=all`
  and copy anything it lists into `fork-preserve/<name>-untracked/`. An empty
  listing is the only licence to skip that.
  **READ THE WHOLE PORCELAIN — `??` alone is NOT the sweep.** A STAGED file
  reports as `A `, so a grep for `??` walks straight past work that is committed
  to the index and to nothing else. Measured here: a staged file survived one
  sweep only because it was noticed by eye.
- Only after that byte-compare passes AND the untracked sweep is empty or
  copied: `forks.sh release` the fork, never a bare `rm -rf`.

## Churn while forks are live — and the one thing that must hold still

The session does not idle waiting on a fork. It works the largest task that does
not data-depend on a pending answer — "blocked" means a real data dependency,
not that the topic is adjacent. Two hard exceptions:

- **Never edit a file a live fork owns.** That is the collision the brief's
  forbidden-files list exists to prevent, applied to yourself.
- **NEVER EDIT THE TREE WHILE A BATTERY IS RUNNING.** The battery reads the
  working tree as it goes, so a mid-run edit produces a result that describes no
  commit that has ever existed. Measured here: a GREEN battery was produced
  while a rebase was checking out other commits underneath it. That green was
  meaningless and was very nearly believed. Let it finish, or re-run it on a
  settled tree — and treat any battery whose tree moved as UNRUN, never as
  passed.

## Know which repo you just acted on

Everything above operates on two repositories at once, which is the condition
under which a command silently lands in the wrong one.

- `git fetch <bundle> <ref>` writes FETCH_HEAD and moves no branch.
  `git checkout FETCH_HEAD` **detaches HEAD in whatever directory is current.**
- **A compound `cd "$DIR" && cmd_a || cmd_b` runs `cmd_b` in the CALLING
  directory when the `cd` fails**, and under `set -u` without `-e` nothing
  aborts — so a fallback written for the other tree executes against this one.
  Guard the `cd` on its own (`cd "$DIR" || exit 1`); never chain a fallback onto
  it.
- Prefer `git -C <repo>` over `cd`. Where a bare `git` is unavoidable, print
  `git symbolic-ref HEAD` and `git rev-parse --short refs/heads/master`
  afterwards and read them.
- A detached HEAD leaves the branch ref intact, so recovery is
  `git checkout master` and nothing is lost — but only if you look. Check the
  branch ref, not `git log -1`, which reports HEAD.

The shorthand: **a CLONE never a worktree; one fork one owner; strip and verify
the remote; brief inline, forbidden files named, expected values withheld; prove
the worker STOPPED before reading the work and STOP it before re-dispatching;
lift on re-run evidence, not on the report, and check what the lift CONTRADICTS;
read the whole porcelain and copy
the untracked before you trust the bundle; restore by FETCH into a repo holding
the base and byte-compare before deleting; and name the repo in every command
that spans two.**
