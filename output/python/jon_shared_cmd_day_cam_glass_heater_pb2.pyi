from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "turn_on", "turn_off", "get_meteo")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    TURN_ON_FIELD_NUMBER: _ClassVar[int]
    TURN_OFF_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    turn_on: TurnOn
    turn_off: TurnOff
    get_meteo: GetMeteo
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., turn_on: _Optional[_Union[TurnOn, _Mapping]] = ..., turn_off: _Optional[_Union[TurnOff, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ...) -> None: ...

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
