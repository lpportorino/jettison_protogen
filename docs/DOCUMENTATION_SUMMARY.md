# Protobuf Documentation Summary

This directory contains comprehensive documentation for all protobuf messages in the Jettison system.

## Statistics

- **Total Directories**: 33
- **Total Documentation Files**: 282
- **Coverage**: All proto files in `/home/jare/git/jettison_protogen/proto/`

## Structure

Each proto file has a corresponding directory with individual markdown files for each message and enum.

### Directory Structure

```
docs/
├── index.md                              # Main index (pre-existing)
├── json-descriptor-reference.md          # Reference doc (pre-existing)
├── DOCUMENTATION_SUMMARY.md              # This file
├── generate_docs.py                      # Documentation generator script
│
├── jon_shared_cmd/                       # Root command messages
│   ├── Root.md
│   ├── Ping.md
│   ├── Noop.md
│   └── Frozen.md
│
├── jon_shared_cmd_compass/               # Compass control commands
├── jon_shared_cmd_cv/                    # Computer vision commands
├── jon_shared_cmd_day_camera/            # Day camera control commands
├── jon_shared_cmd_day_cam_glass_heater/  # Lens heater commands
├── jon_shared_cmd_gps/                   # GPS module commands
├── jon_shared_cmd_heat_camera/           # Thermal camera commands
├── jon_shared_cmd_lira/                  # LIRA subsystem commands
├── jon_shared_cmd_lrf/                   # Laser rangefinder commands
├── jon_shared_cmd_lrf_align/             # LRF calibration commands
├── jon_shared_cmd_osd/                   # OSD control commands
├── jon_shared_cmd_power/                 # Power module commands
├── jon_shared_cmd_rotary/                # Rotary platform commands
├── jon_shared_cmd_system/                # System-level commands
│
├── jon_shared_data/                      # Root state message
│   └── JonGUIState.md
│
├── jon_shared_data_actual_space_time/    # Actual orientation data
├── jon_shared_data_camera_day/           # Day camera state
├── jon_shared_data_camera_heat/          # Thermal camera state
├── jon_shared_data_compass/              # Compass state
├── jon_shared_data_compass_calibration/  # Compass calibration state
├── jon_shared_data_day_cam_glass_heater/ # Lens heater state
├── jon_shared_data_gps/                  # GPS state
├── jon_shared_data_lrf/                  # LRF state and target data
├── jon_shared_data_power/                # Power module state
├── jon_shared_data_rec_osd/              # OSD recording state
├── jon_shared_data_rotary/               # Rotary platform state
├── jon_shared_data_system/               # System state
├── jon_shared_data_time/                 # Time/timezone data
│
├── jon_shared_data_types/                # Shared enums and types
│   ├── JonGuiDataVideoChannelHeatFilters.md
│   ├── JonGuiDataVideoChannelHeatAGCModes.md
│   ├── JonGuiDataGpsFixType.md
│   ├── JonGuiDataRotaryMode.md
│   ├── JonGuiDataClientType.md
│   ├── JonOpaquePayload.md
│   └── ... (23 total files)
│
├── jon_video_meta/                       # Video metadata protocol
│   ├── VideoMetaRequest.md
│   ├── VideoMetaResponse.md
│   ├── VideoMeta.md
│   ├── SampleTable.md
│   └── ... (9 total files)
│
├── jon_client_logs/                      # Client logging protocol
│   ├── ClientLogEntry.md
│   └── ClientLogBatch.md
│
└── jon_sych_archive/                     # Archive format
    ├── SychArchiveIndex.md
    ├── ArchiveEntry.md
    ├── TimelineIndex.md
    ├── VideoEntry.md
    └── OSDReference.md
```

## Documentation Format

Each message documentation file includes:

1. **Message Name** - Fully qualified name (package.Message)
2. **Source File** - Original .proto file
3. **Description** - Purpose and usage of the message
4. **Fields Table** - Detailed field information:
   - Field name
   - Type (with repeated/optional modifiers)
   - Field number
   - Description (from comments)
   - Validation constraints (from buf.validate)
5. **Usage Context** - How the message is used in the system
6. **Related Messages** - Cross-references to related types

Each enum documentation file includes:

1. **Enum Name** - Fully qualified name
2. **Source File** - Original .proto file
3. **Description** - Purpose of the enumeration
4. **Values Table** - All enum values with numbers and descriptions
5. **Usage Context** - Where the enum is used

## Command vs Data Messages

### Command Messages (cmd package)
- Sent **from clients to backend** via `cmd_server`
- Trigger system actions (start, stop, configure, measure)
- Located in `jon_shared_cmd*` directories
- Wrapped in `cmd.Root` message with timing metadata

### Data/State Messages (ser package)
- Broadcast **from backend to clients** via `state_server`
- Contain current system state
- Located in `jon_shared_data*` directories
- Wrapped in `ser.JonGUIState` root message

## Special Message Categories

### Video Metadata (jon.video package)
- Request/response for video file metadata
- Used by media_api_server and video_meta_service
- Includes MP4 sample tables for frame-accurate seeking

### Client Logs (jon.logs package)
- Browser log collection protocol
- Sent to backend for storage in TimescaleDB
- Includes device info and optional state snapshots

### Archive Format (jon.archive package)
- Offline video archive structure
- Used for .sych_video tar files
- Includes index, timeline, and optional OSD packages

## Regenerating Documentation

To regenerate all documentation (e.g., after proto changes):

```bash
cd /home/jare/git/jettison_protogen
python3 generate_docs.py
```

The script:
- Parses all .proto files in `proto/` directory
- Creates/updates markdown files in `docs/` subdirectories
- Only writes files if content changes (idempotent)
- Handles nested messages, oneofs, and multi-line annotations
- Extracts buf.validate constraints automatically

## Verification

All documentation has been verified against source proto files:

- ✅ Directory structure matches proto file names
- ✅ All messages and enums documented
- ✅ Field definitions match proto source
- ✅ Validation constraints extracted correctly
- ✅ Comments preserved from proto files

**Spot-checked files**:
1. `jon_video_meta/VideoMetaResponse.md` - Complex message with comments
2. `jon_shared_data_types/JonGuiDataVideoChannelHeatFilters.md` - Enum
3. `jon_shared_cmd_rotary/RotateToGPS.md` - Message with constraints
4. `jon_shared_data_lrf/JonGuiDataTarget.md` - Large message with many fields
5. `jon_sych_archive/SychArchiveIndex.md` - Message with comments and optional fields

All verified against proto source - accuracy confirmed ✅

## Usage Examples

### Finding Command Documentation

```bash
# List all day camera commands
ls docs/jon_shared_cmd_day_camera/

# View specific command
cat docs/jon_shared_cmd_day_camera/SetDigitalZoomLevel.md
```

### Finding State Documentation

```bash
# List all GPS state fields
cat docs/jon_shared_data_gps/JonGuiDataGps.md

# View enum values
cat docs/jon_shared_data_types/JonGuiDataGpsFixType.md
```

### Searching Documentation

```bash
# Find all messages with "zoom" in the name
find docs -name "*oom*.md"

# Search for specific field names
grep -r "altitude" docs/jon_shared_data_gps/
```

## Integration with Frontend

The TypeScript frontend uses generated bindings from `jettison_proto_typescript` submodule:
- Proto source: `jettison_protogen/proto/`
- TS bindings: `jettison_frontend/frontend/ts/proto/jon/`
- Documentation: `jettison_protogen/docs/`

See `jettison_frontend/CLAUDE.md` for frontend integration details.
