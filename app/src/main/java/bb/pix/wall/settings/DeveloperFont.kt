package bb.pix.wall.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/** One private font shared across Compose screens and the local web dashboard. */
object DeveloperFont {
    private const val PREFS = "bb_developer_font"
    private const val FILE = "custom-ui-font"
    private const val LIMIT = 12L * 1024 * 1024
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun file(context: Context): File = File(context.filesDir, FILE + ".ttf")
    fun isSelected(context: Context): Boolean = prefs(context).getBoolean("selected", false) && file(context).isFile
    fun size(context: Context): Float = prefs(context).getFloat("size", 1f).coerceIn(.80f, 1.30f)
    fun name(context: Context): String = if (isSelected(context)) prefs(context).getString("name", "Custom font") ?: "Custom font" else "App default"
    fun updateSize(context: Context, value: Float) { prefs(context).edit().putFloat("size", value.coerceIn(.80f, 1.30f)).apply() }

    @OptIn(ExperimentalTextApi::class)
    fun family(context: Context): FontFamily? {
        val f = file(context)
        return if (isSelected(context)) runCatching { FontFamily(Font(f)) }.getOrNull() else null
    }

    fun install(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Selected font.ttf"
        require(name.lowercase().endsWith(".ttf") || name.lowercase().endsWith(".otf")) { "Select a .ttf or .otf file" }
        val temp = File(context.cacheDir, "incoming-ui-font.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open font")
            input.use { source ->
                temp.outputStream().use { target ->
                    var bytes = 0L
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= LIMIT) { "Font must be below 12 MB" }
                        target.write(buffer, 0, count)
                    }
                    require(bytes > 0) { "Empty font file" }
                }
            }
            Typeface.createFromFile(temp) // Reject corrupt or unsupported font before changing setting.
            val dest = file(context)
            temp.copyTo(dest, overwrite = true)
            prefs(context).edit().putBoolean("selected", true).putString("name", name).apply()
            return name
        } finally { temp.delete() }
    }

    fun reset(context: Context) {
        prefs(context).edit().putBoolean("selected", false).putFloat("size", 1f).remove("name").apply()
        file(context).delete()
    }
}
