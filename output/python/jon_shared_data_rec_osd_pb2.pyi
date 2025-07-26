import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataRecOsd(_message.Message):
    __slots__ = ("screen", "heat_osd_enabled", "day_osd_enabled", "heat_crosshair_offset_horizontal", "heat_crosshair_offset_vertical", "day_crosshair_offset_horizontal", "day_crosshair_offset_vertical")
    SCREEN_FIELD_NUMBER: _ClassVar[int]
    HEAT_OSD_ENABLED_FIELD_NUMBER: _ClassVar[int]
    DAY_OSD_ENABLED_FIELD_NUMBER: _ClassVar[int]
    HEAT_CROSSHAIR_OFFSET_HORIZONTAL_FIELD_NUMBER: _ClassVar[int]
    HEAT_CROSSHAIR_OFFSET_VERTICAL_FIELD_NUMBER: _ClassVar[int]
    DAY_CROSSHAIR_OFFSET_HORIZONTAL_FIELD_NUMBER: _ClassVar[int]
    DAY_CROSSHAIR_OFFSET_VERTICAL_FIELD_NUMBER: _ClassVar[int]
    screen: _jon_shared_data_types_pb2.JonGuiDataRecOsdScreen
    heat_osd_enabled: bool
    day_osd_enabled: bool
    heat_crosshair_offset_horizontal: int
    heat_crosshair_offset_vertical: int
    day_crosshair_offset_horizontal: int
    day_crosshair_offset_vertical: int
    def __init__(self, screen: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataRecOsdScreen, str]] = ..., heat_osd_enabled: bool = ..., day_osd_enabled: bool = ..., heat_crosshair_offset_horizontal: _Optional[int] = ..., heat_crosshair_offset_vertical: _Optional[int] = ..., day_crosshair_offset_horizontal: _Optional[int] = ..., day_crosshair_offset_vertical: _Optional[int] = ...) -> None: ...
