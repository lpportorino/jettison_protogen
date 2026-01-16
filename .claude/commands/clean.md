---
description: Remove all generated output files
---

Remove all generated protocol buffer bindings from the output directory.

**Preserves:**
- Proto source files in `proto/`
- Docker images

**Removes:**
- `output/` directory contents

**Usage:**
```
/clean
```

```bash
make clean 2>&1
```
