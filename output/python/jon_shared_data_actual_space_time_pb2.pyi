from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataActualSpaceTime(_message.Message):
    __slots__ = ("azimuth", "elevation", "bank", "latitude", "longitude", "altitude", "timestamp")
    AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_FIELD_NUMBER: _ClassVar[int]
    BANK_FIELD_NUMBER: _ClassVar[int]
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    azimuth: float
    elevation: float
    bank: float
    latitude: float
    longitude: float
    altitude: float
    timestamp: int
    def __init__(self, azimuth: _Optional[float] = ..., elevation: _Optional[float] = ..., bank: _Optional[float] = ..., latitude: _Optional[float] = ..., longitude: _Optional[float] = ..., altitude: _Optional[float] = ..., timestamp: _Optional[int] = ...) -> None: ...
