# Viewer and inspection API review

Reviewed 2026-10-08 against the implementation in this checkout, including the
viewer changes under development. This is a source review, executable JVM
dogfood, and a finite CLJ/CLJS Node differential corpus using synthetic fixtures.
It does not certify exhaustive runtime parity, process-memory ceilings, or
completion of the runner lifecycle work.
The accompanying [research](viewer-research.md) records public primary sources;
the findings here concern this implementation.

The public boundary is sound in its essentials: admit a graph, retain its
immutable context, submit a closed data-only request, and return bounded pages
of original observations. Artifact/selection-bound continuations, exact decimal
time arithmetic, typed event endpoints and refusal of unjustified aggregation
are already implemented. The remaining qualifications below must travel with
any claim that the API is bounded or portable.

## Entrypoint inventory

Names below are namespace-qualified; all listed implementation functions have
docstrings and `m/=>` declarations. A declaration alone does not install runtime
checks. The JVM test runner enrolls the view namespaces, checks function schemas
and documentation, and calls `gate.diagnostic/install!`.

| Entrypoint | Input / output | Boundary and lifecycle |
| --- | --- | --- |
| `gate.admission/decode` | Source text, target, limits → admitted value | Bounded parser; use `:graph`, `:query-request`, `:aggregate-request`, `:diff-request`, or `:view-request` as appropriate. `:value` is shape admission, not graph-global validation. |
| `gate.report-io/read-graph!`, `read-archive!` | Path and admission limits → graph/archive | JVM filesystem admission. Read once per retained artifact, not once per page. |
| `gate.query/prepare` | Graph → `inspection-contract/Prepared` | Validates global invariants, normalizes and hashes the complete graph, and builds indexes. Preparation is separate from page budgets. |
| `gate.query/page` | Prepared + `Request` → `Page` | Nodes, immediate children, window overlap or measurements; equality filters only; deterministic candidate order. |
| `gate.measure/aggregate` | Prepared + `AggregateRequest` → `AggregateResult` | Explicit measurement selection; exact sum or observed maximum; pair and response-byte limits; a failed proof returns refusal without a partial total. |
| `gate.diff/prepare`, `page` | Two query contexts → comparison context; request → diff page | Preparation computes task profiles once. Continuations bind both artifacts. Semantic identities preserve occurrence multiplicity instead of guessing a retry pairing. |
| `gate.interval/separated?`, `union`, `duration-sum`, `summarize` | Exact intervals → proof/coverage/summary | Trusted typed helpers. Distinguish elapsed envelope, occupied union and inclusive duration sum. These do not infer exclusive work. |
| `gate.view.model/prepare` | Graph → local `Prepared` | Reuses query admission, adding node, child and immediate endpoint-neighbor indexes. Retain privately and rebuild after artifact changes. |
| `gate.view.model/fold` | 1–256 unique nodes → `view.contract/Fold` | Explicit shape/duplicate validation; exact sorted members, interval union, gaps and outcome counts. Decisions and unfinished executions do not receive invented durations. |
| `gate.view.model/overlaps?` | Observation interval, query interval → boolean | Asymmetric for points: a point at the left boundary matches; an empty query window matches nothing. Reversed intervals receive a named error. |
| `gate.view.model/page` | View context + `view.contract/Request` → `Page` | Exact members, incident typed edges, immediate endpoint neighbors, text search, or coincident measurements. Checks request shape, duplicate/missing members, reversed windows and foreign cursors. |
| `gate.view.opportunity/value` | Admitted measurement → approximate magnitude or nil | Trusted display/heuristic helper. Deltas divide by actual covered nanoseconds; gauges remain samples. It is not an exact aggregation API or percent-normalization API. |
| `gate.view.opportunity/detect` | At most 512 measurements → at most 164 candidates | Explicit production input-size/shape validation; exact time ordering followed by approximate threshold arithmetic. Policy is `:activity-change-v1`; callers group a coherent series and disclose truncation. |
| `gate.canonical/encode`, `normalize-graph`, `sha256`, `utf8-size` | Closed data / text → canonical data or digest/size | ASCII escaped EDN with exact decimal quantities; original graph observations remain authoritative. The closed encoding union is intentionally smaller than arbitrary EDN. |

