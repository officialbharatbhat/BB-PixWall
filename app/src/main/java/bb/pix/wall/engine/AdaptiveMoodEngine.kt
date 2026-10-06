package bb.pix.wall.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import bb.pix.wall.settings.AppSettings
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Environment-aware wallpaper mood.
 *
 * Privacy / power rules:
 * - no continuous sensor listener
 * - ambient light sampled only when required
 * - short-lived lux cache avoids repeated sensor wakeups
 * - no network I/O
 * - no location access
 *
 * Weather is intentionally a separate provider layer and will
 * be added in Mood Engine Patch 2.
 */
object AdaptiveMoodEngine {

    private const val LIGHT_CACHE_MS =
        2L * 60L * 1000L

    private const val LIGHT_SAMPLE_TIMEOUT_MS =
        260L

    @Volatile
    private var cachedLux: Float? = null

    @Volatile
    private var cachedLuxAt: Long = 0L

    enum class DayPhase(
        val label: String,
    ) {
        EARLY_MORNING("Early Morning"),
        MORNING("Morning"),
        AFTERNOON("Afternoon"),
        EVENING("Evening"),
        NIGHT("Night"),
        MIDNIGHT("Midnight"),
    }

    enum class LightMood(
        val label: String,
    ) {
        UNKNOWN("Unknown"),
        DARK("Dark room"),
        DIM("Dim"),
        BALANCED("Balanced"),
        BRIGHT("Bright"),
    }

    data class Snapshot(
        val enabled: Boolean,
        val lux: Float?,
        val lightMood: LightMood,
        val phase: DayPhase,
        val hour: Int,
        val darkMode: Boolean,
        val batteryPct: Int,
        val batteryTempC: Float?,
        val charging: Boolean,
        val powerSave: Boolean,
        val thermalStatus: Int,
        val thermalLabel: String,
        val label: String,
    )

    fun snapshot(
        context: Context,
        settings: AppSettings,
    ): Snapshot {
        val device =
            ContextAwareness.snapshot(
                context
            )

        val hour =
            Calendar.getInstance()
                .get(
                    Calendar.HOUR_OF_DAY
                )

        val phase =
            phaseFor(hour)

        val lux =
            if (
                settings.moodEngineEnabled &&
                settings.moodAmbientLightEnabled
            ) {
                ambientLux(context)
            } else {
                null
            }

        val lightMood =
            classifyLight(
                lux = lux,
                darkThreshold =
                    settings.moodDarkLuxThreshold,
                brightThreshold =
                    settings.moodBrightLuxThreshold,
            )

        val batteryTemp =
            batteryTemperature(context)

        val thermalStatus =
            runCatching {
                context
                    .getSystemService(
                        PowerManager::class.java
                    )
                    ?.currentThermalStatus
                    ?: PowerManager
                        .THERMAL_STATUS_NONE
            }.getOrDefault(
                PowerManager
                    .THERMAL_STATUS_NONE
            )

        val thermalLabel =
            thermalLabel(thermalStatus)

        val label =
            buildString {
                if (!settings.moodEngineEnabled) {
                    append("Off")
                    return@buildString
                }

                if (
                    settings.moodAmbientLightEnabled
                ) {
                    append(lightMood.label)

                    if (lux != null) {
                        append(
                            " • ${lux.roundToInt()} lux"
                        )
                    }
                }

                if (
                    settings.moodTimeEnabled
                ) {
                    if (isNotEmpty()) {
                        append(" • ")
                    }

                    append(phase.label)
                }

                if (
                    settings.moodDarkModeEnabled &&
                    device.darkMode
                ) {
                    if (isNotEmpty()) {
                        append(" • ")
                    }

                    append("Dark UI")
                }

                if (
                    settings.moodBatteryContextEnabled
                ) {
                    if (isNotEmpty()) {
                        append(" • ")
                    }

                    if (device.batteryPct >= 0) {
                        append(
                            "${device.batteryPct}%"
                        )
                    } else {
                        append("Battery ?")
                    }

                    if (device.charging) {
                        append(" charging")
                    }
                }

                if (
                    settings
                        .moodThermalProtectionEnabled
                ) {
                    if (isNotEmpty()) {
                        append(" • ")
                    }

                    append(thermalLabel)

                    if (batteryTemp != null) {
                        append(
                            " ${"%.1f".format(batteryTemp)}°C"
                        )
                    }
                }

                if (isEmpty()) {
                    append("Adaptive")
                }
            }

        val out =
            Snapshot(
                enabled =
                    settings.moodEngineEnabled,
                lux = lux,
                lightMood = lightMood,
                phase = phase,
                hour = hour,
                darkMode = device.darkMode,
                batteryPct =
                    device.batteryPct,
                batteryTempC =
                    batteryTemp,
                charging =
                    device.charging,
                powerSave =
                    device.powerSave,
                thermalStatus =
                    thermalStatus,
                thermalLabel =
                    thermalLabel,
                label = label,
            )

        RuntimeStatus.set(
            context,
            "adaptive_mood_context",
            label,
        )

        RuntimeStatus.set(
            context,
            "adaptive_mood_lux",
            lux?.let {
                "%.1f".format(it)
            } ?: "Unavailable",
        )

        RuntimeStatus.set(
            context,
            "adaptive_mood_phase",
            phase.label,
        )

        RuntimeStatus.set(
            context,
            "adaptive_mood_thermal",
            buildString {
                append(thermalLabel)

                if (batteryTemp != null) {
                    append(
                        " • battery " +
                            "${"%.1f".format(batteryTemp)}°C"
                    )
                }
            },
        )

        return out
    }

