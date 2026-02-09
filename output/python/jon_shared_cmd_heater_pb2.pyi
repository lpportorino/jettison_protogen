from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "set_heating", "get_status", "enable_automatic_control", "disable_automatic_control", "set_automatic_control_params")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    SET_HEATING_FIELD_NUMBER: _ClassVar[int]
    GET_STATUS_FIELD_NUMBER: _ClassVar[int]
    ENABLE_AUTOMATIC_CONTROL_FIELD_NUMBER: _ClassVar[int]
    DISABLE_AUTOMATIC_CONTROL_FIELD_NUMBER: _ClassVar[int]
    SET_AUTOMATIC_CONTROL_PARAMS_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    set_heating: SetHeating
    get_status: GetStatus
    enable_automatic_control: EnableAutomaticControl
    disable_automatic_control: DisableAutomaticControl
    set_automatic_control_params: SetAutomaticControlParams
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., set_heating: _Optional[_Union[SetHeating, _Mapping]] = ..., get_status: _Optional[_Union[GetStatus, _Mapping]] = ..., enable_automatic_control: _Optional[_Union[EnableAutomaticControl, _Mapping]] = ..., disable_automatic_control: _Optional[_Union[DisableAutomaticControl, _Mapping]] = ..., set_automatic_control_params: _Optional[_Union[SetAutomaticControlParams, _Mapping]] = ...) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetHeating(_message.Message):
    __slots__ = ("target_0", "target_1", "target_2", "temp_error_0", "temp_error_1", "temp_error_2")
    TARGET_0_FIELD_NUMBER: _ClassVar[int]
    TARGET_1_FIELD_NUMBER: _ClassVar[int]
    TARGET_2_FIELD_NUMBER: _ClassVar[int]
    TEMP_ERROR_0_FIELD_NUMBER: _ClassVar[int]
    TEMP_ERROR_1_FIELD_NUMBER: _ClassVar[int]
    TEMP_ERROR_2_FIELD_NUMBER: _ClassVar[int]
    target_0: float
    target_1: float
    target_2: float
    temp_error_0: float
    temp_error_1: float
    temp_error_2: float
    def __init__(self, target_0: _Optional[float] = ..., target_1: _Optional[float] = ..., target_2: _Optional[float] = ..., temp_error_0: _Optional[float] = ..., temp_error_1: _Optional[float] = ..., temp_error_2: _Optional[float] = ...) -> None: ...

class GetStatus(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnableAutomaticControl(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableAutomaticControl(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class AutomaticControlChannelParams(_message.Message):
    __slots__ = ("target_temperature",)
    TARGET_TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    target_temperature: float
    def __init__(self, target_temperature: _Optional[float] = ...) -> None: ...

class SetAutomaticControlParams(_message.Message):
    __slots__ = ("channel_0", "channel_1", "channel_2")
    CHANNEL_0_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_1_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_2_FIELD_NUMBER: _ClassVar[int]
    channel_0: AutomaticControlChannelParams
    channel_1: AutomaticControlChannelParams
    channel_2: AutomaticControlChannelParams
    def __init__(self, channel_0: _Optional[_Union[AutomaticControlChannelParams, _Mapping]] = ..., channel_1: _Optional[_Union[AutomaticControlChannelParams, _Mapping]] = ..., channel_2: _Optional[_Union[AutomaticControlChannelParams, _Mapping]] = ...) -> None: ...
