package bb.pix.wall.web.model

enum class WebCategory(
    val query: String,
) {
    AMOLED("amoled wallpaper"),
    DEEP_BLACK("deep black wallpaper"),
    COLORFUL("colorful wallpaper"),
    NATURE("nature wallpaper"),
    SPACE("space wallpaper"),
    EARTH("earth wallpaper"),
    SKY("sky wallpaper"),
    FLOWERS("flowers wallpaper"),
    CARS("cars wallpaper"),
    ARCHITECTURE("architecture wallpaper"),
    AI_ART("ai art wallpaper"),
    QUOTES("quotes wallpaper"),
    TYPOGRAPHY("typography wallpaper"),
    PEOPLE("people wallpaper"),
}

enum class WebQualityMode {
    MAXIMUM,
    BALANCED,
    DATA_SAVER,
}

enum class WebSourceMode {
    WEB_ONLY,
    SMART_MIX,
}

data class WebWallpaperCandidate(
    val id: String,
    val providerId: String,
    val sourceUrl: String,
    val previewUrl: String?,
    val width: Int?,
    val height: Int?,
    val fileSizeBytes: Long?,
    val mimeType: String?,
    val category: WebCategory?,
    val query: String?,
    val sourcePageUrl: String?,
    val attribution: String?,
) {
    val aspectRatio: Double?
        get() =
            if (
                width != null &&
                height != null &&
                width > 0 &&
                height > 0
            ) {
                width.toDouble() / height.toDouble()
            } else {
                null
            }
}

data class DisplayProfile(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
) {
    val portraitWidth: Int
        get() = minOf(widthPx, heightPx)

    val portraitHeight: Int
        get() = maxOf(widthPx, heightPx)

    val aspectRatio: Double
        get() =
            portraitWidth.toDouble() /
                portraitHeight.toDouble()
}

enum class NetworkClass {
    WIFI,
    FIVE_G,
    FOUR_G,
    SLOW,
    OFFLINE,
}

data class NetworkPrefetchPolicy(
    val networkClass: NetworkClass,
    val concurrency: Int,
    val desiredCacheTarget: Int,
    val preserveMaximumQuality: Boolean,
)
