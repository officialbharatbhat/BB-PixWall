package bb.pix.wall.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import bb.pix.wall.engine.AdaptiveMoodEngine
import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.Phase1FinalEngine
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.engine.WeatherMoodEngine
import bb.pix.wall.settings.SettingsStore

class MoodAutoReactReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app =
            context.applicationContext

        val settings =
            SettingsStore(app)
                .load()

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                schedule(
                    app,
                    settings.moodAutoReactEnabled,
                )

                return
            }
        }

        if (
            intent.action !=
                ACTION_CHECK ||
            !settings.moodAutoReactEnabled ||
            !settings.moodEngineEnabled
        ) {
            return
        }

        val pending =
            goAsync()

        EngineExecutors.io {
            try {
                evaluate(
                    app
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val ACTION_CHECK =
            "bb.pix.wall.MOOD_AUTO_REACT_CHECK"

        private const val REQUEST_CODE =
            51031

        private const val CHECK_INTERVAL_MS =
            30L * 60L * 1000L

        private const val FIRST_CHECK_MS =
            10L * 60L * 1000L

        private const val PREFS =
            "bb_pixwall_mood_auto_react"

        fun schedule(
            context: Context,
            enabled: Boolean,
        ) {
            val alarm =
                context.getSystemService(
                    AlarmManager::class.java
                ) ?: return

            val pi =
                pendingIntent(
                    context
                )

            alarm.cancel(pi)

            if (!enabled) {
                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Off",
                )

                return
            }

            alarm.setInexactRepeating(
                AlarmManager
                    .ELAPSED_REALTIME,
                SystemClock
                    .elapsedRealtime() +
                    FIRST_CHECK_MS,
                CHECK_INTERVAL_MS,
                pi,
            )

            RuntimeStatus.set(
                context,
                "mood_auto_react",
                "Watching context",
            )
        }

        private fun pendingIntent(
            context: Context,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(
                    context,
                    MoodAutoReactReceiver::class.java,
                ).setAction(
                    ACTION_CHECK
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
            )

        private fun evaluate(
            context: Context,
        ) {
            val settings =
                SettingsStore(context)
                    .load()

            if (
                !settings.moodAutoReactEnabled ||
                !settings.moodEngineEnabled
            ) {
                return
            }

            val policy =
                Phase1FinalEngine
                    .resourcePolicy(
                        context,
                        settings,
                    )

            if (!policy.allowAutoReact) {
                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Paused • ${policy.label}",
                )

                return
            }

            val adaptive =
                AdaptiveMoodEngine.snapshot(
                    context,
                    settings,
                )

            val weather =
                WeatherMoodEngine.snapshot(
                    context,
                    settings,
                )

            val profile =
                Phase1FinalEngine
                    .publishMoodProfile(
                        context,
                        settings,
                        adaptive,
                        weather,
                    )

            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                )

            val previous =
                prefs.getString(
                    "fingerprint",
                    "",
                ).orEmpty()

            if (previous.isBlank()) {
                prefs.edit()
                    .putString(
                        "fingerprint",
                        profile.fingerprint,
                    )
                    .apply()

                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Baseline • ${profile.label}",
                )

                return
            }

            if (
                previous ==
                    profile.fingerprint
            ) {
                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Stable • ${profile.label}",
                )

                return
            }

            val now =
                System.currentTimeMillis()

            val lastApply =
                prefs.getLong(
                    "last_apply",
                    0L,
                )

            val cooldownMs =
                settings
                    .moodAutoReactCooldownMinutes
                    .coerceIn(
                        15,
                        180,
                    ) *
                    60_000L

            if (
                lastApply > 0L &&
                now - lastApply <
                    cooldownMs
            ) {
                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Change detected • cooldown",
                )

                return
            }

            /*
             * Auto-react never waits for cloud.
             * It only consumes a wallpaper that is already offline-ready.
             */
            val changed =
                WallpaperController.nextWall(
                    context,
                    allowNetwork = false,
                    userInitiated = false,
                )

            if (changed) {
                prefs.edit()
                    .putString(
                        "fingerprint",
                        profile.fingerprint,
                    )
                    .putLong(
                        "last_apply",
                        now,
                    )
                    .apply()

                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Applied • ${profile.label}",
                )
            } else {
                RuntimeStatus.set(
                    context,
                    "mood_auto_react",
                    "Context changed • waiting for offline-ready wall",
                )
            }
        }
    }
}
