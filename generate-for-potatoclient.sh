#!/usr/bin/env bash

set -euo pipefail

# This script generates proto bindings for potatoclient

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration for potatoclient
PROTO_SOURCE_DIR="../potatoclient/proto"
OUTPUT_BASE_DIR="../potatoclient/target/proto-gen"
VALIDATE_OUTPUT_DIR="../potatoclient/target/proto-gen-validated"

# Create output directories
echo "Creating output directories for potatoclient..."
mkdir -p "$OUTPUT_BASE_DIR"
mkdir -p "$VALIDATE_OUTPUT_DIR"

# Run the main generator with potatoclient paths
echo "Generating proto bindings for potatoclient..."
PROTO_SOURCE_DIR="$PROTO_SOURCE_DIR" \
OUTPUT_BASE_DIR="$OUTPUT_BASE_DIR" \
VALIDATE_OUTPUT_DIR="$VALIDATE_OUTPUT_DIR" \
./generate-protos.sh

echo ""
echo "Generation complete!"
echo "Standard bindings: $OUTPUT_BASE_DIR"
echo "Validated bindings: $VALIDATE_OUTPUT_DIR"
echo ""
echo "To use validated Java bindings in potatoclient tests, add this Maven dependency:"
echo ""
echo "<dependency>"
echo "    <groupId>build.buf</groupId>"
echo "    <artifactId>protovalidate</artifactId>"
echo "    <version>0.3.0</version>"
echo "</dependency>"