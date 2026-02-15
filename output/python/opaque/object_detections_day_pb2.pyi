from opaque import detection_common_pb2 as _detection_common_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ObjectDetectionsDay(_message.Message):
    __slots__ = ("status", "detections", "latency_ns", "frame", "config", "capture_monotonic_us")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    DETECTIONS_FIELD_NUMBER: _ClassVar[int]
    LATENCY_NS_FIELD_NUMBER: _ClassVar[int]
    FRAME_FIELD_NUMBER: _ClassVar[int]
    CONFIG_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_MONOTONIC_US_FIELD_NUMBER: _ClassVar[int]
    status: _detection_common_pb2.DetectionStatus
    detections: _containers.RepeatedCompositeFieldContainer[_detection_common_pb2.ObjectDetection]
    latency_ns: int
    frame: _detection_common_pb2.DetectionFrameMeta
    config: _detection_common_pb2.DetectionConfig
    capture_monotonic_us: int
    def __init__(self, status: _Optional[_Union[_detection_common_pb2.DetectionStatus, str]] = ..., detections: _Optional[_Iterable[_Union[_detection_common_pb2.ObjectDetection, _Mapping]]] = ..., latency_ns: _Optional[int] = ..., frame: _Optional[_Union[_detection_common_pb2.DetectionFrameMeta, _Mapping]] = ..., config: _Optional[_Union[_detection_common_pb2.DetectionConfig, _Mapping]] = ..., capture_monotonic_us: _Optional[int] = ...) -> None: ...
