---
description: A bug here is a call for a red-then-green test in the SAME commit, attributed by mutation — and why a golden re-mint is never that test. Loads when editing hand-authored source or a test tree.
paths:
  - "renderer/src/**"
  - "tools/devcards/src/**"
  - "tools/devcards/dev/**"
  - "tools/devcards/test/**"
  - "tools/renderer-gen/src/**"
  - "tools/renderer-gen/dev/**"
  - "tools/renderer-gen/test/**"
  - "docs/.protodoc/tools/**"
  - "tools/lint/**"
  # renderer/wasm_harness/** holds the repo's RUST regression tests — including
  # composition_interaction.rs, the cross-engine mirror CLAUDE.md names. tools/claude/**
  # and tools/*.sh hold the shell canary suites. Verified: without these three, 9
  # test-bearing files were outside this rule's scope, so the rule about writing
  # regression tests did not load where the regression tests live.
  - "renderer/wasm_harness/**"
  - "tools/claude/**"
  - "tools/*.sh"
  - "*.mk"
---
<!-- LOAD-TEST: regression-test-first -->

# A bug is a call for a red→green test, in the SAME commit, ATTRIBUTED

Every defect — found by you, reported by a consumer, or surfaced by a probe, a gate
or an audit — is a call to write a test that FAILS BEFORE the fix and PASSES AFTER.
The test proves the bug is real, proves the fix works, and stops it returning.

**Here that last clause carries unusual weight.** protogen is the pinned upstream for
ten consumer repos that rebuild from the pin, so a defect that returns is
republished to all of them. The test is the only thing that makes a fix stick across
a fan-out nobody re-reviews.

## The order, and the step that is actually load-bearing

1. Write the test asserting the CORRECT behaviour.
2. **Run it. It MUST fail.** If it passes before the fix, you have not reproduced the
   bug — your diagnosis is wrong, and a green "regression" test for an unfixed defect
   is worse than none.
3. Fix the ROOT CAUSE, never the call site that met it.
4. Run it. It must pass.
5. The test lands in the SAME commit as the fix.

**A RED IS NOT ENOUGH — IT MUST NAME ITS CLAUSE.** This is where this repo goes
further than "fails first", and `.claude/rules/gate-enforcement.md` §2 makes it a
condition of the check existing at all. Most code here has several clauses that would
reject the same input, so a red is compatible with the clause under test being dead.
The discipline is: break THAT clause alone in a SCRATCH COPY, require the new test to
go red, and require a CONTROL — a neighbouring assertion — to stay green. Then
restore.

Two failure shapes that a bare red cannot distinguish, both measured in this tree:

- **A mutation that did not land** looks exactly like a clause that did not fire. So
  assert the new text present AND the old absent, on the exact bytes, before
  believing any colour. `tools/lint/test/lib_mutate.sh` refuses on a missing anchor
  for this reason, and it earned that: a canary was retargeted only because the
  primitive refused rather than silently running an unmutated copy.
- **An ERROR wearing a FAIL's colour.** A mutation that breaks compilation or a
  namespace load reddens the file while executing nothing. Require a FAIL, and read
  the ASSERTION COUNT — an armed suite and a suppressed one both print a colour.

## THE GOLDEN IS NOT A REGRESSION TEST, and this is protogen's own trap

Goldens here are sha256 over raw framebuffer bytes. They catch CHANGE with total
precision and CORRECTNESS not at all: **the first mint canonises whatever the code
produced that day.** So "I re-minted the golden" is not a regression test — it is a
record that the new behaviour is now the expected behaviour, which is the opposite
claim.

Measured: `padded-varint`, half of a cross-language wire mirror, ran on every build
with its bytes canonised by a golden. A wrong encoding would have been green forever.
What closed it was a test asserting the CONTRACT — width, continuation bits,
terminator placement, value-identity on decode — derived from the docstring rather
than from a run. **A vector copied from the current output agrees with the code by
construction and cannot fail.**

## Where the test has to LIVE for it to be a test at all

- **Assert the DOCUMENTED property, not the observed output.** The two are
  indistinguishable while the code is right and opposite once it is wrong.
- **Derive the oracle from the source of truth when there is one.** A hand-written
  expectation is written from the same misreading that produced the bug. Measured:
  `guarded-arm-fields` omitted three C guards, and the test that now guards it PARSES
  those guards out of `renderer/src/renderer.c` instead of listing them.
- **A hand-written fixture asserts your MODEL.** For dump-tree shapes that model is
  usually wrong about which keys are omitted; drive the real artifact where you can,
  and say in the docstring when you did not.

## The cases the bug-fix framing does not obviously cover

**A new assertion with no preceding bug**, and **a GATE check** — neither was ever
red on its own. Both are still observed firing on a constructed known-bad input
before they are trusted. An assertion never seen red may assert nothing, and passing
because the property holds is indistinguishable from passing because nothing was
checked.

**The sharpest case is a check whose PASS value equals its NOTHING-RAN value** — an
absence or zero-delta assertion. There it cannot fail when the system under test is
dead, so it owes a non-vacuity guard rather than a comment saying it should. A
non-zero expectation is not in this class; it fails at zero on its own.

## Do NOT pin the broken behaviour as a passing test

Asserting the current wrong output "to document the gap" produces a test that must be
inverted later by someone who no longer knows which way is correct. If a defect is
genuinely deferred, the test is written and marked as expected-to-fail with the
reason — never inverted into a green.

## Scope

Path-scoped to hand-authored source and the test trees. Two exclusions matter:

- **`proto/**` is out.** A wire change is not a local bug fix — it is the ONE
  COORDINATED EVENT `CLAUDE.md` describes: edit, `make generate`, commit, every
  consumer bumps in lockstep. The proving ground there is a consumer's wire-parity
  test against `docs/INTERFACE-CONTRACTS.md` §9, not a test written here.
- **Generated projections are out.** A test asserting a projection's bytes is a
  freshness check, which the battery already owns; a defect in a projection is a
  defect in its generator, and that is where the red→green belongs.
