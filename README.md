# Protogen - Docker-based Protocol Buffer Generator

A containerized environment for generating protocol buffer bindings for multiple languages with consistent tooling and versions. This repository automatically builds and distributes generated bindings to language-specific repositories.

## Features

- **Multi-language support**: C (nanopb), C++, Go, Kotlin, Python, TypeScript, Rust, Zig, and Java
- **Buf.validate support**: Go, C++, Kotlin, Java, and TypeScript (validated) bindings include validation support
- **Consistent environment**: All tools run in a controlled Docker container
- **Sequential generation**: All languages generated in a single GitHub Actions job
- **Automatic distribution**: Generated code pushed to language-specific repositories
- **Automatic cleanup**: Removes buf.validate annotations for languages that don't support them
- **CI/CD Integration**: Fully automated via GitHub Actions
- **Cross-language wire contract**: [`docs/INTERFACE-CONTRACTS.md`](docs/INTERFACE-CONTRACTS.md) is the canonical byte-level wire contract (stream framing, codec/transport headers, the `cmd.*`/state/enrichment encoding, the `controls.tar`/`controls.wasm` ABI + golden vectors) the downstream ARM web + native clients implement — update it when a proto change touches those surfaces

## Reference renderer & widget gallery

Beyond the language bindings, protogen owns the **`ui_ast` reference interpreter** — the C renderer under [`renderer/`](renderer/), compiled to the canonical `controls.wasm`, plus the devcard proof battery that gates it. The rendered documentation is browsable, not just the schema:

- **[Widget gallery](tools/devcards/docs/widgets/README.md)** — one rendered doc page per `ui.WidgetType`, each with stock and themed dark/light contact sheets, generated from the same corpus the devcard gates verify.
- **[Devcards tool](tools/devcards/README.md)** — the corpus runner, golden manifests, invariants, and the gallery/doc generator.

See [`CLAUDE.md`](CLAUDE.md) for the full renderer + devcards architecture.

## Prerequisites

- Docker installed and running
- Protocol buffer source files

## Installation

```bash
# Clone the repository
git clone https://github.com/lpportorino/jettison_protogen.git
cd jettison_protogen
```

The Docker base image will be automatically built on first use. This initial build may take 10-15 minutes but is only required once.

## Quick Start

### Using Make (Recommended)

```bash
# Show available commands
make help

# Generate all bindings (builds image if needed)
make generate

# Generate with custom source directory
make generate PROTO_SOURCE_DIR=/path/to/protos

# Force rebuild image and regenerate
make rebuild

# Clean generated files
make clean
```

### Using Scripts Directly

```bash
# Generate all bindings
./generate-protos.sh

# Generate with custom source directory
PROTO_SOURCE_DIR=/path/to/protos ./generate-protos.sh

# Force rebuild Docker image
REBUILD_IMAGE=true ./generate-protos.sh
```

## Output Distribution

Generated bindings are automatically distributed to dedicated repositories:

