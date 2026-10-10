package bb.pix.wall.engine

import android.content.Context
import bb.pix.wall.BuildConfig
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicit user-initiated update check, no polling/background tracking.
 * Only trust Lite release tag and HTTPS GitHub API.
 */
object LiteUpdateChecker {
    data class Result(val available: Boolean, val version: String, val page: String, val message: String)
    fun check(context: Context): Result {
        val endpoint = URL("https://api.github.com/repos/officialbharatbhat/BB-PixWall/releases?per_page=30")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BB-PixWall-Lite-" + BuildConfig.VERSION_NAME)
        }
        return try {
            if (conn.responseCode != 200) {
                Result(false, BuildConfig.VERSION_NAME, "", "No new stable Lite release yet")
            } else {
                val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                val json = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
                    .filter { it.optString("tag_name").matches(Regex("lite-v\\d+\\.\\d+\\.\\d+")) && !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                    .maxWithOrNull(compareBy<JSONObject> { it.optString("tag_name").removePrefix("lite-v").split(".").getOrNull(0)?.toIntOrNull() ?: 0 }
                        .thenBy { it.optString("tag_name").removePrefix("lite-v").split(".").getOrNull(1)?.toIntOrNull() ?: 0 }
                        .thenBy { it.optString("tag_name").removePrefix("lite-v").split(".").getOrNull(2)?.toIntOrNull() ?: 0 })
                    ?: return Result(false, BuildConfig.VERSION_NAME, "", "No Lite release found")
                val tag = json.optString("tag_name")
                val version = tag.removePrefix("lite-v")
                val parts = version.split('.').mapNotNull { it.toIntOrNull() }
                val current = BuildConfig.VERSION_NAME.split('.').mapNotNull { it.toIntOrNull() }
                val newer = parts.size == 3 && current.size == 3 &&
                    (parts[0] > current[0] || parts[0] == current[0] &&
                        (parts[1] > current[1] || parts[1] == current[1] && parts[2] > current[2]))
                Result(newer, version, json.optString("html_url"), if (newer) "Update available: $version" else "You are up to date")
            }
        } catch (e: Exception) {
            Result(false, BuildConfig.VERSION_NAME, "", "Could not check GitHub: " + (e.message ?: "network error"))
        } finally {
            conn.disconnect()
        }
    }
}
