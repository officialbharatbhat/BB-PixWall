package bb.pix.wall.settings


enum class WallpaperTargetMode(val label: String) {
    HOME("Home only"), LOCK("Lock only"), BOTH_SAME("Home + Lock (same)"), BOTH_DIFFERENT("Home + Lock (different)"),
}

enum class TriggerMode(val label: String) {
    INTERVAL("Interval"), SCREEN_OFF("Screen off"), SCREEN_ON("Screen on"), SCREEN_OFF_OR_ON("Screen off / on"), MANUAL("Manual only"),
}

enum class AppearanceMode(val label: String) {
    LIGHT("Light"), DARK("Dark"), PITCH_BLACK("Pitch Black"), SYSTEM("System Mode"), SYSTEM_MONET("System Monet"),
}

enum class WallpaperOrder(val label: String) {
    A_Z("A–Z"), Z_A("Z–A"), DATE_NEWEST("Date newest → oldest"), DATE_OLDEST("Date oldest → newest"),
    SIZE_LOW_HIGH("Size low → high"), SIZE_HIGH_LOW("Size high → low"), SURPRISE("Surprise"), RANDOM_SHUFFLE("Random shuffle"),
}

enum class EngineMode(val label: String) { STANDARD("Standard"), ADVANCED("Advance"), }

enum class AspectPreference(val label: String) { ANY("Any"), PORTRAIT("Portrait"), LANDSCAPE("Landscape"), SCREEN_MATCH("Match screen"), }

enum class SourcePriorityMode(val label: String) {
    SMART_BALANCED("Smart balanced"),
    PHOTOS_FIRST("Google Photos first"),
    DRIVE_FIRST("Google Drive first"),
    LOCAL_FIRST("Local first"),
}

data class AppSettings(
    val autoChange: Boolean = false,
    val triggerMode: TriggerMode = TriggerMode.INTERVAL,
    val intervalMinutes: Int = 30,
    val targetMode: WallpaperTargetMode = WallpaperTargetMode.BOTH_DIFFERENT,
    val wallpaperOrder: WallpaperOrder = WallpaperOrder.RANDOM_SHUFFLE,
    val engineMode: EngineMode = EngineMode.STANDARD,
    val homeBlurEnabled: Boolean = false,
    val lockBlurEnabled: Boolean = false,
    val homeBlurRadius: Int = 16,
    val lockBlurRadius: Int = 16,
    val lanEnabled: Boolean = false,
    val lanPort: Int = 9090,
    val lanPinEnabled: Boolean = false,
    val lanPin: String = "",
    val photosAlbumUrl: String = "",
    val driveFolderUrl: String = "",
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val dataSaverEnabled: Boolean = false,
    val wifiOnly: Boolean = false,
    val mobileDataAllowed: Boolean = true,
    val chargingOnly: Boolean = false,
    val pauseBatterySaver: Boolean = true,
    val pauseLowBattery: Boolean = true,
    val lowBatteryThreshold: Int = 15,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
    val cacheTarget: Int = 4,
    val backgroundGuardEnabled: Boolean = true,
    val lowStorageReserveMb: Int = 768,
    val aspectPreference: AspectPreference = AspectPreference.ANY,
    val perceptualDistance: Int = 6,
    // Quality-first aspect handling for Bharat's 9:20 wallpaper library.
    // Compatible images are always streamed untouched; only mismatches may be cropped.
    val smartCropEnabled: Boolean = true,
    val smartCropTolerancePct: Float = 1.5f,
    // Keep local storage footprint small even in Advance mode.
    val leanStorageMode: Boolean = true,

    // Phase-1 final smart source/cache policy.
    val sourcePriorityMode: SourcePriorityMode =
        SourcePriorityMode.PHOTOS_FIRST,
    val smartPairingEnabled: Boolean = false,
    val cacheMaxMb: Int = 512,
    val adaptiveResourceProtectionEnabled: Boolean = true,

    // Adaptive environment / Mood Engine.
    val moodEngineEnabled: Boolean = false,
    val moodAmbientLightEnabled: Boolean = true,
    val moodTimeEnabled: Boolean = true,
    val moodDarkModeEnabled: Boolean = true,
    val moodBatteryContextEnabled: Boolean = true,
    val moodThermalProtectionEnabled: Boolean = true,
    val moodStrength: Int = 60,
    val moodDarkLuxThreshold: Int = 20,
    val moodBrightLuxThreshold: Int = 800,
    val moodWeatherEnabled: Boolean = false,
    val moodUseDeviceLocation: Boolean = true,
    val moodWeatherCity: String = "",
    val moodWeatherInfluence: Int = 60,
    val moodOutdoorTemperatureEnabled: Boolean = true,
    val moodColdTemperatureC: Int = 15,
    val moodHotTemperatureC: Int = 32,
    val moodAutoReactEnabled: Boolean = false,
    val moodAutoReactCooldownMinutes: Int = 60,

    // Intelligent ordering for Random Shuffle / Surprise only.
    // Explicit A-Z/date/size orders are never overridden.
    val decisionEngineEnabled: Boolean = false,


    // v1.1+ scalable category discovery.
)
