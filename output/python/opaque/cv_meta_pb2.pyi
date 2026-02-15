import jon_shared_data_camera_day_pb2 as _jon_shared_data_camera_day_pb2
import jon_shared_data_camera_heat_pb2 as _jon_shared_data_camera_heat_pb2
import jon_shared_data_rotary_pb2 as _jon_shared_data_rotary_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CvChannelMeta(_message.Message):
    __slots__ = ("pts_ns", "capture_time_ns", "generation", "sharpness_level0", "sharpness_level1", "sharpness_level2", "sharpness_level3", "sharpness_compute_ns", "sharpness_total_ns", "sharpness_valid", "sensor_gain", "gain_valid", "sensor_exposure", "exposure_valid")
    PTS_NS_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_TIME_NS_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_LEVEL0_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_LEVEL1_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_LEVEL2_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_LEVEL3_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_COMPUTE_NS_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_TOTAL_NS_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_VALID_FIELD_NUMBER: _ClassVar[int]
    SENSOR_GAIN_FIELD_NUMBER: _ClassVar[int]
    GAIN_VALID_FIELD_NUMBER: _ClassVar[int]
    SENSOR_EXPOSURE_FIELD_NUMBER: _ClassVar[int]
    EXPOSURE_VALID_FIELD_NUMBER: _ClassVar[int]
    pts_ns: int
    capture_time_ns: int
    generation: int
    sharpness_level0: float
    sharpness_level1: _containers.RepeatedScalarFieldContainer[float]
    sharpness_level2: _containers.RepeatedScalarFieldContainer[float]
    sharpness_level3: _containers.RepeatedScalarFieldContainer[float]
    sharpness_compute_ns: int
    sharpness_total_ns: int
    sharpness_valid: bool
    sensor_gain: int
    gain_valid: bool
    sensor_exposure: int
    exposure_valid: bool
    def __init__(self, pts_ns: _Optional[int] = ..., capture_time_ns: _Optional[int] = ..., generation: _Optional[int] = ..., sharpness_level0: _Optional[float] = ..., sharpness_level1: _Optional[_Iterable[float]] = ..., sharpness_level2: _Optional[_Iterable[float]] = ..., sharpness_level3: _Optional[_Iterable[float]] = ..., sharpness_compute_ns: _Optional[int] = ..., sharpness_total_ns: _Optional[int] = ..., sharpness_valid: bool = ..., sensor_gain: _Optional[int] = ..., gain_valid: bool = ..., sensor_exposure: _Optional[int] = ..., exposure_valid: bool = ...) -> None: ...

class CvMeta(_message.Message):
    __slots__ = ("capture_monotonic_us", "updated_sources", "camera_day", "camera_heat", "rotary", "channel_day", "channel_heat")
    CAPTURE_MONOTONIC_US_FIELD_NUMBER: _ClassVar[int]
    UPDATED_SOURCES_FIELD_NUMBER: _ClassVar[int]
    CAMERA_DAY_FIELD_NUMBER: _ClassVar[int]
    CAMERA_HEAT_FIELD_NUMBER: _ClassVar[int]
    ROTARY_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_DAY_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_HEAT_FIELD_NUMBER: _ClassVar[int]
    capture_monotonic_us: int
    updated_sources: int
    camera_day: _jon_shared_data_camera_day_pb2.JonGuiDataCameraDay
    camera_heat: _jon_shared_data_camera_heat_pb2.JonGuiDataCameraHeat
    rotary: _jon_shared_data_rotary_pb2.JonGuiDataRotary
    channel_day: CvChannelMeta
    channel_heat: CvChannelMeta
    def __init__(self, capture_monotonic_us: _Optional[int] = ..., updated_sources: _Optional[int] = ..., camera_day: _Optional[_Union[_jon_shared_data_camera_day_pb2.JonGuiDataCameraDay, _Mapping]] = ..., camera_heat: _Optional[_Union[_jon_shared_data_camera_heat_pb2.JonGuiDataCameraHeat, _Mapping]] = ..., rotary: _Optional[_Union[_jon_shared_data_rotary_pb2.JonGuiDataRotary, _Mapping]] = ..., channel_day: _Optional[_Union[CvChannelMeta, _Mapping]] = ..., channel_heat: _Optional[_Union[CvChannelMeta, _Mapping]] = ...) -> None: ...
