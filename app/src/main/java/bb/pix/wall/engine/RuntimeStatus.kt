package bb.pix.wall.engine

import android.content.Context

object RuntimeStatus {
    private const val PREFS = "bb_pixwall_runtime_status"

    fun set(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }
    fun setLong(context: Context, key: String, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(key, value).apply()
    }
    fun get(context: Context, key: String, fallback: String = "Unknown"): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, fallback) ?: fallback
    fun getLong(context: Context, key: String, fallback: Long = 0L): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, fallback)

    fun increment(context: Context, key: String): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getInt(key, 0) + 1
        p.edit().putInt(key, n).apply()
        return n
    }
    fun getInt(context: Context, key: String, fallback: Int = 0): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, fallback)
    fun reset(context: Context, key: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply() }

    fun source(context: Context, source: String, health: String) = set(context, "source_$source", health)
    fun activeSource(context: Context, source: String) = set(context, "active_source", source)
    fun success(context: Context, detail: String) {
        setLong(context, "last_success", System.currentTimeMillis())
        set(context, "last_success_detail", detail)
        set(context, "last_error", "")
    }
    fun failure(context: Context, detail: String) {
        setLong(context, "last_failure", System.currentTimeMillis())
        set(context, "last_error", detail.take(500))
    }
    fun nextRun(context: Context, whenMillis: Long) = setLong(context, "next_run", whenMillis)
}
