# CLAUDE.md - Protogen Module

This file provides guidance to Claude Code when working with the Protogen module.

## Module Overview

Protogen is a Docker-based protocol buffer code generator that supports multiple programming languages with consistent tooling and versions. It provides both standard bindings and validated bindings (for Go and Java) using buf.validate annotations.

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
- `output/` - Standard generated bindings without validation (created at runtime)
- `output-validated/` - Generated bindings with validation support (Go and Java only, created at runtime)
- `scripts/` - Contains helper scripts like proto_cleanup.awk

### Generated Output Structure
```
output/
├── c/          # nanopb C bindings
├── cpp/        # C++ bindings
├── go/         # Go bindings (without validation)
├── java/       # Java bindings (without validation)
├── python/     # Python bindings with type stubs
├── rust/       # Rust bindings using prost
└── typescript/ # TypeScript bindings using ts-proto

output-validated/
├── go/         # Go bindings with protoc-gen-validate
└── java/       # Java bindings with protoc-gen-validate
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
| Python | [jettison_proto_python](https://github.com/lpportorino/jettison_proto_python) |
| TypeScript | [jettison_proto_typescript](https://github.com/lpportorino/jettison_proto_typescript) |
| Rust | [jettison_proto_rust](https://github.com/lpportorino/jettison_proto_rust) |
| Java | [jettison_proto_java](https://github.com/lpportorino/jettison_proto_java) |
| JSON Descriptors | [jettison_proto_json-descriptors](https://github.com/lpportorino/jettison_proto_json-descriptors) |

### GitHub Secrets Required

For automated distribution, these deploy keys must be configured as repository secrets:

- `C_PUSH` - Deploy key for jettison_proto_c
- `CPP_PUSH` - Deploy key for jettison_proto_cpp
- `GO_PUSH` - Deploy key for jettison_proto_go
- `PYTHON_PUSH` - Deploy key for jettison_proto_python
- `TYPESCRIPT_PUSH` - Deploy key for jettison_proto_typescript
- `RUST_PUSH` - Deploy key for jettison_proto_rust
- `JAVA_PUSH` - Deploy key for jettison_proto_java
- `JSON_DESCRIPTORS_PUSH` - Deploy key for jettison_proto_json-descriptors
- `SELF_PUSH` - Deploy key for pushing back to jettison_protogen repository

## Common Operations

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

**Go**
- Standard generation uses official protoc-gen-go
- Validation uses envoyproxy/protoc-gen-validate
- Package paths preserved from proto files

**Java**
- Standard generation uses built-in Java support
- Validation uses protoc-gen-validate
- Package structure follows proto package declarations

**TypeScript**
- Uses ts-proto for idiomatic TypeScript
- Configured options: esModuleInterop, forceLong=long
- Generates index files for easier imports

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
- Go: Standard protobuf generation with buf.validate annotations preserved
- Java: Standard protobuf generation with buf.validate annotations preserved
- Runtime validation: Applications should use the protovalidate libraries:
  - Go: github.com/bufbuild/protovalidate-go
  - Java: build.buf.protovalidate

Note: We migrated from protoc-gen-validate (PGV) to buf protovalidate for better compatibility and modern validation approach.

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

1. C++ validation not supported (library compatibility issues)
2. nanopb requires annotation removal (doesn't support extensions)
3. All proto files must be compiled together for cross-references
4. Docker required for consistent environment
5. GitHub Actions required for automated distribution

## References

### Internal Files
- See [`README.md`](./README.md) for user documentation
- See [`scripts/proto_cleanup.awk`](./scripts/proto_cleanup.awk) for annotation removal logic

### External Documentation
- [Protocol Buffers](https://protobuf.dev/)
- [buf.validate](https://github.com/bufbuild/protovalidate)
- [nanopb](https://github.com/nanopb/nanopb)
- [ts-proto](https://github.com/stephenh/ts-proto)
- [prost](https://github.com/tokio-rs/prost)