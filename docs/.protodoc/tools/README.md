# Protodoc - Proto Documentation Generator

A Clojure-based tool for generating and maintaining documentation for Protocol Buffer schemas. Produces Obsidian-compatible markdown with roundtrip support (user edits are preserved on regeneration).

## Features

- **Plain EDN database** - Git-committed, queryable with standard Clojure
- **Roundtrip preservation** - User documentation survives schema regeneration
- **Obsidian-compatible output** - Wikilinks, YAML frontmatter, graph-friendly
- **buf.validate support** - Extracts and displays validation constraints
- **Pre-built search index** - Fast fuzzy search via Babashka scripts
- **Property-based testing** - Malli schemas with test.check generators
- **Dockerized** - Runs consistently in CI/CD

## Quick Start

### Local Development

```bash
# Run tests
clojure -M:test

# Generate documentation from descriptor
clojure -M:run generate \
  --descriptor ../../../output/json-descriptors/descriptor-set.json \
  --output-dir ../.. \
  --db-path ../proto-db.edn

# Show coverage report
clojure -M:run coverage --db-path ../proto-db.edn

# Validate database integrity
clojure -M:run validate --db-path ../proto-db.edn

# Sync IR without re-rendering (preserves user content)
clojure -M:run sync-ir \
  --descriptor ../../../output/json-descriptors/descriptor-set.json \
  --db-path ../proto-db.edn
```

### Using Docker

```bash
# From repository root
make docs-docker-build    # Build image
make docs-docker-test     # Run tests
make docs-docker-generate # Generate docs
make docs-docker-coverage # Show coverage
```

### Using docker-compose

```bash
cd docs/.protodoc/tools
docker-compose run --rm test      # Run tests
docker-compose run --rm generate  # Generate docs
docker-compose run --rm coverage  # Show coverage
docker-compose run --rm validate  # Validate DB
```

## Architecture

### Data Flow

```
descriptor-set.json (from protoc)
         │
         ▼
    ┌─────────┐
    │  Parse  │  parse.clj - JSON to Clojure maps
    └────┬────┘
         │
         ▼
    ┌─────────────┐
    │   Extract   │  extract.clj - Preserve user content from existing .md
    └──────┬──────┘
           │
           ▼
    ┌─────────────┐
    │   Merge     │  Combine IR with user content
    └──────┬──────┘
           │
           ▼
    ┌─────────────┐
    │  Validate   │  schema.clj - Malli validation
    └──────┬──────┘
           │
           ▼
    proto-db.edn (git committed)
           │
           ▼
    ┌─────────────┐
    │   Render    │  render.clj - Selmer templates
    └──────┬──────┘
           │
           ▼
    vault/*.md (Obsidian-compatible)
```

### Database Schema (proto-db.edn)

```clojure
{:messages
 {"cmd.DayCamera.SetIris"
  {:id "cmd.DayCamera.SetIris"
   :name "SetIris"
   :package "cmd.DayCamera"
   :source "jon_shared_cmd_day_camera.proto"
   :description "User documentation (markdown)"
   :fields [{:number 1
             :name "value"
             :type :double
             :constraints {:gte 0 :lte 1}
             :description "Field documentation"}]
   :oneofs [{:name "payload" :required true :fields [20 21]}]}}

 :enums
 {"ser.JonGuiDataClientType"
  {:id "ser.JonGuiDataClientType"
   :name "JonGuiDataClientType"
   :package "ser"
   :source "jon_shared_data_types.proto"
   :description "Enum documentation"
   :values [{:number 0 :name "UNSPECIFIED" :description "Value doc"}]}}

 :search-index
 {"iris" ["cmd.DayCamera.SetIris"]
  "camera" ["cmd.DayCamera.Root" "cmd.HeatCamera.Root"]}}
```

### Constraint Types

Extracted from buf.validate annotations:

