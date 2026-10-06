package bb.pix.wall.engine

import android.content.Context
import bb.pix.wall.settings.AppSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Weather context for Adaptive Mood.
 *
 * Primary mode:
 *   foreground device location
 *
 * Fallback:
 *   manually configured city
 *
 * Important:
 * - no continuous GPS tracking
 * - no background location permission
 * - no network I/O inside candidate scoring
 * - weather/location refresh happens asynchronously
 */
object WeatherMoodEngine {

    private const val PREFS =
        "bb_pixwall_weather_mood_v2"

    private const val WEATHER_TTL_MS =
        45L * 60L * 1000L

    private const val AUTO_LOCATION_RECHECK_MS =
        20L * 60L * 1000L

    private const val CONNECT_TIMEOUT_MS =
        5000

    private const val READ_TIMEOUT_MS =
        7000

    private val refreshing =
        AtomicBoolean(false)

    enum class Condition(
        val label: String,
    ) {
        CLEAR("Clear"),
        CLOUDY("Cloudy"),
        FOG("Fog"),
        RAIN("Rain"),
        SNOW("Snow"),
        STORM("Storm"),
        UNKNOWN("Unknown"),
    }

    data class Snapshot(
        val available: Boolean,
        val city: String,
        val temperatureC: Float?,
        val weatherCode: Int?,
        val condition: Condition,
        val isDay: Boolean?,
        val updatedAt: Long,
        val stale: Boolean,
        val status: String,
    )

    fun snapshot(
        context: Context,
        settings: AppSettings,
    ): Snapshot {
        if (
            !settings.moodEngineEnabled ||
            !settings.moodWeatherEnabled
        ) {
            return unavailable(
                city =
                    settings
                        .moodWeatherCity
                        .trim(),
                status = "Weather off",
            )
        }

        if (
            settings.moodUseDeviceLocation &&
            !MoodLocationEngine
                .hasAnyPermission(context)
        ) {
            RuntimeStatus.set(
                context,
                "weather_mood_status",
                "Location permission needed",
            )

            /*
             * Manual city may still work as fallback,
             * so trigger a refresh if it exists.
             */
            if (
                settings
                    .moodWeatherCity
                    .isNotBlank()
            ) {
                refreshAsync(
                    context,
                    settings,
                )
            }

            return unavailable(
                city =
                    settings
                        .moodWeatherCity
                        .trim(),
                status =
                    "Location permission needed",
            )
        }

        if (
            !settings.moodUseDeviceLocation &&
            settings
                .moodWeatherCity
                .isBlank()
        ) {
            return unavailable(
                city = "",
                status =
                    "Set a fallback city",
            )
        }

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )

        val updatedAt =
            prefs.getLong(
                "updated_at",
                0L,
            )

        val locationCheckedAt =
            prefs.getLong(
                "location_checked_at",
                0L,
            )

        val cachedMode =
            prefs.getString(
                "mode",
                "",
            ).orEmpty()

        val expectedMode =
            if (
                settings.moodUseDeviceLocation
            ) {
                "auto"
            } else {
                "manual:" +
                    settings
                        .moodWeatherCity
                        .trim()
                        .lowercase(
                            Locale.US
                        )
            }

        val now =
            System.currentTimeMillis()

        val weatherStale =
            updatedAt <= 0L ||
                now - updatedAt >
                    WEATHER_TTL_MS

        val locationStale =
            settings.moodUseDeviceLocation &&
                (
                    locationCheckedAt <= 0L ||
                        now - locationCheckedAt >
                        AUTO_LOCATION_RECHECK_MS
                )

        val modeMismatch =
            cachedMode != expectedMode

        val stale =
            weatherStale ||
                locationStale ||
                modeMismatch

        if (stale) {
            refreshAsync(
                context,
                settings,
            )
        }

        val hasWeather =
            prefs.getBoolean(
                "has_weather",
                false,
            )

        if (
            !hasWeather ||
            modeMismatch
        ) {
            return unavailable(
                city =
                    prefs.getString(
                        "location_label",
                        settings
                            .moodWeatherCity
                            .trim(),
                    ).orEmpty(),
                status =
                    if (
                        settings
                            .moodUseDeviceLocation
                    ) {
                        "Fetching current location & weather"
                    } else {
                        "Fetching weather"
                    },
            )
        }

