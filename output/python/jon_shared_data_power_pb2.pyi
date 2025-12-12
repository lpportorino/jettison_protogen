from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataPowerModule(_message.Message):
    __slots__ = ("voltage", "current", "power", "is_on", "has_alarm")
    VOLTAGE_FIELD_NUMBER: _ClassVar[int]
    CURRENT_FIELD_NUMBER: _ClassVar[int]
    POWER_FIELD_NUMBER: _ClassVar[int]
    IS_ON_FIELD_NUMBER: _ClassVar[int]
    HAS_ALARM_FIELD_NUMBER: _ClassVar[int]
    voltage: float
    current: float
    power: float
    is_on: bool
    has_alarm: bool
    def __init__(self, voltage: _Optional[float] = ..., current: _Optional[float] = ..., power: _Optional[float] = ..., is_on: bool = ..., has_alarm: bool = ...) -> None: ...

class JonGuiDataPower(_message.Message):
    __slots__ = ("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7")
    S0_FIELD_NUMBER: _ClassVar[int]
    S1_FIELD_NUMBER: _ClassVar[int]
    S2_FIELD_NUMBER: _ClassVar[int]
    S3_FIELD_NUMBER: _ClassVar[int]
    S4_FIELD_NUMBER: _ClassVar[int]
    S5_FIELD_NUMBER: _ClassVar[int]
    S6_FIELD_NUMBER: _ClassVar[int]
    S7_FIELD_NUMBER: _ClassVar[int]
    s0: JonGuiDataPowerModule
    s1: JonGuiDataPowerModule
    s2: JonGuiDataPowerModule
    s3: JonGuiDataPowerModule
    s4: JonGuiDataPowerModule
    s5: JonGuiDataPowerModule
    s6: JonGuiDataPowerModule
    s7: JonGuiDataPowerModule
    def __init__(self, s0: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s1: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s2: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s3: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s4: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s5: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s6: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ..., s7: _Optional[_Union[JonGuiDataPowerModule, _Mapping]] = ...) -> None: ...
