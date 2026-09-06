from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ArchiveFormatVersion(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ARCHIVE_FORMAT_VERSION_UNSPECIFIED: _ClassVar[ArchiveFormatVersion]
    ARCHIVE_FORMAT_VERSION_V3: _ClassVar[ArchiveFormatVersion]

class ArchiveCodec(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ARCHIVE_CODEC_UNSPECIFIED: _ClassVar[ArchiveCodec]
    ARCHIVE_CODEC_NONE: _ClassVar[ArchiveCodec]

class StreamKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    STREAM_KIND_UNSPECIFIED: _ClassVar[StreamKind]
    STREAM_KIND_CAN: _ClassVar[StreamKind]
    STREAM_KIND_ROTARY_UART: _ClassVar[StreamKind]
    STREAM_KIND_FRAME_TAP: _ClassVar[StreamKind]
    STREAM_KIND_TSDB_TABLE: _ClassVar[StreamKind]
    STREAM_KIND_MOTION_HISTORY: _ClassVar[StreamKind]

class ArchiveStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ARCHIVE_STATUS_UNSPECIFIED: _ClassVar[ArchiveStatus]
    ARCHIVE_STATUS_COMPLETE: _ClassVar[ArchiveStatus]
    ARCHIVE_STATUS_PARTIAL: _ClassVar[ArchiveStatus]
    ARCHIVE_STATUS_FAILED: _ClassVar[ArchiveStatus]

class SessionProvenance(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SESSION_PROVENANCE_UNSPECIFIED: _ClassVar[SessionProvenance]
    SESSION_PROVENANCE_EUTROPIA: _ClassVar[SessionProvenance]
    SESSION_PROVENANCE_SYNTHESIZED: _ClassVar[SessionProvenance]
ARCHIVE_FORMAT_VERSION_UNSPECIFIED: ArchiveFormatVersion
ARCHIVE_FORMAT_VERSION_V3: ArchiveFormatVersion
ARCHIVE_CODEC_UNSPECIFIED: ArchiveCodec
ARCHIVE_CODEC_NONE: ArchiveCodec
STREAM_KIND_UNSPECIFIED: StreamKind
STREAM_KIND_CAN: StreamKind
STREAM_KIND_ROTARY_UART: StreamKind
STREAM_KIND_FRAME_TAP: StreamKind
STREAM_KIND_TSDB_TABLE: StreamKind
STREAM_KIND_MOTION_HISTORY: StreamKind
ARCHIVE_STATUS_UNSPECIFIED: ArchiveStatus
ARCHIVE_STATUS_COMPLETE: ArchiveStatus
ARCHIVE_STATUS_PARTIAL: ArchiveStatus
ARCHIVE_STATUS_FAILED: ArchiveStatus
SESSION_PROVENANCE_UNSPECIFIED: SessionProvenance
SESSION_PROVENANCE_EUTROPIA: SessionProvenance
SESSION_PROVENANCE_SYNTHESIZED: SessionProvenance

class CvDumpArchive(_message.Message):
    __slots__ = ("version", "id", "generated_at", "window", "provenance", "note", "machine", "integrity", "video", "streams", "shots")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    GENERATED_AT_FIELD_NUMBER: _ClassVar[int]
    WINDOW_FIELD_NUMBER: _ClassVar[int]
    PROVENANCE_FIELD_NUMBER: _ClassVar[int]
    NOTE_FIELD_NUMBER: _ClassVar[int]
    MACHINE_FIELD_NUMBER: _ClassVar[int]
    INTEGRITY_FIELD_NUMBER: _ClassVar[int]
    VIDEO_FIELD_NUMBER: _ClassVar[int]
    STREAMS_FIELD_NUMBER: _ClassVar[int]
    SHOTS_FIELD_NUMBER: _ClassVar[int]
    version: ArchiveFormatVersion
    id: str
    generated_at: str
    window: CaptureWindow
    provenance: SessionProvenance
    note: str
    machine: MachineIdentity
    integrity: IntegrityReport
    video: _containers.RepeatedCompositeFieldContainer[VideoChannel]
    streams: _containers.RepeatedCompositeFieldContainer[StreamGroup]
    shots: _containers.RepeatedCompositeFieldContainer[ShotCapture]
    def __init__(self, version: _Optional[_Union[ArchiveFormatVersion, str]] = ..., id: _Optional[str] = ..., generated_at: _Optional[str] = ..., window: _Optional[_Union[CaptureWindow, _Mapping]] = ..., provenance: _Optional[_Union[SessionProvenance, str]] = ..., note: _Optional[str] = ..., machine: _Optional[_Union[MachineIdentity, _Mapping]] = ..., integrity: _Optional[_Union[IntegrityReport, _Mapping]] = ..., video: _Optional[_Iterable[_Union[VideoChannel, _Mapping]]] = ..., streams: _Optional[_Iterable[_Union[StreamGroup, _Mapping]]] = ..., shots: _Optional[_Iterable[_Union[ShotCapture, _Mapping]]] = ...) -> None: ...

class ShotPlane(_message.Message):
    __slots__ = ("plane", "tag", "source_format", "path", "encoding", "width", "height", "pitch", "uv_offset", "bytes", "sha256")
    PLANE_FIELD_NUMBER: _ClassVar[int]
    TAG_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FORMAT_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    ENCODING_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    PITCH_FIELD_NUMBER: _ClassVar[int]
    UV_OFFSET_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    plane: int
    tag: int
    source_format: int
    path: str
    encoding: str
    width: int
    height: int
    pitch: int
    uv_offset: int
    bytes: int
    sha256: str
    def __init__(self, plane: _Optional[int] = ..., tag: _Optional[int] = ..., source_format: _Optional[int] = ..., path: _Optional[str] = ..., encoding: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., pitch: _Optional[int] = ..., uv_offset: _Optional[int] = ..., bytes: _Optional[int] = ..., sha256: _Optional[str] = ...) -> None: ...

class ShotCapture(_message.Message):
    __slots__ = ("channel", "generation", "pts_ns", "capture_time_ns", "ctl_snapshot", "planes", "absent_reason")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    GENERATION_FIELD_NUMBER: _ClassVar[int]
    PTS_NS_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_TIME_NS_FIELD_NUMBER: _ClassVar[int]
    CTL_SNAPSHOT_FIELD_NUMBER: _ClassVar[int]
    PLANES_FIELD_NUMBER: _ClassVar[int]
    ABSENT_REASON_FIELD_NUMBER: _ClassVar[int]
    channel: str
    generation: int
    pts_ns: int
    capture_time_ns: int
    ctl_snapshot: bytes
    planes: _containers.RepeatedCompositeFieldContainer[ShotPlane]
    absent_reason: str
    def __init__(self, channel: _Optional[str] = ..., generation: _Optional[int] = ..., pts_ns: _Optional[int] = ..., capture_time_ns: _Optional[int] = ..., ctl_snapshot: _Optional[bytes] = ..., planes: _Optional[_Iterable[_Union[ShotPlane, _Mapping]]] = ..., absent_reason: _Optional[str] = ...) -> None: ...

class CaptureWindow(_message.Message):
    __slots__ = ("t0_wall", "t1_wall", "t0_boot_ns", "t1_boot_ns")
    T0_WALL_FIELD_NUMBER: _ClassVar[int]
    T1_WALL_FIELD_NUMBER: _ClassVar[int]
    T0_BOOT_NS_FIELD_NUMBER: _ClassVar[int]
    T1_BOOT_NS_FIELD_NUMBER: _ClassVar[int]
    t0_wall: str
    t1_wall: str
    t0_boot_ns: int
    t1_boot_ns: int
    def __init__(self, t0_wall: _Optional[str] = ..., t1_wall: _Optional[str] = ..., t0_boot_ns: _Optional[int] = ..., t1_boot_ns: _Optional[int] = ...) -> None: ...

class MachineIdentity(_message.Message):
    __slots__ = ("machine_id", "hostname", "hw_model", "boot_id", "deploy", "channels_config", "machine_incomplete")
    MACHINE_ID_FIELD_NUMBER: _ClassVar[int]
    HOSTNAME_FIELD_NUMBER: _ClassVar[int]
    HW_MODEL_FIELD_NUMBER: _ClassVar[int]
    BOOT_ID_FIELD_NUMBER: _ClassVar[int]
    DEPLOY_FIELD_NUMBER: _ClassVar[int]
    CHANNELS_CONFIG_FIELD_NUMBER: _ClassVar[int]
    MACHINE_INCOMPLETE_FIELD_NUMBER: _ClassVar[int]
    machine_id: str
    hostname: str
    hw_model: str
    boot_id: str
    deploy: DeployFingerprint
    channels_config: _containers.RepeatedCompositeFieldContainer[ChannelConfig]
    machine_incomplete: bool
    def __init__(self, machine_id: _Optional[str] = ..., hostname: _Optional[str] = ..., hw_model: _Optional[str] = ..., boot_id: _Optional[str] = ..., deploy: _Optional[_Union[DeployFingerprint, _Mapping]] = ..., channels_config: _Optional[_Iterable[_Union[ChannelConfig, _Mapping]]] = ..., machine_incomplete: bool = ...) -> None: ...

class DeployFingerprint(_message.Message):
    __slots__ = ("fingerprint", "jettison_sha", "describe", "deployed_at")
    FINGERPRINT_FIELD_NUMBER: _ClassVar[int]
    JETTISON_SHA_FIELD_NUMBER: _ClassVar[int]
    DESCRIBE_FIELD_NUMBER: _ClassVar[int]
    DEPLOYED_AT_FIELD_NUMBER: _ClassVar[int]
    fingerprint: str
    jettison_sha: str
    describe: str
    deployed_at: str
    def __init__(self, fingerprint: _Optional[str] = ..., jettison_sha: _Optional[str] = ..., describe: _Optional[str] = ..., deployed_at: _Optional[str] = ...) -> None: ...

class ChannelConfig(_message.Message):
    __slots__ = ("channel", "width", "height", "fps_limit", "sensor_fps", "crop_margins")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    FPS_LIMIT_FIELD_NUMBER: _ClassVar[int]
    SENSOR_FPS_FIELD_NUMBER: _ClassVar[int]
    CROP_MARGINS_FIELD_NUMBER: _ClassVar[int]
    channel: str
    width: int
    height: int
    fps_limit: int
    sensor_fps: int
    crop_margins: int
    def __init__(self, channel: _Optional[str] = ..., width: _Optional[int] = ..., height: _Optional[int] = ..., fps_limit: _Optional[int] = ..., sensor_fps: _Optional[int] = ..., crop_margins: _Optional[int] = ...) -> None: ...

class IntegrityReport(_message.Message):
    __slots__ = ("status", "lapped_segments", "window_start_lost", "writer_seg_skipped", "rec_control_unknown", "rec_enable_failed", "no_window_video_channels", "truncated_sources", "telemetry_incomplete", "io_records_incomplete")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    LAPPED_SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    WINDOW_START_LOST_FIELD_NUMBER: _ClassVar[int]
    WRITER_SEG_SKIPPED_FIELD_NUMBER: _ClassVar[int]
    REC_CONTROL_UNKNOWN_FIELD_NUMBER: _ClassVar[int]
    REC_ENABLE_FAILED_FIELD_NUMBER: _ClassVar[int]
    NO_WINDOW_VIDEO_CHANNELS_FIELD_NUMBER: _ClassVar[int]
    TRUNCATED_SOURCES_FIELD_NUMBER: _ClassVar[int]
    TELEMETRY_INCOMPLETE_FIELD_NUMBER: _ClassVar[int]
    IO_RECORDS_INCOMPLETE_FIELD_NUMBER: _ClassVar[int]
    status: ArchiveStatus
    lapped_segments: int
    window_start_lost: bool
    writer_seg_skipped: bool
    rec_control_unknown: bool
    rec_enable_failed: bool
    no_window_video_channels: _containers.RepeatedScalarFieldContainer[str]
    truncated_sources: _containers.RepeatedScalarFieldContainer[str]
    telemetry_incomplete: bool
    io_records_incomplete: bool
    def __init__(self, status: _Optional[_Union[ArchiveStatus, str]] = ..., lapped_segments: _Optional[int] = ..., window_start_lost: bool = ..., writer_seg_skipped: bool = ..., rec_control_unknown: bool = ..., rec_enable_failed: bool = ..., no_window_video_channels: _Optional[_Iterable[str]] = ..., truncated_sources: _Optional[_Iterable[str]] = ..., telemetry_incomplete: bool = ..., io_records_incomplete: bool = ...) -> None: ...

class VideoChannel(_message.Message):
    __slots__ = ("channel", "segments")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    SEGMENTS_FIELD_NUMBER: _ClassVar[int]
    channel: str
    segments: _containers.RepeatedCompositeFieldContainer[VideoSegment]
    def __init__(self, channel: _Optional[str] = ..., segments: _Optional[_Iterable[_Union[VideoSegment, _Mapping]]] = ...) -> None: ...

class VideoSegment(_message.Message):
    __slots__ = ("sequence", "path", "bytes", "sha256", "start_ns", "end_ns", "open_end")
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    PATH_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    START_NS_FIELD_NUMBER: _ClassVar[int]
    END_NS_FIELD_NUMBER: _ClassVar[int]
    OPEN_END_FIELD_NUMBER: _ClassVar[int]
    sequence: int
    path: str
    bytes: int
    sha256: str
    start_ns: int
    end_ns: int
    open_end: bool
    def __init__(self, sequence: _Optional[int] = ..., path: _Optional[str] = ..., bytes: _Optional[int] = ..., sha256: _Optional[str] = ..., start_ns: _Optional[int] = ..., end_ns: _Optional[int] = ..., open_end: bool = ...) -> None: ...

class StreamGroup(_message.Message):
    __slots__ = ("kind", "source", "codec", "record_count", "decoded_bytes", "decoded_sha256", "truncated", "columns", "payload")
    KIND_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    CODEC_FIELD_NUMBER: _ClassVar[int]
    RECORD_COUNT_FIELD_NUMBER: _ClassVar[int]
    DECODED_BYTES_FIELD_NUMBER: _ClassVar[int]
    DECODED_SHA256_FIELD_NUMBER: _ClassVar[int]
    TRUNCATED_FIELD_NUMBER: _ClassVar[int]
    COLUMNS_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    kind: StreamKind
    source: str
    codec: ArchiveCodec
    record_count: int
    decoded_bytes: int
    decoded_sha256: str
    truncated: bool
    columns: _containers.RepeatedCompositeFieldContainer[ColumnDef]
    payload: bytes
    def __init__(self, kind: _Optional[_Union[StreamKind, str]] = ..., source: _Optional[str] = ..., codec: _Optional[_Union[ArchiveCodec, str]] = ..., record_count: _Optional[int] = ..., decoded_bytes: _Optional[int] = ..., decoded_sha256: _Optional[str] = ..., truncated: bool = ..., columns: _Optional[_Iterable[_Union[ColumnDef, _Mapping]]] = ..., payload: _Optional[bytes] = ...) -> None: ...

class ColumnDef(_message.Message):
    __slots__ = ("name", "type")
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    name: str
    type: str
    def __init__(self, name: _Optional[str] = ..., type: _Optional[str] = ...) -> None: ...

class RedisStreamRecords(_message.Message):
    __slots__ = ("records",)
    RECORDS_FIELD_NUMBER: _ClassVar[int]
    records: _containers.RepeatedCompositeFieldContainer[RedisStreamRecord]
    def __init__(self, records: _Optional[_Iterable[_Union[RedisStreamRecord, _Mapping]]] = ...) -> None: ...

class RedisStreamRecord(_message.Message):
    __slots__ = ("id", "fields")
    ID_FIELD_NUMBER: _ClassVar[int]
    FIELDS_FIELD_NUMBER: _ClassVar[int]
    id: str
    fields: _containers.RepeatedCompositeFieldContainer[RedisStreamField]
    def __init__(self, id: _Optional[str] = ..., fields: _Optional[_Iterable[_Union[RedisStreamField, _Mapping]]] = ...) -> None: ...

class RedisStreamField(_message.Message):
    __slots__ = ("name", "value")
    NAME_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    name: str
    value: bytes
    def __init__(self, name: _Optional[str] = ..., value: _Optional[bytes] = ...) -> None: ...

class TableRows(_message.Message):
    __slots__ = ("rows",)
    ROWS_FIELD_NUMBER: _ClassVar[int]
    rows: _containers.RepeatedScalarFieldContainer[bytes]
    def __init__(self, rows: _Optional[_Iterable[bytes]] = ...) -> None: ...
