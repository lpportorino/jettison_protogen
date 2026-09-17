import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataScene(_message.Message):
    __slots__ = ("day_auto", "day_class", "day_challenger", "day_hold_s", "day_hold_reason", "day_mode", "day_scores", "heat_auto", "heat_class", "heat_challenger", "heat_hold_s", "heat_hold_reason", "heat_mode", "heat_scores", "shadow")
    DAY_AUTO_FIELD_NUMBER: _ClassVar[int]
    DAY_CLASS_FIELD_NUMBER: _ClassVar[int]
    DAY_CHALLENGER_FIELD_NUMBER: _ClassVar[int]
    DAY_HOLD_S_FIELD_NUMBER: _ClassVar[int]
    DAY_HOLD_REASON_FIELD_NUMBER: _ClassVar[int]
    DAY_MODE_FIELD_NUMBER: _ClassVar[int]
    DAY_SCORES_FIELD_NUMBER: _ClassVar[int]
    HEAT_AUTO_FIELD_NUMBER: _ClassVar[int]
    HEAT_CLASS_FIELD_NUMBER: _ClassVar[int]
    HEAT_CHALLENGER_FIELD_NUMBER: _ClassVar[int]
    HEAT_HOLD_S_FIELD_NUMBER: _ClassVar[int]
    HEAT_HOLD_REASON_FIELD_NUMBER: _ClassVar[int]
    HEAT_MODE_FIELD_NUMBER: _ClassVar[int]
    HEAT_SCORES_FIELD_NUMBER: _ClassVar[int]
    SHADOW_FIELD_NUMBER: _ClassVar[int]
    day_auto: bool
    day_class: _jon_shared_data_types_pb2.JonGuiDataSceneClass
    day_challenger: _jon_shared_data_types_pb2.JonGuiDataSceneClass
    day_hold_s: int
    day_hold_reason: str
    day_mode: _jon_shared_data_types_pb2.JonGuiDataFxModeDay
    day_scores: _containers.RepeatedScalarFieldContainer[float]
    heat_auto: bool
    heat_class: _jon_shared_data_types_pb2.JonGuiDataHeatSceneClass
    heat_challenger: _jon_shared_data_types_pb2.JonGuiDataHeatSceneClass
    heat_hold_s: int
    heat_hold_reason: str
    heat_mode: _jon_shared_data_types_pb2.JonGuiDataFxModeHeat
    heat_scores: _containers.RepeatedScalarFieldContainer[float]
    shadow: bool
    def __init__(self, day_auto: bool = ..., day_class: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataSceneClass, str]] = ..., day_challenger: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataSceneClass, str]] = ..., day_hold_s: _Optional[int] = ..., day_hold_reason: _Optional[str] = ..., day_mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataFxModeDay, str]] = ..., day_scores: _Optional[_Iterable[float]] = ..., heat_auto: bool = ..., heat_class: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataHeatSceneClass, str]] = ..., heat_challenger: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataHeatSceneClass, str]] = ..., heat_hold_s: _Optional[int] = ..., heat_hold_reason: _Optional[str] = ..., heat_mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataFxModeHeat, str]] = ..., heat_scores: _Optional[_Iterable[float]] = ..., shadow: bool = ...) -> None: ...
