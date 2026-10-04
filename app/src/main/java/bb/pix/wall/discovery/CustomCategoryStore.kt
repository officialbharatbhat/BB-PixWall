package bb.pix.wall.discovery

import android.content.Context
import bb.pix.wall.discovery.model.WallpaperCategory
import org.json.JSONArray
import org.json.JSONObject

object CustomCategoryStore {
    private const val PREFS =
        "bb_pixwall_custom_categories_v1"

    private const val KEY =
        "categories_json"

    fun load(context: Context): List<WallpaperCategory> {
        val raw =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE,
            ).getString(
                KEY,
                "[]",
            ) ?: "[]"

        return runCatching {
            val array = JSONArray(raw)

            buildList {
                for (i in 0 until array.length()) {
                    val obj =
                        array.optJSONObject(i)
                            ?: continue

                    val title =
                        obj.optString("title")
                            .trim()

                    val query =
                        obj.optString("query")
                            .trim()

                    val id =
                        obj.optString("id")
                            .trim()

                    if (
                        id.isBlank() ||
                        title.isBlank() ||
                        query.isBlank()
                    ) {
                        continue
                    }

                    add(
                        WallpaperCategory(
                            id = id,
                            title = title,
                            groupId = "custom",
                            searchTerms =
                                listOf(query),
                            custom = true,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(
        context: Context,
        title: String,
        query: String = title,
    ): WallpaperCategory? {
        val cleanTitle =
            title.trim()
                .takeIf { it.isNotBlank() }
                ?: return null

        val cleanQuery =
            query.trim()
                .takeIf { it.isNotBlank() }
                ?: cleanTitle

        val id =
            "custom_" +
                cleanTitle
                    .lowercase()
                    .replace(
                        Regex("[^a-z0-9]+"),
                        "_",
                    )
                    .trim('_')
                    .take(48)

        if (id == "custom_") {
            return null
        }

        val current =
            load(context)
                .toMutableList()

        val item =
            WallpaperCategory(
                id = id,
                title = cleanTitle.take(64),
                groupId = "custom",
                searchTerms =
                    listOf(
                        cleanQuery.take(120)
                    ),
                custom = true,
            )

        current.removeAll {
            it.id == id
        }

        current += item

        save(
            context,
            current,
        )

        return item
    }

    fun remove(
        context: Context,
        id: String,
    ) {
        save(
            context,
            load(context)
                .filterNot {
                    it.id == id
                },
        )
    }

    private fun save(
        context: Context,
        categories: List<WallpaperCategory>,
    ) {
        val array =
            JSONArray()

        categories.forEach { item ->
            array.put(
                JSONObject()
                    .put(
                        "id",
                        item.id,
                    )
                    .put(
                        "title",
                        item.title,
                    )
                    .put(
                        "query",
                        item.searchTerms
                            .firstOrNull()
                            .orEmpty(),
                    )
            )
        }

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        ).edit()
            .putString(
                KEY,
                array.toString(),
            )
            .apply()
    }
}
