package bb.pix.wall.web.runtime

import bb.pix.wall.engine.WallpaperSourceEngine
import bb.pix.wall.web.model.WebWallpaperCandidate

object WebCandidateBridge {
    fun toEngineCandidate(
        candidate: WebWallpaperCandidate,
        priority: Int = 3,
    ): WallpaperSourceEngine.Candidate {
        return WallpaperSourceEngine.Candidate(
            id = candidate.id,
            priority = priority,
            source =
                "Web:${candidate.providerId}",
            url =
                candidate.sourceUrl,
            name =
                candidate.id.substringAfter(':'),
            size =
                candidate.fileSizeBytes
                    ?: -1L,
            width =
                candidate.width
                    ?: 0,
            height =
                candidate.height
                    ?: 0,
        )
    }

    fun toEngineCandidates(
        candidates:
            List<WebWallpaperCandidate>,
        priority: Int = 3,
    ): List<WallpaperSourceEngine.Candidate> {
        return candidates.map {
            toEngineCandidate(
                candidate = it,
                priority = priority,
            )
        }
    }
}
