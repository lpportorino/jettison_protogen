from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("refine_target",)
    REFINE_TARGET_FIELD_NUMBER: _ClassVar[int]
    refine_target: Refine_target
    def __init__(self, refine_target: _Optional[_Union[Refine_target, _Mapping]] = ...) -> None: ...

class Refine_target(_message.Message):
    __slots__ = ("target",)
    TARGET_FIELD_NUMBER: _ClassVar[int]
    target: JonGuiDataLiraTarget
    def __init__(self, target: _Optional[_Union[JonGuiDataLiraTarget, _Mapping]] = ...) -> None: ...

class JonGuiDataLiraTarget(_message.Message):
    __slots__ = ("timestamp", "target_longitude", "target_latitude", "target_altitude", "target_azimuth", "target_elevation", "distance", "uuid_part1", "uuid_part2", "uuid_part3", "uuid_part4")
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    TARGET_LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    TARGET_LATITUDE_FIELD_NUMBER: _ClassVar[int]
    TARGET_ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    TARGET_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    TARGET_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    UUID_PART1_FIELD_NUMBER: _ClassVar[int]
    UUID_PART2_FIELD_NUMBER: _ClassVar[int]
    UUID_PART3_FIELD_NUMBER: _ClassVar[int]
    UUID_PART4_FIELD_NUMBER: _ClassVar[int]
    timestamp: int
    target_longitude: float
    target_latitude: float
    target_altitude: float
    target_azimuth: float
    target_elevation: float
    distance: float
    uuid_part1: int
    uuid_part2: int
    uuid_part3: int
    uuid_part4: int
    def __init__(self, timestamp: _Optional[int] = ..., target_longitude: _Optional[float] = ..., target_latitude: _Optional[float] = ..., target_altitude: _Optional[float] = ..., target_azimuth: _Optional[float] = ..., target_elevation: _Optional[float] = ..., distance: _Optional[float] = ..., uuid_part1: _Optional[int] = ..., uuid_part2: _Optional[int] = ..., uuid_part3: _Optional[int] = ..., uuid_part4: _Optional[int] = ...) -> None: ...
