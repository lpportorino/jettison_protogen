from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class CANDevice(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAN_DEVICE_UNSPECIFIED: _ClassVar[CANDevice]
    CAN_DEVICE_UNKNOWN: _ClassVar[CANDevice]
    CAN_DEVICE_COMPASS: _ClassVar[CANDevice]
    CAN_DEVICE_COMPASS_DATA: _ClassVar[CANDevice]
    CAN_DEVICE_GPS_CTRL: _ClassVar[CANDevice]
    CAN_DEVICE_GPS_DATA: _ClassVar[CANDevice]
    CAN_DEVICE_LRF_CTRL: _ClassVar[CANDevice]
    CAN_DEVICE_LRF_DATA: _ClassVar[CANDevice]
    CAN_DEVICE_DAY_CAM: _ClassVar[CANDevice]
    CAN_DEVICE_DAY_GLASS_HEAT_CTRL: _ClassVar[CANDevice]
    CAN_DEVICE_DAY_GLASS_HEAT_DATA: _ClassVar[CANDevice]
    CAN_DEVICE_THERM_CTRL: _ClassVar[CANDevice]
    CAN_DEVICE_THERM_CAM: _ClassVar[CANDevice]

class CANDirection(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CAN_DIRECTION_UNSPECIFIED: _ClassVar[CANDirection]
    CAN_DIRECTION_TX: _ClassVar[CANDirection]
    CAN_DIRECTION_RX: _ClassVar[CANDirection]
CAN_DEVICE_UNSPECIFIED: CANDevice
CAN_DEVICE_UNKNOWN: CANDevice
CAN_DEVICE_COMPASS: CANDevice
CAN_DEVICE_COMPASS_DATA: CANDevice
CAN_DEVICE_GPS_CTRL: CANDevice
CAN_DEVICE_GPS_DATA: CANDevice
CAN_DEVICE_LRF_CTRL: CANDevice
CAN_DEVICE_LRF_DATA: CANDevice
CAN_DEVICE_DAY_CAM: CANDevice
CAN_DEVICE_DAY_GLASS_HEAT_CTRL: CANDevice
CAN_DEVICE_DAY_GLASS_HEAT_DATA: CANDevice
CAN_DEVICE_THERM_CTRL: CANDevice
CAN_DEVICE_THERM_CAM: CANDevice
CAN_DIRECTION_UNSPECIFIED: CANDirection
CAN_DIRECTION_TX: CANDirection
CAN_DIRECTION_RX: CANDirection

class CANFrame(_message.Message):
    __slots__ = ("timestamp_ms", "can_id", "direction", "device", "frame_type", "dlc", "data")
    TIMESTAMP_MS_FIELD_NUMBER: _ClassVar[int]
    CAN_ID_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    FRAME_TYPE_FIELD_NUMBER: _ClassVar[int]
    DLC_FIELD_NUMBER: _ClassVar[int]
    DATA_FIELD_NUMBER: _ClassVar[int]
    timestamp_ms: int
    can_id: int
    direction: CANDirection
    device: CANDevice
    frame_type: int
    dlc: int
    data: bytes
    def __init__(self, timestamp_ms: _Optional[int] = ..., can_id: _Optional[int] = ..., direction: _Optional[_Union[CANDirection, str]] = ..., device: _Optional[_Union[CANDevice, str]] = ..., frame_type: _Optional[int] = ..., dlc: _Optional[int] = ..., data: _Optional[bytes] = ...) -> None: ...

class CANFrameBatch(_message.Message):
    __slots__ = ("frames",)
    FRAMES_FIELD_NUMBER: _ClassVar[int]
    frames: _containers.RepeatedCompositeFieldContainer[CANFrame]
    def __init__(self, frames: _Optional[_Iterable[_Union[CANFrame, _Mapping]]] = ...) -> None: ...

class CANStreamFilter(_message.Message):
    __slots__ = ("devices", "directions", "interval_seconds")
    DEVICES_FIELD_NUMBER: _ClassVar[int]
    DIRECTIONS_FIELD_NUMBER: _ClassVar[int]
    INTERVAL_SECONDS_FIELD_NUMBER: _ClassVar[int]
    devices: _containers.RepeatedScalarFieldContainer[CANDevice]
    directions: _containers.RepeatedScalarFieldContainer[CANDirection]
    interval_seconds: float
    def __init__(self, devices: _Optional[_Iterable[_Union[CANDevice, str]]] = ..., directions: _Optional[_Iterable[_Union[CANDirection, str]]] = ..., interval_seconds: _Optional[float] = ...) -> None: ...

class CANStreamConnected(_message.Message):
    __slots__ = ("status", "filters")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    FILTERS_FIELD_NUMBER: _ClassVar[int]
    status: str
    filters: CANStreamFilter
    def __init__(self, status: _Optional[str] = ..., filters: _Optional[_Union[CANStreamFilter, _Mapping]] = ...) -> None: ...
