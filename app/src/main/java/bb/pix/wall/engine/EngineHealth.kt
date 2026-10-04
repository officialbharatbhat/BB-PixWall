package bb.pix.wall.engine

import android.content.Context
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.SettingsStore

/**
 * Conservative self-healing for the stable engine.
 * Repairs only BB-PixWall-owned state and never mutates Android/OEM wallpaper internals.
 */
object EngineHealth {
    data class Report(
        val storageReady: Boolean,
        val quarantined: Int,
        val nextReady: Boolean,
        val cacheCount: Int,
        val action: String,
    )

    fun auditAndRepair(
        context: Context,
        settings: AppSettings = SettingsStore(context).load(),
        allowNetworkRefill: Boolean = false,
    ): Report {
        val storageReady = WallpaperFiles.ensure()
        if (!storageReady) {
            RuntimeStatus.set(context, "engine_health", "Storage unavailable")
            log("HEALTH storage unavailable")
            return Report(false, 0, false, 0, "storage-unavailable")
        }

        val bad = runCatching { WallpaperController.verifyCacheIntegrity() }.getOrDefault(0)
        val nextReady = runCatching {
            WallpaperController.ensureNext(context, settings, allowNetwork = false)
        }.getOrDefault(false)
        val cache = WallpaperController.cacheCount()
        val target = settings.cacheTarget.coerceIn(4, 36)

        var action = when {
            bad > 0 -> "quarantined:$bad"
            nextReady -> "ready"
            else -> "waiting-for-cache"
        }

        if (allowNetworkRefill && cache < target && WallpaperSourceEngine.networkAvailable(context)) {
            action += "+refill"
            EngineExecutors.io {
                runCatching { WallpaperController.primeCache(context, SettingsStore(context).load()) }
                runCatching { WallpaperController.ensureNext(context, SettingsStore(context).load(), allowNetwork = true) }
            }
        }

        RuntimeStatus.set(context, "engine_health", "Ready=$nextReady cache=$cache/$target bad=$bad action=$action")
        RuntimeStatus.setLong(context, "engine_health_last", System.currentTimeMillis())
        log("HEALTH ready=$nextReady cache=$cache/$target bad=$bad action=$action")
        return Report(true, bad, nextReady, cache, action)
    }

    private fun log(message: String) {
        runCatching {
            if (WallpaperFiles.ensure()) WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