| Constraint | Type | Example |
|------------|------|---------|
| `gt`, `gte`, `lt`, `lte` | number | `{:gte 0 :lte 100}` |
| `min-len`, `max-len` | int | `{:min-len 1 :max-len 255}` |
| `pattern` | string | `{:pattern "^[a-z]+$"}` |
| `defined-only` | boolean | `{:defined-only true}` |
| `not-in` | vector | `{:not-in [0 1]}` |
| `required` | boolean | `{:required true}` |
| `example` | vector | `{:example [1.0 2.0]}` |

### Interaction Metadata (Platform-Agnostic)

Messages and fields can have optional interaction metadata that describes how they should be presented across any UI platform (web, mobile, native). This metadata survives regeneration.

#### Message-Level Interaction

```clojure
:interaction
{:category :actuator           ; :sensor :actuator :settings :status :lifecycle :diagnostic
 :ui-pattern :slider-with-presets  ; See UIPattern enum below
 :feedback :pending-timeout    ; :fire-and-forget :pending-timeout :poll-confirm :optimistic-visual :dual-feedback
 :timeout-ms 2000              ; Default feedback timeout
 :purpose "Controls the iris aperture"
 :related-state ["ser.JonGuiDataCameraDay"]
 :related-commands ["cmd.DayCamera.SetAutoIris"]
 :preconditions ["Camera must be started" "Auto-iris disabled"]
 :notes "Implementation notes for developers"}
```

#### Field-Level Interaction

```clojure
:interaction
{:semantic-type :normalized    ; See SemanticType enum below
 :unit "%"                     ; Display unit: "°", "V", "°C", "m", etc.
 :precision 0                  ; Decimal places (0-6)
 :display-format "{value * 100}%"
 :presets [0 0.25 0.5 0.75 1.0 "auto"]}
```

The `precision 0` above is not illustrative — it is the one part of this shape a
lint rule holds. A `:display-format` that scales the stored value by 100 renders
a fraction as a percent, and a percent of a fraction is displayed in whole
units, so `:percent-display-precision` fails the `lint` command on any field
whose format carries that multiplier and whose precision is anything other than
0 (an absent precision included: leave the key out and every consumer picks its
own default). Group by DISPLAY SHAPE rather than by semantic type — `:normalized`
alone mixes fields rendered as a raw fraction with fields rendered as a percent,
and the two want different precisions.

#### UI Pattern Hierarchy

| Level | Patterns | Description |
|-------|----------|-------------|
| **Atomic** | `:toggle` `:action-button` `:cyclic-button` `:slider` `:stepper` `:indicator` `:enum-picker` `:roi-selection` | Single control |
| **Molecular** | `:slider-with-steppers` `:press-accelerating` | 2-3 controls combined |
| **Composite** | `:slider-with-presets` `:directional-mover` `:tabbed-config` `:state-machine-menu` | Complex multi-control |

#### Semantic Types

| Category | Types |
|----------|-------|
| **Numeric** | `:normalized` (0-1) `:angle` (degrees) `:percentage` (0-100) `:coordinate-geo` `:coordinate-viewport` `:distance` `:temperature` `:speed` `:voltage` `:current` `:power` `:duration` `:count` `:timestamp` `:identifier` |
| **Display** | `:cardinal` (compass) `:enum-label` `:toggle-state` `:raw` |

*The `UIPattern` and `SemanticType` enums in `src/protodoc/schema.clj` are
authoritative; the two tables above are illustrative and can lag the schema.*

#### Interaction in Markdown

```markdown
## Interaction

- **Category:** :actuator
- **UI Pattern:** :slider-with-presets
- **Feedback:** :pending-timeout

### Purpose

Controls the physical iris aperture of the day camera.

### Related State

- [[proto/ser.JonGuiDataCameraDay]]

### Preconditions

- Camera must be started
- Auto-iris must be disabled

## Field Notes

### value (#1)

Normalized iris position (0.0 = closed, 1.0 = fully open).

#### Metadata

- **Semantic Type:** :normalized
- **Unit:** %
- **Precision:** 0
- **Display Format:** {value * 100}%
- **Presets:** 0, 0.25, 0.5, 0.75, 1.0
```

