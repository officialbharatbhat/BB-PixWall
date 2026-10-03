package bb.pix.wall.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object StartupHealth {
    private const val PREFS = "bb_pixwall_startup_health"
    private val heartbeat = AtomicLong(SystemClock.elapsedRealtime())

    fun init(context: Context) {
        phase(context, "application_onCreate")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastStart = prefs.getLong("last_start", 0L)
        val now = System.currentTimeMillis()
        val previousClean = prefs.getBoolean("clean_shutdown", true)
        val crashLoop = !previousClean && lastStart > 0L && now - lastStart < 120_000L
        val count = if (crashLoop) prefs.getInt("rapid_restart_count", 0) + 1 else 0
        prefs.edit().putLong("last_start", now).putBoolean("clean_shutdown", false).putInt("rapid_restart_count", count).apply()
        RuntimeStatus.set(context, "startup_recovery", if (count >= 2) "Recovery" else "Normal")
        if (count >= 2) RuntimeStatus.set(context, "startup_note", "Rapid restart detected; heavy warm-up deferred")
        startWatchdog(context.applicationContext)
    }

    fun phase(context: Context, name: String) {
        RuntimeStatus.set(context, "startup_phase", name)
        runCatching { if (WallpaperFiles.ensure()) WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} STARTUP $name\n") }
    }

    fun markStable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("clean_shutdown", true).putInt("rapid_restart_count", 0).apply()
        RuntimeStatus.set(context, "startup_phase", "stable")
    }

    private fun startWatchdog(context: Context) {
        val main = Handler(Looper.getMainLooper())
        val beat = object : Runnable { override fun run() { heartbeat.set(SystemClock.elapsedRealtime()); main.postDelayed(this, 1000L) } }
        main.post(beat)
        EngineExecutors.scheduler.scheduleAtFixedRate({
            val lag = SystemClock.elapsedRealtime() - heartbeat.get()
            RuntimeStatus.setLong(context, "main_thread_lag_ms", lag)
            if (lag > 4500L) runCatching { WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} WATCHDOG main-thread-lag=${lag}ms\n") }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }
}
