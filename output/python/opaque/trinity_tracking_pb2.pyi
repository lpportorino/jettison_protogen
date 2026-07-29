from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class TrinityRangeSource(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TRINITY_RANGE_SOURCE_UNSPECIFIED: _ClassVar[TrinityRangeSource]
    TRINITY_RANGE_SOURCE_BOARD_EXTENT: _ClassVar[TrinityRangeSource]
    TRINITY_RANGE_SOURCE_LRF: _ClassVar[TrinityRangeSource]
    TRINITY_RANGE_SOURCE_FUSED: _ClassVar[TrinityRangeSource]

class TrinityTrackingStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TRINITY_TRACKING_STATUS_UNSPECIFIED: _ClassVar[TrinityTrackingStatus]
    TRINITY_TRACKING_STATUS_LOCKED: _ClassVar[TrinityTrackingStatus]
    TRINITY_TRACKING_STATUS_SEARCHING: _ClassVar[TrinityTrackingStatus]
    TRINITY_TRACKING_STATUS_DEGRADED: _ClassVar[TrinityTrackingStatus]
    TRINITY_TRACKING_STATUS_BOARD_MISMATCH: _ClassVar[TrinityTrackingStatus]
    TRINITY_TRACKING_STATUS_IDLE: _ClassVar[TrinityTrackingStatus]
TRINITY_RANGE_SOURCE_UNSPECIFIED: TrinityRangeSource
TRINITY_RANGE_SOURCE_BOARD_EXTENT: TrinityRangeSource
TRINITY_RANGE_SOURCE_LRF: TrinityRangeSource
TRINITY_RANGE_SOURCE_FUSED: TrinityRangeSource
TRINITY_TRACKING_STATUS_UNSPECIFIED: TrinityTrackingStatus
TRINITY_TRACKING_STATUS_LOCKED: TrinityTrackingStatus
TRINITY_TRACKING_STATUS_SEARCHING: TrinityTrackingStatus
TRINITY_TRACKING_STATUS_DEGRADED: TrinityTrackingStatus
TRINITY_TRACKING_STATUS_BOARD_MISMATCH: TrinityTrackingStatus
TRINITY_TRACKING_STATUS_IDLE: TrinityTrackingStatus

class TrinityTracking(_message.Message):
    __slots__ = ("board_version", "capture_time_ns", "status", "position_x_m", "position_y_m", "position_z_m", "quat_w", "quat_x", "quat_y", "quat_z", "sigma_position_m", "sigma_range_m", "sigma_orientation_mrad", "ambiguity_resolved", "alternate", "range_source", "anchors_seen", "board_extent_px", "reprojection_rms_px")
    BOARD_VERSION_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_TIME_NS_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    POSITION_X_M_FIELD_NUMBER: _ClassVar[int]
    POSITION_Y_M_FIELD_NUMBER: _ClassVar[int]
    POSITION_Z_M_FIELD_NUMBER: _ClassVar[int]
    QUAT_W_FIELD_NUMBER: _ClassVar[int]
    QUAT_X_FIELD_NUMBER: _ClassVar[int]
    QUAT_Y_FIELD_NUMBER: _ClassVar[int]
    QUAT_Z_FIELD_NUMBER: _ClassVar[int]
    SIGMA_POSITION_M_FIELD_NUMBER: _ClassVar[int]
    SIGMA_RANGE_M_FIELD_NUMBER: _ClassVar[int]
    SIGMA_ORIENTATION_MRAD_FIELD_NUMBER: _ClassVar[int]
    AMBIGUITY_RESOLVED_FIELD_NUMBER: _ClassVar[int]
    ALTERNATE_FIELD_NUMBER: _ClassVar[int]
    RANGE_SOURCE_FIELD_NUMBER: _ClassVar[int]
    ANCHORS_SEEN_FIELD_NUMBER: _ClassVar[int]
    BOARD_EXTENT_PX_FIELD_NUMBER: _ClassVar[int]
    REPROJECTION_RMS_PX_FIELD_NUMBER: _ClassVar[int]
    board_version: TrinityBoardVersion
    capture_time_ns: int
    status: TrinityTrackingStatus
    position_x_m: float
    position_y_m: float
    position_z_m: float
    quat_w: float
    quat_x: float
    quat_y: float
    quat_z: float
    sigma_position_m: float
    sigma_range_m: float
    sigma_orientation_mrad: float
    ambiguity_resolved: bool
    alternate: TrinityAltPose
    range_source: TrinityRangeSource
    anchors_seen: int
    board_extent_px: float
    reprojection_rms_px: float
    def __init__(self, board_version: _Optional[_Union[TrinityBoardVersion, _Mapping]] = ..., capture_time_ns: _Optional[int] = ..., status: _Optional[_Union[TrinityTrackingStatus, str]] = ..., position_x_m: _Optional[float] = ..., position_y_m: _Optional[float] = ..., position_z_m: _Optional[float] = ..., quat_w: _Optional[float] = ..., quat_x: _Optional[float] = ..., quat_y: _Optional[float] = ..., quat_z: _Optional[float] = ..., sigma_position_m: _Optional[float] = ..., sigma_range_m: _Optional[float] = ..., sigma_orientation_mrad: _Optional[float] = ..., ambiguity_resolved: bool = ..., alternate: _Optional[_Union[TrinityAltPose, _Mapping]] = ..., range_source: _Optional[_Union[TrinityRangeSource, str]] = ..., anchors_seen: _Optional[int] = ..., board_extent_px: _Optional[float] = ..., reprojection_rms_px: _Optional[float] = ...) -> None: ...

class TrinityBoardVersion(_message.Message):
    __slots__ = ("family", "major", "minor", "geometry_sha256")
    FAMILY_FIELD_NUMBER: _ClassVar[int]
    MAJOR_FIELD_NUMBER: _ClassVar[int]
    MINOR_FIELD_NUMBER: _ClassVar[int]
    GEOMETRY_SHA256_FIELD_NUMBER: _ClassVar[int]
    family: str
    major: int
    minor: int
    geometry_sha256: str
    def __init__(self, family: _Optional[str] = ..., major: _Optional[int] = ..., minor: _Optional[int] = ..., geometry_sha256: _Optional[str] = ...) -> None: ...

class TrinityAltPose(_message.Message):
    __slots__ = ("position_x_m", "position_y_m", "position_z_m", "quat_w", "quat_x", "quat_y", "quat_z", "reprojection_rms_px")
    POSITION_X_M_FIELD_NUMBER: _ClassVar[int]
    POSITION_Y_M_FIELD_NUMBER: _ClassVar[int]
    POSITION_Z_M_FIELD_NUMBER: _ClassVar[int]
    QUAT_W_FIELD_NUMBER: _ClassVar[int]
    QUAT_X_FIELD_NUMBER: _ClassVar[int]
    QUAT_Y_FIELD_NUMBER: _ClassVar[int]
    QUAT_Z_FIELD_NUMBER: _ClassVar[int]
    REPROJECTION_RMS_PX_FIELD_NUMBER: _ClassVar[int]
    position_x_m: float
    position_y_m: float
    position_z_m: float
    quat_w: float
    quat_x: float
    quat_y: float
    quat_z: float
    reprojection_rms_px: float
    def __init__(self, position_x_m: _Optional[float] = ..., position_y_m: _Optional[float] = ..., position_z_m: _Optional[float] = ..., quat_w: _Optional[float] = ..., quat_x: _Optional[float] = ..., quat_y: _Optional[float] = ..., quat_z: _Optional[float] = ..., reprojection_rms_px: _Optional[float] = ...) -> None: ...
