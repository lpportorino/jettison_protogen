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

# Create output directories with full permissions
print_info "Creating output directories..."
mkdir -p "$OUTPUT_BASE_DIR"/{c,cpp,go,python,typescript,rust,java,json-descriptors}
# Set directory permissions to 777
chmod -R 777 "$OUTPUT_BASE_DIR" 2>/dev/null || true

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
    
    # Modified script to set permissions inside container
    local full_script="$script
# Set permissions to 777 for all generated files
find /workspace/output -type f -exec chmod 777 {} + 2>/dev/null || true
find /workspace/output -type d -exec chmod 777 {} + 2>/dev/null || true"
    
    docker run --rm \
        -v "$SCRIPT_DIR/proto:/workspace/proto:ro" \
        -v "$SCRIPT_DIR/output/$lang:/workspace/output:rw" \
        -v "$SCRIPT_DIR/scripts:/workspace/scripts:ro" \
        -w /workspace \
        "$DOCKER_IMAGE" \
        -c "$full_script"
    
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
# Copy nanopb runtime files that are needed for compilation
cp /opt/nanopb/pb.h /workspace/output/
cp /opt/nanopb/pb_common.h /workspace/output/
cp /opt/nanopb/pb_common.c /workspace/output/
cp /opt/nanopb/pb_encode.h /workspace/output/
cp /opt/nanopb/pb_encode.c /workspace/output/
cp /opt/nanopb/pb_decode.h /workspace/output/
cp /opt/nanopb/pb_decode.c /workspace/output/
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

# Go generation script with validation support
GO_SCRIPT='
set -e
mkdir -p /tmp/go_proto_val

