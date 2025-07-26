#!/usr/bin/env bash

set -euo pipefail

OUTPUT_DIR="${1:-./output}"

echo "=== Proto Generation Test Results ==="
echo

for lang in c cpp go python typescript rust java; do
    echo "[$lang]"
    case $lang in
        c)
            if ls "$OUTPUT_DIR/$lang"/*.pb.h &>/dev/null && ls "$OUTPUT_DIR/$lang"/*.pb.c &>/dev/null; then
                echo "  ✓ Generated C files found:"
                for f in "$OUTPUT_DIR/$lang"/*.pb.h "$OUTPUT_DIR/$lang"/*.pb.c; do
                    [ -f "$f" ] && echo "    - $(basename "$f")"
                done
            else
                echo "  ✗ C files not found"
            fi
            ;;
        cpp)
            if [ -f "$OUTPUT_DIR/$lang/test.pb.h" ] && [ -f "$OUTPUT_DIR/$lang/test.pb.cc" ]; then
                echo "  ✓ Generated C++ files found"
                echo "    - test.pb.h"
                echo "    - test.pb.cc"
            else
                echo "  ✗ C++ files not found"
            fi
            ;;
        go)
            if ls "$OUTPUT_DIR/$lang"/*.pb.go &>/dev/null; then
                echo "  ✓ Generated Go files found:"
                for f in "$OUTPUT_DIR/$lang"/*.pb.go; do
                    echo "    - $(basename "$f")"
                done
            else
                echo "  ✗ Go files not found"
            fi
            ;;
        python)
            if ls "$OUTPUT_DIR/$lang"/*_pb2.py &>/dev/null; then
                echo "  ✓ Generated Python files found:"
                for f in "$OUTPUT_DIR/$lang"/*_pb2.py "$OUTPUT_DIR/$lang"/*_pb2.pyi; do
                    [ -f "$f" ] && echo "    - $(basename "$f")"
                done
            else
                echo "  ✗ Python files not found"
            fi
            ;;
        typescript)
            if [ -f "$OUTPUT_DIR/$lang/test.ts" ]; then
                echo "  ✓ Generated TypeScript files found"
                echo "    - test.ts"
            else
                echo "  ✗ TypeScript files not found"
            fi
            ;;
        rust)
            if ls "$OUTPUT_DIR/$lang"/*.rs &>/dev/null; then
                echo "  ✓ Generated Rust files found:"
                for f in "$OUTPUT_DIR/$lang"/*.rs; do
                    echo "    - $(basename "$f")"
                done
            else
                echo "  ✗ Rust files not found"
            fi
            ;;
        java)
            if find "$OUTPUT_DIR/$lang" -name "*.java" | grep -q .; then
                echo "  ✓ Generated Java files found:"
                find "$OUTPUT_DIR/$lang" -name "*.java" -exec basename {} \; | sed 's/^/    - /'
            else
                echo "  ✗ Java files not found"
            fi
            ;;
    esac
    echo
done

# Count total files
total=$(find "$OUTPUT_DIR" -type f \( -name "*.pb.*" -o -name "*.java" -o -name "*.py*" -o -name "*.ts" -o -name "*.rs" -o -name "*.go" \) 2>/dev/null | wc -l)
echo "Total files generated: $total"