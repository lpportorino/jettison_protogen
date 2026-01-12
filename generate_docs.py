#!/usr/bin/env python3
"""
Generate comprehensive Markdown documentation for all protobuf messages.
This script parses proto files and creates detailed documentation for each message and enum.
"""

import re
import os
from pathlib import Path
from typing import Dict, List, Tuple, Optional

PROTO_DIR = Path("/home/jare/git/jettison_protogen/proto")
DOCS_DIR = Path("/home/jare/git/jettison_protogen/docs")


def parse_field_constraints(line: str) -> str:
    """Extract validation constraints from buf.validate annotations."""
    constraints = []

    # Extract numeric constraints
    if 'gte:' in line:
        match = re.search(r'gte:\s*(-?[\d.]+)', line)
        if match:
            constraints.append(f">= {match.group(1)}")
    if 'gt:' in line:
        match = re.search(r'gt:\s*(-?[\d.]+)', line)
        if match:
            constraints.append(f"> {match.group(1)}")
    if 'lte:' in line:
        match = re.search(r'lte:\s*(-?[\d.]+)', line)
        if match:
            constraints.append(f"<= {match.group(1)}")
    if 'lt:' in line:
        match = re.search(r'lt:\s*(-?[\d.]+)', line)
        if match:
            constraints.append(f"< {match.group(1)}")
    if 'min_len:' in line:
        match = re.search(r'min_len:\s*(\d+)', line)
        if match:
            constraints.append(f"min length: {match.group(1)}")
    if 'min_items:' in line:
        match = re.search(r'min_items:\s*(\d+)', line)
        if match:
            constraints.append(f"min items: {match.group(1)}")
    if 'not_in: [0]' in line:
        constraints.append("must not be 0/UNSPECIFIED")
    if 'defined_only: true' in line:
        constraints.append("must be defined enum value")
    if 'required: true' in line:
        constraints.append("required")

    return ", ".join(constraints) if constraints else "-"


def parse_message(proto_content: str, message_name: str) -> Optional[Dict]:
    """Parse a single message definition from proto content."""
    # Find message block - improved to handle nested braces better
    pattern = rf'message\s+{message_name}\s*\{{'
    match = re.search(pattern, proto_content)

    if not match:
        return None

    # Find matching closing brace
    start_idx = match.end()
    brace_count = 1
    idx = start_idx

    while idx < len(proto_content) and brace_count > 0:
        if proto_content[idx] == '{':
            brace_count += 1
        elif proto_content[idx] == '}':
            brace_count -= 1
        idx += 1

    if brace_count != 0:
        return None

    message_block = proto_content[start_idx:idx-1]
    fields = []

    # Parse with multi-line context support
    full_content = message_block
    lines = message_block.split('\n')
    pending_comment = ""
    in_oneof = False
    i = 0

    while i < len(lines):
        line = lines[i].strip()

        # Collect comments
        if line.startswith('//'):
            comment = line[2:].strip()
            if comment:
                pending_comment = comment if not pending_comment else f"{pending_comment} {comment}"
            i += 1
            continue

        # Track oneof blocks
        if line.startswith('oneof '):
            in_oneof = True
            i += 1
            continue

        # Skip empty lines, reserved, and oneof validation
        if not line or line.startswith('reserved') or line.startswith('option (buf.validate.oneof)'):
            pending_comment = ""
            i += 1
            continue

        # Parse field - including optional modifier
        field_match = re.match(
            r'(optional\s+)?(repeated\s+)?(\w+(?:\.\w+)*)\s+(\w+)\s*=\s*(\d+)',
            line
        )

        if field_match:
            optional, repeated, field_type, field_name, field_num = field_match.groups()

            # Collect annotations from this line and potentially next lines
            full_line = line
            j = i + 1
            while j < len(lines) and not lines[j].strip().endswith(';'):
                full_line += ' ' + lines[j].strip()
                j += 1

            description = pending_comment or "-"
            constraints = parse_field_constraints(full_line)

            type_prefix = ""
            if optional:
                type_prefix = "optional "
            if repeated:
                type_prefix = "repeated "

            fields.append({
                'name': field_name,
                'type': type_prefix + field_type,
                'number': field_num,
                'description': description,
                'constraints': constraints
            })

            pending_comment = ""

        i += 1

    return {
        'name': message_name,
        'fields': fields
    }


