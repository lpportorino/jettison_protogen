# Paired performance companions

`gate.paired-performance/evaluate` evaluates an explicit, closed companion
record. It does not change a run archive, clock interval, CPU measurement or
critical-path estimate. Existing archives without this companion remain
unavailable for paired comparisons. There is no load-average scaling, noise
subtraction or causal attribution.

Nothing in this repository writes a `paired-performance.edn` or calls
`evaluate` outside its own tests: the profile is a contract and an evaluator
waiting for a producer. `tools/perf/bench.sh` is not one — its observations are
fixed-order and carry no A/A control pairs.

`paired-performance.edn` has a directory-catalog hint. Open it explicitly with
kind `:paired-performance`; the existing bounded record zipper reaches every
protocol, policy and raw observation. Names only suggest a kind; ordinary
bounded EDN admission still validates its contents. The canonical record
identity covers the whole companion, including raw observations. Opening it
beside an archive does not establish a relationship between them.

## Producer protocol

Declare the entire `Protocol` before collecting measurements. Its canonical
SHA-256 is returned by `protocol-digest`. Keep the declaration and raw execution
log as separate content-addressed receipts, recorded in `:declaration-evidence`
and `:measurement-evidence`. These are asserted source bindings, not proof of
producer honesty or the chronological fact of preregistration.

The protocol binds both variants' source/build identities, runtime identity,
workload and output witnesses, warmup count, bypassed application/gate-result cache, monotonic timer,
resolution and finite comparison policy. Observation records repeat these
bindings so accidental mixed work, outputs, warmups, runtimes or sources refuse.
The producer must define what workload and output equality mean; equal hashes
do not independently establish semantic equivalence or timing comparability.
The predeclared `:schedule` lists blocks, each with `:block`, `:order`, and
`:first-kind`. Each block contains exactly one comparison pair and one A/A
control pair, with four consecutive global observation indexes, and every
recorded pair belongs to a scheduled block. Both pairs
have the declared order, and pair order matches `:first-kind`. Grouped controls
collected later than all comparisons refuse; they are not locally matched.
Cache bypass does not disable CPU caches, JIT compilation or internal library
caches. Warmups and order checks constrain some effects; they do not eliminate
those mechanisms.
Pair, block, observation-index and duration limits are the bounds of the
contract's `Document`, `Pair`, `Block`, `Observation` and `Duration` schemas in
`src/gate/paired_performance_contract.cljc`. Pair IDs and execution indexes are
unique. Within a pair, indexes are consecutive and match its recorded order. A
control pair runs the reference twice; the candidate slot is a positional label
and still declares `:variant :reference`.

Use balanced reference-first/candidate-first blocks, collected under the same
protocol. Never relabel a previous fixed-order benchmark as a newly declared
paired experiment. Thread CPU, memory, load and environment receipts may
accompany the raw log, but this profile evaluates elapsed nanoseconds only; do
not invent missing resources.

## How a direction is qualified

Each block yields two ratios: the comparison (candidate time over reference
time) and the A/A control (one reference run over another). A ratio below one
means the candidate was faster in that block.

The evaluator never judges a direction from the fastest or slowest block. It
judges the **median** comparison ratio, through two order statistics: sort the
ratios, and take the k-th smallest and the k-th largest. If every block is an
independent draw from one continuous distribution, the true median lies below
the k-th smallest with probability at most the policy's
`:median-bound-miss-ppm`, and above the k-th largest with the same bound — an
exact binomial calculation, with no assumption about the distribution's shape.
k is the largest depth that keeps that promise, so it grows with the number of
blocks and the bounds close in on the median.

`:candidate-faster` needs the comparison median's upper bound below
`1/(1 + floor)` AND below the A/A median; `:candidate-slower` needs its lower
bound above `1 + floor` AND above the A/A median. Every tolerance is a
multiplicative band — a ratio and its reciprocal are equally far from one — so
`1.105` and `1/1.105` are judged alike. The A/A median's own bounds must sit
inside the control band, the comparison bounds must lie within a factor of the
spread tolerance of each other, and the two order strata's central values must
agree within the order tolerance.

Two properties follow, for independent blocks at any block count:

- **No direction where there is no effect beyond the floor.** If the true
  comparison median sits at or inside a floor, the direction beyond that floor
  is reported with probability at most the declared miss, from the floor clause
  alone; the two directions are bounded separately, so a median strictly inside
  the band is misreported in either direction at most twice the miss.
