---
description: Show next undocumented proto message with context for documentation
---

Show what proto documentation is missing and provide context for the next item to document.

**What it shows:**
- Current documentation coverage summary
- Undocumented messages grouped by module
- Next recommended message with full context:
  - Fields with types and constraints
  - Oneofs structure
  - Questions to answer for documentation

**Questions provided:**
1. Purpose - What does this message do?
2. Category - sensor/actuator/settings/status/lifecycle/diagnostic
3. UI Pattern - toggle/slider/action-button/slider-with-presets/etc.
4. Feedback - fire-and-forget/pending-timeout/poll-confirm/optimistic-visual
5. Related state messages
6. Related commands
7. Field semantic types, units, precision

**Usage:**
```
/doc-next
```

After reviewing the output, use the `doc-fill` skill to interactively document the message.

```bash
bb /home/jare/git/jettison_protogen/docs/.protodoc/scripts/doc-next.clj /home/jare/git/jettison_protogen/docs/.protodoc/proto-db.edn
```
