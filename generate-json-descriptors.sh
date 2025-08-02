#!/usr/bin/env bash
# Generate JSON descriptors from proto files using buf (integrated with protogen)

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
OUTPUT_DIR="${OUTPUT_DIR:-./output/json-descriptors}"

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

# Check if Docker image exists
if ! docker image inspect "$DOCKER_IMAGE" &> /dev/null; then
    print_warning "Docker image not found. Building..."
    if docker build -t "$DOCKER_IMAGE" .; then
        print_info "Docker image built successfully"
    else
        print_error "Failed to build Docker image"
        exit 1
    fi
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Copy proto files to local directory to avoid permission issues
print_info "Preparing proto files..."
if [ "$PROTO_SOURCE_DIR" != "./proto" ]; then
    rm -rf ./proto
    cp -r "$PROTO_SOURCE_DIR" ./proto
fi

print_info "Generating JSON descriptors using buf..."

# JSON generation script for Docker
JSON_SCRIPT='
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

# Use protoc to generate FileDescriptorSet (binary format)
echo "Generating FileDescriptorSet with protoc..."
protoc -I/tmp/json_proto \
    --descriptor_set_out=/tmp/descriptor-set.pb \
    --include_imports \
    --include_source_info \
    /tmp/json_proto/*.proto

# Convert to JSON using Python
echo "Converting to JSON format..."
python3 << "PYTHON_EOF"
import json
import base64
from google.protobuf import descriptor_pb2
from google.protobuf.json_format import MessageToJson, MessageToDict

# Read the binary descriptor set
with open("/tmp/descriptor-set.pb", "rb") as f:
    descriptor_data = f.read()

# Parse it as a FileDescriptorSet
file_descriptor_set = descriptor_pb2.FileDescriptorSet()
file_descriptor_set.ParseFromString(descriptor_data)

# Convert to JSON with proper formatting
json_str = MessageToJson(
    file_descriptor_set, 
    indent=2,
    preserving_proto_field_name=True
)

# Write JSON output
with open("/workspace/output/descriptor-set.json", "w") as f:
    f.write(json_str)

# Also convert to dict for processing individual files
descriptor_dict = MessageToDict(
    file_descriptor_set,
    preserving_proto_field_name=True
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
PYTHON_EOF

echo "Descriptors generated successfully!"
echo "Generated files:"
ls -la /workspace/output/
'

# Run JSON generation in Docker
print_info "Running JSON descriptor generation in Docker..."
docker run --rm \
    -u "$(id -u):$(id -g)" \
    -v "$SCRIPT_DIR/proto:/workspace/proto:ro" \
    -v "$SCRIPT_DIR/$OUTPUT_DIR:/workspace/output:rw" \
    -w /workspace \
    "$DOCKER_IMAGE" \
    -c "$JSON_SCRIPT"

if [ $? -ne 0 ]; then
    print_error "JSON descriptor generation failed"
    exit 1
fi

# Set permissions
chmod -R 777 "$OUTPUT_DIR" 2>/dev/null || true

# Check results
if [ -f "$OUTPUT_DIR/descriptor-set.json" ]; then
    print_info "Success! JSON descriptors generated in $OUTPUT_DIR"
    print_info "Main descriptor set: $OUTPUT_DIR/descriptor-set.json"
    
    # Show summary
    file_count=$(find "$OUTPUT_DIR" -name "*.json" | wc -l)
    print_info "Generated $file_count JSON descriptor files"
    
    # Show sample of descriptor content
    print_info "Sample descriptor content:"
    echo "----------------------------------------"
    head -50 "$OUTPUT_DIR/descriptor-set.json" | jq '.' 2>/dev/null || head -50 "$OUTPUT_DIR/descriptor-set.json"
    echo "----------------------------------------"
    
    # List all generated files
    print_info "Generated files:"
    ls -la "$OUTPUT_DIR"/
else
    print_error "Failed to generate JSON descriptors"
    exit 1
fi

# Optional: Clean up proto copy
if [ "$PROTO_SOURCE_DIR" != "./proto" ]; then
    rm -rf ./proto
fi