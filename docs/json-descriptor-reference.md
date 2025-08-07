# Protocol Buffer JSON Descriptor Reference

## Overview

Protocol Buffer JSON descriptors are JSON representations of compiled `.proto` files that contain complete structural information about Protocol Buffer definitions, including all messages, fields, enums, services, and validation rules. These descriptors are generated using the `buf` CLI or `protoc` compiler and provide a machine-readable format for understanding proto schemas.

## Table of Contents

1. [Structure Overview](#structure-overview)
2. [File Descriptor Format](#file-descriptor-format)
3. [Message Type Definitions](#message-type-definitions)
4. [Field Descriptors](#field-descriptors)
5. [Validation Annotations](#validation-annotations)
6. [Enum Definitions](#enum-definitions)
7. [Service Definitions](#service-definitions)
8. [Examples](#examples)

## Structure Overview

### Root Structure

Every JSON descriptor has the following root structure:

```json
{
  "file": [
    {
      // FileDescriptorProto objects
    }
  ]
}
```

The root object contains a single key `"file"` which is an array of `FileDescriptorProto` objects, each representing a compiled `.proto` file.

## File Descriptor Format

Each file in the descriptor set is represented by a `FileDescriptorProto` object:

```json
{
  "name": "my_proto.proto",
  "package": "com.example.myapp",
  "dependency": ["google/protobuf/timestamp.proto", "buf/validate/validate.proto"],
  "publicDependency": [0],
  "weakDependency": [],
  "messageType": [...],
  "enumType": [...],
  "service": [...],
  "extension": [...],
  "options": {...},
  "sourceCodeInfo": {...},
  "syntax": "proto3"
}
```

### File Descriptor Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | The file name (e.g., "my_proto.proto") |
| `package` | string | The package declaration |
| `dependency` | string[] | Import dependencies (other proto files) |
| `publicDependency` | int[] | Indexes of public imports |
| `weakDependency` | int[] | Indexes of weak imports |
| `messageType` | MessageDescriptorProto[] | Top-level message definitions |
| `enumType` | EnumDescriptorProto[] | Top-level enum definitions |
| `service` | ServiceDescriptorProto[] | Service definitions |
| `extension` | FieldDescriptorProto[] | Top-level extensions |
| `options` | FileOptions | File-level options |
| `sourceCodeInfo` | SourceCodeInfo | Source location information |
| `syntax` | string | Proto syntax version ("proto2" or "proto3") |

## Message Type Definitions

Messages are represented by `MessageDescriptorProto` objects:

```json
{
  "name": "MyMessage",
  "field": [
    {
      "name": "id",
      "number": 1,
      "label": "LABEL_OPTIONAL",
      "type": "TYPE_INT64",
      "jsonName": "id"
    },
    {
      "name": "coordinates",
      "number": 2,
      "label": "LABEL_OPTIONAL",
      "type": "TYPE_MESSAGE",
      "typeName": ".com.example.Coordinates",
      "jsonName": "coordinates"
    }
  ],
  "nestedType": [...],
  "enumType": [...],
  "extensionRange": [...],
  "oneofDecl": [...],
  "options": {...},
  "reservedRange": [...],
  "reservedName": [...]
}
```

### Message Descriptor Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Message type name |
| `field` | FieldDescriptorProto[] | Field definitions |
| `extension` | FieldDescriptorProto[] | Extension field definitions |
| `nestedType` | MessageDescriptorProto[] | Nested message types |
| `enumType` | EnumDescriptorProto[] | Nested enum types |
| `extensionRange` | ExtensionRange[] | Extension number ranges |
| `oneofDecl` | OneofDescriptorProto[] | Oneof group declarations |
| `options` | MessageOptions | Message-level options |
| `reservedRange` | ReservedRange[] | Reserved field number ranges |
| `reservedName` | string[] | Reserved field names |

## Field Descriptors

Fields are represented by `FieldDescriptorProto` objects:

```json
{
  "name": "temperature",
  "number": 3,
  "label": "LABEL_OPTIONAL",
  "type": "TYPE_FLOAT",
  "jsonName": "temperature",
  "options": {
    "[buf.validate.field]": {
      "float": {
        "gte": -50.0,
        "lte": 150.0
      }
    }
  }
}
```

### Field Labels

| Label | Description |
|-------|-------------|
| `LABEL_OPTIONAL` | Optional field (default in proto3) |
| `LABEL_REQUIRED` | Required field (proto2 only) |
| `LABEL_REPEATED` | Repeated field (array/list) |

### Field Types

| Type | Description | Example |
|------|-------------|---------|
| `TYPE_DOUBLE` | 64-bit floating point | `double` |
| `TYPE_FLOAT` | 32-bit floating point | `float` |
| `TYPE_INT64` | 64-bit signed integer | `int64` |
| `TYPE_UINT64` | 64-bit unsigned integer | `uint64` |
| `TYPE_INT32` | 32-bit signed integer | `int32` |
| `TYPE_FIXED64` | 64-bit fixed integer | `fixed64` |
| `TYPE_FIXED32` | 32-bit fixed integer | `fixed32` |
| `TYPE_BOOL` | Boolean | `bool` |
| `TYPE_STRING` | String | `string` |
| `TYPE_MESSAGE` | Message type | Custom message |
| `TYPE_BYTES` | Byte array | `bytes` |
| `TYPE_UINT32` | 32-bit unsigned integer | `uint32` |
| `TYPE_ENUM` | Enum type | Custom enum |
| `TYPE_SFIXED32` | 32-bit signed fixed | `sfixed32` |
| `TYPE_SFIXED64` | 64-bit signed fixed | `sfixed64` |
| `TYPE_SINT32` | 32-bit signed integer (ZigZag) | `sint32` |
| `TYPE_SINT64` | 64-bit signed integer (ZigZag) | `sint64` |

### Field Descriptor Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Field name |
| `number` | int | Field number (must be unique) |
| `label` | string | Field label (LABEL_*) |
| `type` | string | Field type (TYPE_*) |
| `typeName` | string | For MESSAGE/ENUM types, the fully qualified type name |
| `extendee` | string | For extensions, the extended type |
| `defaultValue` | string | Default value |
| `oneofIndex` | int | Index of the oneof this field belongs to |
| `jsonName` | string | JSON field name |
| `options` | FieldOptions | Field options including validation |
| `proto3Optional` | bool | Whether field uses proto3 optional |

## Validation Annotations

The protogen system preserves buf.validate annotations in the JSON descriptors. These appear in the field options:

### Field Validation Structure

```json
{
  "options": {
    "[buf.validate.field]": {
      "float": {
        "gte": -1.0,
        "lte": 1.0
      }
    }
  }
}
```

### Supported Validation Rules

#### Numeric Validations (float, double, int32, int64, uint32, uint64)

```json
{
  "[buf.validate.field]": {
    "float": {
      "const": 42.0,      // Must equal this value
      "lt": 100.0,        // Less than
      "lte": 100.0,       // Less than or equal
      "gt": 0.0,          // Greater than
      "gte": 0.0,         // Greater than or equal
      "in": [1.0, 2.0],   // Must be one of these values
      "not_in": [3.0]     // Must not be one of these values
    }
  }
}
```

#### String Validations

```json
{
  "[buf.validate.field]": {
    "string": {
      "const": "hello",           // Must equal this value
      "min_len": 3,              // Minimum length
      "max_len": 100,            // Maximum length
      "pattern": "^[A-Z]+$",     // Regex pattern
      "prefix": "foo_",          // Must start with
      "suffix": "_bar",          // Must end with
      "contains": "test",        // Must contain
      "in": ["foo", "bar"],      // Must be one of these
      "not_in": ["baz"]         // Must not be one of these
    }
  }
}
```

#### Collection Validations

```json
{
  "[buf.validate.field]": {
    "repeated": {
      "min_items": 1,      // Minimum number of items
      "max_items": 100,    // Maximum number of items
      "unique": true       // All items must be unique
    }
  }
}
```

#### Message Validations

```json
{
  "[buf.validate.field]": {
    "message": {
      "required": true     // Message field cannot be null
    }
  }
}
```

### CEL Expression Validations

Complex validation rules are stored as CEL (Common Expression Language) expressions:

```json
{
  "options": {
    "[buf.validate.predefined]": {
      "cel": [
        {
          "id": "float.gte",
          "expression": "!has(rules.lt) && !has(rules.lte) && (this.isNan() || this < rules.gte)? 'value must be greater than or equal to %s'.format([rules.gte]) : ''"
        }
      ]
    }
  }
}
```

Each CEL expression includes:
- `id`: Rule identifier (e.g., "float.gte", "string.min_len")
- `expression`: The CEL expression that evaluates the validation

## Enum Definitions

Enums are represented by `EnumDescriptorProto` objects:

```json
{
  "name": "Status",
  "value": [
    {
      "name": "STATUS_UNKNOWN",
      "number": 0
    },
    {
      "name": "STATUS_ACTIVE",
      "number": 1
    },
    {
      "name": "STATUS_INACTIVE",
      "number": 2
    }
  ],
  "options": {...},
  "reservedRange": [...],
  "reservedName": [...]
}
```

### Enum Value Structure

```json
{
  "name": "ENUM_VALUE_NAME",
  "number": 1,
  "options": {
    "deprecated": true
  }
}
```

## Service Definitions

Services represent RPC interfaces:

```json
{
  "name": "MyService",
  "method": [
    {
      "name": "GetUser",
      "inputType": ".com.example.GetUserRequest",
      "outputType": ".com.example.GetUserResponse",
      "options": {},
      "clientStreaming": false,
      "serverStreaming": false
    }
  ],
  "options": {}
}
```

### Method Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Method name |
| `inputType` | string | Fully qualified request message type |
| `outputType` | string | Fully qualified response message type |
| `clientStreaming` | bool | Whether client streams requests |
| `serverStreaming` | bool | Whether server streams responses |
| `options` | MethodOptions | Method-level options |

## Examples

### Complete Example: Coordinate Message with Validation

```json
{
  "file": [{
    "name": "coordinates.proto",
    "package": "geo",
    "dependency": ["buf/validate/validate.proto"],
    "messageType": [{
      "name": "Coordinate",
      "field": [
        {
          "name": "latitude",
          "number": 1,
          "label": "LABEL_OPTIONAL",
          "type": "TYPE_FLOAT",
          "jsonName": "latitude",
          "options": {
            "[buf.validate.field]": {
              "float": {
                "gte": -90.0,
                "lte": 90.0
              }
            }
          }
        },
        {
          "name": "longitude",
          "number": 2,
          "label": "LABEL_OPTIONAL",
          "type": "TYPE_FLOAT",
          "jsonName": "longitude",
          "options": {
            "[buf.validate.field]": {
              "float": {
                "gte": -180.0,
                "lte": 180.0
              }
            }
          }
        },
        {
          "name": "altitude",
          "number": 3,
          "label": "LABEL_OPTIONAL",
          "type": "TYPE_FLOAT",
          "jsonName": "altitude",
          "options": {
            "[buf.validate.field]": {
              "float": {
                "gte": -1000.0,
                "lte": 50000.0
              }
            }
          }
        }
      ]
    }],
    "syntax": "proto3"
  }]
}
```

### Example: Enum with Reserved Values

```json
{
  "enumType": [{
    "name": "DeviceStatus",
    "value": [
      {"name": "DEVICE_STATUS_UNKNOWN", "number": 0},
      {"name": "DEVICE_STATUS_ONLINE", "number": 1},
      {"name": "DEVICE_STATUS_OFFLINE", "number": 2},
      {"name": "DEVICE_STATUS_ERROR", "number": 3}
    ],
    "reservedRange": [
      {"start": 100, "end": 200}
    ],
    "reservedName": ["DEVICE_STATUS_DEPRECATED"]
  }]
}
```

### Example: Oneof Field Group

```json
{
  "messageType": [{
    "name": "Command",
    "field": [
      {
        "name": "start",
        "number": 1,
        "label": "LABEL_OPTIONAL",
        "type": "TYPE_MESSAGE",
        "typeName": ".StartCommand",
        "oneofIndex": 0,
        "jsonName": "start"
      },
      {
        "name": "stop",
        "number": 2,
        "label": "LABEL_OPTIONAL",
        "type": "TYPE_MESSAGE",
        "typeName": ".StopCommand",
        "oneofIndex": 0,
        "jsonName": "stop"
      }
    ],
    "oneofDecl": [
      {
        "name": "command",
        "options": {}
      }
    ]
  }]
}
```

## Working with JSON Descriptors

### Reading Descriptors

JSON descriptors can be parsed using any JSON parser. The structure follows a consistent pattern based on the Protocol Buffer descriptor.proto definition.

### Validation Integration

When buf.validate annotations are present in the source proto files:

1. Field validations appear in `options["[buf.validate.field]"]`
2. CEL expressions appear in `options["[buf.validate.predefined]"]`
3. The validation rules preserve all constraints from the original proto

### Type Resolution

When resolving types:
- Fully qualified type names start with a dot (e.g., `.com.example.MyMessage`)
- Type names are relative to the package of the containing file
- Use the `dependency` array to understand import relationships

### Common Patterns

1. **Finding all messages in a package**:
   ```javascript
   const messages = descriptor.file
     .filter(f => f.package === "com.example")
     .flatMap(f => f.messageType);
   ```

2. **Extracting validation rules**:
   ```javascript
   const validations = field.options?.["[buf.validate.field]"];
   ```

3. **Resolving message dependencies**:
   ```javascript
   const findMessage = (typeName) => {
     for (const file of descriptor.file) {
       const message = file.messageType?.find(m => 
         `.${file.package}.${m.name}` === typeName
       );
       if (message) return message;
     }
   };
   ```

## Best Practices

1. **Always check for optional fields**: Not all fields in the descriptor are guaranteed to be present
2. **Handle type resolution carefully**: Message and enum types use fully qualified names
3. **Preserve validation semantics**: When processing descriptors, maintain the validation rules
4. **Use the syntax field**: Different behaviors apply for proto2 vs proto3
5. **Consider source code info**: The `sourceCodeInfo` field can help with error reporting

## Additional Resources

- [Protocol Buffers Descriptor Proto](https://github.com/protocolbuffers/protobuf/blob/main/src/google/protobuf/descriptor.proto)
- [buf.validate Documentation](https://github.com/bufbuild/protovalidate)
- [JSON Mapping for Protocol Buffers](https://protobuf.dev/programming-guides/proto3/#json)