package bb.pix.wall.engine

import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/** On-demand original-file metadata; never runs on wallpaper trigger threads. */
object WallpaperDna {
    data class Report(
        val width: Int,
        val height: Int,
        val bytes: Long,
        val format: String,
        val sha256: String,
    )

    fun inspect(file: File): Report? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            Report(
                width = bounds.outWidth,
                height = bounds.outHeight,
                bytes = file.length(),
                format = bounds.outMimeType ?: file.extension.lowercase(),
                sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        }.getOrNull()
    }
}
