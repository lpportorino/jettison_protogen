from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SubjectType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SUBJECT_INT: _ClassVar[SubjectType]
    SUBJECT_STRING: _ClassVar[SubjectType]

class PatchOpKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PATCH_OP_UPDATE_PROPS: _ClassVar[PatchOpKind]
    PATCH_OP_REPLACE_NODE: _ClassVar[PatchOpKind]
    PATCH_OP_INSERT_NODE: _ClassVar[PatchOpKind]
    PATCH_OP_REMOVE_NODE: _ClassVar[PatchOpKind]
    PATCH_OP_MOVE_NODE: _ClassVar[PatchOpKind]

class WidgetType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    WIDGET_OBJ: _ClassVar[WidgetType]
    WIDGET_BUTTON: _ClassVar[WidgetType]
    WIDGET_LABEL: _ClassVar[WidgetType]
    WIDGET_SLIDER: _ClassVar[WidgetType]
    WIDGET_IMAGE: _ClassVar[WidgetType]
    WIDGET_ARC: _ClassVar[WidgetType]
    WIDGET_BAR: _ClassVar[WidgetType]
    WIDGET_SWITCH: _ClassVar[WidgetType]
    WIDGET_CHECKBOX: _ClassVar[WidgetType]
    WIDGET_DROPDOWN: _ClassVar[WidgetType]
    WIDGET_ROLLER: _ClassVar[WidgetType]
    WIDGET_TEXTAREA: _ClassVar[WidgetType]
    WIDGET_SPINBOX: _ClassVar[WidgetType]
    WIDGET_SPINNER: _ClassVar[WidgetType]
    WIDGET_LED: _ClassVar[WidgetType]
    WIDGET_LINE: _ClassVar[WidgetType]
    WIDGET_SCALE: _ClassVar[WidgetType]
    WIDGET_BUTTONMATRIX: _ClassVar[WidgetType]
    WIDGET_TABLE: _ClassVar[WidgetType]
    WIDGET_TABVIEW: _ClassVar[WidgetType]
    WIDGET_CHART: _ClassVar[WidgetType]
    WIDGET_HOST_PROXY: _ClassVar[WidgetType]

class ProxyMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PROXY_MODE_STATIC: _ClassVar[ProxyMode]
    PROXY_MODE_DRAGGABLE: _ClassVar[ProxyMode]
    PROXY_MODE_RESIZABLE: _ClassVar[ProxyMode]
    PROXY_MODE_ALIGNABLE: _ClassVar[ProxyMode]

