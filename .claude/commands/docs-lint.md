---
description: Lint proto documentation for quality issues
---

Check proto documentation quality beyond simple coverage. Finds semantic type mismatches,
incomplete interaction metadata, invalid cross-references, empty enum values, and vague descriptions.

**Severity levels:**
- **Errors** - Must fix: invalid references, type mismatches
- **Warnings** - Should fix: incomplete metadata, undocumented values
- **Info** - Nice to fix: vague descriptions

**Rules checked:**
- enum-values-undocumented
- field-metadata-without-description
- semantic-type-mismatch
- interaction-incomplete
- invalid-references
- description-vague
- constrained-fields-undocumented

**Usage:**
```
/docs-lint
```

```bash
bb /home/jare/git/jettison_protogen/docs/.protodoc/scripts/proto-lint.clj /home/jare/git/jettison_protogen/docs/.protodoc/proto-db.edn
```
