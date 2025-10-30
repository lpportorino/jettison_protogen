from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "set_manual_position", "set_use_manual_position", "get_meteo")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    SET_MANUAL_POSITION_FIELD_NUMBER: _ClassVar[int]
    SET_USE_MANUAL_POSITION_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    set_manual_position: SetManualPosition
    set_use_manual_position: SetUseManualPosition
    get_meteo: GetMeteo
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., set_manual_position: _Optional[_Union[SetManualPosition, _Mapping]] = ..., set_use_manual_position: _Optional[_Union[SetUseManualPosition, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ...) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetUseManualPosition(_message.Message):
    __slots__ = ("flag",)
    FLAG_FIELD_NUMBER: _ClassVar[int]
    flag: bool
    def __init__(self, flag: bool = ...) -> None: ...

class SetManualPosition(_message.Message):
    __slots__ = ("latitude", "longitude", "altitude")
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    ALTITUDE_FIELD_NUMBER: _ClassVar[int]
    latitude: float
    longitude: float
    altitude: float
    def __init__(self, latitude: _Optional[float] = ..., longitude: _Optional[float] = ..., altitude: _Optional[float] = ...) -> None: ...
