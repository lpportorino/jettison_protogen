import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("start_all", "stop_all", "reboot", "power_off", "localization", "reset_configs", "start_rec", "stop_rec", "mark_rec_important", "unmark_rec_important", "enter_transport", "geodesic_mode_enable", "geodesic_mode_disable")
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
    def __init__(self, start_all: _Optional[_Union[StartALl, _Mapping]] = ..., stop_all: _Optional[_Union[StopALl, _Mapping]] = ..., reboot: _Optional[_Union[Reboot, _Mapping]] = ..., power_off: _Optional[_Union[PowerOff, _Mapping]] = ..., localization: _Optional[_Union[SetLocalization, _Mapping]] = ..., reset_configs: _Optional[_Union[ResetConfigs, _Mapping]] = ..., start_rec: _Optional[_Union[StartRec, _Mapping]] = ..., stop_rec: _Optional[_Union[StopRec, _Mapping]] = ..., mark_rec_important: _Optional[_Union[MarkRecImportant, _Mapping]] = ..., unmark_rec_important: _Optional[_Union[UnmarkRecImportant, _Mapping]] = ..., enter_transport: _Optional[_Union[EnterTransport, _Mapping]] = ..., geodesic_mode_enable: _Optional[_Union[EnableGeodesicMode, _Mapping]] = ..., geodesic_mode_disable: _Optional[_Union[DisableGeodesicMode, _Mapping]] = ...) -> None: ...

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
