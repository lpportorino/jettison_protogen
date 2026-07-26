---
description: Show proto documentation coverage report
---

Display documentation coverage statistics for the proto schema database.

**Metrics shown:**
- **Messages** - How many messages have descriptions
- **Enums** - How many enums have descriptions
- **Fields** - How many fields have descriptions
- **Constrained Fields** - Fields with buf.validate constraints that have descriptions

**Output includes:**
- Progress bars for visual coverage
- Percentage complete
- List of undocumented messages (first 10)

**Usage:**
```
/proto-coverage
```

```bash
bb ./docs/.protodoc/scripts/proto-coverage.clj ./docs/.protodoc/proto-db.edn
```
