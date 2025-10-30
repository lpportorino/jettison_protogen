from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Root(_message.Message):
    __slots__ = ("day", "heat")
    DAY_FIELD_NUMBER: _ClassVar[int]
    HEAT_FIELD_NUMBER: _ClassVar[int]
    day: Offsets
    heat: Offsets
    def __init__(self, day: _Optional[_Union[Offsets, _Mapping]] = ..., heat: _Optional[_Union[Offsets, _Mapping]] = ...) -> None: ...

class Offsets(_message.Message):
    __slots__ = ("set", "save", "reset", "shift")
    SET_FIELD_NUMBER: _ClassVar[int]
    SAVE_FIELD_NUMBER: _ClassVar[int]
    RESET_FIELD_NUMBER: _ClassVar[int]
    SHIFT_FIELD_NUMBER: _ClassVar[int]
    set: SetOffsets
    save: SaveOffsets
    reset: ResetOffsets
    shift: ShiftOffsetsBy
    def __init__(self, set: _Optional[_Union[SetOffsets, _Mapping]] = ..., save: _Optional[_Union[SaveOffsets, _Mapping]] = ..., reset: _Optional[_Union[ResetOffsets, _Mapping]] = ..., shift: _Optional[_Union[ShiftOffsetsBy, _Mapping]] = ...) -> None: ...

class SetOffsets(_message.Message):
    __slots__ = ("x", "y")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ...) -> None: ...

class ShiftOffsetsBy(_message.Message):
    __slots__ = ("x", "y")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ...) -> None: ...

class ResetOffsets(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class SaveOffsets(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...
