import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataCompass(_message.Message):
    __slots__ = ("azimuth", "elevation", "bank", "offsetAzimuth", "offsetElevation", "magneticDeclination", "calibrating", "is_started", "meteo")
    AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_FIELD_NUMBER: _ClassVar[int]
    BANK_FIELD_NUMBER: _ClassVar[int]
    OFFSETAZIMUTH_FIELD_NUMBER: _ClassVar[int]
    OFFSETELEVATION_FIELD_NUMBER: _ClassVar[int]
    MAGNETICDECLINATION_FIELD_NUMBER: _ClassVar[int]
    CALIBRATING_FIELD_NUMBER: _ClassVar[int]
    IS_STARTED_FIELD_NUMBER: _ClassVar[int]
    METEO_FIELD_NUMBER: _ClassVar[int]
    azimuth: float
    elevation: float
    bank: float
    offsetAzimuth: float
    offsetElevation: float
    magneticDeclination: float
    calibrating: bool
    is_started: bool
    meteo: _jon_shared_data_types_pb2.JonGuiDataMeteo
    def __init__(self, azimuth: _Optional[float] = ..., elevation: _Optional[float] = ..., bank: _Optional[float] = ..., offsetAzimuth: _Optional[float] = ..., offsetElevation: _Optional[float] = ..., magneticDeclination: _Optional[float] = ..., calibrating: bool = ..., is_started: bool = ..., meteo: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataMeteo, _Mapping]] = ...) -> None: ...