The prepared structures are **application-owned values**, not external request
schemas. They contain the complete graph and indexes and must not be exposed as
tool results or admitted from client input. Their Malli schemas describe trusted
internal structure; they do not prove that a caller-constructed digest agrees
with the graph. No mutable resource requires a close operation. Release a
context by dropping references; bound any cache of contexts at the adapter.

## Semantics and misuse resistance

`view/page` preserves canonical rows. `:edges` returns internal and boundary
edges incident to the named members. Endpoint `:phase` and edge `:kind` survive;
an edge is not reduced to a pair of task labels. `:neighborhood` is undirected,
one-hop endpoint adjacency over all recorded edge kinds. It is neither
containment traversal nor a dependency-only predecessor/successor query.
Callers needing that distinction must inspect typed edges. Graph validity is
defined over the event-phase model; task-level folding must not substitute its
own DAG assumption.

Member vectors are unique and order-sensitive in the selection digest. Reuse
the exact selection on continuation: semantically similar forms, such as a
reordered member vector or omitted versus empty filters, are not normalized
into one token identity. A cursor is a closed data record, not an authorization
capability or a signed token. Budgets can change; artifact and selection cannot.
The cursor version is currently 1. Persisting cursors across future semantic
changes needs an explicit version policy.

The page loop checks completion before visit and row exhaustion. Every examined
candidate counts, including nonmatches and a selected row rejected for bytes.
`visited` is local to the page. Pages reserve a conservative envelope and count
encoded row bytes, then verify the complete response, including `:next`, against
the requested limit. Canonical strings are ASCII escaped, so string length and
UTF-8 byte length happen to agree on encoded output; this review does not claim
the earlier length-based implementation lost Unicode bytes.

A byte-stopped page can contain no rows and retain its offset. Increase the byte
budget before retrying. Never loop blindly on unchanged cursors; also impose an
adapter-level maximum page count or aggregate work budget. Row limits can leave
a final empty page because remaining candidates do not match. Continue until
`:stop :complete`, not until a short/empty page or the last expected match.

The numeric ceilings are explicit: 128,000 visits, 1,000 rows and 1 MiB per page;
256 fold members; 512 detector samples. Preparation, request validation,
selection hashing, per-row bounded encoding, and optional Malli instrumentation
are additional work. A visit is not a constant number of CPU instructions or a
wall-clock guarantee. In particular, instrumentation can revisit large trusted
context schemas on function calls. Benchmark cold preparation separately from
warm paging, with instrumentation state recorded.

Errors have two layers: malformed operations throw compact `ex-info`; valid
aggregation requests whose evidence cannot prove the operation return
`:status :refused`. Viewer request/cursor/member errors use
`gate.view.contract/Failure`; shared arithmetic/encoding/admission errors use
their corresponding shared contracts. Instrumented calls can instead receive
the bounded `gate.diagnostic/Failure` shape before entering the function. A
tool adapter should document and normalize these layers rather than assuming
every exception has the same `:code` enum.

## Findings and disposition

