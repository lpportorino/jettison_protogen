import jon_shared_data_types_pb2 as _jon_shared_data_types_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("measure", "scan_on", "scan_off", "start", "stop", "target_designator_off", "target_designator_on_mode_a", "target_designator_on_mode_b", "enable_fog_mode", "disable_fog_mode", "set_scan_mode", "new_session", "get_meteo", "refine_on", "refine_off")
    MEASURE_FIELD_NUMBER: _ClassVar[int]
    SCAN_ON_FIELD_NUMBER: _ClassVar[int]
    SCAN_OFF_FIELD_NUMBER: _ClassVar[int]
    START_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    TARGET_DESIGNATOR_OFF_FIELD_NUMBER: _ClassVar[int]
    TARGET_DESIGNATOR_ON_MODE_A_FIELD_NUMBER: _ClassVar[int]
    TARGET_DESIGNATOR_ON_MODE_B_FIELD_NUMBER: _ClassVar[int]
    ENABLE_FOG_MODE_FIELD_NUMBER: _ClassVar[int]
    DISABLE_FOG_MODE_FIELD_NUMBER: _ClassVar[int]
    SET_SCAN_MODE_FIELD_NUMBER: _ClassVar[int]
    NEW_SESSION_FIELD_NUMBER: _ClassVar[int]
    GET_METEO_FIELD_NUMBER: _ClassVar[int]
    REFINE_ON_FIELD_NUMBER: _ClassVar[int]
    REFINE_OFF_FIELD_NUMBER: _ClassVar[int]
    measure: Measure
    scan_on: ScanOn
    scan_off: ScanOff
    start: Start
    stop: Stop
    target_designator_off: TargetDesignatorOff
    target_designator_on_mode_a: TargetDesignatorOnModeA
    target_designator_on_mode_b: TargetDesignatorOnModeB
    enable_fog_mode: EnableFogMode
    disable_fog_mode: DisableFogMode
    set_scan_mode: SetScanMode
    new_session: NewSession
    get_meteo: GetMeteo
    refine_on: RefineOn
    refine_off: RefineOff
    def __init__(self, measure: _Optional[_Union[Measure, _Mapping]] = ..., scan_on: _Optional[_Union[ScanOn, _Mapping]] = ..., scan_off: _Optional[_Union[ScanOff, _Mapping]] = ..., start: _Optional[_Union[Start, _Mapping]] = ..., stop: _Optional[_Union[Stop, _Mapping]] = ..., target_designator_off: _Optional[_Union[TargetDesignatorOff, _Mapping]] = ..., target_designator_on_mode_a: _Optional[_Union[TargetDesignatorOnModeA, _Mapping]] = ..., target_designator_on_mode_b: _Optional[_Union[TargetDesignatorOnModeB, _Mapping]] = ..., enable_fog_mode: _Optional[_Union[EnableFogMode, _Mapping]] = ..., disable_fog_mode: _Optional[_Union[DisableFogMode, _Mapping]] = ..., set_scan_mode: _Optional[_Union[SetScanMode, _Mapping]] = ..., new_session: _Optional[_Union[NewSession, _Mapping]] = ..., get_meteo: _Optional[_Union[GetMeteo, _Mapping]] = ..., refine_on: _Optional[_Union[RefineOn, _Mapping]] = ..., refine_off: _Optional[_Union[RefineOff, _Mapping]] = ...) -> None: ...

class GetMeteo(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Start(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Stop(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Measure(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanOn(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ScanOff(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RefineOff(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class RefineOn(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class TargetDesignatorOff(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class TargetDesignatorOnModeA(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class TargetDesignatorOnModeB(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnableFogMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class DisableFogMode(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SetScanMode(_message.Message):
    __slots__ = ("mode",)
    MODE_FIELD_NUMBER: _ClassVar[int]
    mode: _jon_shared_data_types_pb2.JonGuiDataLrfScanModes
    def __init__(self, mode: _Optional[_Union[_jon_shared_data_types_pb2.JonGuiDataLrfScanModes, str]] = ...) -> None: ...

class NewSession(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
