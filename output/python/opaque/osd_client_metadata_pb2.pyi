from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class OsdClientMetadata(_message.Message):
    __slots__ = ("canvas_width_px", "canvas_height_px", "device_pixel_ratio", "osd_buffer_width", "osd_buffer_height")
    CANVAS_WIDTH_PX_FIELD_NUMBER: _ClassVar[int]
    CANVAS_HEIGHT_PX_FIELD_NUMBER: _ClassVar[int]
    DEVICE_PIXEL_RATIO_FIELD_NUMBER: _ClassVar[int]
    OSD_BUFFER_WIDTH_FIELD_NUMBER: _ClassVar[int]
    OSD_BUFFER_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    canvas_width_px: int
    canvas_height_px: int
    device_pixel_ratio: float
    osd_buffer_width: int
    osd_buffer_height: int
    def __init__(self, canvas_width_px: _Optional[int] = ..., canvas_height_px: _Optional[int] = ..., device_pixel_ratio: _Optional[float] = ..., osd_buffer_width: _Optional[int] = ..., osd_buffer_height: _Optional[int] = ...) -> None: ...
