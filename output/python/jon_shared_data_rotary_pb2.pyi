import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataRotary(_message.Message):
    __slots__ = ("azimuth", "azimuth_speed", "elevation", "elevation_speed", "platform_azimuth", "platform_elevation", "platform_bank", "is_moving", "mode", "is_scanning", "is_scanning_paused", "use_rotary_as_compass", "scan_target", "scan_target_max", "sun_azimuth", "sun_elevation", "current_scan_node")
    AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    AZIMUTH_SPEED_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_SPEED_FIELD_NUMBER: _ClassVar[int]
    PLATFORM_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    PLATFORM_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    PLATFORM_BANK_FIELD_NUMBER: _ClassVar[int]
    IS_MOVING_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    IS_SCANNING_FIELD_NUMBER: _ClassVar[int]
    IS_SCANNING_PAUSED_FIELD_NUMBER: _ClassVar[int]
    USE_ROTARY_AS_COMPASS_FIELD_NUMBER: _ClassVar[int]
    SCAN_TARGET_FIELD_NUMBER: _ClassVar[int]
    SCAN_TARGET_MAX_FIELD_NUMBER: _ClassVar[int]
    SUN_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    SUN_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    CURRENT_SCAN_NODE_FIELD_NUMBER: _ClassVar[int]
    azimuth: float
    azimuth_speed: float
    elevation: float
    elevation_speed: float
    platform_azimuth: float
    platform_elevation: float
    platform_bank: float
    is_moving: bool
    mode: _jon_shared_data_types_pb2.JonGuiDataRotaryMode
    is_scanning: bool
    is_scanning_paused: bool
    use_rotary_as_compass: bool
    scan_target: int
    scan_target_max: int
    sun_azimuth: float
    sun_elevation: float
    current_scan_node: ScanNode
    def __init__(self, azimuth: _Optional[float] = ..., azimuth_speed: _Optional[float] = ..., elevation: _Optional[float] = ..., elevation_speed: _Optional[float] = ..., platform_azimuth: _Optional[float] = ..., platform_elevation: _Optional[float] = ..., platform_bank: _Optional[float] = ..., is_moving: bool = ..., mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryMode, str]] = ..., is_scanning: bool = ..., is_scanning_paused: bool = ..., use_rotary_as_compass: bool = ..., scan_target: _Optional[int] = ..., scan_target_max: _Optional[int] = ..., sun_azimuth: _Optional[float] = ..., sun_elevation: _Optional[float] = ..., current_scan_node: _Optional[_Union[ScanNode, _Mapping]] = ...) -> None: ...

class ScanNode(_message.Message):
    __slots__ = ("index", "DayZoomTableValue", "HeatZoomTableValue", "azimuth", "elevation", "linger", "speed")
    INDEX_FIELD_NUMBER: _ClassVar[int]
    DAYZOOMTABLEVALUE_FIELD_NUMBER: _ClassVar[int]
    HEATZOOMTABLEVALUE_FIELD_NUMBER: _ClassVar[int]
    AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_FIELD_NUMBER: _ClassVar[int]
    LINGER_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    index: int
    DayZoomTableValue: int
    HeatZoomTableValue: int
    azimuth: float
    elevation: float
    linger: float
    speed: float
    def __init__(self, index: _Optional[int] = ..., DayZoomTableValue: _Optional[int] = ..., HeatZoomTableValue: _Optional[int] = ..., azimuth: _Optional[float] = ..., elevation: _Optional[float] = ..., linger: _Optional[float] = ..., speed: _Optional[float] = ...) -> None: ...
