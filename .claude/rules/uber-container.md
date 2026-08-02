<!-- LOAD-TEST: uber-container -->

# Builds & generation run in the pinned toolchain — never the host

Every operation that PRODUCES a committed or gate-checked artifact runs on the
PINNED toolchain, never the host's: binding generation, the renderer/wasm build,
the devcards render + golden mint + JPEG gallery, docs generation, and the test
suites.

## The ergonomic local entry point
`tools/uber.sh` runs any command inside the "uber" container built from
`Dockerfile.base` — the one image that carries EVERY pinned toolchain (WASI-SDK
clang, GraalVM Community JDK + Clojure, protoc + plugins). It pins docker
`--platform` to the host's native arch; CI parity comes from the pinned
toolchain versions (below), not the platform flag.

```
tools/uber.sh 'make -f renderer.mk check-renderer'    # full renderer proof battery
tools/uber.sh 'make -f renderer.mk gallery-prebuilt'  # devcards JPEG gallery
tools/uber.sh --check                                 # build AND verify it executes
```

**A build that needs a new tool ADDS IT TO `Dockerfile.base`, pinned, in the
same change — and rebuilds the image in that change too.** `uber.sh` is
`present || build`: it reuses whatever base image already exists and never
notices `Dockerfile.base` moved, so a pin bump silently leaves you gating on the
OLD toolchain while CI (a `docker build` per job, on a fresh runner) uses the
new one. `tools/uber.sh --build` is what closes it; nothing else will.

Run the toolchain directly (`make -f renderer.mk …`, `make docs-generate`,
`clojure`, `cargo`, `protoc`). Targets that themselves orchestrate docker
(`make generate`, `docs-docker-*`, `binary-dedup`) can't run in here — the base
image ships no docker CLI; those stay on the host, and so does
`tools/scratchcard/bin/scratchcard`: its whole job is spawning a second
container from the host, on the invocation path described below.

Do NOT run `clojure` / `javac` / `protoc` / `make` on the host to produce a
committed artifact — the host's toolchain versions (JDK, WASI-SDK, protoc) differ
from the pins, so the output diverges from CI and from what consumers vendor.
Concretely: the host JDK's `javax.imageio` encoder rewrites every committed
gallery JPEG byte-for-byte. Pure host-side work (git, grep, reading files,
editing source) produces no artifact and is fine.

## What a container run reproduces — everything, JPEGs included
CI (`.github/workflows/renderer.yml`) splits for speed: the wasm build runs in
the uber container (it needs the WASI-SDK); `fixtures-prebuilt` /
`gallery-prebuilt` run on a plain runner with `setup-graalvm` consuming the
prebuilt wasm. Both legs pin the same toolchain versions (the uber image bundles
that same GraalVM), so a run IN THE CONTAINER reproduces CI byte-for-byte across
all three artifact classes: goldens (raw framebuffer sha256), generated doc
TEXT, **and the gallery JPEGs**.

The proof is CI's own freshness step, and it is NOT a battery lane: after the
containerised regen, `renderer.yml` and `devcards.yml` run
`git diff --exit-code tools/devcards/goldens tools/devcards/docs` on the
RUNNER, and `check-renderer`'s `fixtures` lane covers only HALF of what that
diff spans.

**WHY IT IS ON THE RUNNER IS NOW AN INVOCATION-PATH FACT, not a flat
impossibility.** git refuses a container-mounted worktree as dubiously owned
because the container runs as root over files owned by the invoking user.
`tools/uber.sh` DECLARES `safe.directory` for the workspace, so git works
normally under it — which is what makes `dead-c-externs-test`, and therefore
`check-renderer`, runnable locally.

**IT IS LOAD-BEARING ON THE STANDALONE SHAPE AND INERT ON THE SUBMODULE ONE,
and the reason is not the one the shapes suggest.** Measured in the pinned
image, container uid 0 over files at uid 1000, four runs differing in one
variable each:

| shape | `GIT_DIR` | `safe.directory` | `git ls-files` |
|---|---|---|---|
| standalone | unset | none | `fatal: detected dubious ownership`, rc 128 |
| standalone | unset | `$WORKSPACE` | 54 files, rc 0 |
| standalone | set explicitly | none | 54 files, rc 0 |
| submodule | `/gitdir` | none | 54 files, rc 0 |

