package bb.pix.wall.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.AspectPreference
import bb.pix.wall.settings.WallpaperOrder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

object WallpaperSourceEngine {
    private data class CachedIndex(val at: Long, val items: List<Candidate>)
    private val indexCache = java.util.concurrent.ConcurrentHashMap<String, CachedIndex>()
    private const val INDEX_TTL_MS = 10 * 60 * 1000L

    fun invalidateCloudIndex() { indexCache.clear() }

    data class Candidate(
        val id: String,
        val priority: Int,
        val source: String,
        val url: String? = null,
        val file: File? = null,
        val name: String = id,
        val size: Long = -1L,
        val modified: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
    )

    fun collect(context: Context, settings: AppSettings, allowNetwork: Boolean = true): List<Candidate> {
        val all = mutableListOf<Candidate>()
        val network = allowNetwork && networkAvailable(context) && networkPolicyAllows(context, settings)
        var photosOk = false
        var driveOk = false

        if (settings.photosAlbumUrl.isNotBlank()) {
            if (!network) RuntimeStatus.source(context, "photos", "Offline")
            else if (!sourceBackoffExpired(context, "photos")) RuntimeStatus.source(context, "photos", "Backoff")
            else {
                val started = System.currentTimeMillis()
                runCatching { googlePhotos(settings.photosAlbumUrl, settings.dataSaverEnabled) }
                    .onSuccess {
                        all += it; photosOk = it.isNotEmpty(); sourceSuccess(context, "photos", System.currentTimeMillis() - started, it.size)
                    }
                    .onFailure { sourceFailure(context, "photos", it, System.currentTimeMillis() - started); log("Google Photos: ${it.message}") }
            }
        } else RuntimeStatus.source(context, "photos", "Not configured")

        if (settings.driveFolderUrl.isNotBlank()) {
            if (!network) RuntimeStatus.source(context, "drive", "Offline")
            else if (!sourceBackoffExpired(context, "drive")) RuntimeStatus.source(context, "drive", "Backoff")
            else {
                val started = System.currentTimeMillis()
                runCatching { googleDriveFolder(settings.driveFolderUrl, settings.dataSaverEnabled) }
                    .onSuccess {
                        all += it; driveOk = it.isNotEmpty(); sourceSuccess(context, "drive", System.currentTimeMillis() - started, it.size, fallback = !photosOk)
                    }
                    .onFailure { sourceFailure(context, "drive", it, System.currentTimeMillis() - started); log("Google Drive: ${it.message}") }
            }
        } else RuntimeStatus.source(context, "drive", "Not configured")

        if (Environment.isExternalStorageManager()) {
            val local = localFiles(settings); all += local
            RuntimeStatus.source(context, "local", when { local.isEmpty() -> "Empty"; !photosOk && !driveOk -> "Fallback active"; else -> "Healthy" })
        } else RuntimeStatus.source(context, "local", "Permission required")

        val grouped = all.distinctBy { it.id }
            .groupBy { it.priority }
            .toSortedMap()
            .values
            .flatMap { sortGroup(it, settings.wallpaperOrder, stableSeed(context)) }
        RuntimeStatus.set(context, "candidate_count", grouped.size.toString())
        return grouped
    }

    private fun sourceSuccess(context: Context, source: String, ms: Long, count: Int, fallback: Boolean = false) {
        RuntimeStatus.reset(context, "${source}_fail_streak")
        RuntimeStatus.setLong(context, "${source}_last_latency_ms", ms)
        RuntimeStatus.setLong(context, "${source}_last_ok", System.currentTimeMillis())
        RuntimeStatus.set(context, "${source}_last_count", count.toString())
        RuntimeStatus.source(context, source, when { count == 0 -> "Empty"; fallback -> "Fallback active"; ms > 4500L -> "Slow"; else -> "Healthy" })
    }

