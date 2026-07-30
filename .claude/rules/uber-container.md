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
image ships no docker CLI; those stay on the host.

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

## Shared with CI, not a fork of it
`renderer.yml` runs the same battery in this same base image, and the consumer
repos' proto build is unaffected. This helper is a LOCAL entry point onto that
shared image — not a parallel flow. Fix the image itself (e.g. its ENTRYPOINT
contract) in `Dockerfile.base` so CI and local stay in step.
