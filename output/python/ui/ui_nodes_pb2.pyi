from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class NodeSchemaVersion(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    NODE_SCHEMA_VERSION_UNSPECIFIED: _ClassVar[NodeSchemaVersion]
    NODE_SCHEMA_VERSION_V1: _ClassVar[NodeSchemaVersion]
NODE_SCHEMA_VERSION_UNSPECIFIED: NodeSchemaVersion
NODE_SCHEMA_VERSION_V1: NodeSchemaVersion

class FixedPointScale(_message.Message):
    __slots__ = ("scale",)
    SCALE_FIELD_NUMBER: _ClassVar[int]
    scale: int
    def __init__(self, scale: _Optional[int] = ...) -> None: ...

class StateBinding(_message.Message):
    __slots__ = ("state_field_path", "subject_name", "scale")
    STATE_FIELD_PATH_FIELD_NUMBER: _ClassVar[int]
    SUBJECT_NAME_FIELD_NUMBER: _ClassVar[int]
    SCALE_FIELD_NUMBER: _ClassVar[int]
    state_field_path: str
    subject_name: str
    scale: FixedPointScale
    def __init__(self, state_field_path: _Optional[str] = ..., subject_name: _Optional[str] = ..., scale: _Optional[_Union[FixedPointScale, _Mapping]] = ...) -> None: ...

class CommandBinding(_message.Message):
    __slots__ = ("command_id", "scale")
    COMMAND_ID_FIELD_NUMBER: _ClassVar[int]
    SCALE_FIELD_NUMBER: _ClassVar[int]
    command_id: str
    scale: FixedPointScale
    def __init__(self, command_id: _Optional[str] = ..., scale: _Optional[_Union[FixedPointScale, _Mapping]] = ...) -> None: ...

class SliderControl(_message.Message):
    __slots__ = ("version", "title", "state", "command", "min_value", "max_value")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    MIN_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_VALUE_FIELD_NUMBER: _ClassVar[int]
    version: int
    title: str
    state: StateBinding
    command: CommandBinding
    min_value: int
    max_value: int
    def __init__(self, version: _Optional[int] = ..., title: _Optional[str] = ..., state: _Optional[_Union[StateBinding, _Mapping]] = ..., command: _Optional[_Union[CommandBinding, _Mapping]] = ..., min_value: _Optional[int] = ..., max_value: _Optional[int] = ...) -> None: ...

class ActionButton(_message.Message):
    __slots__ = ("version", "title", "command")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    version: int
    title: str
    command: CommandBinding
    def __init__(self, version: _Optional[int] = ..., title: _Optional[str] = ..., command: _Optional[_Union[CommandBinding, _Mapping]] = ...) -> None: ...

class ToggleControl(_message.Message):
    __slots__ = ("version", "title", "command_on", "command_off", "state")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_ON_FIELD_NUMBER: _ClassVar[int]
    COMMAND_OFF_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    version: int
    title: str
    command_on: CommandBinding
    command_off: CommandBinding
    state: StateBinding
    def __init__(self, version: _Optional[int] = ..., title: _Optional[str] = ..., command_on: _Optional[_Union[CommandBinding, _Mapping]] = ..., command_off: _Optional[_Union[CommandBinding, _Mapping]] = ..., state: _Optional[_Union[StateBinding, _Mapping]] = ...) -> None: ...

class EnumOption(_message.Message):
    __slots__ = ("label", "value")
    LABEL_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    label: str
    value: int
    def __init__(self, label: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...

class EnumPicker(_message.Message):
    __slots__ = ("version", "title", "command", "options")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    version: int
    title: str
    command: CommandBinding
    options: _containers.RepeatedCompositeFieldContainer[EnumOption]
    def __init__(self, version: _Optional[int] = ..., title: _Optional[str] = ..., command: _Optional[_Union[CommandBinding, _Mapping]] = ..., options: _Optional[_Iterable[_Union[EnumOption, _Mapping]]] = ...) -> None: ...

class StepperControl(_message.Message):
    __slots__ = ("version", "title", "command_increment", "command_decrement")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_INCREMENT_FIELD_NUMBER: _ClassVar[int]
    COMMAND_DECREMENT_FIELD_NUMBER: _ClassVar[int]
    version: int
    title: str
    command_increment: CommandBinding
    command_decrement: CommandBinding
    def __init__(self, version: _Optional[int] = ..., title: _Optional[str] = ..., command_increment: _Optional[_Union[CommandBinding, _Mapping]] = ..., command_decrement: _Optional[_Union[CommandBinding, _Mapping]] = ...) -> None: ...

class ShiftStepper(_message.Message):
    __slots__ = ("version", "title", "command", "step")
    VERSION_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    version: int
    title: str
    command: CommandBinding
    step: int
    def __init__(self, version: _Optional[int] = ..., title: _Optional[str] = ..., command: _Optional[_Union[CommandBinding, _Mapping]] = ..., step: _Optional[int] = ...) -> None: ...
