from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataHeaterChannelStatus(_message.Message):
    __slots__ = ("temperature", "applied_voltage_V", "target_voltage_V", "enabled")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    APPLIED_VOLTAGE_V_FIELD_NUMBER: _ClassVar[int]
    TARGET_VOLTAGE_V_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    applied_voltage_V: float
    target_voltage_V: float
    enabled: bool
    def __init__(self, temperature: _Optional[float] = ..., applied_voltage_V: _Optional[float] = ..., target_voltage_V: _Optional[float] = ..., enabled: bool = ...) -> None: ...

class JonGuiDataHeater(_message.Message):
    __slots__ = ("bus_voltage_V", "current_A", "power_W", "channels")
    BUS_VOLTAGE_V_FIELD_NUMBER: _ClassVar[int]
    CURRENT_A_FIELD_NUMBER: _ClassVar[int]
    POWER_W_FIELD_NUMBER: _ClassVar[int]
    CHANNELS_FIELD_NUMBER: _ClassVar[int]
    bus_voltage_V: float
    current_A: float
    power_W: float
    channels: _containers.RepeatedCompositeFieldContainer[JonGuiDataHeaterChannelStatus]
    def __init__(self, bus_voltage_V: _Optional[float] = ..., current_A: _Optional[float] = ..., power_W: _Optional[float] = ..., channels: _Optional[_Iterable[_Union[JonGuiDataHeaterChannelStatus, _Mapping]]] = ...) -> None: ...
