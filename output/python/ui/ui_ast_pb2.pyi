from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class WidgetType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    WIDGET_OBJ: _ClassVar[WidgetType]
    WIDGET_BUTTON: _ClassVar[WidgetType]
    WIDGET_LABEL: _ClassVar[WidgetType]
    WIDGET_SLIDER: _ClassVar[WidgetType]
    WIDGET_IMAGE: _ClassVar[WidgetType]

class LayoutFlow(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LAYOUT_NONE: _ClassVar[LayoutFlow]
    LAYOUT_FLEX_ROW: _ClassVar[LayoutFlow]
    LAYOUT_FLEX_COLUMN: _ClassVar[LayoutFlow]

class StylePropertyType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PROP_BG_COLOR: _ClassVar[StylePropertyType]
    PROP_BG_OPA: _ClassVar[StylePropertyType]
    PROP_TEXT_COLOR: _ClassVar[StylePropertyType]
    PROP_TEXT_FONT: _ClassVar[StylePropertyType]
    PROP_BORDER_COLOR: _ClassVar[StylePropertyType]
    PROP_BORDER_WIDTH: _ClassVar[StylePropertyType]
    PROP_RADIUS: _ClassVar[StylePropertyType]
    PROP_PAD_ALL: _ClassVar[StylePropertyType]
    PROP_PAD_GAP: _ClassVar[StylePropertyType]
    PROP_WIDTH: _ClassVar[StylePropertyType]
    PROP_HEIGHT: _ClassVar[StylePropertyType]
    PROP_SHADOW: _ClassVar[StylePropertyType]
    PROP_PAD_HOR: _ClassVar[StylePropertyType]
    PROP_PAD_VER: _ClassVar[StylePropertyType]
    PROP_MARGIN_ALL: _ClassVar[StylePropertyType]
    PROP_BORDER_OPA: _ClassVar[StylePropertyType]
WIDGET_OBJ: WidgetType
WIDGET_BUTTON: WidgetType
WIDGET_LABEL: WidgetType
WIDGET_SLIDER: WidgetType
WIDGET_IMAGE: WidgetType
LAYOUT_NONE: LayoutFlow
LAYOUT_FLEX_ROW: LayoutFlow
LAYOUT_FLEX_COLUMN: LayoutFlow
PROP_BG_COLOR: StylePropertyType
PROP_BG_OPA: StylePropertyType
PROP_TEXT_COLOR: StylePropertyType
PROP_TEXT_FONT: StylePropertyType
PROP_BORDER_COLOR: StylePropertyType
PROP_BORDER_WIDTH: StylePropertyType
PROP_RADIUS: StylePropertyType
PROP_PAD_ALL: StylePropertyType
PROP_PAD_GAP: StylePropertyType
PROP_WIDTH: StylePropertyType
PROP_HEIGHT: StylePropertyType
PROP_SHADOW: StylePropertyType
PROP_PAD_HOR: StylePropertyType
PROP_PAD_VER: StylePropertyType
PROP_MARGIN_ALL: StylePropertyType
PROP_BORDER_OPA: StylePropertyType

class Screen(_message.Message):
    __slots__ = ("root",)
    ROOT_FIELD_NUMBER: _ClassVar[int]
    root: WidgetNode
    def __init__(self, root: _Optional[_Union[WidgetNode, _Mapping]] = ...) -> None: ...

class WidgetNode(_message.Message):
    __slots__ = ("type", "x", "y", "text", "bindings", "event", "layout", "children", "style_groups")
    class BindingsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    TYPE_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    BINDINGS_FIELD_NUMBER: _ClassVar[int]
    EVENT_FIELD_NUMBER: _ClassVar[int]
    LAYOUT_FIELD_NUMBER: _ClassVar[int]
    CHILDREN_FIELD_NUMBER: _ClassVar[int]
    STYLE_GROUPS_FIELD_NUMBER: _ClassVar[int]
    type: WidgetType
    x: int
    y: int
    text: str
    bindings: _containers.ScalarMap[str, str]
    event: EventBinding
    layout: Layout
    children: _containers.RepeatedCompositeFieldContainer[WidgetNode]
    style_groups: _containers.RepeatedCompositeFieldContainer[StyleGroup]
    def __init__(self, type: _Optional[_Union[WidgetType, str]] = ..., x: _Optional[int] = ..., y: _Optional[int] = ..., text: _Optional[str] = ..., bindings: _Optional[_Mapping[str, str]] = ..., event: _Optional[_Union[EventBinding, _Mapping]] = ..., layout: _Optional[_Union[Layout, _Mapping]] = ..., children: _Optional[_Iterable[_Union[WidgetNode, _Mapping]]] = ..., style_groups: _Optional[_Iterable[_Union[StyleGroup, _Mapping]]] = ...) -> None: ...

class EventBinding(_message.Message):
    __slots__ = ("event_name", "command_type", "float_value", "int_value")
    EVENT_NAME_FIELD_NUMBER: _ClassVar[int]
    COMMAND_TYPE_FIELD_NUMBER: _ClassVar[int]
    FLOAT_VALUE_FIELD_NUMBER: _ClassVar[int]
    INT_VALUE_FIELD_NUMBER: _ClassVar[int]
    event_name: str
    command_type: int
    float_value: float
    int_value: int
    def __init__(self, event_name: _Optional[str] = ..., command_type: _Optional[int] = ..., float_value: _Optional[float] = ..., int_value: _Optional[int] = ...) -> None: ...

class Layout(_message.Message):
    __slots__ = ("flow",)
    FLOW_FIELD_NUMBER: _ClassVar[int]
    flow: LayoutFlow
    def __init__(self, flow: _Optional[_Union[LayoutFlow, str]] = ...) -> None: ...

class StyleGroup(_message.Message):
    __slots__ = ("state_selector", "variants")
    STATE_SELECTOR_FIELD_NUMBER: _ClassVar[int]
    VARIANTS_FIELD_NUMBER: _ClassVar[int]
    state_selector: int
    variants: _containers.RepeatedCompositeFieldContainer[ResolvedStyle]
    def __init__(self, state_selector: _Optional[int] = ..., variants: _Optional[_Iterable[_Union[ResolvedStyle, _Mapping]]] = ...) -> None: ...

class ResolvedStyle(_message.Message):
    __slots__ = ("properties",)
    PROPERTIES_FIELD_NUMBER: _ClassVar[int]
    properties: _containers.RepeatedCompositeFieldContainer[StyleProperty]
    def __init__(self, properties: _Optional[_Iterable[_Union[StyleProperty, _Mapping]]] = ...) -> None: ...

class StyleProperty(_message.Message):
    __slots__ = ("type", "uint_value", "int_value", "color_value", "string_value", "shadow_value")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    UINT_VALUE_FIELD_NUMBER: _ClassVar[int]
    INT_VALUE_FIELD_NUMBER: _ClassVar[int]
    COLOR_VALUE_FIELD_NUMBER: _ClassVar[int]
    STRING_VALUE_FIELD_NUMBER: _ClassVar[int]
    SHADOW_VALUE_FIELD_NUMBER: _ClassVar[int]
    type: StylePropertyType
    uint_value: int
    int_value: int
    color_value: Color
    string_value: str
    shadow_value: ShadowBundle
    def __init__(self, type: _Optional[_Union[StylePropertyType, str]] = ..., uint_value: _Optional[int] = ..., int_value: _Optional[int] = ..., color_value: _Optional[_Union[Color, _Mapping]] = ..., string_value: _Optional[str] = ..., shadow_value: _Optional[_Union[ShadowBundle, _Mapping]] = ...) -> None: ...

class Color(_message.Message):
    __slots__ = ("r", "g", "b")
    R_FIELD_NUMBER: _ClassVar[int]
    G_FIELD_NUMBER: _ClassVar[int]
    B_FIELD_NUMBER: _ClassVar[int]
    r: int
    g: int
    b: int
    def __init__(self, r: _Optional[int] = ..., g: _Optional[int] = ..., b: _Optional[int] = ...) -> None: ...

class ShadowBundle(_message.Message):
    __slots__ = ("width", "offset_x", "offset_y", "spread", "opa")
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    OFFSET_X_FIELD_NUMBER: _ClassVar[int]
    OFFSET_Y_FIELD_NUMBER: _ClassVar[int]
    SPREAD_FIELD_NUMBER: _ClassVar[int]
    OPA_FIELD_NUMBER: _ClassVar[int]
    width: int
    offset_x: int
    offset_y: int
    spread: int
    opa: int
    def __init__(self, width: _Optional[int] = ..., offset_x: _Optional[int] = ..., offset_y: _Optional[int] = ..., spread: _Optional[int] = ..., opa: _Optional[int] = ...) -> None: ...
