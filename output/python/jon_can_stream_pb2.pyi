from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CANFrame(_message.Message):
    __slots__ = ("timestamp_us", "can_id", "is_rx", "is_fd", "data")
    TIMESTAMP_US_FIELD_NUMBER: _ClassVar[int]
    CAN_ID_FIELD_NUMBER: _ClassVar[int]
    IS_RX_FIELD_NUMBER: _ClassVar[int]
    IS_FD_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    timestamp_us: int
    can_id: int
    is_rx: bool
    is_fd: bool
    data: bytes
    def __init__(self, timestamp_us: _Optional[int] = ..., can_id: _Optional[int] = ..., is_rx: bool = ..., is_fd: bool = ..., data: _Optional[bytes] = ...) -> None: ...

class CANFrameBatch(_message.Message):
    __slots__ = ("frames",)
    FRAMES_FIELD_NUMBER: _ClassVar[int]
    frames: _containers.RepeatedCompositeFieldContainer[CANFrame]
    def __init__(self, frames: _Optional[_Iterable[_Union[CANFrame, _Mapping]]] = ...) -> None: ...
