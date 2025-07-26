import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataGps(_message.Message):
    __slots__ = ("longitude", "latitude", "altitude", "manual_longitude", "manual_latitude", "manual_altitude", "fix_type", "use_manual")
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    MANUAL_LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    MANUAL_LATITUDE_FIELD_NUMBER: _ClassVar[int]
    MANUAL_ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    FIX_TYPE_FIELD_NUMBER: _ClassVar[int]
    USE_MANUAL_FIELD_NUMBER: _ClassVar[int]
    longitude: float
    latitude: float
    altitude: float
    manual_longitude: float
    manual_latitude: float
    manual_altitude: float
    fix_type: _jon_shared_data_types_pb2.JonGuiDataGpsFixType
    use_manual: bool
    def __init__(self, longitude: _Optional[float] = ..., latitude: _Optional[float] = ..., altitude: _Optional[float] = ..., manual_longitude: _Optional[float] = ..., manual_latitude: _Optional[float] = ..., manual_altitude: _Optional[float] = ..., fix_type: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataGpsFixType, str]] = ..., use_manual: bool = ...) -> None: ...