# Copy proto files and add validate import
for proto in proto/*.proto; do
    cp "$proto" "/tmp/go_proto_val/$(basename "$proto")"
    /usr/local/bin/add-validate-import.sh "/tmp/go_proto_val/$(basename "$proto")"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/go_proto_val/

# Create buf.yaml for the generation
cd /tmp/go_proto_val
cat > buf.yaml << "BUF_EOF"
version: v2
modules:
  - path: .
    name: buf.build/jettison/jonp
BUF_EOF

# Create buf.gen.yaml for Go generation with validation
cat > buf.gen.yaml << "BUF_EOF"
version: v2
managed:
  enabled: true
  override:
    - file_option: go_package_prefix
      value: ""
plugins:
  - remote: buf.build/protocolbuffers/go:v1.36.6
    out: /workspace/output
  - remote: buf.build/grpc/go:v1.3.0
    out: /workspace/output
BUF_EOF

# Ensure output directory exists
mkdir -p /workspace/output

# Generate using buf
echo "Generating Go bindings with buf.validate support using buf generate..."
buf generate

# Verify files were generated
if [ -z "$(find /workspace/output -name "*.pb.go" -type f 2>/dev/null)" ]; then
    echo "ERROR: No Go files were generated!"
    exit 1
fi
echo "Go generation successful, found $(find /workspace/output -name "*.pb.go" -type f | wc -l) .pb.go files"
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

# Ensure the output directory exists
mkdir -p /workspace/output

# Set PATH to include cargo - try multiple locations
export PATH="/opt/rust/bin:/root/.cargo/bin:/usr/local/bin:$PATH"

# Debug output
echo "PATH: $PATH"
echo "Checking cargo availability..."

# If cargo is not accessible, try to find it
if ! command -v cargo &> /dev/null; then
    echo "cargo not found in PATH"
    # Try to find cargo in common locations
    if [ -f "/root/.cargo/bin/cargo" ]; then
        echo "cargo found at /root/.cargo/bin/cargo"
        # Check if we can execute it
        if /root/.cargo/bin/cargo --version &> /dev/null; then
            echo "cargo is executable, adding to PATH"
            export PATH="/root/.cargo/bin:$PATH"
        else
            echo "cargo not executable, trying to copy to accessible location"
            # If cargo exists but not accessible due to permissions, skip Rust generation
            echo "WARNING: Skipping Rust generation due to permission issues with cargo"
            echo "To fix this, rebuild the base image with proper Rust installation"
            exit 0
        fi
    else
        echo "cargo not found at /root/.cargo/bin/cargo"
        echo "WARNING: Skipping Rust generation - cargo not installed"
        exit 0
    fi
else
    echo "cargo found in PATH: $(which cargo)"
fi

# Create a Rust project for generation
cd /tmp/rust_gen

# Set cargo directories to writable locations
export CARGO_HOME="/tmp/.cargo"
mkdir -p "$CARGO_HOME"

# Verify cargo is working
echo "Testing cargo version..."
cargo --version || { echo "ERROR: cargo not working"; exit 1; }

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
    
    // Ensure output directory exists and is writable
    std::fs::create_dir_all("/workspace/output")?;
    
    prost_build::Config::new()
        .out_dir("/workspace/output")
        .compile_protos(&proto_files, &["/tmp/cleaned_proto"])?;
    
    Ok(())
}
EOF

cat > src/main.rs << "EOF"
fn main() {}
EOF

echo "Building Rust proto generation..."
cargo build
'

# Java generation script with buf.validate support
JAVA_SCRIPT='
set -ex  # Add -x for debugging
# Create a temporary directory for proto files
mkdir -p /tmp/java_proto_buf

# Copy proto files to temporary directory
cp -r /workspace/proto/* /tmp/java_proto_buf/

# Copy buf validate proto definitions to the expected location
mkdir -p /tmp/java_proto_buf/buf/validate
cp /opt/protovalidate/proto/protovalidate/buf/validate/validate.proto /tmp/java_proto_buf/buf/validate/

# First, ensure all proto files have proper imports
cd /tmp/java_proto_buf
echo "Adding validate imports to proto files..."
for proto in *.proto; do
    if [ -f "$proto" ]; then
        echo "Processing: $proto"
        /usr/local/bin/add-validate-import.sh "$proto"
        # Show first few lines to verify import was added
        head -5 "$proto"
        echo "---"
    fi
done

# List all files to ensure validate.proto is present
echo "Files in /tmp/java_proto_buf:"
find /tmp/java_proto_buf -name "*.proto" | sort

# Generate using standard protoc with the validate.proto available
# This is simpler and more reliable than using buf generate for our use case
echo "Running protoc..."
protoc -I/tmp/java_proto_buf \
    --java_out=/workspace/output \
    /tmp/java_proto_buf/*.proto

# Verify files were generated
if [ -z "$(find /workspace/output -name "*.java" -type f 2>/dev/null)" ]; then
    echo "ERROR: No Java files were generated!"
    exit 1
fi
echo "Java generation successful, found $(find /workspace/output -name "*.java" -type f | wc -l) .java files"
'

# REMOVED - Go validation is now in main GO_SCRIPT

# Java validation script removed - Java now uses direct generation with annotations

# C++ validation removed - not needed and has compatibility issues

# JSON descriptor generation script
JSON_DESCRIPTOR_SCRIPT='
set -ex
# Create a temporary directory for proto files with validate imports
mkdir -p /tmp/json_proto

# Copy proto files to temporary directory
cp -r /workspace/proto/* /tmp/json_proto/

# Copy buf validate proto definitions
mkdir -p /tmp/json_proto/buf/validate
cp /opt/protovalidate/proto/protovalidate/buf/validate/validate.proto /tmp/json_proto/buf/validate/

# Add validate imports to proto files
cd /tmp/json_proto
for proto in *.proto; do
    if [ -f "$proto" ]; then
        /usr/local/bin/add-validate-import.sh "$proto"
    fi
done

# Check if buf is available, otherwise fall back to protoc
if command -v buf &> /dev/null; then
    echo "Using buf to generate JSON descriptors with validation annotations..."
    
    # Create buf.yaml if it doesn'\''t exist
    if [ ! -f buf.yaml ]; then
        cat > buf.yaml << "BUF_EOF"
version: v1
breaking:
  use:
    - FILE
lint:
  use:
    - DEFAULT
BUF_EOF
    fi
    
    # Generate JSON descriptors using buf build
    buf build . -o /workspace/output/descriptor-set.json --exclude-source-info
    
    # Also generate individual file descriptors
    for proto in *.proto; do
        if [ -f "$proto" ]; then
            base_name="${proto%.proto}"
            buf build . --path "$proto" -o "/workspace/output/${base_name}.json" --exclude-source-info
        fi
    done
else
    echo "buf not found, using protoc with custom extensions support..."
    
    # Use protoc to generate FileDescriptorSet (binary format) with extensions
    protoc -I/tmp/json_proto \
        --descriptor_set_out=/tmp/descriptor-set.pb \
        --include_imports \
        --include_source_info \
        /tmp/json_proto/*.proto
    
    # Convert to JSON using Python with custom extensions support
    python3 << "PYTHON_EOF"
import json
import base64
from google.protobuf import descriptor_pb2
from google.protobuf.json_format import MessageToJson, MessageToDict
from google.protobuf import text_format

# Read the binary descriptor set
with open("/tmp/descriptor-set.pb", "rb") as f:
    descriptor_data = f.read()

# Parse it as a FileDescriptorSet
file_descriptor_set = descriptor_pb2.FileDescriptorSet()
file_descriptor_set.ParseFromString(descriptor_data)

# Convert to JSON with extensions preserved
# The including_default_value_fields and preserving_proto_field_name options
# help preserve more information, but custom extensions still need special handling
json_str = MessageToJson(
    file_descriptor_set, 
    indent=2,
    preserving_proto_field_name=True,
    including_default_value_fields=True,
    use_integers_for_enums=False
)

# Write JSON output
with open("/workspace/output/descriptor-set.json", "w") as f:
    f.write(json_str)

# Also convert to dict for processing individual files
descriptor_dict = MessageToDict(
    file_descriptor_set,
    preserving_proto_field_name=True,
    including_default_value_fields=True
)

# Save individual file descriptors as JSON
for file_descriptor in descriptor_dict.get("file", []):
    name = file_descriptor.get("name", "").replace(".proto", "")
    if name and not name.startswith("google/") and not name.startswith("buf/"):
        individual_desc = {
            "file": [file_descriptor]
        }
        # Get just the base filename
        base_name = name.split("/")[-1]
        output_path = f"/workspace/output/{base_name}.json"
        with open(output_path, "w") as f:
            json.dump(individual_desc, f, indent=2)

print("JSON conversion complete!")
print("Note: buf.validate annotations may not be fully preserved without buf CLI")
PYTHON_EOF
fi

echo "Descriptors generated successfully!"
echo "Generated files:"
ls -la /workspace/output/
'

# Run all generations
FAILED_LANGS=()

for lang in c cpp go python typescript rust java json-descriptors; do
    case $lang in
        c) script="$C_SCRIPT" ;;
        cpp) script="$CPP_SCRIPT" ;;
        go) script="$GO_SCRIPT" ;;
        python) script="$PYTHON_SCRIPT" ;;
        typescript) script="$TYPESCRIPT_SCRIPT" ;;
        rust) script="$RUST_SCRIPT" ;;
        java) script="$JAVA_SCRIPT" ;;
        json-descriptors) script="$JSON_DESCRIPTOR_SCRIPT" ;;
    esac
    
    if ! run_generation "$lang" "$script"; then
        FAILED_LANGS+=("$lang")
    fi
done

# Permissions are now set inside the Docker container after each generation

# No separate validated generation needed - Go and Java now use validation by default

# Summary
echo
print_info "========== Generation Summary =========="
print_info "Output directory: $OUTPUT_BASE_DIR"

# Check what was generated
for lang in c cpp go python typescript rust java json-descriptors; do
    count=$(find "$OUTPUT_BASE_DIR/$lang" -type f 2>/dev/null | wc -l)
    if [ $count -gt 0 ]; then
        print_info "$lang: $count files generated"
    else
        print_warning "$lang: No files generated"
    fi
done

print_info ""
print_info "Note: Go and Java bindings now include buf.validate support by default"

if [ ${#FAILED_LANGS[@]} -gt 0 ]; then
    print_error "Failed languages: ${FAILED_LANGS[*]}"
    exit 1
else
    print_info "All generations completed successfully!"
fi

# Optional: Clean up proto copy
# Only remove if we copied from a different location
if [ "$PROTO_SOURCE_DIR" != "./proto" ]; then
    rm -rf ./proto
fi