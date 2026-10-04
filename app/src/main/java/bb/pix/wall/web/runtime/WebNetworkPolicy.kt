package bb.pix.wall.web.runtime

import bb.pix.wall.web.model.NetworkClass
import bb.pix.wall.web.model.NetworkPrefetchPolicy
import bb.pix.wall.web.model.WebQualityMode

object WebNetworkPolicy {
    fun resolve(
        networkClass: NetworkClass,
        qualityMode: WebQualityMode,
    ): NetworkPrefetchPolicy {
        val preserveMaximumQuality =
            qualityMode == WebQualityMode.MAXIMUM

        return when (networkClass) {
            NetworkClass.WIFI ->
                NetworkPrefetchPolicy(
                    networkClass = networkClass,
                    concurrency = 4,
                    desiredCacheTarget = 30,
                    preserveMaximumQuality =
                        preserveMaximumQuality,
                )

            NetworkClass.FIVE_G ->
                NetworkPrefetchPolicy(
                    networkClass = networkClass,
                    concurrency = 4,
                    desiredCacheTarget = 30,
                    preserveMaximumQuality =
                        preserveMaximumQuality,
                )

            NetworkClass.FOUR_G ->
                NetworkPrefetchPolicy(
                    networkClass = networkClass,
                    concurrency = 2,
                    desiredCacheTarget = 20,
                    preserveMaximumQuality =
                        preserveMaximumQuality,
                )

            NetworkClass.SLOW ->
                NetworkPrefetchPolicy(
                    networkClass = networkClass,
                    concurrency = 1,
                    desiredCacheTarget = 12,
                    preserveMaximumQuality =
                        preserveMaximumQuality,
                )

            NetworkClass.OFFLINE ->
                NetworkPrefetchPolicy(
                    networkClass = networkClass,
                    concurrency = 0,
                    desiredCacheTarget = 0,
                    preserveMaximumQuality =
                        preserveMaximumQuality,
                )
        }
    }
}