| ID | Finding | Disposition and evidence |
| --- | --- | --- |
| API-01 | Repeating file admission/preparation for every facade call defeats the prepared API. | Existing shared API already has `query/prepare`; retain it per artifact. The new view context follows that pattern. Adapter ownership, cache lifetime and invalidation remain consumer responsibilities; removing validation is not the fix. |
| API-02 | Duplicate fold/member selections can inflate summaries or create ambiguous grouping. | Fixed in viewer code: explicit uniqueness checks. Regression `rejected-members-and-stale-continuations` covers the refusal. Fold membership and occupied-time oracle tests remain independent of rendering. |
| API-03 | Neighborhood rows must be nodes, while incident-edge rows preserve typed endpoints. | Fixed and documented as distinct selectors. `neighborhoods-return-nodes-not-edges` and the executable assertions below check both surfaces. |
| API-04 | Page budgets must cover nonmatches, UTF-8 serialization and the continuation envelope. | Implemented. Existing non-BMP regression demonstrates an empty byte-stopped page and successful continuation with a larger budget. No false claim of a prior Unicode defect: canonical output is ASCII. Broader budget combinations remain recommended below. |
| API-05 | `detect` originally relied only on `m/=>` for its maximum 512 samples. Uninstrumented calls could exceed the documented work bound. | Fixed during review with an explicit validator and `:invalid-opportunity-samples`. Independent uninstrumented 513-sample probe observed that error. The error contract/schema publication question is tracked separately in API-07. |
| API-06 | `overlaps?` sounded symmetric and did not explicitly reject reversed intervals. | Fixed during review: docstring names observation/window asymmetry; reversed intervals throw `:invalid-view-request`. Independent uninstrumented probe observed that code. This does not make the helper an arbitrary-data admission boundary. |
| API-07 | Opportunity candidates originally had local Malli schemas but were absent from the shared canonical encoding/discovery union. | Fixed through the shared schema seam during review. The initial executable probe returned `:invalid-encoding-input`; named Candidate/Candidates/Failure registry entries, the encoding union and admission keyword discovery now cover these data. The portable corpus checks candidate canonical encoding and `:value` round-trip admission on both runtimes. |
| API-08 | CLJC sources and browser execution alone did not establish result/error/cursor parity. | A finite differential acceptance corpus is now implemented in `test/gate/view/parity.cljc`, executed by `browser/api-parity.sh`. Exact outputs match on CLJ and both unoptimized and advanced CLJS/Node; details below. Larger generated cross-runtime coverage and production browser-bundle differential checks remain open. |
| API-09 | New view dependencies must participate in source identity/freshness checks. | Coordinated source fix observed: all three new view CLJC paths are in the shared source roster. Verify the source-bound freshness test and packaged manifest after rebuilding; this review does not substitute for packaged acceptance. |
| API-10 | A fold ID hashes member IDs only, without artifact identity; standalone folds can be confused across captures. | Accepted local-context scope. Keep fold data beside the prepared artifact identity; do not use fold ID alone as a global cache/deep-link key. `fold` is a projection helper, not evidence admission or cross-artifact comparison. |
| API-11 | Generic public names such as `value` and `overlaps?` hide units/trust preconditions. | Documentation clarified for overlap; inventory above states `value` is approximate per-nanosecond magnitude for deltas. Prefer explicit aliases or examples at an adapter boundary rather than changing canonical quantities. |

No runner process, archive lifecycle, transport, acquisition, or external
repository code was changed for this review. Findings about those systems are
outside this document's tested claim.

## Viewer adapter repair review

A subsequent independent read of `viewer.cljs` and `report.clj` checked how
the UI consumes these contracts. The production owner applied the following
repairs; this review did not edit those implementations.

| Finding | Disposition |
| --- | --- |
| An unfinished run's extent considered node times but omitted later unowned telemetry. | Fixed with `capture-extent`, retained once at admission. A direct CLJS helper probe on an admitted synthetic graph with node end 100 and measurement end 200 returned 200. |
| Automatically opening every sole root hid a one-node leaf run. | Fixed with `overview-anchor`: only a root with children is opened. A direct CLJS helper probe returned a nil anchor and one visible node for a single leaf. |
| An empty first bounded edge page was labeled as having no recorded edges even when the scan stopped early. | Source recheck confirms the absence claim now requires `:stop :complete`; partial scans describe the inspected prefix. A large-edge browser regression remains appropriate. |
| Duplicate member IDs restored from a fragment bypassed the page API's uniqueness checks. | Fixed by requiring distinct fragment members. A direct CLJS helper probe confirmed that `fold=compile,compile` does not restore a fold. |
| Zoom-dependent regrouping could leave the lane offset beyond the last group, showing no lanes despite matching tasks. | Source recheck confirms the stored offset is clamped to the current final page before slicing. Browser coverage should combine paging, zoom-dependent grouping and an oversized valid fragment offset. |

