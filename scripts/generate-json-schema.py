#!/usr/bin/env python3
"""
Generate JSON Schema from Protocol Buffer JSON descriptors.
This analyzes the structure of JSON descriptors and creates a formal JSON Schema.
"""

import json
import sys
from typing import Dict, Any, List, Set
from collections import defaultdict

def analyze_json_structure(obj: Any, path: str = "$") -> Dict[str, Any]:
    """Recursively analyze JSON structure and infer schema."""
    if obj is None:
        return {"type": "null"}
    
    if isinstance(obj, bool):
        return {"type": "boolean"}
    
    if isinstance(obj, int):
        return {"type": "integer"}
    
    if isinstance(obj, float):
        return {"type": "number"}
    
    if isinstance(obj, str):
        return {"type": "string"}
    
    if isinstance(obj, list):
        if not obj:
            return {"type": "array", "items": {}}
        
        # Analyze all items to find common schema
        item_schemas = [analyze_json_structure(item, f"{path}[{i}]") for i, item in enumerate(obj)]
        
        # If all items have the same type, use that
        if len(set(json.dumps(s, sort_keys=True) for s in item_schemas)) == 1:
            return {"type": "array", "items": item_schemas[0]}
        
        # Otherwise, use anyOf
        unique_schemas = []
        seen = set()
        for schema in item_schemas:
            schema_str = json.dumps(schema, sort_keys=True)
            if schema_str not in seen:
                seen.add(schema_str)
                unique_schemas.append(schema)
        
        return {"type": "array", "items": {"anyOf": unique_schemas}}
    
    if isinstance(obj, dict):
        properties = {}
        required = []
        
        for key, value in obj.items():
            properties[key] = analyze_json_structure(value, f"{path}.{key}")
            required.append(key)
        
        schema = {
            "type": "object",
            "properties": properties
        }
        
        if required:
            schema["required"] = sorted(required)
        
        return schema
    
    return {"type": "string"}  # Default fallback

