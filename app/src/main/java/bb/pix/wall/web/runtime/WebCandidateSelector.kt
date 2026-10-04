package bb.pix.wall.web.runtime

import bb.pix.wall.web.model.DisplayProfile
import bb.pix.wall.web.model.WebQualityMode
import bb.pix.wall.web.model.WebWallpaperCandidate
import kotlin.math.abs

object WebCandidateSelector {
    fun rank(
        candidates: List<WebWallpaperCandidate>,
        display: DisplayProfile,
        qualityMode: WebQualityMode,
    ): List<WebWallpaperCandidate> {
        return candidates
            .asSequence()
            .filter {
                isUsable(
                    candidate = it,
                    display = display,
                    qualityMode = qualityMode,
                )
            }
            .sortedByDescending {
                score(
                    candidate = it,
                    display = display,
                    qualityMode = qualityMode,
                )
            }
            .toList()
    }

    private fun isUsable(
        candidate: WebWallpaperCandidate,
        display: DisplayProfile,
        qualityMode: WebQualityMode,
    ): Boolean {
        val width =
            candidate.width
                ?: return qualityMode !=
                    WebQualityMode.MAXIMUM

        val height =
            candidate.height
                ?: return qualityMode !=
                    WebQualityMode.MAXIMUM

        if (
            width <= 0 ||
            height <= 0
        ) {
            return false
        }

        /*
         * BB-PixWall currently targets portrait phone displays.
         * A giant landscape image may satisfy raw pixel minimums,
         * but would require destructive cropping. Reject it before
         * ranking instead of pretending resolution alone is quality.
         */
        if (height < width) {
            return false
        }

        val portraitWidth =
            minOf(width, height)

        val portraitHeight =
            maxOf(width, height)

        val candidateAspect =
            portraitWidth.toDouble() /
                portraitHeight.toDouble()

        val displayAspect =
            display.aspectRatio

        /*
         * Estimate how much of the original image survives a
         * center-crop to the detected screen aspect.
         *
         * 1.00 = exact aspect match
         * 0.90 = roughly 90% retained
         *
         * Maximum mode is intentionally strict so high pixel count
         * cannot hide destructive cropping.
         */
        val cropRetention =
            minOf(
                candidateAspect /
                    displayAspect,
                displayAspect /
                    candidateAspect,
            ).coerceIn(
                0.0,
                1.0,
            )

        val aspectAccepted =
            when (qualityMode) {
                WebQualityMode.MAXIMUM ->
                    cropRetention >= 0.90

                WebQualityMode.BALANCED ->
                    cropRetention >= 0.82

                WebQualityMode.DATA_SAVER ->
                    cropRetention >= 0.72
            }

        if (!aspectAccepted) {
            return false
        }

        return when (qualityMode) {
            WebQualityMode.MAXIMUM ->
                portraitWidth >= display.portraitWidth &&
                    portraitHeight >= display.portraitHeight

            WebQualityMode.BALANCED ->
                portraitWidth >=
                    (display.portraitWidth * 0.75).toInt() &&
                    portraitHeight >=
                    (display.portraitHeight * 0.75).toInt()

            WebQualityMode.DATA_SAVER ->
                portraitWidth >=
                    (display.portraitWidth * 0.50).toInt() &&
                    portraitHeight >=
                    (display.portraitHeight * 0.50).toInt()
        }
    }

    private fun score(
        candidate: WebWallpaperCandidate,
        display: DisplayProfile,
        qualityMode: WebQualityMode,
    ): Double {
        val width =
            candidate.width
                ?: return -1000.0

        val height =
            candidate.height
                ?: return -1000.0

        val portraitWidth =
            minOf(width, height)

        val portraitHeight =
            maxOf(width, height)

        val candidateAspect =
            portraitWidth.toDouble() /
                portraitHeight.toDouble()

        val aspectDistance =
            abs(
                candidateAspect -
                    display.aspectRatio
            )

        val cropRetention =
            minOf(
                candidateAspect /
                    display.aspectRatio,
                display.aspectRatio /
                    candidateAspect,
            ).coerceIn(
                0.0,
                1.0,
            )

        val resolutionRatio =
            minOf(
                portraitWidth.toDouble() /
                    display.portraitWidth.toDouble(),
                portraitHeight.toDouble() /
                    display.portraitHeight.toDouble(),
            )

        val aspectScore =
            when {
                aspectDistance <= 0.01 -> 60.0
                aspectDistance <= 0.03 -> 45.0
                aspectDistance <= 0.06 -> 25.0
                aspectDistance <= 0.10 -> 10.0
                else -> -20.0
            }

        val resolutionScore =
            when {
                resolutionRatio >= 2.0 -> 30.0
                resolutionRatio >= 1.5 -> 24.0
                resolutionRatio >= 1.0 -> 18.0
                resolutionRatio >= 0.75 -> 8.0
                else -> -15.0
            }

        val orientationScore =
            if (height >= width) {
                15.0
            } else {
                -30.0
            }

        val qualityBias =
            when (qualityMode) {
                WebQualityMode.MAXIMUM -> 15.0
                WebQualityMode.BALANCED -> 5.0
                WebQualityMode.DATA_SAVER -> 0.0
            }

        val retentionScore =
            cropRetention * 35.0

        return aspectScore +
            resolutionScore +
            orientationScore +
            qualityBias +
            retentionScore
    }
}