def parse_enum(proto_content: str, enum_name: str) -> Optional[Dict]:
    """Parse enum definition from proto content."""
    pattern = rf'enum\s+{enum_name}\s*\{{([^}}]+)\}}'
    match = re.search(pattern, proto_content, re.DOTALL)

    if not match:
        return None

    enum_block = match.group(1)
    values = []
    pending_comment = ""

    for line in enum_block.split('\n'):
        line = line.strip()

        if line.startswith('//'):
            comment = line[2:].strip()
            if comment:
                pending_comment = comment
            continue

        value_match = re.match(r'(\w+)\s*=\s*(\d+);', line)
        if value_match:
            name, number = value_match.groups()
            values.append({
                'name': name,
                'number': number,
                'description': pending_comment or "-"
            })
            pending_comment = ""

    return {
        'name': enum_name,
        'values': values
    }


def get_package_name(proto_content: str) -> str:
    """Extract package name from proto file."""
    match = re.search(r'package\s+([\w.]+);', proto_content)
    return match.group(1) if match else "unknown"


def find_all_messages(proto_content: str) -> List[str]:
    """Find all message names in proto content."""
    return re.findall(r'message\s+(\w+)\s*\{', proto_content)


def find_all_enums(proto_content: str) -> List[str]:
    """Find all enum names in proto content."""
    return re.findall(r'enum\s+(\w+)\s*\{', proto_content)


def infer_message_purpose(message_name: str, package: str, fields: List[Dict]) -> str:
    """Infer the purpose of a message from its name and context."""
    name_lower = message_name.lower()

    # Command messages
    if 'cmd' in package.lower() or name_lower in ['root']:
        if name_lower in ['start', 'stop', 'halt']:
            return f"{'Starts' if 'start' in name_lower else 'Stops' if 'stop' in name_lower else 'Halts'} the associated module or operation."
        if 'set' in name_lower:
            return "Sets a configuration parameter or value."
        if 'get' in name_lower:
            return "Requests data or status information."
        if name_lower == 'root':
            return "Root container for all commands in this subsystem."
        if 'calibrat' in name_lower:
            return "Calibration-related command."
        if 'scan' in name_lower:
            return "Scanning pattern control command."
        if 'measure' in name_lower:
            return "Initiates a measurement operation."
        if any(x in name_lower for x in ['enable', 'disable']):
            return f"{'Enables' if 'enable' in name_lower else 'Disables'} a feature or mode."
        if 'rotate' in name_lower:
            return "Controls rotary platform rotation."
        if any(x in name_lower for x in ['photo', 'record', 'rec']):
            return "Controls photo capture or recording."
        return "Command message for specific operation."

    # State/data messages
    if 'data' in package.lower() or 'ser' in package:
        if 'state' in name_lower:
            return "Complete system state snapshot."
        if any(x in name_lower for x in ['camera', 'gps', 'compass', 'lrf', 'rotary', 'power']):
            return f"State data for {message_name.replace('JonGuiData', '').replace('Jon', '')} subsystem."
        if 'meteo' in name_lower:
            return "Meteorological sensor data (temperature, humidity, pressure)."
        if 'target' in name_lower:
            return "Target location and measurement data."
        return "State/data message."

    # Video/archive messages
    if 'video' in package.lower():
        return "Video metadata or processing information."
    if 'archive' in package.lower():
        return "Archive file structure metadata."
    if 'log' in package.lower():
        return "Client logging data."

    return "Protocol buffer message."


