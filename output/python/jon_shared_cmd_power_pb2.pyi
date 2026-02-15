from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("set_channel", "set_all", "set_alert_threshold")
    SET_CHANNEL_FIELD_NUMBER: _ClassVar[int]
    SET_ALL_FIELD_NUMBER: _ClassVar[int]
    SET_ALERT_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    set_channel: SetChannel
    set_all: SetAll
    set_alert_threshold: SetAlertThreshold
    def __init__(self, set_channel: _Optional[_Union[SetChannel, _Mapping]] = ..., set_all: _Optional[_Union[SetAll, _Mapping]] = ..., set_alert_threshold: _Optional[_Union[SetAlertThreshold, _Mapping]] = ...) -> None: ...

class SetChannel(_message.Message):
    __slots__ = ("channel", "power_on")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    POWER_ON_FIELD_NUMBER: _ClassVar[int]
    channel: int
    power_on: bool
    def __init__(self, channel: _Optional[int] = ..., power_on: bool = ...) -> None: ...

class SetAll(_message.Message):
    __slots__ = ("power_on",)
    POWER_ON_FIELD_NUMBER: _ClassVar[int]
    power_on: bool
    def __init__(self, power_on: bool = ...) -> None: ...

class SetAlertThreshold(_message.Message):
    __slots__ = ("channel", "threshold_ma")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    THRESHOLD_MA_FIELD_NUMBER: _ClassVar[int]
    channel: int
    threshold_ma: int
    def __init__(self, channel: _Optional[int] = ..., threshold_ma: _Optional[int] = ...) -> None: ...
