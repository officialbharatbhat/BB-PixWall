package bb.pix.wall

import android.app.Application
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.engine.StartupHealth
import bb.pix.wall.network.NetworkMonitor
import bb.pix.wall.engine.EngineExecutors

class BBPixWallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupHealth.init(this)
        NetworkMonitor.register(this)
        EngineExecutors.scheduler.schedule({ StartupHealth.markStable(this) }, 10, java.util.concurrent.TimeUnit.SECONDS)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (WallpaperFiles.ensure()) {
                    WallpaperFiles.runtimeLog.appendText(
                        "${System.currentTimeMillis()} CRASH thread=${thread.name} ${error.javaClass.name}: ${error.message}\n" +
                            error.stackTrace.take(30).joinToString("\n") { "  at $it" } + "\n"
                    )
                }
            }
            getSharedPreferences("bb_pixwall_startup_health", MODE_PRIVATE).edit().putBoolean("clean_shutdown", false).apply()
            previous?.uncaughtException(thread, error)
        }
    }
}