The helper probe made four assertions and passed. It exercised the actual
CLJS helper functions; it was not a screenshot or an end-to-end browser test.
Rebuilding the production bundle and checking the corresponding visible states
remain separate acceptance steps.

Three smaller adapter issues were also repaired. Source recheck confirms that
used evidence-continuation buttons are removed, preventing repeated activation
from appending duplicate page batches. Request and page text now have separate
bounded disclosures, so their concatenation cannot exceed `gate.view.dom/Text`'s
1 MiB declaration.

`report/render` now checks its complete UTF-8 output against the existing
160 MiB limit and throws the named `:html-byte-limit` failure before returning
an oversized document. The independent publication guard remains unchanged.
This matters because graph EDN appears in both a visible `pre` and a data script,
with further bundle and escaping overhead. A small-bound regression demonstrated
two assertion failures before the guard and then passed both uninstrumented and
instrumented focused runs: **3 tests / 12 assertions per run**, zero failures or
errors. It checks exact-limit acceptance, one-byte-over rejection and a Unicode
case distinguishing byte counts from string units. The guard bounds returned
output; it does not claim a streaming renderer or a peak-allocation ceiling.

The report's ordinary untrusted text passes through Hiccup escaping or
`textContent`; embedded EDN escapes the script boundary and the trusted bundle
rejects closing-script text. This review found no new script-boundary defect in
that path.

## Executable synthetic examples

The following forms were executed in the repository's pinned toolchain with
`src` and `test` on the classpath, without diagnostic instrumentation. All nine
assertions passed. Save the block as `tools/gate-graph/target/api-review-example.clj`
and run from the repository root:

```sh
bash tools/uber.sh 'cd tools/gate-graph && clojure -Sdeps '\''{:aliases {:review {:extra-paths ["test"]}}}'\'' -M:review target/api-review-example.clj'
```

```clojure
(require '[gate.fixtures :as f]
         '[gate.query :as query]
         '[gate.view.model :as view]
         '[gate.measure :as measure]
         '[gate.canonical :as canonical])

(def graph (f/example))
(def context (view/prepare graph))
(def budget {:visits 10 :rows 1 :bytes 4096})
(def request {:select {:op :members :members ["compile" "check"]}
              :budget budget})
(def p1 (view/page context request))
(def p2 (view/page context (assoc request :cursor (:next p1))))
(def p3 (view/page context (assoc request :cursor (:next p2))))

(assert (= ["check" "compile"]
           (mapv :id (into (:rows p1) (:rows p2)))))
(assert (= :complete (:stop p3)))
(assert (= :invalid-view-cursor
           (try
             (view/page context
                        (assoc request :select {:op :members :members ["compile"]}
                                       :cursor (:next p1)))
             (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
(assert (= ["check"]
           (mapv :id (:rows (view/page context
                             {:select {:op :neighborhood :members ["compile"]}
                              :budget budget})))))
(assert (= ["compiled-before-check"]
           (mapv :id (:rows (view/page context
                             {:select {:op :edges :members ["compile"]}
                              :budget budget})))))
(assert (= "100" (get-in (view/fold (vec (rest (:nodes graph))))
                         [:summary :occupied-ns])))
(assert (empty? (:rows (view/page context
                         {:select {:op :correlation
                                   :interval {:start-ns "0" :end-ns "100"}}
                          :budget budget}))))
(def measured (query/prepare (assoc graph :measurements [(f/measurement)])))
(assert (= "80" (:value (measure/aggregate measured
                         {:op :sum :measurements ["cpu"]
                          :budget {:pairs 0 :bytes 4096}}))))
(assert (<= (canonical/utf8-size (canonical/encode p1 4096)) 4096))
```

