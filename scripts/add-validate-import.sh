#!/bin/bash
# Script to add buf/validate/validate.proto import to a proto file if not already present

set -e

proto_file="$1"
if [ -z "$proto_file" ]; then
    echo "Usage: add-validate-import.sh <proto_file>"
    exit 1
fi

# Check if import already exists
if grep -q 'import "buf/validate/validate.proto"' "$proto_file"; then
    # Import already exists, nothing to do
    exit 0
fi

# Create a temporary file
temp_file=$(mktemp)

# Find the syntax line
syntax_line=$(grep -n '^syntax' "$proto_file" | head -1 | cut -d: -f1)

if [ -z "$syntax_line" ]; then
    echo "Error: No syntax line found in $proto_file"
    exit 1
fi

# Copy file with import added after syntax line
awk -v line="$syntax_line" '
NR == line {
    print $0
    print ""
    print "import \"buf/validate/validate.proto\";"
    next
}
{print}
' "$proto_file" > "$temp_file"

# Replace original file
mv "$temp_file" "$proto_file"