from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "turn_on", "turn_off", "get_meteo", "get_heater_power_state", "power_off", "charge_enable", "charge_disable", "boot_heater", "get_data_u1")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    TURN_ON_FIELD_NUMBER: _ClassVar[int]
    TURN_OFF_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    GET_HEATER_POWER_STATE_FIELD_NUMBER: _ClassVar[int]
    POWER_OFF_FIELD_NUMBER: _ClassVar[int]
    CHARGE_ENABLE_FIELD_NUMBER: _ClassVar[int]
    CHARGE_DISABLE_FIELD_NUMBER: _ClassVar[int]
    BOOT_HEATER_FIELD_NUMBER: _ClassVar[int]
    GET_DATA_U1_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    turn_on: TurnOn
    turn_off: TurnOff
    get_meteo: GetMeteo
    get_heater_power_state: GetHeaterPowerState
    power_off: PowerOff
    charge_enable: ChargeEnable
    charge_disable: ChargeDisable
    boot_heater: BootHeater
    get_data_u1: GetDataU1
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., turn_on: _Optional[_Union[TurnOn, _Mapping]] = ..., turn_off: _Optional[_Union[TurnOff, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ..., get_heater_power_state: _Optional[_Union[GetHeaterPowerState, _Mapping]] = ..., power_off: _Optional[_Union[PowerOff, _Mapping]] = ..., charge_enable: _Optional[_Union[ChargeEnable, _Mapping]] = ..., charge_disable: _Optional[_Union[ChargeDisable, _Mapping]] = ..., boot_heater: _Optional[_Union[BootHeater, _Mapping]] = ..., get_data_u1: _Optional[_Union[GetDataU1, _Mapping]] = ...) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class TurnOn(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class TurnOff(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetHeaterPowerState(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PowerOff(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ChargeEnable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ChargeDisable(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class BootHeater(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetDataU1(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
