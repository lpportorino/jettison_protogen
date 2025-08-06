import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("set_auto_focus", "start_track_ndc", "stop_track", "vampire_mode_enable", "vampire_mode_disable", "stabilization_mode_enable", "stabilization_mode_disable", "dump_start", "dump_stop", "recognition_mode_enable", "recognition_mode_disable")
    SET_AUTO_FOCUS_FIELD_NUMBER: _ClassVar[int]
    START_TRACK_NDC_FIELD_NUMBER: _ClassVar[int]
    STOP_TRACK_FIELD_NUMBER: _ClassVar[int]
    VAMPIRE_MODE_ENABLE_FIELD_NUMBER: _ClassVar[int]
    VAMPIRE_MODE_DISABLE_FIELD_NUMBER: _ClassVar[int]
    STABILIZATION_MODE_ENABLE_FIELD_NUMBER: _ClassVar[int]
    STABILIZATION_MODE_DISABLE_FIELD_NUMBER: _ClassVar[int]
    DUMP_START_FIELD_NUMBER: _ClassVar[int]
    DUMP_STOP_FIELD_NUMBER: _ClassVar[int]
    RECOGNITION_MODE_ENABLE_FIELD_NUMBER: _ClassVar[int]
    RECOGNITION_MODE_DISABLE_FIELD_NUMBER: _ClassVar[int]
    set_auto_focus: SetAutoFocus
    start_track_ndc: StartTrackNDC
    stop_track: StopTrack
    vampire_mode_enable: VampireModeEnable
    vampire_mode_disable: VampireModeDisable
    stabilization_mode_enable: StabilizationModeEnable
    stabilization_mode_disable: StabilizationModeDisable
    dump_start: DumpStart
    dump_stop: DumpStop
    recognition_mode_enable: RecognitionModeEnable
    recognition_mode_disable: RecognitionModeDisable
    def __init__(self, set_auto_focus: _Optional[_Union[SetAutoFocus, _Mapping]] = ..., start_track_ndc: _Optional[_Union[StartTrackNDC, _Mapping]] = ..., stop_track: _Optional[_Union[StopTrack, _Mapping]] = ..., vampire_mode_enable: _Optional[_Union[VampireModeEnable, _Mapping]] = ..., vampire_mode_disable: _Optional[_Union[VampireModeDisable, _Mapping]] = ..., stabilization_mode_enable: _Optional[_Union[StabilizationModeEnable, _Mapping]] = ..., stabilization_mode_disable: _Optional[_Union[StabilizationModeDisable, _Mapping]] = ..., dump_start: _Optional[_Union[DumpStart, _Mapping]] = ..., dump_stop: _Optional[_Union[DumpStop, _Mapping]] = ..., recognition_mode_enable: _Optional[_Union[RecognitionModeEnable, _Mapping]] = ..., recognition_mode_disable: _Optional[_Union[RecognitionModeDisable, _Mapping]] = ...) -> None: ...

class VampireModeEnable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DumpStart(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DumpStop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class VampireModeDisable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RecognitionModeEnable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RecognitionModeDisable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StabilizationModeEnable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StabilizationModeDisable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetAutoFocus(_message.Message):
    __slots__ = ("channel", "value")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    channel: _jon_shared_data_types_pb2.JonGuiDataVideoChannel
    value: bool
    def __init__(self, channel: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannel, str]] = ..., value: bool = ...) -> None: ...

class StartTrackNDC(_message.Message):
    __slots__ = ("channel", "x", "y", "frame_time")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    FRAME_TIME_FIELD_NUMBER: _ClassVar[int]
    channel: _jon_shared_data_types_pb2.JonGuiDataVideoChannel
    x: float
    y: float
    frame_time: int
    def __init__(self, channel: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannel, str]] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., frame_time: _Optional[int] = ...) -> None: ...

class StopTrack(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
