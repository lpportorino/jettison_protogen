from opaque import sam_tracking_common_pb2 as _sam_tracking_common_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SamTrackingHeat(_message.Message):
    __slots__ = ("status", "state", "bbox_x1", "bbox_y1", "bbox_x2", "bbox_y2", "centroid_x", "centroid_y", "confidence", "iou", "mask_rle", "mask_width", "mask_height", "mask_pixels", "frame", "kalman", "lost_frame_count", "latency_ns")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    BBOX_X1_FIELD_NUMBER: _ClassVar[int]
    BBOX_Y1_FIELD_NUMBER: _ClassVar[int]
    BBOX_X2_FIELD_NUMBER: _ClassVar[int]
    BBOX_Y2_FIELD_NUMBER: _ClassVar[int]
    CENTROID_X_FIELD_NUMBER: _ClassVar[int]
    CENTROID_Y_FIELD_NUMBER: _ClassVar[int]
    CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    IOU_FIELD_NUMBER: _ClassVar[int]
    MASK_RLE_FIELD_NUMBER: _ClassVar[int]
    MASK_WIDTH_FIELD_NUMBER: _ClassVar[int]
    MASK_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    MASK_PIXELS_FIELD_NUMBER: _ClassVar[int]
    FRAME_FIELD_NUMBER: _ClassVar[int]
    KALMAN_FIELD_NUMBER: _ClassVar[int]
    LOST_FRAME_COUNT_FIELD_NUMBER: _ClassVar[int]
    LATENCY_NS_FIELD_NUMBER: _ClassVar[int]
    status: _sam_tracking_common_pb2.SamTrackingStatus
    state: _sam_tracking_common_pb2.SamTrackingState
    bbox_x1: float
    bbox_y1: float
    bbox_x2: float
    bbox_y2: float
    centroid_x: float
    centroid_y: float
    confidence: float
    iou: float
    mask_rle: bytes
    mask_width: int
    mask_height: int
    mask_pixels: int
    frame: _sam_tracking_common_pb2.SamTrackingFrameMeta
    kalman: _sam_tracking_common_pb2.SamTrackingKalmanState
    lost_frame_count: int
    latency_ns: int
    def __init__(self, status: _Optional[_Union[_sam_tracking_common_pb2.SamTrackingStatus, str]] = ..., state: _Optional[_Union[_sam_tracking_common_pb2.SamTrackingState, str]] = ..., bbox_x1: _Optional[float] = ..., bbox_y1: _Optional[float] = ..., bbox_x2: _Optional[float] = ..., bbox_y2: _Optional[float] = ..., centroid_x: _Optional[float] = ..., centroid_y: _Optional[float] = ..., confidence: _Optional[float] = ..., iou: _Optional[float] = ..., mask_rle: _Optional[bytes] = ..., mask_width: _Optional[int] = ..., mask_height: _Optional[int] = ..., mask_pixels: _Optional[int] = ..., frame: _Optional[_Union[_sam_tracking_common_pb2.SamTrackingFrameMeta, _Mapping]] = ..., kalman: _Optional[_Union[_sam_tracking_common_pb2.SamTrackingKalmanState, _Mapping]] = ..., lost_frame_count: _Optional[int] = ..., latency_ns: _Optional[int] = ...) -> None: ...