## Project Structure

The tree below is illustrative, not exhaustive — the directory listings are the
authoritative set of files.

```
docs/                           # Obsidian vault (output)
├── .protodoc/                  # Implementation files (hidden)
│   ├── proto-db.edn           # EDN database (git committed)
│   ├── scripts/               # Babashka scripts
│   │   ├── proto-search.clj   # Fuzzy search
│   │   └── proto-coverage.clj # Coverage report
│   └── tools/                 # This directory
│       ├── deps.edn           # Dependencies (Malli, Selmer, etc.)
│       ├── Dockerfile         # temurin-based image
│       ├── docker-compose.yml # Service definitions
│       ├── build.clj          # tools.build config
│       ├── src/protodoc/
│       │   ├── core.clj       # CLI entry point
│       │   ├── schema.clj     # Malli schemas + generators
│       │   ├── parse.clj      # JSON descriptor parsing
│       │   ├── extract.clj    # Markdown extraction
│       │   └── render.clj     # Selmer rendering
│       ├── resources/templates/
│       │   ├── message.md.selmer  # Message template
│       │   ├── enum.md.selmer     # Enum template
│       │   └── index.md.selmer    # Index template
│       └── test/protodoc/
│           ├── schema_test.clj    # Schema + property tests
│           ├── parse_test.clj     # Parsing tests
│           ├── extract_test.clj   # Extraction tests
│           ├── render_test.clj    # Rendering tests
│           ├── roundtrip_test.clj # E2E roundtrip tests
│           └── core_test.clj      # CLI + integration tests
├── proto/                      # Message + enum documentation (cmd.* / ser.*)
└── index.md                    # Schema index
```

## CLI Commands

`clojure -M:run` with no command prints the authoritative usage banner
(`src/protodoc/core.clj`), the source of truth for the full subcommand + option
set. The common ones:

### generate

Full roundtrip: parse descriptors + extract user content + render markdown.

```bash
clojure -M:run generate \
  --descriptor path/to/descriptor-set.json \
  --output-dir path/to/vault \
  --db-path path/to/proto-db.edn
```

### sync-ir

Update DB from descriptors without markdown extraction/render. Preserves user content from existing DB.

```bash
clojure -M:run sync-ir \
  --descriptor path/to/descriptor-set.json \
  --db-path path/to/proto-db.edn
```

### coverage

Show documentation coverage report.

```bash
clojure -M:run coverage --db-path path/to/proto-db.edn
clojure -M:run coverage --db-path path/to/proto-db.edn --strict  # Exit 1 if incomplete
```

### validate

Validate database integrity against Malli schema.

```bash
clojure -M:run validate --db-path path/to/proto-db.edn
```

### render · lint · manifest · binary-dedup

- `render` — re-render markdown from the existing `proto-db.edn` (skips
  parse/extract).
- `lint` — lint documentation quality (`--rules` / `--exclude` / `--severity`;
  exits nonzero on errors).
- `manifest` — emit the machine-readable JSON manifests (`--config-path` /
  `--git-sha` for metadata).
- `binary-dedup` — emit the binary-dedup TypeScript tag map from a descriptor
  (`--descriptor` / `--output`).

## Output Format

### Message Markdown

```markdown
---
id: cmd.DayCamera.SetIris
proto: jon_shared_cmd_day_camera.proto
package: cmd.DayCamera
type: message
---

# SetIris

**Source:** `jon_shared_cmd_day_camera.proto`

## Description

User documentation preserved across regenerations.

## Fields

| # | Field | Type | Constraints |
|---|-------|------|-------------|
| 1 | value | double | >= 0, <= 1 |

## Field Notes

### value (#1)

Field-level documentation keyed by field number.
```

