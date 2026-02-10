# CLAUDE.md - Protogen Module

This file provides guidance to Claude Code when working with the Protogen module.

## Module Overview

Protogen is a Docker-based protocol buffer code generator that supports multiple programming languages with consistent tooling and versions. It provides both standard bindings and validated bindings (for Go, Kotlin, and Java) using buf.validate annotations.

## Module Structure

### Core Files
- `Makefile` - Build automation with targets for image building and proto generation
- `generate-protos.sh` - Main generation script that orchestrates Docker container execution
- `Dockerfile` - Main Docker image that uses the base image
- `Dockerfile.base` - Base image with all necessary tools and dependencies
- `scripts/proto_cleanup.awk` - AWK script to remove buf.validate annotations for incompatible languages
- `.github/workflows/build-and-release.yml` - GitHub Actions workflow for automated distribution
- `.gitattributes` - Empty file (previously used for Git LFS, now removed)

### Directories
- `proto/` - Input directory containing .proto files to process (contains jon_shared_*.proto files)
  - Supports subdirectories (e.g., `proto/opaque/` for opaque payload types)
  - Generation scripts recursively find all `.proto` files, excluding `test/` directory
- `output/` - All generated bindings organized by language (created at runtime)
  - Preserves subdirectory structure (e.g., `output/typescript/opaque/`)
- `scripts/` - Contains helper scripts like proto_cleanup.awk and add-validate-import.sh

### Generated Output Structure
```
output/
├── c/                    # nanopb C bindings
├── cpp/                  # C++ bindings
├── go/                   # Go bindings with buf.validate support
├── kotlin/               # Kotlin bindings with buf.validate support
├── java/                 # Java bindings with buf.validate support
├── python/               # Python bindings with type stubs
├── rust/                 # Rust bindings using prost
├── typescript/           # TypeScript bindings using ts-proto (no validation)
├── typescript-validated/ # TypeScript bindings with protovalidate-es
└── json-descriptors/     # JSON FileDescriptorSets with buf.validate annotations
```

## Key Patterns

### Docker Container Usage
- Container builds automatically on first run if image doesn't exist
- Base image built locally on first use or restored from GitHub Actions cache
- All generation runs inside Docker for consistency
- Uses volume mounts to access input/output directories
- Runs bash scripts passed via `-c` flag

### Parallel Processing
- C generation uses `xargs -P 8` for parallel protoc invocations
- Each language generator runs sequentially to avoid conflicts
- Error handling aggregates failures and reports at end

### Annotation Handling
- AWK script (`proto_cleanup.awk`) removes buf.validate annotations
- Required for nanopb (C) compatibility
- Applied before generation for non-validation outputs
- Preserves all other proto syntax

### Import Management
- Validation-enabled outputs automatically add `import "buf/validate/validate.proto"`
- All proto files compiled together to resolve cross-file dependencies
- validate.proto copied from protovalidate repository

## Output Distribution

Generated bindings are automatically distributed to dedicated repositories:

