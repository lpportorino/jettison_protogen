---
description: Force rebuild Docker image and regenerate all bindings
---

Force rebuild the Docker image from scratch and regenerate all protocol buffer bindings.

**Use when:**
- Dockerfile has changed
- Dependencies need updating
- Clean rebuild is needed

**Usage:**
```
/rebuild
```

```bash
make rebuild 2>&1
```
