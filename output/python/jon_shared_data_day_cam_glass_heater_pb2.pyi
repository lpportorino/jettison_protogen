from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataDayCamGlassHeater(_message.Message):
    __slots__ = ("temperature", "status", "is_started")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    IS_STARTED_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    status: bool
    is_started: bool
    def __init__(self, temperature: _Optional[float] = ..., status: bool = ..., is_started: bool = ...) -> None: ...
