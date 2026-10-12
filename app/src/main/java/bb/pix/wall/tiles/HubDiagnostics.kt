package bb.pix.wall.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.engine.WallpaperFiles
import java.io.File

/** Only four small previews while Hub is visible. No CPU, RAM, network or storage polling. */
internal object HubDiagnostics {
    data class Preview(val label: String, val bitmap: Bitmap?)

    private fun preview(label: String, file: File): Preview {
        if (!file.isFile || file.length() <= 0L) return Preview(label, null)
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Preview(label, null)

            var sample = 1
            // Hub tiles are only 44dp wide. Avoid decoding screen-sized images.
            while (bounds.outWidth / sample > 160 || bounds.outHeight / sample > 320) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            Preview(label, BitmapFactory.decodeFile(file.absolutePath, options))
        }.getOrElse { Preview(label, null) }
    }

    fun collect(context: Context): List<Preview> = listOf(
        preview("Home", WallpaperController.previewFile(context, WallpaperFiles.currentHome, true)),
        preview("Lock", WallpaperController.previewFile(context, WallpaperFiles.currentLock, false)),
        preview("Next H", WallpaperFiles.nextHome),
        preview("Next L", WallpaperFiles.nextLock),
    )
}