    private fun sourceFailure(context: Context, source: String, error: Throwable, ms: Long) {
        val streak = RuntimeStatus.increment(context, "${source}_fail_streak")
        RuntimeStatus.setLong(context, "${source}_last_latency_ms", ms)
        RuntimeStatus.setLong(context, "${source}_last_fail", System.currentTimeMillis())
        val backoff = (1_000L shl (streak - 1).coerceIn(0, 6)).coerceAtMost(60_000L)
        RuntimeStatus.setLong(context, "${source}_backoff_until", System.currentTimeMillis() + backoff)
        RuntimeStatus.set(context, "fallback_reason", "$source failed: ${error.message ?: error.javaClass.simpleName}")
        RuntimeStatus.source(context, source, "Failed")
    }

    private fun sourceBackoffExpired(context: Context, source: String): Boolean =
        System.currentTimeMillis() >= RuntimeStatus.getLong(context, "${source}_backoff_until", 0L)

    private fun stableSeed(context: Context): Long {
        val prefs = context.getSharedPreferences("bb_pixwall_order", Context.MODE_PRIVATE)
        var seed = prefs.getLong("shuffle_seed", 0L)
        if (seed == 0L) { seed = System.currentTimeMillis() xor context.packageName.hashCode().toLong(); prefs.edit().putLong("shuffle_seed", seed).apply() }
        return seed
    }

    private fun sortGroup(items: List<Candidate>, order: WallpaperOrder, seed: Long): List<Candidate> = when (order) {
        WallpaperOrder.A_Z -> items.sortedBy { it.name.lowercase(Locale.ROOT) }
        WallpaperOrder.Z_A -> items.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        WallpaperOrder.DATE_NEWEST -> items.sortedWith(compareByDescending<Candidate> { it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })
        WallpaperOrder.DATE_OLDEST -> items.sortedWith(compareBy<Candidate> { if (it.modified <= 0L) Long.MAX_VALUE else it.modified }.thenBy { it.name.lowercase(Locale.ROOT) })
        WallpaperOrder.SIZE_LOW_HIGH -> items.sortedWith(compareBy<Candidate> { if (it.size < 0) Long.MAX_VALUE else it.size }.thenBy { it.name.lowercase(Locale.ROOT) })
        WallpaperOrder.SIZE_HIGH_LOW -> items.sortedWith(compareByDescending<Candidate> { it.size }.thenBy { it.name.lowercase(Locale.ROOT) })
        WallpaperOrder.SURPRISE -> items.sortedBy { it.id.hashCode().toLong() xor (System.currentTimeMillis() / 86_400_000L) }
        WallpaperOrder.RANDOM_SHUFFLE -> items.shuffled(Random(seed))
    }


    private fun googlePhotos(sharedUrl: String, dataSaver: Boolean): List<Candidate> {
        val key = "photos|$dataSaver|${sharedUrl.trim()}"
        val now = System.currentTimeMillis()
        indexCache[key]?.takeIf { now - it.at < INDEX_TTL_MS }?.let { return it.items }
        return parseGooglePhotos(sharedUrl, dataSaver).also { indexCache[key] = CachedIndex(now, it) }
    }

    private fun googleDriveFolder(folderUrl: String, dataSaver: Boolean): List<Candidate> {
        val key = "drive|$dataSaver|${folderUrl.trim()}"
        val now = System.currentTimeMillis()
        indexCache[key]?.takeIf { now - it.at < INDEX_TTL_MS }?.let { return it.items }
        return parseGoogleDriveFolder(folderUrl, dataSaver).also { indexCache[key] = CachedIndex(now, it) }
    }
    private fun parseGooglePhotos(sharedUrl: String, dataSaver: Boolean): List<Candidate> {
        val html = getText(sharedUrl)
            .replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")
        val patterns = listOf(
            Regex("https://lh3\\.googleusercontent\\.com/(?:pw/)?[A-Za-z0-9_-]{20,}(?:=[A-Za-z0-9_-]+)?"),
            Regex("https://lh3\\.googleusercontent\\.com/[A-Za-z0-9_-]{20,}(?:=[A-Za-z0-9_-]+)?"),
            Regex("https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_-]{20,}"),
            Regex("https:\\/\\/lh3\\.googleusercontent\\.com\\/(?:pw\\/)?[A-Za-z0-9_-]{20,}")
        )
        val urls = linkedSetOf<String>()
        patterns.forEach { re -> re.findAll(html).forEach { m ->
            val decoded = m.value.replace("\\/", "/")
            val base = decoded.substringBefore('=')
            urls += if (dataSaver) "$base=w1080-h2400-rw" else "$base=s0"
        }}
        return urls.mapIndexed { index, u ->
            val id = u.substringAfterLast('/').substringBefore('=')
            Candidate("photos:$id", 0, "Photos", url=u, name="Photo_${(index+1).toString().padStart(4,'0')}")
        }
    }

