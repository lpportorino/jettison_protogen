---
description: Regenerate proto documentation from descriptor-set.json
---

Regenerate the proto documentation markdown files from the JSON descriptors.

**What it does:**
1. Parses `output/json-descriptors/descriptor-set.json`
2. Extracts existing user content from `docs/proto/*.md` (preserves your descriptions)
3. Regenerates markdown files with updated schema
4. Updates the search index in `proto-db.edn`

**Usage:**
```
/docs-generate
```

**Note:** User-written descriptions and field notes are preserved during regeneration.

```bash
make docs-docker-generate 2>&1
```
