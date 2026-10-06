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
        val autonomous =
            AutonomousIntelligenceEngine
                .auditAndRepair(
                    context = context,
                    settings = settings,
                    allowNetworkRefill =
                        allowNetworkRefill,
                )

        val action =
            "${autonomous.grade} • " +
                "${autonomous.action}"

        RuntimeStatus.set(
            context,
            "engine_health",
            "${autonomous.grade} • " +
                "${autonomous.engineScore}/100 • " +
                "ready=${autonomous.nextReady} • " +
                "cache=${autonomous.cacheCount}",
        )

        RuntimeStatus.setLong(
            context,
            "engine_health_last",
            System.currentTimeMillis(),
        )

        log(
            "HEALTH ${autonomous.grade} " +
                "score=${autonomous.engineScore} " +
                "ready=${autonomous.nextReady} " +
                "cache=${autonomous.cacheCount} " +
                "action=${autonomous.action}"
        )

        return Report(
            storageReady =
                autonomous.storageReady,
            quarantined =
                autonomous.invalid,
            nextReady =
                autonomous.nextReady,
            cacheCount =
                autonomous.cacheCount,
            action =
                action,
        )
    }

    private fun log(message: String) {
        runCatching {
            if (WallpaperFiles.ensure()) WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
