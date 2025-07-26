# CLAUDE.md - Protogen Module

This file provides guidance to Claude Code when working with the Protogen module.

## Module Overview

Protogen is a Docker-based protocol buffer code generator that supports multiple programming languages with consistent tooling and versions. It provides both standard bindings and validated bindings (for Go and Java) using buf.validate annotations.

## Module Structure

### Core Files
- `Makefile` - Build automation with targets for image building and proto generation
- `generate-protos.sh` - Main generation script that orchestrates Docker container execution
- `Dockerfile` - Defines the build environment with all necessary tools and dependencies
- `scripts/proto_cleanup.awk` - AWK script to remove buf.validate annotations for incompatible languages

### Directories
- `proto/` - Input directory containing .proto files to process
- `output/` - Standard generated bindings without validation
- `output-validated/` - Generated bindings with validation support (Go and Java only)
- `examples/` - Usage examples for validated bindings
- `test-proto/` - Test proto files for development

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

## Common Operations

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
# Edit version variables in Dockerfile
PROTOC_VERSION=26.0
GO_VERSION=1.22.0

# Force rebuild using Make
make rebuild

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

Only Go and Java support validation due to library availability:
- Go: Uses protoc-gen-validate from Envoy
- Java: Uses same protoc-gen-validate plugin
- Runtime libraries required: protovalidate-go, protovalidate-java

C++ validation was removed due to compatibility issues with current protoc versions.

## Environment Variables

- `PROTO_SOURCE_DIR`: Input directory (default: `../proto`)
- `OUTPUT_BASE_DIR`: Standard output (default: `./output`)
- `VALIDATE_OUTPUT_DIR`: Validated output (default: `./output-validated`)
- `REBUILD_IMAGE`: Force Docker rebuild (default: `false`)

## Known Limitations

1. C++ validation not supported (library compatibility issues)
2. nanopb requires annotation removal (doesn't support extensions)
3. All proto files must be compiled together for cross-references
4. Docker required for consistent environment

## References

### Internal Files
- See [`README.md`](./README.md) for user documentation
- See [`examples/JavaValidationExample.java`](./examples/JavaValidationExample.java) for validation usage
- See [`scripts/proto_cleanup.awk`](./scripts/proto_cleanup.awk) for annotation removal logic

### External Documentation
- [Protocol Buffers](https://protobuf.dev/)
- [buf.validate](https://github.com/bufbuild/protovalidate)
- [nanopb](https://github.com/nanopb/nanopb)
- [ts-proto](https://github.com/stephenh/ts-proto)
- [prost](https://github.com/tokio-rs/prost)