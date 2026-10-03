package bb.pix.wall.data.source

import bb.pix.wall.domain.model.WallpaperTarget

data class WallpaperCandidate(
    val stableId: String,
    val sourceName: String,
    val remoteUrl: String? = null,
    val localPath: String? = null,
)

interface WallpaperSource {
    val displayName: String
    suspend fun list(target: WallpaperTarget): Result<List<WallpaperCandidate>>
}
