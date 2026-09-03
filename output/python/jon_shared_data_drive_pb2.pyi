import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataDrive(_message.Message):
    __slots__ = ("program", "state", "phase", "error_code", "poi_index", "park_in_progress", "tables_generation", "wasm_version", "rejected_commands")
    PROGRAM_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    PHASE_FIELD_NUMBER: _ClassVar[int]
    ERROR_CODE_FIELD_NUMBER: _ClassVar[int]
    POI_INDEX_FIELD_NUMBER: _ClassVar[int]
    PARK_IN_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    TABLES_GENERATION_FIELD_NUMBER: _ClassVar[int]
    WASM_VERSION_FIELD_NUMBER: _ClassVar[int]
    REJECTED_COMMANDS_FIELD_NUMBER: _ClassVar[int]
    program: _jon_shared_data_types_pb2.JonGuiDataDriveProgram
    state: _jon_shared_data_types_pb2.JonGuiDataDriveState
    phase: int
    error_code: int
    poi_index: int
    park_in_progress: bool
    tables_generation: int
    wasm_version: int
    rejected_commands: int
    def __init__(self, program: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataDriveProgram, str]] = ..., state: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataDriveState, str]] = ..., phase: _Optional[int] = ..., error_code: _Optional[int] = ..., poi_index: _Optional[int] = ..., park_in_progress: bool = ..., tables_generation: _Optional[int] = ..., wasm_version: _Optional[int] = ..., rejected_commands: _Optional[int] = ...) -> None: ...