### Enum Markdown

```markdown
---
id: ser.JonGuiDataClientType
proto: jon_shared_data_types.proto
package: ser
type: enum
---

# JonGuiDataClientType

**Source:** `jon_shared_data_types.proto`

## Description

Enum-level documentation.

## Values

| # | Name | Description |
|---|------|-------------|
| 0 | UNSPECIFIED | Value documentation |
| 1 | WEB | - |
```

## Testing

### Run All Tests

```bash
clojure -M:test
```

### Test Categories

- **Schema tests** - Malli validation, edge cases, property-based
- **Parse tests** - JSON parsing, constraints, nested messages, error handling
- **Extract tests** - Markdown parsing, frontmatter, field notes
- **Render tests** - Template rendering, wikilinks, constraints formatting
- **Roundtrip tests** - Generate -> edit -> regenerate -> verify preserved
- **Core tests** - CLI, integration, error handling

### Property-Based Tests

Uses Malli generators with test.check:

```clojure
(deftest generated-messages-are-valid-test
  (testing "Generated messages are always valid"
    (let [result (tc/quick-check 50
                   (prop/for-all [msg (mg/generator schema/Message-gen)]
                     (schema/valid? schema/Message msg)))]
      (is (:pass? result)))))
```

## Dependencies

| Dependency | Purpose |
|------------|---------|
| metosin/malli | Schema validation + generators |
| selmer/selmer | Template rendering |
| org.clojure/data.json | JSON parsing |
| markdown-clj/markdown-clj | Markdown parsing |
| com.taoensso/telemere | Structured logging |
| org.clojure/test.check | Property-based testing |

## Makefile Targets

From repository root:

```bash
make docs-generate        # Generate docs locally
make docs-render          # Re-render markdown from proto-db.edn (no parse)
make docs-manifests       # Emit machine-readable JSON manifests
make docs-coverage        # Show coverage locally
make docs-test           # Run tests locally
make docs-search Q="iris" # Search proto schema

make docs-docker-build    # Build Docker image
make docs-docker-test     # Run tests in Docker
make docs-docker-generate # Generate docs in Docker
make docs-docker-render   # Re-render markdown in Docker
make docs-docker-coverage # Show coverage in Docker
make docs-docker-lint     # Lint documentation in Docker
make docs-docker-all      # Build + test + generate
```

The repo `Makefile` is the authoritative target list.

## Design Decisions

1. **Plain EDN over database** - the schema's messages don't need Datomic/Datascript
2. **Field numbers as keys** - Stable across field renames
3. **Raw markdown blobs** - No structured parsing of user content
4. **Pre-computed search index** - Build at generate time for fast lookups
5. **Obsidian-native format** - Wikilinks for automatic graph relationships
6. **Selmer templates** - Simple, flexible, Jinja2-like syntax
7. **Malli for validation** - Also provides test generators

## Obsidian Integration

The generated vault is compatible with Obsidian:

- **Wikilinks** - `[[cmd.DayCamera.SetIris]]` creates automatic links
- **Frontmatter** - YAML metadata for filtering and queries
- **Graph view** - Type references create visual relationships
- **Backlinks** - Obsidian shows incoming references automatically
- **Search** - Native Obsidian search + our bb scripts

## Troubleshooting

### Tests timeout

The test runner may hang if tests call `System/exit`. The `validate` function returns a result map; only its `validate-cli` wrapper calls `System/exit`.

### Docker build fails

Use `--network=host` for Docker commands:

```bash
docker build --network=host -t protodoc:latest .
docker run --rm --network=host protodoc:latest -M:test
```

### Constraint values as strings

Int64 values in JSON may be strings for precision. The parser handles this with `parse-number`.

### HTML escaping in output

Use `|safe` filter in Selmer templates for raw markdown content.