The third row is the one that explains the rest: naming `GIT_DIR` does not add a
second checked path, it takes git off the check entirely. `ensure_valid_ownership`
is called only from the DISCOVERY walk; an explicit `GIT_DIR` takes
`setup_explicit_git_dir` and never validates. So the gitfile branch — which sets
`GIT_DIR=/gitdir` — was never at risk, and the declaration that rescues the
standalone path does nothing there. `/gitdir` IS checkable in principle: mounted
and discovered as a bare repo it refuses by name. It is simply never reached.

**Do not "harden" this with a second `safe.directory` entry for `/gitdir`.** It
is inert by measurement, and no input can make the check fire on the explicit-
`GIT_DIR` path — so nothing in this repo could ever prove the line does
anything, which is a claim wearing the shape of a guard.

And note which way the standalone failure went: `git ls-files` printed ZERO and
exited ZERO under the refusal. A lane discovering its corpus that way reports a
clean run over nothing, which is why every such lane owes the non-vacuity floor
`gate-enforcement.md` §3 demands.

**THE SHAPE THAT DOES REFUSE IS THE LINKED WORKTREE, for a different reason and
by design.** Its private gitdir holds no objects and its common dir is a host
path, so `uber.sh` declines to mount it (the `GITDIR = COMMONDIR` test) and
leaves git unavailable rather than half-supporting it. Lanes then refuse
correctly — `dead_c_externs.sh` exits 3 naming the cause. What did NOT refuse
correctly was its canary SUITE, which re-resolved its own root unguarded and so
emitted FAILs from clauses that had never run; it now refuses up front with its
own CANNOT RUN, which is the shape any suite with a git precondition owes.

**CI IS NOT BLOCKED BY THIS EITHER**, and it is worth stating because the
obvious inference is wrong: `renderer.yml`'s shellcheck lane already passes the
same GIT_CONFIG_* env to a raw `docker run`, with its own measurement recorded
beside it. So a "git cannot resolve the checkout in the container" claim in this
repo is scoped to an invocation that has not declared safe.directory — never to
a capability. `standard-brief`'s freshness half and CI's goldens/docs diff are
consequently ARMABLE on both paths; they stay unarmed as a decision, and this
sentence exists so the gap is a decision rather than a stale belief.

Write the boundary of what the battery DOES assert, because it moved: the
`fixtures` lane now READS each committed `goldens/manifest-*.edn`
before the mint overwrites it and fails on any drifted, missing or new card
(`gates/golden-drift-findings`), so a green in-container battery DOES assert the
goldens are fresh. It asserts nothing about `tools/devcards/docs` — the JPEG
contact sheets and generated pages come from the separate `gallery` mode and are
still mint-only, and `check-renderer` does not even list `gallery-prebuilt`. So
the runner diff stays load-bearing, for the DOCS half. What makes the claim above
true is the measurement: a full gallery re-mint from this container passed that
CI step unchanged.

So a pixel-shifting renderer or corpus change re-mints goldens AND the gallery
locally in the pinned container, and commits both in the same change — the
devcards rule's "re-mint both together" is a thing you can actually satisfy.

The HOST is what diverges. Its JDK's `javax.imageio` encoder rewrites every
gallery JPEG byte-for-byte, so a host-side gallery run produces sheets CI will
reject — every one of them. That divergence is the reason for the container
requirement above, and it is a property of the HOST toolchain — not of JPEG
encoding as such.

## Running the base image directly
`Dockerfile.base` sets no ENTRYPOINT: `docker run <base> <cmd>` runs `<cmd>`
directly — exactly how `renderer.yml` invokes it (`docker run <base> make -f
renderer.mk …`). A bare `docker run <base>` still opens a shell (ubuntu's default
`CMD`). `tools/uber.sh` passes a `-lc "…"` script string, so it names the shell
explicitly via `--entrypoint bash`. The MAIN image (`Dockerfile`) keeps its own
bash ENTRYPOINT for `generate-protos.sh`'s `-c` flow — leave that one in place.