- **The bound depth never decreases as blocks are added.** The depth k moves in
  whole order statistics: adding a block without raising k keeps the same
  order-statistic positions over one more sample, which lowers the miss and
  widens the interval slightly, so power dips (at the fixture policy k stays 1
  from 6 to 8 blocks, where the bounds are the extremes); each step up in k
  narrows it again, and across the steps power rises toward one.

Both hold only under the independence assumption, which the evaluator cannot
check: it is a property of how the producer ran the blocks.

Reproduce the measurement behind those two properties, a seeded simulation of
the real `evaluate` over generated documents, with
`clojure -M:test:paired-simulation` from this module. With the fixture policy
(2.5 % miss, 10 % floor) and 3 % lognormal noise it reports, per block count
from 6 to 32: a true ratio of 0.88 qualifying faster at 0.398, 0.301, 0.569,
0.773, 0.823, 0.944, 0.962 and 0.992 of 1000 trials; and a true median exactly
on either floor qualifying in at most 0.027 of them.

Policy fields are declared with the protocol, never inferred from the result.
Their ranges are the `Policy` schema's; the `-ppm` fields are parts per million
and the first two are not:

- `:minimum-per-order`: a block count required in each order, so each order
  stratum has a median to compare.
- `:resolution-multiple`: a multiple of the declared `:resolution-ns`; every
  observation's elapsed time must reach that many timer quanta.
- `:control-tolerance-ppm`: the band the A/A median's bounds must stay inside.
- `:order-tolerance-ppm`: the band within which the two order strata's central
  values must agree.
- `:spread-tolerance-ppm`: the factor within which the comparison median's two
  bounds must lie of each other.
- `:effect-floor-ppm`: the band around one the comparison median's bounds must
  clear.
- `:median-bound-miss-ppm`: the per-side probability that a median bound misses.

A policy that cannot be met honestly refuses: an effect floor finer than one
timer quantum of the shortest admitted observation (`:effect-floor-unresolvable`),
or a replication floor too small for any median bound at the declared miss
(`:replication-unresolvable`).

## Timer evidence

The companion's top-level `:resolution-evidence` is separate from the frozen
protocol. `{:status :declared-unit-only :source <digest>}` preserves a producer's
unit declaration without pretending it calibrated a timer. Evidence that passes
every binding check then always evaluates `:inconclusive` with
`:timer-resolution-unverified`, even when the observed candidate effect is large;
evidence failing a binding check is `:refused` first, as below. Adding that
assessment after measurement does not rewrite the predeclared protocol or its
digest.

For `:measured`, provide `:method` and `:record` receipt digests plus
`:observed-ns`, the granularity the producer reports having observed. The
evaluator compares that number with the declared `:resolution-ns` and refuses
only an observed granularity coarser than the declaration; declaring a coarser
resolution than observed is conservative and accepted. It cannot check how the
number was measured: a minimum positive nanoTime step is not a proof of
accuracy, scheduler noise or tail uncertainty, and the API cannot authenticate
or independently replay a source digest.

## Results and uncertainty

Nil input returns `:unavailable`. Malformed shape throws `{:code :paired-shape}`.
Valid-shaped but inconsistent, failed, cached or zero-duration evidence, or an
unsatisfiable policy, returns `:refused`. Valid evidence failing any comparison
guard returns `:inconclusive`. Exact raw candidate/reference fractions remain
visible in both refused and inconclusive results; zero denominators in refused
evidence are invalid observations, not usable ratios.

`:qualified` means **the declared checks passed**, and only that state carries
a `:direction`. It is not a stable ranking or proof of performance under a
different workload, and the miss probability means what the section above says
only if the blocks really were independent. The result contains the policy and
all reasons. Each summary keeps the minimum, maximum, both central order
statistics (no rounded even-sample midpoint) and, once there are enough blocks,
the median's two bounds.

Durations and products are exact decimal strings, so values above
JavaScript's safe integer range keep every digit. The packaged browser bundle
compiles this profile's contract (its schemas and enums) but not `evaluate`,
whose verdicts are validated on the JVM only. A facade can additionally cap
canonical output bytes and refuse a large whole result; callers then use the
bounded zipper for raw inspection. It must not silently truncate away failed
controls.
