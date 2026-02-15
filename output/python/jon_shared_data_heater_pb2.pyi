from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

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
    __slots__ = ("bus_voltage_V", "current_A", "power_W", "channel_0", "channel_1", "channel_2", "automatic_control_enabled", "target_temp_channel_0", "target_temp_channel_1", "target_temp_channel_2")
    BUS_VOLTAGE_V_FIELD_NUMBER: _ClassVar[int]
    CURRENT_A_FIELD_NUMBER: _ClassVar[int]
    POWER_W_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_0_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_1_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_2_FIELD_NUMBER: _ClassVar[int]
    AUTOMATIC_CONTROL_ENABLED_FIELD_NUMBER: _ClassVar[int]
    TARGET_TEMP_CHANNEL_0_FIELD_NUMBER: _ClassVar[int]
    TARGET_TEMP_CHANNEL_1_FIELD_NUMBER: _ClassVar[int]
    TARGET_TEMP_CHANNEL_2_FIELD_NUMBER: _ClassVar[int]
    bus_voltage_V: float
    current_A: float
    power_W: float
    channel_0: JonGuiDataHeaterChannelStatus
    channel_1: JonGuiDataHeaterChannelStatus
    channel_2: JonGuiDataHeaterChannelStatus
    automatic_control_enabled: bool
    target_temp_channel_0: float
    target_temp_channel_1: float
    target_temp_channel_2: float
    def __init__(self, bus_voltage_V: _Optional[float] = ..., current_A: _Optional[float] = ..., power_W: _Optional[float] = ..., channel_0: _Optional[_Union[JonGuiDataHeaterChannelStatus, _Mapping]] = ..., channel_1: _Optional[_Union[JonGuiDataHeaterChannelStatus, _Mapping]] = ..., channel_2: _Optional[_Union[JonGuiDataHeaterChannelStatus, _Mapping]] = ..., automatic_control_enabled: bool = ..., target_temp_channel_0: _Optional[float] = ..., target_temp_channel_1: _Optional[float] = ..., target_temp_channel_2: _Optional[float] = ...) -> None: ...
