# Isolated forks — strip the remote, lift by evidence, verify the restore

Work that needs its own tree runs in a clone of this repo with its **remote
REMOVED**. protogen is the pinned upstream for a consumer fleet and CI fans out
on any push to `master`, so the strip is not tidiness: with no remote configured
a push cannot resolve a destination at all, which is the only thing that makes a
mistake unable to reach the fleet.

## Creating one

- Clone, `git remote remove origin`, and **verify both ways**: `git remote -v`
  prints nothing AND `git push` answers `fatal: No configured push destination.`
- **Do not arm the monitors there.** `git-behind` cannot fetch without a remote,
  so its silence is permanent and reads as coverage; `ci-watch` spends a shared
  per-IP budget on commits that checkout cannot have produced. Both scripts now
  refuse on their own preconditions and the nudge hook sources the same resolver
  (`.claude/rules/monitor-discipline.md`), so the refusal is honest rather than a
  nag loop — but an explicit `OWNER_REPO` override still bypasses it.
- Seed the brief as **its own commit**, so the worker's commits are exactly what
  gets lifted and nothing has to be untangled later.
- **Never git-ignore the deliverable.** Excluding scratch inputs is right;
  putting the required report in `.git/info/exclude` silently drops the one
  artifact the lift reads. One worker caught it and used `git add -f`; a worker
  who does not notice hands back nothing.
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
- Only after that byte-compare passes: `rm -rf` the fork.

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

The shorthand: **strip and verify the remote; lift on re-run evidence, not on
the report; restore by FETCH into a repo holding the base and byte-compare
before deleting; and name the repo in every command that spans two.**
