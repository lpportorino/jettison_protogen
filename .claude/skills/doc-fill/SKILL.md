---
name: doc-fill
description: Interactive workflow to fill in missing proto documentation. Walks through undocumented messages with Q&A.
allowed-tools: Read, Bash, Edit, Write, Glob, Grep, AskUserQuestion
---

# Proto Documentation Filling Skill

Interactive workflow for filling in missing protobuf documentation through guided Q&A.

---

## Quick Start

When invoked, guide the user through documenting undocumented proto messages and fields.

### Workflow

1. **Find Next Item** - Run `/doc-next` or query undocumented items
2. **Show Context** - Display field types, constraints, related messages
3. **Ask Questions** - Gather information from user using AskUserQuestion
4. **Write Documentation** - Edit markdown file with collected info
5. **Verify** - Show the user the result

---

## Interactive Q&A Flow

Use the `AskUserQuestion` tool to gather information for each message.

### Questions for Each Message

#### 1. Message Purpose
Ask: "What does {message_id} do?"

Provide context: Show the fields, their types, and constraints to help user understand.

#### 2. Category
Ask: "What category best describes this message?"

Options:
- `:sensor` - Reads data from hardware
- `:actuator` - Controls hardware/causes action
- `:settings` - Configuration values
- `:status` - Read-only state information
- `:lifecycle` - Start/stop/initialize commands
- `:diagnostic` - Debug/testing/calibration

**Hints:**
- `cmd.*.Start/Stop/TurnOn/TurnOff` → `:lifecycle`
- `cmd.*.Set*` → `:actuator`
- `cmd.*.Get*` → `:sensor`
- `ser.*` messages → typically `:status`

#### 3. UI Pattern
Ask: "How should this be displayed in a UI?"

Options:
- Atomic:
  - `:toggle` - On/off switch
  - `:action-button` - Button that triggers action
  - `:slider` - Continuous value control
  - `:stepper` - Increment/decrement buttons
  - `:indicator` - Read-only display
  - `:enum-picker` - Select from options
- Molecular:
  - `:slider-with-steppers` - Slider + fine control buttons
  - `:press-accelerating` - Hold to accelerate
- Composite:
  - `:slider-with-presets` - Slider + preset buttons
  - `:directional-mover` - Multi-axis control (pan/tilt)
  - `:tabbed-config` - Multiple config sections
  - `:state-machine-menu` - Complex state transitions

**Hints:**
- Parameterless commands → `:action-button`
- Single normalized value (0-1) → `:slider` or `:slider-with-presets`
- Boolean field → `:toggle`
- Enum field → `:enum-picker`
- Start/Stop commands → `:action-button`

#### 4. Feedback Type
Ask: "How should the UI respond after sending this command?"

Options:
- `:fire-and-forget` - Send and don't wait for confirmation
- `:pending-timeout` - Show pending state, timeout after ~2s if no response
- `:poll-confirm` - Poll server for confirmation
- `:optimistic-visual` - Update UI immediately, revert on failure

**Hints:**
- Lifecycle commands (start/stop) → `:pending-timeout`
- Real-time controls (zoom, focus) → `:optimistic-visual`
- One-shot actions → `:fire-and-forget`

#### 5. Related State
Ask: "Which state message (ser.*) shows the result of this command? (or 'none')"

Example: `cmd.DayCamera.SetIris` → `ser.JonGuiDataCameraDay`

#### 6. Related Commands
Ask: "What other commands are related? (or 'none')"

Example: `cmd.DayCamera.SetIris` → `cmd.DayCamera.SetAutoIris`

#### 7. Preconditions (optional)
Ask: "What must be true before this command can be sent? (or 'none')"

Examples:
- "Camera must be started"
- "Auto mode must be disabled"

### Questions for Each Field (if message has fields)

#### 1. Field Purpose
Ask: "What does the '{name}' field represent?"

Provide context: Show type and constraints.

#### 2. Semantic Type
Ask: "What semantic type is '{name}'?"

Options (field-type compatibility in parentheses):
- `:normalized` - 0-1 range value (double/float only)
- `:angle` - Degrees or radians (double/float/int32/uint32)
- `:percentage` - 0-100% (double/float/int32/uint32)
- `:coordinate-geo` - Lat/lon geographic coordinates (double/float only)
- `:coordinate-viewport` - NDC viewport coordinates -1 to 1 (double/float only)
- `:speed` - Movement speed value (double/float only)
- `:temperature` - Celsius/Fahrenheit (double/float/int32)
- `:voltage` - Volts (double/float only)
- `:current` - Amps (double/float only)
- `:power` - Watts (double/float only)
- `:distance` - Meters (double/float/int32/uint32/int64/uint64)
- `:duration` - Time span, ms or s (uint32/uint64/int32/int64/double/float)
- `:count` - Integer count (uint32/int32/uint64/int64 only)
- `:timestamp` - Time value (uint64/int64/uint32/double only)
- `:identifier` - ID values (uint32/int32/uint64/int64/string)
- `:enum-label` - Enum display name (enum fields ONLY, not bool/int)
- `:toggle-state` - Boolean on/off (bool fields ONLY)
- `:raw` - No special type (any field type)

**IMPORTANT: Type compatibility rules:**
- `:enum-label` ONLY works with `:enum` field type — never use for `:bool` or numeric fields
- `:toggle-state` ONLY works with `:bool` field type
- `:normalized`/`:coordinate-geo`/`:coordinate-viewport` ONLY work with `:double`/`:float`
- `:count` ONLY works with integer types (not `:double`/`:float`)
- For `:message` typed fields, do NOT assign a semantic type — omit `:interaction` entirely

