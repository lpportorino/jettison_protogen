from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Mapping as _Mapping, Optional as _Optional, Union as _Union

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

class JonGuiDataTargetType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_TARGET_TYPE_UNSPECIFIED: _ClassVar[JonGuiDataTargetType]
    JON_GUI_DATA_TARGET_TYPE_TARGET: _ClassVar[JonGuiDataTargetType]
    JON_GUI_DATA_TARGET_TYPE_PHOTO: _ClassVar[JonGuiDataTargetType]

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

class JonGuiDataClientApp(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_CLIENT_APP_UNSPECIFIED: _ClassVar[JonGuiDataClientApp]
    JON_GUI_DATA_CLIENT_APP_BROWSER_UI: _ClassVar[JonGuiDataClientApp]
    JON_GUI_DATA_CLIENT_APP_BROWSER_MAP: _ClassVar[JonGuiDataClientApp]
    JON_GUI_DATA_CLIENT_APP_DESKTOP_NATIVE: _ClassVar[JonGuiDataClientApp]
    JON_GUI_DATA_CLIENT_APP_MOBILE_NATIVE: _ClassVar[JonGuiDataClientApp]

class JonGuiDataExtBatStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_EXT_BAT_STATUS_UNSPECIFIED: _ClassVar[JonGuiDataExtBatStatus]
    JON_GUI_DATA_EXT_BAT_STATUS_CHARGING: _ClassVar[JonGuiDataExtBatStatus]
    JON_GUI_DATA_EXT_BAT_STATUS_DISCHARGING: _ClassVar[JonGuiDataExtBatStatus]
    JON_GUI_DATA_EXT_BAT_STATUS_BALANCING: _ClassVar[JonGuiDataExtBatStatus]

class JonGuiDataStateSource(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_STATE_SOURCE_UNSPECIFIED: _ClassVar[JonGuiDataStateSource]
    JON_GUI_DATA_STATE_SOURCE_DAY_PIPELINE: _ClassVar[JonGuiDataStateSource]
    JON_GUI_DATA_STATE_SOURCE_HEAT_PIPELINE: _ClassVar[JonGuiDataStateSource]
    JON_GUI_DATA_STATE_SOURCE_SYSTEM: _ClassVar[JonGuiDataStateSource]

class JonGuiDataDriveProgram(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_DRIVE_PROGRAM_NONE: _ClassVar[JonGuiDataDriveProgram]
    JON_GUI_DATA_DRIVE_PROGRAM_SCAN: _ClassVar[JonGuiDataDriveProgram]
    JON_GUI_DATA_DRIVE_PROGRAM_POI: _ClassVar[JonGuiDataDriveProgram]
    JON_GUI_DATA_DRIVE_PROGRAM_PARK: _ClassVar[JonGuiDataDriveProgram]
    JON_GUI_DATA_DRIVE_PROGRAM_COMPASS: _ClassVar[JonGuiDataDriveProgram]

class JonGuiDataDriveState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    JON_GUI_DATA_DRIVE_STATE_UNSPECIFIED: _ClassVar[JonGuiDataDriveState]
    JON_GUI_DATA_DRIVE_STATE_IDLE: _ClassVar[JonGuiDataDriveState]
    JON_GUI_DATA_DRIVE_STATE_ARMED: _ClassVar[JonGuiDataDriveState]
    JON_GUI_DATA_DRIVE_STATE_RUNNING: _ClassVar[JonGuiDataDriveState]
    JON_GUI_DATA_DRIVE_STATE_PAUSED: _ClassVar[JonGuiDataDriveState]
    JON_GUI_DATA_DRIVE_STATE_DONE: _ClassVar[JonGuiDataDriveState]
    JON_GUI_DATA_DRIVE_STATE_FAULT: _ClassVar[JonGuiDataDriveState]
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
JON_GUI_DATA_TARGET_TYPE_UNSPECIFIED: JonGuiDataTargetType
JON_GUI_DATA_TARGET_TYPE_TARGET: JonGuiDataTargetType
JON_GUI_DATA_TARGET_TYPE_PHOTO: JonGuiDataTargetType
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
JON_GUI_DATA_CLIENT_APP_UNSPECIFIED: JonGuiDataClientApp
JON_GUI_DATA_CLIENT_APP_BROWSER_UI: JonGuiDataClientApp
JON_GUI_DATA_CLIENT_APP_BROWSER_MAP: JonGuiDataClientApp
JON_GUI_DATA_CLIENT_APP_DESKTOP_NATIVE: JonGuiDataClientApp
JON_GUI_DATA_CLIENT_APP_MOBILE_NATIVE: JonGuiDataClientApp
JON_GUI_DATA_EXT_BAT_STATUS_UNSPECIFIED: JonGuiDataExtBatStatus
JON_GUI_DATA_EXT_BAT_STATUS_CHARGING: JonGuiDataExtBatStatus
JON_GUI_DATA_EXT_BAT_STATUS_DISCHARGING: JonGuiDataExtBatStatus
JON_GUI_DATA_EXT_BAT_STATUS_BALANCING: JonGuiDataExtBatStatus
JON_GUI_DATA_STATE_SOURCE_UNSPECIFIED: JonGuiDataStateSource
JON_GUI_DATA_STATE_SOURCE_DAY_PIPELINE: JonGuiDataStateSource
JON_GUI_DATA_STATE_SOURCE_HEAT_PIPELINE: JonGuiDataStateSource
JON_GUI_DATA_STATE_SOURCE_SYSTEM: JonGuiDataStateSource
JON_GUI_DATA_DRIVE_PROGRAM_NONE: JonGuiDataDriveProgram
JON_GUI_DATA_DRIVE_PROGRAM_SCAN: JonGuiDataDriveProgram
JON_GUI_DATA_DRIVE_PROGRAM_POI: JonGuiDataDriveProgram
JON_GUI_DATA_DRIVE_PROGRAM_PARK: JonGuiDataDriveProgram
JON_GUI_DATA_DRIVE_PROGRAM_COMPASS: JonGuiDataDriveProgram
JON_GUI_DATA_DRIVE_STATE_UNSPECIFIED: JonGuiDataDriveState
JON_GUI_DATA_DRIVE_STATE_IDLE: JonGuiDataDriveState
JON_GUI_DATA_DRIVE_STATE_ARMED: JonGuiDataDriveState
JON_GUI_DATA_DRIVE_STATE_RUNNING: JonGuiDataDriveState
JON_GUI_DATA_DRIVE_STATE_PAUSED: JonGuiDataDriveState
JON_GUI_DATA_DRIVE_STATE_DONE: JonGuiDataDriveState
JON_GUI_DATA_DRIVE_STATE_FAULT: JonGuiDataDriveState

class JonGuiDataMeteo(_message.Message):
    __slots__ = ("temperature", "humidity", "pressure")
    TEMPERATURE_FIELD_NUMBER: _ClassVar[int]
    HUMIDITY_FIELD_NUMBER: _ClassVar[int]
    PRESSURE_FIELD_NUMBER: _ClassVar[int]
    temperature: float
    humidity: float
    pressure: float
    def __init__(self, temperature: _Optional[float] = ..., humidity: _Optional[float] = ..., pressure: _Optional[float] = ...) -> None: ...

class JonOpaquePayloadVersion(_message.Message):
    __slots__ = ("major", "minor", "build")
    MAJOR_FIELD_NUMBER: _ClassVar[int]
    MINOR_FIELD_NUMBER: _ClassVar[int]
    BUILD_FIELD_NUMBER: _ClassVar[int]
    major: int
    minor: int
    build: int
    def __init__(self, major: _Optional[int] = ..., minor: _Optional[int] = ..., build: _Optional[int] = ...) -> None: ...

class JonOpaquePayload(_message.Message):
    __slots__ = ("type_uuid", "version", "payload")
    TYPE_UUID_FIELD_NUMBER: _ClassVar[int]
    VERSION_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    type_uuid: str
    version: JonOpaquePayloadVersion
    payload: bytes
    def __init__(self, type_uuid: _Optional[str] = ..., version: _Optional[_Union[JonOpaquePayloadVersion, _Mapping]] = ..., payload: _Optional[bytes] = ...) -> None: ...

class JonGuiDataROI(_message.Message):
    __slots__ = ("x1", "y1", "x2", "y2")
    X1_FIELD_NUMBER: _ClassVar[int]
    Y1_FIELD_NUMBER: _ClassVar[int]
    X2_FIELD_NUMBER: _ClassVar[int]
    Y2_FIELD_NUMBER: _ClassVar[int]
    x1: float
    y1: float
    x2: float
    y2: float
    def __init__(self, x1: _Optional[float] = ..., y1: _Optional[float] = ..., x2: _Optional[float] = ..., y2: _Optional[float] = ...) -> None: ...

class JonGuiDataSharpness(_message.Message):
    __slots__ = ("value", "derivative_1", "derivative_2")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    DERIVATIVE_1_FIELD_NUMBER: _ClassVar[int]
    DERIVATIVE_2_FIELD_NUMBER: _ClassVar[int]
    value: float
    derivative_1: float
    derivative_2: float
    def __init__(self, value: _Optional[float] = ..., derivative_1: _Optional[float] = ..., derivative_2: _Optional[float] = ...) -> None: ...

class JonGuiDataVector3(_message.Message):
    __slots__ = ("x", "y", "z")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    z: float
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ...) -> None: ...

class JonGuiDataQuaternion(_message.Message):
    __slots__ = ("w", "x", "y", "z")
    W_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    w: float
    x: float
    y: float
    z: float
    def __init__(self, w: _Optional[float] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ...) -> None: ...

class JonGuiDataTransform3D(_message.Message):
    __slots__ = ("position", "orientation", "linear_velocity", "angular_velocity")
    POSITION_FIELD_NUMBER: _ClassVar[int]
    ORIENTATION_FIELD_NUMBER: _ClassVar[int]
    LINEAR_VELOCITY_FIELD_NUMBER: _ClassVar[int]
    ANGULAR_VELOCITY_FIELD_NUMBER: _ClassVar[int]
    position: JonGuiDataVector3
    orientation: JonGuiDataQuaternion
    linear_velocity: JonGuiDataVector3
    angular_velocity: JonGuiDataVector3
    def __init__(self, position: _Optional[_Union[JonGuiDataVector3, _Mapping]] = ..., orientation: _Optional[_Union[JonGuiDataQuaternion, _Mapping]] = ..., linear_velocity: _Optional[_Union[JonGuiDataVector3, _Mapping]] = ..., angular_velocity: _Optional[_Union[JonGuiDataVector3, _Mapping]] = ...) -> None: ...

class JonGuiDataTrackedObject(_message.Message):
    __slots__ = ("uuid", "transform", "bounding_box", "state")
    class TrackingState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        TRACKING_STATE_UNSPECIFIED: _ClassVar[JonGuiDataTrackedObject.TrackingState]
        TRACKING_STATE_ACQUIRING: _ClassVar[JonGuiDataTrackedObject.TrackingState]
        TRACKING_STATE_TRACKING: _ClassVar[JonGuiDataTrackedObject.TrackingState]
        TRACKING_STATE_PREDICTED: _ClassVar[JonGuiDataTrackedObject.TrackingState]
        TRACKING_STATE_LOST: _ClassVar[JonGuiDataTrackedObject.TrackingState]
    TRACKING_STATE_UNSPECIFIED: JonGuiDataTrackedObject.TrackingState
    TRACKING_STATE_ACQUIRING: JonGuiDataTrackedObject.TrackingState
    TRACKING_STATE_TRACKING: JonGuiDataTrackedObject.TrackingState
    TRACKING_STATE_PREDICTED: JonGuiDataTrackedObject.TrackingState
    TRACKING_STATE_LOST: JonGuiDataTrackedObject.TrackingState
    UUID_FIELD_NUMBER: _ClassVar[int]
    TRANSFORM_FIELD_NUMBER: _ClassVar[int]
    BOUNDING_BOX_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    uuid: str
    transform: JonGuiDataTransform3D
    bounding_box: JonGuiDataROI
    state: JonGuiDataTrackedObject.TrackingState
    def __init__(self, uuid: _Optional[str] = ..., transform: _Optional[_Union[JonGuiDataTransform3D, _Mapping]] = ..., bounding_box: _Optional[_Union[JonGuiDataROI, _Mapping]] = ..., state: _Optional[_Union[JonGuiDataTrackedObject.TrackingState, str]] = ...) -> None: ...
