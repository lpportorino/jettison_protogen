# Protogen - Docker-based Protocol Buffer Generator

A containerized environment for generating protocol buffer bindings for multiple languages with consistent tooling and versions. This repository automatically builds and distributes generated bindings to language-specific repositories.

## Features

- **Multi-language support**: C (nanopb), C++, Go, Python, TypeScript, Rust, and Java
- **Buf.validate support**: Go and Java bindings include validation support by default
- **Consistent environment**: All tools run in a controlled Docker container
- **Parallel generation**: Each language generated in parallel GitHub Actions jobs
- **Automatic distribution**: Generated code pushed to language-specific repositories
- **Automatic cleanup**: Removes buf.validate annotations for languages that don't support them
- **CI/CD Integration**: Fully automated via GitHub Actions

## Prerequisites

- Docker installed and running
- Protocol buffer source files

## Installation

```bash
# Clone the repository
git clone https://github.com/JAremko/protogen.git
cd protogen
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
| C (nanopb) | [jettison_proto_c](https://github.com/lpportorico/jettison_proto_c) | Header files |
| C++ | [jettison_proto_cpp](https://github.com/lpportorico/jettison_proto_cpp) | Header files |
| Go | [jettison_proto_go](https://github.com/lpportorico/jettison_proto_go) | Go module |
| Python | [jettison_proto_python](https://github.com/lpportorico/jettison_proto_python) | Python package |
| TypeScript | [jettison_proto_typescript](https://github.com/lpportorico/jettison_proto_typescript) | npm package |
| Rust | [jettison_proto_rust](https://github.com/lpportorico/jettison_proto_rust) | Cargo crate |
| Java | [jettison_proto_java](https://github.com/lpportorico/jettison_proto_java) | Maven/Gradle |
| JSON Descriptors | [jettison_proto_json-descriptors](https://github.com/lpportorico/jettison_proto_json-descriptors) | JSON files |

### Local Output

The `output/` directory in this repository contains the latest generated files:

```
output/
├── c/               # C bindings (nanopb)
├── cpp/             # C++ bindings
├── go/              # Go bindings with buf.validate support
├── python/          # Python bindings with type stubs
├── typescript/      # TypeScript bindings (ts-proto)
├── rust/            # Rust bindings (prost)
├── java/            # Java bindings with buf.validate support
└── json-descriptors/# JSON FileDescriptorSets with buf.validate annotations
```

**Note**: Go and Java bindings include buf.validate support by default.


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
2. **Generate Stage**: Run parallel jobs for each language
3. **Push Stage**: Each job pushes to its respective language repository
4. **Gather Stage**: Consolidate outputs back to main repository
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
- `PYTHON_PUSH` - Deploy key for jettison_proto_python
- `TYPESCRIPT_PUSH` - Deploy key for jettison_proto_typescript
- `RUST_PUSH` - Deploy key for jettison_proto_rust
- `JAVA_PUSH` - Deploy key for jettison_proto_java
- `JSON_DESCRIPTORS_PUSH` - Deploy key for jettison_proto_json-descriptors

### Environment Variables

- `PROTO_SOURCE_DIR`: Source proto directory (default: `../proto`)
- `OUTPUT_BASE_DIR`: Output directory (default: `./output`)
- `REBUILD_IMAGE`: Force Docker image rebuild (default: `false`)

### Docker Base Image

The base Docker image contains all necessary dependencies and tools. It will be automatically built on first use if not present. The image is cached locally after the initial build.

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
- `make rebuild-base` - Force rebuild base image
- `make clean` - Remove generated files
- `make clean-all` - Remove generated files and Docker images
- `make clean-image` - Remove the main Docker image
- `make clean-base` - Remove the base Docker image
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