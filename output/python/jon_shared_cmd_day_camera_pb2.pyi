import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SetValue(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class Move(_message.Message):
    __slots__ = ("target_value", "speed")
    TARGET_VALUE_FIELD_NUMBER: _ClassVar[int]
    SPEED_FIELD_NUMBER: _ClassVar[int]
    target_value: float
    speed: float
    def __init__(self, target_value: _Optional[float] = ..., speed: _Optional[float] = ...) -> None: ...

class Offset(_message.Message):
    __slots__ = ("offset_value",)
    OFFSET_VALUE_FIELD_NUMBER: _ClassVar[int]
    offset_value: float
    def __init__(self, offset_value: _Optional[float] = ...) -> None: ...

class SetClaheLevel(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class ShiftClaheLevel(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class Root(_message.Message):
    __slots__ = ("focus", "zoom", "set_iris", "set_infra_red_filter", "start", "stop", "photo", "set_auto_iris", "halt_all", "set_fx_mode", "next_fx_mode", "prev_fx_mode", "get_meteo", "refresh_fx_mode", "set_digital_zoom_level", "set_clahe_level", "shift_clahe_level", "focus_at_roi", "track_roi", "zoom_roi")
    FOCUS_FIELD_NUMBER: _ClassVar[int]
    ZOOM_FIELD_NUMBER: _ClassVar[int]
    SET_IRIS_FIELD_NUMBER: _ClassVar[int]
    SET_INFRA_RED_FILTER_FIELD_NUMBER: _ClassVar[int]
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    PHOTO_FIELD_NUMBER: _ClassVar[int]
    SET_AUTO_IRIS_FIELD_NUMBER: _ClassVar[int]
    HALT_ALL_FIELD_NUMBER: _ClassVar[int]
    SET_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    NEXT_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    PREV_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    REFRESH_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    SET_DIGITAL_ZOOM_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SET_CLAHE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SHIFT_CLAHE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    FOCUS_AT_ROI_FIELD_NUMBER: _ClassVar[int]
    TRACK_ROI_FIELD_NUMBER: _ClassVar[int]
    ZOOM_ROI_FIELD_NUMBER: _ClassVar[int]
    focus: Focus
    zoom: Zoom
    set_iris: SetIris
    set_infra_red_filter: SetInfraRedFilter
    start: Start
    stop: Stop
    photo: Photo
    set_auto_iris: SetAutoIris
    halt_all: HaltAll
    set_fx_mode: SetFxMode
    next_fx_mode: NextFxMode
    prev_fx_mode: PrevFxMode
    get_meteo: GetMeteo
    refresh_fx_mode: RefreshFxMode
    set_digital_zoom_level: SetDigitalZoomLevel
    set_clahe_level: SetClaheLevel
    shift_clahe_level: ShiftClaheLevel
    focus_at_roi: FocusAtROI
    track_roi: TrackROI
    zoom_roi: ZoomROI
    def __init__(self, focus: _Optional[_Union[Focus, _Mapping]] = ..., zoom: _Optional[_Union[Zoom, _Mapping]] = ..., set_iris: _Optional[_Union[SetIris, _Mapping]] = ..., set_infra_red_filter: _Optional[_Union[SetInfraRedFilter, _Mapping]] = ..., start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., photo: _Optional[_Union[Photo, _Mapping]] = ..., set_auto_iris: _Optional[_Union[SetAutoIris, _Mapping]] = ..., halt_all: _Optional[_Union[HaltAll, _Mapping]] = ..., set_fx_mode: _Optional[_Union[SetFxMode, _Mapping]] = ..., next_fx_mode: _Optional[_Union[NextFxMode, _Mapping]] = ..., prev_fx_mode: _Optional[_Union[PrevFxMode, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ..., refresh_fx_mode: _Optional[_Union[RefreshFxMode, _Mapping]] = ..., set_digital_zoom_level: _Optional[_Union[SetDigitalZoomLevel, _Mapping]] = ..., set_clahe_level: _Optional[_Union[SetClaheLevel, _Mapping]] = ..., shift_clahe_level: _Optional[_Union[ShiftClaheLevel, _Mapping]] = ..., focus_at_roi: _Optional[_Union[FocusAtROI, _Mapping]] = ..., track_roi: _Optional[_Union[TrackROI, _Mapping]] = ..., zoom_roi: _Optional[_Union[ZoomROI, _Mapping]] = ...) -> None: ...

class GetPos(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class NextFxMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PrevFxMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RefreshFxMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class HaltAll(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetFxMode(_message.Message):
    __slots__ = ("mode",)
    MODE_FIELD_NUMBER: _ClassVar[int]
    mode: _jon_shared_data_types_pb2.JonGuiDataFxModeDay
    def __init__(self, mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataFxModeDay, str]] = ...) -> None: ...

class SetDigitalZoomLevel(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class Focus(_message.Message):
    __slots__ = ("set_value", "move", "halt", "offset", "reset_focus", "save_to_table_focus")
    SET_VALUE_FIELD_NUMBER: _ClassVar[int]
    MOVE_FIELD_NUMBER: _ClassVar[int]
    HALT_FIELD_NUMBER: _ClassVar[int]
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    RESET_FOCUS_FIELD_NUMBER: _ClassVar[int]
    SAVE_TO_TABLE_FOCUS_FIELD_NUMBER: _ClassVar[int]
    set_value: SetValue
    move: Move
    halt: Halt
    offset: Offset
    reset_focus: ResetFocus
    save_to_table_focus: SaveToTableFocus
    def __init__(self, set_value: _Optional[_Union[SetValue, _Mapping]] = ..., move: _Optional[_Union[Move, _Mapping]] = ..., halt: _Optional[_Union[Halt, _Mapping]] = ..., offset: _Optional[_Union[Offset, _Mapping]] = ..., reset_focus: _Optional[_Union[ResetFocus, _Mapping]] = ..., save_to_table_focus: _Optional[_Union[SaveToTableFocus, _Mapping]] = ...) -> None: ...

class Zoom(_message.Message):
    __slots__ = ("set_value", "move", "halt", "set_zoom_table_value", "next_zoom_table_pos", "prev_zoom_table_pos", "offset", "reset_zoom", "save_to_table")
    SET_VALUE_FIELD_NUMBER: _ClassVar[int]
    MOVE_FIELD_NUMBER: _ClassVar[int]
    HALT_FIELD_NUMBER: _ClassVar[int]
    SET_ZOOM_TABLE_VALUE_FIELD_NUMBER: _ClassVar[int]
    NEXT_ZOOM_TABLE_POS_FIELD_NUMBER: _ClassVar[int]
    PREV_ZOOM_TABLE_POS_FIELD_NUMBER: _ClassVar[int]
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    RESET_ZOOM_FIELD_NUMBER: _ClassVar[int]
    SAVE_TO_TABLE_FIELD_NUMBER: _ClassVar[int]
    set_value: SetValue
    move: Move
    halt: Halt
    set_zoom_table_value: SetZoomTableValue
    next_zoom_table_pos: NextZoomTablePos
    prev_zoom_table_pos: PrevZoomTablePos
    offset: Offset
    reset_zoom: ResetZoom
    save_to_table: SaveToTable
    def __init__(self, set_value: _Optional[_Union[SetValue, _Mapping]] = ..., move: _Optional[_Union[Move, _Mapping]] = ..., halt: _Optional[_Union[Halt, _Mapping]] = ..., set_zoom_table_value: _Optional[_Union[SetZoomTableValue, _Mapping]] = ..., next_zoom_table_pos: _Optional[_Union[NextZoomTablePos, _Mapping]] = ..., prev_zoom_table_pos: _Optional[_Union[PrevZoomTablePos, _Mapping]] = ..., offset: _Optional[_Union[Offset, _Mapping]] = ..., reset_zoom: _Optional[_Union[ResetZoom, _Mapping]] = ..., save_to_table: _Optional[_Union[SaveToTable, _Mapping]] = ...) -> None: ...

class NextZoomTablePos(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PrevZoomTablePos(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetIris(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetInfraRedFilter(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: bool
    def __init__(self, value: bool = ...) -> None: ...

class SetAutoIris(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: bool
    def __init__(self, value: bool = ...) -> None: ...

class SetZoomTableValue(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: int
    def __init__(self, value: _Optional[int] = ...) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Photo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Halt(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ResetZoom(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ResetFocus(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SaveToTable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SaveToTableFocus(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FocusAtROI(_message.Message):
    __slots__ = ("x", "y")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ...) -> None: ...

class TrackROI(_message.Message):
    __slots__ = ("x1", "y1", "x2", "y2")
    X1_FIELD_NUMBER: _ClassVar[int]
    Y1_FIELD_NUMBER: _ClassVar[int]
    X2_FIELD_NUMBER: _ClassVar[int]
    Y2_FIELD_NUMBER: _ClassVar[int]
    x1: float
    y1: float
    x2: float
    y2: float
    def __init__(self, x1: _Optional[float] = ..., y1: _Optional[float] = ..., x2: _Optional[float] = ..., y2: _Optional[float] = ...) -> None: ...

class ZoomROI(_message.Message):
    __slots__ = ("x1", "y1", "x2", "y2")
    X1_FIELD_NUMBER: _ClassVar[int]
    Y1_FIELD_NUMBER: _ClassVar[int]
    X2_FIELD_NUMBER: _ClassVar[int]
    Y2_FIELD_NUMBER: _ClassVar[int]
    x1: float
    y1: float
    x2: float
    y2: float
    def __init__(self, x1: _Optional[float] = ..., y1: _Optional[float] = ..., x2: _Optional[float] = ..., y2: _Optional[float] = ...) -> None: ...
