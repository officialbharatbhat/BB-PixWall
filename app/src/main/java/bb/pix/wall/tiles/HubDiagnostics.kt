package bb.pix.wall.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.net.TrafficStats
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperFiles
import java.io.File
import kotlin.math.roundToInt
import bb.pix.wall.engine.HubFetchCounters

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
            while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 480) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
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
        return Snapshot(previews, stats(context))
    }

    fun stats(context: Context): String {
        val photos = RuntimeStatus.getInt(context, "download_photos")
        val drive = RuntimeStatus.getInt(context, "download_drive")
        val local = RuntimeStatus.getInt(context, "download_local")
        val bytes = HubFetchCounters.downloadedBytes(context)
        val uid = android.os.Process.myUid()
        val received = TrafficStats.getUidRxBytes(uid)
        val sent = TrafficStats.getUidTxBytes(uid)
        val appTraffic = if (received == TrafficStats.UNSUPPORTED.toLong() || sent == TrafficStats.UNSUPPORTED.toLong() || received < 0L || sent < 0L) {
            "Unavailable"
        } else {
            "%.2f MB (since reboot)".format(java.util.Locale.US, (received.toDouble() + sent.toDouble()) / 1048576.0)
        }
        val memoryMb = Debug.getPss().toDouble() / 1024.0
        val cpuA = android.os.Process.getElapsedCpuTime()
        val timeA = android.os.SystemClock.elapsedRealtime()
        android.os.SystemClock.sleep(300L)
        val elapsed = (android.os.SystemClock.elapsedRealtime() - timeA).coerceAtLeast(1L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpu = (100.0 * (android.os.Process.getElapsedCpuTime() - cpuA) / (elapsed * cores))
            .coerceIn(0.0, 100.0).roundToInt()
        return "Photos: $photos images prepared" +
            "\nDrive: $drive images prepared" +
            "\nLocal: $local images prepared" +
            "\nWallpaper downloads: " + "%.2f MB".format(java.util.Locale.US, bytes / 1048576.0) +
            "\nApp Traffic: " + appTraffic +
            "\nRAM: " + "%.1f MB".format(java.util.Locale.US, memoryMb) + " (app PSS)" +
            "\nCPU: $cpu% (all cores, sampled)" +
            "\nCounters start with this feature update."

    }
}
