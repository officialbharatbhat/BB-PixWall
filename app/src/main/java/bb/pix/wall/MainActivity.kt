package bb.pix.wall

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import bb.pix.wall.automation.WallpaperAutomationService
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.network.LanServerService
import bb.pix.wall.root.RootAccess
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.ui.LiteHomeScreen
import bb.pix.wall.ui.theme.BBPixWallTheme
import bb.pix.wall.ui.theme.ThemeProfile

class MainActivity : ComponentActivity() {
    private val blurDebounce = Handler(Looper.getMainLooper())
    private var blurGeneration = 0
    private var sourceMutationGeneration = 0
    private var sourceMutationFuture: java.util.concurrent.ScheduledFuture<*>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val runtime = getSharedPreferences("bb_pixwall_runtime", MODE_PRIVATE)
        if (runtime.getInt("migration_version", 0) < 9) {
            runtime.edit().putBoolean("blur_master", false).putInt("migration_version", 9).apply()
        }

        setContent {
            val store =
                remember {
                    SettingsStore(
                        applicationContext
                    )
                }

            var settings by
                remember {
                    mutableStateOf(
                        store.load()
                    )
                }

            BBPixWallTheme(
                profile =
                    ThemeProfile.MATERIAL_PRO,
                appearanceMode =
                    settings.appearanceMode,
            ) {
                LiteHomeScreen(
                    settings = settings,
                    onSettingsChange = { requested ->
                        /*
                         * Lite deliberately hard-disables every heavy
                         * intelligence system. Only fetch/order/apply,
                         * blur, remote and root background tuning remain.
                         */
                        val updated =
                            requested.copy(
                                decisionEngineEnabled = false,
                                smartPairingEnabled = false,
                                moodEngineEnabled = false,
                                moodAutoReactEnabled = false,
                                moodWeatherEnabled = false,
                                sourcePriorityMode =
                                    bb.pix.wall.settings.SourcePriorityMode.PHOTOS_FIRST,
                                smartCropEnabled = false,
                                dataSaverEnabled = false,
                                adaptiveResourceProtectionEnabled = false,
                                cacheTarget =
                                    requested.cacheTarget
                                        .coerceIn(4, 4),
                            )

                        val previous =
                            settings

                        settings = updated
                        store.save(updated)

                        handleSettingsMutation(
                            previous,
                            updated,
                        ) {}
                    },
                    onRequestAdvanced = {
                        Thread {
                            val result =
                                RootAccess.requestAndTune(
                                    applicationContext
                                )

                            if (result.granted) {
                                val updated =
                                    settings.copy(
                                        engineMode =
                                            EngineMode.ADVANCED,
                                        decisionEngineEnabled =
                                            false,
                                        smartPairingEnabled =
                                            false,
                                        moodEngineEnabled =
                                            false,
                                        moodAutoReactEnabled =
                                            false,
                                        moodWeatherEnabled =
                                            false,
                                    )

                                store.save(updated)

                                runOnUiThread {
                                    settings = updated
                                }
                            }
                        }.start()
                    },
                )
            }
        }

