package bb.pix.wall.discovery.model

data class WallpaperCategory(
    val id: String,
    val title: String,
    val groupId: String,
    val searchTerms: List<String>,
    val aliases: List<String> = emptyList(),
    val preferredColors: List<String> = emptyList(),
    val premiumWeight: Float = 1.0f,
    val custom: Boolean = false,
)
