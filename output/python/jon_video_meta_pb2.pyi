from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class VideoErrorType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    VIDEO_ERROR_TYPE_UNSPECIFIED: _ClassVar[VideoErrorType]
    VIDEO_ERROR_TYPE_FILE_NOT_FOUND: _ClassVar[VideoErrorType]
    VIDEO_ERROR_TYPE_EMPTY_FILE: _ClassVar[VideoErrorType]
    VIDEO_ERROR_TYPE_NO_MOOV: _ClassVar[VideoErrorType]
    VIDEO_ERROR_TYPE_INVALID_MOOV: _ClassVar[VideoErrorType]
    VIDEO_ERROR_TYPE_TRUNCATED: _ClassVar[VideoErrorType]
VIDEO_ERROR_TYPE_UNSPECIFIED: VideoErrorType
VIDEO_ERROR_TYPE_FILE_NOT_FOUND: VideoErrorType
VIDEO_ERROR_TYPE_EMPTY_FILE: VideoErrorType
VIDEO_ERROR_TYPE_NO_MOOV: VideoErrorType
VIDEO_ERROR_TYPE_INVALID_MOOV: VideoErrorType
VIDEO_ERROR_TYPE_TRUNCATED: VideoErrorType

class VideoMetaRequest(_message.Message):
    __slots__ = ("range", "ids")
    RANGE_FIELD_NUMBER: _ClassVar[int]
    IDS_FIELD_NUMBER: _ClassVar[int]
    range: VideoRangeQuery
    ids: VideoIdList
    def __init__(self, range: _Optional[_Union[VideoRangeQuery, _Mapping]] = ..., ids: _Optional[_Union[VideoIdList, _Mapping]] = ...) -> None: ...

class VideoIdList(_message.Message):
    __slots__ = ("uuids",)
    UUIDS_FIELD_NUMBER: _ClassVar[int]
    uuids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, uuids: _Optional[_Iterable[str]] = ...) -> None: ...

class VideoRangeQuery(_message.Message):
    __slots__ = ("start_timestamp", "end_timestamp", "source_type", "limit", "offset")
    START_TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    END_TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    SOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    start_timestamp: int
    end_timestamp: int
    source_type: str
    limit: int
    offset: int
    def __init__(self, start_timestamp: _Optional[int] = ..., end_timestamp: _Optional[int] = ..., source_type: _Optional[str] = ..., limit: _Optional[int] = ..., offset: _Optional[int] = ...) -> None: ...

class VideoMetaResponse(_message.Message):
    __slots__ = ("videos", "errors", "total_count", "width", "height", "dsi", "timescale")
    VIDEOS_FIELD_NUMBER: _ClassVar[int]
    ERRORS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_COUNT_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    DSI_FIELD_NUMBER: _ClassVar[int]
    TIMESCALE_FIELD_NUMBER: _ClassVar[int]
    videos: _containers.RepeatedCompositeFieldContainer[VideoMeta]
    errors: _containers.RepeatedCompositeFieldContainer[VideoError]
    total_count: int
    width: int
    height: int
    dsi: bytes
    timescale: int
    def __init__(self, videos: _Optional[_Iterable[_Union[VideoMeta, _Mapping]]] = ..., errors: _Optional[_Iterable[_Union[VideoError, _Mapping]]] = ..., total_count: _Optional[int] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., dsi: _Optional[bytes] = ..., timescale: _Optional[int] = ...) -> None: ...

class VideoMeta(_message.Message):
    __slots__ = ("uuid", "session_id", "timestamp", "storage_path", "source_type", "frame_count", "duration_ms", "sample_table")
    UUID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    STORAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    SOURCE_TYPE_FIELD_NUMBER: _ClassVar[int]
    FRAME_COUNT_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_TABLE_FIELD_NUMBER: _ClassVar[int]
    uuid: str
    session_id: int
    timestamp: int
    storage_path: str
    source_type: str
    frame_count: int
    duration_ms: int
    sample_table: SampleTable
    def __init__(self, uuid: _Optional[str] = ..., session_id: _Optional[int] = ..., timestamp: _Optional[int] = ..., storage_path: _Optional[str] = ..., source_type: _Optional[str] = ..., frame_count: _Optional[int] = ..., duration_ms: _Optional[int] = ..., sample_table: _Optional[_Union[SampleTable, _Mapping]] = ...) -> None: ...

class SampleTable(_message.Message):
    __slots__ = ("sample_sizes", "chunk_offsets", "sample_times", "sync_samples", "sample_to_chunk")
    SAMPLE_SIZES_FIELD_NUMBER: _ClassVar[int]
    CHUNK_OFFSETS_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_TIMES_FIELD_NUMBER: _ClassVar[int]
    SYNC_SAMPLES_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_TO_CHUNK_FIELD_NUMBER: _ClassVar[int]
    sample_sizes: _containers.RepeatedScalarFieldContainer[int]
    chunk_offsets: _containers.RepeatedScalarFieldContainer[int]
    sample_times: _containers.RepeatedScalarFieldContainer[int]
    sync_samples: _containers.RepeatedScalarFieldContainer[int]
    sample_to_chunk: _containers.RepeatedCompositeFieldContainer[SampleToChunk]
    def __init__(self, sample_sizes: _Optional[_Iterable[int]] = ..., chunk_offsets: _Optional[_Iterable[int]] = ..., sample_times: _Optional[_Iterable[int]] = ..., sync_samples: _Optional[_Iterable[int]] = ..., sample_to_chunk: _Optional[_Iterable[_Union[SampleToChunk, _Mapping]]] = ...) -> None: ...

class SampleToChunk(_message.Message):
    __slots__ = ("first_chunk", "samples_per_chunk", "sample_description_index")
    FIRST_CHUNK_FIELD_NUMBER: _ClassVar[int]
    SAMPLES_PER_CHUNK_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_DESCRIPTION_INDEX_FIELD_NUMBER: _ClassVar[int]
    first_chunk: int
    samples_per_chunk: int
    sample_description_index: int
    def __init__(self, first_chunk: _Optional[int] = ..., samples_per_chunk: _Optional[int] = ..., sample_description_index: _Optional[int] = ...) -> None: ...

class VideoError(_message.Message):
    __slots__ = ("uuid", "storage_path", "error_type", "error_message")
    UUID_FIELD_NUMBER: _ClassVar[int]
    STORAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    ERROR_TYPE_FIELD_NUMBER: _ClassVar[int]
    ERROR_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    uuid: str
    storage_path: str
    error_type: VideoErrorType
    error_message: str
    def __init__(self, uuid: _Optional[str] = ..., storage_path: _Optional[str] = ..., error_type: _Optional[_Union[VideoErrorType, str]] = ..., error_message: _Optional[str] = ...) -> None: ...
