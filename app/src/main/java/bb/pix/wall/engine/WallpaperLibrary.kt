package bb.pix.wall.engine

import bb.pix.wall.settings.WallpaperTargetMode
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Phase 1 persistent wallpaper library.
 *
 * Keeps:
 * - multi-entry history snapshots
 * - favorite/pinned originals
 * - permanent "never show again" candidate IDs
 *
 * No database required; everything remains local under /sdcard/wallpaper.
 */
object WallpaperLibrary {

    private const val HISTORY_LIMIT = 10

    data class HistoryEntry(
        val id: String,
        val createdAt: Long,
        val home: File?,
        val lock: File?,
    )

    fun captureCurrent(
        targetMode: WallpaperTargetMode,
    ) {
        WallpaperFiles.ensure()

        val sources =
            when (targetMode) {
                WallpaperTargetMode.HOME ->
                    listOf("home" to WallpaperFiles.currentHome)

                WallpaperTargetMode.LOCK ->
                    listOf("lock" to WallpaperFiles.currentLock)

                WallpaperTargetMode.BOTH_SAME,
                WallpaperTargetMode.BOTH_DIFFERENT ->
                    listOf(
                        "home" to WallpaperFiles.currentHome,
                        "lock" to WallpaperFiles.currentLock,
                    )
            }

        if (sources.none { it.second.exists() }) return

        val stamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US,
            ).format(Date())

        val dir =
            File(
                WallpaperFiles.history,
                stamp,
            )

        if (!dir.mkdirs() && !dir.exists()) return

        sources.forEach { (name, src) ->
            if (!src.exists()) return@forEach

            val ext =
                src.extension
                    .ifBlank { "jpg" }

            val dest =
                File(
                    dir,
                    "${name}.$ext",
                )

            runCatching {
                src.copyTo(
                    dest,
                    overwrite = true,
                )

                copyMeta(
                    src,
                    dest,
                )
            }
        }

        File(
            dir,
            "history.properties",
        ).outputStream().use { output ->
            Properties().apply {
                setProperty(
                    "created_at",
                    System.currentTimeMillis()
                        .toString(),
                )
                setProperty(
                    "target_mode",
                    targetMode.name,
                )
            }.store(
                output,
                "BB-PixWall history",
            )
        }

