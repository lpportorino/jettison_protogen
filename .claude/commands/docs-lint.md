---
description: Lint proto documentation for quality issues
---

Check proto documentation quality beyond simple coverage — semantic type
mismatches, incomplete interaction metadata, invalid cross-references, empty
enum values, vague descriptions, and metadata that contradicts its own display
format.

**Severity levels:**
- **Errors** - Must fix: something is WRONG
- **Warnings** - Should fix: something is MISSING

The rule set is `default-rules` in
`docs/.protodoc/tools/src/protodoc/lint.clj`, and the run prints how many rules
it checked. No list is kept here: this command and the pre-push hook and CI must
all report the same verdict, which they can only do by invoking the same
implementation.

**Usage:**
```
/docs-lint
```

```bash
make -f lint.mk docs-lint
```
