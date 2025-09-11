import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "axis", "set_platform_azimuth", "set_platform_elevation", "set_platform_bank", "halt", "set_use_rotary_as_compass", "rotate_to_gps", "set_origin_gps", "set_mode", "rotate_to_ndc", "scan_start", "scan_stop", "scan_pause", "scan_unpause", "get_meteo", "scan_prev", "scan_next", "scan_refresh_node_list", "scan_select_node", "scan_delete_node", "scan_update_node", "scan_add_node", "halt_with_ndc")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    AXIS_FIELD_NUMBER: _ClassVar[int]
    SET_PLATFORM_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    SET_PLATFORM_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    SET_PLATFORM_BANK_FIELD_NUMBER: _ClassVar[int]
    HALT_FIELD_NUMBER: _ClassVar[int]
    SET_USE_ROTARY_AS_COMPASS_FIELD_NUMBER: _ClassVar[int]
    ROTATE_TO_GPS_FIELD_NUMBER: _ClassVar[int]
    SET_ORIGIN_GPS_FIELD_NUMBER: _ClassVar[int]
    SET_MODE_FIELD_NUMBER: _ClassVar[int]
    ROTATE_TO_NDC_FIELD_NUMBER: _ClassVar[int]
    SCAN_START_FIELD_NUMBER: _ClassVar[int]
    SCAN_STOP_FIELD_NUMBER: _ClassVar[int]
    SCAN_PAUSE_FIELD_NUMBER: _ClassVar[int]
    SCAN_UNPAUSE_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    SCAN_PREV_FIELD_NUMBER: _ClassVar[int]
    SCAN_NEXT_FIELD_NUMBER: _ClassVar[int]
    SCAN_REFRESH_NODE_LIST_FIELD_NUMBER: _ClassVar[int]
    SCAN_SELECT_NODE_FIELD_NUMBER: _ClassVar[int]
    SCAN_DELETE_NODE_FIELD_NUMBER: _ClassVar[int]
    SCAN_UPDATE_NODE_FIELD_NUMBER: _ClassVar[int]
    SCAN_ADD_NODE_FIELD_NUMBER: _ClassVar[int]
    HALT_WITH_NDC_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    axis: Axis
    set_platform_azimuth: SetPlatformAzimuth
    set_platform_elevation: SetPlatformElevation
    set_platform_bank: SetPlatformBank
    halt: Halt
    set_use_rotary_as_compass: setUseRotaryAsCompass
    rotate_to_gps: RotateToGPS
    set_origin_gps: SetOriginGPS
    set_mode: SetMode
    rotate_to_ndc: RotateToNDC
    scan_start: ScanStart
    scan_stop: ScanStop
    scan_pause: ScanPause
    scan_unpause: ScanUnpause
    get_meteo: GetMeteo
    scan_prev: ScanPrev
    scan_next: ScanNext
    scan_refresh_node_list: ScanRefreshNodeList
    scan_select_node: ScanSelectNode
    scan_delete_node: ScanDeleteNode
    scan_update_node: ScanUpdateNode
    scan_add_node: ScanAddNode
    halt_with_ndc: HaltWithNDC
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., axis: _Optional[_Union[Axis, _Mapping]] = ..., set_platform_azimuth: _Optional[_Union[SetPlatformAzimuth, _Mapping]] = ..., set_platform_elevation: _Optional[_Union[SetPlatformElevation, _Mapping]] = ..., set_platform_bank: _Optional[_Union[SetPlatformBank, _Mapping]] = ..., halt: _Optional[_Union[Halt, _Mapping]] = ..., set_use_rotary_as_compass: _Optional[_Union[setUseRotaryAsCompass, _Mapping]] = ..., rotate_to_gps: _Optional[_Union[RotateToGPS, _Mapping]] = ..., set_origin_gps: _Optional[_Union[SetOriginGPS, _Mapping]] = ..., set_mode: _Optional[_Union[SetMode, _Mapping]] = ..., rotate_to_ndc: _Optional[_Union[RotateToNDC, _Mapping]] = ..., scan_start: _Optional[_Union[ScanStart, _Mapping]] = ..., scan_stop: _Optional[_Union[ScanStop, _Mapping]] = ..., scan_pause: _Optional[_Union[ScanPause, _Mapping]] = ..., scan_unpause: _Optional[_Union[ScanUnpause, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ..., scan_prev: _Optional[_Union[ScanPrev, _Mapping]] = ..., scan_next: _Optional[_Union[ScanNext, _Mapping]] = ..., scan_refresh_node_list: _Optional[_Union[ScanRefreshNodeList, _Mapping]] = ..., scan_select_node: _Optional[_Union[ScanSelectNode, _Mapping]] = ..., scan_delete_node: _Optional[_Union[ScanDeleteNode, _Mapping]] = ..., scan_update_node: _Optional[_Union[ScanUpdateNode, _Mapping]] = ..., scan_add_node: _Optional[_Union[ScanAddNode, _Mapping]] = ..., halt_with_ndc: _Optional[_Union[HaltWithNDC, _Mapping]] = ...) -> None: ...

class Axis(_message.Message):
    __slots__ = ("azimuth", "elevation")
    AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_FIELD_NUMBER: _ClassVar[int]
    azimuth: Azimuth
    elevation: Elevation
    def __init__(self, azimuth: _Optional[_Union[Azimuth, _Mapping]] = ..., elevation: _Optional[_Union[Elevation, _Mapping]] = ...) -> None: ...

class SetMode(_message.Message):
    __slots__ = ("mode",)
    MODE_FIELD_NUMBER: _ClassVar[int]
    mode: _jon_shared_data_types_pb2.JonGuiDataRotaryMode
    def __init__(self, mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryMode, str]] = ...) -> None: ...

