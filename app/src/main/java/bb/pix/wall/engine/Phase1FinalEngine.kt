package bb.pix.wall.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.SourcePriorityMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase-1 final coordination layer.
 *
 * Keeps policy out of WallpaperController:
 * - combined environment mood
 * - source ranking policy
 * - smart Home/Lock pairing
 * - battery / thermal heavy-work policy
 * - decision diagnostics
 */
object Phase1FinalEngine {

    data class MoodProfile(
        val label: String,
        val summary: String,
        val fingerprint: String,
    )

    data class ResourcePolicy(
        val allowNetworkRefill: Boolean,
        val allowHeavyAnalysis: Boolean,
        val allowAutoReact: Boolean,
        val targetScale: Float,
        val label: String,
    )

    fun sourceBaseScore(
        settings: AppSettings,
        source: String,
    ): Int {
        val key = source.lowercase()

        return when (settings.sourcePriorityMode) {
            SourcePriorityMode.SMART_BALANCED ->
                when (key) {
                    "photos" -> 36
                    "drive" -> 24
                    "local" -> 10
                    else -> 0
                }

            SourcePriorityMode.PHOTOS_FIRST ->
                when (key) {
                    "photos" -> 44
                    "drive" -> 24
                    "local" -> 12
                    else -> 0
                }

            SourcePriorityMode.DRIVE_FIRST ->
                when (key) {
                    "drive" -> 44
                    "photos" -> 28
                    "local" -> 12
                    else -> 0
                }

            SourcePriorityMode.LOCAL_FIRST ->
                when (key) {
                    "local" -> 44
                    "photos" -> 28
                    "drive" -> 20
                    else -> 0
                }
        }
    }

    fun profile(
        settings: AppSettings,
        adaptive: AdaptiveMoodEngine.Snapshot,
        weather: WeatherMoodEngine.Snapshot,
    ): MoodProfile {
        val temp = weather.temperatureC

        val label =
            when {
                weather.condition ==
                    WeatherMoodEngine.Condition.STORM ->
                    "Storm Focus"

                weather.condition ==
                    WeatherMoodEngine.Condition.RAIN ->
                    "Rainy Cinematic"

                adaptive.phase ==
                    AdaptiveMoodEngine.DayPhase.MIDNIGHT &&
                    (
                        adaptive.lightMood ==
                            AdaptiveMoodEngine.LightMood.DARK ||
                            adaptive.lightMood ==
                            AdaptiveMoodEngine.LightMood.DIM
                    ) ->
                    "Deep Night"

                temp != null &&
                    temp >= settings.moodHotTemperatureC &&
                    adaptive.lightMood ==
                        AdaptiveMoodEngine.LightMood.BRIGHT ->
                    "Cool & Fresh"

                weather.condition ==
                    WeatherMoodEngine.Condition.CLEAR &&
                    adaptive.lightMood ==
                        AdaptiveMoodEngine.LightMood.BRIGHT ->
                    "Bright & Fresh"

                adaptive.phase ==
                    AdaptiveMoodEngine.DayPhase.EVENING ->
                    "Warm Evening"

                adaptive.phase ==
                    AdaptiveMoodEngine.DayPhase.NIGHT ->
                    "Calm Night"

                adaptive.lightMood ==
                    AdaptiveMoodEngine.LightMood.DARK ->
                    "Low-Light Calm"

                else ->
                    "Adaptive Balance"
            }

        val tempBucket =
            when {
                temp == null -> "temp?"
                temp <= settings.moodColdTemperatureC -> "cold"
                temp >= settings.moodHotTemperatureC -> "hot"
                else -> "mild"
            }

        val summary =
            buildList {
                if (settings.moodAmbientLightEnabled) {
                    add(adaptive.lightMood.label)
                }

                if (settings.moodTimeEnabled) {
                    add(adaptive.phase.label)
                }

                if (
                    settings.moodWeatherEnabled &&
                    weather.available
                ) {
                    add(weather.condition.label)

                    temp?.let {
                        add("${"%.1f".format(it)}°C")
                    }
                }

                if (
                    settings.moodDarkModeEnabled &&
                    adaptive.darkMode
                ) {
                    add("Dark UI")
                }

                if (
                    settings.moodThermalProtectionEnabled
                ) {
                    add(adaptive.thermalLabel)
                }
            }.joinToString(" • ")
                .ifBlank {
                    "Adaptive context"
                }

        val fingerprint =
            listOf(
                adaptive.lightMood.name,
                adaptive.phase.name,
                weather.condition.name,
                tempBucket,
                if (adaptive.darkMode) "dark" else "light",
                thermalBucket(adaptive.thermalStatus),
            ).joinToString("|")

        return MoodProfile(
            label = label,
            summary = summary,
            fingerprint = fingerprint,
        )
    }

