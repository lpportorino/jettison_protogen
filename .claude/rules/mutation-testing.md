---
description: "Manual affected-scope mutation at epic completion: zero surviving eligible faults, attributed assertions and honest invalid outcomes."
---

# Mutation assessment before epic completion

**An epic is incomplete while any eligible mutant survives.** Run a manually
invoked, affected-scope campaign at epic completion; additional idle-time runs
are encouraged. Do not add a whole-repository mutation campaign to ordinary
commit or push gates. Ordinary runner canaries remain required and are distinct
from this assessment of production behavior.

Choose and record the scope before running: changed behavior, affected callers,
shared contracts, failure boundaries and the production behavior protected by
changed tests. Include C/WASM, Clojure/JVM, Rust and other affected languages as
applicable. Mutate first-party source or the generator that owns a projection;
editing generated artifacts or vendored implementation does not add evidence
about first-party correctness.

Use the official container and actual suites, with passing initial and final
baselines and frozen source/test/tool hashes. Work in disposable copies. A
mutation must demonstrably apply at its intended anchor; an unchanged copy is
not a tested fault. A kill requires a named failing assertion, positive executed
test/assertion counts, and an unaffected neighboring control that still passes.
The existing [regression rule](regression-test-first.md) also requires an
attributed red-before/green-after test for each repaired defect; an epic campaign
does not replace that obligation.

**No survivor waivers, accepted-survivor baselines or deferred-test promises.**
Strengthen the correct contract's regression when a behavioral fault survives,
observe that fault fail, and replay the campaign. Never alter correct production
behavior merely to kill a mutant. A proven equivalent transformation is an
invalid fault model: record the argument and domain, replace or remove that
candidate, and replay. Never count it as a kill or accepted survivor.

Compile errors, load errors, crashes, empty/skipped selections, missing tools
and timeouts are separate invalid or inconclusive outcomes, never kills. Bound
workers and their descendants; use a fresh process rather than retaining mutant
state in a reused JVM. Diagnose and repair malformed selected faults. Unresolved
infrastructure failures or unsupported affected suites block completion; a
follow-up entry does not make them green.

Retain the selected faults, exact replacements, commands, source/test/tool
fingerprints, named failures and passing controls, counts, durations, seeds,
outcome dispositions and exclusions in ignored machine reports. Put the durable
decision and reproducible command in maintained documentation. An empty scope
needs an explicit justification, such as prose-only work; it is not a successful
mutation run. The acceptance threshold is zero survivors in the justified
eligible scope, never a percentage that hides surviving behavior.

For the renderer migration, [the porting guide](../../docs/LVGL-PORTING.md)
records the ordinary native probes and the manually invoked renderer, lifecycle,
sizing and generator-contract (schema) campaigns. Keep their boundary-double
evidence distinct from actual WASM rendering and consumer acceptance.
