package bb.pix.wall.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BB-PixWall portable local backup.
 *
 * Design:
 * - survives app uninstall because file lives under /sdcard/wallpaper/
 * - versioned JSON schema
 * - restores known SharedPreferences without deleting future/new keys
 * - runtime/transient diagnostics are intentionally excluded
 * - obvious secrets/auth material are intentionally excluded
 *
 * Google Drive backup can later upload this exact file format.
 */
object SettingsBackup {

    private const val SCHEMA_VERSION = 1

    private val excludedPrefs =
        setOf(
            "bb_pixwall_runtime",
            "bb_pixwall_runtime_status",
        )

    private val sensitiveTokens =
        listOf(
            "password",
            "passwd",
            "secret",
            "token",
            "auth",
            "credential",
            "cookie",
            "session_key",
            "lan_pin",
        )

    data class Result(
        val success: Boolean,
        val message: String,
        val file: File? = null,
    )

    private fun backupDir(): File =
        File(
            "/sdcard/wallpaper/backup/settings"
        ).apply {
            mkdirs()
        }

    private fun latestFile(): File =
        File(
            backupDir(),
            "BB-PixWall-settings-latest.json",
        )

    private fun isSensitive(
        key: String,
    ): Boolean {
        val lower = key.lowercase(Locale.ROOT)

        return sensitiveTokens.any {
            it in lower
        }
    }

    private fun availablePreferenceNames(
        context: Context,
    ): List<String> {
        val dir =
            File(
                context.applicationInfo.dataDir,
                "shared_prefs",
            )

        return dir
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter {
                it.isFile &&
                    it.extension == "xml"
            }
            .map {
                it.nameWithoutExtension
            }
            .filter {
                it !in excludedPrefs
            }
            .distinct()
            .sorted()
            .toList()
    }

