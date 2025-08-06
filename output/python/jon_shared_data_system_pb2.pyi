import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataSystem(_message.Message):
    __slots__ = ("cpu_temperature", "gpu_temperature", "gpu_load", "cpu_load", "power_consumption", "loc", "cur_video_rec_dir_year", "cur_video_rec_dir_month", "cur_video_rec_dir_day", "cur_video_rec_dir_hour", "cur_video_rec_dir_minute", "cur_video_rec_dir_second", "rec_enabled", "important_rec_enabled", "low_disk_space", "no_disk_space", "disk_space", "tracking", "vampire_mode", "stabilization_mode", "geodesic_mode", "cv_dumping", "recognition_mode")
    CPU_TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    GPU_TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    GPU_LOAD_FIELD_NUMBER: _ClassVar[int]
    CPU_LOAD_FIELD_NUMBER: _ClassVar[int]
    POWER_CONSUMPTION_FIELD_NUMBER: _ClassVar[int]
    LOC_FIELD_NUMBER: _ClassVar[int]
    CUR_VIDEO_REC_DIR_YEAR_FIELD_NUMBER: _ClassVar[int]
    CUR_VIDEO_REC_DIR_MONTH_FIELD_NUMBER: _ClassVar[int]
    CUR_VIDEO_REC_DIR_DAY_FIELD_NUMBER: _ClassVar[int]
    CUR_VIDEO_REC_DIR_HOUR_FIELD_NUMBER: _ClassVar[int]
    CUR_VIDEO_REC_DIR_MINUTE_FIELD_NUMBER: _ClassVar[int]
    CUR_VIDEO_REC_DIR_SECOND_FIELD_NUMBER: _ClassVar[int]
    REC_ENABLED_FIELD_NUMBER: _ClassVar[int]
    IMPORTANT_REC_ENABLED_FIELD_NUMBER: _ClassVar[int]
    LOW_DISK_SPACE_FIELD_NUMBER: _ClassVar[int]
    NO_DISK_SPACE_FIELD_NUMBER: _ClassVar[int]
    DISK_SPACE_FIELD_NUMBER: _ClassVar[int]
    TRACKING_FIELD_NUMBER: _ClassVar[int]
    VAMPIRE_MODE_FIELD_NUMBER: _ClassVar[int]
    STABILIZATION_MODE_FIELD_NUMBER: _ClassVar[int]
    GEODESIC_MODE_FIELD_NUMBER: _ClassVar[int]
    CV_DUMPING_FIELD_NUMBER: _ClassVar[int]
    RECOGNITION_MODE_FIELD_NUMBER: _ClassVar[int]
    cpu_temperature: float
    gpu_temperature: float
    gpu_load: float
    cpu_load: float
    power_consumption: float
    loc: _jon_shared_data_types_pb2.JonGuiDataSystemLocalizations
    cur_video_rec_dir_year: int
    cur_video_rec_dir_month: int
    cur_video_rec_dir_day: int
    cur_video_rec_dir_hour: int
    cur_video_rec_dir_minute: int
    cur_video_rec_dir_second: int
    rec_enabled: bool
    important_rec_enabled: bool
    low_disk_space: bool
    no_disk_space: bool
    disk_space: int
    tracking: bool
    vampire_mode: bool
    stabilization_mode: bool
    geodesic_mode: bool
    cv_dumping: bool
    recognition_mode: bool
    def __init__(self, cpu_temperature: _Optional[float] = ..., gpu_temperature: _Optional[float] = ..., gpu_load: _Optional[float] = ..., cpu_load: _Optional[float] = ..., power_consumption: _Optional[float] = ..., loc: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataSystemLocalizations, str]] = ..., cur_video_rec_dir_year: _Optional[int] = ..., cur_video_rec_dir_month: _Optional[int] = ..., cur_video_rec_dir_day: _Optional[int] = ..., cur_video_rec_dir_hour: _Optional[int] = ..., cur_video_rec_dir_minute: _Optional[int] = ..., cur_video_rec_dir_second: _Optional[int] = ..., rec_enabled: bool = ..., important_rec_enabled: bool = ..., low_disk_space: bool = ..., no_disk_space: bool = ..., disk_space: _Optional[int] = ..., tracking: bool = ..., vampire_mode: bool = ..., stabilization_mode: bool = ..., geodesic_mode: bool = ..., cv_dumping: bool = ..., recognition_mode: bool = ...) -> None: ...
