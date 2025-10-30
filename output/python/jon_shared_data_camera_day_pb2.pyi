import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataCameraDay(_message.Message):
    __slots__ = ("focus_pos", "zoom_pos", "iris_pos", "infrared_filter", "zoom_table_pos", "zoom_table_pos_max", "fx_mode", "auto_focus", "auto_iris", "digital_zoom_level", "clahe_level")
    FOCUS_POS_FIELD_NUMBER: _ClassVar[int]
    ZOOM_POS_FIELD_NUMBER: _ClassVar[int]
    IRIS_POS_FIELD_NUMBER: _ClassVar[int]
    INFRARED_FILTER_FIELD_NUMBER: _ClassVar[int]
    ZOOM_TABLE_POS_FIELD_NUMBER: _ClassVar[int]
    ZOOM_TABLE_POS_MAX_FIELD_NUMBER: _ClassVar[int]
    FX_MODE_FIELD_NUMBER: _ClassVar[int]
    AUTO_FOCUS_FIELD_NUMBER: _ClassVar[int]
    AUTO_IRIS_FIELD_NUMBER: _ClassVar[int]
    DIGITAL_ZOOM_LEVEL_FIELD_NUMBER: _ClassVar[int]
    CLAHE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    focus_pos: float
    zoom_pos: float
    iris_pos: float
    infrared_filter: bool
    zoom_table_pos: int
    zoom_table_pos_max: int
    fx_mode: _jon_shared_data_types_pb2.JonGuiDataFxModeDay
    auto_focus: bool
    auto_iris: bool
    digital_zoom_level: float
    clahe_level: float
    def __init__(self, focus_pos: _Optional[float] = ..., zoom_pos: _Optional[float] = ..., iris_pos: _Optional[float] = ..., infrared_filter: bool = ..., zoom_table_pos: _Optional[int] = ..., zoom_table_pos_max: _Optional[int] = ..., fx_mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataFxModeDay, str]] = ..., auto_focus: bool = ..., auto_iris: bool = ..., digital_zoom_level: _Optional[float] = ..., clahe_level: _Optional[float] = ...) -> None: ...
