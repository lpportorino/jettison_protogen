import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start_all", "stop_all", "reboot", "power_off", "localization", "reset_configs", "start_rec", "stop_rec", "mark_rec_important", "unmark_rec_important", "enter_transport", "geodesic_mode_enable", "geodesic_mode_disable", "save_factory_defaults", "wipe_user_data", "step_year", "step_month", "step_day", "step_hour", "step_minute", "step_second", "enable_manual_time", "disable_manual_time", "set_time_zone", "step_time_zone", "set_time_and_zone")
    START_ALL_FIELD_NUMBER: _ClassVar[int]
    STOP_ALL_FIELD_NUMBER: _ClassVar[int]
    REBOOT_FIELD_NUMBER: _ClassVar[int]
    POWER_OFF_FIELD_NUMBER: _ClassVar[int]
    LOCALIZATION_FIELD_NUMBER: _ClassVar[int]
    RESET_CONFIGS_FIELD_NUMBER: _ClassVar[int]
    START_REC_FIELD_NUMBER: _ClassVar[int]
    STOP_REC_FIELD_NUMBER: _ClassVar[int]
    MARK_REC_IMPORTANT_FIELD_NUMBER: _ClassVar[int]
    UNMARK_REC_IMPORTANT_FIELD_NUMBER: _ClassVar[int]
    ENTER_TRANSPORT_FIELD_NUMBER: _ClassVar[int]
    GEODESIC_MODE_ENABLE_FIELD_NUMBER: _ClassVar[int]
    GEODESIC_MODE_DISABLE_FIELD_NUMBER: _ClassVar[int]
    SAVE_FACTORY_DEFAULTS_FIELD_NUMBER: _ClassVar[int]
    WIPE_USER_DATA_FIELD_NUMBER: _ClassVar[int]
    STEP_YEAR_FIELD_NUMBER: _ClassVar[int]
    STEP_MONTH_FIELD_NUMBER: _ClassVar[int]
    STEP_DAY_FIELD_NUMBER: _ClassVar[int]
    STEP_HOUR_FIELD_NUMBER: _ClassVar[int]
    STEP_MINUTE_FIELD_NUMBER: _ClassVar[int]
    STEP_SECOND_FIELD_NUMBER: _ClassVar[int]
    ENABLE_MANUAL_TIME_FIELD_NUMBER: _ClassVar[int]
    DISABLE_MANUAL_TIME_FIELD_NUMBER: _ClassVar[int]
    SET_TIME_ZONE_FIELD_NUMBER: _ClassVar[int]
    STEP_TIME_ZONE_FIELD_NUMBER: _ClassVar[int]
    SET_TIME_AND_ZONE_FIELD_NUMBER: _ClassVar[int]
    start_all: StartALl
    stop_all: StopALl
    reboot: Reboot
    power_off: PowerOff
    localization: SetLocalization
    reset_configs: ResetConfigs
    start_rec: StartRec
    stop_rec: StopRec
    mark_rec_important: MarkRecImportant
    unmark_rec_important: UnmarkRecImportant
    enter_transport: EnterTransport
    geodesic_mode_enable: EnableGeodesicMode
    geodesic_mode_disable: DisableGeodesicMode
    save_factory_defaults: SaveFactoryDefaults
    wipe_user_data: WipeUserData
    step_year: StepYear
    step_month: StepMonth
    step_day: StepDay
    step_hour: StepHour
    step_minute: StepMinute
    step_second: StepSecond
    enable_manual_time: EnableManualTime
    disable_manual_time: DisableManualTime
    set_time_zone: SetTimeZone
    step_time_zone: StepTimeZone
    set_time_and_zone: SetTimeAndZone
    def __init__(self, start_all: _Optional[_Union[StartALl, _Mapping]] = ..., stop_all: _Optional[_Union[StopALl, _Mapping]] = ..., reboot: _Optional[_Union[Reboot, _Mapping]] = ..., power_off: _Optional[_Union[PowerOff, _Mapping]] = ..., localization: _Optional[_Union[SetLocalization, _Mapping]] = ..., reset_configs: _Optional[_Union[ResetConfigs, _Mapping]] = ..., start_rec: _Optional[_Union[StartRec, _Mapping]] = ..., stop_rec: _Optional[_Union[StopRec, _Mapping]] = ..., mark_rec_important: _Optional[_Union[MarkRecImportant, _Mapping]] = ..., unmark_rec_important: _Optional[_Union[UnmarkRecImportant, _Mapping]] = ..., enter_transport: _Optional[_Union[EnterTransport, _Mapping]] = ..., geodesic_mode_enable: _Optional[_Union[EnableGeodesicMode, _Mapping]] = ..., geodesic_mode_disable: _Optional[_Union[DisableGeodesicMode, _Mapping]] = ..., save_factory_defaults: _Optional[_Union[SaveFactoryDefaults, _Mapping]] = ..., wipe_user_data: _Optional[_Union[WipeUserData, _Mapping]] = ..., step_year: _Optional[_Union[StepYear, _Mapping]] = ..., step_month: _Optional[_Union[StepMonth, _Mapping]] = ..., step_day: _Optional[_Union[StepDay, _Mapping]] = ..., step_hour: _Optional[_Union[StepHour, _Mapping]] = ..., step_minute: _Optional[_Union[StepMinute, _Mapping]] = ..., step_second: _Optional[_Union[StepSecond, _Mapping]] = ..., enable_manual_time: _Optional[_Union[EnableManualTime, _Mapping]] = ..., disable_manual_time: _Optional[_Union[DisableManualTime, _Mapping]] = ..., set_time_zone: _Optional[_Union[SetTimeZone, _Mapping]] = ..., step_time_zone: _Optional[_Union[StepTimeZone, _Mapping]] = ..., set_time_and_zone: _Optional[_Union[SetTimeAndZone, _Mapping]] = ...) -> None: ...

class StartALl(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StopALl(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Reboot(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class PowerOff(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ResetConfigs(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SaveFactoryDefaults(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class WipeUserData(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StartRec(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class StopRec(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class MarkRecImportant(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class UnmarkRecImportant(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnterTransport(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnableGeodesicMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableGeodesicMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetLocalization(_message.Message):
    __slots__ = ("loc",)
    LOC_FIELD_NUMBER: _ClassVar[int]
    loc: _jon_shared_data_types_pb2.JonGuiDataSystemLocalizations
    def __init__(self, loc: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataSystemLocalizations, str]] = ...) -> None: ...

class StepYear(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class StepMonth(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class StepDay(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class StepHour(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class StepMinute(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class StepSecond(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class EnableManualTime(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableManualTime(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetTimeZone(_message.Message):
    __slots__ = ("zone_id",)
    ZONE_ID_FIELD_NUMBER: _ClassVar[int]
    zone_id: int
    def __init__(self, zone_id: _Optional[int] = ...) -> None: ...

class StepTimeZone(_message.Message):
    __slots__ = ("offset",)
    OFFSET_FIELD_NUMBER: _ClassVar[int]
    offset: int
    def __init__(self, offset: _Optional[int] = ...) -> None: ...

class SetTimeAndZone(_message.Message):
    __slots__ = ("timestamp", "zone_id")
    TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    ZONE_ID_FIELD_NUMBER: _ClassVar[int]
    timestamp: int
    zone_id: int
    def __init__(self, timestamp: _Optional[int] = ..., zone_id: _Optional[int] = ...) -> None: ...
