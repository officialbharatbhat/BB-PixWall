package bb.pix.wall.web.provider

import bb.pix.wall.web.model.DisplayProfile
import bb.pix.wall.web.model.WebCategory
import bb.pix.wall.web.model.WebQualityMode
import bb.pix.wall.web.model.WebWallpaperCandidate
import bb.pix.wall.web.runtime.WebCandidateSelector
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object WallhavenProvider : WebWallpaperProvider {
    override val id: String =
        "wallhaven"

    override val displayName: String =
        "Wallhaven"

    override fun search(
        category: WebCategory?,
        customQuery: String?,
        display: DisplayProfile,
        qualityMode: WebQualityMode,
        limit: Int,
    ): List<WebWallpaperCandidate> {
        val wanted =
            limit.coerceIn(
                1,
                100,
            )

        /*
         * Wallhaven's public API search index is not reliable for
         * arbitrary free-text queries. Keep this provider focused on
         * broad SFW wallpaper discovery and let app-side ranking,
         * category learning and future providers handle semantic
         * custom search.
         */
        val query =
            customQuery
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: category?.query
                ?: "discovery"

        val collected =
            linkedMapOf<String, WebWallpaperCandidate>()

        /*
         * Stop based on candidates that actually survive the
         * device/quality selector, not merely raw API result count.
         *
         * Maximum mode can reject many otherwise-large wallpapers
         * because their aspect would require destructive cropping.
         */
        val maxPages =
            when (qualityMode) {
                WebQualityMode.MAXIMUM -> 8
                WebQualityMode.BALANCED -> 6
                WebQualityMode.DATA_SAVER -> 4
            }

        var failedPages = 0

        for (page in 1..maxPages) {
            val json =
                try {
                    requestPage(
                        query = query,
                        display = display,
                        page = page,
                    )
                } catch (t: Throwable) {
                    failedPages++

                    /*
                     * Preserve useful results from earlier pages.
                     * A late-page timeout must not turn a successful
                     * discovery pass into an empty provider failure.
                     */
                    if (
                        collected.isEmpty() &&
                        failedPages >= 2
                    ) {
                        throw t
                    }

                    if (failedPages >= 2) {
                        break
                    }

                    continue
                }

            /*
             * A successful request clears the consecutive-failure
             * pressure and allows discovery to continue.
             */
            failedPages = 0

            val data =
                json.optJSONArray(
                    "data"
                ) ?: break

            if (data.length() == 0) {
                break
            }

            for (index in 0 until data.length()) {
                val item =
                    data.optJSONObject(index)
                        ?: continue

                val candidate =
                    parseCandidate(
                        item = item,
                        category = category,
                        query = query,
                    ) ?: continue

                collected[
                    candidate.id
                ] = candidate
            }

            val rankedCount =
                WebCandidateSelector
                    .rank(
                        candidates =
                            collected.values.toList(),
                        display = display,
                        qualityMode = qualityMode,
                    )
                    .size

            if (rankedCount >= wanted) {
                break
            }

            val meta =
                json.optJSONObject(
                    "meta"
                )

            val lastPage =
                meta?.optInt(
                    "last_page",
                    page,
                ) ?: page

            if (page >= lastPage) {
                break
            }
        }

        return WebCandidateSelector
            .rank(
                candidates =
                    collected.values.toList(),
                display = display,
                qualityMode = qualityMode,
            )
            .take(wanted)
    }

    private fun requestPage(
        query: String,
        display: DisplayProfile,
        page: Int,
    ): JSONObject {
        val minimum =
            "${display.portraitWidth}" +
                "x${display.portraitHeight}"

        val rawUrl =
            "https://wallhaven.cc/api/v1/search" +
                "?categories=111" +
                "&purity=100" +
                "&sorting=toplist" +
                "&order=desc" +
                "&atleast=$minimum" +
                "&page=$page"

        val text =
            getText(rawUrl)

        return JSONObject(text)
    }

    private fun parseCandidate(
        item: JSONObject,
        category: WebCategory?,
        query: String,
    ): WebWallpaperCandidate? {
        val id =
            item.optString(
                "id"
            ).trim()

        val path =
            item.optString(
                "path"
            ).trim()

        if (
            id.isBlank() ||
            path.isBlank()
        ) {
            return null
        }

        val purity =
            item.optString(
                "purity",
                "sfw",
            )

        /*
         * Defense in depth. The request already asks for SFW only,
         * but reject anything else if upstream ever ignores it.
         */
        if (
            !purity.equals(
                "sfw",
                ignoreCase = true,
            )
        ) {
            return null
        }

        val width =
            item.optInt(
                "dimension_x",
                0,
            ).takeIf {
                it > 0
            }

        val height =
            item.optInt(
                "dimension_y",
                0,
            ).takeIf {
                it > 0
            }

        val size =
            item.optLong(
                "file_size",
                -1L,
            ).takeIf {
                it > 0L
            }

        val mime =
            item.optString(
                "file_type"
            ).takeIf {
                it.isNotBlank()
            }

        val pageUrl =
            item.optString(
                "url"
            ).takeIf {
                it.isNotBlank()
            }

        val thumbs =
            item.optJSONObject(
                "thumbs"
            )

        val preview =
            thumbs
                ?.optString(
                    "large"
                )
                ?.takeIf {
                    it.isNotBlank()
                }

        return WebWallpaperCandidate(
            id = "wallhaven:$id",
            providerId = this.id,
            sourceUrl = path,
            previewUrl = preview,
            width = width,
            height = height,
            fileSizeBytes = size,
            mimeType = mime,
            category = category,
            query = query,
            sourcePageUrl = pageUrl,
            attribution =
                "Wallhaven • $id",
        )
    }

    private fun getText(
        rawUrl: String,
    ): String {
        var connection:
            HttpURLConnection? = null

        try {
            connection =
                (
                    URL(rawUrl)
                        .openConnection()
                    as HttpURLConnection
                ).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true

                    setRequestProperty(
                        "User-Agent",
                        "BB-PixWall/1.1 Android"
                    )

                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                }

            val code =
                connection.responseCode

            if (
                code !in 200..299
            ) {
                error(
                    "Wallhaven HTTP $code"
                )
            }

            return BufferedReader(
                InputStreamReader(
                    connection.inputStream,
                    Charsets.UTF_8,
                )
            ).use {
                it.readText()
            }
        } finally {
            connection?.disconnect()
        }
    }
}