        val initial = SettingsStore(this).load()
        syncAutomation(initial)
        syncLan(initial, forceRestart = false)
        bb.pix.wall.engine.EngineExecutors.scheduler.schedule({
            val latest =
                SettingsStore(
                    applicationContext
                ).load()

            if (!latest.autoChange) {
                bb.pix.wall.engine.EngineExecutors.io {
                    runCatching {
                        WallpaperController.ensureNext(
                            applicationContext,
                            latest,
                            allowNetwork = true,
                        )
                    }
                }
            }
        }, 1200L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun handleSettingsMutation(previous: AppSettings, updated: AppSettings, refresh: () -> Unit) {
        val automationChanged = previous.autoChange != updated.autoChange ||
            previous.triggerMode != updated.triggerMode ||
            previous.intervalMinutes != updated.intervalMinutes ||
            previous.chargingOnly != updated.chargingOnly ||
            previous.mobileDataAllowed != updated.mobileDataAllowed ||
            previous.pauseBatterySaver != updated.pauseBatterySaver ||
            previous.pauseLowBattery != updated.pauseLowBattery ||
            previous.lowBatteryThreshold != updated.lowBatteryThreshold ||
            previous.quietHoursEnabled != updated.quietHoursEnabled ||
            previous.quietStartHour != updated.quietStartHour ||
            previous.quietEndHour != updated.quietEndHour ||
            previous.backgroundGuardEnabled != updated.backgroundGuardEnabled ||
            previous.engineMode != updated.engineMode
        if (automationChanged) syncAutomation(updated)

        if (previous.lanEnabled != updated.lanEnabled || previous.lanPort != updated.lanPort) {
            syncLan(updated, forceRestart = true)
        }

        val sourcePipelineChanged =
            previous.photosAlbumUrl != updated.photosAlbumUrl ||
                previous.driveFolderUrl != updated.driveFolderUrl ||
                previous.wallpaperOrder != updated.wallpaperOrder ||
                previous.dataSaverEnabled != updated.dataSaverEnabled

        if (sourcePipelineChanged) {
            if (
                previous.photosAlbumUrl !=
                updated.photosAlbumUrl ||
                previous.driveFolderUrl !=
                updated.driveFolderUrl ||
                previous.dataSaverEnabled !=
                updated.dataSaverEnabled
            ) {
                bb.pix.wall.engine.WallpaperSourceEngine
                    .invalidateCloudIndex()
            }




            WallpaperController.invalidateQueue()

            sourceMutationFuture?.cancel(false)

            val generation =
                ++sourceMutationGeneration
            // Paste/typing used to launch a cloud parse for every character. Debounce it.
            sourceMutationFuture = bb.pix.wall.engine.EngineExecutors.scheduler.schedule({
                if (generation != sourceMutationGeneration) return@schedule
                val latest = SettingsStore(applicationContext).load()
                bb.pix.wall.engine.EngineExecutors.io {
                    runCatching { WallpaperController.ensureNext(applicationContext, latest, allowNetwork = true) }
                    runOnUiThread(refresh)
                }
            }, 900L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        val blurToggleChanged = previous.homeBlurEnabled != updated.homeBlurEnabled ||
            previous.lockBlurEnabled != updated.lockBlurEnabled
        val blurRadiusChanged = previous.homeBlurRadius != updated.homeBlurRadius ||
            previous.lockBlurRadius != updated.lockBlurRadius
        val qualityPipelineChanged = previous.smartCropEnabled != updated.smartCropEnabled ||
            previous.smartCropTolerancePct != updated.smartCropTolerancePct
        if (blurToggleChanged || blurRadiusChanged || qualityPipelineChanged) {
            val generation = ++blurGeneration
            blurDebounce.removeCallbacksAndMessages(null)
            blurDebounce.postDelayed({
                if (generation != blurGeneration) return@postDelayed
                bb.pix.wall.engine.EngineExecutors.io {
                    runCatching { WallpaperController.setBlurMasterAndReapply(applicationContext, updated.homeBlurEnabled || updated.lockBlurEnabled) }
                    runOnUiThread(refresh)
                }
            }, if (blurToggleChanged) 80L else 420L)
        }
    }

    private fun syncAutomation(s: AppSettings) {
        val intent = Intent(this, WallpaperAutomationService::class.java)
        if (s.autoChange) startForegroundService(intent) else stopService(intent)
    }

    private fun syncLan(s: AppSettings, forceRestart: Boolean) {
        val intent = Intent(this, LanServerService::class.java)
        if (!s.lanEnabled) {
            stopService(intent)
            return
        }
        if (forceRestart) stopService(intent)
        startForegroundService(intent)
    }
    override fun onDestroy() {
        blurDebounce.removeCallbacksAndMessages(null)
        sourceMutationFuture?.cancel(false)
        super.onDestroy()
    }

}
