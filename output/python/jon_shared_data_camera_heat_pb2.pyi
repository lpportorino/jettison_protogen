import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataCameraHeat(_message.Message):
    __slots__ = ("zoom_pos", "agc_mode", "filter", "auto_focus", "zoom_table_pos", "zoom_table_pos_max", "dde_level", "dde_enabled", "fx_mode", "digital_zoom_level", "clahe_level")
    ZOOM_POS_FIELD_NUMBER: _ClassVar[int]
    AGC_MODE_FIELD_NUMBER: _ClassVar[int]
    FILTER_FIELD_NUMBER: _ClassVar[int]
    AUTO_FOCUS_FIELD_NUMBER: _ClassVar[int]
    ZOOM_TABLE_POS_FIELD_NUMBER: _ClassVar[int]
    ZOOM_TABLE_POS_MAX_FIELD_NUMBER: _ClassVar[int]
    DDE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    DDE_ENABLED_FIELD_NUMBER: _ClassVar[int]
    FX_MODE_FIELD_NUMBER: _ClassVar[int]
    DIGITAL_ZOOM_LEVEL_FIELD_NUMBER: _ClassVar[int]
    CLAHE_LEVEL_FIELD_NUMBER: _ClassVar[int]
    zoom_pos: float
    agc_mode: _jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatAGCModes
    filter: _jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatFilters
    auto_focus: bool
    zoom_table_pos: int
    zoom_table_pos_max: int
    dde_level: int
    dde_enabled: bool
    fx_mode: _jon_shared_data_types_pb2.JonGuiDataFxModeHeat
    digital_zoom_level: float
    clahe_level: float
    def __init__(self, zoom_pos: _Optional[float] = ..., agc_mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatAGCModes, str]] = ..., filter: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataVideoChannelHeatFilters, str]] = ..., auto_focus: bool = ..., zoom_table_pos: _Optional[int] = ..., zoom_table_pos_max: _Optional[int] = ..., dde_level: _Optional[int] = ..., dde_enabled: bool = ..., fx_mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataFxModeHeat, str]] = ..., digital_zoom_level: _Optional[float] = ..., clahe_level: _Optional[float] = ...) -> None: ...