    private fun appVersion(
        context: Context,
    ): String =
        runCatching {
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    0,
                )
                .versionName
                .toString()
        }.getOrDefault("unknown")

    fun create(
        context: Context,
        includeLearning: Boolean = true,
    ): Result =
        runCatching {
            val now =
                System.currentTimeMillis()

            val root =
                JSONObject().apply {
                    put(
                        "schemaVersion",
                        SCHEMA_VERSION,
                    )
                    put(
                        "packageName",
                        context.packageName,
                    )
                    put(
                        "appVersion",
                        appVersion(context),
                    )
                    put(
                        "createdAt",
                        now,
                    )
                    put(
                        "includeLearning",
                        includeLearning,
                    )
                }

            val allPrefs =
                JSONObject()

            val preferenceNames =
                availablePreferenceNames(
                    context
                )

            preferenceNames.forEach {
                    prefName ->

                /*
                 * Core settings/order always included.
                 * With learning enabled we retain all persistent
                 * non-runtime app preference sets so Taste/Style/
                 * Session learning survives reinstall as well.
                 */
                val include =
                    includeLearning ||
                        prefName ==
                            "bb_pixwall_settings" ||
                        prefName ==
                            "bb_pixwall_order"

                if (!include) {
                    return@forEach
                }

                val source =
                    context
                        .getSharedPreferences(
                            prefName,
                            Context.MODE_PRIVATE,
                        )
                        .all

                val target =
                    JSONObject()

                source
                    .toSortedMap()
                    .forEach {
                            (key, value) ->

                        if (
                            isSensitive(key)
                        ) {
                            return@forEach
                        }

                        val encoded =
                            JSONObject()

                        when (value) {
                            is Boolean -> {
                                encoded.put(
                                    "type",
                                    "boolean",
                                )
                                encoded.put(
                                    "value",
                                    value,
                                )
                            }

                            is Int -> {
                                encoded.put(
                                    "type",
                                    "int",
                                )
                                encoded.put(
                                    "value",
                                    value,
                                )
                            }

                            is Long -> {
                                encoded.put(
                                    "type",
                                    "long",
                                )
                                encoded.put(
                                    "value",
                                    value,
                                )
                            }

                            is Float -> {
                                encoded.put(
                                    "type",
                                    "float",
                                )
                                encoded.put(
                                    "value",
                                    value.toDouble(),
                                )
                            }

                            is String -> {
                                encoded.put(
                                    "type",
                                    "string",
                                )
                                encoded.put(
                                    "value",
                                    value,
                                )
                            }

                            is Set<*> -> {
                                val array =
                                    JSONArray()

                                value
                                    .filterIsInstance<String>()
                                    .sorted()
                                    .forEach {
                                        array.put(it)
                                    }

                                encoded.put(
                                    "type",
                                    "string_set",
                                )
                                encoded.put(
                                    "value",
                                    array,
                                )
                            }

                            else ->
                                return@forEach
                        }

                        target.put(
                            key,
                            encoded,
                        )
                    }

                if (
                    target.length() > 0
                ) {
                    allPrefs.put(
                        prefName,
                        target,
                    )
                }
            }

            root.put(
                "preferences",
                allPrefs,
            )

            val stamp =
                SimpleDateFormat(
                    "yyyyMMdd-HHmmss",
                    Locale.US,
                ).format(
                    Date(now)
                )

            val dir =
                backupDir()

            val file =
                File(
                    dir,
                    "BB-PixWall-settings-$stamp.json",
                )

            val temp =
                File(
                    dir,
                    ".BB-PixWall-settings-$stamp.tmp",
                )

            temp.writeText(
                root.toString(2)
            )

            if (
                !temp.renameTo(file)
            ) {
                temp.copyTo(
                    file,
                    overwrite = true,
                )
                temp.delete()
            }

            latestFile().writeText(
                root.toString(2)
            )

            /*
             * Keep latest + five timestamped snapshots.
             */
            dir.listFiles()
                .orEmpty()
                .filter {
                    it.isFile &&
                        it.name.startsWith(
                            "BB-PixWall-settings-"
                        ) &&
                        it.name !=
                            "BB-PixWall-settings-latest.json" &&
                        it.extension == "json"
                }
                .sortedByDescending {
                    it.lastModified()
                }
                .drop(5)
                .forEach {
                    it.delete()
                }

            Result(
                success = true,
                message =
                    "Backup saved • ${allPrefs.length()} preference groups",
                file = file,
            )
        }.getOrElse {
            Result(
                success = false,
                message =
                    "Backup failed: ${
                        it.message
                            ?: it.javaClass.simpleName
                    }",
            )
        }

    fun latest(): File? {
        val direct =
            latestFile()

        if (
            direct.exists() &&
            direct.length() > 0L
        ) {
            return direct
        }

        return backupDir()
            .listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.extension == "json" &&
                    it.name.startsWith(
                        "BB-PixWall-settings-"
                    )
            }
            .maxByOrNull {
                it.lastModified()
            }
    }

    fun restoreLatest(
        context: Context,
    ): Result {
        val source =
            latest()
                ?: return Result(
                    false,
                    "No BB-PixWall backup found",
                )

        return restore(
            context,
            source,
        )
    }

    fun restore(
        context: Context,
        source: File,
    ): Result =
        runCatching {
            require(
                source.exists() &&
                    source.length() > 0L
            ) {
                "Backup file missing or empty"
            }

            val root =
                JSONObject(
                    source.readText()
                )

            val schema =
                root.optInt(
                    "schemaVersion",
                    -1,
                )

            require(
                schema in 1..SCHEMA_VERSION
            ) {
                "Unsupported backup schema: $schema"
            }

            val packageName =
                root.optString(
                    "packageName",
                    "",
                )

            require(
                packageName.isBlank() ||
                    packageName ==
                        context.packageName
            ) {
                "Backup belongs to another app"
            }

            val allPrefs =
                root.getJSONObject(
                    "preferences"
                )

            var groups = 0
            var values = 0

            val prefNames =
                allPrefs.keys()

            while (
                prefNames.hasNext()
            ) {
                val prefName =
                    prefNames.next()

                if (
                    prefName in excludedPrefs
                ) {
                    continue
                }

                val sourcePrefs =
                    allPrefs.getJSONObject(
                        prefName
                    )

                val editor =
                    context
                        .getSharedPreferences(
                            prefName,
                            Context.MODE_PRIVATE,
                        )
                        .edit()

                val keys =
                    sourcePrefs.keys()

                while (
                    keys.hasNext()
                ) {
                    val key =
                        keys.next()

                    if (
                        isSensitive(key)
                    ) {
                        continue
                    }

                    val encoded =
                        sourcePrefs
                            .getJSONObject(key)

                    when (
                        encoded.getString(
                            "type"
                        )
                    ) {
                        "boolean" ->
                            editor.putBoolean(
                                key,
                                encoded.getBoolean(
                                    "value"
                                ),
                            )

                        "int" ->
                            editor.putInt(
                                key,
                                encoded.getInt(
                                    "value"
                                ),
                            )

                        "long" ->
                            editor.putLong(
                                key,
                                encoded.getLong(
                                    "value"
                                ),
                            )

                        "float" ->
                            editor.putFloat(
                                key,
                                encoded.getDouble(
                                    "value"
                                ).toFloat(),
                            )

                        "string" ->
                            editor.putString(
                                key,
                                encoded.getString(
                                    "value"
                                ),
                            )

                        "string_set" -> {
                            val array =
                                encoded.getJSONArray(
                                    "value"
                                )

                            val set =
                                buildSet {
                                    for (
                                        i in 0 until
                                            array.length()
                                    ) {
                                        add(
                                            array.getString(i)
                                        )
                                    }
                                }

                            editor.putStringSet(
                                key,
                                set,
                            )
                        }
                    }

                    values++
                }

                /*
                 * commit() is intentional here.
                 * Restore should be durable before UI/service reload.
                 */
                check(
                    editor.commit()
                ) {
                    "Could not restore $prefName"
                }

                groups++
            }

            Result(
                true,
                "Restored $values values from $groups groups",
                source,
            )
        }.getOrElse {
            Result(
                false,
                "Restore failed: ${
                    it.message
                        ?: it.javaClass.simpleName
                }",
                source,
            )
        }
}
