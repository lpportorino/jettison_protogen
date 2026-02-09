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
import jon_shared_data_actual_space_time_pb2 as _jon_shared_data_actual_space_time_pb2
import jon_shared_data_power_pb2 as _jon_shared_data_power_pb2
import jon_shared_data_cv_pb2 as _jon_shared_data_cv_pb2
import jon_shared_data_pmu_pb2 as _jon_shared_data_pmu_pb2
import jon_shared_data_heater_pb2 as _jon_shared_data_heater_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGUIState(_message.Message):
    __slots__ = ("protocol_version", "system_monotonic_time_us", "state_source", "frame_pts_day_ns", "frame_pts_heat_ns", "frame_monotonic_day_us", "frame_monotonic_heat_us", "opaque_payloads", "system", "meteo_internal", "lrf", "time", "gps", "compass", "rotary", "camera_day", "camera_heat", "compass_calibration", "rec_osd", "actual_space_time", "power", "cv", "pmu", "heater")
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    SYSTEM_MONOTONIC_TIME_US_FIELD_NUMBER: _ClassVar[int]
    STATE_SOURCE_FIELD_NUMBER: _ClassVar[int]
    FRAME_PTS_DAY_NS_FIELD_NUMBER: _ClassVar[int]
    FRAME_PTS_HEAT_NS_FIELD_NUMBER: _ClassVar[int]
    FRAME_MONOTONIC_DAY_US_FIELD_NUMBER: _ClassVar[int]
    FRAME_MONOTONIC_HEAT_US_FIELD_NUMBER: _ClassVar[int]
    OPAQUE_PAYLOADS_FIELD_NUMBER: _ClassVar[int]
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
    ACTUAL_SPACE_TIME_FIELD_NUMBER: _ClassVar[int]
    POWER_FIELD_NUMBER: _ClassVar[int]
    CV_FIELD_NUMBER: _ClassVar[int]
    PMU_FIELD_NUMBER: _ClassVar[int]
    HEATER_FIELD_NUMBER: _ClassVar[int]
    protocol_version: int
    system_monotonic_time_us: int
    state_source: _jon_shared_data_types_pb2.JonGuiDataStateSource
    frame_pts_day_ns: int
    frame_pts_heat_ns: int
    frame_monotonic_day_us: int
    frame_monotonic_heat_us: int
    opaque_payloads: _containers.RepeatedCompositeFieldContainer[_jon_shared_data_types_pb2.JonOpaquePayload]
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
    actual_space_time: _jon_shared_data_actual_space_time_pb2.JonGuiDataActualSpaceTime
    power: _jon_shared_data_power_pb2.JonGuiDataPower
    cv: _jon_shared_data_cv_pb2.JonGuiDataCV
    pmu: _jon_shared_data_pmu_pb2.JonGuiDataPMU
    heater: _jon_shared_data_heater_pb2.JonGuiDataHeater
    def __init__(self, protocol_version: _Optional[int] = ..., system_monotonic_time_us: _Optional[int] = ..., state_source: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataStateSource, str]] = ..., frame_pts_day_ns: _Optional[int] = ..., frame_pts_heat_ns: _Optional[int] = ..., frame_monotonic_day_us: _Optional[int] = ..., frame_monotonic_heat_us: _Optional[int] = ..., opaque_payloads: _Optional[_Iterable[_Union[_jon_shared_data_types_pb2.JonOpaquePayload, _Mapping]]] = ..., system: _Optional[_Union[_jon_shared_data_system_pb2.JonGuiDataSystem, _Mapping]] = ..., meteo_internal: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataMeteo, _Mapping]] = ..., lrf: _Optional[_Union[_jon_shared_data_lrf_pb2.JonGuiDataLrf, _Mapping]] = ..., time: _Optional[_Union[_jon_shared_data_time_pb2.JonGuiDataTime, _Mapping]] = ..., gps: _Optional[_Union[_jon_shared_data_gps_pb2.JonGuiDataGps, _Mapping]] = ..., compass: _Optional[_Union[_jon_shared_data_compass_pb2.JonGuiDataCompass, _Mapping]] = ..., rotary: _Optional[_Union[_jon_shared_data_rotary_pb2.JonGuiDataRotary, _Mapping]] = ..., camera_day: _Optional[_Union[_jon_shared_data_camera_day_pb2.JonGuiDataCameraDay, _Mapping]] = ..., camera_heat: _Optional[_Union[_jon_shared_data_camera_heat_pb2.JonGuiDataCameraHeat, _Mapping]] = ..., compass_calibration: _Optional[_Union[_jon_shared_data_compass_calibration_pb2.JonGuiDataCompassCalibration, _Mapping]] = ..., rec_osd: _Optional[_Union[_jon_shared_data_rec_osd_pb2.JonGuiDataRecOsd, _Mapping]] = ..., actual_space_time: _Optional[_Union[_jon_shared_data_actual_space_time_pb2.JonGuiDataActualSpaceTime, _Mapping]] = ..., power: _Optional[_Union[_jon_shared_data_power_pb2.JonGuiDataPower, _Mapping]] = ..., cv: _Optional[_Union[_jon_shared_data_cv_pb2.JonGuiDataCV, _Mapping]] = ..., pmu: _Optional[_Union[_jon_shared_data_pmu_pb2.JonGuiDataPMU, _Mapping]] = ..., heater: _Optional[_Union[_jon_shared_data_heater_pb2.JonGuiDataHeater, _Mapping]] = ...) -> None: ...
