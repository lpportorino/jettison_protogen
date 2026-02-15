from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class DetectionStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DETECTION_STATUS_UNSPECIFIED: _ClassVar[DetectionStatus]
    DETECTION_STATUS_OK: _ClassVar[DetectionStatus]
    DETECTION_STATUS_NOT_READY: _ClassVar[DetectionStatus]
    DETECTION_STATUS_IPC_TIMEOUT: _ClassVar[DetectionStatus]
    DETECTION_STATUS_INFER_FAILED: _ClassVar[DetectionStatus]
    DETECTION_STATUS_ERROR: _ClassVar[DetectionStatus]
DETECTION_STATUS_UNSPECIFIED: DetectionStatus
DETECTION_STATUS_OK: DetectionStatus
DETECTION_STATUS_NOT_READY: DetectionStatus
DETECTION_STATUS_IPC_TIMEOUT: DetectionStatus
DETECTION_STATUS_INFER_FAILED: DetectionStatus
DETECTION_STATUS_ERROR: DetectionStatus

class ObjectDetection(_message.Message):
    __slots__ = ("x1", "y1", "x2", "y2", "confidence", "class_id")
    X1_FIELD_NUMBER: _ClassVar[int]
    Y1_FIELD_NUMBER: _ClassVar[int]
    X2_FIELD_NUMBER: _ClassVar[int]
    Y2_FIELD_NUMBER: _ClassVar[int]
    CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    CLASS_ID_FIELD_NUMBER: _ClassVar[int]
    x1: float
    y1: float
    x2: float
    y2: float
    confidence: float
    class_id: int
    def __init__(self, x1: _Optional[float] = ..., y1: _Optional[float] = ..., x2: _Optional[float] = ..., y2: _Optional[float] = ..., confidence: _Optional[float] = ..., class_id: _Optional[int] = ...) -> None: ...

class DetectionConfig(_message.Message):
    __slots__ = ("confidence_threshold", "nms_iou_threshold")
    CONFIDENCE_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    NMS_IOU_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    confidence_threshold: float
    nms_iou_threshold: float
    def __init__(self, confidence_threshold: _Optional[float] = ..., nms_iou_threshold: _Optional[float] = ...) -> None: ...

class DetectionFrameMeta(_message.Message):
    __slots__ = ("pts_ns", "capture_time_ns", "generation", "width", "height")
    PTS_NS_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_TIME_NS_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    pts_ns: int
    capture_time_ns: int
    generation: int
    width: int
    height: int
    def __init__(self, pts_ns: _Optional[int] = ..., capture_time_ns: _Optional[int] = ..., generation: _Optional[int] = ..., width: _Optional[int] = ..., height: _Optional[int] = ...) -> None: ...
