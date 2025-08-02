# Protogen - Docker-based Protocol Buffer Generator

A containerized environment for generating protocol buffer bindings for multiple languages with consistent tooling and versions.

## Features

- **Multi-language support**: C (nanopb), C++, Go, Python, TypeScript, Rust, and Java
- **Buf.validate support**: Java generation now uses buf CLI for proper validation metadata embedding
- **Consistent environment**: All tools run in a controlled Docker container
- **Parallel generation**: Optimized for speed with parallel processing where applicable
- **Automatic cleanup**: Removes buf.validate annotations for languages that don't support them

## Prerequisites

- Docker installed and running
- Protocol buffer source files
- Git LFS (Large File Storage) for cloning the repository

## Installation

### Cloning the Repository

This repository includes a pre-built Docker base image to speed up builds. The image is stored using Git LFS.

```bash
# Clone the repository
git clone https://github.com/JAremko/protogen.git
cd protogen

# Pull the pre-built base image (if you have Git LFS)
git lfs pull
```

**Note**: The base image is ~1.2GB. If you don't have Git LFS or prefer to build from scratch, the system will automatically build the base image when needed.

#### Installing Git LFS (Optional)
If you want to use the pre-built base image:
- Ubuntu/Debian: `sudo apt-get install git-lfs`
- Fedora: `sudo dnf install git-lfs`
- macOS: `brew install git-lfs`
- Arch Linux: `sudo pacman -S git-lfs`

After installing, initialize Git LFS:
```bash
git lfs install
```

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
├── c/               # C bindings (nanopb)
├── cpp/             # C++ bindings
├── go/              # Go bindings
├── python/          # Python bindings with type stubs
├── typescript/      # TypeScript bindings (ts-proto)
├── rust/            # Rust bindings (prost)
├── java/            # Java bindings
└── json-descriptors/# JSON FileDescriptorSets with buf.validate annotations
```

### Validated Bindings (with buf.validate support)
```
output-validated/
└── go/         # Go bindings with buf.validate annotations
```

**Note**: Java validation is now handled directly in the standard bindings using buf CLI, which properly embeds validation metadata. Use the protovalidate-java runtime library for validation.


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
- Generated using buf CLI for proper buf.validate metadata embedding
- Validation annotations are preserved in the generated code
- Java 17+ compatible code
- Runtime validation requires protovalidate-java library

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

### JSON Descriptors
- Complete FileDescriptorSet in JSON format
- Includes all buf.validate annotations
- Individual JSON files for each proto file
- Useful for tooling that needs to analyze proto schemas
- Can be parsed to extract validation constraints programmatically

## Configuration

### Environment Variables

- `PROTO_SOURCE_DIR`: Source proto directory (default: `../proto`)
- `OUTPUT_BASE_DIR`: Standard output directory (default: `./output`)
- `VALIDATE_OUTPUT_DIR`: Validated output directory (default: `./output-validated`)
- `REBUILD_IMAGE`: Force Docker image rebuild (default: `false`)

### Pre-built Base Image

The repository includes a pre-built base Docker image (`jettison-proto-generator-base.tar.gz`) stored via Git LFS. This image contains all necessary dependencies and tools, saving significant build time on first use.

To use the pre-built image:
```bash
# Import the base image (automatic with Make targets)
make import-base

# Or manually
docker load < jettison-proto-generator-base.tar.gz
```

### Docker Image Details

The Docker image includes:
- Ubuntu 24.04 base
- Protocol Buffers compiler 25.1
- Go 1.21.13
- Rust 1.83.0
- Python 3 with protobuf tools
- Node.js with TypeScript proto tools
- Java 17 (OpenJDK)
- nanopb for C generation
- buf CLI for proper validation support
- All necessary protoc plugins

## Examples

### Using Java Validation

Java bindings now include buf.validate metadata when generated. To use validation at runtime:

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

- `make help` - Show all available targets
- `make build` - Build Docker image only
- `make build-base` - Build the base Docker image with all dependencies
- `make generate` - Build image and generate bindings
- `make rebuild` - Force rebuild image and regenerate
- `make rebuild-base` - Force rebuild base image and export
- `make clean` - Remove generated files
- `make clean-all` - Remove generated files and Docker images
- `make clean-image` - Remove the main Docker image
- `make clean-base` - Remove the base Docker image
- `make export-base` - Export base image to a gzip archive
- `make import-base` - Import base image from gzip archive
- `make test` - Run test generation with test proto
- `make shell` - Open shell in Docker container
- `make versions` - Show tool versions in Docker image

### Adding Custom Protoc Options

Edit the language-specific script sections in `generate-protos.sh`.

### Updating Tool Versions

Edit version variables in `Dockerfile.base`:
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