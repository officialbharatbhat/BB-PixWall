package bb.pix.wall.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground location helper for Weather Mood.
 *
 * No continuous tracking.
 * No ACCESS_BACKGROUND_LOCATION.
 *
 * Weather refresh asks for a fresh location when appropriate,
 * while last-known location remains available as a fast fallback.
 */
object MoodLocationEngine {

    private const val CURRENT_TIMEOUT_SECONDS =
        7L

    private const val MAX_LAST_KNOWN_AGE_MS =
        6L * 60L * 60L * 1000L

    data class Result(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val label: String,
        val precise: Boolean,
        val source: String,
    )

    fun hasAnyPermission(
        context: Context,
    ): Boolean =
        hasFinePermission(context) ||
            hasCoarsePermission(context)

    fun hasFinePermission(
        context: Context,
    ): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun hasCoarsePermission(
        context: Context,
    ): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun permissionLabel(
        context: Context,
    ): String =
        when {
            hasFinePermission(context) ->
                "Precise location granted"

            hasCoarsePermission(context) ->
                "Approximate location granted"

            else ->
                "Location permission needed"
        }

    fun resolve(
        context: Context,
    ): Result? {
        if (!hasAnyPermission(context)) {
            RuntimeStatus.set(
                context,
                "weather_location_status",
                "Location permission needed",
            )

            return null
        }

        val manager =
            context.getSystemService(
                Context.LOCATION_SERVICE
            ) as? LocationManager
                ?: return null

        val fresh =
            if (
                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R
            ) {
                currentLocation(
                    manager = manager,
                    precise =
                        hasFinePermission(context),
                )
            } else {
                null
            }

        val chosen =
            fresh
                ?: bestLastKnownLocation(
                    manager,
                )
                ?: return null

        val label =
            reverseGeocode(
                context,
                chosen,
            )

        val precise =
            hasFinePermission(context)

        val result =
            Result(
                latitude =
                    chosen.latitude,
                longitude =
                    chosen.longitude,
                accuracyMeters =
                    chosen.accuracy
                        .takeIf {
                            chosen.hasAccuracy()
                        },
                label = label,
                precise = precise,
                source =
                    if (fresh != null) {
                        "current"
                    } else {
                        "last-known"
                    },
            )

        RuntimeStatus.set(
            context,
            "weather_location_status",
            buildString {
                append(result.label)

                result.accuracyMeters?.let {
                    append(
                        " • ±${it.toInt()}m"
                    )
                }

                append(
                    if (precise) {
                        " • precise"
                    } else {
                        " • approximate"
                    }
                )
            },
        )

        return result
    }

    @Suppress("MissingPermission")
    private fun currentLocation(
        manager: LocationManager,
        precise: Boolean,
    ): Location? {
        val provider =
            when {
                precise &&
                    manager.isProviderEnabled(
                        LocationManager.GPS_PROVIDER
                    ) ->
                    LocationManager.GPS_PROVIDER

                manager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                ) ->
                    LocationManager.NETWORK_PROVIDER

                manager.isProviderEnabled(
                    LocationManager.PASSIVE_PROVIDER
                ) ->
                    LocationManager.PASSIVE_PROVIDER

                else ->
                    return null
            }

        val ref =
            AtomicReference<Location?>(
                null
            )

        val latch =
            CountDownLatch(1)

        val cancellation =
            CancellationSignal()

        return try {
            manager.getCurrentLocation(
                provider,
                cancellation,
                { command ->
                    command.run()
                },
            ) { location ->
                ref.set(location)
                latch.countDown()
            }

            latch.await(
                CURRENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )

            cancellation.cancel()

            ref.get()
        } catch (_: Throwable) {
            cancellation.cancel()
            null
        }
    }

    @Suppress("MissingPermission")
    private fun bestLastKnownLocation(
        manager: LocationManager,
    ): Location? {
        val now =
            System.currentTimeMillis()

        val candidates =
            listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).mapNotNull { provider ->
                runCatching {
                    manager.getLastKnownLocation(
                        provider
                    )
                }.getOrNull()
            }

        if (candidates.isEmpty()) {
            return null
        }

        val recent =
            candidates.filter {
                now - it.time <=
                    MAX_LAST_KNOWN_AGE_MS
            }

        val pool =
            if (recent.isNotEmpty()) {
                recent
            } else {
                candidates
            }

        return pool.maxWithOrNull(
            compareBy<Location> {
                it.time
            }.thenBy {
                if (it.hasAccuracy()) {
                    -it.accuracy
                } else {
                    Float.NEGATIVE_INFINITY
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(
        context: Context,
        location: Location,
    ): String {
        val fallback =
            String.format(
                Locale.US,
                "%.4f, %.4f",
                location.latitude,
                location.longitude,
            )

        if (
            !Geocoder.isPresent()
        ) {
            return fallback
        }

        return runCatching {
            val geocoder =
                Geocoder(
                    context,
                    Locale.getDefault(),
                )

            val address =
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                )?.firstOrNull()
                    ?: return@runCatching fallback

            val city =
                address.locality
                    ?: address.subLocality
                    ?: address.subAdminArea
                    ?: address.adminArea

            val state =
                address.adminArea

            when {
                !city.isNullOrBlank() &&
                    !state.isNullOrBlank() &&
                    !city.equals(
                        state,
                        ignoreCase = true,
                    ) ->
                    "$city, $state"

                !city.isNullOrBlank() ->
                    city

                !state.isNullOrBlank() ->
                    state

                else ->
                    fallback
            }
        }.getOrDefault(
            fallback
        )
    }
}
