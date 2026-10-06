package bb.pix.wall.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import bb.pix.wall.network.LanServerService
import bb.pix.wall.settings.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON")) return
        val pending = goAsync()
        Thread {
            try {
                val settings = SettingsStore(context).load()
                if (settings.autoChange) runCatching { context.startForegroundService(Intent(context, WallpaperAutomationService::class.java)) }
                if (settings.lanEnabled) runCatching { context.startForegroundService(Intent(context, LanServerService::class.java)) }
            } finally { pending.finish() }
        }.start()
    }
}
