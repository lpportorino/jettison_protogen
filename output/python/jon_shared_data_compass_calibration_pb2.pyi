import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataCompassCalibration(_message.Message):
    __slots__ = ("stage", "final_stage", "target_azimuth", "target_elevation", "target_bank", "status")
    STAGE_FIELD_NUMBER: _ClassVar[int]
    FINAL_STAGE_FIELD_NUMBER: _ClassVar[int]
    TARGET_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    TARGET_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    TARGET_BANK_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    stage: int
    final_stage: int
    target_azimuth: float
    target_elevation: float
    target_bank: float
    status: _jon_shared_data_types_pb2.JonGuiDataCompassCalibrateStatus
    def __init__(self, stage: _Optional[int] = ..., final_stage: _Optional[int] = ..., target_azimuth: _Optional[float] = ..., target_elevation: _Optional[float] = ..., target_bank: _Optional[float] = ..., status: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataCompassCalibrateStatus, str]] = ...) -> None: ...
