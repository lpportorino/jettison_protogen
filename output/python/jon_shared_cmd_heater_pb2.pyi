from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

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
    __slots__ = ("targets", "temp_error")
    TARGETS_FIELD_NUMBER: _ClassVar[int]
    TEMP_ERROR_FIELD_NUMBER: _ClassVar[int]
    targets: _containers.RepeatedScalarFieldContainer[float]
    temp_error: _containers.RepeatedScalarFieldContainer[float]
    def __init__(self, targets: _Optional[_Iterable[float]] = ..., temp_error: _Optional[_Iterable[float]] = ...) -> None: ...

class GetStatus(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
