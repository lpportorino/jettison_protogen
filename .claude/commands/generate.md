---
description: Generate protocol buffer bindings for all languages
---

Generate protobuf bindings for all supported languages from the proto files.

**Languages generated:**
- C (nanopb)
- C++
- Go
- Java
- Python (with .pyi stubs)
- Rust (prost)
- TypeScript (ts-proto)
- TypeScript validated (protovalidate-es)
- JSON descriptors

**Usage:**
```
/generate
```

```bash
make generate 2>&1
```