class EventTrigger(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TRIGGER_CLICKED: _ClassVar[EventTrigger]
    TRIGGER_VALUE_CHANGED: _ClassVar[EventTrigger]
    TRIGGER_LONG_PRESSED: _ClassVar[EventTrigger]

class PatchKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    PATCH_KIND_UNSPECIFIED: _ClassVar[PatchKind]
    PATCH_KIND_NDC_X: _ClassVar[PatchKind]
    PATCH_KIND_NDC_Y: _ClassVar[PatchKind]
    PATCH_KIND_DELTA: _ClassVar[PatchKind]
    PATCH_KIND_WIDGET_VALUE: _ClassVar[PatchKind]

class GestureKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GESTURE_KIND_PAN_MOVE: _ClassVar[GestureKind]
    GESTURE_KIND_PAN_END: _ClassVar[GestureKind]
    GESTURE_KIND_TAP: _ClassVar[GestureKind]
    GESTURE_KIND_TRACK: _ClassVar[GestureKind]
    GESTURE_KIND_PINCH: _ClassVar[GestureKind]
    GESTURE_KIND_WHEEL: _ClassVar[GestureKind]

class CompareOp(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    COMPARE_EQ: _ClassVar[CompareOp]
    COMPARE_NOT_EQ: _ClassVar[CompareOp]
    COMPARE_GT: _ClassVar[CompareOp]
    COMPARE_GTE: _ClassVar[CompareOp]
    COMPARE_LT: _ClassVar[CompareOp]
    COMPARE_LTE: _ClassVar[CompareOp]

class FlexFlow(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    FLEX_FLOW_NONE: _ClassVar[FlexFlow]
    FLEX_FLOW_ROW: _ClassVar[FlexFlow]
    FLEX_FLOW_COLUMN: _ClassVar[FlexFlow]
    FLEX_FLOW_ROW_WRAP: _ClassVar[FlexFlow]
    FLEX_FLOW_ROW_REVERSE: _ClassVar[FlexFlow]
    FLEX_FLOW_ROW_WRAP_REVERSE: _ClassVar[FlexFlow]
    FLEX_FLOW_COLUMN_WRAP: _ClassVar[FlexFlow]
    FLEX_FLOW_COLUMN_REVERSE: _ClassVar[FlexFlow]
    FLEX_FLOW_COLUMN_WRAP_REVERSE: _ClassVar[FlexFlow]

class FlexAlign(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    FLEX_ALIGN_START: _ClassVar[FlexAlign]
    FLEX_ALIGN_END: _ClassVar[FlexAlign]
    FLEX_ALIGN_CENTER: _ClassVar[FlexAlign]
    FLEX_ALIGN_SPACE_EVENLY: _ClassVar[FlexAlign]
    FLEX_ALIGN_SPACE_AROUND: _ClassVar[FlexAlign]
    FLEX_ALIGN_SPACE_BETWEEN: _ClassVar[FlexAlign]

class GridAlign(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GRID_ALIGN_START: _ClassVar[GridAlign]
    GRID_ALIGN_CENTER: _ClassVar[GridAlign]
    GRID_ALIGN_END: _ClassVar[GridAlign]
    GRID_ALIGN_STRETCH: _ClassVar[GridAlign]
    GRID_ALIGN_SPACE_EVENLY: _ClassVar[GridAlign]
    GRID_ALIGN_SPACE_AROUND: _ClassVar[GridAlign]
    GRID_ALIGN_SPACE_BETWEEN: _ClassVar[GridAlign]

class TextAlign(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TEXT_ALIGN_AUTO: _ClassVar[TextAlign]
    TEXT_ALIGN_LEFT: _ClassVar[TextAlign]
    TEXT_ALIGN_CENTER: _ClassVar[TextAlign]
    TEXT_ALIGN_RIGHT: _ClassVar[TextAlign]

class TextDecor(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TEXT_DECOR_NONE: _ClassVar[TextDecor]
    TEXT_DECOR_UNDERLINE: _ClassVar[TextDecor]
    TEXT_DECOR_STRIKETHROUGH: _ClassVar[TextDecor]

class BlendMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BLEND_MODE_NORMAL: _ClassVar[BlendMode]
    BLEND_MODE_ADDITIVE: _ClassVar[BlendMode]
    BLEND_MODE_SUBTRACTIVE: _ClassVar[BlendMode]
    BLEND_MODE_MULTIPLY: _ClassVar[BlendMode]
    BLEND_MODE_DIFFERENCE: _ClassVar[BlendMode]

class BaseDir(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BASE_DIR_LTR: _ClassVar[BaseDir]
    BASE_DIR_RTL: _ClassVar[BaseDir]
    BASE_DIR_AUTO: _ClassVar[BaseDir]
    BASE_DIR_NEUTRAL: _ClassVar[BaseDir]
    BASE_DIR_WEAK: _ClassVar[BaseDir]

class GradDir(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GRAD_DIR_NONE: _ClassVar[GradDir]
    GRAD_DIR_VER: _ClassVar[GradDir]
    GRAD_DIR_HOR: _ClassVar[GradDir]
    GRAD_DIR_LINEAR: _ClassVar[GradDir]
    GRAD_DIR_RADIAL: _ClassVar[GradDir]
    GRAD_DIR_CONICAL: _ClassVar[GradDir]

class Dir(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DIR_NONE: _ClassVar[Dir]
    DIR_LEFT: _ClassVar[Dir]
    DIR_RIGHT: _ClassVar[Dir]
    DIR_TOP: _ClassVar[Dir]
    DIR_BOTTOM: _ClassVar[Dir]
    DIR_HOR: _ClassVar[Dir]
    DIR_VER: _ClassVar[Dir]
    DIR_ALL: _ClassVar[Dir]

class Align(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ALIGN_DEFAULT: _ClassVar[Align]
    ALIGN_TOP_LEFT: _ClassVar[Align]
    ALIGN_TOP_MID: _ClassVar[Align]
    ALIGN_TOP_RIGHT: _ClassVar[Align]
    ALIGN_BOTTOM_LEFT: _ClassVar[Align]
    ALIGN_BOTTOM_MID: _ClassVar[Align]
    ALIGN_BOTTOM_RIGHT: _ClassVar[Align]
    ALIGN_LEFT_MID: _ClassVar[Align]
    ALIGN_RIGHT_MID: _ClassVar[Align]
    ALIGN_CENTER: _ClassVar[Align]
    ALIGN_OUT_TOP_LEFT: _ClassVar[Align]
    ALIGN_OUT_TOP_MID: _ClassVar[Align]
    ALIGN_OUT_TOP_RIGHT: _ClassVar[Align]
    ALIGN_OUT_BOTTOM_LEFT: _ClassVar[Align]
    ALIGN_OUT_BOTTOM_MID: _ClassVar[Align]
    ALIGN_OUT_BOTTOM_RIGHT: _ClassVar[Align]
    ALIGN_OUT_LEFT_TOP: _ClassVar[Align]
    ALIGN_OUT_LEFT_MID: _ClassVar[Align]
    ALIGN_OUT_LEFT_BOTTOM: _ClassVar[Align]
    ALIGN_OUT_RIGHT_TOP: _ClassVar[Align]
    ALIGN_OUT_RIGHT_MID: _ClassVar[Align]
    ALIGN_OUT_RIGHT_BOTTOM: _ClassVar[Align]

class BorderSide(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BORDER_SIDE_NONE: _ClassVar[BorderSide]
    BORDER_SIDE_BOTTOM: _ClassVar[BorderSide]
    BORDER_SIDE_TOP: _ClassVar[BorderSide]
    BORDER_SIDE_LEFT: _ClassVar[BorderSide]
    BORDER_SIDE_RIGHT: _ClassVar[BorderSide]
    BORDER_SIDE_FULL: _ClassVar[BorderSide]
    BORDER_SIDE_INTERNAL: _ClassVar[BorderSide]

class LabelLongMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LABEL_LONG_MODE_WRAP: _ClassVar[LabelLongMode]
    LABEL_LONG_MODE_DOTS: _ClassVar[LabelLongMode]
    LABEL_LONG_MODE_SCROLL: _ClassVar[LabelLongMode]
    LABEL_LONG_MODE_SCROLL_CIRCULAR: _ClassVar[LabelLongMode]
    LABEL_LONG_MODE_CLIP: _ClassVar[LabelLongMode]

class BarMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    BAR_MODE_NORMAL: _ClassVar[BarMode]
    BAR_MODE_SYMMETRICAL: _ClassVar[BarMode]
    BAR_MODE_RANGE: _ClassVar[BarMode]

class ArcMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ARC_MODE_NORMAL: _ClassVar[ArcMode]
    ARC_MODE_SYMMETRICAL: _ClassVar[ArcMode]
    ARC_MODE_REVERSE: _ClassVar[ArcMode]

class RollerMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ROLLER_MODE_NORMAL: _ClassVar[RollerMode]
    ROLLER_MODE_INFINITE: _ClassVar[RollerMode]

class ScaleMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SCALE_MODE_HORIZONTAL_TOP: _ClassVar[ScaleMode]
    SCALE_MODE_HORIZONTAL_BOTTOM: _ClassVar[ScaleMode]
    SCALE_MODE_VERTICAL_LEFT: _ClassVar[ScaleMode]
    SCALE_MODE_VERTICAL_RIGHT: _ClassVar[ScaleMode]
    SCALE_MODE_ROUND_INNER: _ClassVar[ScaleMode]
    SCALE_MODE_ROUND_OUTER: _ClassVar[ScaleMode]

class ChartType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CHART_TYPE_NONE: _ClassVar[ChartType]
    CHART_TYPE_LINE: _ClassVar[ChartType]
    CHART_TYPE_CURVE: _ClassVar[ChartType]
    CHART_TYPE_BAR: _ClassVar[ChartType]
    CHART_TYPE_STACKED: _ClassVar[ChartType]
    CHART_TYPE_SCATTER: _ClassVar[ChartType]

class ChartAxis(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CHART_AXIS_PRIMARY_Y: _ClassVar[ChartAxis]
    CHART_AXIS_SECONDARY_Y: _ClassVar[ChartAxis]
    CHART_AXIS_PRIMARY_X: _ClassVar[ChartAxis]
    CHART_AXIS_SECONDARY_X: _ClassVar[ChartAxis]

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
    PROP_MIN_WIDTH: _ClassVar[StylePropertyType]
    PROP_MAX_WIDTH: _ClassVar[StylePropertyType]
    PROP_MIN_HEIGHT: _ClassVar[StylePropertyType]
    PROP_MAX_HEIGHT: _ClassVar[StylePropertyType]
    PROP_LENGTH: _ClassVar[StylePropertyType]
    PROP_X: _ClassVar[StylePropertyType]
    PROP_Y: _ClassVar[StylePropertyType]
    PROP_ALIGN: _ClassVar[StylePropertyType]
    PROP_TRANSFORM_WIDTH: _ClassVar[StylePropertyType]
    PROP_TRANSFORM_HEIGHT: _ClassVar[StylePropertyType]
    PROP_TRANSLATE_X: _ClassVar[StylePropertyType]
    PROP_TRANSLATE_Y: _ClassVar[StylePropertyType]
    PROP_SCALE_X: _ClassVar[StylePropertyType]
    PROP_SCALE_Y: _ClassVar[StylePropertyType]
    PROP_ROTATION: _ClassVar[StylePropertyType]
    PROP_PIVOT_X: _ClassVar[StylePropertyType]
    PROP_PIVOT_Y: _ClassVar[StylePropertyType]
    PROP_SKEW_X: _ClassVar[StylePropertyType]
    PROP_SKEW_Y: _ClassVar[StylePropertyType]
    PROP_PAD_TOP: _ClassVar[StylePropertyType]
    PROP_PAD_BOTTOM: _ClassVar[StylePropertyType]
    PROP_PAD_LEFT: _ClassVar[StylePropertyType]
    PROP_PAD_RIGHT: _ClassVar[StylePropertyType]
    PROP_PAD_ROW: _ClassVar[StylePropertyType]
    PROP_PAD_COLUMN: _ClassVar[StylePropertyType]
    PROP_MARGIN_TOP: _ClassVar[StylePropertyType]
    PROP_MARGIN_BOTTOM: _ClassVar[StylePropertyType]
    PROP_MARGIN_LEFT: _ClassVar[StylePropertyType]
    PROP_MARGIN_RIGHT: _ClassVar[StylePropertyType]
    PROP_BG_GRAD_COLOR: _ClassVar[StylePropertyType]
    PROP_BG_GRAD_DIR: _ClassVar[StylePropertyType]
    PROP_BG_MAIN_STOP: _ClassVar[StylePropertyType]
    PROP_BG_GRAD_STOP: _ClassVar[StylePropertyType]
    PROP_BG_MAIN_OPA: _ClassVar[StylePropertyType]
    PROP_BG_GRAD_OPA: _ClassVar[StylePropertyType]
    PROP_BG_IMAGE_SRC: _ClassVar[StylePropertyType]
    PROP_BG_IMAGE_OPA: _ClassVar[StylePropertyType]
    PROP_BG_IMAGE_RECOLOR: _ClassVar[StylePropertyType]
    PROP_BG_IMAGE_RECOLOR_OPA: _ClassVar[StylePropertyType]
    PROP_BG_IMAGE_TILED: _ClassVar[StylePropertyType]
    PROP_BORDER_SIDE: _ClassVar[StylePropertyType]
    PROP_BORDER_POST: _ClassVar[StylePropertyType]
    PROP_OUTLINE_WIDTH: _ClassVar[StylePropertyType]
    PROP_OUTLINE_COLOR: _ClassVar[StylePropertyType]
    PROP_OUTLINE_OPA: _ClassVar[StylePropertyType]
    PROP_OUTLINE_PAD: _ClassVar[StylePropertyType]
    PROP_SHADOW_WIDTH: _ClassVar[StylePropertyType]
    PROP_SHADOW_OFFSET_X: _ClassVar[StylePropertyType]
    PROP_SHADOW_OFFSET_Y: _ClassVar[StylePropertyType]
    PROP_SHADOW_SPREAD: _ClassVar[StylePropertyType]
    PROP_SHADOW_COLOR: _ClassVar[StylePropertyType]
    PROP_SHADOW_OPA: _ClassVar[StylePropertyType]
    PROP_IMAGE_OPA: _ClassVar[StylePropertyType]
    PROP_IMAGE_RECOLOR: _ClassVar[StylePropertyType]
    PROP_IMAGE_RECOLOR_OPA: _ClassVar[StylePropertyType]
    PROP_LINE_WIDTH: _ClassVar[StylePropertyType]
    PROP_LINE_DASH_WIDTH: _ClassVar[StylePropertyType]
    PROP_LINE_DASH_GAP: _ClassVar[StylePropertyType]
    PROP_LINE_ROUNDED: _ClassVar[StylePropertyType]
    PROP_LINE_COLOR: _ClassVar[StylePropertyType]
    PROP_LINE_OPA: _ClassVar[StylePropertyType]
    PROP_ARC_WIDTH: _ClassVar[StylePropertyType]
    PROP_ARC_ROUNDED: _ClassVar[StylePropertyType]
    PROP_ARC_COLOR: _ClassVar[StylePropertyType]
    PROP_ARC_OPA: _ClassVar[StylePropertyType]
    PROP_TEXT_OPA: _ClassVar[StylePropertyType]
    PROP_TEXT_LETTER_SPACE: _ClassVar[StylePropertyType]
    PROP_TEXT_LINE_SPACE: _ClassVar[StylePropertyType]
    PROP_TEXT_DECOR: _ClassVar[StylePropertyType]
    PROP_TEXT_ALIGN: _ClassVar[StylePropertyType]
    PROP_CLIP_CORNER: _ClassVar[StylePropertyType]
    PROP_OPA: _ClassVar[StylePropertyType]
    PROP_OPA_LAYERED: _ClassVar[StylePropertyType]
    PROP_COLOR_FILTER_OPA: _ClassVar[StylePropertyType]
    PROP_ANIM_DURATION: _ClassVar[StylePropertyType]
    PROP_BLEND_MODE: _ClassVar[StylePropertyType]
    PROP_BASE_DIR: _ClassVar[StylePropertyType]
    PROP_ROTARY_SENSITIVITY: _ClassVar[StylePropertyType]
    PROP_FLEX_FLOW: _ClassVar[StylePropertyType]
    PROP_FLEX_MAIN_PLACE: _ClassVar[StylePropertyType]
    PROP_FLEX_CROSS_PLACE: _ClassVar[StylePropertyType]
    PROP_FLEX_TRACK_PLACE: _ClassVar[StylePropertyType]
    PROP_FLEX_GROW: _ClassVar[StylePropertyType]
    PROP_GRID_COLUMN_ALIGN: _ClassVar[StylePropertyType]
    PROP_GRID_ROW_ALIGN: _ClassVar[StylePropertyType]
    PROP_GRID_CELL_COLUMN_POS: _ClassVar[StylePropertyType]
    PROP_GRID_CELL_X_ALIGN: _ClassVar[StylePropertyType]
    PROP_GRID_CELL_COLUMN_SPAN: _ClassVar[StylePropertyType]
    PROP_GRID_CELL_ROW_POS: _ClassVar[StylePropertyType]
    PROP_GRID_CELL_Y_ALIGN: _ClassVar[StylePropertyType]
    PROP_GRID_CELL_ROW_SPAN: _ClassVar[StylePropertyType]
SUBJECT_INT: SubjectType
SUBJECT_STRING: SubjectType
PATCH_OP_UPDATE_PROPS: PatchOpKind
PATCH_OP_REPLACE_NODE: PatchOpKind
PATCH_OP_INSERT_NODE: PatchOpKind
PATCH_OP_REMOVE_NODE: PatchOpKind
PATCH_OP_MOVE_NODE: PatchOpKind
WIDGET_OBJ: WidgetType
WIDGET_BUTTON: WidgetType
WIDGET_LABEL: WidgetType
WIDGET_SLIDER: WidgetType
WIDGET_IMAGE: WidgetType
WIDGET_ARC: WidgetType
WIDGET_BAR: WidgetType
WIDGET_SWITCH: WidgetType
WIDGET_CHECKBOX: WidgetType
WIDGET_DROPDOWN: WidgetType
WIDGET_ROLLER: WidgetType
WIDGET_TEXTAREA: WidgetType
WIDGET_SPINBOX: WidgetType
WIDGET_SPINNER: WidgetType
WIDGET_LED: WidgetType
WIDGET_LINE: WidgetType
WIDGET_SCALE: WidgetType
WIDGET_BUTTONMATRIX: WidgetType
WIDGET_TABLE: WidgetType
WIDGET_TABVIEW: WidgetType
WIDGET_CHART: WidgetType
WIDGET_HOST_PROXY: WidgetType
PROXY_MODE_STATIC: ProxyMode
PROXY_MODE_DRAGGABLE: ProxyMode
PROXY_MODE_RESIZABLE: ProxyMode
PROXY_MODE_ALIGNABLE: ProxyMode
TRIGGER_CLICKED: EventTrigger
TRIGGER_VALUE_CHANGED: EventTrigger
TRIGGER_LONG_PRESSED: EventTrigger
PATCH_KIND_UNSPECIFIED: PatchKind
PATCH_KIND_NDC_X: PatchKind
PATCH_KIND_NDC_Y: PatchKind
PATCH_KIND_DELTA: PatchKind
PATCH_KIND_WIDGET_VALUE: PatchKind
GESTURE_KIND_PAN_MOVE: GestureKind
GESTURE_KIND_PAN_END: GestureKind
GESTURE_KIND_TAP: GestureKind
GESTURE_KIND_TRACK: GestureKind
GESTURE_KIND_PINCH: GestureKind
GESTURE_KIND_WHEEL: GestureKind
COMPARE_EQ: CompareOp
COMPARE_NOT_EQ: CompareOp
COMPARE_GT: CompareOp
COMPARE_GTE: CompareOp
COMPARE_LT: CompareOp
COMPARE_LTE: CompareOp
FLEX_FLOW_NONE: FlexFlow
FLEX_FLOW_ROW: FlexFlow
FLEX_FLOW_COLUMN: FlexFlow
FLEX_FLOW_ROW_WRAP: FlexFlow
FLEX_FLOW_ROW_REVERSE: FlexFlow
FLEX_FLOW_ROW_WRAP_REVERSE: FlexFlow
FLEX_FLOW_COLUMN_WRAP: FlexFlow
FLEX_FLOW_COLUMN_REVERSE: FlexFlow
FLEX_FLOW_COLUMN_WRAP_REVERSE: FlexFlow
FLEX_ALIGN_START: FlexAlign
FLEX_ALIGN_END: FlexAlign
FLEX_ALIGN_CENTER: FlexAlign
FLEX_ALIGN_SPACE_EVENLY: FlexAlign
FLEX_ALIGN_SPACE_AROUND: FlexAlign
FLEX_ALIGN_SPACE_BETWEEN: FlexAlign
GRID_ALIGN_START: GridAlign
GRID_ALIGN_CENTER: GridAlign
GRID_ALIGN_END: GridAlign
GRID_ALIGN_STRETCH: GridAlign
GRID_ALIGN_SPACE_EVENLY: GridAlign
GRID_ALIGN_SPACE_AROUND: GridAlign
GRID_ALIGN_SPACE_BETWEEN: GridAlign
TEXT_ALIGN_AUTO: TextAlign
TEXT_ALIGN_LEFT: TextAlign
TEXT_ALIGN_CENTER: TextAlign
TEXT_ALIGN_RIGHT: TextAlign
TEXT_DECOR_NONE: TextDecor
TEXT_DECOR_UNDERLINE: TextDecor
TEXT_DECOR_STRIKETHROUGH: TextDecor
BLEND_MODE_NORMAL: BlendMode
BLEND_MODE_ADDITIVE: BlendMode
BLEND_MODE_SUBTRACTIVE: BlendMode
BLEND_MODE_MULTIPLY: BlendMode
BLEND_MODE_DIFFERENCE: BlendMode
BASE_DIR_LTR: BaseDir
BASE_DIR_RTL: BaseDir
BASE_DIR_AUTO: BaseDir
BASE_DIR_NEUTRAL: BaseDir
BASE_DIR_WEAK: BaseDir
GRAD_DIR_NONE: GradDir
GRAD_DIR_VER: GradDir
GRAD_DIR_HOR: GradDir
GRAD_DIR_LINEAR: GradDir
GRAD_DIR_RADIAL: GradDir
GRAD_DIR_CONICAL: GradDir
DIR_NONE: Dir
DIR_LEFT: Dir
DIR_RIGHT: Dir
DIR_TOP: Dir
DIR_BOTTOM: Dir
DIR_HOR: Dir
DIR_VER: Dir
DIR_ALL: Dir
ALIGN_DEFAULT: Align
ALIGN_TOP_LEFT: Align
ALIGN_TOP_MID: Align
ALIGN_TOP_RIGHT: Align
ALIGN_BOTTOM_LEFT: Align
ALIGN_BOTTOM_MID: Align
ALIGN_BOTTOM_RIGHT: Align
ALIGN_LEFT_MID: Align
ALIGN_RIGHT_MID: Align
ALIGN_CENTER: Align
ALIGN_OUT_TOP_LEFT: Align
ALIGN_OUT_TOP_MID: Align
ALIGN_OUT_TOP_RIGHT: Align
ALIGN_OUT_BOTTOM_LEFT: Align
ALIGN_OUT_BOTTOM_MID: Align
ALIGN_OUT_BOTTOM_RIGHT: Align
ALIGN_OUT_LEFT_TOP: Align
ALIGN_OUT_LEFT_MID: Align
ALIGN_OUT_LEFT_BOTTOM: Align
ALIGN_OUT_RIGHT_TOP: Align
ALIGN_OUT_RIGHT_MID: Align
ALIGN_OUT_RIGHT_BOTTOM: Align
BORDER_SIDE_NONE: BorderSide
BORDER_SIDE_BOTTOM: BorderSide
BORDER_SIDE_TOP: BorderSide
BORDER_SIDE_LEFT: BorderSide
BORDER_SIDE_RIGHT: BorderSide
BORDER_SIDE_FULL: BorderSide
BORDER_SIDE_INTERNAL: BorderSide
LABEL_LONG_MODE_WRAP: LabelLongMode
LABEL_LONG_MODE_DOTS: LabelLongMode
LABEL_LONG_MODE_SCROLL: LabelLongMode
LABEL_LONG_MODE_SCROLL_CIRCULAR: LabelLongMode
LABEL_LONG_MODE_CLIP: LabelLongMode
BAR_MODE_NORMAL: BarMode
BAR_MODE_SYMMETRICAL: BarMode
BAR_MODE_RANGE: BarMode
ARC_MODE_NORMAL: ArcMode
ARC_MODE_SYMMETRICAL: ArcMode
ARC_MODE_REVERSE: ArcMode
ROLLER_MODE_NORMAL: RollerMode
ROLLER_MODE_INFINITE: RollerMode
SCALE_MODE_HORIZONTAL_TOP: ScaleMode
SCALE_MODE_HORIZONTAL_BOTTOM: ScaleMode
SCALE_MODE_VERTICAL_LEFT: ScaleMode
SCALE_MODE_VERTICAL_RIGHT: ScaleMode
SCALE_MODE_ROUND_INNER: ScaleMode
SCALE_MODE_ROUND_OUTER: ScaleMode
CHART_TYPE_NONE: ChartType
CHART_TYPE_LINE: ChartType
CHART_TYPE_CURVE: ChartType
CHART_TYPE_BAR: ChartType
CHART_TYPE_STACKED: ChartType
CHART_TYPE_SCATTER: ChartType
CHART_AXIS_PRIMARY_Y: ChartAxis
CHART_AXIS_SECONDARY_Y: ChartAxis
CHART_AXIS_PRIMARY_X: ChartAxis
CHART_AXIS_SECONDARY_X: ChartAxis
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
PROP_MIN_WIDTH: StylePropertyType
PROP_MAX_WIDTH: StylePropertyType
PROP_MIN_HEIGHT: StylePropertyType
PROP_MAX_HEIGHT: StylePropertyType
PROP_LENGTH: StylePropertyType
PROP_X: StylePropertyType
PROP_Y: StylePropertyType
PROP_ALIGN: StylePropertyType
PROP_TRANSFORM_WIDTH: StylePropertyType
PROP_TRANSFORM_HEIGHT: StylePropertyType
PROP_TRANSLATE_X: StylePropertyType
PROP_TRANSLATE_Y: StylePropertyType
PROP_SCALE_X: StylePropertyType
PROP_SCALE_Y: StylePropertyType
PROP_ROTATION: StylePropertyType
PROP_PIVOT_X: StylePropertyType
PROP_PIVOT_Y: StylePropertyType
PROP_SKEW_X: StylePropertyType
PROP_SKEW_Y: StylePropertyType
PROP_PAD_TOP: StylePropertyType
PROP_PAD_BOTTOM: StylePropertyType
PROP_PAD_LEFT: StylePropertyType
PROP_PAD_RIGHT: StylePropertyType
PROP_PAD_ROW: StylePropertyType
PROP_PAD_COLUMN: StylePropertyType
PROP_MARGIN_TOP: StylePropertyType
PROP_MARGIN_BOTTOM: StylePropertyType
PROP_MARGIN_LEFT: StylePropertyType
PROP_MARGIN_RIGHT: StylePropertyType
PROP_BG_GRAD_COLOR: StylePropertyType
PROP_BG_GRAD_DIR: StylePropertyType
PROP_BG_MAIN_STOP: StylePropertyType
PROP_BG_GRAD_STOP: StylePropertyType
PROP_BG_MAIN_OPA: StylePropertyType
PROP_BG_GRAD_OPA: StylePropertyType
PROP_BG_IMAGE_SRC: StylePropertyType
PROP_BG_IMAGE_OPA: StylePropertyType
PROP_BG_IMAGE_RECOLOR: StylePropertyType
PROP_BG_IMAGE_RECOLOR_OPA: StylePropertyType
PROP_BG_IMAGE_TILED: StylePropertyType
PROP_BORDER_SIDE: StylePropertyType
PROP_BORDER_POST: StylePropertyType
PROP_OUTLINE_WIDTH: StylePropertyType
PROP_OUTLINE_COLOR: StylePropertyType
PROP_OUTLINE_OPA: StylePropertyType
PROP_OUTLINE_PAD: StylePropertyType
PROP_SHADOW_WIDTH: StylePropertyType
PROP_SHADOW_OFFSET_X: StylePropertyType
PROP_SHADOW_OFFSET_Y: StylePropertyType
PROP_SHADOW_SPREAD: StylePropertyType
PROP_SHADOW_COLOR: StylePropertyType
PROP_SHADOW_OPA: StylePropertyType
PROP_IMAGE_OPA: StylePropertyType
PROP_IMAGE_RECOLOR: StylePropertyType
PROP_IMAGE_RECOLOR_OPA: StylePropertyType
PROP_LINE_WIDTH: StylePropertyType
PROP_LINE_DASH_WIDTH: StylePropertyType
PROP_LINE_DASH_GAP: StylePropertyType
PROP_LINE_ROUNDED: StylePropertyType
PROP_LINE_COLOR: StylePropertyType
PROP_LINE_OPA: StylePropertyType
PROP_ARC_WIDTH: StylePropertyType
PROP_ARC_ROUNDED: StylePropertyType
PROP_ARC_COLOR: StylePropertyType
PROP_ARC_OPA: StylePropertyType
PROP_TEXT_OPA: StylePropertyType
PROP_TEXT_LETTER_SPACE: StylePropertyType
PROP_TEXT_LINE_SPACE: StylePropertyType
PROP_TEXT_DECOR: StylePropertyType
PROP_TEXT_ALIGN: StylePropertyType
PROP_CLIP_CORNER: StylePropertyType
PROP_OPA: StylePropertyType
PROP_OPA_LAYERED: StylePropertyType
PROP_COLOR_FILTER_OPA: StylePropertyType
PROP_ANIM_DURATION: StylePropertyType
PROP_BLEND_MODE: StylePropertyType
PROP_BASE_DIR: StylePropertyType
PROP_ROTARY_SENSITIVITY: StylePropertyType
PROP_FLEX_FLOW: StylePropertyType
PROP_FLEX_MAIN_PLACE: StylePropertyType
PROP_FLEX_CROSS_PLACE: StylePropertyType
PROP_FLEX_TRACK_PLACE: StylePropertyType
PROP_FLEX_GROW: StylePropertyType
PROP_GRID_COLUMN_ALIGN: StylePropertyType
PROP_GRID_ROW_ALIGN: StylePropertyType
PROP_GRID_CELL_COLUMN_POS: StylePropertyType
PROP_GRID_CELL_X_ALIGN: StylePropertyType
PROP_GRID_CELL_COLUMN_SPAN: StylePropertyType
PROP_GRID_CELL_ROW_POS: StylePropertyType
PROP_GRID_CELL_Y_ALIGN: StylePropertyType
PROP_GRID_CELL_ROW_SPAN: StylePropertyType

class SubjectDeclaration(_message.Message):
    __slots__ = ("name", "type", "int_initial", "string_initial")
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    INT_INITIAL_FIELD_NUMBER: _ClassVar[int]
    STRING_INITIAL_FIELD_NUMBER: _ClassVar[int]
    name: str
    type: SubjectType
    int_initial: int
    string_initial: str
    def __init__(self, name: _Optional[str] = ..., type: _Optional[_Union[SubjectType, str]] = ..., int_initial: _Optional[int] = ..., string_initial: _Optional[str] = ...) -> None: ...

class StateUpdate(_message.Message):
    __slots__ = ("values",)
    VALUES_FIELD_NUMBER: _ClassVar[int]
    values: _containers.RepeatedCompositeFieldContainer[SubjectValue]
    def __init__(self, values: _Optional[_Iterable[_Union[SubjectValue, _Mapping]]] = ...) -> None: ...

class SubjectValue(_message.Message):
    __slots__ = ("name", "int_value", "string_value")
    NAME_FIELD_NUMBER: _ClassVar[int]
    INT_VALUE_FIELD_NUMBER: _ClassVar[int]
    STRING_VALUE_FIELD_NUMBER: _ClassVar[int]
    name: str
    int_value: int
    string_value: str
    def __init__(self, name: _Optional[str] = ..., int_value: _Optional[int] = ..., string_value: _Optional[str] = ...) -> None: ...

class Screen(_message.Message):
    __slots__ = ("root", "subjects")
    ROOT_FIELD_NUMBER: _ClassVar[int]
    SUBJECTS_FIELD_NUMBER: _ClassVar[int]
    root: WidgetNode
    subjects: _containers.RepeatedCompositeFieldContainer[SubjectDeclaration]
    def __init__(self, root: _Optional[_Union[WidgetNode, _Mapping]] = ..., subjects: _Optional[_Iterable[_Union[SubjectDeclaration, _Mapping]]] = ...) -> None: ...

class WidgetNode(_message.Message):
    __slots__ = ("type", "x", "y", "text", "bindings", "event", "layout", "children", "style_groups", "obj_props", "button_props", "label_props", "slider_props", "image_props", "arc_props", "bar_props", "switch_props", "checkbox_props", "dropdown_props", "roller_props", "textarea_props", "spinbox_props", "spinner_props", "led_props", "line_props", "scale_props", "buttonmatrix_props", "table_props", "tabview_props", "chart_props", "host_proxy_props", "visibility", "bind_formats", "obj_flags", "obj_flags_clear", "states", "scroll_dir", "grid_col_dsc", "grid_row_dsc", "bare", "in_tab_bar", "checked_when", "uid", "gestures")
    class BindingsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    class BindFormatsEntry(_message.Message):
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
    OBJ_PROPS_FIELD_NUMBER: _ClassVar[int]
    BUTTON_PROPS_FIELD_NUMBER: _ClassVar[int]
    LABEL_PROPS_FIELD_NUMBER: _ClassVar[int]
    SLIDER_PROPS_FIELD_NUMBER: _ClassVar[int]
    IMAGE_PROPS_FIELD_NUMBER: _ClassVar[int]
    ARC_PROPS_FIELD_NUMBER: _ClassVar[int]
    BAR_PROPS_FIELD_NUMBER: _ClassVar[int]
    SWITCH_PROPS_FIELD_NUMBER: _ClassVar[int]
    CHECKBOX_PROPS_FIELD_NUMBER: _ClassVar[int]
    DROPDOWN_PROPS_FIELD_NUMBER: _ClassVar[int]
    ROLLER_PROPS_FIELD_NUMBER: _ClassVar[int]
    TEXTAREA_PROPS_FIELD_NUMBER: _ClassVar[int]
    SPINBOX_PROPS_FIELD_NUMBER: _ClassVar[int]
    SPINNER_PROPS_FIELD_NUMBER: _ClassVar[int]
    LED_PROPS_FIELD_NUMBER: _ClassVar[int]
    LINE_PROPS_FIELD_NUMBER: _ClassVar[int]
    SCALE_PROPS_FIELD_NUMBER: _ClassVar[int]
    BUTTONMATRIX_PROPS_FIELD_NUMBER: _ClassVar[int]
    TABLE_PROPS_FIELD_NUMBER: _ClassVar[int]
    TABVIEW_PROPS_FIELD_NUMBER: _ClassVar[int]
    CHART_PROPS_FIELD_NUMBER: _ClassVar[int]
    HOST_PROXY_PROPS_FIELD_NUMBER: _ClassVar[int]
    VISIBILITY_FIELD_NUMBER: _ClassVar[int]
    BIND_FORMATS_FIELD_NUMBER: _ClassVar[int]
    OBJ_FLAGS_FIELD_NUMBER: _ClassVar[int]
    OBJ_FLAGS_CLEAR_FIELD_NUMBER: _ClassVar[int]
    STATES_FIELD_NUMBER: _ClassVar[int]
    SCROLL_DIR_FIELD_NUMBER: _ClassVar[int]
    GRID_COL_DSC_FIELD_NUMBER: _ClassVar[int]
    GRID_ROW_DSC_FIELD_NUMBER: _ClassVar[int]
    BARE_FIELD_NUMBER: _ClassVar[int]
    IN_TAB_BAR_FIELD_NUMBER: _ClassVar[int]
    CHECKED_WHEN_FIELD_NUMBER: _ClassVar[int]
    UID_FIELD_NUMBER: _ClassVar[int]
    GESTURES_FIELD_NUMBER: _ClassVar[int]
    type: WidgetType
    x: int
    y: int
    text: str
    bindings: _containers.ScalarMap[str, str]
    event: EventBinding
    layout: Layout
    children: _containers.RepeatedCompositeFieldContainer[WidgetNode]
    style_groups: _containers.RepeatedCompositeFieldContainer[StyleGroup]
    obj_props: ObjProps
    button_props: ButtonProps
    label_props: LabelProps
    slider_props: SliderProps
    image_props: ImageProps
    arc_props: ArcProps
    bar_props: BarProps
    switch_props: SwitchProps
    checkbox_props: CheckboxProps
    dropdown_props: DropdownProps
    roller_props: RollerProps
    textarea_props: TextareaProps
    spinbox_props: SpinboxProps
    spinner_props: SpinnerProps
    led_props: LedProps
    line_props: LineProps
    scale_props: ScaleProps
    buttonmatrix_props: ButtonMatrixProps
    table_props: TableProps
    tabview_props: TabviewProps
    chart_props: ChartProps
    host_proxy_props: HostProxyProps
    visibility: VisibilityBinding
    bind_formats: _containers.ScalarMap[str, str]
    obj_flags: int
    obj_flags_clear: int
    states: int
    scroll_dir: int
    grid_col_dsc: _containers.RepeatedScalarFieldContainer[int]
    grid_row_dsc: _containers.RepeatedScalarFieldContainer[int]
    bare: bool
    in_tab_bar: bool
    checked_when: VisibilityBinding
    uid: int
    gestures: _containers.RepeatedCompositeFieldContainer[GestureSpec]
    def __init__(self, type: _Optional[_Union[WidgetType, str]] = ..., x: _Optional[int] = ..., y: _Optional[int] = ..., text: _Optional[str] = ..., bindings: _Optional[_Mapping[str, str]] = ..., event: _Optional[_Union[EventBinding, _Mapping]] = ..., layout: _Optional[_Union[Layout, _Mapping]] = ..., children: _Optional[_Iterable[_Union[WidgetNode, _Mapping]]] = ..., style_groups: _Optional[_Iterable[_Union[StyleGroup, _Mapping]]] = ..., obj_props: _Optional[_Union[ObjProps, _Mapping]] = ..., button_props: _Optional[_Union[ButtonProps, _Mapping]] = ..., label_props: _Optional[_Union[LabelProps, _Mapping]] = ..., slider_props: _Optional[_Union[SliderProps, _Mapping]] = ..., image_props: _Optional[_Union[ImageProps, _Mapping]] = ..., arc_props: _Optional[_Union[ArcProps, _Mapping]] = ..., bar_props: _Optional[_Union[BarProps, _Mapping]] = ..., switch_props: _Optional[_Union[SwitchProps, _Mapping]] = ..., checkbox_props: _Optional[_Union[CheckboxProps, _Mapping]] = ..., dropdown_props: _Optional[_Union[DropdownProps, _Mapping]] = ..., roller_props: _Optional[_Union[RollerProps, _Mapping]] = ..., textarea_props: _Optional[_Union[TextareaProps, _Mapping]] = ..., spinbox_props: _Optional[_Union[SpinboxProps, _Mapping]] = ..., spinner_props: _Optional[_Union[SpinnerProps, _Mapping]] = ..., led_props: _Optional[_Union[LedProps, _Mapping]] = ..., line_props: _Optional[_Union[LineProps, _Mapping]] = ..., scale_props: _Optional[_Union[ScaleProps, _Mapping]] = ..., buttonmatrix_props: _Optional[_Union[ButtonMatrixProps, _Mapping]] = ..., table_props: _Optional[_Union[TableProps, _Mapping]] = ..., tabview_props: _Optional[_Union[TabviewProps, _Mapping]] = ..., chart_props: _Optional[_Union[ChartProps, _Mapping]] = ..., host_proxy_props: _Optional[_Union[HostProxyProps, _Mapping]] = ..., visibility: _Optional[_Union[VisibilityBinding, _Mapping]] = ..., bind_formats: _Optional[_Mapping[str, str]] = ..., obj_flags: _Optional[int] = ..., obj_flags_clear: _Optional[int] = ..., states: _Optional[int] = ..., scroll_dir: _Optional[int] = ..., grid_col_dsc: _Optional[_Iterable[int]] = ..., grid_row_dsc: _Optional[_Iterable[int]] = ..., bare: bool = ..., in_tab_bar: bool = ..., checked_when: _Optional[_Union[VisibilityBinding, _Mapping]] = ..., uid: _Optional[int] = ..., gestures: _Optional[_Iterable[_Union[GestureSpec, _Mapping]]] = ...) -> None: ...

class TreePatchOp(_message.Message):
    __slots__ = ("kind", "target_uid", "parent_uid", "index", "node")
    KIND_FIELD_NUMBER: _ClassVar[int]
    TARGET_UID_FIELD_NUMBER: _ClassVar[int]
    PARENT_UID_FIELD_NUMBER: _ClassVar[int]
    INDEX_FIELD_NUMBER: _ClassVar[int]
    NODE_FIELD_NUMBER: _ClassVar[int]
    kind: PatchOpKind
    target_uid: int
    parent_uid: int
    index: int
    node: WidgetNode
    def __init__(self, kind: _Optional[_Union[PatchOpKind, str]] = ..., target_uid: _Optional[int] = ..., parent_uid: _Optional[int] = ..., index: _Optional[int] = ..., node: _Optional[_Union[WidgetNode, _Mapping]] = ...) -> None: ...

class ScreenPatch(_message.Message):
    __slots__ = ("base_hash", "target_hash", "ops")
    BASE_HASH_FIELD_NUMBER: _ClassVar[int]
    TARGET_HASH_FIELD_NUMBER: _ClassVar[int]
    OPS_FIELD_NUMBER: _ClassVar[int]
    base_hash: int
    target_hash: int
    ops: _containers.RepeatedCompositeFieldContainer[TreePatchOp]
    def __init__(self, base_hash: _Optional[int] = ..., target_hash: _Optional[int] = ..., ops: _Optional[_Iterable[_Union[TreePatchOp, _Mapping]]] = ...) -> None: ...

class ObjProps(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ButtonProps(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class LabelProps(_message.Message):
    __slots__ = ("long_mode",)
    LONG_MODE_FIELD_NUMBER: _ClassVar[int]
    long_mode: LabelLongMode
    def __init__(self, long_mode: _Optional[_Union[LabelLongMode, str]] = ...) -> None: ...

class SliderProps(_message.Message):
    __slots__ = ("min_value", "max_value", "value", "mode")
    MIN_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_VALUE_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    min_value: int
    max_value: int
    value: int
    mode: BarMode
    def __init__(self, min_value: _Optional[int] = ..., max_value: _Optional[int] = ..., value: _Optional[int] = ..., mode: _Optional[_Union[BarMode, str]] = ...) -> None: ...

class ImageProps(_message.Message):
    __slots__ = ("src", "has_pivot", "pivot_x", "pivot_y", "rotation")
    SRC_FIELD_NUMBER: _ClassVar[int]
    HAS_PIVOT_FIELD_NUMBER: _ClassVar[int]
    PIVOT_X_FIELD_NUMBER: _ClassVar[int]
    PIVOT_Y_FIELD_NUMBER: _ClassVar[int]
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    src: str
    has_pivot: bool
    pivot_x: int
    pivot_y: int
    rotation: int
    def __init__(self, src: _Optional[str] = ..., has_pivot: bool = ..., pivot_x: _Optional[int] = ..., pivot_y: _Optional[int] = ..., rotation: _Optional[int] = ...) -> None: ...

class ArcProps(_message.Message):
    __slots__ = ("start_angle", "end_angle", "bg_start_angle", "bg_end_angle", "rotation", "mode", "min_value", "max_value", "value")
    START_ANGLE_FIELD_NUMBER: _ClassVar[int]
    END_ANGLE_FIELD_NUMBER: _ClassVar[int]
    BG_START_ANGLE_FIELD_NUMBER: _ClassVar[int]
    BG_END_ANGLE_FIELD_NUMBER: _ClassVar[int]
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    MIN_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_VALUE_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    start_angle: int
    end_angle: int
    bg_start_angle: int
    bg_end_angle: int
    rotation: int
    mode: ArcMode
    min_value: int
    max_value: int
    value: int
    def __init__(self, start_angle: _Optional[int] = ..., end_angle: _Optional[int] = ..., bg_start_angle: _Optional[int] = ..., bg_end_angle: _Optional[int] = ..., rotation: _Optional[int] = ..., mode: _Optional[_Union[ArcMode, str]] = ..., min_value: _Optional[int] = ..., max_value: _Optional[int] = ..., value: _Optional[int] = ...) -> None: ...

class BarProps(_message.Message):
    __slots__ = ("min_value", "max_value", "value", "start_value", "mode")
    MIN_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_VALUE_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    START_VALUE_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    min_value: int
    max_value: int
    value: int
    start_value: int
    mode: BarMode
    def __init__(self, min_value: _Optional[int] = ..., max_value: _Optional[int] = ..., value: _Optional[int] = ..., start_value: _Optional[int] = ..., mode: _Optional[_Union[BarMode, str]] = ...) -> None: ...

class SwitchProps(_message.Message):
    __slots__ = ("checked",)
    CHECKED_FIELD_NUMBER: _ClassVar[int]
    checked: bool
    def __init__(self, checked: bool = ...) -> None: ...

class CheckboxProps(_message.Message):
    __slots__ = ("checked",)
    CHECKED_FIELD_NUMBER: _ClassVar[int]
    checked: bool
    def __init__(self, checked: bool = ...) -> None: ...

class DropdownProps(_message.Message):
    __slots__ = ("options", "selected", "direction", "option_values")
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    SELECTED_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    OPTION_VALUES_FIELD_NUMBER: _ClassVar[int]
    options: str
    selected: int
    direction: Dir
    option_values: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, options: _Optional[str] = ..., selected: _Optional[int] = ..., direction: _Optional[_Union[Dir, str]] = ..., option_values: _Optional[_Iterable[int]] = ...) -> None: ...

class RollerProps(_message.Message):
    __slots__ = ("options", "selected", "visible_row_count", "mode")
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    SELECTED_FIELD_NUMBER: _ClassVar[int]
    VISIBLE_ROW_COUNT_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    options: str
    selected: int
    visible_row_count: int
    mode: RollerMode
    def __init__(self, options: _Optional[str] = ..., selected: _Optional[int] = ..., visible_row_count: _Optional[int] = ..., mode: _Optional[_Union[RollerMode, str]] = ...) -> None: ...

class TextareaProps(_message.Message):
    __slots__ = ("placeholder", "max_length", "one_line", "password_mode")
    PLACEHOLDER_FIELD_NUMBER: _ClassVar[int]
    MAX_LENGTH_FIELD_NUMBER: _ClassVar[int]
    ONE_LINE_FIELD_NUMBER: _ClassVar[int]
    PASSWORD_MODE_FIELD_NUMBER: _ClassVar[int]
    placeholder: str
    max_length: int
    one_line: bool
    password_mode: bool
    def __init__(self, placeholder: _Optional[str] = ..., max_length: _Optional[int] = ..., one_line: bool = ..., password_mode: bool = ...) -> None: ...

class SpinboxProps(_message.Message):
    __slots__ = ("min_value", "max_value", "value", "step", "digit_count", "separator_position")
    MIN_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_VALUE_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    STEP_FIELD_NUMBER: _ClassVar[int]
    DIGIT_COUNT_FIELD_NUMBER: _ClassVar[int]
    SEPARATOR_POSITION_FIELD_NUMBER: _ClassVar[int]
    min_value: int
    max_value: int
    value: int
    step: int
    digit_count: int
    separator_position: int
    def __init__(self, min_value: _Optional[int] = ..., max_value: _Optional[int] = ..., value: _Optional[int] = ..., step: _Optional[int] = ..., digit_count: _Optional[int] = ..., separator_position: _Optional[int] = ...) -> None: ...

class SpinnerProps(_message.Message):
    __slots__ = ("spin_time", "arc_length")
    SPIN_TIME_FIELD_NUMBER: _ClassVar[int]
    ARC_LENGTH_FIELD_NUMBER: _ClassVar[int]
    spin_time: int
    arc_length: int
    def __init__(self, spin_time: _Optional[int] = ..., arc_length: _Optional[int] = ...) -> None: ...

class LedProps(_message.Message):
    __slots__ = ("color", "brightness")
    COLOR_FIELD_NUMBER: _ClassVar[int]
    BRIGHTNESS_FIELD_NUMBER: _ClassVar[int]
    color: Color
    brightness: int
    def __init__(self, color: _Optional[_Union[Color, _Mapping]] = ..., brightness: _Optional[int] = ...) -> None: ...

class LineProps(_message.Message):
    __slots__ = ("points", "y_invert")
    POINTS_FIELD_NUMBER: _ClassVar[int]
    Y_INVERT_FIELD_NUMBER: _ClassVar[int]
    points: _containers.RepeatedCompositeFieldContainer[Point]
    y_invert: bool
    def __init__(self, points: _Optional[_Iterable[_Union[Point, _Mapping]]] = ..., y_invert: bool = ...) -> None: ...

class ScaleProps(_message.Message):
    __slots__ = ("mode", "total_tick_count", "major_tick_every", "label_show", "min_value", "max_value", "rotation", "angle_range", "text_src", "post_draw", "sections")
    MODE_FIELD_NUMBER: _ClassVar[int]
    TOTAL_TICK_COUNT_FIELD_NUMBER: _ClassVar[int]
    MAJOR_TICK_EVERY_FIELD_NUMBER: _ClassVar[int]
    LABEL_SHOW_FIELD_NUMBER: _ClassVar[int]
    MIN_VALUE_FIELD_NUMBER: _ClassVar[int]
    MAX_VALUE_FIELD_NUMBER: _ClassVar[int]
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    ANGLE_RANGE_FIELD_NUMBER: _ClassVar[int]
    TEXT_SRC_FIELD_NUMBER: _ClassVar[int]
    POST_DRAW_FIELD_NUMBER: _ClassVar[int]
    SECTIONS_FIELD_NUMBER: _ClassVar[int]
    mode: ScaleMode
    total_tick_count: int
    major_tick_every: int
    label_show: bool
    min_value: int
    max_value: int
    rotation: int
    angle_range: int
    text_src: str
    post_draw: bool
    sections: _containers.RepeatedCompositeFieldContainer[ScaleSection]
    def __init__(self, mode: _Optional[_Union[ScaleMode, str]] = ..., total_tick_count: _Optional[int] = ..., major_tick_every: _Optional[int] = ..., label_show: bool = ..., min_value: _Optional[int] = ..., max_value: _Optional[int] = ..., rotation: _Optional[int] = ..., angle_range: _Optional[int] = ..., text_src: _Optional[str] = ..., post_draw: bool = ..., sections: _Optional[_Iterable[_Union[ScaleSection, _Mapping]]] = ...) -> None: ...

class ScaleSection(_message.Message):
    __slots__ = ("range_min", "range_max", "color", "width", "main_color", "main_width")
    RANGE_MIN_FIELD_NUMBER: _ClassVar[int]
    RANGE_MAX_FIELD_NUMBER: _ClassVar[int]
    COLOR_FIELD_NUMBER: _ClassVar[int]
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    MAIN_COLOR_FIELD_NUMBER: _ClassVar[int]
    MAIN_WIDTH_FIELD_NUMBER: _ClassVar[int]
    range_min: int
    range_max: int
    color: Color
    width: int
    main_color: Color
    main_width: int
    def __init__(self, range_min: _Optional[int] = ..., range_max: _Optional[int] = ..., color: _Optional[_Union[Color, _Mapping]] = ..., width: _Optional[int] = ..., main_color: _Optional[_Union[Color, _Mapping]] = ..., main_width: _Optional[int] = ...) -> None: ...

class ButtonMatrixProps(_message.Message):
    __slots__ = ("map_str", "one_check")
    MAP_STR_FIELD_NUMBER: _ClassVar[int]
    ONE_CHECK_FIELD_NUMBER: _ClassVar[int]
    map_str: str
    one_check: bool
    def __init__(self, map_str: _Optional[str] = ..., one_check: bool = ...) -> None: ...

class TableProps(_message.Message):
    __slots__ = ("row_count", "column_count")
    ROW_COUNT_FIELD_NUMBER: _ClassVar[int]
    COLUMN_COUNT_FIELD_NUMBER: _ClassVar[int]
    row_count: int
    column_count: int
    def __init__(self, row_count: _Optional[int] = ..., column_count: _Optional[int] = ...) -> None: ...

class TabviewProps(_message.Message):
    __slots__ = ("tab_names", "tab_bar_size", "active_index", "tab_bar_position", "tab_bar_pad_left")
    TAB_NAMES_FIELD_NUMBER: _ClassVar[int]
    TAB_BAR_SIZE_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_INDEX_FIELD_NUMBER: _ClassVar[int]
    TAB_BAR_POSITION_FIELD_NUMBER: _ClassVar[int]
    TAB_BAR_PAD_LEFT_FIELD_NUMBER: _ClassVar[int]
    tab_names: _containers.RepeatedScalarFieldContainer[str]
    tab_bar_size: int
    active_index: int
    tab_bar_position: Dir
    tab_bar_pad_left: int
    def __init__(self, tab_names: _Optional[_Iterable[str]] = ..., tab_bar_size: _Optional[int] = ..., active_index: _Optional[int] = ..., tab_bar_position: _Optional[_Union[Dir, str]] = ..., tab_bar_pad_left: _Optional[int] = ...) -> None: ...

class ChartSeries(_message.Message):
    __slots__ = ("color", "axis", "values")
    COLOR_FIELD_NUMBER: _ClassVar[int]
    AXIS_FIELD_NUMBER: _ClassVar[int]
    VALUES_FIELD_NUMBER: _ClassVar[int]
    color: Color
    axis: ChartAxis
    values: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, color: _Optional[_Union[Color, _Mapping]] = ..., axis: _Optional[_Union[ChartAxis, str]] = ..., values: _Optional[_Iterable[int]] = ...) -> None: ...

class ChartProps(_message.Message):
    __slots__ = ("type", "point_count", "has_div_lines", "hdiv_count", "vdiv_count", "series", "fade_area")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    POINT_COUNT_FIELD_NUMBER: _ClassVar[int]
    HAS_DIV_LINES_FIELD_NUMBER: _ClassVar[int]
    HDIV_COUNT_FIELD_NUMBER: _ClassVar[int]
    VDIV_COUNT_FIELD_NUMBER: _ClassVar[int]
    SERIES_FIELD_NUMBER: _ClassVar[int]
    FADE_AREA_FIELD_NUMBER: _ClassVar[int]
    type: ChartType
    point_count: int
    has_div_lines: bool
    hdiv_count: int
    vdiv_count: int
    series: _containers.RepeatedCompositeFieldContainer[ChartSeries]
    fade_area: bool
    def __init__(self, type: _Optional[_Union[ChartType, str]] = ..., point_count: _Optional[int] = ..., has_div_lines: bool = ..., hdiv_count: _Optional[int] = ..., vdiv_count: _Optional[int] = ..., series: _Optional[_Iterable[_Union[ChartSeries, _Mapping]]] = ..., fade_area: bool = ...) -> None: ...

class HostProxyProps(_message.Message):
    __slots__ = ("proxy_id", "mode", "min_w", "min_h", "max_w", "max_h", "handle_size", "z")
    PROXY_ID_FIELD_NUMBER: _ClassVar[int]
    MODE_FIELD_NUMBER: _ClassVar[int]
    MIN_W_FIELD_NUMBER: _ClassVar[int]
    MIN_H_FIELD_NUMBER: _ClassVar[int]
    MAX_W_FIELD_NUMBER: _ClassVar[int]
    MAX_H_FIELD_NUMBER: _ClassVar[int]
    HANDLE_SIZE_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    proxy_id: str
    mode: ProxyMode
    min_w: int
    min_h: int
    max_w: int
    max_h: int
    handle_size: int
    z: int
    def __init__(self, proxy_id: _Optional[str] = ..., mode: _Optional[_Union[ProxyMode, str]] = ..., min_w: _Optional[int] = ..., min_h: _Optional[int] = ..., max_w: _Optional[int] = ..., max_h: _Optional[int] = ..., handle_size: _Optional[int] = ..., z: _Optional[int] = ...) -> None: ...

class Point(_message.Message):
    __slots__ = ("x", "y")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ...) -> None: ...

class EventBinding(_message.Message):
    __slots__ = ("name", "trigger", "int_value", "include_widget_value", "set_subject", "set_value", "toggle", "notify_host", "cmd", "cmd_by_value")
    NAME_FIELD_NUMBER: _ClassVar[int]
    TRIGGER_FIELD_NUMBER: _ClassVar[int]
    INT_VALUE_FIELD_NUMBER: _ClassVar[int]
    INCLUDE_WIDGET_VALUE_FIELD_NUMBER: _ClassVar[int]
    SET_SUBJECT_FIELD_NUMBER: _ClassVar[int]
    SET_VALUE_FIELD_NUMBER: _ClassVar[int]
    TOGGLE_FIELD_NUMBER: _ClassVar[int]
    NOTIFY_HOST_FIELD_NUMBER: _ClassVar[int]
    CMD_FIELD_NUMBER: _ClassVar[int]
    CMD_BY_VALUE_FIELD_NUMBER: _ClassVar[int]
    name: str
    trigger: EventTrigger
    int_value: int
    include_widget_value: bool
    set_subject: str
    set_value: int
    toggle: bool
    notify_host: bool
    cmd: CmdSpec
    cmd_by_value: _containers.RepeatedCompositeFieldContainer[CmdSpec]
    def __init__(self, name: _Optional[str] = ..., trigger: _Optional[_Union[EventTrigger, str]] = ..., int_value: _Optional[int] = ..., include_widget_value: bool = ..., set_subject: _Optional[str] = ..., set_value: _Optional[int] = ..., toggle: bool = ..., notify_host: bool = ..., cmd: _Optional[_Union[CmdSpec, _Mapping]] = ..., cmd_by_value: _Optional[_Iterable[_Union[CmdSpec, _Mapping]]] = ...) -> None: ...

class FieldPatch(_message.Message):
    __slots__ = ("byte_offset", "byte_width", "kind", "wire_scale")
    BYTE_OFFSET_FIELD_NUMBER: _ClassVar[int]
    BYTE_WIDTH_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    WIRE_SCALE_FIELD_NUMBER: _ClassVar[int]
    byte_offset: int
    byte_width: int
    kind: PatchKind
    wire_scale: int
    def __init__(self, byte_offset: _Optional[int] = ..., byte_width: _Optional[int] = ..., kind: _Optional[_Union[PatchKind, str]] = ..., wire_scale: _Optional[int] = ...) -> None: ...

class CmdSpec(_message.Message):
    __slots__ = ("command_id", "root_template", "patches")
    COMMAND_ID_FIELD_NUMBER: _ClassVar[int]
    ROOT_TEMPLATE_FIELD_NUMBER: _ClassVar[int]
    PATCHES_FIELD_NUMBER: _ClassVar[int]
    command_id: str
    root_template: bytes
    patches: _containers.RepeatedCompositeFieldContainer[FieldPatch]
    def __init__(self, command_id: _Optional[str] = ..., root_template: _Optional[bytes] = ..., patches: _Optional[_Iterable[_Union[FieldPatch, _Mapping]]] = ...) -> None: ...

class GestureSpec(_message.Message):
    __slots__ = ("kind", "cmd")
    KIND_FIELD_NUMBER: _ClassVar[int]
    CMD_FIELD_NUMBER: _ClassVar[int]
    kind: GestureKind
    cmd: CmdSpec
    def __init__(self, kind: _Optional[_Union[GestureKind, str]] = ..., cmd: _Optional[_Union[CmdSpec, _Mapping]] = ...) -> None: ...

class VisibilityBinding(_message.Message):
    __slots__ = ("subject", "ref_value", "compare")
    SUBJECT_FIELD_NUMBER: _ClassVar[int]
    REF_VALUE_FIELD_NUMBER: _ClassVar[int]
    COMPARE_FIELD_NUMBER: _ClassVar[int]
    subject: str
    ref_value: int
    compare: CompareOp
    def __init__(self, subject: _Optional[str] = ..., ref_value: _Optional[int] = ..., compare: _Optional[_Union[CompareOp, str]] = ...) -> None: ...

class Layout(_message.Message):
    __slots__ = ("flow", "main_place", "cross_place", "track_place")
    FLOW_FIELD_NUMBER: _ClassVar[int]
    MAIN_PLACE_FIELD_NUMBER: _ClassVar[int]
    CROSS_PLACE_FIELD_NUMBER: _ClassVar[int]
    TRACK_PLACE_FIELD_NUMBER: _ClassVar[int]
    flow: FlexFlow
    main_place: FlexAlign
    cross_place: FlexAlign
    track_place: FlexAlign
    def __init__(self, flow: _Optional[_Union[FlexFlow, str]] = ..., main_place: _Optional[_Union[FlexAlign, str]] = ..., cross_place: _Optional[_Union[FlexAlign, str]] = ..., track_place: _Optional[_Union[FlexAlign, str]] = ...) -> None: ...

class StyleGroup(_message.Message):
    __slots__ = ("state_selector", "variants")
    STATE_SELECTOR_FIELD_NUMBER: _ClassVar[int]
    VARIANTS_FIELD_NUMBER: _ClassVar[int]
    state_selector: int
    variants: _containers.RepeatedCompositeFieldContainer[StyleVariant]
    def __init__(self, state_selector: _Optional[int] = ..., variants: _Optional[_Iterable[_Union[StyleVariant, _Mapping]]] = ...) -> None: ...

class StyleVariant(_message.Message):
    __slots__ = ("variant_index", "properties")
    VARIANT_INDEX_FIELD_NUMBER: _ClassVar[int]
    PROPERTIES_FIELD_NUMBER: _ClassVar[int]
    variant_index: int
    properties: _containers.RepeatedCompositeFieldContainer[StyleProperty]
    def __init__(self, variant_index: _Optional[int] = ..., properties: _Optional[_Iterable[_Union[StyleProperty, _Mapping]]] = ...) -> None: ...

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
