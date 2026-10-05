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
import java.net.URLEncoder
import org.json.JSONArray
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
        val network =
            allowNetwork &&
                networkAvailable(context) &&
                networkPolicyAllows(context, settings)

        var photosOk = false
        var driveOk = false


        if (settings.photosAlbumUrl.isNotBlank()) {
                if (!network) RuntimeStatus.source(context, "photos", "Offline")
                else if (!sourceBackoffExpired(context, "photos")) RuntimeStatus.source(context, "photos", "Backoff")
                else {
                    val started = System.currentTimeMillis()
                    runCatching { googlePhotos(settings.photosAlbumUrl, settings.dataSaverEnabled) }
                        .onSuccess {
                            all += it
                            photosOk = it.isNotEmpty()
                            sourceSuccess(
                                context,
                                "photos",
                                System.currentTimeMillis() - started,
                                it,
                            )

                            if (it.isNotEmpty()) {
                                RuntimeStatus.set(
                                    context,
                                    "fallback_reason",
                                    "",
                                )

                                RuntimeStatus.set(
                                    context,
                                    "photos_index_total",
                                    it.size.toString(),
                                )

                                RuntimeStatus.set(
                                    context,
                                    "photos_index_cache_hit",
                                    "no",
                                )
                            }
                        }
                        .onFailure { sourceFailure(context, "photos", it, System.currentTimeMillis() - started); log("Google Photos: ${it.message}") }
                }
            } else RuntimeStatus.source(context, "photos", "Not configured")



        if (settings.driveFolderUrl.isNotBlank()) {
                // Drive is a mirror/fallback, not a second source to poll on every healthy Photos cycle.
                // This keeps network and storage churn low for the user's cloud-first setup.
                if (photosOk) RuntimeStatus.source(context, "drive", "Standby mirror")
                else if (!network) RuntimeStatus.source(context, "drive", "Offline")
                else if (!sourceBackoffExpired(context, "drive")) RuntimeStatus.source(context, "drive", "Backoff")
                else {
                    val started = System.currentTimeMillis()
                    runCatching { googleDriveFolder(settings.driveFolderUrl, settings.dataSaverEnabled) }
                        .onSuccess {
                            all += it
                            driveOk = it.isNotEmpty()
                            sourceSuccess(
                                context,
                                "drive",
                                System.currentTimeMillis() - started,
                                it,
                                fallback = true,
                            )
                        }
                        .onFailure { sourceFailure(context, "drive", it, System.currentTimeMillis() - started); log("Google Drive: ${it.message}") }
                }
            } else RuntimeStatus.source(context, "drive", "Not configured")




        if (Environment.isExternalStorageManager()) {
            val local =
                localFiles(settings)

            val localFallbackActive =
                !photosOk &&
                    !driveOk

            if (localFallbackActive) {
                all += local
            }

            RuntimeStatus.source(
                context,
                "local",
                when {
                    local.isEmpty() ->
                        "Empty"

                    localFallbackActive ->
                        "Fallback active"

                    else ->
                        "Standby local"
                },
            )
        } else {
            RuntimeStatus.source(
                context,
                "local",
                "Permission required",
            )
        }

        val seed = stableSeed(context)

        val grouped = all.distinctBy { it.id }
            .groupBy { it.priority }
            .toSortedMap()
            .values
            .flatMap { group ->
                val base = sortGroup(
                    group,
                    settings.wallpaperOrder,
                    seed,
                )

                DecisionEngine.rank(
                    context = context,
                    settings = settings,
                    candidates = base,
                    seed = seed,
                )
            }

        RuntimeStatus.set(
            context,
            "candidate_count",
            grouped.size.toString()
        )
        return grouped
    }

    private fun sourceSuccess(
        context: Context,
        source: String,
        ms: Long,
        items: List<Candidate>,
        fallback: Boolean = false,
    ) {
        val count = items.size
        val previousCount = RuntimeStatus.get(
            context,
            "${source}_last_count",
            ""
        ).toIntOrNull()

        val fingerprint = sourceFingerprint(items)
        val previousFingerprint = RuntimeStatus.get(
            context,
            "${source}_index_fingerprint",
            ""
        )

        RuntimeStatus.reset(context, "${source}_fail_streak")
        RuntimeStatus.setLong(context, "${source}_last_latency_ms", ms)
        RuntimeStatus.setLong(context, "${source}_last_ok", System.currentTimeMillis())
        RuntimeStatus.setLong(context, "${source}_index_checked_at", System.currentTimeMillis())
        RuntimeStatus.set(context, "${source}_last_count", count.toString())
        RuntimeStatus.set(context, "${source}_index_fingerprint", fingerprint)

        when {
            previousCount == null -> {
                RuntimeStatus.set(context, "${source}_delta", "baseline")
            }

            previousCount != count -> {
                val delta = count - previousCount
                RuntimeStatus.set(
                    context,
                    "${source}_delta",
                    if (delta > 0) "+$delta" else delta.toString()
                )
                RuntimeStatus.set(
                    context,
                    "${source}_content_change",
                    "count $previousCount → $count"
                )
                log("$source index changed $previousCount -> $count")
            }

            previousFingerprint.isNotBlank() &&
                previousFingerprint != fingerprint -> {
                RuntimeStatus.set(context, "${source}_delta", "content changed")
                RuntimeStatus.set(
                    context,
                    "${source}_content_change",
                    "same count • IDs changed"
                )
                log("$source content changed with same count=$count")
            }

            else -> {
                RuntimeStatus.set(context, "${source}_delta", "unchanged")
            }
        }

        RuntimeStatus.source(
            context,
            source,
            when {
                count == 0 -> "Empty"
                fallback -> "Fallback active"
                ms > 4_500L -> "Slow"
                else -> "Healthy"
            }
        )
    }

    private fun sourceFingerprint(items: List<Candidate>): String {
        if (items.isEmpty()) return "empty"

        val digest = java.security.MessageDigest.getInstance("SHA-256")

        items.asSequence()
            .map { it.id }
            .sorted()
            .forEach { id ->
                digest.update(id.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }

        return digest.digest()
            .take(12)
            .joinToString("") { "%02x".format(it) }
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
        indexCache[key]
            ?.takeIf {
                now - it.at < INDEX_TTL_MS
            }
            ?.let {
                return it.items
            }

        return parseGooglePhotos(
            sharedUrl,
            dataSaver,
        ).also {
            indexCache[key] =
                CachedIndex(
                    now,
                    it,
                )
        }
    }

    private fun googleDriveFolder(folderUrl: String, dataSaver: Boolean): List<Candidate> {
        val key = "drive|$dataSaver|${folderUrl.trim()}"
        val now = System.currentTimeMillis()
        indexCache[key]?.takeIf { now - it.at < INDEX_TTL_MS }?.let { return it.items }
        return parseGoogleDriveFolder(folderUrl, dataSaver).also { indexCache[key] = CachedIndex(now, it) }
    }
    private data class PhotosPageRequest(
        val albumKey: String,
        val authKey: String,
    )

    private data class PhotosBatchPage(
        val items: List<Candidate>,
        val nextPageToken: String?,
    )

    private fun parseGooglePhotos(
        sharedUrl: String,
        dataSaver: Boolean,
    ): List<Candidate> {
        /*
         * Google Photos public shared albums hydrate only the first
         * batch directly in the HTML, typically around 300 items.
         *
         * Remaining items are fetched through PhotosUi's public
         * snAcKc continuation RPC.
         *
         * Keep this index metadata-only. WallpaperController still
         * downloads only the configured cache subset.
         */
        val rawHtml =
            getText(sharedUrl)

        val html =
            rawHtml
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")

        val candidates =
            LinkedHashMap<String, Candidate>()

        val seenImageBases =
            linkedSetOf<String>()

        /*
         * First hydrated page.
         *
         * Keep the existing robust URL extraction because Google
         * occasionally changes surrounding JS object formatting.
         */
        val patterns =
            listOf(
                Regex(
                    """https://lh3\.googleusercontent\.com/(?:pw/)?[A-Za-z0-9_-]{20,}(?:=[A-Za-z0-9_-]+)?"""
                ),
                Regex(
                    """https://lh3\.googleusercontent\.com/[A-Za-z0-9_-]{20,}(?:=[A-Za-z0-9_-]+)?"""
                ),
                Regex(
                    """https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]{20,}"""
                ),
            )

        val firstUrls =
            linkedSetOf<String>()

        patterns.forEach { regex ->
            regex
                .findAll(html)
                .forEach { match ->
                    firstUrls +=
                        match.value
                            .replace("\\/", "/")
                            .substringBefore('=')
                }
        }

        firstUrls
            .forEachIndexed { index, base ->
                if (!seenImageBases.add(base)) {
                    return@forEachIndexed
                }

                val id =
                    base
                        .substringAfterLast('/')
                        .substringBefore('=')

                val url =
                    if (dataSaver) {
                        "$base=w1080-h2400-rw"
                    } else {
                        "$base=s0"
                    }

                candidates[
                    "photos:$id"
                ] =
                    Candidate(
                        id = "photos:$id",
                        priority = 0,
                        source = "Photos",
                        url = url,
                        name =
                            "Photo_${
                                (index + 1)
                                    .toString()
                                    .padStart(4, '0')
                            }",
                    )
            }

        val request =
            extractPhotosPageRequest(
                rawHtml
            )

        var pageToken =
            extractPhotosNextPageToken(
                rawHtml
            )

        /*
         * If Google changes its internal page markup, do not destroy
         * an otherwise healthy first-page Photos source. Return the
         * items already obtained and let Drive remain the fallback.
         */
        if (
            request == null ||
            pageToken.isNullOrBlank()
        ) {
            return candidates.values.toList()
        }

        val seenTokens =
            linkedSetOf<String>()

        var pageNumber = 2

        while (
            pageToken != null &&
            pageNumber <= 100
        ) {
            val token =
                pageToken

            if (!seenTokens.add(token)) {
                log(
                    "Google Photos pagination stopped: " +
                        "repeated token on page $pageNumber"
                )
                break
            }

            val page =
                runCatching {
                    fetchGooglePhotosPage(
                        request = request,
                        pageToken = token,
                        dataSaver = dataSaver,
                        itemOffset =
                            candidates.size,
                    )
                }
                    .onFailure {
                        log(
                            "Google Photos pagination page " +
                                "$pageNumber failed: ${it.message}"
                        )
                    }
                    .getOrNull()
                    ?: break

            page.items
                .forEach { candidate ->
                    val base =
                        candidate.url
                            ?.substringBefore('=')
                            ?: return@forEach

                    if (
                        seenImageBases.add(base)
                    ) {
                        candidates[
                            candidate.id
                        ] = candidate
                    }
                }

            pageToken =
                page.nextPageToken
                    ?.takeIf {
                        it.isNotBlank()
                    }

            pageNumber++
        }

        if (
            pageToken != null &&
            pageNumber > 100
        ) {
            log(
                "Google Photos pagination stopped at " +
                    "100-page safety limit"
            )
        }

        return candidates
            .values
            .toList()
    }

    private fun extractPhotosPageRequest(
        html: String,
    ): PhotosPageRequest? {
        /*
         * Example:
         * id:'snAcKc',
         * request:["albumKey",null,null,"authKey"]
         */
        val regex =
            Regex(
                pattern =
                    """snAcKc[^}]*?request:\s*\[\s*"([A-Za-z0-9_-]+)"\s*,\s*null\s*,\s*null\s*,\s*"([A-Za-z0-9_-]+)"""",
                option =
                    RegexOption.DOT_MATCHES_ALL,
            )

        val match =
            regex.find(html)
                ?: return null

        val albumKey =
            match.groupValues
                .getOrNull(1)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val authKey =
            match.groupValues
                .getOrNull(2)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        return PhotosPageRequest(
            albumKey = albumKey,
            authKey = authKey,
        )
    }

    private fun extractPhotosNextPageToken(
        html: String,
    ): String? {
        /*
         * ds:1 is a deeply nested JavaScript array.
         * Regex cannot safely identify data[2] because media entries
         * themselves contain many nested arrays/objects.
         *
         * Find the ds:1 callback, locate its data array, then read the
         * third TOP-LEVEL element with a small bracket-aware scanner.
         */
        val callback =
            Regex(
                """AF_initDataCallback\(\{key:\s*['"]ds:1['"]"""
            )
                .find(html)
                ?: return null

        val scriptEnd =
            html.indexOf(
                "</script>",
                callback.range.first,
            )

        if (
            scriptEnd <=
            callback.range.first
        ) {
            return null
        }

        val block =
            html.substring(
                callback.range.first,
                scriptEnd,
            )

        val dataMarker =
            Regex(
                """\bdata\s*:"""
            )
                .find(block)
                ?: return null

        val arrayStart =
            block.indexOf(
                '[',
                dataMarker.range.last + 1,
            )

        if (arrayStart < 0) {
            return null
        }

        val tokenLiteral =
            extractTopLevelJsArrayElement(
                source = block,
                arrayStart = arrayStart,
                elementIndex = 2,
            )
                ?.trim()
                ?: return null

        if (
            tokenLiteral == "null" ||
            tokenLiteral.length < 2 ||
            tokenLiteral.first() != '"' ||
            tokenLiteral.last() != '"'
        ) {
            return null
        }

        return tokenLiteral
            .substring(
                1,
                tokenLiteral.length - 1,
            )
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun extractTopLevelJsArrayElement(
        source: String,
        arrayStart: Int,
        elementIndex: Int,
    ): String? {
        if (
            arrayStart !in
            source.indices ||
            source[arrayStart] != '['
        ) {
            return null
        }

        var squareDepth = 1
        var curlyDepth = 0
        var parenDepth = 0

        var inString = false
        var quote = '\u0000'
        var escaped = false

        var currentElement = 0
        var elementStart =
            arrayStart + 1

        var i =
            arrayStart + 1

        while (
            i < source.length
        ) {
            val c =
                source[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (c) {
                        '\\' ->
                            escaped = true

                        quote -> {
                            inString = false
                            quote = '\u0000'
                        }
                    }
                }

                i++
                continue
            }

            when (c) {
                '"',
                '\'' -> {
                    inString = true
                    quote = c
                }

                '[' ->
                    squareDepth++

                ']' -> {
                    if (
                        squareDepth == 1 &&
                        curlyDepth == 0 &&
                        parenDepth == 0
                    ) {
                        if (
                            currentElement ==
                            elementIndex
                        ) {
                            return source.substring(
                                elementStart,
                                i,
                            )
                        }

                        return null
                    }

                    squareDepth--
                }

                '{' ->
                    curlyDepth++

                '}' ->
                    curlyDepth--

                '(' ->
                    parenDepth++

                ')' ->
                    parenDepth--

                ',' -> {
                    if (
                        squareDepth == 1 &&
                        curlyDepth == 0 &&
                        parenDepth == 0
                    ) {
                        if (
                            currentElement ==
                            elementIndex
                        ) {
                            return source.substring(
                                elementStart,
                                i,
                            )
                        }

                        currentElement++
                        elementStart =
                            i + 1
                    }
                }
            }

            i++
        }

        return null
    }

    private fun fetchGooglePhotosPage(
        request: PhotosPageRequest,
        pageToken: String,
        dataSaver: Boolean,
        itemOffset: Int,
    ): PhotosBatchPage {
        val innerRequest =
            JSONArray()
                .put(request.albumKey)
                .put(pageToken)
                .put(org.json.JSONObject.NULL)
                .put(request.authKey)
                .toString()

        val envelope =
            JSONArray()
                .put(
                    JSONArray()
                        .put(
                            JSONArray()
                                .put("snAcKc")
                                .put(innerRequest)
                                .put(org.json.JSONObject.NULL)
                                .put("generic")
                        )
                )
                .toString()

        val endpoint =
            "https://photos.google.com/u/0/_/PhotosUi/data/" +
                "batchexecute" +
                "?rpcids=snAcKc" +
                "&source-path=" +
                URLEncoder.encode(
                    "/share/${request.albumKey}",
                    "UTF-8",
                )

        val formBody =
            "f.req=" +
                URLEncoder.encode(
                    envelope,
                    "UTF-8",
                )

        val response =
            postGooglePhotosBatch(
                url = endpoint,
                body = formBody,
                referer =
                    "https://photos.google.com/share/" +
                        request.albumKey +
                        "?key=" +
                        request.authKey,
            )

        return parseGooglePhotosBatchPage(
            body = response,
            dataSaver = dataSaver,
            itemOffset = itemOffset,
        )
    }

    private fun postGooglePhotosBatch(
        url: String,
        body: String,
        referer: String,
    ): String =
        retry(4) {
            val connection =
                (
                    URL(url)
                        .openConnection()
                    as HttpURLConnection
                )
                    .apply {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        doOutput = true

                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 16) " +
                                "AppleWebKit/537.36 " +
                                "Chrome/140 Mobile Safari/537.36",
                        )

                        setRequestProperty(
                            "Accept-Language",
                            "en-US,en;q=0.9",
                        )

                        setRequestProperty(
                            "Content-Type",
                            "application/x-www-form-urlencoded;charset=UTF-8",
                        )

                        setRequestProperty(
                            "Origin",
                            "https://photos.google.com",
                        )

                        setRequestProperty(
                            "Referer",
                            referer,
                        )
                    }

            try {
                connection
                    .outputStream
                    .bufferedWriter(
                        Charsets.UTF_8
                    )
                    .use {
                        it.write(body)
                    }

                val code =
                    connection.responseCode

                if (
                    code !in 200..299
                ) {
                    error(
                        "Google Photos batch HTTP $code"
                    )
                }

                connection
                    .inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }
            } finally {
                connection.disconnect()
            }
        }

    private fun parseGooglePhotosBatchPage(
        body: String,
        dataSaver: Boolean,
        itemOffset: Int,
    ): PhotosBatchPage {
        var text =
            body.trimStart()

        if (
            text.startsWith(
                ")]}'"
            )
        ) {
            text =
                text
                    .removePrefix(
                        ")]}'"
                    )
                    .trimStart()
        }

        val responseLine =
            text
                .lineSequence()
                .firstOrNull {
                    it.trimStart()
                        .startsWith("[[")
                }
                ?: error(
                    "Google Photos batch payload missing"
                )

        val outer =
            JSONArray(
                responseLine
            )

        var payload: String? =
            null

        for (
            i in 0 until outer.length()
        ) {
            val entry =
                outer.optJSONArray(i)
                    ?: continue

            if (
                entry.optString(0) ==
                    "wrb.fr" &&
                entry.optString(1) ==
                    "snAcKc"
            ) {
                payload =
                    entry.optString(
                        2,
                        null,
                    )
                break
            }
        }

        val rawPayload =
            payload
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: error(
                    "Google Photos snAcKc payload missing"
                )

        val data =
            JSONArray(
                rawPayload
            )

        val rawItems =
            data.optJSONArray(1)
                ?: error(
                    "Google Photos media page missing"
                )

        val result =
            mutableListOf<Candidate>()

        for (
            i in 0 until rawItems.length()
        ) {
            val entry =
                rawItems.optJSONArray(i)
                    ?: continue

            if (
                entry.length() < 6
            ) {
                continue
            }

            /*
             * Google Photos video metadata key.
             * BB-PixWall is an image wallpaper engine, so do not add
             * video posters as normal wallpaper candidates.
             */
            val meta =
                entry.optJSONObject(9)

            if (
                meta?.has(
                    "76647426"
                ) == true
            ) {
                continue
            }

            val uid =
                entry.optString(
                    0,
                    "",
                )

            val detail =
                entry.optJSONArray(1)
                    ?: continue

            val base =
                detail
                    .optString(
                        0,
                        "",
                    )
                    .substringBefore('=')

            if (
                uid.isBlank() ||
                base.isBlank() ||
                !base.startsWith(
                    "https://lh3.googleusercontent.com/"
                )
            ) {
                continue
            }

            val width =
                detail
                    .optInt(
                        1,
                        0,
                    )
                    .coerceAtLeast(0)

            val height =
                detail
                    .optInt(
                        2,
                        0,
                    )
                    .coerceAtLeast(0)

            val modified =
                entry
                    .optLong(
                        5,
                        0L,
                    )
                    .coerceAtLeast(0L)

            val finalUrl =
                if (dataSaver) {
                    "$base=w1080-h2400-rw"
                } else {
                    "$base=s0"
                }

            result +=
                Candidate(
                    id =
                        "photos:$uid",
                    priority = 0,
                    source = "Photos",
                    url = finalUrl,
                    name =
                        "Photo_${
                            (
                                itemOffset +
                                    result.size +
                                    1
                            )
                                .toString()
                                .padStart(
                                    4,
                                    '0',
                                )
                        }",
                    modified = modified,
                    width = width,
                    height = height,
                )
        }

        val nextToken =
            if (
                data.length() > 2 &&
                !data.isNull(2)
            ) {
                data
                    .optString(
                        2,
                        "",
                    )
                    .takeIf {
                        it.isNotBlank()
                    }
            } else {
                null
            }

        return PhotosBatchPage(
            items = result,
            nextPageToken = nextToken,
        )
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
