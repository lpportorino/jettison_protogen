import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataDayCamGlassHeater(_message.Message):
    __slots__ = ("temperature", "status", "is_started", "meteo")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    IS_STARTED_FIELD_NUMBER: _ClassVar[int]
    METEO_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    status: bool
    is_started: bool
    meteo: _jon_shared_data_types_pb2.JonGuiDataMeteo
    def __init__(self, temperature: _Optional[float] = ..., status: bool = ..., is_started: bool = ..., meteo: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataMeteo, _Mapping]] = ...) -> None: ...