    private fun parseGoogleDriveFolder(folderUrl: String, dataSaver: Boolean): List<Candidate> {
        val html = getText(folderUrl).replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/")
        val ids = linkedSetOf<String>()
        listOf(
            Regex("/file/d/([A-Za-z0-9_-]{20,100})"),
            Regex("(?:open\\?id=|[?&]id=)([A-Za-z0-9_-]{20,100})"),
            Regex("\\[\\\"([A-Za-z0-9_-]{20,100})\\\",[^\\]]{0,900}?(?:image/jpeg|image/png|image/webp|image/avif)"),
            Regex("drive\\.google\\.com/(?:uc|thumbnail)\\?[^\\\"']*id=([A-Za-z0-9_-]{20,100})")
        ).forEach { r -> r.findAll(html).forEach { ids += it.groupValues[1] } }
        return ids.mapIndexed { index, id -> Candidate(
            "drive:$id", 1, "Drive",
            url=if (dataSaver) "https://drive.google.com/thumbnail?id=$id&sz=w1080" else "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t",
            name="Drive_${(index+1).toString().padStart(4,'0')}"
        ) }
    }

    private fun localFiles(settings: AppSettings): List<Candidate> {
        if (!WallpaperFiles.ensure()) return emptyList()
        val allowed = setOf("jpg","jpeg","png","webp","avif")
        val roots = listOf(WallpaperFiles.local, WallpaperFiles.root)
        return roots.flatMap { dir -> dir.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in allowed }
            .distinctBy { it.canonicalPath }
            .mapNotNull { f ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                val w = bounds.outWidth.coerceAtLeast(0); val h = bounds.outHeight.coerceAtLeast(0)
                val ok = when (settings.aspectPreference) {
                    AspectPreference.ANY, AspectPreference.SCREEN_MATCH -> true
                    AspectPreference.PORTRAIT -> h >= w
                    AspectPreference.LANDSCAPE -> w > h
                }
                if (!ok) null else Candidate("local:${f.canonicalPath}", 2, "Local", file=f, name=f.name, size=f.length(), modified=f.lastModified(), width=w, height=h)
            }
    }

    private fun getText(rawUrl: String): String = retry(4) {
        var url = URL(rawUrl.trim())
        repeat(10) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 18_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) url = URL(url, c.getHeaderField("Location") ?: error("Redirect without Location"))
                else {
                    if (code !in 200..299) error("HTTP $code")
                    BufferedReader(InputStreamReader(c.inputStream)).use { return@retry it.readText() }
                }
            } finally { c.disconnect() }
        }
        error("Too many redirects")
    }

    private fun <T> retry(attempts: Int, block: () -> T): T {
        var last: Throwable? = null
        repeat(attempts) { n ->
            try { return block() } catch (t: Throwable) {
                last = t
                if (n < attempts - 1) Thread.sleep(min(4_000L, 350L * (1L shl n)))
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    fun networkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun wifiAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    private fun networkPolicyAllows(context: Context, settings: AppSettings): Boolean {
        if (wifiAvailable(context)) return true
        if (settings.wifiOnly) return false
        return settings.mobileDataAllowed
    }
    private fun log(message: String) { runCatching { if (WallpaperFiles.ensure()) WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} SOURCE $message\n") } }
}