| Language | Repository |
|----------|------------|
| C (nanopb) | [jettison_proto_c](https://github.com/lpportorino/jettison_proto_c) |
| C++ | [jettison_proto_cpp](https://github.com/lpportorino/jettison_proto_cpp) |
| Go | [jettison_proto_go](https://github.com/lpportorino/jettison_proto_go) |
| Kotlin | [jettison_proto_kotlin](https://github.com/lpportorino/jettison_proto_kotlin) |
| Python | [jettison_proto_python](https://github.com/lpportorino/jettison_proto_python) |
| TypeScript | [jettison_proto_typescript](https://github.com/lpportorino/jettison_proto_typescript) |
| TypeScript (validated) | [jettison_protovalidate_es](https://github.com/lpportorino/jettison_protovalidate_es) |
| Rust | [jettison_proto_rust](https://github.com/lpportorino/jettison_proto_rust) |
| Java | [jettison_proto_java](https://github.com/lpportorino/jettison_proto_java) |
| JSON Descriptors | [jettison_proto_json-descriptors](https://github.com/lpportorino/jettison_proto_json-descriptors) |

### GitHub Secrets Required

For automated distribution, these deploy keys must be configured as repository secrets:

- `C_PUSH` - Deploy key for jettison_proto_c
- `CPP_PUSH` - Deploy key for jettison_proto_cpp
- `GO_PUSH` - Deploy key for jettison_proto_go
- `KOTLIN_PUSH` - Deploy key for jettison_proto_kotlin
- `PYTHON_PUSH` - Deploy key for jettison_proto_python
- `TYPESCRIPT_PUSH` - Deploy key for jettison_proto_typescript
- `PUSH_TO_PROTOVALIDATE_ES` - Deploy key for jettison_protovalidate_es
- `RUST_PUSH` - Deploy key for jettison_proto_rust
- `JAVA_PUSH` - Deploy key for jettison_proto_java
- `JSON_DESCRIPTORS_PUSH` - Deploy key for jettison_proto_json-descriptors
- `SELF_PUSH` - Deploy key for pushing back to jettison_protogen repository

## Common Operations

### After Adding New Proto Messages

When new messages or fields are added to proto files, you MUST regenerate the documentation:

1. **Regenerate bindings** (creates updated JSON descriptors):
   ```bash
   make generate
   ```

2. **Regenerate documentation**:
   ```bash
   make docs-docker-generate
   ```

3. **Add descriptions** to new messages/fields in the generated markdown files in `docs/`

4. **Run lint** to verify no errors introduced:
   ```bash
   make docs-docker-lint
   ```

5. **Commit all changes** including the updated docs

### Understanding Message Context

**Before implementing features involving proto messages, read the documentation in `docs/`.**

The documentation provides:
- Message purpose and description
- Field constraints (validation rules like `gte`, `lte`, `required`)
- Field notes explaining semantic meaning
- Interaction metadata (UI patterns, semantic types, related commands)
- Related state messages and commands

**Quick ways to find message documentation:**
- Use `/proto-search <query>` to find messages by name or field
- Read `docs/proto/cmd.<Package>.<Message>.md` for command messages
- Read `docs/proto/ser.<Package>.<Message>.md` for state/data messages
- Check `docs/enums/` for enum definitions

### CI/CD Architecture

The repository uses a sequential workflow in GitHub Actions:

1. **Build Base Stage**: Builds and caches the Docker base image
2. **Sequential Generation**: All languages generated in a single job
3. **Push to Language Repos**: Sequentially push to each dedicated repository
4. **Update Main Repo**: Commit generated outputs back to jettison_protogen

This architecture provides:
- Simple execution flow for easier debugging
- Independent language repositories for consumers
- Automatic distribution without manual intervention
- Efficient Docker layer caching via GitHub Actions cache

### Adding a New Language
1. Add toolchain installation to Dockerfile
2. Create generation script in `generate-protos.sh`
3. Add output directory creation
4. Update documentation

### Debugging Generation Issues
```bash
# Check Docker logs for specific language
docker run --rm -it jettison-proto-generator:latest /bin/bash

# Test individual commands inside container
protoc --version
which protoc-gen-go
```

### Updating Dependencies
```bash
# Edit version variables in Dockerfile.base
PROTOC_VERSION=26.0
GO_VERSION=1.22.0

# Force rebuild using Make
make rebuild-base

# Or using script directly
REBUILD_IMAGE=true ./generate-protos.sh
```

### Using Make Commands
```bash
# Show help
make help

# Build Docker image only
make build

# Generate all proto bindings
make generate

# Clean and rebuild everything
make rebuild

# Open shell in container for debugging
make shell

# Show tool versions
make versions
```

## Technical Details

### Language-Specific Configurations

**C (nanopb)**
- Uses nanopb plugin for embedded-friendly code
- Removes all validation annotations via AWK preprocessing
- Generates fixed-size structs suitable for microcontrollers

**C++**
- Standard protoc generation with buf.validate annotations preserved
- Annotations embedded as field options/extensions in generated code
- Includes `buf/validate/validate.pb.h` header references
- Runtime validation requires protovalidate-cc and CEL-C++ libraries (not included in generated output)
- Applications must link against protovalidate-cc for runtime validation

**Go**
- Uses `buf generate` with remote BSR plugins (buf.build/protocolbuffers/go, buf.build/grpc/go)
- buf.validate annotations preserved for runtime validation with protovalidate-go
- Package paths preserved from proto files
- **Note:** Subject to BSR rate limits (see rate limits section below)

**Kotlin**
- Uses `buf generate` with remote BSR plugin (buf.build/protocolbuffers/kotlin:v33.5)
- buf.validate annotations preserved for runtime validation
- Generates Kotlin-specific protobuf classes with DSL builders
- Runtime validation requires protovalidate Kotlin library
- **Note:** Subject to BSR rate limits (see rate limits section below)

**Java**
- Standard protoc generation with buf.validate annotations preserved
- Runtime validation requires protovalidate Java library
- Package structure follows proto package declarations

**TypeScript (Standard)**
- Uses ts-proto for idiomatic TypeScript without validation
- Configured options: esModuleInterop, forceLong=long
- Generates index files for easier imports
- Output directory: `output/typescript/`

**TypeScript (Validated)**
- Uses @bufbuild/protoc-gen-es with @bufbuild/protovalidate
- Includes buf.validate annotations for runtime validation
- Generates TypeScript with validation support
- Published as @lpportorino/jettison-protovalidate-es
- Output directory: `output/typescript-validated/`

**Rust**
- Uses prost-build in a temporary Cargo project
- Handles all proto files in single compilation
- Creates module structure automatically

**Python**
- Generates both .py implementation and .pyi type stubs
- Uses standard protoc Python plugin
- Compatible with mypy type checking

### Validation Support

Proto files use buf.validate annotations for validation constraints. The validated outputs include these annotations in the generated code:
- **C++**: Standard protobuf generation with buf.validate annotations preserved as field options/extensions
- **Go**: Standard protobuf generation with buf.validate annotations preserved
- **Kotlin**: Standard protobuf generation with buf.validate annotations preserved
- **Java**: Standard protobuf generation with buf.validate annotations preserved

Runtime validation requires the protovalidate libraries:
- **C++**: https://github.com/bufbuild/protovalidate-cc (requires CEL-C++ 0.11.0+)
- **Go**: github.com/bufbuild/protovalidate-go
- **Kotlin**: build.buf:protovalidate-kotlin
- **Java**: build.buf.protovalidate

**Important Notes**:
- C++ generated code includes buf.validate header references, but applications must build and link against protovalidate-cc separately
- The protovalidate-cc library is not included in the Docker image or generated output (it's only needed at runtime by applications)
- We migrated from protoc-gen-validate (PGV) to buf protovalidate for better compatibility and modern validation approach

### JSON Descriptor Generation

The JSON descriptor generation script has been enhanced to use buf CLI when available, which properly preserves buf.validate annotations and CEL expressions:

1. **Primary method (buf CLI)**:
   - Detects if buf is installed in the Docker container
   - Uses `buf build` with `--exclude-source-info` flag for cleaner output
   - Generates both complete descriptor set and individual file descriptors
   - **Preserves all buf.validate annotations with CEL expressions**

2. **Fallback method (protoc + Python)**:
   - Used when buf CLI is not available
   - Attempts to preserve extensions using enhanced JSON serialization options
   - May not fully preserve custom extensions like buf.validate
   - Includes warning about potential limitation

**CEL Expression Preservation**:
- Validation rules are stored in field options under `[buf.validate.predefined]`
- Each rule includes:
  - `id`: Rule identifier (e.g., "float.gte", "int32.lt")
  - `expression`: Complete CEL expression for validation
  - Error message templates with formatting

**Example preserved annotation**:
```json
"options": {
  "[buf.validate.predefined]": {
    "cel": [{
      "id": "float.gte",
      "expression": "!has(rules.lt) && !has(rules.lte) && (this.isNan() || this < rules.gte)? 'value must be greater than or equal to %s'.format([rules.gte]) : ''"
    }]
  }
}
```

## Environment Variables

- `PROTO_SOURCE_DIR`: Input directory (default: `./proto`)
- `OUTPUT_BASE_DIR`: Output directory (default: `./output`)
- `REBUILD_IMAGE`: Force Docker rebuild (default: `false`)

## Known Limitations

1. C++ validation annotations are preserved in generated code, but runtime validation requires applications to build and link protovalidate-cc separately
2. nanopb (C) requires annotation removal (doesn't support extensions)
3. All proto files must be compiled together for cross-references
4. Docker required for consistent environment
5. GitHub Actions required for automated distribution
6. Buf Schema Registry (BSR) rate limits apply to Go and Kotlin generation (see below)

## Buf Schema Registry (BSR) Rate Limits

Go and Kotlin generation use `buf generate` with remote plugins, which connects to the Buf Schema Registry. Rate limits apply:

### Limits
| Service | Unauthenticated | Authenticated |
|---------|-----------------|---------------|
| Code Generation | 10 req/hour (10 burst) | 960 req/hour (120 burst) |
| General API | 30 req/sec (60 burst) | 30 req/sec (60 burst) |
| FileDescriptorSetService | 1 req/sec (2 burst) | 1 req/sec (2 burst) |

**Note:** Each `buf generate` command counts as one request (max 20 plugins per request).

### Detecting Rate Limits
- HTTP 429 response indicates rate limit exceeded
- Response headers: `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`

### Avoiding Rate Limits
1. **Authenticate requests**: Run `buf registry login` to increase code generation limit from 10/hour to 960/hour
2. **Batch generation**: Run `make generate` once rather than regenerating frequently
3. **Local plugins**: Consider using local plugins instead of remote BSR plugins for high-frequency development

### Troubleshooting
If Go or Kotlin generation fails with rate limit errors:
```bash
# Check if authenticated
buf registry whoami

# Login to BSR (increases limits significantly)
buf registry login
```

## Proto Documentation System

The `docs/` directory IS the Obsidian vault, containing generated markdown with roundtrip support (user documentation survives regeneration). Implementation files are in `.protodoc/`.

### Key Components

```
docs/                      # Obsidian vault (output)
├── .protodoc/             # Implementation files (hidden)
│   ├── proto-db.edn      # EDN database (git committed)
│   ├── scripts/          # Babashka scripts for Claude
│   │   ├── proto-search.clj
│   │   ├── proto-coverage.clj
│   │   ├── doc-next.clj
│   │   ├── proto-lint.clj
│   │   └── patch-lint.clj
│   └── tools/            # Clojure tooling
│       ├── src/protodoc/ # Core modules (parse, extract, render, lint, schema)
│       ├── test/protodoc/# Tests (83 tests, 335 assertions)
│       ├── resources/    # Selmer templates
│       ├── Dockerfile    # temurin-25 based
│       └── deps.edn      # Dependencies
├── cmd/                   # cmd.* messages
├── ser/                   # ser.* messages
├── enums/                 # Enum definitions
└── index.md               # Schema index
```

### Database Schema

The `proto-db.edn` file contains:

```clojure
{:messages {"cmd.DayCamera.SetIris" {:id "cmd.DayCamera.SetIris"
                                      :name "SetIris"
                                      :package "cmd.DayCamera"
                                      :source "jon_shared_cmd_day_camera.proto"
                                      :description "User docs (preserved)"
                                      :fields [{:number 1 :name "value" :type :double
                                                :constraints {:gte 0 :lte 1}}]}}
 :enums {"ser.JonGuiDataClientType" {...}}
 :search-index {"iris" ["cmd.DayCamera.SetIris"] ...}}
```

### Interaction Metadata

Messages and fields can have optional interaction metadata for platform-agnostic UI specifications:

```clojure
;; Message-level interaction
{:interaction {:category :actuator           ; :sensor :actuator :settings :status :lifecycle :diagnostic
               :ui-pattern :slider-with-presets  ; See UI patterns below
               :feedback :pending-timeout    ; :fire-and-forget :pending-timeout :poll-confirm :optimistic-visual
               :timeout-ms 2000
               :purpose "Controls the iris aperture"
               :related-state ["ser.JonGuiDataCameraDay"]
               :related-commands ["cmd.DayCamera.SetAutoIris"]
               :preconditions ["Camera must be started" "Auto-iris disabled"]
               :notes "Implementation notes"}}

;; Field-level interaction
{:interaction {:semantic-type :normalized    ; :angle :percentage :temperature :voltage etc.
               :unit "%"                     ; Display unit
               :precision 0                  ; Decimal places
               :display-format "{value * 100}%"
               :presets [0 0.25 0.5 0.75 1.0 "auto"]}}
```

**UI Patterns (hierarchical):**
- Atomic: `:toggle` `:action-button` `:slider` `:stepper` `:indicator` `:enum-picker`
- Molecular: `:slider-with-steppers` `:press-accelerating`
- Composite: `:slider-with-presets` `:directional-mover` `:tabbed-config` `:state-machine-menu`

**Semantic Types:** `:normalized` `:angle` `:percentage` `:coordinate-geo` `:coordinate-viewport` `:temperature` `:voltage` `:current` `:power` `:distance` `:duration` `:speed` `:count` `:timestamp` `:cardinal` `:enum-label` `:toggle-state` `:identifier` `:raw`

Interaction metadata survives roundtrip regeneration and appears in the `## Interaction` section of generated markdown.

### Common Operations

```bash
# Generate docs (from repo root)
make docs-generate
make docs-docker-generate  # In Docker

# Run tests
make docs-test
make docs-docker-test      # In Docker

# Render only (DB → markdown, no parsing/extraction)
make docs-render
make docs-docker-render    # In Docker

# Coverage report
make docs-coverage
make docs-docker-coverage  # In Docker

# Lint documentation quality
make docs-docker-lint      # In Docker

# Validate database
cd docs/.protodoc/tools && clojure -M:run validate --db-path ../../proto-db.edn

# Search proto schema (via Claude command)
/proto-search iris
/proto-search camera zoom

# Coverage report (via Claude command)
/proto-coverage
```

### Claude Slash Commands

Slash commands available for proto documentation:

- `/proto-search <query>` - Fuzzy search messages, fields, enums
- `/proto-coverage` - Show documentation coverage report
- `/doc-next` - Show next undocumented message with context

These use Babashka scripts that read directly from `.protodoc/proto-db.edn`.

### Interactive Documentation Filling

The `doc-fill` skill provides an interactive workflow for filling in missing documentation:

1. **Find what's missing**: Run `/doc-next` to see undocumented items grouped by module
2. **Review context**: See field types, constraints, and suggested questions
3. **Answer questions**: Claude asks about purpose, category, UI pattern, etc.
4. **Documentation written**: Claude edits the markdown file with collected info

**Workflow example:**
```
User: /doc-next
Claude: [Shows cmd.PMU.Start needs documentation]

User: Let's document it
Claude: [Invokes doc-fill skill, asks questions interactively]
- What does PMU.Start do?
- What category? (suggesting :lifecycle)
- UI pattern? (suggesting :action-button)
...

User: [Answers each question]
Claude: [Writes documentation to docs/proto/cmd.PMU.Start.md]
```

**Questions asked for each message:**
1. Purpose - What does this message do?
2. Category - :sensor :actuator :settings :status :lifecycle :diagnostic
3. UI Pattern - :toggle :action-button :slider :slider-with-presets etc.
4. Feedback - :fire-and-forget :pending-timeout :poll-confirm :optimistic-visual
5. Related state messages (ser.*)
6. Related commands
7. For each field: semantic type, unit, precision, display format

The skill suggests answers based on field constraints and naming patterns.

### Workflow

1. **Generate** - Parse JSON descriptors, extract user content, render markdown
2. **Edit** - Users edit markdown in `docs/` (descriptions, field notes)
3. **Regenerate** - User content extracted and preserved in new output
4. **Search** - Use `/proto-search` to find messages/fields

### Data Flow

```
descriptor-set.json → parse.clj → extract.clj → proto-db.edn → render.clj → docs/*.md
                                       ↑                              │
                                       └──────── user edits ──────────┘
```

### Testing

```bash
cd docs/.protodoc/tools
clojure -M:test  # 83 tests, 335 assertions

# Test categories:
# - schema_test.clj    - Malli validation, property-based
# - parse_test.clj     - JSON parsing, constraints, error handling
# - extract_test.clj   - Markdown extraction, frontmatter
# - render_test.clj    - Template rendering, wikilinks
# - roundtrip_test.clj - E2E preservation tests
# - core_test.clj      - CLI, integration
# - lint_test.clj      - Documentation quality rules
```

## References

### Internal Files
- See [`README.md`](./README.md) for user documentation
- See [`docs/.protodoc/tools/README.md`](./docs/.protodoc/tools/README.md) for proto docs tool documentation
- See [`scripts/proto_cleanup.awk`](./scripts/proto_cleanup.awk) for annotation removal logic

### External Documentation
- [Protocol Buffers](https://protobuf.dev/)
- [buf.validate](https://github.com/bufbuild/protovalidate)
- [nanopb](https://github.com/nanopb/nanopb)
- [ts-proto](https://github.com/stephenh/ts-proto)
- [prost](https://github.com/tokio-rs/prost)
- [Malli](https://github.com/metosin/malli) - Schema validation
- [Selmer](https://github.com/yogthos/Selmer) - Template rendering