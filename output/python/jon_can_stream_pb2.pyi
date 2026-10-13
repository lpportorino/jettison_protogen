from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CANDirection(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAN_DIRECTION_UNSPECIFIED: _ClassVar[CANDirection]
    CAN_DIRECTION_TX: _ClassVar[CANDirection]
    CAN_DIRECTION_RX: _ClassVar[CANDirection]
    CAN_DIRECTION_UNKNOWN: _ClassVar[CANDirection]

class BusOrigin(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BUS_ORIGIN_UNSPECIFIED: _ClassVar[BusOrigin]
    BUS_ORIGIN_BUS: _ClassVar[BusOrigin]
    BUS_ORIGIN_ORIN: _ClassVar[BusOrigin]
    BUS_ORIGIN_CARAMBOLA: _ClassVar[BusOrigin]
    BUS_ORIGIN_LOCAL: _ClassVar[BusOrigin]
CAN_DIRECTION_UNSPECIFIED: CANDirection
CAN_DIRECTION_TX: CANDirection
CAN_DIRECTION_RX: CANDirection
CAN_DIRECTION_UNKNOWN: CANDirection
BUS_ORIGIN_UNSPECIFIED: BusOrigin
BUS_ORIGIN_BUS: BusOrigin
BUS_ORIGIN_ORIN: BusOrigin
BUS_ORIGIN_CARAMBOLA: BusOrigin
BUS_ORIGIN_LOCAL: BusOrigin

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
    def __init__(self, timestamp_us: _Optional[int] = ..., can_id: _Optional[int] = ..., is_rx: _Optional[bool] = ..., is_fd: _Optional[bool] = ..., data: _Optional[bytes] = ..., dir: _Optional[_Union[CANDirection, str]] = ..., kernel_ns: _Optional[int] = ..., seq64: _Optional[int] = ..., drops: _Optional[int] = ...) -> None: ...

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

class BusRefusal(_message.Message):
    __slots__ = ("code", "detail")
    CODE_FIELD_NUMBER: _ClassVar[int]
    DETAIL_FIELD_NUMBER: _ClassVar[int]
    code: str
    detail: str
    def __init__(self, code: _Optional[str] = ..., detail: _Optional[str] = ...) -> None: ...

class BusRecord(_message.Message):
    __slots__ = ("n", "title", "kind", "dir", "node", "module", "topic", "family", "command", "message", "decoded_json", "refusal", "ts_ns", "origin", "hop_seq", "sender", "sender_module", "can_id", "fflags", "frame_flags", "data", "payload", "prio", "type", "seq", "frag_total", "tx_time_ns", "ts_valid", "stream", "entry_id", "time_ms")
    N_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    DIR_FIELD_NUMBER: _ClassVar[int]
    NODE_FIELD_NUMBER: _ClassVar[int]
    MODULE_FIELD_NUMBER: _ClassVar[int]
    TOPIC_FIELD_NUMBER: _ClassVar[int]
    FAMILY_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    DECODED_JSON_FIELD_NUMBER: _ClassVar[int]
    REFUSAL_FIELD_NUMBER: _ClassVar[int]
    TS_NS_FIELD_NUMBER: _ClassVar[int]
    ORIGIN_FIELD_NUMBER: _ClassVar[int]
    HOP_SEQ_FIELD_NUMBER: _ClassVar[int]
    SENDER_FIELD_NUMBER: _ClassVar[int]
    SENDER_MODULE_FIELD_NUMBER: _ClassVar[int]
    CAN_ID_FIELD_NUMBER: _ClassVar[int]
    FFLAGS_FIELD_NUMBER: _ClassVar[int]
    FRAME_FLAGS_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    PRIO_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    SEQ_FIELD_NUMBER: _ClassVar[int]
    FRAG_TOTAL_FIELD_NUMBER: _ClassVar[int]
    TX_TIME_NS_FIELD_NUMBER: _ClassVar[int]
    TS_VALID_FIELD_NUMBER: _ClassVar[int]
    STREAM_FIELD_NUMBER: _ClassVar[int]
    ENTRY_ID_FIELD_NUMBER: _ClassVar[int]
    TIME_MS_FIELD_NUMBER: _ClassVar[int]
    n: int
    title: str
    kind: str
    dir: str
    node: int
    module: str
    topic: int
    family: str
    command: str
    message: str
    decoded_json: str
    refusal: BusRefusal
    ts_ns: int
    origin: BusOrigin
    hop_seq: int
    sender: int
    sender_module: str
    can_id: int
    fflags: int
    frame_flags: str
    data: bytes
    payload: bytes
    prio: int
    type: int
    seq: int
    frag_total: int
    tx_time_ns: int
    ts_valid: bool
    stream: str
    entry_id: str
    time_ms: int
    def __init__(self, n: _Optional[int] = ..., title: _Optional[str] = ..., kind: _Optional[str] = ..., dir: _Optional[str] = ..., node: _Optional[int] = ..., module: _Optional[str] = ..., topic: _Optional[int] = ..., family: _Optional[str] = ..., command: _Optional[str] = ..., message: _Optional[str] = ..., decoded_json: _Optional[str] = ..., refusal: _Optional[_Union[BusRefusal, _Mapping]] = ..., ts_ns: _Optional[int] = ..., origin: _Optional[_Union[BusOrigin, str]] = ..., hop_seq: _Optional[int] = ..., sender: _Optional[int] = ..., sender_module: _Optional[str] = ..., can_id: _Optional[int] = ..., fflags: _Optional[int] = ..., frame_flags: _Optional[str] = ..., data: _Optional[bytes] = ..., payload: _Optional[bytes] = ..., prio: _Optional[int] = ..., type: _Optional[int] = ..., seq: _Optional[int] = ..., frag_total: _Optional[int] = ..., tx_time_ns: _Optional[int] = ..., ts_valid: _Optional[bool] = ..., stream: _Optional[str] = ..., entry_id: _Optional[str] = ..., time_ms: _Optional[int] = ...) -> None: ...

class BusStreamStats(_message.Message):
    __slots__ = ("records", "messages", "refused", "refused_by", "absorbed", "dropped", "hop_gaps", "senders", "streams", "source_connected")
    class RefusedByEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: int
        def __init__(self, key: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...
    class DroppedEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: int
        def __init__(self, key: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...
    RECORDS_FIELD_NUMBER: _ClassVar[int]
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    REFUSED_FIELD_NUMBER: _ClassVar[int]
    REFUSED_BY_FIELD_NUMBER: _ClassVar[int]
    ABSORBED_FIELD_NUMBER: _ClassVar[int]
    DROPPED_FIELD_NUMBER: _ClassVar[int]
    HOP_GAPS_FIELD_NUMBER: _ClassVar[int]
    SENDERS_FIELD_NUMBER: _ClassVar[int]
    STREAMS_FIELD_NUMBER: _ClassVar[int]
    SOURCE_CONNECTED_FIELD_NUMBER: _ClassVar[int]
    records: int
    messages: int
    refused: int
    refused_by: _containers.ScalarMap[str, int]
    absorbed: int
    dropped: _containers.ScalarMap[str, int]
    hop_gaps: int
    senders: int
    streams: int
    source_connected: bool
    def __init__(self, records: _Optional[int] = ..., messages: _Optional[int] = ..., refused: _Optional[int] = ..., refused_by: _Optional[_Mapping[str, int]] = ..., absorbed: _Optional[int] = ..., dropped: _Optional[_Mapping[str, int]] = ..., hop_gaps: _Optional[int] = ..., senders: _Optional[int] = ..., streams: _Optional[int] = ..., source_connected: _Optional[bool] = ...) -> None: ...

class BusRecordBatch(_message.Message):
    __slots__ = ("records", "stats", "evicted")
    RECORDS_FIELD_NUMBER: _ClassVar[int]
    STATS_FIELD_NUMBER: _ClassVar[int]
    EVICTED_FIELD_NUMBER: _ClassVar[int]
    records: _containers.RepeatedCompositeFieldContainer[BusRecord]
    stats: BusStreamStats
    evicted: int
    def __init__(self, records: _Optional[_Iterable[_Union[BusRecord, _Mapping]]] = ..., stats: _Optional[_Union[BusStreamStats, _Mapping]] = ..., evicted: _Optional[int] = ...) -> None: ...

class BusStreamConnected(_message.Message):
    __slots__ = ("streams",)
    STREAMS_FIELD_NUMBER: _ClassVar[int]
    streams: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, streams: _Optional[_Iterable[str]] = ...) -> None: ...

class BusStreamMessage(_message.Message):
    __slots__ = ("connected", "batch")
    CONNECTED_FIELD_NUMBER: _ClassVar[int]
    BATCH_FIELD_NUMBER: _ClassVar[int]
    connected: BusStreamConnected
    batch: BusRecordBatch
    def __init__(self, connected: _Optional[_Union[BusStreamConnected, _Mapping]] = ..., batch: _Optional[_Union[BusRecordBatch, _Mapping]] = ...) -> None: ...