    /**
     * Environmental context is a ranking bias, not a hard filter.
     * Quality, duplicate prevention, taste and source health remain
     * stronger authorities.
     */
    fun influence(
        snapshot: Snapshot,
        traits: WallpaperStyleLearning.Traits?,
        settings: AppSettings,
    ): Int {
        if (
            !snapshot.enabled ||
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

        /*
         * ROOM LIGHT
         *
         * Dark surroundings gently favor darker / softer images.
         * Bright surroundings favor readable, brighter, slightly
         * more saturated images.
         */
        if (
            settings.moodAmbientLightEnabled
        ) {
            when (
                snapshot.lightMood
            ) {
                LightMood.DARK -> {
                    raw += dark * 0.70f
                    raw -= brightness * 0.35f
                    raw -= saturation * 0.10f
                }

                LightMood.DIM -> {
                    raw += dark * 0.35f
                    raw -= brightness * 0.15f
                }

                LightMood.BRIGHT -> {
                    raw += brightness * 0.60f
                    raw += saturation * 0.20f
                    raw -= dark * 0.25f
                }

                LightMood.BALANCED,
                LightMood.UNKNOWN -> Unit
            }
        }

        /*
         * TIME MOOD
         *
         * Deliberately subtle: this should feel natural rather than
         * make every night wallpaper black.
         */
        if (settings.moodTimeEnabled) {
            when (snapshot.phase) {
                DayPhase.EARLY_MORNING -> {
                    raw += warmth * 0.18f
                    raw += brightness * 0.16f
                }

                DayPhase.MORNING -> {
                    raw += brightness * 0.30f
                    raw += saturation * 0.12f
                    raw += warmth * 0.10f
                }

                DayPhase.AFTERNOON -> {
                    raw += brightness * 0.22f
                    raw += saturation * 0.14f
                }

                DayPhase.EVENING -> {
                    raw += warmth * 0.26f
                    raw += contrast * 0.08f
                }

                DayPhase.NIGHT -> {
                    raw += dark * 0.34f
                    raw -= brightness * 0.16f
                    raw -= saturation * 0.06f
                }

                DayPhase.MIDNIGHT -> {
                    raw += dark * 0.48f
                    raw -= brightness * 0.24f
                    raw -= saturation * 0.10f
                }
            }
        }

        /*
         * SYSTEM DARK MODE
         */
        if (
            settings.moodDarkModeEnabled
        ) {
            if (snapshot.darkMode) {
                raw += dark * 0.24f
                raw -= brightness * 0.10f
            } else {
                raw += brightness * 0.10f
            }
        }

        /*
         * BATTERY CONTEXT
         *
         * Battery Saver / low battery biases toward calmer and
         * slightly darker visuals. Charging allows a tiny vibrant bias.
         */
        if (
            settings.moodBatteryContextEnabled
        ) {
            when {
                snapshot.powerSave -> {
                    raw += dark * 0.14f
                    raw -= saturation * 0.10f
                }

                snapshot.batteryPct in 0..20 -> {
                    raw += dark * 0.10f
                    raw -= saturation * 0.06f
                }

                snapshot.charging -> {
                    raw += saturation * 0.07f
                    raw += contrast * 0.04f
                }
            }
        }

        /*
         * THERMAL CONTEXT
         *
         * Visual bias is intentionally tiny. Actual heavy-work
         * throttling belongs to the resource-protection layer.
         */
        if (
            settings
                .moodThermalProtectionEnabled
        ) {
            when {
                snapshot.thermalStatus >=
                    PowerManager
                        .THERMAL_STATUS_SEVERE -> {
                    raw += dark * 0.12f
                    raw -= saturation * 0.10f
                    raw -= contrast * 0.06f
                }

                snapshot.thermalStatus >=
                    PowerManager
                        .THERMAL_STATUS_MODERATE -> {
                    raw += dark * 0.06f
                    raw -= saturation * 0.05f
                }
            }
        }

        val strength =
            settings.moodStrength
                .coerceIn(
                    0,
                    100,
                ) / 100f

        val influence =
            (
                raw *
                    5.5f *
                    strength
            )
                .roundToInt()
                .coerceIn(
                    -10,
                    10,
                )

        return influence
    }

    fun influenceDescription(
        influence: Int,
    ): String =
        when {
            influence >= 6 ->
                "strong environment match"

            influence >= 3 ->
                "good environment match"

            influence > 0 ->
                "slight environment match"

            influence <= -6 ->
                "poor environment match"

            influence <= -3 ->
                "weaker environment match"

            influence < 0 ->
                "slightly less suited"

            else ->
                "neutral"
        }

    private fun phaseFor(
        hour: Int,
    ): DayPhase =
        when (hour) {
            in 0..4 ->
                DayPhase.MIDNIGHT

            in 5..7 ->
                DayPhase.EARLY_MORNING

            in 8..11 ->
                DayPhase.MORNING

            in 12..16 ->
                DayPhase.AFTERNOON

            in 17..20 ->
                DayPhase.EVENING

            else ->
                DayPhase.NIGHT
        }

    private fun classifyLight(
        lux: Float?,
        darkThreshold: Int,
        brightThreshold: Int,
    ): LightMood {
        if (
            lux == null ||
            !lux.isFinite()
        ) {
            return LightMood.UNKNOWN
        }

        val dark =
            darkThreshold
                .coerceIn(
                    1,
                    200,
                )
                .toFloat()

        val bright =
            brightThreshold
                .coerceIn(
                    100,
                    10_000,
                )
                .coerceAtLeast(
                    darkThreshold + 50
                )
                .toFloat()

        return when {
            lux <= dark ->
                LightMood.DARK

            lux <= dark * 4f ->
                LightMood.DIM

            lux >= bright ->
                LightMood.BRIGHT

            else ->
                LightMood.BALANCED
        }
    }

    private fun ambientLux(
        context: Context,
    ): Float? {
        val now =
            System.currentTimeMillis()

        cachedLux?.let { value ->
            if (
                now - cachedLuxAt <=
                LIGHT_CACHE_MS
            ) {
                return value
            }
        }

        /*
         * Never block the UI thread waiting for a sensor event.
         * If we already have an older sample, use it; otherwise
         * this decision remains light-neutral.
         */
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            return cachedLux
        }

        val manager =
            context.getSystemService(
                SensorManager::class.java
            ) ?: return cachedLux

        val sensor =
            manager.getDefaultSensor(
                Sensor.TYPE_LIGHT
            ) ?: return cachedLux

        val latch =
            CountDownLatch(1)

        var sample: Float? = null

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(
                    event: SensorEvent,
                ) {
                    val value =
                        event.values
                            .firstOrNull()
                            ?.takeIf {
                                it.isFinite() &&
                                    it >= 0f
                            }
                            ?: return

                    sample = value
                    latch.countDown()
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }

        val registered =
            runCatching {
                manager.registerListener(
                    listener,
                    sensor,
                    SensorManager
                        .SENSOR_DELAY_NORMAL,
                    Handler(
                        Looper.getMainLooper()
                    ),
                )
            }.getOrDefault(false)

        if (!registered) {
            return cachedLux
        }

        try {
            latch.await(
                LIGHT_SAMPLE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread()
                .interrupt()
        } finally {
            runCatching {
                manager.unregisterListener(
                    listener
                )
            }
        }

        sample?.let {
            cachedLux = it
            cachedLuxAt =
                System.currentTimeMillis()
        }

        return sample ?: cachedLux
    }

    private fun batteryTemperature(
        context: Context,
    ): Float? {
        val intent =
            runCatching {
                context.registerReceiver(
                    null,
                    IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED
                    ),
                )
            }.getOrNull()
                ?: return null

        val raw =
            intent.getIntExtra(
                BatteryManager
                    .EXTRA_TEMPERATURE,
                Int.MIN_VALUE,
            )

        if (
            raw == Int.MIN_VALUE ||
            raw <= 0
        ) {
            return null
        }

        return raw / 10f
    }

    private fun thermalLabel(
        status: Int,
    ): String =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE ->
                "Thermal normal"

            PowerManager.THERMAL_STATUS_LIGHT ->
                "Thermal light"

            PowerManager.THERMAL_STATUS_MODERATE ->
                "Thermal moderate"

            PowerManager.THERMAL_STATUS_SEVERE ->
                "Thermal severe"

            PowerManager.THERMAL_STATUS_CRITICAL ->
                "Thermal critical"

            PowerManager.THERMAL_STATUS_EMERGENCY ->
                "Thermal emergency"

            PowerManager.THERMAL_STATUS_SHUTDOWN ->
                "Thermal shutdown"

            else ->
                "Thermal unknown"
        }
}
