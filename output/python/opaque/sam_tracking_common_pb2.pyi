from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class SamTrackingState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SAM_TRACKING_STATE_UNSPECIFIED: _ClassVar[SamTrackingState]
    SAM_TRACKING_STATE_IDLE: _ClassVar[SamTrackingState]
    SAM_TRACKING_STATE_STARTING: _ClassVar[SamTrackingState]
    SAM_TRACKING_STATE_TRACKING: _ClassVar[SamTrackingState]
    SAM_TRACKING_STATE_OCCLUDED: _ClassVar[SamTrackingState]
    SAM_TRACKING_STATE_LOST: _ClassVar[SamTrackingState]

class SamTrackingStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SAM_TRACKING_STATUS_UNSPECIFIED: _ClassVar[SamTrackingStatus]
    SAM_TRACKING_STATUS_OK: _ClassVar[SamTrackingStatus]
    SAM_TRACKING_STATUS_NOT_READY: _ClassVar[SamTrackingStatus]
    SAM_TRACKING_STATUS_NOT_STARTED: _ClassVar[SamTrackingStatus]
    SAM_TRACKING_STATUS_IPC_TIMEOUT: _ClassVar[SamTrackingStatus]
    SAM_TRACKING_STATUS_INFER_FAILED: _ClassVar[SamTrackingStatus]
    SAM_TRACKING_STATUS_LOST: _ClassVar[SamTrackingStatus]
SAM_TRACKING_STATE_UNSPECIFIED: SamTrackingState
SAM_TRACKING_STATE_IDLE: SamTrackingState
SAM_TRACKING_STATE_STARTING: SamTrackingState
SAM_TRACKING_STATE_TRACKING: SamTrackingState
SAM_TRACKING_STATE_OCCLUDED: SamTrackingState
SAM_TRACKING_STATE_LOST: SamTrackingState
SAM_TRACKING_STATUS_UNSPECIFIED: SamTrackingStatus
SAM_TRACKING_STATUS_OK: SamTrackingStatus
SAM_TRACKING_STATUS_NOT_READY: SamTrackingStatus
SAM_TRACKING_STATUS_NOT_STARTED: SamTrackingStatus
SAM_TRACKING_STATUS_IPC_TIMEOUT: SamTrackingStatus
SAM_TRACKING_STATUS_INFER_FAILED: SamTrackingStatus
SAM_TRACKING_STATUS_LOST: SamTrackingStatus

class SamTrackingFrameMeta(_message.Message):
    __slots__ = ("pts_ns", "capture_time_ns", "generation", "capture_monotonic_us")
    PTS_NS_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_TIME_NS_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_MONOTONIC_US_FIELD_NUMBER: _ClassVar[int]
    pts_ns: int
    capture_time_ns: int
    generation: int
    capture_monotonic_us: int
    def __init__(self, pts_ns: _Optional[int] = ..., capture_time_ns: _Optional[int] = ..., generation: _Optional[int] = ..., capture_monotonic_us: _Optional[int] = ...) -> None: ...

class SamTrackingKalmanState(_message.Message):
    __slots__ = ("predicted_x", "predicted_y", "velocity_x", "velocity_y")
    PREDICTED_X_FIELD_NUMBER: _ClassVar[int]
    PREDICTED_Y_FIELD_NUMBER: _ClassVar[int]
    VELOCITY_X_FIELD_NUMBER: _ClassVar[int]
    VELOCITY_Y_FIELD_NUMBER: _ClassVar[int]
    predicted_x: float
    predicted_y: float
    velocity_x: float
    velocity_y: float
    def __init__(self, predicted_x: _Optional[float] = ..., predicted_y: _Optional[float] = ..., velocity_x: _Optional[float] = ..., velocity_y: _Optional[float] = ...) -> None: ...
