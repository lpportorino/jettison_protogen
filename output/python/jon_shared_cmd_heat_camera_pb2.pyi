import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("zoom", "set_agc", "set_filter", "start", "stop", "photo", "zoom_in", "zoom_out", "zoom_stop", "focus_in", "focus_out", "focus_stop", "calibrate", "set_dde_level", "enable_dde", "disable_dde", "set_auto_focus", "focus_step_plus", "focus_step_minus", "set_fx_mode", "next_fx_mode", "prev_fx_mode", "get_meteo", "shift_dde", "refresh_fx_mode", "reset_zoom", "save_to_table", "set_calib_mode", "set_digital_zoom_level", "set_clahe_level", "shift_clahe_level", "focus_at_roi", "track_roi", "zoom_roi")
    ZOOM_FIELD_NUMBER: _ClassVar[int]
    SET_AGC_FIELD_NUMBER: _ClassVar[int]
    SET_FILTER_FIELD_NUMBER: _ClassVar[int]
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    PHOTO_FIELD_NUMBER: _ClassVar[int]
    ZOOM_IN_FIELD_NUMBER: _ClassVar[int]
    ZOOM_OUT_FIELD_NUMBER: _ClassVar[int]
    ZOOM_STOP_FIELD_NUMBER: _ClassVar[int]
    FOCUS_IN_FIELD_NUMBER: _ClassVar[int]
    FOCUS_OUT_FIELD_NUMBER: _ClassVar[int]
    FOCUS_STOP_FIELD_NUMBER: _ClassVar[int]
    CALIBRATE_FIELD_NUMBER: _ClassVar[int]
    SET_DDE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    ENABLE_DDE_FIELD_NUMBER: _ClassVar[int]
    DISABLE_DDE_FIELD_NUMBER: _ClassVar[int]
    SET_AUTO_FOCUS_FIELD_NUMBER: _ClassVar[int]
    FOCUS_STEP_PLUS_FIELD_NUMBER: _ClassVar[int]
    FOCUS_STEP_MINUS_FIELD_NUMBER: _ClassVar[int]
    SET_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    NEXT_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    PREV_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    SHIFT_DDE_FIELD_NUMBER: _ClassVar[int]
    REFRESH_FX_MODE_FIELD_NUMBER: _ClassVar[int]
    RESET_ZOOM_FIELD_NUMBER: _ClassVar[int]
    SAVE_TO_TABLE_FIELD_NUMBER: _ClassVar[int]
    SET_CALIB_MODE_FIELD_NUMBER: _ClassVar[int]
    SET_DIGITAL_ZOOM_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SET_CLAHE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    SHIFT_CLAHE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    FOCUS_AT_ROI_FIELD_NUMBER: _ClassVar[int]
    TRACK_ROI_FIELD_NUMBER: _ClassVar[int]
    ZOOM_ROI_FIELD_NUMBER: _ClassVar[int]
    zoom: Zoom
    set_agc: SetAGC
    set_filter: SetFilters
    start: Start
    stop: Stop
    photo: Photo
    zoom_in: ZoomIn
    zoom_out: ZoomOut
    zoom_stop: ZoomStop
    focus_in: FocusIn
    focus_out: FocusOut
    focus_stop: FocusStop
    calibrate: Calibrate
    set_dde_level: SetDDELevel
    enable_dde: EnableDDE
    disable_dde: DisableDDE
    set_auto_focus: SetAutoFocus
    focus_step_plus: FocusStepPlus
    focus_step_minus: FocusStepMinus
    set_fx_mode: SetFxMode
    next_fx_mode: NextFxMode
    prev_fx_mode: PrevFxMode
    get_meteo: GetMeteo
    shift_dde: ShiftDDE
    refresh_fx_mode: RefreshFxMode
    reset_zoom: ResetZoom
    save_to_table: SaveToTable
    set_calib_mode: SetCalibMode
    set_digital_zoom_level: SetDigitalZoomLevel
    set_clahe_level: SetClaheLevel
    shift_clahe_level: ShiftClaheLevel
    focus_at_roi: FocusAtROI
    track_roi: TrackROI
    zoom_roi: ZoomROI
    def __init__(self, zoom: _Optional[_Union[Zoom, _Mapping]] = ..., set_agc: _Optional[_Union[SetAGC, _Mapping]] = ..., set_filter: _Optional[_Union[SetFilters, _Mapping]] = ..., start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., photo: _Optional[_Union[Photo, _Mapping]] = ..., zoom_in: _Optional[_Union[ZoomIn, _Mapping]] = ..., zoom_out: _Optional[_Union[ZoomOut, _Mapping]] = ..., zoom_stop: _Optional[_Union[ZoomStop, _Mapping]] = ..., focus_in: _Optional[_Union[FocusIn, _Mapping]] = ..., focus_out: _Optional[_Union[FocusOut, _Mapping]] = ..., focus_stop: _Optional[_Union[FocusStop, _Mapping]] = ..., calibrate: _Optional[_Union[Calibrate, _Mapping]] = ..., set_dde_level: _Optional[_Union[SetDDELevel, _Mapping]] = ..., enable_dde: _Optional[_Union[EnableDDE, _Mapping]] = ..., disable_dde: _Optional[_Union[DisableDDE, _Mapping]] = ..., set_auto_focus: _Optional[_Union[SetAutoFocus, _Mapping]] = ..., focus_step_plus: _Optional[_Union[FocusStepPlus, _Mapping]] = ..., focus_step_minus: _Optional[_Union[FocusStepMinus, _Mapping]] = ..., set_fx_mode: _Optional[_Union[SetFxMode, _Mapping]] = ..., next_fx_mode: _Optional[_Union[NextFxMode, _Mapping]] = ..., prev_fx_mode: _Optional[_Union[PrevFxMode, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ..., shift_dde: _Optional[_Union[ShiftDDE, _Mapping]] = ..., refresh_fx_mode: _Optional[_Union[RefreshFxMode, _Mapping]] = ..., reset_zoom: _Optional[_Union[ResetZoom, _Mapping]] = ..., save_to_table: _Optional[_Union[SaveToTable, _Mapping]] = ..., set_calib_mode: _Optional[_Union[SetCalibMode, _Mapping]] = ..., set_digital_zoom_level: _Optional[_Union[SetDigitalZoomLevel, _Mapping]] = ..., set_clahe_level: _Optional[_Union[SetClaheLevel, _Mapping]] = ..., shift_clahe_level: _Optional[_Union[ShiftClaheLevel, _Mapping]] = ..., focus_at_roi: _Optional[_Union[FocusAtROI, _Mapping]] = ..., track_roi: _Optional[_Union[TrackROI, _Mapping]] = ..., zoom_roi: _Optional[_Union[ZoomROI, _Mapping]] = ...) -> None: ...

class SetFxMode(_message.Message):
    __slots__ = ("mode",)
    MODE_FIELD_NUMBER: _ClassVar[int]
    mode: _jon_shared_data_types_pb2.JonGuiDataFxModeHeat
    def __init__(self, mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataFxModeHeat, str]] = ...) -> None: ...

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

class NextFxMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PrevFxMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RefreshFxMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnableDDE(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableDDE(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetValue(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetDDELevel(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: int
    def __init__(self, value: _Optional[int] = ...) -> None: ...

class SetDigitalZoomLevel(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class ShiftDDE(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: int
    def __init__(self, value: _Optional[int] = ...) -> None: ...

class ZoomIn(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ZoomOut(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ZoomStop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FocusIn(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FocusOut(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FocusStop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FocusStepPlus(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FocusStepMinus(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Calibrate(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Zoom(_message.Message):
    __slots__ = ("set_zoom_table_value", "next_zoom_table_pos", "prev_zoom_table_pos")
    SET_ZOOM_TABLE_VALUE_FIELD_NUMBER: _ClassVar[int]
    NEXT_ZOOM_TABLE_POS_FIELD_NUMBER: _ClassVar[int]
    PREV_ZOOM_TABLE_POS_FIELD_NUMBER: _ClassVar[int]
    set_zoom_table_value: SetZoomTableValue
    next_zoom_table_pos: NextZoomTablePos
    prev_zoom_table_pos: PrevZoomTablePos
    def __init__(self, set_zoom_table_value: _Optional[_Union[SetZoomTableValue, _Mapping]] = ..., next_zoom_table_pos: _Optional[_Union[NextZoomTablePos, _Mapping]] = ..., prev_zoom_table_pos: _Optional[_Union[PrevZoomTablePos, _Mapping]] = ...) -> None: ...

class NextZoomTablePos(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PrevZoomTablePos(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetCalibMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetZoomTableValue(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: int
    def __init__(self, value: _Optional[int] = ...) -> None: ...

class SetAGC(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: _jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatAGCModes
    def __init__(self, value: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatAGCModes, str]] = ...) -> None: ...

class SetFilters(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: _jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatFilters
    def __init__(self, value: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatFilters, str]] = ...) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Halt(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Photo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetAutoFocus(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: bool
    def __init__(self, value: bool = ...) -> None: ...

class ResetZoom(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SaveToTable(_message.Message):
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
