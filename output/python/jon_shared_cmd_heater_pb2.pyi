from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("set_heating", "get_status")
    SET_HEATING_FIELD_NUMBER: _ClassVar[int]
    GET_STATUS_FIELD_NUMBER: _ClassVar[int]
    set_heating: SetHeating
    get_status: GetStatus
    def __init__(self, set_heating: _Optional[_Union[SetHeating, _Mapping]] = ..., get_status: _Optional[_Union[GetStatus, _Mapping]] = ...) -> None: ...

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