class SetAzimuthValue(_message.Message):
    __slots__ = ("value", "direction")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    value: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, value: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class RotateAzimuthTo(_message.Message):
    __slots__ = ("target_value", "speed", "direction")
    TARGET_VALUE_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    target_value: float
    speed: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, target_value: _Optional[float] = ..., speed: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class RotateAzimuth(_message.Message):
    __slots__ = ("speed", "direction")
    SPEED_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    speed: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, speed: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class RotateElevation(_message.Message):
    __slots__ = ("speed", "direction")
    SPEED_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    speed: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, speed: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class SetElevationValue(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class RotateElevationTo(_message.Message):
    __slots__ = ("target_value", "speed")
    TARGET_VALUE_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    target_value: float
    speed: float
    def __init__(self, target_value: _Optional[float] = ..., speed: _Optional[float] = ...) -> None: ...

class RotateElevationRelative(_message.Message):
    __slots__ = ("value", "speed", "direction")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    value: float
    speed: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, value: _Optional[float] = ..., speed: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class RotateElevationRelativeSet(_message.Message):
    __slots__ = ("value", "direction")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    value: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, value: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class RotateAzimuthRelative(_message.Message):
    __slots__ = ("value", "speed", "direction")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    value: float
    speed: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, value: _Optional[float] = ..., speed: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class RotateAzimuthRelativeSet(_message.Message):
    __slots__ = ("value", "direction")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    value: float
    direction: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    def __init__(self, value: _Optional[float] = ..., direction: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ...) -> None: ...

class SetPlatformAzimuth(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetPlatformElevation(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetPlatformBank(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Azimuth(_message.Message):
    __slots__ = ("set_value", "rotate_to", "rotate", "relative", "relative_set", "halt")
    SET_VALUE_FIELD_NUMBER: _ClassVar[int]
    ROTATE_TO_FIELD_NUMBER: _ClassVar[int]
    ROTATE_FIELD_NUMBER: _ClassVar[int]
    RELATIVE_FIELD_NUMBER: _ClassVar[int]
    RELATIVE_SET_FIELD_NUMBER: _ClassVar[int]
    HALT_FIELD_NUMBER: _ClassVar[int]
    set_value: SetAzimuthValue
    rotate_to: RotateAzimuthTo
    rotate: RotateAzimuth
    relative: RotateAzimuthRelative
    relative_set: RotateAzimuthRelativeSet
    halt: HaltAzimuth
    def __init__(self, set_value: _Optional[_Union[SetAzimuthValue, _Mapping]] = ..., rotate_to: _Optional[_Union[RotateAzimuthTo, _Mapping]] = ..., rotate: _Optional[_Union[RotateAzimuth, _Mapping]] = ..., relative: _Optional[_Union[RotateAzimuthRelative, _Mapping]] = ..., relative_set: _Optional[_Union[RotateAzimuthRelativeSet, _Mapping]] = ..., halt: _Optional[_Union[HaltAzimuth, _Mapping]] = ...) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Halt(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanStart(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanStop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanPause(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanUnpause(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class HaltAzimuth(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class HaltElevation(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanPrev(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanNext(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanRefreshNodeList(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanSelectNode(_message.Message):
    __slots__ = ("index",)
    INDEX_FIELD_NUMBER: _ClassVar[int]
    index: int
    def __init__(self, index: _Optional[int] = ...) -> None: ...

class ScanDeleteNode(_message.Message):
    __slots__ = ("index",)
    INDEX_FIELD_NUMBER: _ClassVar[int]
    index: int
    def __init__(self, index: _Optional[int] = ...) -> None: ...

class ScanUpdateNode(_message.Message):
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

class ScanAddNode(_message.Message):
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

class Elevation(_message.Message):
    __slots__ = ("set_value", "rotate_to", "rotate", "relative", "relative_set", "halt")
    SET_VALUE_FIELD_NUMBER: _ClassVar[int]
    ROTATE_TO_FIELD_NUMBER: _ClassVar[int]
    ROTATE_FIELD_NUMBER: _ClassVar[int]
    RELATIVE_FIELD_NUMBER: _ClassVar[int]
    RELATIVE_SET_FIELD_NUMBER: _ClassVar[int]
    HALT_FIELD_NUMBER: _ClassVar[int]
    set_value: SetElevationValue
    rotate_to: RotateElevationTo
    rotate: RotateElevation
    relative: RotateElevationRelative
    relative_set: RotateElevationRelativeSet
    halt: HaltElevation
    def __init__(self, set_value: _Optional[_Union[SetElevationValue, _Mapping]] = ..., rotate_to: _Optional[_Union[RotateElevationTo, _Mapping]] = ..., rotate: _Optional[_Union[RotateElevation, _Mapping]] = ..., relative: _Optional[_Union[RotateElevationRelative, _Mapping]] = ..., relative_set: _Optional[_Union[RotateElevationRelativeSet, _Mapping]] = ..., halt: _Optional[_Union[HaltElevation, _Mapping]] = ...) -> None: ...

class setUseRotaryAsCompass(_message.Message):
    __slots__ = ("flag",)
    FLAG_FIELD_NUMBER: _ClassVar[int]
    flag: bool
    def __init__(self, flag: bool = ...) -> None: ...

class RotateToGPS(_message.Message):
    __slots__ = ("latitude", "longitude", "altitude")
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    latitude: float
    longitude: float
    altitude: float
    def __init__(self, latitude: _Optional[float] = ..., longitude: _Optional[float] = ..., altitude: _Optional[float] = ...) -> None: ...

class SetOriginGPS(_message.Message):
    __slots__ = ("latitude", "longitude", "altitude")
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    latitude: float
    longitude: float
    altitude: float
    def __init__(self, latitude: _Optional[float] = ..., longitude: _Optional[float] = ..., altitude: _Optional[float] = ...) -> None: ...

class RotateToNDC(_message.Message):
    __slots__ = ("channel", "x", "y", "frame_time", "state_time")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    FRAME_TIME_FIELD_NUMBER: _ClassVar[int]
    STATE_TIME_FIELD_NUMBER: _ClassVar[int]
    channel: _jon_shared_data_types_pb2.JonGuiDataVideoChannel
    x: float
    y: float
    frame_time: int
    state_time: int
    def __init__(self, channel: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannel, str]] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., frame_time: _Optional[int] = ..., state_time: _Optional[int] = ...) -> None: ...

class HaltWithNDC(_message.Message):
    __slots__ = ("channel", "x", "y", "frame_time", "state_time")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    FRAME_TIME_FIELD_NUMBER: _ClassVar[int]
    STATE_TIME_FIELD_NUMBER: _ClassVar[int]
    channel: _jon_shared_data_types_pb2.JonGuiDataVideoChannel
    x: float
    y: float
    frame_time: int
    state_time: int
    def __init__(self, channel: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannel, str]] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., frame_time: _Optional[int] = ..., state_time: _Optional[int] = ...) -> None: ...