**Hints from constraints:**
- `gte: 0, lte: 1` with double/float → `:normalized`
- `gte: -1, lte: 1` with double/float → `:coordinate-viewport` (NDC)
- `gte: -90, lte: 90` with double → `:coordinate-geo` (latitude)
- `gte: -273.15` (temperature) → `:temperature`
- `gte: 0, lte: 360` → `:angle`
- Bool field → `:toggle-state` (never `:enum-label`)
- Enum field with `:defined-only` → `:enum-label`
- Voltage constraints → `:voltage`

#### 3. Unit
Ask: "What unit should be displayed? (e.g., '%', 'V', 'degrees', 'ms')"

#### 4. Precision
Ask: "How many decimal places? (e.g., 0, 1, 2)"

#### 5. Display Format (optional)
Ask: "Any special display format? (e.g., '{value * 100}%')"

---

## Writing Documentation

After gathering information, edit the markdown file.

### File Location
```
docs/proto/{message_id}.md
```

Example: `docs/proto/cmd.PMU.Start.md`

### Sections to Edit

#### Description Section
Replace the placeholder:
```markdown
## Description

*No description yet.*
```

With:
```markdown
## Description

{purpose from Q1}
```

#### Interaction Section
Add after the Fields table:
```markdown
## Interaction

- **Category:** {category}
- **UI Pattern:** {pattern}
- **Feedback:** {feedback}

### Purpose

{expanded purpose}

### Related State

- [[proto/{state_message}]]

### Related Commands

- [[proto/{related_command}]]

### Preconditions

- {precondition}
```

#### Field Notes Section
For each field with metadata:
```markdown
## Field Notes

### {field_name} (#{number})

{field description}

#### Metadata

- **Semantic Type:** {semantic_type}
- **Unit:** {unit}
- **Precision:** {precision}
- **Display Format:** `{display_format}`
```

---

## Example Session

```
Claude: Let me find the next undocumented message.

[Runs doc-next.clj]

The next undocumented message is cmd.PMU.Start.

Source: jon_shared_cmd_pmu.proto
Fields: (none - parameterless command)

Let me ask you about this message.

[Uses AskUserQuestion]
Question: What does cmd.PMU.Start do?
Options: (free text response)

User: It initializes and starts the Power Management Unit, beginning power monitoring

Claude: [Uses AskUserQuestion]
Question: What category?
Options: :lifecycle (Recommended), :actuator, :settings, :other

User: lifecycle

Claude: [Uses AskUserQuestion]
Question: What UI pattern?
Options: :action-button (Recommended), :toggle, :other

User: action-button

Claude: [Uses AskUserQuestion]
Question: What feedback type?
Options: :pending-timeout (Recommended), :fire-and-forget, :other

User: pending-timeout

Claude: [Uses AskUserQuestion]
Question: Related state message?
Options: ser.JonGuiDataPMU (Recommended), none, other

User: ser.JonGuiDataPMU

Claude: [Uses AskUserQuestion]
Question: Related commands?
Options: cmd.PMU.Stop, cmd.PMU.TurnOn, none, other

User: cmd.PMU.Stop

Claude: Now I'll write the documentation.

[Edits docs/proto/cmd.PMU.Start.md]

Done! Here's the updated documentation:
[Shows the result]
```

---

## Module-by-Module Strategy

Work through modules in priority order:

1. **Current Priority: Heater** - 5 undocumented commands
2. **PMU** - 12 undocumented commands + state message
3. **New ser.* messages** - State/data messages

For each module:
1. Document the Root message first (shows structure)
2. Document lifecycle commands (Start, Stop, TurnOn, TurnOff)
3. Document action commands (Set*, Get*)
4. Document the state message (ser.*)

---

## Tips

1. **Use constraints as hints**
   - `gte: 0, lte: 1` suggests `:normalized` semantic type
   - Temperature constraints suggest `:temperature`
   - Voltage-like constraints suggest `:voltage`

2. **Check similar messages**
   - `cmd.DayCamera.*` is well-documented, use as reference
   - `cmd.HeatCamera.*` has similar patterns

3. **State messages**
   - `ser.*` messages are typically `:status` category
   - UI pattern usually `:indicator`

4. **Root messages**
   - Often `:tabbed-config` or container pattern
   - Usually have oneofs with child commands

5. **Skip if unknown**
   - User can say "skip" or "unknown" for any question
   - Can be filled in later

---

## Finding Related Messages

Use proto-search to find related items:
```bash
bb docs/.protodoc/scripts/proto-search.clj "PMU" docs/.protodoc/proto-db.edn
```

Look for patterns:
- `cmd.X.Set*` usually has `cmd.X.SetAuto*` counterpart
- `cmd.X.*` commands usually have `ser.JonGuiData*` state

---

## Post-Write Validation

**IMPORTANT:** After writing documentation for each message, run lint to verify no errors were introduced:

```bash
/docs-lint
```

Check for:
- `:semantic-type-mismatch` errors — field type incompatible with chosen semantic type
- `:invalid-references` errors — related-state/related-commands pointing to non-existent messages
- `:interaction-incomplete` warnings — missing `:ui-pattern` or `:feedback`

Fix any errors before moving to the next message.
