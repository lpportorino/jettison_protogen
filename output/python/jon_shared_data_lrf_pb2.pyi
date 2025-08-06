import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataLrf(_message.Message):
    __slots__ = ("is_scanning", "is_measuring", "measure_id", "target", "pointer_mode", "fogModeEnabled", "is_refining")
    IS_SCANNING_FIELD_NUMBER: _ClassVar[int]
    IS_MEASURING_FIELD_NUMBER: _ClassVar[int]
    MEASURE_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_FIELD_NUMBER: _ClassVar[int]
    POINTER_MODE_FIELD_NUMBER: _ClassVar[int]
    FOGMODEENABLED_FIELD_NUMBER: _ClassVar[int]
    IS_REFINING_FIELD_NUMBER: _ClassVar[int]
    is_scanning: bool
    is_measuring: bool
    measure_id: int
    target: JonGuiDataTarget
    pointer_mode: _jon_shared_data_types_pb2.JonGuiDatatLrfLaserPointerModes
    fogModeEnabled: bool
    is_refining: bool
    def __init__(self, is_scanning: bool = ..., is_measuring: bool = ..., measure_id: _Optional[int] = ..., target: _Optional[_Union[JonGuiDataTarget, _Mapping]] = ..., pointer_mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDatatLrfLaserPointerModes, str]] = ..., fogModeEnabled: bool = ..., is_refining: bool = ...) -> None: ...

class JonGuiDataTarget(_message.Message):
    __slots__ = ("timestamp", "target_longitude", "target_latitude", "target_altitude", "observer_longitude", "observer_latitude", "observer_altitude", "observer_azimuth", "observer_elevation", "observer_bank", "distance_2d", "distance_3b", "observer_fix_type", "session_id", "target_id", "target_color", "type", "uuid_part1", "uuid_part2", "uuid_part3", "uuid_part4")
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    TARGET_LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    TARGET_LATITUDE_FIELD_NUMBER: _ClassVar[int]
    TARGET_ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_LATITUDE_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_BANK_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_2D_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_3B_FIELD_NUMBER: _ClassVar[int]
    OBSERVER_FIX_TYPE_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_COLOR_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    UUID_PART1_FIELD_NUMBER: _ClassVar[int]
    UUID_PART2_FIELD_NUMBER: _ClassVar[int]
    UUID_PART3_FIELD_NUMBER: _ClassVar[int]
    UUID_PART4_FIELD_NUMBER: _ClassVar[int]
    timestamp: int
    target_longitude: float
    target_latitude: float
    target_altitude: float
    observer_longitude: float
    observer_latitude: float
    observer_altitude: float
    observer_azimuth: float
    observer_elevation: float
    observer_bank: float
    distance_2d: float
    distance_3b: float
    observer_fix_type: _jon_shared_data_types_pb2.JonGuiDataGpsFixType
    session_id: int
    target_id: int
    target_color: RgbColor
    type: int
    uuid_part1: int
    uuid_part2: int
    uuid_part3: int
    uuid_part4: int
    def __init__(self, timestamp: _Optional[int] = ..., target_longitude: _Optional[float] = ..., target_latitude: _Optional[float] = ..., target_altitude: _Optional[float] = ..., observer_longitude: _Optional[float] = ..., observer_latitude: _Optional[float] = ..., observer_altitude: _Optional[float] = ..., observer_azimuth: _Optional[float] = ..., observer_elevation: _Optional[float] = ..., observer_bank: _Optional[float] = ..., distance_2d: _Optional[float] = ..., distance_3b: _Optional[float] = ..., observer_fix_type: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataGpsFixType, str]] = ..., session_id: _Optional[int] = ..., target_id: _Optional[int] = ..., target_color: _Optional[_Union[RgbColor, _Mapping]] = ..., type: _Optional[int] = ..., uuid_part1: _Optional[int] = ..., uuid_part2: _Optional[int] = ..., uuid_part3: _Optional[int] = ..., uuid_part4: _Optional[int] = ...) -> None: ...

class RgbColor(_message.Message):
    __slots__ = ("red", "green", "blue")
    RED_FIELD_NUMBER: _ClassVar[int]
    GREEN_FIELD_NUMBER: _ClassVar[int]
    BLUE_FIELD_NUMBER: _ClassVar[int]
    red: int
    green: int
    blue: int
    def __init__(self, red: _Optional[int] = ..., green: _Optional[int] = ..., blue: _Optional[int] = ...) -> None: ...
