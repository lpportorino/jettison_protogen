from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CANDirection(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAN_DIRECTION_UNSPECIFIED: _ClassVar[CANDirection]
    CAN_DIRECTION_TX: _ClassVar[CANDirection]
    CAN_DIRECTION_RX: _ClassVar[CANDirection]
    CAN_DIRECTION_UNKNOWN: _ClassVar[CANDirection]
CAN_DIRECTION_UNSPECIFIED: CANDirection
CAN_DIRECTION_TX: CANDirection
CAN_DIRECTION_RX: CANDirection
CAN_DIRECTION_UNKNOWN: CANDirection

class CANFrame(_message.Message):
    __slots__ = ("timestamp_us", "can_id", "is_rx", "is_fd", "data", "dir", "kernel_ns", "seq64", "drops")
    TIMESTAMP_US_FIELD_NUMBER: _ClassVar[int]
    CAN_ID_FIELD_NUMBER: _ClassVar[int]
    IS_RX_FIELD_NUMBER: _ClassVar[int]
    IS_FD_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    DIR_FIELD_NUMBER: _ClassVar[int]
    KERNEL_NS_FIELD_NUMBER: _ClassVar[int]
    SEQ64_FIELD_NUMBER: _ClassVar[int]
    DROPS_FIELD_NUMBER: _ClassVar[int]
    timestamp_us: int
    can_id: int
    is_rx: bool
    is_fd: bool
    data: bytes
    dir: CANDirection
    kernel_ns: int
    seq64: int
    drops: int
    def __init__(self, timestamp_us: _Optional[int] = ..., can_id: _Optional[int] = ..., is_rx: bool = ..., is_fd: bool = ..., data: _Optional[bytes] = ..., dir: _Optional[_Union[CANDirection, str]] = ..., kernel_ns: _Optional[int] = ..., seq64: _Optional[int] = ..., drops: _Optional[int] = ...) -> None: ...

class CANFrameBatch(_message.Message):
    __slots__ = ("frames",)
    FRAMES_FIELD_NUMBER: _ClassVar[int]
    frames: _containers.RepeatedCompositeFieldContainer[CANFrame]
    def __init__(self, frames: _Optional[_Iterable[_Union[CANFrame, _Mapping]]] = ...) -> None: ...

class CANStreamConnected(_message.Message):
    __slots__ = ("streams",)
    STREAMS_FIELD_NUMBER: _ClassVar[int]
    streams: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, streams: _Optional[_Iterable[str]] = ...) -> None: ...