## A second invoker of the same image: `tools/scratchcard`

`tools/scratchcard/bin/scratchcard.bb` runs its own `docker run` — it does not
go through `tools/uber.sh`, and the docker-orchestrating exclusion just above
is exactly why: the daemon's whole purpose is spawning a container from the
host, so it cannot itself run inside one. It targets the SAME image
(`jettison-proto-generator-base:latest`, overridable by `PROTOGEN_IMAGE_TAG`,
the identical default `uber.sh` hardcodes as `IMG`) with the SAME
`--entrypoint bash … -lc "…"` convention this file describes above — a
property of `Dockerfile.base` having no ENTRYPOINT, not of `uber.sh`. Past
that the two invocations diverge on purpose, because a long-lived daemon and a
one-shot command want different things from the same image:

- **It runs `--user <uid>:<gid>` — the calling user, not root.** `uber.sh`
  runs as root (the image's Maven/Clojure caches live under `/root`) and
  chowns the workspace back when the command exits; a daemon that stays up for
  a whole session has no such exit to hang a chown off, so it runs as the
  caller directly and points `HOME` at a per-fork `.protogen/home` instead.
  There is no chown-back anywhere in `scratchcard.bb`, because running as the
  caller leaves nothing for one to repair.
- **It declares none of the `GIT_DIR` / `safe.directory` handling above, and
  does not need to.** `scratchcard.provenance/git-stamp` runs `git` from
  inside the container against the same mounted checkout, and the ownership
  check this file spends most of its length on never fires there — not
  because `scratchcard.bb` reimplements the workaround, but because that check
  exists to catch a container UID that does not own the files it reads, and
  here the container's UID IS the host's. Do not "port" `GIT_CONFIG_*` into
  `scratchcard.bb`; there is nothing there for it to fix.
  One gap the identity mount below does NOT close: it carries only the
  checkout root, so a `.git` gitfile pointing outside that root resolves to
  nothing in-container. `uber.sh`'s `GIT_MOUNT` serves the SUBMODULE half of
  that shape and refuses the linked-worktree half by design — the
  `GITDIR = COMMONDIR` test above, which a linked worktree fails because its
  common dir is elsewhere. `scratchcard.bb` has no equivalent either way:
  `scratchcard.provenance` degrades those fields to absent rather than failing
  loud, by its own design (its docstring: "DEGRADES RATHER THAN THROWS").
- **It mounts the repo under BOTH `/workspace` and its own host path**
  (`-v <repo>:/workspace -v <repo>:<repo>`), not `uber.sh`'s single
  `$WORKSPACE` alias, so a client speaking host paths and a daemon speaking
  container paths need no translation between them — see
  `.claude/rules/scratch-devcard.md` for why.
- **It passes no `--platform`.** `uber.sh` detects the host arch and pins it
  on every `docker run`, specifically to catch a buildx default that would
  otherwise cross-build or cross-run a binary that "cannot execute";
  `scratchcard.bb`'s `docker run` carries no equivalent flag, so that failure
  mode is unguarded on this path.

None of this forks the IMAGE — same tag, same `Dockerfile.base`, same pins.
What forks is the INVOCATION, and each divergence above answers a question
this file already asks about `uber.sh`'s own invocation, with the opposite
answer for a daemon's needs rather than a one-shot command's. What still goes
through `uber.sh`, unchanged: the targets that GATE the tool sit in
`check-renderer-lanes` like every other lane and run inside the same container
battery this file describes throughout. Only the DAEMON drives docker itself —
and that half is exercised by `scratchcard-e2e`, which is deliberately in no
aggregate and no workflow because it needs a docker CLI the toolchain image does
not carry. So "no aggregate reaches the daemon" is the accurate claim; "nothing
tests it" is not (`renderer.mk`'s own comment beside `scratchcard-lane-suite`
records the same boundary from the Makefile side).

## Shared with CI, not a fork of it
`renderer.yml` runs the same battery in this same base image, and the consumer
repos' proto build is unaffected. This helper is a LOCAL entry point onto that
shared image — not a parallel flow. Fix the image itself (e.g. its ENTRYPOINT
contract) in `Dockerfile.base` so CI and local stay in step.
