#!/usr/bin/env python3
"""Validate JSON descriptors against the schema."""

import json
import sys
from jsonschema import validate, ValidationError, Draft7Validator

def main():
    # Load schema
    with open('protobuf-json-descriptor.schema.json', 'r') as f:
        schema = json.load(f)
    
    # Load a sample descriptor
    sample_file = sys.argv[1] if len(sys.argv) > 1 else 'output/json-descriptors/jon_shared_cmd_rotary.json'
    
    with open(sample_file, 'r') as f:
        descriptor = json.load(f)
    
    # Validate
    try:
        validate(instance=descriptor, schema=schema)
        print(f"✓ {sample_file} is valid according to the schema")
    except ValidationError as e:
        print(f"✗ Validation error in {sample_file}:")
        print(f"  Path: {' -> '.join(str(p) for p in e.absolute_path)}")
        print(f"  Message: {e.message}")
        
        # Check if it's a structural issue
        validator = Draft7Validator(schema)
        errors = sorted(validator.iter_errors(descriptor), key=lambda e: e.path)
        
        if len(errors) > 1:
            print(f"\n  Found {len(errors)} validation errors. First few:")
            for err in errors[:5]:
                print(f"  - {' -> '.join(str(p) for p in err.path)}: {err.message}")

if __name__ == "__main__":
    main()