def merge_schemas(schemas: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Merge multiple schemas into one that accepts all variants."""
    if not schemas:
        return {}
    
    if len(schemas) == 1:
        return schemas[0]
    
    # Check if all schemas are the same type
    types = set()
    for schema in schemas:
        if "type" in schema:
            types.add(schema["type"])
    
    if len(types) == 1 and "object" in types:
        # Merge object schemas
        merged_properties = {}
        all_required = set()
        
        for schema in schemas:
            if "properties" in schema:
                for prop, prop_schema in schema["properties"].items():
                    if prop not in merged_properties:
                        merged_properties[prop] = prop_schema
                    else:
                        # Merge property schemas
                        existing = merged_properties[prop]
                        if json.dumps(existing, sort_keys=True) != json.dumps(prop_schema, sort_keys=True):
                            # Different schemas for same property
                            merged_properties[prop] = {"anyOf": [existing, prop_schema]}
            
            if "required" in schema:
                # Track which properties are always required
                if not all_required:
                    all_required = set(schema["required"])
                else:
                    all_required = all_required.intersection(schema["required"])
        
        result = {
            "type": "object",
            "properties": merged_properties
        }
        
        if all_required:
            result["required"] = sorted(all_required)
        
        return result
    
    # For different types, use anyOf
    unique_schemas = []
    seen = set()
    for schema in schemas:
        schema_str = json.dumps(schema, sort_keys=True)
        if schema_str not in seen:
            seen.add(schema_str)
            unique_schemas.append(schema)
    
    if len(unique_schemas) == 1:
        return unique_schemas[0]
    
    return {"anyOf": unique_schemas}

def generate_schema_from_files(file_paths: List[str]) -> Dict[str, Any]:
    """Generate a comprehensive schema from multiple JSON descriptor files."""
    schemas = []
    
    for file_path in file_paths:
        try:
            with open(file_path, 'r') as f:
                data = json.load(f)
                schema = analyze_json_structure(data)
                schemas.append(schema)
                print(f"Analyzed: {file_path}", file=sys.stderr)
        except Exception as e:
            print(f"Error processing {file_path}: {e}", file=sys.stderr)
    
    # Merge all schemas
    merged = merge_schemas(schemas)
    
    # Add metadata
    final_schema = {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "$id": "https://github.com/JAremko/protogen/protobuf-json-descriptor.schema.json",
        "title": "Protocol Buffer JSON Descriptor",
        "description": "JSON Schema for Protocol Buffer descriptors generated by buf/protoc with validation annotations",
        **merged
    }
    
    return final_schema

def add_semantic_descriptions(schema: Dict[str, Any]) -> Dict[str, Any]:
    """Add semantic descriptions to known fields in the schema."""
    descriptions = {
        "file": "Array of FileDescriptorProto objects representing all proto files in the descriptor set",
        "name": "The name of the file, message, field, enum, or service",
        "package": "The package name for the proto file",
        "messageType": "Array of message type definitions in this file",
        "enumType": "Array of enum type definitions in this file",
        "field": "Array of field definitions in this message",
        "number": "The field number (must be unique within the message)",
        "label": "Field label (LABEL_OPTIONAL, LABEL_REPEATED, LABEL_REQUIRED)",
        "type": "Field type (TYPE_STRING, TYPE_INT32, TYPE_MESSAGE, etc.)",
        "typeName": "For message and enum types, the fully qualified type name",
        "jsonName": "JSON name for this field",
        "options": "Field options including validation rules",
        "[buf.validate.field]": "buf.validate field validation rules",
        "[buf.validate.predefined]": "Predefined validation rules with CEL expressions",
        "float": "Validation rules for float fields",
        "string": "Validation rules for string fields",
        "int32": "Validation rules for int32 fields",
        "gte": "Greater than or equal to constraint",
        "lte": "Less than or equal to constraint",
        "gt": "Greater than constraint",
        "lt": "Less than constraint",
        "const": "Constant value constraint",
        "in": "Value must be in this list",
        "not_in": "Value must not be in this list",
        "min_len": "Minimum string length",
        "max_len": "Maximum string length",
        "pattern": "Regular expression pattern",
        "cel": "CEL (Common Expression Language) validation expressions",
        "id": "Validation rule identifier",
        "expression": "CEL expression for validation",
        "dependency": "Import dependencies for this proto file",
        "oneofIndex": "Index of the oneof group this field belongs to",
        "oneofDecl": "Oneof declarations in this message",
        "service": "Service definitions in this file",
        "method": "RPC method definitions in this service",
        "inputType": "Input message type for RPC method",
        "outputType": "Output message type for RPC method",
        "syntax": "Proto syntax version (proto2 or proto3)",
        "sourceCodeInfo": "Source code location information",
        "publicDependency": "Indexes of public dependencies",
        "weakDependency": "Indexes of weak dependencies",
        "nestedType": "Nested message type definitions",
        "value": "Enum value definitions",
        "reservedRange": "Reserved field number ranges",
        "reservedName": "Reserved field names",
        "extensionRange": "Extension ranges for this message",
        "extension": "Extension field definitions"
    }
    
    def add_descriptions_recursive(obj: Dict[str, Any], path: str = "") -> Dict[str, Any]:
        if isinstance(obj, dict):
            result = {}
            for key, value in obj.items():
                new_path = f"{path}.{key}" if path else key
                
                # Add description if we have one for this key
                if key in descriptions and isinstance(value, dict):
                    value = {**value, "description": descriptions[key]}
                
                # Recurse
                result[key] = add_descriptions_recursive(value, new_path)
            
            return result
        elif isinstance(obj, list):
            return [add_descriptions_recursive(item, path) for item in obj]
        else:
            return obj
    
    return add_descriptions_recursive(schema)

def main():
    import glob
    import os
    
    # Find all JSON descriptor files
    json_files = glob.glob("output/json-descriptors/*.json")
    
    if not json_files:
        print("No JSON descriptor files found in output/json-descriptors/", file=sys.stderr)
        sys.exit(1)
    
    print(f"Found {len(json_files)} JSON descriptor files", file=sys.stderr)
    
    # Generate schema
    schema = generate_schema_from_files(json_files)
    
    # Add semantic descriptions
    schema = add_semantic_descriptions(schema)
    
    # Output schema
    print(json.dumps(schema, indent=2))

if __name__ == "__main__":
    main()