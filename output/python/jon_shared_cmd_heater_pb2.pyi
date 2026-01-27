from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "set_heating", "get_status")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    SET_HEATING_FIELD_NUMBER: _ClassVar[int]
    GET_STATUS_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    set_heating: SetHeating
    get_status: GetStatus
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., set_heating: _Optional[_Union[SetHeating, _Mapping]] = ..., get_status: _Optional[_Union[GetStatus, _Mapping]] = ...) -> None: ...

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
