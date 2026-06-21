import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class InputSchemaVersion(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    INPUT_SCHEMA_VERSION_UNSPECIFIED: _ClassVar[InputSchemaVersion]
    INPUT_SCHEMA_VERSION_V1: _ClassVar[InputSchemaVersion]

class PointerPhase(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    POINTER_PHASE_UNSPECIFIED: _ClassVar[PointerPhase]
    POINTER_PHASE_DOWN: _ClassVar[PointerPhase]
    POINTER_PHASE_MOVE: _ClassVar[PointerPhase]
    POINTER_PHASE_UP: _ClassVar[PointerPhase]

class PointerKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    POINTER_KIND_UNSPECIFIED: _ClassVar[PointerKind]
    POINTER_KIND_MOUSE: _ClassVar[PointerKind]
    POINTER_KIND_TOUCH: _ClassVar[PointerKind]
    POINTER_KIND_PEN: _ClassVar[PointerKind]

class RecognizedGesture(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RECOGNIZED_GESTURE_UNSPECIFIED: _ClassVar[RecognizedGesture]
    RECOGNIZED_GESTURE_PAN_MOVE: _ClassVar[RecognizedGesture]
    RECOGNIZED_GESTURE_PAN_END: _ClassVar[RecognizedGesture]
    RECOGNIZED_GESTURE_TAP: _ClassVar[RecognizedGesture]
    RECOGNIZED_GESTURE_TRACK: _ClassVar[RecognizedGesture]
    RECOGNIZED_GESTURE_PINCH: _ClassVar[RecognizedGesture]

class ThemeMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    THEME_MODE_UNSPECIFIED: _ClassVar[ThemeMode]
    THEME_MODE_LIGHT: _ClassVar[ThemeMode]
    THEME_MODE_DARK: _ClassVar[ThemeMode]

class CursorType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CURSOR_TYPE_UNSPECIFIED: _ClassVar[CursorType]
    CURSOR_TYPE_DEFAULT: _ClassVar[CursorType]
    CURSOR_TYPE_POINTER: _ClassVar[CursorType]
    CURSOR_TYPE_TEXT: _ClassVar[CursorType]
    CURSOR_TYPE_GRAB: _ClassVar[CursorType]
    CURSOR_TYPE_RESIZE: _ClassVar[CursorType]
    CURSOR_TYPE_NOT_ALLOWED: _ClassVar[CursorType]
INPUT_SCHEMA_VERSION_UNSPECIFIED: InputSchemaVersion
INPUT_SCHEMA_VERSION_V1: InputSchemaVersion
POINTER_PHASE_UNSPECIFIED: PointerPhase
POINTER_PHASE_DOWN: PointerPhase
POINTER_PHASE_MOVE: PointerPhase
POINTER_PHASE_UP: PointerPhase
POINTER_KIND_UNSPECIFIED: PointerKind
POINTER_KIND_MOUSE: PointerKind
POINTER_KIND_TOUCH: PointerKind
POINTER_KIND_PEN: PointerKind
RECOGNIZED_GESTURE_UNSPECIFIED: RecognizedGesture
RECOGNIZED_GESTURE_PAN_MOVE: RecognizedGesture
RECOGNIZED_GESTURE_PAN_END: RecognizedGesture
RECOGNIZED_GESTURE_TAP: RecognizedGesture
RECOGNIZED_GESTURE_TRACK: RecognizedGesture
RECOGNIZED_GESTURE_PINCH: RecognizedGesture
THEME_MODE_UNSPECIFIED: ThemeMode
THEME_MODE_LIGHT: ThemeMode
THEME_MODE_DARK: ThemeMode
CURSOR_TYPE_UNSPECIFIED: CursorType
CURSOR_TYPE_DEFAULT: CursorType
CURSOR_TYPE_POINTER: CursorType
CURSOR_TYPE_TEXT: CursorType
CURSOR_TYPE_GRAB: CursorType
CURSOR_TYPE_RESIZE: CursorType
CURSOR_TYPE_NOT_ALLOWED: CursorType

class PointerEvent(_message.Message):
    __slots__ = ("phase", "kind", "x", "y", "buttons")
    PHASE_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    BUTTONS_FIELD_NUMBER: _ClassVar[int]
    phase: PointerPhase
    kind: PointerKind
    x: float
    y: float
    buttons: int
    def __init__(self, phase: _Optional[_Union[PointerPhase, str]] = ..., kind: _Optional[_Union[PointerKind, str]] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., buttons: _Optional[int] = ...) -> None: ...

class GestureCommand(_message.Message):
    __slots__ = ("gesture", "channel", "x", "y", "az_speed", "el_speed", "az_dir", "el_dir", "zoom", "frame_time", "state_time")
    GESTURE_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    AZ_SPEED_FIELD_NUMBER: _ClassVar[int]
    EL_SPEED_FIELD_NUMBER: _ClassVar[int]
    AZ_DIR_FIELD_NUMBER: _ClassVar[int]
    EL_DIR_FIELD_NUMBER: _ClassVar[int]
    ZOOM_FIELD_NUMBER: _ClassVar[int]
    FRAME_TIME_FIELD_NUMBER: _ClassVar[int]
    STATE_TIME_FIELD_NUMBER: _ClassVar[int]
    gesture: RecognizedGesture
    channel: _jon_shared_data_types_pb2.JonGuiDataVideoChannel
    x: float
    y: float
    az_speed: float
    el_speed: float
    az_dir: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    el_dir: _jon_shared_data_types_pb2.JonGuiDataRotaryDirection
    zoom: int
    frame_time: int
    state_time: int
    def __init__(self, gesture: _Optional[_Union[RecognizedGesture, str]] = ..., channel: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannel, str]] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., az_speed: _Optional[float] = ..., el_speed: _Optional[float] = ..., az_dir: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ..., el_dir: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRotaryDirection, str]] = ..., zoom: _Optional[int] = ..., frame_time: _Optional[int] = ..., state_time: _Optional[int] = ...) -> None: ...

