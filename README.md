# Protogen - Docker-based Protocol Buffer Generator

A containerized environment for generating protocol buffer bindings for multiple languages with consistent tooling and versions.

## Features

- **Multi-language support**: C (nanopb), C++, Go, Python, TypeScript, Rust, and Java
- **Buf Validate support**: Go and Java bindings include validation code generation
- **Consistent environment**: All tools run in a controlled Docker container
- **Parallel generation**: Optimized for speed with parallel processing where applicable
- **Automatic cleanup**: Removes buf.validate annotations for languages that don't support them

## Prerequisites

- Docker installed and running
- Protocol buffer source files

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

## Output Structure

Generated files are organized by language in two directories:

### Standard Bindings (without validation annotations)
```
output/
├── c/          # C bindings (nanopb)
├── cpp/        # C++ bindings
├── go/         # Go bindings
├── python/     # Python bindings with type stubs
├── typescript/ # TypeScript bindings (ts-proto)
├── rust/       # Rust bindings (prost)
└── java/       # Java bindings
```

### Validated Bindings (with buf.validate support)
```
output-validated/
├── go/         # Go bindings with protoc-gen-validate
└── java/       # Java bindings with protoc-gen-validate
```


## Language-Specific Features

### C (nanopb)
- Embedded-friendly protocol buffers
- Automatically removes buf.validate annotations
- Generates `.pb.c` and `.pb.h` files

### Go
- Standard bindings without validation code
- Validated bindings include protoc-gen-validate support
- Both versions generate with gRPC support

### Java
- Standard bindings without validation code
- Validated bindings include protoc-gen-validate support
- Java 21 compatible code
- See `examples/JavaValidationExample.java` for usage

### TypeScript
- Uses ts-proto for idiomatic TypeScript code
- Configured with esModuleInterop and proper long handling

### Rust
- Uses prost for Rust code generation
- Creates proper Rust module structure

### Python
- Generates both `.py` files and `.pyi` type stubs
- Compatible with Python 3.x

### C++
- Standard protocol buffer generation
- No validation support (use protovalidate-cc if needed)

## Configuration

### Environment Variables

- `PROTO_SOURCE_DIR`: Source proto directory (default: `../proto`)
- `OUTPUT_BASE_DIR`: Standard output directory (default: `./output`)
- `VALIDATE_OUTPUT_DIR`: Validated output directory (default: `./output-validated`)
- `REBUILD_IMAGE`: Force Docker image rebuild (default: `false`)

### Docker Image Details

The Docker image includes:
- Ubuntu 24.04 base
- Protocol Buffers compiler 25.1
- Go 1.21.13
- Rust 1.83.0
- Python 3 with protobuf tools
- Node.js with TypeScript proto tools
- Java 21 with Maven
- nanopb for C generation
- All necessary protoc plugins

## Examples

### Using Java Validation

```java
import build.buf.protovalidate.Validator;
import build.buf.protovalidate.results.ValidationException;

Validator validator = new Validator();
try {
    validator.validate(message);
} catch (ValidationException e) {
    // Handle validation errors
}
```

See `examples/JavaValidationExample.java` for a complete example.

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

- `make help` - Show all available targets
- `make build` - Build Docker image only
- `make generate` - Build image and generate bindings
- `make rebuild` - Force rebuild image and regenerate
- `make clean` - Remove generated files
- `make clean-all` - Remove generated files and Docker image
- `make test` - Run test generation with test proto
- `make shell` - Open shell in Docker container
- `make versions` - Show tool versions in Docker image

### Adding Custom Protoc Options

Edit the language-specific script sections in `generate-protos.sh`.

### Updating Tool Versions

Edit version variables in `Dockerfile`:
- `PROTOC_VERSION`
- `GO_VERSION`
- `RUST_VERSION`

Then rebuild:
```bash
make rebuild
# or
REBUILD_IMAGE=true ./generate-protos.sh
```

## License

This project is licensed under the MIT License.