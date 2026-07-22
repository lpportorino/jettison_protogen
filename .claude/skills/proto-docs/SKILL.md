---
name: proto-docs
description: Generate and edit proto documentation. Use when regenerating docs after proto changes, adding descriptions, or fixing documentation issues.
allowed-tools: Read, Bash, Edit, Write, Glob, Grep
---

# Proto Documentation Skill

Generate and maintain Obsidian-compatible markdown documentation for protobuf schemas.

---

## Quick Reference

### Generate Docs
```bash
# Via Docker (recommended)
make docs-docker-generate

# Local (requires Clojure)
make docs-generate
```

### Run Tests
```bash
make docs-docker-test
```

### Search Schema
```bash
# Via babashka script
bb docs/.protodoc/scripts/proto-search.clj "query" docs/.protodoc/proto-db.edn
```

---

## Directory Structure

```
docs/                           # Obsidian vault (output)
├── .protodoc/                  # Implementation (hidden in Obsidian)
│   ├── proto-db.edn           # EDN database (git committed)
│   ├── scripts/               # Babashka scripts
│   │   ├── proto-search.clj   # Search command
│   │   ├── proto-coverage.clj # Coverage report
│   │   ├── doc-next.clj       # Next undocumented message
│   │   ├── proto-lint.clj     # Documentation linting
│   │   └── patch-lint.clj     # Batch lint fixes
│   └── tools/                 # Clojure tooling
│       ├── src/protodoc/      # Core modules
│       ├── test/protodoc/     # Tests
│       ├── resources/         # Selmer templates
│       └── deps.edn           # Dependencies
├── proto/                      # Message + enum documentation
│   ├── cmd.*.md               # Command messages
│   └── ser.*.md               # State/data + enum messages
└── index.md                    # Schema index
```

---

## Workflow: After Proto Changes

1. **Regenerate bindings** (if proto files changed):
   ```bash
   make generate
   ```

2. **Regenerate docs**:
   ```bash
   make docs-docker-generate
   ```

3. **Check diff** for new fields/messages:
   ```bash
   git diff docs/proto/
   ```

4. **Add descriptions** to new fields in the markdown files

5. **Commit changes**:
   ```bash
   git add -A && git commit -m "docs: Update proto documentation"
   ```

---

## Adding Field Descriptions

User content survives regeneration. Edit markdown directly:

### Message Description
```markdown
## Description

Your description here. This survives regeneration.
```

### Field Notes
```markdown
## Field Notes

### field_name (#1)

Your field description here.

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 2
```

### Interaction Metadata
```markdown
## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider
- **Feedback:** :fire-and-forget

### Purpose

What this message is used for.

### Related Commands

- [[proto/cmd.Other.Command]]
```

---

## Database Schema (proto-db.edn)

```clojure
{:messages {"cmd.DayCamera.SetIris"
            {:id "cmd.DayCamera.SetIris"
             :name "SetIris"
             :package "cmd.DayCamera"
             :source "jon_shared_cmd_day_camera.proto"
             :description "User docs (preserved)"
             :fields [{:number 1
                       :name "value"
                       :type :double
                       :constraints {:gte 0 :lte 1}}]}}
 :enums {"ser.JonGuiDataClientType" {...}}
 :search-index {"iris" ["cmd.DayCamera.SetIris"] ...}}
```

---

## Constraint Types

The parser supports these buf.validate constraints:

| Type | Constraints |
|------|-------------|
| Numeric | gt, gte, lt, lte, example |
| String | minLen, maxLen, pattern, in, email |
| Bytes | minLen, maxLen |
| Enum | definedOnly, notIn |
| Repeated | minItems |
| General | required |

### Adding New Constraints

If you see an error like:
```
No method in multimethod 'parse-constraint' for dispatch value: :type/constraint
```

1. Add to `constraint-registry` in `parse.clj`
2. Add handler: `(defmethod parse-constraint :type/constraint [_ _ v] {...})`
3. Update schema in `schema.clj`
4. Update `format-constraints` in `render.clj`

---

## Interaction Metadata

### Categories
`:sensor` `:actuator` `:settings` `:status` `:lifecycle` `:diagnostic`

### UI Patterns
- Atomic: `:toggle` `:action-button` `:slider` `:stepper` `:indicator` `:enum-picker`
- Molecular: `:slider-with-steppers` `:press-accelerating`
- Composite: `:slider-with-presets` `:directional-mover` `:tabbed-config`

### Semantic Types
`:normalized` `:angle` `:percentage` `:coordinate-geo` `:temperature` `:voltage` `:current` `:power` `:distance` `:duration` `:count` `:timestamp` `:enum-label` `:raw`

---

## Troubleshooting

### Docker Image Missing
```bash
make docs-docker-build
```

### Parse Error
Check the error message for the constraint type, then add support in `parse.clj`.

### User Content Lost
User content is extracted from `## Description`, `## Field Notes`, and `## Interaction` sections. Ensure these headers exist and are properly formatted.

### Regeneration Shows No Changes
The descriptor-set.json may not have been updated. Run:
```bash
make generate  # Regenerate all bindings including JSON descriptors
make docs-docker-generate
```

---

## Data Flow

```
proto/*.proto
    → make generate
    → output/json-descriptors/descriptor-set.json
    → make docs-docker-generate
    → docs/.protodoc/proto-db.edn + docs/proto/*.md
```

Roundtrip preservation:
```
descriptor-set.json → parse.clj → extract.clj → proto-db.edn → render.clj → docs/*.md
                                       ↑                              │
                                       └──────── user edits ──────────┘
```
