import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
import jon_shared_data_time_pb2 as _jon_shared_data_time_pb2
import jon_shared_data_system_pb2 as _jon_shared_data_system_pb2
import jon_shared_data_lrf_pb2 as _jon_shared_data_lrf_pb2
import jon_shared_data_gps_pb2 as _jon_shared_data_gps_pb2
import jon_shared_data_compass_pb2 as _jon_shared_data_compass_pb2
import jon_shared_data_compass_calibration_pb2 as _jon_shared_data_compass_calibration_pb2
import jon_shared_data_rotary_pb2 as _jon_shared_data_rotary_pb2
import jon_shared_data_camera_day_pb2 as _jon_shared_data_camera_day_pb2
import jon_shared_data_camera_heat_pb2 as _jon_shared_data_camera_heat_pb2
import jon_shared_data_rec_osd_pb2 as _jon_shared_data_rec_osd_pb2
import jon_shared_data_day_cam_glass_heater_pb2 as _jon_shared_data_day_cam_glass_heater_pb2
import jon_shared_data_actual_space_time_pb2 as _jon_shared_data_actual_space_time_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGUIState(_message.Message):
    __slots__ = ("protocol_version", "system_monotonic_time_us", "system", "meteo_internal", "lrf", "time", "gps", "compass", "rotary", "camera_day", "camera_heat", "compass_calibration", "rec_osd", "day_cam_glass_heater", "actual_space_time")
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    SYSTEM_MONOTONIC_TIME_US_FIELD_NUMBER: _ClassVar[int]
    SYSTEM_FIELD_NUMBER: _ClassVar[int]
    METEO_INTERNAL_FIELD_NUMBER: _ClassVar[int]
    LRF_FIELD_NUMBER: _ClassVar[int]
    TIME_FIELD_NUMBER: _ClassVar[int]
    GPS_FIELD_NUMBER: _ClassVar[int]
    COMPASS_FIELD_NUMBER: _ClassVar[int]
    ROTARY_FIELD_NUMBER: _ClassVar[int]
    CAMERA_DAY_FIELD_NUMBER: _ClassVar[int]
    CAMERA_HEAT_FIELD_NUMBER: _ClassVar[int]
    COMPASS_CALIBRATION_FIELD_NUMBER: _ClassVar[int]
    REC_OSD_FIELD_NUMBER: _ClassVar[int]
    DAY_CAM_GLASS_HEATER_FIELD_NUMBER: _ClassVar[int]
    ACTUAL_SPACE_TIME_FIELD_NUMBER: _ClassVar[int]
    protocol_version: int
    system_monotonic_time_us: int
    system: _jon_shared_data_system_pb2.JonGuiDataSystem
    meteo_internal: _jon_shared_data_types_pb2.JonGuiDataMeteo
    lrf: _jon_shared_data_lrf_pb2.JonGuiDataLrf
    time: _jon_shared_data_time_pb2.JonGuiDataTime
    gps: _jon_shared_data_gps_pb2.JonGuiDataGps
    compass: _jon_shared_data_compass_pb2.JonGuiDataCompass
    rotary: _jon_shared_data_rotary_pb2.JonGuiDataRotary
    camera_day: _jon_shared_data_camera_day_pb2.JonGuiDataCameraDay
    camera_heat: _jon_shared_data_camera_heat_pb2.JonGuiDataCameraHeat
    compass_calibration: _jon_shared_data_compass_calibration_pb2.JonGuiDataCompassCalibration
    rec_osd: _jon_shared_data_rec_osd_pb2.JonGuiDataRecOsd
    day_cam_glass_heater: _jon_shared_data_day_cam_glass_heater_pb2.JonGuiDataDayCamGlassHeater
    actual_space_time: _jon_shared_data_actual_space_time_pb2.JonGuiDataActualSpaceTime
    def __init__(self, protocol_version: _Optional[int] = ..., system_monotonic_time_us: _Optional[int] = ..., system: _Optional[_Union[_jon_shared_data_system_pb2.JonGuiDataSystem, _Mapping]] = ..., meteo_internal: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataMeteo, _Mapping]] = ..., lrf: _Optional[_Union[_jon_shared_data_lrf_pb2.JonGuiDataLrf, _Mapping]] = ..., time: _Optional[_Union[_jon_shared_data_time_pb2.JonGuiDataTime, _Mapping]] = ..., gps: _Optional[_Union[_jon_shared_data_gps_pb2.JonGuiDataGps, _Mapping]] = ..., compass: _Optional[_Union[_jon_shared_data_compass_pb2.JonGuiDataCompass, _Mapping]] = ..., rotary: _Optional[_Union[_jon_shared_data_rotary_pb2.JonGuiDataRotary, _Mapping]] = ..., camera_day: _Optional[_Union[_jon_shared_data_camera_day_pb2.JonGuiDataCameraDay, _Mapping]] = ..., camera_heat: _Optional[_Union[_jon_shared_data_camera_heat_pb2.JonGuiDataCameraHeat, _Mapping]] = ..., compass_calibration: _Optional[_Union[_jon_shared_data_compass_calibration_pb2.JonGuiDataCompassCalibration, _Mapping]] = ..., rec_osd: _Optional[_Union[_jon_shared_data_rec_osd_pb2.JonGuiDataRecOsd, _Mapping]] = ..., day_cam_glass_heater: _Optional[_Union[_jon_shared_data_day_cam_glass_heater_pb2.JonGuiDataDayCamGlassHeater, _Mapping]] = ..., actual_space_time: _Optional[_Union[_jon_shared_data_actual_space_time_pb2.JonGuiDataActualSpaceTime, _Mapping]] = ...) -> None: ...
