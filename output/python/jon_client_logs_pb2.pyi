from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ClientLogEntry(_message.Message):
    __slots__ = ("lvl", "mod", "msg", "ts", "file", "line", "sid", "ua", "url", "origin", "commit", "build", "sw", "sh", "dpr", "lang", "tz", "extra", "state_snapshot")
    LVL_FIELD_NUMBER: _ClassVar[int]
    MOD_FIELD_NUMBER: _ClassVar[int]
    MSG_FIELD_NUMBER: _ClassVar[int]
    TS_FIELD_NUMBER: _ClassVar[int]
    FILE_FIELD_NUMBER: _ClassVar[int]
    LINE_FIELD_NUMBER: _ClassVar[int]
    SID_FIELD_NUMBER: _ClassVar[int]
    UA_FIELD_NUMBER: _ClassVar[int]
    URL_FIELD_NUMBER: _ClassVar[int]
    ORIGIN_FIELD_NUMBER: _ClassVar[int]
    COMMIT_FIELD_NUMBER: _ClassVar[int]
    BUILD_FIELD_NUMBER: _ClassVar[int]
    SW_FIELD_NUMBER: _ClassVar[int]
    SH_FIELD_NUMBER: _ClassVar[int]
    DPR_FIELD_NUMBER: _ClassVar[int]
    LANG_FIELD_NUMBER: _ClassVar[int]
    TZ_FIELD_NUMBER: _ClassVar[int]
    EXTRA_FIELD_NUMBER: _ClassVar[int]
    STATE_SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    lvl: str
    mod: str
    msg: str
    ts: int
    file: str
    line: int
    sid: str
    ua: str
    url: str
    origin: str
    commit: str
    build: str
    sw: int
    sh: int
    dpr: float
    lang: str
    tz: str
    extra: str
    state_snapshot: bytes
    def __init__(self, lvl: _Optional[str] = ..., mod: _Optional[str] = ..., msg: _Optional[str] = ..., ts: _Optional[int] = ..., file: _Optional[str] = ..., line: _Optional[int] = ..., sid: _Optional[str] = ..., ua: _Optional[str] = ..., url: _Optional[str] = ..., origin: _Optional[str] = ..., commit: _Optional[str] = ..., build: _Optional[str] = ..., sw: _Optional[int] = ..., sh: _Optional[int] = ..., dpr: _Optional[float] = ..., lang: _Optional[str] = ..., tz: _Optional[str] = ..., extra: _Optional[str] = ..., state_snapshot: _Optional[bytes] = ...) -> None: ...

class ClientLogBatch(_message.Message):
    __slots__ = ("version", "entries")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    ENTRIES_FIELD_NUMBER: _ClassVar[int]
    version: int
    entries: _containers.RepeatedCompositeFieldContainer[ClientLogEntry]
    def __init__(self, version: _Optional[int] = ..., entries: _Optional[_Iterable[_Union[ClientLogEntry, _Mapping]]] = ...) -> None: ...
