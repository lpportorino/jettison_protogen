from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataCompass(_message.Message):
    __slots__ = ("azimuth", "elevation", "bank", "offsetAzimuth", "offsetElevation", "magneticDeclination", "calibrating")
    AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_FIELD_NUMBER: _ClassVar[int]
    BANK_FIELD_NUMBER: _ClassVar[int]
    OFFSETAZIMUTH_FIELD_NUMBER: _ClassVar[int]
    OFFSETELEVATION_FIELD_NUMBER: _ClassVar[int]
    MAGNETICDECLINATION_FIELD_NUMBER: _ClassVar[int]
    CALIBRATING_FIELD_NUMBER: _ClassVar[int]
    azimuth: float
    elevation: float
    bank: float
    offsetAzimuth: float
    offsetElevation: float
    magneticDeclination: float
    calibrating: bool
    def __init__(self, azimuth: _Optional[float] = ..., elevation: _Optional[float] = ..., bank: _Optional[float] = ..., offsetAzimuth: _Optional[float] = ..., offsetElevation: _Optional[float] = ..., magneticDeclination: _Optional[float] = ..., calibrating: bool = ...) -> None: ...
