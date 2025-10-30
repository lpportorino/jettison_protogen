from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("show_default_screen", "show_lrf_measure_screen", "show_lrf_result_screen", "show_lrf_result_simplified_screen", "enable_heat_osd", "disable_heat_osd", "enable_day_osd", "disable_day_osd")
    SHOW_DEFAULT_SCREEN_FIELD_NUMBER: _ClassVar[int]
    SHOW_LRF_MEASURE_SCREEN_FIELD_NUMBER: _ClassVar[int]
    SHOW_LRF_RESULT_SCREEN_FIELD_NUMBER: _ClassVar[int]
    SHOW_LRF_RESULT_SIMPLIFIED_SCREEN_FIELD_NUMBER: _ClassVar[int]
    ENABLE_HEAT_OSD_FIELD_NUMBER: _ClassVar[int]
    DISABLE_HEAT_OSD_FIELD_NUMBER: _ClassVar[int]
    ENABLE_DAY_OSD_FIELD_NUMBER: _ClassVar[int]
    DISABLE_DAY_OSD_FIELD_NUMBER: _ClassVar[int]
    show_default_screen: ShowDefaultScreen
    show_lrf_measure_screen: ShowLRFMeasureScreen
    show_lrf_result_screen: ShowLRFResultScreen
    show_lrf_result_simplified_screen: ShowLRFResultSimplifiedScreen
    enable_heat_osd: EnableHeatOSD
    disable_heat_osd: DisableHeatOSD
    enable_day_osd: EnableDayOSD
    disable_day_osd: DisableDayOSD
    def __init__(self, show_default_screen: _Optional[_Union[ShowDefaultScreen, _Mapping]] = ..., show_lrf_measure_screen: _Optional[_Union[ShowLRFMeasureScreen, _Mapping]] = ..., show_lrf_result_screen: _Optional[_Union[ShowLRFResultScreen, _Mapping]] = ..., show_lrf_result_simplified_screen: _Optional[_Union[ShowLRFResultSimplifiedScreen, _Mapping]] = ..., enable_heat_osd: _Optional[_Union[EnableHeatOSD, _Mapping]] = ..., disable_heat_osd: _Optional[_Union[DisableHeatOSD, _Mapping]] = ..., enable_day_osd: _Optional[_Union[EnableDayOSD, _Mapping]] = ..., disable_day_osd: _Optional[_Union[DisableDayOSD, _Mapping]] = ...) -> None: ...

class ShowDefaultScreen(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ShowLRFMeasureScreen(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ShowLRFResultScreen(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ShowLRFResultSimplifiedScreen(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnableHeatOSD(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableHeatOSD(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnableDayOSD(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableDayOSD(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
