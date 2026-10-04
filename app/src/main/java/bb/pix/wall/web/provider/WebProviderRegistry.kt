package bb.pix.wall.web.provider

object WebProviderRegistry {
    private val providers =
        linkedMapOf<String, WebWallpaperProvider>()

    init {
        register(
            WallhavenProvider
        )
    }

    @Synchronized
    fun register(
        provider: WebWallpaperProvider,
    ) {
        providers[
            provider.id
        ] = provider
    }

    @Synchronized
    fun unregister(
        providerId: String,
    ) {
        providers.remove(
            providerId
        )
    }

    @Synchronized
    fun all():
        List<WebWallpaperProvider> =
        providers.values.toList()

    @Synchronized
    fun find(
        providerId: String,
    ): WebWallpaperProvider? =
        providers[
            providerId
        ]
}