    fun publishMoodProfile(
        context: Context,
        settings: AppSettings,
        adaptive: AdaptiveMoodEngine.Snapshot,
        weather: WeatherMoodEngine.Snapshot,
    ): MoodProfile {
        val profile =
            profile(
                settings,
                adaptive,
                weather,
            )

        RuntimeStatus.set(
            context,
            "mood_profile_label",
            profile.label,
        )

        RuntimeStatus.set(
            context,
            "mood_profile_summary",
            profile.summary,
        )

        RuntimeStatus.set(
            context,
            "mood_profile_fingerprint",
            profile.fingerprint,
        )

        return profile
    }

    fun factorSummary(
        reason: String,
    ): String {
        val factors =
            reason
                .split(" • ")
                .mapNotNull { part ->
                    when {
                        part.startsWith("adaptive+") ||
                            part.startsWith("adaptive-") ->
                            "environment ${scoreToken(part)}"

                        part.startsWith("weather+") ||
                            part.startsWith("weather-") ->
                            "weather ${scoreToken(part)}"

                        part.startsWith("mood+") ||
                            part.startsWith("mood-") ->
                            "learning ${scoreToken(part)}"

                        part.startsWith("context+") ||
                            part.startsWith("context-") ->
                            "device ${scoreToken(part)}"

                        part.startsWith("style+") ||
                            part.startsWith("style-") ->
                            "style ${scoreToken(part)}"

                        part.startsWith("taste+") ||
                            part.startsWith("taste-") ->
                            "taste ${scoreToken(part)}"

                        else ->
                            null
                    }
                }

        return if (factors.isEmpty()) {
            "Neutral environmental influence"
        } else {
            factors
                .take(6)
                .joinToString(" • ")
        }
    }

    private fun scoreToken(
        raw: String,
    ): String {
        val value =
            Regex("""[-+]\d+""")
                .find(raw)
                ?.value
                ?: "0"

        return value
    }

    fun pairScore(
        context: Context,
        firstId: String,
        secondId: String,
    ): Int {
        if (
            firstId.isBlank() ||
            secondId.isBlank() ||
            firstId == secondId
        ) {
            return 0
        }

        VisualIntelligenceEngine
            .pairScore(
                context,
                firstId,
                secondId,
            )?.let {
                return it
            }

        val map =
            WallpaperStyleLearning
                .traitsSnapshot(
                    context,
                    listOf(
                        firstId,
                        secondId,
                    ),
                )

        val a =
            map[firstId]
                ?: return 50

        val b =
            map[secondId]
                ?: return 50

        val brightnessDiff =
            abs(
                a.brightness -
                    b.brightness
            )

        val saturationDiff =
            abs(
                a.saturation -
                    b.saturation
            )

        val contrastDiff =
            abs(
                a.contrast -
                    b.contrast
            )

        /*
         * Pairing goal:
         * related enough to look intentional,
         * different enough to avoid Home/Lock clones.
         */
        val brightnessHarmony =
            (
                1f -
                    abs(
                        brightnessDiff -
                            0.18f
                    )
            ).coerceIn(
                0f,
                1f,
            )

        val saturationHarmony =
            (
                1f -
                    saturationDiff
            ).coerceIn(
                0f,
                1f,
            )

        val contrastHarmony =
            (
                1f -
                    contrastDiff
            ).coerceIn(
                0f,
                1f,
            )

        val hueBonus =
            when {
                a.hue == b.hue ->
                    8f

                a.hue == "neutral" ||
                    b.hue == "neutral" ->
                    6f

                else ->
                    10f
            }

        return (
            brightnessHarmony * 35f +
                saturationHarmony * 20f +
                contrastHarmony * 15f +
                hueBonus +
                20f
        )
            .roundToInt()
            .coerceIn(
                0,
                100,
            )
    }

