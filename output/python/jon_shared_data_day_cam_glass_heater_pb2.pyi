from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataDayCamGlassHeater(_message.Message):
    __slots__ = ("temperature", "status")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    status: bool
    def __init__(self, temperature: _Optional[float] = ..., status: bool = ...) -> None: ...