def generate_message_doc(message: Dict, proto_file: str, package: str) -> str:
    """Generate markdown documentation for a message."""
    name = message['name']
    fields = message['fields']

    # Determine full qualified name
    qualified_name = f"{package}.{name}"

    # Infer purpose
    purpose = infer_message_purpose(name, package, fields)

    doc = f"# {name} ({qualified_name})\n\n"
    doc += f"**Source:** `{proto_file}`\n\n"
    doc += f"## Description\n\n{purpose}\n\n"

    if not fields:
        doc += "## Fields\n\nThis message has no fields (empty message).\n\n"
    else:
        # Check if this is a oneof container
        has_oneof = any('oneof' in str(f.get('description', '')) for f in fields)

        doc += "## Fields\n\n"
        doc += "| Field | Type | Number | Description | Constraints |\n"
        doc += "|-------|------|--------|-------------|-------------|\n"

        for field in fields:
            doc += f"| {field['name']} | {field['type']} | {field['number']} | {field['description']} | {field['constraints']} |\n"

        doc += "\n"

    doc += "## Usage Context\n\n"
    if 'cmd' in package.lower():
        doc += "Command sent from clients to control system behavior or request operations.\n\n"
    elif 'ser' in package or 'data' in package.lower():
        doc += "State data broadcast from backend to clients, typically via WebSocket/WebTransport.\n\n"
    else:
        doc += "Part of the Jettison protocol buffer schema.\n\n"

    doc += "## Related Messages\n\n"
    doc += f"- See `{proto_file}` for complete context\n"

    return doc


def generate_enum_doc(enum: Dict, proto_file: str, package: str) -> str:
    """Generate markdown documentation for an enum."""
    name = enum['name']
    values = enum['values']

    qualified_name = f"{package}.{name}"

    doc = f"# {name} ({qualified_name})\n\n"
    doc += f"**Source:** `{proto_file}`\n\n"
    doc += f"## Description\n\nEnumeration defining valid values for {name}.\n\n"

    doc += "## Values\n\n"
    doc += "| Name | Number | Description |\n"
    doc += "|------|--------|-------------|\n"

    for value in values:
        doc += f"| {value['name']} | {value['number']} | {value['description']} |\n"

    doc += "\n"
    doc += "## Usage Context\n\n"
    doc += "Used in various messages to specify enumerated options or states.\n\n"

    return doc


def process_proto_file(proto_path: Path):
    """Process a single proto file and generate all documentation."""
    print(f"Processing {proto_path.name}...")

    with open(proto_path, 'r') as f:
        content = f.read()

    package = get_package_name(content)
    proto_name = proto_path.stem

    # Create docs directory
    docs_subdir = DOCS_DIR / proto_name
    docs_subdir.mkdir(exist_ok=True)

    # Process all messages
    messages = find_all_messages(content)
    for msg_name in messages:
        msg = parse_message(content, msg_name)
        if msg:
            doc_content = generate_message_doc(msg, proto_path.name, package)
            doc_file = docs_subdir / f"{msg_name}.md"

            # Only write if doesn't exist or is different
            if not doc_file.exists() or doc_file.read_text() != doc_content:
                with open(doc_file, 'w') as f:
                    f.write(doc_content)
                print(f"  Created: {doc_file.relative_to(DOCS_DIR)}")

    # Process all enums
    enums = find_all_enums(content)
    for enum_name in enums:
        enum = parse_enum(content, enum_name)
        if enum:
            doc_content = generate_enum_doc(enum, proto_path.name, package)
            doc_file = docs_subdir / f"{enum_name}.md"

            if not doc_file.exists() or doc_file.read_text() != doc_content:
                with open(doc_file, 'w') as f:
                    f.write(doc_content)
                print(f"  Created: {doc_file.relative_to(DOCS_DIR)}")


def main():
    """Main entry point."""
    print("Generating protobuf documentation...")
    print(f"Proto directory: {PROTO_DIR}")
    print(f"Docs directory: {DOCS_DIR}")
    print()

    # Find all proto files
    proto_files = sorted(PROTO_DIR.glob("*.proto"))
    proto_files = [p for p in proto_files if not p.name.startswith('test')]

    print(f"Found {len(proto_files)} proto files\n")

    for proto_file in proto_files:
        try:
            process_proto_file(proto_file)
        except Exception as e:
            print(f"ERROR processing {proto_file.name}: {e}")

    print("\nDocumentation generation complete!")


if __name__ == "__main__":
    main()
