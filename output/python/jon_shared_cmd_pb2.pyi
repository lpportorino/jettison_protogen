import jon_shared_cmd_compass_pb2 as _jon_shared_cmd_compass_pb2
import jon_shared_cmd_gps_pb2 as _jon_shared_cmd_gps_pb2
import jon_shared_cmd_lrf_pb2 as _jon_shared_cmd_lrf_pb2
import jon_shared_cmd_day_camera_pb2 as _jon_shared_cmd_day_camera_pb2
import jon_shared_cmd_heat_camera_pb2 as _jon_shared_cmd_heat_camera_pb2
import jon_shared_cmd_rotary_pb2 as _jon_shared_cmd_rotary_pb2
import jon_shared_cmd_osd_pb2 as _jon_shared_cmd_osd_pb2
import jon_shared_cmd_lrf_align_pb2 as _jon_shared_cmd_lrf_align_pb2
import jon_shared_cmd_system_pb2 as _jon_shared_cmd_system_pb2
import jon_shared_cmd_cv_pb2 as _jon_shared_cmd_cv_pb2
import jon_shared_cmd_day_cam_glass_heater_pb2 as _jon_shared_cmd_day_cam_glass_heater_pb2
import jon_shared_cmd_lira_pb2 as _jon_shared_cmd_lira_pb2
import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("protocol_version", "session_id", "important", "from_cv_subsystem", "client_type", "day_camera", "heat_camera", "gps", "compass", "lrf", "lrf_calib", "rotary", "osd", "ping", "noop", "frozen", "system", "cv", "day_cam_glass_heater", "lira")
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    IMPORTANT_FIELD_NUMBER: _ClassVar[int]
    FROM_CV_SUBSYSTEM_FIELD_NUMBER: _ClassVar[int]
    CLIENT_TYPE_FIELD_NUMBER: _ClassVar[int]
    DAY_CAMERA_FIELD_NUMBER: _ClassVar[int]
    HEAT_CAMERA_FIELD_NUMBER: _ClassVar[int]
    GPS_FIELD_NUMBER: _ClassVar[int]
    COMPASS_FIELD_NUMBER: _ClassVar[int]
    LRF_FIELD_NUMBER: _ClassVar[int]
    LRF_CALIB_FIELD_NUMBER: _ClassVar[int]
    ROTARY_FIELD_NUMBER: _ClassVar[int]
    OSD_FIELD_NUMBER: _ClassVar[int]
    PING_FIELD_NUMBER: _ClassVar[int]
    NOOP_FIELD_NUMBER: _ClassVar[int]
    FROZEN_FIELD_NUMBER: _ClassVar[int]
    SYSTEM_FIELD_NUMBER: _ClassVar[int]
    CV_FIELD_NUMBER: _ClassVar[int]
    DAY_CAM_GLASS_HEATER_FIELD_NUMBER: _ClassVar[int]
    LIRA_FIELD_NUMBER: _ClassVar[int]
    protocol_version: int
    session_id: int
    important: bool
    from_cv_subsystem: bool
    client_type: _jon_shared_data_types_pb2.JonGuiDataClientType
    day_camera: _jon_shared_cmd_day_camera_pb2.Root
    heat_camera: _jon_shared_cmd_heat_camera_pb2.Root
    gps: _jon_shared_cmd_gps_pb2.Root
    compass: _jon_shared_cmd_compass_pb2.Root
    lrf: _jon_shared_cmd_lrf_pb2.Root
    lrf_calib: _jon_shared_cmd_lrf_align_pb2.Root
    rotary: _jon_shared_cmd_rotary_pb2.Root
    osd: _jon_shared_cmd_osd_pb2.Root
    ping: Ping
    noop: Noop
    frozen: Frozen
    system: _jon_shared_cmd_system_pb2.Root
    cv: _jon_shared_cmd_cv_pb2.Root
    day_cam_glass_heater: _jon_shared_cmd_day_cam_glass_heater_pb2.Root
    lira: _jon_shared_cmd_lira_pb2.Root
    def __init__(self, protocol_version: _Optional[int] = ..., session_id: _Optional[int] = ..., important: bool = ..., from_cv_subsystem: bool = ..., client_type: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataClientType, str]] = ..., day_camera: _Optional[_Union[_jon_shared_cmd_day_camera_pb2.Root, _Mapping]] = ..., heat_camera: _Optional[_Union[_jon_shared_cmd_heat_camera_pb2.Root, _Mapping]] = ..., gps: _Optional[_Union[_jon_shared_cmd_gps_pb2.Root, _Mapping]] = ..., compass: _Optional[_Union[_jon_shared_cmd_compass_pb2.Root, _Mapping]] = ..., lrf: _Optional[_Union[_jon_shared_cmd_lrf_pb2.Root, _Mapping]] = ..., lrf_calib: _Optional[_Union[_jon_shared_cmd_lrf_align_pb2.Root, _Mapping]] = ..., rotary: _Optional[_Union[_jon_shared_cmd_rotary_pb2.Root, _Mapping]] = ..., osd: _Optional[_Union[_jon_shared_cmd_osd_pb2.Root, _Mapping]] = ..., ping: _Optional[_Union[Ping, _Mapping]] = ..., noop: _Optional[_Union[Noop, _Mapping]] = ..., frozen: _Optional[_Union[Frozen, _Mapping]] = ..., system: _Optional[_Union[_jon_shared_cmd_system_pb2.Root, _Mapping]] = ..., cv: _Optional[_Union[_jon_shared_cmd_cv_pb2.Root, _Mapping]] = ..., day_cam_glass_heater: _Optional[_Union[_jon_shared_cmd_day_cam_glass_heater_pb2.Root, _Mapping]] = ..., lira: _Optional[_Union[_jon_shared_cmd_lira_pb2.Root, _Mapping]] = ...) -> None: ...

class Ping(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Noop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Frozen(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
