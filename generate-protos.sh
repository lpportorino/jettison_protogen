#!/usr/bin/env bash

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
DOCKER_IMAGE="jettison-proto-generator:latest"
PROTO_SOURCE_DIR="${PROTO_SOURCE_DIR:-../proto}"
OUTPUT_BASE_DIR="${OUTPUT_BASE_DIR:-./output}"
VALIDATE_OUTPUT_DIR="${VALIDATE_OUTPUT_DIR:-./output-validated}"

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed. Please install Docker first."
    exit 1
fi

# Check if proto source directory exists
if [ ! -d "$PROTO_SOURCE_DIR" ]; then
    print_error "Proto source directory not found: $PROTO_SOURCE_DIR"
    print_info "Set PROTO_SOURCE_DIR environment variable or ensure ../proto exists"
    exit 1
fi

# Function to check if Docker image exists
image_exists() {
    docker image inspect "$DOCKER_IMAGE" &> /dev/null
}

# Build Docker image if it doesn't exist
build_image() {
    print_info "Building Docker image: $DOCKER_IMAGE"
    if docker build -t "$DOCKER_IMAGE" .; then
        print_info "Docker image built successfully"
    else
        print_error "Failed to build Docker image"
        exit 1
    fi
}

# Check if image exists, build if not
if ! image_exists; then
    print_warning "Docker image not found. Building..."
    build_image
else
    print_info "Docker image found: $DOCKER_IMAGE"
    # Optional: Ask if user wants to rebuild
    if [ "${REBUILD_IMAGE:-false}" == "true" ]; then
        print_info "Rebuilding image (REBUILD_IMAGE=true)"
        build_image
    fi
fi

# Create output directories
print_info "Creating output directories..."
mkdir -p "$OUTPUT_BASE_DIR"/{c,cpp,go,python,typescript,rust,java}
mkdir -p "$VALIDATE_OUTPUT_DIR"/{go,java}

# Copy proto files to local directory to avoid permission issues
print_info "Preparing proto files..."
# Only copy if source is different from ./proto
if [ "$PROTO_SOURCE_DIR" != "./proto" ]; then
    rm -rf ./proto
    cp -r "$PROTO_SOURCE_DIR" ./proto
fi

# Function to run generation in Docker
run_generation() {
    local lang=$1
    local script=$2
    
    print_info "Generating $lang bindings..."
    
    docker run --rm \
        -u "$(id -u):$(id -g)" \
        -v "$SCRIPT_DIR/proto:/workspace/proto:ro" \
        -v "$SCRIPT_DIR/output/$lang:/workspace/output:rw" \
        -v "$SCRIPT_DIR/scripts:/workspace/scripts:ro" \
        -w /workspace \
        "$DOCKER_IMAGE" \
        -c "$script"
    
    if [ $? -eq 0 ]; then
        print_info "$lang generation completed successfully"
    else
        print_error "$lang generation failed"
        return 1
    fi
}

# C generation script
C_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$(basename "$proto")"
done
find /tmp/cleaned_proto -name "*.proto" -print0 | xargs -0 -P 8 -I{} \
    protoc --plugin=protoc-gen-nanopb=/opt/nanopb/generator/protoc-gen-nanopb \
    -I/tmp/cleaned_proto \
    --nanopb_out=/workspace/output \
    {}
'