        trimHistory()
    }

    fun history(): List<HistoryEntry> {
        WallpaperFiles.ensure()
        // Enforce the new on-disk limit even for installs upgrading from 40 entries.
        trimHistory()

        return WallpaperFiles.history
            .listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { dir ->
                val props =
                    runCatching {
                        Properties().apply {
                            File(
                                dir,
                                "history.properties",
                            ).takeIf(File::exists)
                                ?.inputStream()
                                ?.use(::load)
                        }
                    }.getOrDefault(
                        Properties()
                    )

                val home =
                    dir.listFiles()
                        .orEmpty()
                        .firstOrNull {
                            it.isFile &&
                                it.nameWithoutExtension ==
                                "home"
                        }

                val lock =
                    dir.listFiles()
                        .orEmpty()
                        .firstOrNull {
                            it.isFile &&
                                it.nameWithoutExtension ==
                                "lock"
                        }

                if (home == null && lock == null) {
                    null
                } else {
                    HistoryEntry(
                        id = dir.name,
                        createdAt =
                            props.getProperty(
                                "created_at",
                            )?.toLongOrNull()
                                ?: dir.lastModified(),
                        home = home,
                        lock = lock,
                    )
                }
            }
            .sortedByDescending {
                it.createdAt
            }
    }

    fun favorite(
        src: File,
        pinned: Boolean = false,
    ): File? {
        if (!src.exists()) return null

        WallpaperFiles.ensure()

        val candidateId =
            candidateId(src)
                ?: sha256(src)

        val token =
            token(candidateId)

        val ext =
            src.extension
                .ifBlank { "jpg" }

        val dest =
            File(
                WallpaperFiles.favorites,
                "$token.$ext",
            )

        return runCatching {
            if (!dest.exists()) {
                src.copyTo(
                    dest,
                    overwrite = false,
                )

                copyMeta(
                    src,
                    dest,
                )
            }

            val props =
                Properties().apply {
                    setProperty(
                        "candidate_id",
                        candidateId,
                    )
                    setProperty(
                        "favorite_at",
                        System.currentTimeMillis()
                            .toString(),
                    )
                    setProperty(
                        "pinned",
                        pinned.toString(),
                    )
                }

            File(
                dest.absolutePath +
                    ".library",
            ).outputStream().use {
                props.store(
                    it,
                    "BB-PixWall favorite",
                )
            }

            dest
        }.getOrNull()
    }

    fun setPinned(
        src: File,
        pinned: Boolean,
    ): Boolean {
        val favorite =
            favorite(
                src,
                pinned,
            ) ?: return false

        val sidecar =
            File(
                favorite.absolutePath +
                    ".library",
            )

        val props =
            runCatching {
                Properties().apply {
                    if (sidecar.exists()) {
                        sidecar.inputStream()
                            .use(::load)
                    }
                }
            }.getOrDefault(
                Properties()
            )

        props.setProperty(
            "pinned",
            pinned.toString(),
        )

        return runCatching {
            sidecar.outputStream().use {
                props.store(
                    it,
                    "BB-PixWall favorite",
                )
            }

            true
        }.getOrDefault(false)
    }

    fun isFavorite(
        src: File,
    ): Boolean =
        favoriteFiles(src)
            .isNotEmpty()

    fun isPinned(
        src: File,
    ): Boolean {
        val favorite =
            favoriteFiles(src)
                .firstOrNull()
                ?: return false

        val sidecar =
            File(
                favorite.absolutePath +
                    ".library"
            )

        if (!sidecar.exists()) {
            return false
        }

        return runCatching {
            Properties().apply {
                sidecar.inputStream()
                    .use(::load)
            }.getProperty(
                "pinned",
                "false",
            ).toBoolean()
        }.getOrDefault(false)
    }

    fun removeFavorite(
        src: File,
    ): Boolean {
        val files =
            favoriteFiles(src)

        if (files.isEmpty()) {
            return false
        }

        var changed = false

        files.forEach { favorite ->
            changed =
                favorite.delete() ||
                    changed

            File(
                favorite.absolutePath +
                    ".meta"
            ).delete()

            File(
                favorite.absolutePath +
                    ".library"
            ).delete()
        }

        return changed
    }

    fun saveOriginal(
        src: File,
        label: String,
    ): File? {
        if (!src.exists()) return null

        WallpaperFiles.ensure()

        val stamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US,
            ).format(Date())

        val cleanLabel =
            label.lowercase(Locale.US)
                .replace(
                    Regex("[^a-z0-9]+"),
                    "_",
                )
                .trim('_')
                .ifBlank {
                    "wallpaper"
                }

        val ext =
            src.extension
                .ifBlank {
                    "jpg"
                }

        var dest =
            File(
                WallpaperFiles.saved,
                "BBPixWall_${stamp}_${cleanLabel}.$ext",
            )

        var index = 1

        while (dest.exists()) {
            dest =
                File(
                    WallpaperFiles.saved,
                    "BBPixWall_${stamp}_${cleanLabel}_$index.$ext",
                )

            index++
        }

        return runCatching {
            src.copyTo(
                dest,
                overwrite = false,
            )

            copyMeta(
                src,
                dest,
            )

            dest
        }.getOrNull()
    }

    private fun favoriteFiles(
        src: File,
    ): List<File> {
        if (!src.exists()) {
            return emptyList()
        }

        WallpaperFiles.ensure()

        val candidateId =
            candidateId(src)
                ?: runCatching {
                    sha256(src)
                }.getOrNull()
                ?: return emptyList()

        val prefix =
            token(candidateId)

        return WallpaperFiles.favorites
            .listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.nameWithoutExtension ==
                        prefix &&
                    !file.name.endsWith(
                        ".library"
                    ) &&
                    !file.name.endsWith(
                        ".meta"
                    )
            }
    }

    fun neverShowAgain(
        src: File,
    ): Boolean {
        if (!src.exists()) {
            return false
        }

        val idChanged =
            candidateId(src)
                ?.let {
                    blockCandidate(it)
                }
                ?: false

        val hashChanged =
            runCatching {
                blockHash(
                    sha256(src)
                )
            }.getOrDefault(false)

        return idChanged ||
            hashChanged
    }

    @Synchronized
    fun blockCandidate(
        candidateId: String,
    ): Boolean {
        if (candidateId.isBlank()) return false

        WallpaperFiles.ensure()

        val ids =
            blockedIds()
                .toMutableSet()

        val changed =
            ids.add(
                candidateId.trim(),
            )

        if (changed) {
            WallpaperFiles.blockedIds
                .writeText(
                    ids.joinToString(
                        separator = "\n",
                        postfix = "\n",
                    )
                )
        }

        return changed
    }

    fun isBlocked(
        candidateId: String,
    ): Boolean =
        candidateId.isNotBlank() &&
            candidateId in blockedIds()

    fun blockedIds(): Set<String> =
        runCatching {
            if (!WallpaperFiles.blockedIds.exists()) {
                emptySet()
            } else {
                WallpaperFiles.blockedIds
                    .readLines()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
            }
        }.getOrDefault(
            emptySet()
        )


    @Synchronized
    fun blockHash(
        hash: String,
    ): Boolean {
        val clean =
            hash.trim()
                .lowercase()

        if (clean.length != 64) {
            return false
        }

        WallpaperFiles.ensure()

        val hashes =
            blockedHashes()
                .toMutableSet()

        val changed =
            hashes.add(clean)

        if (changed) {
            WallpaperFiles
                .blockedHashes
                .writeText(
                    hashes.joinToString(
                        separator = "\n",
                        postfix = "\n",
                    )
                )
        }

        return changed
    }

    fun isBlockedHash(
        hash: String,
    ): Boolean =
        hash.trim()
            .lowercase() in
            blockedHashes()

    fun blockedHashes(): Set<String> =
        runCatching {
            if (
                !WallpaperFiles
                    .blockedHashes
                    .exists()
            ) {
                emptySet()
            } else {
                WallpaperFiles
                    .blockedHashes
                    .readLines()
                    .map {
                        it.trim()
                            .lowercase()
                    }
                    .filter {
                        it.length == 64
                    }
                    .toSet()
            }
        }.getOrDefault(
            emptySet()
        )

    fun isBlockedFile(
        file: File,
    ): Boolean {
        if (!file.exists()) {
            return false
        }

        val id =
            candidateId(file)

        if (
            id != null &&
            isBlocked(id)
        ) {
            return true
        }

        return runCatching {
            isBlockedHash(
                sha256(file)
            )
        }.getOrDefault(false)
    }

    private fun trimHistory() {
        val entries =
            WallpaperFiles.history
                .listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .sortedByDescending(
                    File::lastModified,
                )

        entries.drop(
            HISTORY_LIMIT
        ).forEach { dir ->
            runCatching {
                dir.deleteRecursively()
            }
        }
    }

    private fun candidateId(
        file: File,
    ): String? {
        val meta =
            File(
                file.absolutePath +
                    ".meta",
            )

        if (!meta.exists()) return null

        return runCatching {
            Properties().apply {
                meta.inputStream()
                    .use(::load)
            }.getProperty("id")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    private fun copyMeta(
        src: File,
        dest: File,
    ) {
        val meta =
            File(
                src.absolutePath +
                    ".meta",
            )

        if (!meta.exists()) return

        meta.copyTo(
            File(
                dest.absolutePath +
                    ".meta",
            ),
            overwrite = true,
        )
    }

    private fun sha256(
        file: File,
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256",
            )

        file.inputStream().use { input ->
            val buffer =
                ByteArray(
                    64 * 1024,
                )

            while (true) {
                val count =
                    input.read(buffer)

                if (count <= 0) break

                digest.update(
                    buffer,
                    0,
                    count,
                )
            }
        }

        return digest.digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }

    private fun token(
        value: String,
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256",
            ).digest(
                value.toByteArray(),
            )

        return digest
            .take(12)
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
