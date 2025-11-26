from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start", "stop", "set_magnetic_declination", "set_offset_angle_azimuth", "set_offset_angle_elevation", "set_use_rotary_position", "start_calibrate_long", "start_calibrate_short", "calibrate_next", "calibrate_cencel", "get_meteo")
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    SET_MAGNETIC_DECLINATION_FIELD_NUMBER: _ClassVar[int]
    SET_OFFSET_ANGLE_AZIMUTH_FIELD_NUMBER: _ClassVar[int]
    SET_OFFSET_ANGLE_ELEVATION_FIELD_NUMBER: _ClassVar[int]
    SET_USE_ROTARY_POSITION_FIELD_NUMBER: _ClassVar[int]
    START_CALIBRATE_LONG_FIELD_NUMBER: _ClassVar[int]
    START_CALIBRATE_SHORT_FIELD_NUMBER: _ClassVar[int]
    CALIBRATE_NEXT_FIELD_NUMBER: _ClassVar[int]
    CALIBRATE_CENCEL_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    start: Start
    stop: Stop
    set_magnetic_declination: SetMagneticDeclination
    set_offset_angle_azimuth: SetOffsetAngleAzimuth
    set_offset_angle_elevation: SetOffsetAngleElevation
    set_use_rotary_position: SetUseRotaryPosition
    start_calibrate_long: CalibrateStartLong
    start_calibrate_short: CalibrateStartShort
    calibrate_next: CalibrateNext
    calibrate_cencel: CalibrateCencel
    get_meteo: GetMeteo
    def __init__(self, start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., set_magnetic_declination: _Optional[_Union[SetMagneticDeclination, _Mapping]] = ..., set_offset_angle_azimuth: _Optional[_Union[SetOffsetAngleAzimuth, _Mapping]] = ..., set_offset_angle_elevation: _Optional[_Union[SetOffsetAngleElevation, _Mapping]] = ..., set_use_rotary_position: _Optional[_Union[SetUseRotaryPosition, _Mapping]] = ..., start_calibrate_long: _Optional[_Union[CalibrateStartLong, _Mapping]] = ..., start_calibrate_short: _Optional[_Union[CalibrateStartShort, _Mapping]] = ..., calibrate_next: _Optional[_Union[CalibrateNext, _Mapping]] = ..., calibrate_cencel: _Optional[_Union[CalibrateCencel, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ...) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Next(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class CalibrateStartLong(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class CalibrateStartShort(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class CalibrateNext(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class CalibrateCencel(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetMagneticDeclination(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetOffsetAngleAzimuth(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetOffsetAngleElevation(_message.Message):
    __slots__ = ("value",)
    VALUE_FIELD_NUMBER: _ClassVar[int]
    value: float
    def __init__(self, value: _Optional[float] = ...) -> None: ...

class SetUseRotaryPosition(_message.Message):
    __slots__ = ("flag",)
    FLAG_FIELD_NUMBER: _ClassVar[int]
    flag: bool
    def __init__(self, flag: bool = ...) -> None: ...