| Language | Repository | Package Support |
|----------|------------|----------------|
| C (nanopb) | [jettison_proto_c](https://github.com/lpportorino/jettison_proto_c) | Header files |
| C++ | [jettison_proto_cpp](https://github.com/lpportorino/jettison_proto_cpp) | Header files |
| Go | [jettison_proto_go](https://github.com/lpportorino/jettison_proto_go) | Go module |
| Kotlin | [jettison_proto_kotlin](https://github.com/lpportorino/jettison_proto_kotlin) | Maven/Gradle |
| Python | [jettison_proto_python](https://github.com/lpportorino/jettison_proto_python) | Python package |
| TypeScript | [jettison_proto_typescript](https://github.com/lpportorino/jettison_proto_typescript) | npm package |
| TypeScript (validated) | [jettison_protovalidate_es](https://github.com/lpportorino/jettison_protovalidate_es) | npm package |
| Rust | [jettison_proto_rust](https://github.com/lpportorino/jettison_proto_rust) | Cargo crate |
| Java | [jettison_proto_java](https://github.com/lpportorino/jettison_proto_java) | Maven/Gradle |
| JSON Descriptors | [jettison_proto_json-descriptors](https://github.com/lpportorino/jettison_proto_json-descriptors) | JSON files |

### Local Output

The `output/` directory in this repository contains the latest generated files:

```
output/
├── c/                    # C bindings (nanopb)
├── cpp/                  # C++ bindings with buf.validate support
├── go/                   # Go bindings with buf.validate support
├── kotlin/               # Kotlin bindings with buf.validate support
├── python/               # Python bindings with type stubs
├── typescript/           # TypeScript bindings (ts-proto, no validation)
├── typescript-validated/ # TypeScript bindings with protovalidate-es
├── rust/                 # Rust bindings (prost)
├── zig/                  # Zig bindings (zig-protobuf)
├── java/                 # Java bindings with buf.validate support
└── json-descriptors/     # JSON FileDescriptorSets with buf.validate annotations
```

**Note**: Go, C++, Kotlin, Java, and TypeScript-validated bindings include buf.validate support.


## Language-Specific Features

### C (nanopb)
- Embedded-friendly protocol buffers
- Automatically removes buf.validate annotations
- Generates `.pb.c` and `.pb.h` files

### Go
- Generated using buf with buf.validate annotations preserved
- Includes gRPC support
- Runtime validation requires protovalidate-go library

### Java
- Generated using protoc with buf.validate annotations preserved
- Validation metadata embedded in the generated code
- Java 17+ compatible code
- Runtime validation requires protovalidate-java library

### Kotlin
- Generated using local `protoc --kotlin_out` (not buf/BSR — the proto package must match the Java output)
- buf.validate annotations preserved for runtime validation
- Generates Kotlin-specific protobuf classes with DSL builders
- Runtime validation requires protovalidate-kotlin library

### TypeScript
- **Standard (ts-proto)**: Idiomatic TypeScript without validation
  - Configured with esModuleInterop and proper long handling
  - Available in `output/typescript/` directory
- **Validated (protoc-gen-es)**: TypeScript with runtime validation support
  - Uses @bufbuild/protoc-gen-es and @bufbuild/protovalidate
  - Includes buf.validate annotations for runtime validation
  - Available in `output/typescript-validated/` directory
  - Published as @lpportorino/jettison-protovalidate-es

### Rust
- Uses prost for Rust code generation
- Creates proper Rust module structure

### Zig
- Uses zig-protobuf (Arwalk/zig-protobuf) for Zig code generation
- Automatically removes buf.validate annotations
- Proto3 only

### Python
- Generates both `.py` files and `.pyi` type stubs
- Compatible with Python 3.x

### C++
- Standard protocol buffer generation with buf.validate annotations preserved
- Generated code includes validation metadata as field options/extensions
- Runtime validation requires protovalidate-cc library (see usage example below)
- Applications must build and link against [protovalidate-cc](https://github.com/bufbuild/protovalidate-cc)

### JSON Descriptors
- Complete FileDescriptorSet in JSON format generated using buf CLI
- **Includes all buf.validate annotations with CEL expressions preserved**
- Individual JSON files for each proto file
- Useful for tooling that needs to analyze proto schemas
- Can be parsed to extract validation constraints programmatically
- CEL expressions are available in field options under `[buf.validate.predefined]`
- Example validation rules preserved:
  - Range constraints: `gte`, `lte`, `gt`, `lt` with CEL expressions
  - Enum constraints: `defined_only`, `not_in`
  - Required fields: `required` on oneofs
  - Custom CEL validation expressions

## CI/CD Workflow

The repository uses GitHub Actions to automatically:

1. **Build Stage**: Build Docker base image with all language toolchains
2. **Generate Stage**: Generate bindings for all languages sequentially
3. **Push Stage**: Push generated code to each language-specific repository
4. **Update Stage**: Commit generated outputs back to main repository
5. **Release Stage**: Create GitHub release with all artifacts

### Workflow Triggers

- Push to `main` or `master` branch
- Changes to proto files, Dockerfiles, or scripts
- Manual workflow dispatch

## Configuration

### GitHub Secrets Required

For automated distribution, configure these deploy keys as repository secrets:

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

### Environment Variables

- `PROTO_SOURCE_DIR`: Source proto directory (default: `./proto`)
- `OUTPUT_BASE_DIR`: Output directory (default: `./output`)
- `REBUILD_IMAGE`: Force Docker image rebuild (default: `false`)

### Docker Base Image

The base Docker image contains all necessary dependencies and tools. It will be automatically built on first use if not present. The image is cached locally after the initial build.

### Docker Image Details

The Docker image bundles every toolchain this repo uses — proto codegen plus the
devcard/renderer proof battery. The exact pinned versions live in
`Dockerfile.base`, the source of truth. It includes:
- Ubuntu base
- Protocol Buffers compiler
- Go
- Rust
- Zig
- Python 3 with protobuf tools
- Node.js with TypeScript proto tools
- GraalVM Community JDK — the devcard renderer runs on any JDK 21+ (stock JDKs interpret the polyglot host); GraalVM CE JIT-compiles it, and apt's OpenJDK 17 is below the 21+ floor (see `tools/devcards/README.md`)
- Clojure CLI (the devcards corpus runner)
- WASI-SDK (the renderer wasm cross-compiler)
- nanopb for C generation
- buf CLI for validation support
- The protoc plugins the generators use

## Examples

### Using C++ Validation

C++ bindings include buf.validate metadata when generated. To use validation at runtime:

```cpp
#include <buf/validate/validator.h>
#include "jon_shared_data_camera_day.pb.h"

// Create validator factory and validator
auto factory_result = buf::validate::ValidatorFactory::New();
if (!factory_result.ok()) {
    std::cerr << "Failed to create validator factory" << std::endl;
    return 1;
}

google::protobuf::Arena arena;
buf::validate::Validator validator = factory_result.value()->NewValidator(&arena);

// Create and populate your message
ser::JonGuiDataCameraDay message;
message.set_zoom_pos(1.5);  // Invalid: exceeds max 1.0

// Validate the message
auto violations_result = validator.Validate(message);
if (!violations_result.ok()) {
    std::cerr << "Validation error: " << violations_result.status() << std::endl;
    return 1;
}

buf::validate::Violations violations = violations_result.value();
if (violations.violations_size() > 0) {
    // Handle validation errors
    for (const auto& violation : violations.violations()) {
        std::cerr << "Field: " << violation.field_path() << std::endl;
        std::cerr << "Constraint: " << violation.constraint_id() << std::endl;
        std::cerr << "Message: " << violation.message() << std::endl;
    }
}
```

**Dependencies**:
- [protovalidate-cc](https://github.com/bufbuild/protovalidate-cc) v1.0.0-rc.2+
- [CEL-C++](https://github.com/google/cel-cpp) v0.11.0+
- Add to your CMakeLists.txt or build system

**CMake Example**:
```cmake
find_package(protobuf-validate-cc REQUIRED)
find_package(cel-cpp REQUIRED)

target_link_libraries(your_target
    PRIVATE
    protovalidate-cc::protovalidate-cc
    cel-cpp::cel
)
```

### Using Java Validation

Java bindings include buf.validate metadata when generated. To use validation at runtime:

```java
import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.ValidationResult;

// Create a validator instance
Validator validator = ValidatorFactory.newBuilder().build();

// Validate a message
ValidationResult result = validator.validate(message);
if (!result.isSuccess()) {
    // Handle validation errors
    result.getViolations().forEach(violation -> {
        System.err.println("Field: " + violation.getFieldPath());
        System.err.println("Constraint: " + violation.getConstraintId());
        System.err.println("Message: " + violation.getMessage());
    });
}
```

**Dependencies**: Add `build.buf:protovalidate` to your Java project.

## Troubleshooting

### Docker not found
```bash
sudo systemctl start docker
```

### Permission denied
```bash
sudo usermod -aG docker $USER
# Log out and back in
```

### Proto files not found
Check that your proto source directory exists and contains `.proto` files.

## Advanced Usage

### Makefile Targets

Run `make help` for the full, current target list — it's generated from the
Makefile's own `##` comments, so it never drifts.

### Adding Custom Protoc Options

Edit the language-specific script sections in `generate-protos.sh`.

### Updating Tool Versions

Edit the pinned `*_VERSION` variables at the top of `Dockerfile.base`.

Then rebuild:
```bash
make rebuild
# or
REBUILD_IMAGE=true ./generate-protos.sh
```

## Proto Documentation

The repository includes a documentation generator for the proto schema. See [`docs/.protodoc/tools/README.md`](./docs/.protodoc/tools/README.md) for full details.

### Important: After Adding New Messages

When new proto messages or fields are added, regenerate the documentation:

```bash
make generate              # Regenerate bindings (updates JSON descriptors)
make docs-docker-generate  # Regenerate documentation
```

Then add descriptions to the new messages/fields in the generated markdown files in `docs/`.

### Finding Message Context

The `docs/` directory contains detailed documentation for all proto messages. Use this to understand:
- Message purpose and field semantics
- Validation constraints (ranges, required fields)
- UI interaction patterns and related commands

Search with `/proto-search <query>` or browse `docs/proto/` directly.

### Quick Start

```bash
# Generate documentation
make docs-generate

# Show coverage
make docs-coverage

# Search schema
make docs-search Q="iris"

# Run tests
make docs-test
```

### Docker Usage

```bash
make docs-docker-build     # Build image
make docs-docker-generate  # Generate docs
make docs-docker-test      # Run tests
make docs-docker-coverage  # Show coverage
```

### Claude Commands

Two slash commands are available for proto schema exploration:

- `/proto-search <query>` - Fuzzy search messages, fields, enums
- `/proto-coverage` - Show documentation coverage report

### Output Structure

```
docs/                   # The Obsidian vault (generated markdown)
├── index.md            # Generated schema index
├── proto/              # Generated per-message + per-enum markdown (cmd.* + ser.*)
└── .protodoc/          # Implementation (hidden)
    ├── proto-db.edn    # EDN database (git committed)
    ├── scripts/        # Babashka scripts (/proto-search, /doc-next, ...)
    └── tools/          # Clojure tooling (src, test, resources)
```

## License

This project is licensed under the GNU Affero General Public License v3.0
(AGPL-3.0-or-later) — see [`LICENSE`](./LICENSE).