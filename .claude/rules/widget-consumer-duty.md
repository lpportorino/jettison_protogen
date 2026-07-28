---
description: What a repo consuming the ui_ast widget surface owes back — deprecated-access gates it must extend, defects it must fix here rather than shim, and the vocabularies it must not hand-transcribe. Loads when editing the LVGL/ui_ast widget surface or its docs.
paths:
  - "proto/ui/**"
  - "renderer/**"
  - "tools/devcards/**"
  - "tools/renderer-gen/**"
---
<!-- LOAD-TEST: widget-consumer-duty -->

# The widget surface has consumers, and consuming it is a duty

This repo's widget layer is authored HERE and built ON elsewhere. The consumer is
the party actually assembling screens, so it meets the defects first — and it is
therefore the party that must fix them here rather than route around them there.
A consumer that only reports, or only works around, takes the value and returns
none. This rule states what the consumer owes and what this repo must ship to make
that duty dischargeable.

## What this repo owes the consumer

1. **A CONSUMER-RUNNABLE CHECK, NOT AN IN-TREE-ONLY ONE.** A gate that lives here
   and only ever runs here cannot bind anyone downstream. Anything a consumer is
   expected to comply with ships as a script the consumer invokes against its OWN
   sources — the preflight in `.claude/skills/ui-standard-review` is the shape to
   copy. A rule with no consumer-runnable check is advice, and advice does not gate.
2. **A NAMED VOCABULARY FOR EVERY BITMASK OR ENUM A CONSUMER MUST AUTHOR.** Where
   an API field is an integer whose meaning lives in a C header, the consumer will
   write a magic literal, because nothing offers it anything better. The vocabulary
   is this repo's to publish.
3. **THE VOCABULARY IS DERIVED, NEVER HAND-TRANSCRIBED.** A table that "mirrors"
   a header is a second source that diverges silently, and publishing one promotes
   the defect to every consumer at once. Derive it from the header, and if it
   cannot be reached from where it must live, fixing that reachability IS the work.
4. **A VOCABULARY IS SCOPED TO ITS FIELD.** One flat keyword table across every
   flag family is not type safety: the C API takes a bare int and will accept a
   state bit in an object-flags parameter without complaint. Only a per-field
   vocabulary makes the wrong constant unrepresentable, which is the whole reason
   to have one.
5. **DEPRECATION IS ANNOUNCED IN A FORM A MACHINE READS.** When an access pattern
   is superseded, the replacement is named in the check that rejects it, not only
   in prose. A consumer cannot grep a changelog it does not know exists.

## What the consumer owes back

6. **A DEFECT MET WHILE CONSUMING IS FIXED HERE, WITH A REGRESSION TEST, THROUGH
   THESE GATES** — never shimmed downstream, however small the shim looks. A shim
   is cheaper on the day and more expensive at the next re-vendor, which it turns
   into a merge.
7. **EXTEND THE CONSUMER-FACING GATES; DO NOT MERELY RUN THEM.** When a consumer
   meets a class the shipped checks do not catch — a superseded accessor, a raw
   bitmask where a vocabulary exists, a call shape this repo has replaced — the
   finding is not "the upstream gate is incomplete". It is a MISSING LEG THE
   CONSUMER OWES, and it lands here so every other consumer gains it.
8. **A GATE ADDED HERE IS CANARIED HERE.** Its negative leg lands in this repo's
   harness and is observed red FOR ITS OWN REASON before the gate is claimed. A
   prescribed check nobody has watched fail is a prescription, not a gate.
9. **CONSUME WHAT THIS REPO OWNS RATHER THAN REBUILDING IT.** Encoders, flag
   vocabularies, validators and invariant layers published here are the single
   source. A downstream reimplementation is a silent fork: both copies look correct
   in isolation, and nothing compares them.
10. **DO NOT ADOPT A STRICTER BAR THAN THIS REPO SETS FOR ITSELF.** Byte-exactness
    belongs where this repo puts it — the raw framebuffer goldens — and the
    committed gallery images are presentation, never compared. A consumer that
    hashes gallery images manufactures findings that are artifacts of its own
    stricter standard.

## Anti-patterns

- Publishing a hand-written constant table beside the header it mirrors.
- A consumer-facing rule with no consumer-runnable check behind it.
- One flat flag vocabulary shared across unrelated fields.
- A consumer working around a widget-layer defect locally because the upstream
  round-trip is slower.
- Treating a prescribed gate as a finished product to run rather than a surface to
  extend.
