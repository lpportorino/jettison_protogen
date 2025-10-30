from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class JonGuiDataVideoChannelHeatFilters(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_UNSPECIFIED: _ClassVar[JonGuiDataVideoChannelHeatFilters]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_WHITE: _ClassVar[JonGuiDataVideoChannelHeatFilters]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_BLACK: _ClassVar[JonGuiDataVideoChannelHeatFilters]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA: _ClassVar[JonGuiDataVideoChannelHeatFilters]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA_INVERSE: _ClassVar[JonGuiDataVideoChannelHeatFilters]

class JonGuiDataVideoChannelHeatAGCModes(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_UNSPECIFIED: _ClassVar[JonGuiDataVideoChannelHeatAGCModes]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_1: _ClassVar[JonGuiDataVideoChannelHeatAGCModes]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_2: _ClassVar[JonGuiDataVideoChannelHeatAGCModes]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_3: _ClassVar[JonGuiDataVideoChannelHeatAGCModes]

class JonGuiDataGpsUnits(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_GPS_UNITS_UNSPECIFIED: _ClassVar[JonGuiDataGpsUnits]
    JON_GUI_DATA_GPS_UNITS_DECIMAL_DEGREES: _ClassVar[JonGuiDataGpsUnits]
    JON_GUI_DATA_GPS_UNITS_DEGREES_MINUTES_SECONDS: _ClassVar[JonGuiDataGpsUnits]
    JON_GUI_DATA_GPS_UNITS_DEGREES_DECIMAL_MINUTES: _ClassVar[JonGuiDataGpsUnits]

class JonGuiDataGpsFixType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_GPS_FIX_TYPE_UNSPECIFIED: _ClassVar[JonGuiDataGpsFixType]
    JON_GUI_DATA_GPS_FIX_TYPE_NONE: _ClassVar[JonGuiDataGpsFixType]
    JON_GUI_DATA_GPS_FIX_TYPE_1D: _ClassVar[JonGuiDataGpsFixType]
    JON_GUI_DATA_GPS_FIX_TYPE_2D: _ClassVar[JonGuiDataGpsFixType]
    JON_GUI_DATA_GPS_FIX_TYPE_3D: _ClassVar[JonGuiDataGpsFixType]
    JON_GUI_DATA_GPS_FIX_TYPE_MANUAL: _ClassVar[JonGuiDataGpsFixType]

class JonGuiDataCompassUnits(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_COMPASS_UNITS_UNSPECIFIED: _ClassVar[JonGuiDataCompassUnits]
    JON_GUI_DATA_COMPASS_UNITS_DEGREES: _ClassVar[JonGuiDataCompassUnits]
    JON_GUI_DATA_COMPASS_UNITS_MILS: _ClassVar[JonGuiDataCompassUnits]
    JON_GUI_DATA_COMPASS_UNITS_GRAD: _ClassVar[JonGuiDataCompassUnits]
    JON_GUI_DATA_COMPASS_UNITS_MRAD: _ClassVar[JonGuiDataCompassUnits]

class JonGuiDataAccumulatorStateIdx(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_ACCUMULATOR_STATE_UNSPECIFIED: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_UNKNOWN: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_EMPTY: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_1: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_2: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_3: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_4: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_5: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_6: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_FULL: _ClassVar[JonGuiDataAccumulatorStateIdx]
    JON_GUI_DATA_ACCUMULATOR_STATE_CHARGING: _ClassVar[JonGuiDataAccumulatorStateIdx]

class JonGuiDataTimeFormats(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_TIME_FORMAT_UNSPECIFIED: _ClassVar[JonGuiDataTimeFormats]
    JON_GUI_DATA_TIME_FORMAT_H_M_S: _ClassVar[JonGuiDataTimeFormats]
    JON_GUI_DATA_TIME_FORMAT_Y_m_D_H_M_S: _ClassVar[JonGuiDataTimeFormats]

class JonGuiDataRotaryDirection(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED: _ClassVar[JonGuiDataRotaryDirection]
    JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE: _ClassVar[JonGuiDataRotaryDirection]
    JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE: _ClassVar[JonGuiDataRotaryDirection]

class JonGuiDataLrfScanModes(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_LRF_SCAN_MODE_UNSPECIFIED: _ClassVar[JonGuiDataLrfScanModes]
    JON_GUI_DATA_LRF_SCAN_MODE_1_HZ_CONTINUOUS: _ClassVar[JonGuiDataLrfScanModes]
    JON_GUI_DATA_LRF_SCAN_MODE_4_HZ_CONTINUOUS: _ClassVar[JonGuiDataLrfScanModes]
    JON_GUI_DATA_LRF_SCAN_MODE_10_HZ_CONTINUOUS: _ClassVar[JonGuiDataLrfScanModes]
    JON_GUI_DATA_LRF_SCAN_MODE_20_HZ_CONTINUOUS: _ClassVar[JonGuiDataLrfScanModes]
    JON_GUI_DATA_LRF_SCAN_MODE_100_HZ_CONTINUOUS: _ClassVar[JonGuiDataLrfScanModes]
    JON_GUI_DATA_LRF_SCAN_MODE_200_HZ_CONTINUOUS: _ClassVar[JonGuiDataLrfScanModes]

class JonGuiDatatLrfLaserPointerModes(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_LRF_LASER_POINTER_MODE_UNSPECIFIED: _ClassVar[JonGuiDatatLrfLaserPointerModes]
    JON_GUI_DATA_LRF_LASER_POINTER_MODE_OFF: _ClassVar[JonGuiDatatLrfLaserPointerModes]
    JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_1: _ClassVar[JonGuiDatatLrfLaserPointerModes]
    JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_2: _ClassVar[JonGuiDatatLrfLaserPointerModes]

class JonGuiDataCompassCalibrateStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_UNSPECIFIED: _ClassVar[JonGuiDataCompassCalibrateStatus]
    JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_NOT_CALIBRATING: _ClassVar[JonGuiDataCompassCalibrateStatus]
    JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_SHORT: _ClassVar[JonGuiDataCompassCalibrateStatus]
    JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_LONG: _ClassVar[JonGuiDataCompassCalibrateStatus]
    JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_FINISHED: _ClassVar[JonGuiDataCompassCalibrateStatus]
    JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_ERROR: _ClassVar[JonGuiDataCompassCalibrateStatus]

class JonGuiDataRotaryMode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_ROTARY_MODE_UNSPECIFIED: _ClassVar[JonGuiDataRotaryMode]
    JON_GUI_DATA_ROTARY_MODE_INITIALIZATION: _ClassVar[JonGuiDataRotaryMode]
    JON_GUI_DATA_ROTARY_MODE_SPEED: _ClassVar[JonGuiDataRotaryMode]
    JON_GUI_DATA_ROTARY_MODE_POSITION: _ClassVar[JonGuiDataRotaryMode]
    JON_GUI_DATA_ROTARY_MODE_STABILIZATION: _ClassVar[JonGuiDataRotaryMode]
    JON_GUI_DATA_ROTARY_MODE_TARGETING: _ClassVar[JonGuiDataRotaryMode]
    JON_GUI_DATA_ROTARY_MODE_VIDEO_TRACKER: _ClassVar[JonGuiDataRotaryMode]

class JonGuiDataVideoChannel(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_VIDEO_CHANNEL_UNSPECIFIED: _ClassVar[JonGuiDataVideoChannel]
    JON_GUI_DATA_VIDEO_CHANNEL_HEAT: _ClassVar[JonGuiDataVideoChannel]
    JON_GUI_DATA_VIDEO_CHANNEL_DAY: _ClassVar[JonGuiDataVideoChannel]

class JonGuiDataRecOsdScreen(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_REC_OSD_SCREEN_UNSPECIFIED: _ClassVar[JonGuiDataRecOsdScreen]
    JON_GUI_DATA_REC_OSD_SCREEN_MAIN: _ClassVar[JonGuiDataRecOsdScreen]
    JON_GUI_DATA_REC_OSD_SCREEN_LRF_MEASURE: _ClassVar[JonGuiDataRecOsdScreen]
    JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT: _ClassVar[JonGuiDataRecOsdScreen]
    JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT_SIMPLIFIED: _ClassVar[JonGuiDataRecOsdScreen]

class JonGuiDataFxModeDay(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_FX_MODE_DAY_DEFAULT: _ClassVar[JonGuiDataFxModeDay]
    JON_GUI_DATA_FX_MODE_DAY_A: _ClassVar[JonGuiDataFxModeDay]
    JON_GUI_DATA_FX_MODE_DAY_B: _ClassVar[JonGuiDataFxModeDay]
    JON_GUI_DATA_FX_MODE_DAY_C: _ClassVar[JonGuiDataFxModeDay]
    JON_GUI_DATA_FX_MODE_DAY_D: _ClassVar[JonGuiDataFxModeDay]
    JON_GUI_DATA_FX_MODE_DAY_E: _ClassVar[JonGuiDataFxModeDay]
    JON_GUI_DATA_FX_MODE_DAY_F: _ClassVar[JonGuiDataFxModeDay]

class JonGuiDataFxModeHeat(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_FX_MODE_HEAT_DEFAULT: _ClassVar[JonGuiDataFxModeHeat]
    JON_GUI_DATA_FX_MODE_HEAT_A: _ClassVar[JonGuiDataFxModeHeat]
    JON_GUI_DATA_FX_MODE_HEAT_B: _ClassVar[JonGuiDataFxModeHeat]
    JON_GUI_DATA_FX_MODE_HEAT_C: _ClassVar[JonGuiDataFxModeHeat]
    JON_GUI_DATA_FX_MODE_HEAT_D: _ClassVar[JonGuiDataFxModeHeat]
    JON_GUI_DATA_FX_MODE_HEAT_E: _ClassVar[JonGuiDataFxModeHeat]
    JON_GUI_DATA_FX_MODE_HEAT_F: _ClassVar[JonGuiDataFxModeHeat]

class JonGuiDataSystemLocalizations(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_SYSTEM_LOCALIZATION_UNSPECIFIED: _ClassVar[JonGuiDataSystemLocalizations]
    JON_GUI_DATA_SYSTEM_LOCALIZATION_EN: _ClassVar[JonGuiDataSystemLocalizations]
    JON_GUI_DATA_SYSTEM_LOCALIZATION_UA: _ClassVar[JonGuiDataSystemLocalizations]
    JON_GUI_DATA_SYSTEM_LOCALIZATION_AR: _ClassVar[JonGuiDataSystemLocalizations]
    JON_GUI_DATA_SYSTEM_LOCALIZATION_CS: _ClassVar[JonGuiDataSystemLocalizations]

class JonGuiDataClientType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_CLIENT_TYPE_UNSPECIFIED: _ClassVar[JonGuiDataClientType]
    JON_GUI_DATA_CLIENT_TYPE_INTERNAL_CV: _ClassVar[JonGuiDataClientType]
    JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK: _ClassVar[JonGuiDataClientType]
    JON_GUI_DATA_CLIENT_TYPE_CERTIFICATE_PROTECTED: _ClassVar[JonGuiDataClientType]
    JON_GUI_DATA_CLIENT_TYPE_LIRA: _ClassVar[JonGuiDataClientType]
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_UNSPECIFIED: JonGuiDataVideoChannelHeatFilters
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_WHITE: JonGuiDataVideoChannelHeatFilters
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_HOT_BLACK: JonGuiDataVideoChannelHeatFilters
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA: JonGuiDataVideoChannelHeatFilters
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_FILTER_SEPIA_INVERSE: JonGuiDataVideoChannelHeatFilters
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_UNSPECIFIED: JonGuiDataVideoChannelHeatAGCModes
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_1: JonGuiDataVideoChannelHeatAGCModes
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_2: JonGuiDataVideoChannelHeatAGCModes
JON_GUI_DATA_VIDEO_CHANNEL_HEAT_AGC_MODE_3: JonGuiDataVideoChannelHeatAGCModes
JON_GUI_DATA_GPS_UNITS_UNSPECIFIED: JonGuiDataGpsUnits
JON_GUI_DATA_GPS_UNITS_DECIMAL_DEGREES: JonGuiDataGpsUnits
JON_GUI_DATA_GPS_UNITS_DEGREES_MINUTES_SECONDS: JonGuiDataGpsUnits
JON_GUI_DATA_GPS_UNITS_DEGREES_DECIMAL_MINUTES: JonGuiDataGpsUnits
JON_GUI_DATA_GPS_FIX_TYPE_UNSPECIFIED: JonGuiDataGpsFixType
JON_GUI_DATA_GPS_FIX_TYPE_NONE: JonGuiDataGpsFixType
JON_GUI_DATA_GPS_FIX_TYPE_1D: JonGuiDataGpsFixType
JON_GUI_DATA_GPS_FIX_TYPE_2D: JonGuiDataGpsFixType
JON_GUI_DATA_GPS_FIX_TYPE_3D: JonGuiDataGpsFixType
JON_GUI_DATA_GPS_FIX_TYPE_MANUAL: JonGuiDataGpsFixType
JON_GUI_DATA_COMPASS_UNITS_UNSPECIFIED: JonGuiDataCompassUnits
JON_GUI_DATA_COMPASS_UNITS_DEGREES: JonGuiDataCompassUnits
JON_GUI_DATA_COMPASS_UNITS_MILS: JonGuiDataCompassUnits
JON_GUI_DATA_COMPASS_UNITS_GRAD: JonGuiDataCompassUnits
JON_GUI_DATA_COMPASS_UNITS_MRAD: JonGuiDataCompassUnits
JON_GUI_DATA_ACCUMULATOR_STATE_UNSPECIFIED: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_UNKNOWN: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_EMPTY: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_1: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_2: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_3: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_4: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_5: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_6: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_FULL: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_ACCUMULATOR_STATE_CHARGING: JonGuiDataAccumulatorStateIdx
JON_GUI_DATA_TIME_FORMAT_UNSPECIFIED: JonGuiDataTimeFormats
JON_GUI_DATA_TIME_FORMAT_H_M_S: JonGuiDataTimeFormats
JON_GUI_DATA_TIME_FORMAT_Y_m_D_H_M_S: JonGuiDataTimeFormats
JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED: JonGuiDataRotaryDirection
JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE: JonGuiDataRotaryDirection
JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE: JonGuiDataRotaryDirection
JON_GUI_DATA_LRF_SCAN_MODE_UNSPECIFIED: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_SCAN_MODE_1_HZ_CONTINUOUS: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_SCAN_MODE_4_HZ_CONTINUOUS: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_SCAN_MODE_10_HZ_CONTINUOUS: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_SCAN_MODE_20_HZ_CONTINUOUS: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_SCAN_MODE_100_HZ_CONTINUOUS: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_SCAN_MODE_200_HZ_CONTINUOUS: JonGuiDataLrfScanModes
JON_GUI_DATA_LRF_LASER_POINTER_MODE_UNSPECIFIED: JonGuiDatatLrfLaserPointerModes
JON_GUI_DATA_LRF_LASER_POINTER_MODE_OFF: JonGuiDatatLrfLaserPointerModes
JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_1: JonGuiDatatLrfLaserPointerModes
JON_GUI_DATA_LRF_LASER_POINTER_MODE_ON_2: JonGuiDatatLrfLaserPointerModes
JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_UNSPECIFIED: JonGuiDataCompassCalibrateStatus
JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_NOT_CALIBRATING: JonGuiDataCompassCalibrateStatus
JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_SHORT: JonGuiDataCompassCalibrateStatus
JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_CALIBRATING_LONG: JonGuiDataCompassCalibrateStatus
JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_FINISHED: JonGuiDataCompassCalibrateStatus
JON_GUI_DATA_COMPASS_CALIBRATE_STATUS_ERROR: JonGuiDataCompassCalibrateStatus
JON_GUI_DATA_ROTARY_MODE_UNSPECIFIED: JonGuiDataRotaryMode
JON_GUI_DATA_ROTARY_MODE_INITIALIZATION: JonGuiDataRotaryMode
JON_GUI_DATA_ROTARY_MODE_SPEED: JonGuiDataRotaryMode
JON_GUI_DATA_ROTARY_MODE_POSITION: JonGuiDataRotaryMode
JON_GUI_DATA_ROTARY_MODE_STABILIZATION: JonGuiDataRotaryMode
JON_GUI_DATA_ROTARY_MODE_TARGETING: JonGuiDataRotaryMode
JON_GUI_DATA_ROTARY_MODE_VIDEO_TRACKER: JonGuiDataRotaryMode
JON_GUI_DATA_VIDEO_CHANNEL_UNSPECIFIED: JonGuiDataVideoChannel
JON_GUI_DATA_VIDEO_CHANNEL_HEAT: JonGuiDataVideoChannel
JON_GUI_DATA_VIDEO_CHANNEL_DAY: JonGuiDataVideoChannel
JON_GUI_DATA_REC_OSD_SCREEN_UNSPECIFIED: JonGuiDataRecOsdScreen
JON_GUI_DATA_REC_OSD_SCREEN_MAIN: JonGuiDataRecOsdScreen
JON_GUI_DATA_REC_OSD_SCREEN_LRF_MEASURE: JonGuiDataRecOsdScreen
JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT: JonGuiDataRecOsdScreen
JON_GUI_DATA_REC_OSD_SCREEN_LRF_RESULT_SIMPLIFIED: JonGuiDataRecOsdScreen
JON_GUI_DATA_FX_MODE_DAY_DEFAULT: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_DAY_A: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_DAY_B: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_DAY_C: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_DAY_D: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_DAY_E: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_DAY_F: JonGuiDataFxModeDay
JON_GUI_DATA_FX_MODE_HEAT_DEFAULT: JonGuiDataFxModeHeat
JON_GUI_DATA_FX_MODE_HEAT_A: JonGuiDataFxModeHeat
JON_GUI_DATA_FX_MODE_HEAT_B: JonGuiDataFxModeHeat
JON_GUI_DATA_FX_MODE_HEAT_C: JonGuiDataFxModeHeat
JON_GUI_DATA_FX_MODE_HEAT_D: JonGuiDataFxModeHeat
JON_GUI_DATA_FX_MODE_HEAT_E: JonGuiDataFxModeHeat
JON_GUI_DATA_FX_MODE_HEAT_F: JonGuiDataFxModeHeat
JON_GUI_DATA_SYSTEM_LOCALIZATION_UNSPECIFIED: JonGuiDataSystemLocalizations
JON_GUI_DATA_SYSTEM_LOCALIZATION_EN: JonGuiDataSystemLocalizations
JON_GUI_DATA_SYSTEM_LOCALIZATION_UA: JonGuiDataSystemLocalizations
JON_GUI_DATA_SYSTEM_LOCALIZATION_AR: JonGuiDataSystemLocalizations
JON_GUI_DATA_SYSTEM_LOCALIZATION_CS: JonGuiDataSystemLocalizations
JON_GUI_DATA_CLIENT_TYPE_UNSPECIFIED: JonGuiDataClientType
JON_GUI_DATA_CLIENT_TYPE_INTERNAL_CV: JonGuiDataClientType
JON_GUI_DATA_CLIENT_TYPE_LOCAL_NETWORK: JonGuiDataClientType
JON_GUI_DATA_CLIENT_TYPE_CERTIFICATE_PROTECTED: JonGuiDataClientType
JON_GUI_DATA_CLIENT_TYPE_LIRA: JonGuiDataClientType

class JonGuiDataMeteo(_message.Message):
    __slots__ = ("temperature", "humidity", "pressure")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    HUMIDITY_FIELD_NUMBER: _ClassVar[int]
    PRESSURE_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    humidity: float
    pressure: float
    def __init__(self, temperature: _Optional[float] = ..., humidity: _Optional[float] = ..., pressure: _Optional[float] = ...) -> None: ...