# C++ generation script
CPP_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$(basename "$proto")"
done
protoc -I/tmp/cleaned_proto \
    --cpp_out=/workspace/output \
    /tmp/cleaned_proto/*.proto
'

# Go generation script (without validation)
GO_SCRIPT='
set -e
mkdir -p /tmp/go_proto_clean
# Copy proto files and remove validate annotations
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/go_proto_clean/$(basename "$proto")"
done

# Generate all proto files together to resolve imports
protoc -I/tmp/go_proto_clean \
    --go_out=/workspace/output \
    --go-grpc_out=/workspace/output \
    /tmp/go_proto_clean/*.proto
'

# Python generation script
PYTHON_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$(basename "$proto")"
done
protoc -I/tmp/cleaned_proto \
    --python_out=/workspace/output \
    --pyi_out=/workspace/output \
    /tmp/cleaned_proto/*.proto
'

# TypeScript generation script
TYPESCRIPT_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$(basename "$proto")"
done

# Create temporary node project for ts-proto
cd /tmp
npm init -y
npm install ts-proto

protoc -I/tmp/cleaned_proto \
    --plugin=protoc-gen-ts_proto=/tmp/node_modules/.bin/protoc-gen-ts_proto \
    --ts_proto_opt=outputIndex=true \
    --ts_proto_opt=esModuleInterop=true \
    --ts_proto_opt=forceLong=long \
    --ts_proto_out=/workspace/output \
    /tmp/cleaned_proto/*.proto
'

# Rust generation script
RUST_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto /tmp/rust_gen
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$(basename "$proto")"
done

# Create a Rust project for generation
cd /tmp/rust_gen
cat > Cargo.toml << EOF
[package]
name = "proto-gen"
version = "0.1.0"
edition = "2021"

[dependencies]
prost = "0.13"

[build-dependencies]
prost-build = "0.13"
EOF

mkdir -p src
cat > build.rs << "EOF"
use std::io::Result;

fn main() -> Result<()> {
    let proto_files: Vec<_> = std::fs::read_dir("/tmp/cleaned_proto")?
        .filter_map(|entry| {
            let entry = entry.ok()?;
            let path = entry.path();
            if path.extension()? == "proto" {
                Some(path)
            } else {
                None
            }
        })
        .collect();
    
    prost_build::Config::new()
        .out_dir("/workspace/output")
        .compile_protos(&proto_files, &["/tmp/cleaned_proto"])?;
    
    Ok(())
}
EOF

cat > src/main.rs << "EOF"
fn main() {}
EOF

cargo build
'

# Java generation script (without validation)
JAVA_SCRIPT='
set -e
mkdir -p /tmp/java_proto_clean

# Copy proto files and remove validate annotations
for proto in proto/*.proto; do
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/java_proto_clean/$(basename "$proto")"
done

# Generate all proto files together
protoc -I/tmp/java_proto_clean \
    --java_out=/workspace/output \
    /tmp/java_proto_clean/*.proto
'

# Go generation script with protovalidate (new approach)
GO_VALIDATE_SCRIPT='
set -e
mkdir -p /tmp/go_proto_val

# Copy proto files and add validate import
for proto in proto/*.proto; do
    cp "$proto" "/tmp/go_proto_val/$(basename "$proto")"
    /usr/local/bin/add-validate-import.sh "/tmp/go_proto_val/$(basename "$proto")"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/go_proto_val/

# Generate all proto files together
protoc -I/tmp/go_proto_val \
    --go_out=/workspace/output-validated \
    --go-grpc_out=/workspace/output-validated \
    --validate_out="lang=go:/workspace/output-validated" \
    /tmp/go_proto_val/*.proto
'

# Java generation script with protovalidate
JAVA_VALIDATE_SCRIPT='
set -e
mkdir -p /tmp/java_proto_val

# Copy proto files and add validate import
for proto in proto/*.proto; do
    cp "$proto" "/tmp/java_proto_val/$(basename "$proto")"
    /usr/local/bin/add-validate-import.sh "/tmp/java_proto_val/$(basename "$proto")"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/java_proto_val/

# Generate all proto files together
protoc -I/tmp/java_proto_val \
    --java_out=/workspace/output-validated \
    --validate_out="lang=java:/workspace/output-validated" \
    /tmp/java_proto_val/*.proto
'

# C++ validation removed - not needed and has compatibility issues

# Run all generations
FAILED_LANGS=()

for lang in c cpp go python typescript rust java; do
    case $lang in
        c) script="$C_SCRIPT" ;;
        cpp) script="$CPP_SCRIPT" ;;
        go) script="$GO_SCRIPT" ;;
        python) script="$PYTHON_SCRIPT" ;;
        typescript) script="$TYPESCRIPT_SCRIPT" ;;
        rust) script="$RUST_SCRIPT" ;;
        java) script="$JAVA_SCRIPT" ;;
    esac
    
    if ! run_generation "$lang" "$script"; then
        FAILED_LANGS+=("$lang")
    fi
done

# Set permissions on generated files to 777 so anyone can delete/modify them
print_info "Setting file permissions on generated files..."
chmod -R 777 "$OUTPUT_BASE_DIR" 2>/dev/null || true

# Run validated generations for supported languages
print_info "========== Generating Validated Bindings =========="

for lang in go java; do
    case $lang in
        go) script="$GO_VALIDATE_SCRIPT" ;;
        java) script="$JAVA_VALIDATE_SCRIPT" ;;
    esac
    
    print_info "Generating validated $lang bindings..."
    
    docker run --rm \
        -u "$(id -u):$(id -g)" \
        -v "$SCRIPT_DIR/proto:/workspace/proto:ro" \
        -v "$SCRIPT_DIR/$VALIDATE_OUTPUT_DIR/$lang:/workspace/output-validated:rw" \
        -v "$SCRIPT_DIR/scripts:/workspace/scripts:ro" \
        -w /workspace \
        "$DOCKER_IMAGE" \
        -c "$script"
    
    if [ $? -eq 0 ]; then
        print_info "Validated $lang generation completed successfully"
    else
        print_error "Validated $lang generation failed"
        FAILED_LANGS+=("validated-$lang")
    fi
done

# Set permissions on validated generated files to 777
chmod -R 777 "$VALIDATE_OUTPUT_DIR" 2>/dev/null || true

# Summary
echo
print_info "========== Generation Summary =========="
print_info "Output directory: $OUTPUT_BASE_DIR"
print_info "Validated output directory: $VALIDATE_OUTPUT_DIR"

# Check what was generated
for lang in c cpp go python typescript rust java; do
    count=$(find "$OUTPUT_BASE_DIR/$lang" -type f 2>/dev/null | wc -l)
    if [ $count -gt 0 ]; then
        print_info "$lang: $count files generated"
    else
        print_warning "$lang: No files generated"
    fi
done

# Check validated outputs
print_info ""
print_info "Validated bindings:"
for lang in go java; do
    count=$(find "$VALIDATE_OUTPUT_DIR/$lang" -type f 2>/dev/null | wc -l)
    if [ $count -gt 0 ]; then
        print_info "validated $lang: $count files generated"
    else
        print_warning "validated $lang: No files generated"
    fi
done

if [ ${#FAILED_LANGS[@]} -gt 0 ]; then
    print_error "Failed languages: ${FAILED_LANGS[*]}"
    exit 1
else
    print_info "All generations completed successfully!"
fi

# Optional: Clean up proto copy
rm -rf ./proto