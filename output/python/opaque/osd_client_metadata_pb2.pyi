from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class OsdClientMetadata(_message.Message):
    __slots__ = ("canvas_width_px", "canvas_height_px", "device_pixel_ratio", "osd_buffer_width", "osd_buffer_height", "video_proxy_ndc_x", "video_proxy_ndc_y", "video_proxy_ndc_width", "video_proxy_ndc_height", "scale_factor", "is_sharp_mode", "theme_hue", "theme_chroma", "theme_lightness")
    CANVAS_WIDTH_PX_FIELD_NUMBER: _ClassVar[int]
    CANVAS_HEIGHT_PX_FIELD_NUMBER: _ClassVar[int]
    DEVICE_PIXEL_RATIO_FIELD_NUMBER: _ClassVar[int]
    OSD_BUFFER_WIDTH_FIELD_NUMBER: _ClassVar[int]
    OSD_BUFFER_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    VIDEO_PROXY_NDC_X_FIELD_NUMBER: _ClassVar[int]
    VIDEO_PROXY_NDC_Y_FIELD_NUMBER: _ClassVar[int]
    VIDEO_PROXY_NDC_WIDTH_FIELD_NUMBER: _ClassVar[int]
    VIDEO_PROXY_NDC_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    SCALE_FACTOR_FIELD_NUMBER: _ClassVar[int]
    IS_SHARP_MODE_FIELD_NUMBER: _ClassVar[int]
    THEME_HUE_FIELD_NUMBER: _ClassVar[int]
    THEME_CHROMA_FIELD_NUMBER: _ClassVar[int]
    THEME_LIGHTNESS_FIELD_NUMBER: _ClassVar[int]
    canvas_width_px: int
    canvas_height_px: int
    device_pixel_ratio: float
    osd_buffer_width: int
    osd_buffer_height: int
    video_proxy_ndc_x: float
    video_proxy_ndc_y: float
    video_proxy_ndc_width: float
    video_proxy_ndc_height: float
    scale_factor: float
    is_sharp_mode: bool
    theme_hue: float
    theme_chroma: float
    theme_lightness: float
    def __init__(self, canvas_width_px: _Optional[int] = ..., canvas_height_px: _Optional[int] = ..., device_pixel_ratio: _Optional[float] = ..., osd_buffer_width: _Optional[int] = ..., osd_buffer_height: _Optional[int] = ..., video_proxy_ndc_x: _Optional[float] = ..., video_proxy_ndc_y: _Optional[float] = ..., video_proxy_ndc_width: _Optional[float] = ..., video_proxy_ndc_height: _Optional[float] = ..., scale_factor: _Optional[float] = ..., is_sharp_mode: bool = ..., theme_hue: _Optional[float] = ..., theme_chroma: _Optional[float] = ..., theme_lightness: _Optional[float] = ...) -> None: ...