        val locationLabel =
            prefs.getString(
                "location_label",
                "",
            ).orEmpty()

        val temperature =
            prefs.getFloat(
                "temperature_c",
                Float.NaN,
            ).takeIf {
                it.isFinite()
            }

        val weatherCode =
            prefs.getInt(
                "weather_code",
                -1,
            ).takeIf {
                it >= 0
            }

        val isDay =
            when (
                prefs.getInt(
                    "is_day",
                    -1,
                )
            ) {
                1 -> true
                0 -> false
                else -> null
            }

        val condition =
            conditionFor(
                weatherCode
            )

        val status =
            buildString {
                if (
                    locationLabel
                        .isNotBlank()
                ) {
                    append(locationLabel)
                    append(" • ")
                }

                append(
                    condition.label
                )

                temperature?.let {
                    append(
                        " • ${"%.1f".format(it)}°C"
                    )
                }

                if (stale) {
                    append(
                        " • refreshing"
                    )
                }
            }

        RuntimeStatus.set(
            context,
            "weather_mood_status",
            status,
        )

        return Snapshot(
            available = true,
            city = locationLabel,
            temperatureC = temperature,
            weatherCode = weatherCode,
            condition = condition,
            isDay = isDay,
            updatedAt = updatedAt,
            stale = stale,
            status = status,
        )
    }

    fun forceRefresh(
        context: Context,
        settings: AppSettings,
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            )
            .edit()
            .putLong(
                "updated_at",
                0L,
            )
            .putLong(
                "location_checked_at",
                0L,
            )
            .apply()

        refreshAsync(
            context,
            settings,
        )
    }

    fun influence(
        snapshot: Snapshot,
        traits: WallpaperStyleLearning.Traits?,
        settings: AppSettings,
    ): Int {
        if (
            !settings.moodEngineEnabled ||
            !settings.moodWeatherEnabled ||
            !snapshot.available ||
            traits == null
        ) {
            return 0
        }

        fun centered(
            value: Float,
        ): Float =
            ((value - 0.5f) * 2f)
                .coerceIn(
                    -1f,
                    1f,
                )

        val brightness =
            centered(
                traits.brightness
            )

        val saturation =
            centered(
                traits.saturation
            )

        val contrast =
            centered(
                traits.contrast
            )

        val dark =
            centered(
                traits.darkRatio
            )

        val warmth =
            traits.warmth
                .coerceIn(
                    -1f,
                    1f,
                )

        var raw = 0f

        when (
            snapshot.condition
        ) {
            Condition.CLEAR -> {
                raw +=
                    brightness * 0.42f

                raw +=
                    saturation * 0.28f

                raw +=
                    warmth * 0.08f
            }

            Condition.CLOUDY -> {
                raw +=
                    contrast * 0.12f

                raw -=
                    saturation * 0.08f
            }

            Condition.FOG -> {
                raw -=
                    contrast * 0.22f

                raw -=
                    saturation * 0.18f

                raw +=
                    brightness * 0.08f
            }

            Condition.RAIN -> {
                raw +=
                    dark * 0.30f

                raw +=
                    contrast * 0.20f

                raw -=
                    warmth * 0.14f

                raw -=
                    saturation * 0.08f
            }

            Condition.SNOW -> {
                raw +=
                    brightness * 0.32f

                raw -=
                    saturation * 0.16f

                raw -=
                    warmth * 0.12f
            }

            Condition.STORM -> {
                raw +=
                    dark * 0.42f

                raw +=
                    contrast * 0.30f

                raw -=
                    brightness * 0.16f
            }

            Condition.UNKNOWN ->
                Unit
        }

        if (
            settings
                .moodOutdoorTemperatureEnabled
        ) {
            snapshot
                .temperatureC
                ?.let { temperature ->
                    when {
                        temperature <=
                            settings
                                .moodColdTemperatureC -> {
                            raw -=
                                warmth * 0.24f

                            raw +=
                                contrast * 0.08f
                        }

                        temperature >=
                            settings
                                .moodHotTemperatureC -> {
                            raw -=
                                warmth * 0.28f

                            raw +=
                                saturation * 0.10f

                            raw +=
                                brightness * 0.08f
                        }
                    }
                }
        }

        val weatherStrength =
            settings
                .moodWeatherInfluence
                .coerceIn(
                    0,
                    100,
                ) / 100f

        val moodStrength =
            settings
                .moodStrength
                .coerceIn(
                    0,
                    100,
                ) / 100f

        return (
            raw *
                7f *
                weatherStrength *
                moodStrength
        )
            .roundToInt()
            .coerceIn(
                -8,
                8,
            )
    }

    private fun refreshAsync(
        context: Context,
        settings: AppSettings,
    ) {
        if (
            !refreshing.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        EngineExecutors.io {
            try {
                refreshNow(
                    context.applicationContext,
                    settings,
                )
            } finally {
                refreshing.set(false)
            }
        }
    }

    private data class Target(
        val latitude: Double,
        val longitude: Double,
        val label: String,
        val mode: String,
    )

    private fun refreshNow(
        context: Context,
        settings: AppSettings,
    ) {
        val manualCity =
            settings
                .moodWeatherCity
                .trim()

        val target =
            if (
                settings
                    .moodUseDeviceLocation
            ) {
                val device =
                    MoodLocationEngine
                        .resolve(context)

                if (device != null) {
                    Target(
                        latitude =
                            device.latitude,
                        longitude =
                            device.longitude,
                        label =
                            device.label,
                        mode = "auto",
                    )
                } else {
                    /*
                     * Permission/provider failed:
                     * use manual city if configured.
                     */
                    manualTarget(
                        manualCity
                    )
                }
            } else {
                manualTarget(
                    manualCity
                )
            }

        if (target == null) {
            RuntimeStatus.set(
                context,
                "weather_mood_status",
                if (
                    settings
                        .moodUseDeviceLocation
                ) {
                    "Location unavailable • set fallback city"
                } else {
                    "Set a fallback city"
                },
            )

            return
        }

        RuntimeStatus.set(
            context,
            "weather_mood_status",
            "Fetching ${target.label}",
        )

        try {
            val weather =
                fetchWeather(
                    target.latitude,
                    target.longitude,
                )

            if (weather == null) {
                RuntimeStatus.set(
                    context,
                    "weather_mood_status",
                    "${target.label} • weather unavailable",
                )

                return
            }

            val now =
                System.currentTimeMillis()

            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )
                .edit()
                .putBoolean(
                    "has_weather",
                    true,
                )
                .putString(
                    "mode",
                    if (
                        target.mode ==
                            "auto"
                    ) {
                        "auto"
                    } else {
                        "manual:" +
                            manualCity
                                .lowercase(
                                    Locale.US
                                )
                    },
                )
                .putString(
                    "location_label",
                    target.label,
                )
                .putString(
                    "latitude",
                    target.latitude
                        .toString(),
                )
                .putString(
                    "longitude",
                    target.longitude
                        .toString(),
                )
                .putFloat(
                    "temperature_c",
                    weather.temperatureC,
                )
                .putInt(
                    "weather_code",
                    weather.weatherCode,
                )
                .putInt(
                    "is_day",
                    if (
                        weather.isDay
                    ) {
                        1
                    } else {
                        0
                    },
                )
                .putLong(
                    "updated_at",
                    now,
                )
                .putLong(
                    "location_checked_at",
                    now,
                )
                .apply()

            RuntimeStatus.set(
                context,
                "weather_mood_status",
                buildString {
                    append(
                        target.label
                    )

                    append(" • ")

                    append(
                        conditionFor(
                            weather.weatherCode
                        ).label
                    )

                    append(
                        " • ${"%.1f".format(weather.temperatureC)}°C"
                    )
                },
            )

            runCatching {
                val freshAdaptive =
                    AdaptiveMoodEngine.snapshot(
                        context,
                        settings,
                    )

                val freshWeather =
                    snapshot(
                        context,
                        settings,
                    )

                Phase1FinalEngine
                    .publishMoodProfile(
                        context = context,
                        settings = settings,
                        adaptive = freshAdaptive,
                        weather = freshWeather,
                    )
            }

        } catch (t: Throwable) {
            RuntimeStatus.set(
                context,
                "weather_mood_status",
                "${target.label} • ${t.javaClass.simpleName}",
            )
        }
    }

    private fun manualTarget(
        city: String,
    ): Target? {
        if (
            city.isBlank()
        ) {
            return null
        }

        val encoded =
            URLEncoder.encode(
                city,
                "UTF-8",
            )

        val url =
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=$encoded" +
                "&count=1" +
                "&language=en" +
                "&format=json"

        val json =
            requestJson(url)
                ?: return null

        val results =
            json.optJSONArray(
                "results"
            ) ?: return null

        if (
            results.length() == 0
        ) {
            return null
        }

        val first =
            results
                .getJSONObject(0)

        val lat =
            first.optDouble(
                "latitude",
                Double.NaN,
            )

        val lon =
            first.optDouble(
                "longitude",
                Double.NaN,
            )

        if (
            !lat.isFinite() ||
            !lon.isFinite()
        ) {
            return null
        }

        val resolvedName =
            first.optString(
                "name",
                city,
            )

        val admin1 =
            first.optString(
                "admin1",
                "",
            )

        val label =
            if (
                admin1.isNotBlank() &&
                !resolvedName.equals(
                    admin1,
                    ignoreCase = true,
                )
            ) {
                "$resolvedName, $admin1"
            } else {
                resolvedName
            }

        return Target(
            latitude = lat,
            longitude = lon,
            label = label,
            mode = "manual",
        )
    }

    private data class WeatherResult(
        val temperatureC: Float,
        val weatherCode: Int,
        val isDay: Boolean,
    )

    private fun fetchWeather(
        latitude: Double,
        longitude: Double,
    ): WeatherResult? {
        val url =
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=%.6f" +
                    "&longitude=%.6f" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&temperature_unit=celsius" +
                    "&timezone=auto",
                latitude,
                longitude,
            )

        val json =
            requestJson(url)
                ?: return null

        val current =
            json.optJSONObject(
                "current"
            ) ?: return null

        val temperature =
            current.optDouble(
                "temperature_2m",
                Double.NaN,
            )

        val code =
            current.optInt(
                "weather_code",
                -1,
            )

        val isDay =
            current.optInt(
                "is_day",
                1,
            ) == 1

        if (
            !temperature.isFinite() ||
            code < 0
        ) {
            return null
        }

        return WeatherResult(
            temperatureC =
                temperature.toFloat(),
            weatherCode = code,
            isDay = isDay,
        )
    }

    private fun requestJson(
        address: String,
    ): JSONObject? {
        val connection =
            URL(address)
                .openConnection() as
                HttpURLConnection

        return try {
            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.setRequestProperty(
                "Accept",
                "application/json",
            )

            connection.setRequestProperty(
                "User-Agent",
                "BB-PixWall/1.1",
            )

            val code =
                connection.responseCode

            if (
                code !in 200..299
            ) {
                return null
            }

            val text =
                connection
                    .inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun unavailable(
        city: String,
        status: String,
    ): Snapshot =
        Snapshot(
            available = false,
            city = city,
            temperatureC = null,
            weatherCode = null,
            condition =
                Condition.UNKNOWN,
            isDay = null,
            updatedAt = 0L,
            stale = true,
            status = status,
        )

    private fun conditionFor(
        code: Int?,
    ): Condition =
        when (code) {
            null ->
                Condition.UNKNOWN

            0 ->
                Condition.CLEAR

            1, 2, 3 ->
                Condition.CLOUDY

            45, 48 ->
                Condition.FOG

            51, 53, 55,
            56, 57,
            61, 63, 65,
            66, 67,
            80, 81, 82 ->
                Condition.RAIN

            71, 73, 75,
            77,
            85, 86 ->
                Condition.SNOW

            95, 96, 99 ->
                Condition.STORM

            else ->
                Condition.UNKNOWN
        }
}
