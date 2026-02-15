from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataTime(_message.Message):
    __slots__ = ("timestamp", "manual_timestamp", "zone_id", "use_manual_time")
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    MANUAL_TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    ZONE_ID_FIELD_NUMBER: _ClassVar[int]
    USE_MANUAL_TIME_FIELD_NUMBER: _ClassVar[int]
    timestamp: int
    manual_timestamp: int
    zone_id: int
    use_manual_time: bool
    def __init__(self, timestamp: _Optional[int] = ..., manual_timestamp: _Optional[int] = ..., zone_id: _Optional[int] = ..., use_manual_time: bool = ...) -> None: ...
