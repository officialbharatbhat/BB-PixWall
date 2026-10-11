package bb.pix.wall.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperFiles
import java.io.File
import kotlin.math.roundToInt

/** On-demand only; no live polling, full-size bitmap decode, or persistent service. */
internal object HubDiagnostics {
    data class Preview(val label: String, val bitmap: Bitmap?)
    data class Snapshot(val images: List<Preview>, val footer: String)

    private fun preview(label: String, file: File): Preview {
        if (!file.isFile || file.length() <= 0L) return Preview(label, null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Preview(label, null)
            var sample = 1
            while (bounds.outWidth / sample > 112 || bounds.outHeight / sample > 160) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            Preview(label, BitmapFactory.decodeFile(file.absolutePath, options))
        }.getOrElse { Preview(label, null) }
    }

    fun collect(context: Context): Snapshot {
        val previews = listOf(
            preview("Home", WallpaperFiles.currentHome),
            preview("Lock", WallpaperFiles.currentLock),
            preview("Next H", WallpaperFiles.nextHome),
            preview("Next L", WallpaperFiles.nextLock),
        )
        val photos = RuntimeStatus.getInt(context, "download_photos")
        val drive = RuntimeStatus.getInt(context, "download_drive")
        val local = RuntimeStatus.getInt(context, "download_local")
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val net = if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            "unavailable"
        } else {
            "%.1f MB".format(java.util.Locale.US, (rx + tx).toDouble() / 1048576.0)
        }
        val cpu0 = Process.getElapsedCpuTime()
        val t0 = SystemClock.elapsedRealtime()
        SystemClock.sleep(300L)
        val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1L)
        val cpuPct = (100.0 * (Process.getElapsedCpuTime() - cpu0) / elapsed).coerceAtLeast(0.0)
        val ramKb = Debug.getPss()
        val stats = "Photos $photos  •  Drive $drive  •  Local $local prepared*" +
            "\nNet $net (UID, since boot)  •  RAM ${ramKb / 1024} MB PSS  •  CPU ${cpuPct.roundToInt()}% (300ms)"
        return Snapshot(previews, stats + "\n*Successfully acquired since this update; not historic album totals")
    }
}
