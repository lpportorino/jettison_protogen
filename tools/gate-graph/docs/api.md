# API identity and consumer adoption

`gate.api-contract` defines closed, portable records for public API manifests
and consumer acceptance. `gate.api` supplies the pure identity, upgrade and
adoption checks. This layer is implemented independently of a source-discovery
adapter. **Automatic inventory generation, committed manifest freshness and
consumer CLI integration are still pending.** Supplying an incomplete inventory
to a pure constructor does not establish export completeness.

An inventory includes operations, schemas and other public values. Operations
declare whether they are bounded tools or trusted library helpers, their target
platforms, capabilities, declaration identity and argument/result contract
fingerprints. Function and macro invocation are distinct. Every entry has a
qualified identity, including ordinary punctuation such as `!` and `?`.

The reserved `:source-closure-v1` profile is conservative source identity. Its
discovery adapter must bind contract expressions and their source dependencies;
runtime function-object printers are unsuitable fingerprints. This document
does not establish that adapter or a portable semantic-schema hash algorithm.
Fingerprints can show change; they do not prove semantic compatibility.

## Pure checks

`manifest` takes `Material`, rejects duplicate identities/platforms/capabilities,
sorts set-like fields and returns a canonical SHA-256-bound `Manifest`.
`verify!` rejects noncanonical ordering and content/digest disagreement.
`verify-upgrade!` compares independently retained manifests: changed export
declarations require a greater API version, and versions cannot regress.
Source-only changes still change artifact identity and require consumer ACK.

`check!` takes a closed `Request`:

```clojure
{:manifest shared-manifest
 :acceptance consumer-record
 :previous previous-committed-record ; nil only on first adoption
 :surface observed-consumer-source-digest
 :exports ["example.toolbox/drill"]}
```

The consumer record binds the complete manifest, consumer source fingerprint
and actual wrapper inventory. Every `:tool` operation requires exactly one
`:adopt` or `:defer` decision with a nonblank reason. Adoption names existing
wrappers; deferral names none. Every observed wrapper must be covered by an
adoption. Several operations may use the same dispatcher. Trusted helpers,
schemas and constants still participate in the whole-manifest ACK.

A changed manifest, source, decision or wrapper inventory requires increasing
the acceptance or consumer API version. Changed wrapper exports specifically
require increasing the consumer API version. Neither version may decrease.
Initial versions are both 1. A changed decision needs ACK even when the wrapper
inventory stays identical. Version increments do not make stale observations
or missing decisions valid.

The response lists accepted manifest identity, versions and adopted/deferred
operation IDs. Refusals contain only `:code` and `:phase`, conforming to
`Failure`; instrumentation supplies the existing compact contract diagnostics
for malformed function inputs. Canonical records can be read through the
bounded `:value` admission target. Raw records are never executable queries.

## Integration obligations

Acquire source/files with explicit bounds. Independently discover the actual
public inventory and consumer exports; never use the acceptance record itself
as the observation. Refuse unavailable history instead of passing nil to reset
versioning. Run these checks on the actual gate/CLI path and retain reviewed
evidence for wrapper behavior: declaration membership alone cannot prove that
a wrapper performs the operation it claims to adopt.

Collections are finite: at most 4096 exports/decisions, 256 wrappers per
adoption, 32 capabilities per entry and 16 MiB of canonical manifest material.
A schema-valid collection can still exceed the byte budget; construction,
verification and adoption return closed `:api-budget`/`:manifest` failures.
These are whole-record checks, not pageable LLM inventory inspection yet.

Run `bash browser/adoption-parity.sh` from this module in the pinned toolchain
to compare fifteen complete canonical results/refusals on JVM, unoptimized
Node and advanced Node. The public synthetic corpus includes Unicode and a
schema-valid oversized inventory. Differential equality complements the JVM
semantic assertions; it is not exhaustive equivalence or inventory discovery.

The synthetic tests cover declaration and consumer versioning,
decision/observation drift, duplicate and forged inventories, bounded closed
schemas, canonical admission roundtrips and a seeded Malli-generated source
change property. The manual campaign exercises thirteen behavioral faults with
attributed failing assertions, passing neighboring controls and full initial
and final suite baselines:

```sh
clojure -M:test:api-campaign /absolute/path/to/fresh-campaign-parent
```

Run from the module directory with a freshly built viewer. The campaign binds
source, tests, dependency declarations and packaged resources; do not edit those
inputs during execution. Source acquisition, automatic completeness, CLJS
parity and real consumer adoption remain separate obligations.

[Passive source observations](api-source.md) now provide bounded CLJ/CLJS
declaration and registration evidence. They do not establish complete exports
or replace independent runtime/analyzer reconciliation and manifest freshness.
