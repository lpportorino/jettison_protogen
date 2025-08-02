#!/usr/bin/env python3
"""Test script to check if JSON descriptors contain validation rules."""

import json
import sys

def find_validation_rules(data, path=""):
    """Recursively search for validation rules in the JSON structure."""
    rules = []
    
    if isinstance(data, dict):
        # Check for buf.validate extensions
        if "buf.validate.field" in str(data):
            rules.append(f"Found buf.validate.field at {path}")
        
        # Look for validation-related keys
        for key in data:
            if any(validation_key in str(key).lower() for validation_key in ["validate", "constraint", "rule", "gte", "lte", "gt", "lt"]):
                rules.append(f"Found validation-related key '{key}' at {path}")
                
        # Recurse into nested structures
        for key, value in data.items():
            rules.extend(find_validation_rules(value, f"{path}.{key}"))
            
    elif isinstance(data, list):
        for i, item in enumerate(data):
            rules.extend(find_validation_rules(item, f"{path}[{i}]"))
    
    return rules

def main():
    # Read the JSON descriptor
    json_file = sys.argv[1] if len(sys.argv) > 1 else "output/json-descriptors/jon_shared_cmd_rotary.json"
    
    try:
        with open(json_file, 'r') as f:
            data = json.load(f)
            
        print(f"Analyzing {json_file}...")
        print(f"File has {len(json.dumps(data))} characters")
        
        # Look for validation rules
        rules = find_validation_rules(data)
        
        if rules:
            print(f"\nFound {len(rules)} validation-related items:")
            for rule in rules[:10]:  # Show first 10
                print(f"  - {rule}")
            if len(rules) > 10:
                print(f"  ... and {len(rules) - 10} more")
        else:
            print("\nNo validation rules found in the expected format.")
            
        # Check if we have extensions in the options
        print("\nChecking for field options with extensions...")
        
        def check_field_options(obj, path=""):
            if isinstance(obj, dict):
                if "field" in obj and isinstance(obj["field"], list):
                    for field in obj["field"]:
                        if "options" in field and field["options"]:
                            print(f"  Found field '{field.get('name', 'unknown')}' with options: {field['options']}")
                            
                for key, value in obj.items():
                    check_field_options(value, f"{path}.{key}")
            elif isinstance(obj, list):
                for item in obj:
                    check_field_options(item, path)
                    
        check_field_options(data)
        
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()