class Lifecycle(_message.Message):
    __slots__ = ("theme", "focused", "visible")
    THEME_FIELD_NUMBER: _ClassVar[int]
    FOCUSED_FIELD_NUMBER: _ClassVar[int]
    VISIBLE_FIELD_NUMBER: _ClassVar[int]
    theme: ThemeMode
    focused: bool
    visible: bool
    def __init__(self, theme: _Optional[_Union[ThemeMode, str]] = ..., focused: bool = ..., visible: bool = ...) -> None: ...

class HostToWasm(_message.Message):
    __slots__ = ("version", "pointer", "gesture", "lifecycle")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    POINTER_FIELD_NUMBER: _ClassVar[int]
    GESTURE_FIELD_NUMBER: _ClassVar[int]
    LIFECYCLE_FIELD_NUMBER: _ClassVar[int]
    version: int
    pointer: PointerEvent
    gesture: GestureCommand
    lifecycle: Lifecycle
    def __init__(self, version: _Optional[int] = ..., pointer: _Optional[_Union[PointerEvent, _Mapping]] = ..., gesture: _Optional[_Union[GestureCommand, _Mapping]] = ..., lifecycle: _Optional[_Union[Lifecycle, _Mapping]] = ...) -> None: ...

class HoverState(_message.Message):
    __slots__ = ("hovered_uid", "interactive")
    HOVERED_UID_FIELD_NUMBER: _ClassVar[int]
    INTERACTIVE_FIELD_NUMBER: _ClassVar[int]
    hovered_uid: int
    interactive: bool
    def __init__(self, hovered_uid: _Optional[int] = ..., interactive: bool = ...) -> None: ...

class CursorRequest(_message.Message):
    __slots__ = ("cursor",)
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    cursor: CursorType
    def __init__(self, cursor: _Optional[_Union[CursorType, str]] = ...) -> None: ...

class WasmToHost(_message.Message):
    __slots__ = ("version", "hover", "cursor")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    HOVER_FIELD_NUMBER: _ClassVar[int]
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    version: int
    hover: HoverState
    cursor: CursorRequest
    def __init__(self, version: _Optional[int] = ..., hover: _Optional[_Union[HoverState, _Mapping]] = ..., cursor: _Optional[_Union[CursorRequest, _Mapping]] = ...) -> None: ...
