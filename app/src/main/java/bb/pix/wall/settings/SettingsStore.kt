package bb.pix.wall.settings

import android.content.Context
import bb.pix.wall.web.model.WebQualityMode
import bb.pix.wall.web.model.WebSourceMode
import bb.pix.wall.discovery.model.DiscoveryMixMode

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("bb_pixwall_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        autoChange = prefs.getBoolean("auto_change", false),
        triggerMode = enumOrDefault(prefs.getString("trigger_mode", null), TriggerMode.INTERVAL),
        intervalMinutes = prefs.getInt("interval_minutes", 30).coerceIn(1, 240),
        targetMode = enumOrDefault(prefs.getString("target_mode", null), WallpaperTargetMode.BOTH_DIFFERENT),
        wallpaperOrder = enumOrDefault(prefs.getString("wallpaper_order", null), WallpaperOrder.RANDOM_SHUFFLE),
        engineMode = enumOrDefault(prefs.getString("engine_mode", null), EngineMode.STANDARD),
        homeBlurEnabled = prefs.getBoolean("home_blur_enabled", false),
        lockBlurEnabled = prefs.getBoolean("lock_blur_enabled", false),
        homeBlurRadius = prefs.getInt("home_blur_radius", 16).coerceIn(0, 64),
        lockBlurRadius = prefs.getInt("lock_blur_radius", 16).coerceIn(0, 64),
        lanEnabled = prefs.getBoolean("lan_enabled", false),
        lanPort = prefs.getInt("lan_port", 9090).coerceIn(1024, 65535),
        lanPinEnabled = prefs.getBoolean("lan_pin_enabled", false),
        lanPin = prefs.getString("lan_pin", "") ?: "",
        photosAlbumUrl = prefs.getString("photos_album_url", "") ?: "",
        driveFolderUrl = prefs.getString("drive_folder_url", "") ?: "",
        appearanceMode = enumOrDefault(prefs.getString("appearance_mode", null), AppearanceMode.SYSTEM),
        dataSaverEnabled = prefs.getBoolean("data_saver", false),
        wifiOnly = prefs.getBoolean("wifi_only", false),
        mobileDataAllowed = prefs.getBoolean("mobile_data_allowed", true),
        chargingOnly = prefs.getBoolean("charging_only", false),
        pauseBatterySaver = prefs.getBoolean("pause_battery_saver", true),
        pauseLowBattery = prefs.getBoolean("pause_low_battery", true),
        lowBatteryThreshold = prefs.getInt("low_battery_threshold", 15).coerceIn(5, 50),
        quietHoursEnabled = prefs.getBoolean("quiet_hours", false),
        quietStartHour = prefs.getInt("quiet_start", 23).coerceIn(0, 23),
        quietEndHour = prefs.getInt("quiet_end", 7).coerceIn(0, 23),
        cacheTarget = prefs.getInt("cache_target", 8).coerceIn(4, 36),
        backgroundGuardEnabled = prefs.getBoolean("background_guard", true),
        lowStorageReserveMb = prefs.getInt("low_storage_reserve_mb", 768).coerceIn(256, 8192),
        aspectPreference = enumOrDefault(prefs.getString("aspect_preference", null), AspectPreference.ANY),
        perceptualDistance = prefs.getInt("perceptual_distance", 6).coerceIn(0, 24),
        smartCropEnabled = prefs.getBoolean("smart_crop_enabled", true),
        smartCropTolerancePct = prefs.getFloat("smart_crop_tolerance_pct", 1.5f).coerceIn(0.2f, 5f),
        leanStorageMode = prefs.getBoolean("lean_storage_mode", true),
        decisionEngineEnabled = prefs.getBoolean("decision_engine_enabled", true),
        webSourceMode = enumOrDefault(
            prefs.getString("web_source_mode", null),
            WebSourceMode.OFF,
        ),
        webQualityMode = enumOrDefault(
            prefs.getString("web_quality_mode", null),
            WebQualityMode.MAXIMUM,
        ),
        discoveryEnabled =
            prefs.getBoolean(
                "discovery_enabled",
                true,
            ),
        discoveryMixMode =
            enumOrDefault(
                prefs.getString(
                    "discovery_mix_mode",
                    null,
                ),
                DiscoveryMixMode.PREMIUM_MIX,
            ),
        enabledDiscoveryCategoryIds =
            prefs.getStringSet(
                "discovery_category_ids",
                emptySet(),
            )?.toSet()
                ?: emptySet(),
        categoryRotationEnabled =
            prefs.getBoolean(
                "category_rotation_enabled",
                true,
            ),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean("auto_change", settings.autoChange)
            .putString("trigger_mode", settings.triggerMode.name)
            .putInt("interval_minutes", settings.intervalMinutes.coerceIn(1, 240))
            .putString("target_mode", settings.targetMode.name)
            .putString("wallpaper_order", settings.wallpaperOrder.name)
            .putString("engine_mode", settings.engineMode.name)
            .putBoolean("home_blur_enabled", settings.homeBlurEnabled)
            .putBoolean("lock_blur_enabled", settings.lockBlurEnabled)
            .putInt("home_blur_radius", settings.homeBlurRadius)
            .putInt("lock_blur_radius", settings.lockBlurRadius)
            .putBoolean("lan_enabled", settings.lanEnabled)
            .putInt("lan_port", settings.lanPort)
            .putBoolean("lan_pin_enabled", settings.lanPinEnabled)
            .putString("lan_pin", settings.lanPin.take(12))
            .putString("photos_album_url", settings.photosAlbumUrl)
            .putString("drive_folder_url", settings.driveFolderUrl)
            .putString("appearance_mode", settings.appearanceMode.name)
            .putBoolean("data_saver", settings.dataSaverEnabled)
            .putBoolean("wifi_only", settings.wifiOnly)
            .putBoolean("mobile_data_allowed", settings.mobileDataAllowed)
            .putBoolean("charging_only", settings.chargingOnly)
            .putBoolean("pause_battery_saver", settings.pauseBatterySaver)
            .putBoolean("pause_low_battery", settings.pauseLowBattery)
            .putInt("low_battery_threshold", settings.lowBatteryThreshold.coerceIn(5,50))
            .putBoolean("quiet_hours", settings.quietHoursEnabled)
            .putInt("quiet_start", settings.quietStartHour)
            .putInt("quiet_end", settings.quietEndHour)
            .putInt("cache_target", settings.cacheTarget.coerceIn(4, 36))
            .putBoolean("background_guard", settings.backgroundGuardEnabled)
            .putInt("low_storage_reserve_mb", settings.lowStorageReserveMb.coerceIn(256,8192))
            .putString("aspect_preference", settings.aspectPreference.name)
            .putInt("perceptual_distance", settings.perceptualDistance.coerceIn(0,24))
            .putBoolean("smart_crop_enabled", settings.smartCropEnabled)
            .putFloat("smart_crop_tolerance_pct", settings.smartCropTolerancePct.coerceIn(0.2f, 5f))
            .putBoolean("lean_storage_mode", settings.leanStorageMode)
            .putBoolean("decision_engine_enabled", settings.decisionEngineEnabled)
            .putString("web_source_mode", settings.webSourceMode.name)
            .putString("web_quality_mode", settings.webQualityMode.name)
            .putBoolean(
                "discovery_enabled",
                settings.discoveryEnabled,
            )
            .putString(
                "discovery_mix_mode",
                settings.discoveryMixMode.name,
            )
            .putStringSet(
                "discovery_category_ids",
                settings.enabledDiscoveryCategoryIds,
            )
            .putBoolean(
                "category_rotation_enabled",
                settings.categoryRotationEnabled,
            )
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        runCatching { enumValueOf<T>(raw ?: "") }.getOrDefault(fallback)
}