An empty correlation page here means the fixture contains no measurements. It
does not mean CPU or memory use was zero. The measured variant supplies one
explicit synthetic CPU observation; its 80 ns service exceeds its 40 ns covered
wall interval without implying an error or a host utilization percentage.

## Portable differential acceptance

Run the separate executable corpus from the repository root:

```sh
bash tools/uber.sh 'bash tools/gate-graph/browser/api-parity.sh'
```

The script uses the module's existing pinned `:shadow` dependency and temporary
CLI aliases; it changes no dependency or build configuration. It first asserts
the JVM corpus, compiles the same `.cljc` namespace with `cljs.main` for Node
under both `:none` and `:advanced` optimization, asserts both corpora, and compares
complete output with `cmp`. Generated files
remain under `target/api-parity/`. Each runtime independently checks expected
semantics before the byte comparison, so agreeing implementations cannot pass
merely by emitting no results.

Executed 2026-10-08: **79 assertions in each of three executions**, exact output match, with
Clojure 1.12.0, ClojureScript 1.12.145, Node 18.19.1 and GraalVM CE 25.0.2.
`clj.edn-lines`, `cljs.edn-lines` and `advanced.edn-lines` all had SHA-256
`722db54b7a61d4ad4c3e9f4000f2a2ad3d9263631f3ed437cdcd4a11dcded742`.
The documentation's separate nine-assertion example was extracted from this
file and executed successfully; its rendered snippet was not merely reviewed.

The corpus covers all five view selectors, all four shared query selectors,
small visit budgets, closed page schemas, foreign-selection/artifact/offset
cursors, named malformed-input errors, huge decimal interval positions, interval
gaps and overlap, fold permutation/round-trip, point-window boundaries,
supplementary Unicode and lone-surrogate escapes, detector bounds/contiguity/
uncertainty/strict threshold, and canonical candidate admission. Its byte-stop
case checks an empty page with unchanged offset and a successful larger-budget
retry. This is an uninstrumented JVM versus unoptimized and advanced CLJS/Node
source-level comparison, not execution of the packaged browser UI bundle.

## Coverage and remaining extensions

The final suite includes a 100-case discrete occupied-union oracle (seed 260108)
and a 40-case generated disconnected-DAG incident-edge pagination oracle
(seed 260109), alongside branch/join, nested dense children, point windows,
Unicode byte stops, changed artifacts, invalid requests and incomplete states.
The dense browser fixture includes 3,000 children and 2,960 child dependencies.

The differential corpus runs 79 assertions in each of JVM, unoptimized CLJS/Node
and Closure advanced CLJS/Node. It compares canonical pages, cursor identities,
folds, bounded failures and strict candidate thresholds, including large decimal
quantities, supplementary Unicode and escaped lone surrogate units. Browser
acceptance separately exercises the shipped advanced UI bundle. This is finite
coverage, not an exhaustive differential test of every UI-internal helper.

The [mutation record](viewer-mutations.md) documents 10 attributed behavior
faults with independent passing controls and complete before/after suites.
Preparation and 100 warm bounded pages are measured separately by
`gate.view.benchmark`; instrumentation mode, workload sizes, budget and artifact
identity are retained in the [query measurements](viewer-query-performance.edn).
The [browser performance record](viewer-performance.md) binds its own measured
values and HTML identities; [combined acceptance](integrated-viewer.md) records
the subsequent integrated build.

Useful future extensions include a larger generated product of budgets and graph
shapes, direct differential invocation from the shipped UI artifact, and richer
producer coverage for ready queues, throttling and device pressure. Current
bounds and finite tests do not prove constant-time visits, process-memory caps,
causal attribution, or universal performance thresholds.