    fun resourcePolicy(
        context: Context,
        settings: AppSettings,
    ): ResourcePolicy {
        if (
            !settings
                .adaptiveResourceProtectionEnabled
        ) {
            return ResourcePolicy(
                allowNetworkRefill = true,
                allowHeavyAnalysis = true,
                allowAutoReact = true,
                targetScale = 1f,
                label = "Protection off",
            )
        }

        val pm =
            context.getSystemService(
                PowerManager::class.java
            )

        val thermal =
            runCatching {
                pm?.currentThermalStatus
                    ?: PowerManager
                        .THERMAL_STATUS_NONE
            }.getOrDefault(
                PowerManager
                    .THERMAL_STATUS_NONE
            )

        val saver =
            runCatching {
                pm?.isPowerSaveMode == true
            }.getOrDefault(false)

        val bm =
            context.getSystemService(
                BatteryManager::class.java
            )

        val battery =
            runCatching {
                bm?.getIntProperty(
                    BatteryManager
                        .BATTERY_PROPERTY_CAPACITY
                ) ?: -1
            }.getOrDefault(-1)

        val sticky =
            runCatching {
                context.registerReceiver(
                    null,
                    IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED
                    ),
                )
            }.getOrNull()

        val batteryStatus =
            sticky?.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                -1,
            ) ?: -1

        val charging =
            batteryStatus ==
                BatteryManager
                    .BATTERY_STATUS_CHARGING ||
                batteryStatus ==
                    BatteryManager
                        .BATTERY_STATUS_FULL

        val severeThermal =
            thermal >=
                PowerManager
                    .THERMAL_STATUS_SEVERE

        val moderateThermal =
            thermal >=
                PowerManager
                    .THERMAL_STATUS_MODERATE

        val lowBattery =
            battery in
                0 until
                    settings
                        .lowBatteryThreshold
                        .coerceIn(
                            5,
                            50,
                        )

        val blockBackgroundHeavy =
            severeThermal ||
                (
                    !charging &&
                        (
                            saver ||
                                lowBattery
                        )
                )

        val policy =
            ResourcePolicy(
                allowNetworkRefill =
                    !blockBackgroundHeavy,
                allowHeavyAnalysis =
                    !moderateThermal &&
                        !saver &&
                        (
                            charging ||
                                !lowBattery
                        ),
                allowAutoReact =
                    !blockBackgroundHeavy,
                targetScale =
                    when {
                        severeThermal ->
                            0.50f

                        moderateThermal ->
                            0.75f

                        saver ||
                            lowBattery ->
                            0.65f

                        else ->
                            1f
                    },
                label =
                    buildString {
                        append(
                            when {
                                severeThermal ->
                                    "Thermal protect"

                                moderateThermal ->
                                    "Thermal moderate"

                                saver ->
                                    "Battery Saver"

                                lowBattery ->
                                    "Low battery"

                                else ->
                                    "Normal"
                            }
                        )

                        if (battery >= 0) {
                            append(
                                " • $battery%"
                            )
                        }

                        if (charging) {
                            append(
                                " • charging"
                            )
                        }
                    },
            )

        RuntimeStatus.set(
            context,
            "resource_policy",
            policy.label,
        )

        return policy
    }

    fun adjustedCacheTarget(
        requested: Int,
        policy: ResourcePolicy,
    ): Int =
        (
            requested *
                policy.targetScale
        )
            .roundToInt()
            .coerceIn(
                2,
                requested
                    .coerceAtLeast(2),
            )

    private fun thermalBucket(
        status: Int,
    ): String =
        when {
            status >=
                PowerManager
                    .THERMAL_STATUS_SEVERE ->
                "hot"

            status >=
                PowerManager
                    .THERMAL_STATUS_MODERATE ->
                "warm"

            else ->
                "normal"
        }
}
