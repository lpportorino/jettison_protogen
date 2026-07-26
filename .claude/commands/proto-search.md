---
description: Fuzzy search proto schema (messages, fields, enums)
---

Search the proto documentation database for messages, fields, or enums.

**Query:** `$ARGUMENTS`

**Examples:**
- `/proto-search iris` - Find iris-related messages
- `/proto-search camera zoom` - Find camera zoom fields
- `/proto-search DayCamera` - Find DayCamera messages
- `/proto-search JonGuiData` - Find state messages

**Output format:**
- `[msg] cmd.DayCamera.SetIris` - Message
- `[enum] ser.JonGuiDataClientType` - Enum

```bash
bb ./docs/.protodoc/scripts/proto-search.clj "$ARGUMENTS" ./docs/.protodoc/proto-db.edn
```
