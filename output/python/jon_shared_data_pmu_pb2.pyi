import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataPMU(_message.Message):
    __slots__ = ("temperature", "is_started", "meteo", "voltage", "heater_power_state", "ina_voltage", "ina_current", "ina_power", "ina_power_fault", "charge_disabled")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    IS_STARTED_FIELD_NUMBER: _ClassVar[int]
    METEO_FIELD_NUMBER: _ClassVar[int]
    VOLTAGE_FIELD_NUMBER: _ClassVar[int]
    HEATER_POWER_STATE_FIELD_NUMBER: _ClassVar[int]
    INA_VOLTAGE_FIELD_NUMBER: _ClassVar[int]
    INA_CURRENT_FIELD_NUMBER: _ClassVar[int]
    INA_POWER_FIELD_NUMBER: _ClassVar[int]
    INA_POWER_FAULT_FIELD_NUMBER: _ClassVar[int]
    CHARGE_DISABLED_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    is_started: bool
    meteo: _jon_shared_data_types_pb2.JonGuiDataMeteo
    voltage: float
    heater_power_state: bool
    ina_voltage: float
    ina_current: float
    ina_power: float
    ina_power_fault: bool
    charge_disabled: bool
    def __init__(self, temperature: _Optional[float] = ..., is_started: bool = ..., meteo: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataMeteo, _Mapping]] = ..., voltage: _Optional[float] = ..., heater_power_state: bool = ..., ina_voltage: _Optional[float] = ..., ina_current: _Optional[float] = ..., ina_power: _Optional[float] = ..., ina_power_fault: bool = ..., charge_disabled: bool = ...) -> None: ...
