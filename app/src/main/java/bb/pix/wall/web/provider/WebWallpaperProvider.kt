package bb.pix.wall.web.provider

import bb.pix.wall.web.model.DisplayProfile
import bb.pix.wall.web.model.WebCategory
import bb.pix.wall.web.model.WebQualityMode
import bb.pix.wall.web.model.WebWallpaperCandidate

interface WebWallpaperProvider {
    val id: String
    val displayName: String

    fun search(
        category: WebCategory? = null,
        customQuery: String? = null,
        display: DisplayProfile,
        qualityMode: WebQualityMode,
        limit: Int,
    ): List<WebWallpaperCandidate>
}
