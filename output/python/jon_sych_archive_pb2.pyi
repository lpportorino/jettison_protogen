import jon_video_meta_pb2 as _jon_video_meta_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SychArchiveIndex(_message.Message):
    __slots__ = ("version", "created_at", "exported_from", "files", "timeline", "osd")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    CREATED_AT_FIELD_NUMBER: _ClassVar[int]
    EXPORTED_FROM_FIELD_NUMBER: _ClassVar[int]
    FILES_FIELD_NUMBER: _ClassVar[int]
    TIMELINE_FIELD_NUMBER: _ClassVar[int]
    OSD_FIELD_NUMBER: _ClassVar[int]
    version: int
    created_at: int
    exported_from: str
    files: _containers.RepeatedCompositeFieldContainer[ArchiveEntry]
    timeline: TimelineIndex
    osd: OSDReference
    def __init__(self, version: _Optional[int] = ..., created_at: _Optional[int] = ..., exported_from: _Optional[str] = ..., files: _Optional[_Iterable[_Union[ArchiveEntry, _Mapping]]] = ..., timeline: _Optional[_Union[TimelineIndex, _Mapping]] = ..., osd: _Optional[_Union[OSDReference, _Mapping]] = ...) -> None: ...

class ArchiveEntry(_message.Message):
    __slots__ = ("path", "header_offset", "data_offset", "size")
    PATH_FIELD_NUMBER: _ClassVar[int]
    HEADER_OFFSET_FIELD_NUMBER: _ClassVar[int]
    DATA_OFFSET_FIELD_NUMBER: _ClassVar[int]
    SIZE_FIELD_NUMBER: _ClassVar[int]
    path: str
    header_offset: int
    data_offset: int
    size: int
    def __init__(self, path: _Optional[str] = ..., header_offset: _Optional[int] = ..., data_offset: _Optional[int] = ..., size: _Optional[int] = ...) -> None: ...

class TimelineIndex(_message.Message):
    __slots__ = ("total_frames", "total_duration_ms", "videos")
    TOTAL_FRAMES_FIELD_NUMBER: _ClassVar[int]
    TOTAL_DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    VIDEOS_FIELD_NUMBER: _ClassVar[int]
    total_frames: int
    total_duration_ms: int
    videos: _containers.RepeatedCompositeFieldContainer[VideoEntry]
    def __init__(self, total_frames: _Optional[int] = ..., total_duration_ms: _Optional[int] = ..., videos: _Optional[_Iterable[_Union[VideoEntry, _Mapping]]] = ...) -> None: ...

class VideoEntry(_message.Message):
    __slots__ = ("id", "archive_path", "thumbnail_path", "global_frame_start", "meta")
    ID_FIELD_NUMBER: _ClassVar[int]
    ARCHIVE_PATH_FIELD_NUMBER: _ClassVar[int]
    THUMBNAIL_PATH_FIELD_NUMBER: _ClassVar[int]
    GLOBAL_FRAME_START_FIELD_NUMBER: _ClassVar[int]
    META_FIELD_NUMBER: _ClassVar[int]
    id: str
    archive_path: str
    thumbnail_path: str
    global_frame_start: int
    meta: _jon_video_meta_pb2.VideoMeta
    def __init__(self, id: _Optional[str] = ..., archive_path: _Optional[str] = ..., thumbnail_path: _Optional[str] = ..., global_frame_start: _Optional[int] = ..., meta: _Optional[_Union[_jon_video_meta_pb2.VideoMeta, _Mapping]] = ...) -> None: ...

class OSDReference(_message.Message):
    __slots__ = ("package_path", "config_path", "package_name", "package_version", "package_variant")
    PACKAGE_PATH_FIELD_NUMBER: _ClassVar[int]
    CONFIG_PATH_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_NAME_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_VERSION_FIELD_NUMBER: _ClassVar[int]
    PACKAGE_VARIANT_FIELD_NUMBER: _ClassVar[int]
    package_path: str
    config_path: str
    package_name: str
    package_version: str
    package_variant: str
    def __init__(self, package_path: _Optional[str] = ..., config_path: _Optional[str] = ..., package_name: _Optional[str] = ..., package_version: _Optional[str] = ..., package_variant: _Optional[str] = ...) -> None: ...
