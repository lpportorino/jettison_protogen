# View evidence mutation campaign

The manual campaign at [gate.view.campaign](../test/gate/view/campaign.clj)
reuses `gate.admission-campaign` and its fresh-JVM mutation runner. It targets
view-model and activity-candidate behavior independently of browser rendering.
All fixtures and selected tests are synthetic.

From the repository root, run through the official container:

```sh
tools/uber.sh "cd tools/gate-graph && clojure -Sdeps '{:paths [\"src\" \"resources\" \"test\"] :deps {org.clojure/test.check {:mvn/version \"1.1.3\"}}}' -M -m gate.view.campaign ../../.fork-scratch/viewer-mutations"
```

The harness freezes source, tests, dependency declarations and resources into a
disposable module copy. Each fault must replace exactly one anchor and record
different original/mutant hashes. Every fresh worker has a 180-second limit.
The selected target and independent control must each execute one nonempty test
with zero errors. Only target assertion failures with a passing control count
as a kill. Compilation errors, crashes, timeouts, missing/empty tests and control
failures are invalid evidence. Full passing instrumented suites are required
before and after; the original and restored-copy fingerprints must match.

## Selected behavior changes

| Fault | Target behavior | Independent control |
| --- | --- | --- |
| `fold-occupied-union-skipped` | Fold retains the occupied union rather than raw overlapping members. | Point-window boundaries |
| `right-window-boundary-included` | Half-open windows exclude their right boundary. | Fold gaps and overlap |
| `incident-edge-needs-both-members` | Incident edge pages include edges crossing the selected member boundary. | Fold gaps and overlap |
| `foreign-artifact-cursor-accepted` | A continuation cannot silently move to a changed artifact. | Neighborhood nodes |
| `partial-sample-treated-as-measured` | A partial baseline cannot establish a measured activity change. | Burst and counter refusal |
| `sampling-gap-ignored` | An unknown gap cannot support a continuous candidate interval. | Burst and counter refusal |
| `counter-treated-as-rate` | Raw counters are not validated interval rates. | Metric-family labels |
| `zero-baseline-ratio-accepted` | A zero baseline cannot establish the declared ratio change. | Sustained dip evidence |
| `pressure-mislabeled-as-activity` | Pressure and activity remain distinct metric meanings. | Sustained dip evidence |
| `memory-change-mislabeled-as-activity` | Occupancy changes remain footprint changes. | Sustained dip evidence |

These faults are selected examples, not exhaustive mutation coverage or proof
of browser correctness. Detection remains the declared observation-weighted
ratio heuristic, with no causal attribution or recoverable-time estimate.

## Recorded result

The official-container final-source run on 2026-10-08 passed:

- Initial full control: **316 tests, 16,896 assertions, zero failures/errors**.
- Selected mutants: **10 assertion-killed, zero survived, zero invalid**.
- Every mutant executed one target and one independent control. All controls
  passed, all workers exited normally, and no target or control had an error.
  The fold-union fault caused two assertion failures; each other fault caused
  one. Thus all ten kills are attributed to behavioral assertion failures.
- Restored full control: **316 tests, 16,896 assertions, zero failures/errors**.
- All **131** source/test/dependency/resource fingerprints matched the restored
  copy and original census. Runtime: Clojure 1.12.0, Java 25.0.2 in the
  GraalVM Community toolchain; the reported VM name is `OpenJDK 64-Bit Server VM`.

Execution used `tools/uber.sh` with this checkout mounted at `/workspace`, on
`linux/amd64`. The existing official image's immutable ID was
`sha256:2fffd4b35684499c30d54406e12ad931ddd856dfd62506267117f747b27edad9`.
Image identity, `java -version` output, and SHA-256 fingerprints of
`Dockerfile.base`, `tools/uber.sh` and the ignored invocation script are retained
beside the raw campaign directories. The tag alone is not a toolchain pin.

Judged production SHA-256:

| Source | Digest |
| --- | --- |
| `src/gate/view/model.cljc` | `540a9869d0fa9f9044fb792e4283873086b9f6b605d831825dad35e964c87d77` |
| `src/gate/view/opportunity.cljc` | `f5acb16f48772f1a26682a550a8a4e926ce384d9f6a47eea80be31e065faabba` |
| `src/gate/report.clj` | `43dd8fc2e9254978ccfd1deaa6dc34765deed2aec226e6c6ac2fa7a1007b7fb6` |
| `src/gate/viewer.cljs` | `658d4f87815029263e81e3a526f2577d5cc6a60decb1c1a286bc16bf32a53931` |
| `resources/gate/viewer/manifest.edn` | `5a7e94bdac18e10475b11eed9f83c2ae225be52333129cbf5c26a1506107050a` |
| `resources/gate/viewer/main.js` | `8552f7957e69bbd9005df0bea61958dffc78275525b99ed93770aa172aadbc31` |

Raw logs, worker counters and fingerprint evidence remain in the ignored
`.fork-scratch/viewer-mutations/viewer-delivery-13940316585900253079` directory.
The raw `report.edn` SHA-256 is
`28973eb5d438d51bc88afdaf49edb160cc791076952835ddcf6fd0d03d2d2adc`.
Earlier passing runs were superseded after visual-review fixes changed report
layout and the viewer bundle. Later repeats covered a viewer-source syntax
repair for the function-size reader and its updated manifest, followed by a
CSS-only label/arrow layering correction in `report.clj`. Those changes left
the compiled JavaScript byte-identical. The final run repeated both full
controls and all selected mutations, binding every result above to one exact
source census rather than combining results from different revisions.
Those earlier campaigns ran on the host's OpenJDK 27 and did not satisfy the
official-container requirement. The accepted result above supersedes them:
both full controls and all ten selected faults were rerun inside the official
container, with no production changes.
