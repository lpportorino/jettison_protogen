import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataCV(_message.Message):
    __slots__ = ("autofocus_state_day", "sharpness_day", "best_sharpness_day", "sweep_progress_day", "best_focus_pos_day", "autofocus_state_heat", "sharpness_heat", "best_sharpness_heat", "sweep_progress_heat", "best_focus_pos_heat", "roi_x1", "roi_y1", "roi_x2", "roi_y2")
    class AutofocusState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        AUTOFOCUS_STATE_UNSPECIFIED: _ClassVar[JonGuiDataCV.AutofocusState]
        AUTOFOCUS_STATE_IDLE: _ClassVar[JonGuiDataCV.AutofocusState]
        AUTOFOCUS_STATE_COARSE_SWEEP: _ClassVar[JonGuiDataCV.AutofocusState]
        AUTOFOCUS_STATE_FINE_SWEEP: _ClassVar[JonGuiDataCV.AutofocusState]
        AUTOFOCUS_STATE_CONVERGED: _ClassVar[JonGuiDataCV.AutofocusState]
        AUTOFOCUS_STATE_FAILED: _ClassVar[JonGuiDataCV.AutofocusState]
    AUTOFOCUS_STATE_UNSPECIFIED: JonGuiDataCV.AutofocusState
    AUTOFOCUS_STATE_IDLE: JonGuiDataCV.AutofocusState
    AUTOFOCUS_STATE_COARSE_SWEEP: JonGuiDataCV.AutofocusState
    AUTOFOCUS_STATE_FINE_SWEEP: JonGuiDataCV.AutofocusState
    AUTOFOCUS_STATE_CONVERGED: JonGuiDataCV.AutofocusState
    AUTOFOCUS_STATE_FAILED: JonGuiDataCV.AutofocusState
    AUTOFOCUS_STATE_DAY_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_DAY_FIELD_NUMBER: _ClassVar[int]
    BEST_SHARPNESS_DAY_FIELD_NUMBER: _ClassVar[int]
    SWEEP_PROGRESS_DAY_FIELD_NUMBER: _ClassVar[int]
    BEST_FOCUS_POS_DAY_FIELD_NUMBER: _ClassVar[int]
    AUTOFOCUS_STATE_HEAT_FIELD_NUMBER: _ClassVar[int]
    SHARPNESS_HEAT_FIELD_NUMBER: _ClassVar[int]
    BEST_SHARPNESS_HEAT_FIELD_NUMBER: _ClassVar[int]
    SWEEP_PROGRESS_HEAT_FIELD_NUMBER: _ClassVar[int]
    BEST_FOCUS_POS_HEAT_FIELD_NUMBER: _ClassVar[int]
    ROI_X1_FIELD_NUMBER: _ClassVar[int]
    ROI_Y1_FIELD_NUMBER: _ClassVar[int]
    ROI_X2_FIELD_NUMBER: _ClassVar[int]
    ROI_Y2_FIELD_NUMBER: _ClassVar[int]
    autofocus_state_day: JonGuiDataCV.AutofocusState
    sharpness_day: float
    best_sharpness_day: float
    sweep_progress_day: int
    best_focus_pos_day: float
    autofocus_state_heat: JonGuiDataCV.AutofocusState
    sharpness_heat: float
    best_sharpness_heat: float
    sweep_progress_heat: int
    best_focus_pos_heat: float
    roi_x1: float
    roi_y1: float
    roi_x2: float
    roi_y2: float
    def __init__(self, autofocus_state_day: _Optional[_Union[JonGuiDataCV.AutofocusState, str]] = ..., sharpness_day: _Optional[float] = ..., best_sharpness_day: _Optional[float] = ..., sweep_progress_day: _Optional[int] = ..., best_focus_pos_day: _Optional[float] = ..., autofocus_state_heat: _Optional[_Union[JonGuiDataCV.AutofocusState, str]] = ..., sharpness_heat: _Optional[float] = ..., best_sharpness_heat: _Optional[float] = ..., sweep_progress_heat: _Optional[int] = ..., best_focus_pos_heat: _Optional[float] = ..., roi_x1: _Optional[float] = ..., roi_y1: _Optional[float] = ..., roi_x2: _Optional[float] = ..., roi_y2: _Optional[float] = ...) -> None: ...
