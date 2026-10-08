# Integrated viewer and API acceptance

The offline timeline viewer is combined with the current runner cancellation
fixes and the API identity/adoption algebra. Graph and archive formats remain
unchanged. Viewer requests and API records extend canonical value admission;
an empty candidate vector is a valid `:value`, not a valid graph or request.

The API constructor validates supplied declarations and explicit adoption
decisions. It does not discover all exports, prove source-inventory completeness,
generate contract fingerprints, enforce a committed manifest or establish
semantic compatibility. Those integration steps remain pending. See
[API identity and adoption](api.md) and [viewer contracts](viewer-api-review.md).

## Rebuilt source and checks

The merged viewer binds 30 CLJC/CLJS inputs. Its release processed 79 files with
zero compiler warnings. JavaScript SHA-256:
`6a26404e001fc5de10ac389b243ff55c0d3b44a120452534a0a64d7594bd2d85`.
Packaged manifest SHA-256:
`624e8f7bb04b540b8046d869760ccda81b55187b56306f8219f5bf3749bf2c91`.

- The complete module suite passed 329 tests and 19,028 assertions before and
  after 23 selected API/view mutations. All faults produced attributed assertion
  failures with passing controls, no error/timeout kills, and an unchanged
  135-file source/test/dependency/resource census. This census preceded the
  addition of the standalone portable adoption corpus below.
- The receiving checkout independently passed the same complete module suite
  and the normal enrollment canary, which rejected a vacuous success.
- Viewer parity passed 79 assertions each on JVM, unoptimized Node and advanced
  Node, with identical canonical output SHA-256
  `722db54b7a61d4ad4c3e9f4000f2a2ad3d9263631f3ed437cdcd4a11dcded742`.
- The standalone adoption corpus matched all 15 canonical results/refusals on
  those three runtimes, including a legal oversized inventory. Output SHA-256:
  `6fe910817412880c29a32b9e39f8653a796f57618e4eb784183b622da0d5dda9`.
  Replay with `bash browser/adoption-parity.sh`; semantic assertions live in
  `gate.api-test`, since equality alone does not establish correctness.
- Source/resource JAR delivery produced all seven synthetic HTML fixtures
  byte-identically to the source-classpath browser inputs. No sibling asset or
  source-directory classpath was required.

The rebuilt bundle passed 24 Chromium and 24 WebKit fixed-viewport cases with
zero external requests or browser errors. All 76 Chromium images are covered
by actual inspection or verified identical-image reuse; all 72 WebKit images
were opened for review. Four WebKit active-rotation states remain unaccepted.
See the [integrated visual evidence](viewer-integrated-acceptance.md) for exact
HTML/image identities, desktop and iPhone emulation, and review methods.

Run `make -f lint.mk gate-graph-test` at the repository root for the normal
module suite and enrollment canary. Viewer parity and packaged delivery are
reproducible with `browser/api-parity.sh` and `browser/delivery.sh` from this
module. The scoped mutation entrypoints are `gate.api-campaign` and
`gate.view.campaign`; their source contains exact fault clauses and controls.

The original [viewer visual record](viewer-visual-review.md) and performance
records describe the earlier viewer-only bundle. They are historical evidence,
not benchmark measurements of this combined build. Consumer owners must adopt
the integrated source, regenerate HTML and accept their own actual archives;
passing synthetic fixtures cannot certify a consumer's full CI coverage.

Real background/objective telemetry acquisition, certified clock joins,
complete cache read sets and richer cross-run performance comparisons remain
separate work. The viewer never infers missing CPU time, pressure or readiness
from elapsed bars.
