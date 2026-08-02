---
description: The scratch-devcard warm render daemon — per-fork scoping, the AF_UNIX transport, staleness, and the recovery ladder. Loads when editing tools/scratchcard.
paths:
  - "tools/scratchcard/**"
---
<!-- LOAD-TEST: scratch-devcard -->

# scratch-devcard — a hot render surface for a non-human author

`tools/scratchcard` renders an authored screen across the theme/resolution
matrix, judges it with THIS repo's own quality lanes, and writes a diffable run
directory. Its reader is a model, not a person.

**IT GATES NOTHING ABOUT A SCREEN'S CONTENT.** `check-renderer` is the gate.
This tool tells an author what their screen does; two of its own lanes
(`scratchcard-test`, `scratchcard-lane`) are gates over the TOOL, not over
anything a user writes with it.

**ITS OUTPUT IS GITIGNORED BY CONTRACT.** Everything lands under `.protogen/`.
That is not tidiness: this repo's battery READS THE WORKING TREE as it runs, so
a daemon writing into tracked space while a battery ran would produce a result
describing no commit that ever existed. **Do not relocate output into the tree.**

## Per-fork scoping — the key, and why it is that key

```
worktree-hash = sha256(git rev-parse --show-toplevel)[0:16]
container       protogen-render-<hash>
runtime dir     $XDG_RUNTIME_DIR/protogen/<hash>/   mode 0700
socket          <runtime>/render.sock
lock            <runtime>/render.lock
output          <repo>/.protogen/scratch/           hash-free — the path IS the worktree
TCP ports       NONE
```

- Hash the OUTPUT of `git rev-parse --show-toplevel`, never a raw cwd — a
  symlinked checkout otherwise hashes differently depending on the path the
  caller arrived by, and one worktree acquires two identities.
- **Not a git sha** (it churns every commit and would orphan the running
  daemon). **Not the basename** (two forks both named `jettison_protogen`
  collide, which is the case this exists to prevent).
- **NO TCP PORTS, EVER.** Isolation is by hash-keyed paths, so multi-clone and
  git-worktree setups auto-isolate with no port-allocation contract.
- `$XDG_RUNTIME_DIR` rather than a repo-relative socket because AF_UNIX bounds
  the path by `sun_path`; a deeply-nested worktree would silently exceed it.
  `scope/check-socket-path!` refuses up front rather than letting `bind` fail
  obscurely.

**TWO AUTHORITIES COMPUTE THIS AND THEY MUST AGREE:** `scratchcard.scope` (in
the JVM) and `bin/scratchcard.bb` (the client). The CLIENT owns the socket path
and passes it by env; the daemon never recomputes it, because every fork's
container sees the same workspace mount and an in-container derivation would
collide across forks.

## The repo is mounted under BOTH path views

`-v <repo>:/workspace` **and** `-v <repo>:<repo>`. This removes path
translation entirely — a caller on the host passes host paths, a caller inside
passes container paths, and neither needs to know which side it is on.

Found by failing without it: the first socket round trip returned *"No such
file or directory"* for a perfectly good screen. A translating client would
instead have one such bug per path it forgot to rewrite, with the cause
invisible from the message.

## Renders are PARALLEL here — do not add an ops lock

A GraalWasm `Context` is its own module instance with its own linear memory, so
LVGL being single-threaded is a **per-instance** property. Separate contexts on
separate threads over the shared engine is what `devcards.docs/generate!`
already does for the committed gallery.

The sibling fleet's GPU worker serialises every op because a GPU is one
resource. **A renderer is not**, and copying that lock would serialise a matrix
that currently renders concurrently, for no correctness gain. This divergence
is deliberate; do not "restore" it for symmetry.

## Recovery ladder — each rung reachable without the one above

| condition | outcome |
|---|---|
| wedged cell | the per-cell deadline in `run/default-cell-timeout-ms` fails THAT cell |
| wedged whole request | client `DAEMON_TIMEOUT`. NOTE there is no whole-request server ceiling, so on a low-core host a long matrix can outlast the client cap — the client's error is then the only one, and it is the poorer of the two |
| wedged daemon | `scratchcard restart` discards it — disposable by design |
| dead daemon | the next call's connect-probe respawns it inside the flock |
| OOM | `ExitOnOutOfMemoryError` + `--rm` ⇒ container vanishes; next call respawns |
| trapping render | fresh context per cell — that cell fails, the rest land |

Detection is LAZY: every call begins with a connect probe. There is no
supervisor and no restart policy, deliberately.

**A DAEMON RUNNING STALE CODE REPORTS ITSELF, and never auto-restarts.** It
digests every `.clj` under the trees it executes (`scratchcard/src` and
`devcards/src` — devcards supplies the render host and the armed lanes) at
boot, and compares on every `status` AND on every `regenerate` response. The
regenerate half is the load-bearing one: a warning a caller has to ASK for is a
warning they will not see, and the moment it matters is the moment they are
reading a result produced by the old code and concluding their edit did
nothing.

Measured, and it is why this exists: a path-traversal fix was verified against
a warm daemon still running the pre-fix code. The traversal succeeded and wrote
a run directory outside the scratch root, and the only symptom was a security
fix appearing not to work.

It REPORTS rather than recovers. Silent self-recovery would hide the very fact
the operator needs — that their edit was not in effect.

**A STALE SOCKET IS DETECTED BY CONNECTING, never by `exists?`.** That is the
only correct staleness test for a unix socket — a leftover file from a killed
daemon is indistinguishable from a live one by any filesystem property. A live
peer is REFUSED; a dead one is unlinked.

**The `--rm` reaper race is real.** `docker rm -f` returns before docker frees
the NAME, so an immediate `docker run --name` hits *"Conflict: name already in
use"*. The client polls `docker ps -aq` name-anchored until it clears.

## babashka is vendored, pinned, and NOT committed

A large statically-linked, **platform-specific** binary. The sibling repos
fetch x86-64 and aarch64 builds, so committing one would pin this repo to a
single architecture — defeating portability rather than helping it — and would
put the binary into the history of a submodule ten consumer repos clone.
`bin/ensure-bb.sh` fetches the pin into gitignored `.protogen/bin`.

The pin matches the version the siblings predominantly use. A floating version
would let two forks differ silently and leave a client regression with no
bisect.

**The installer itself is fetched from a moving ref and is not checksummed** —
only the resulting `--version` is checked. That is a real gap, recorded here
rather than left implicit.

## What the lanes judge, and what they cannot

The tool runs `devcards.lanes/atomic-producers` verbatim plus the `:emission`
builtin (NOT `armed-producers`, which also carries the by-mode emission
producer this tool does not arm). `:layers`, `:palette` and `:border` are
DECLINED and named in every report, so silence is never mistakable for
coverage.

**No report may imply readability, contrast or legibility under any lighting or
panel condition.** Those are properties of a PANEL and an OPERATOR, governed
upstream and measured at a bench. This repo has never held them, and a pass
message implying one is the over-claim `docs/UI-QUALITY-CONTRACTS.md` §0
forbids.
