package bb.pix.wall.root

import android.content.Context
import android.os.Process
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.SettingsStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object RootAccess {
    data class Result(
        val granted: Boolean,
        val detail: String,
        val latencyMs: Long = 0L,
    )

    data class Capabilities(
        val root: Boolean,
        val provider: String,
        val renice: Boolean,
        val deviceIdle: Boolean,
        val appOps: Boolean,
        val oomScore: Boolean,
        val checkedAt: Long,
        val latencyMs: Long,
        val detail: String,
    )

    @Volatile private var cached: Capabilities? = null
    private val lastProbeAt = AtomicLong(0L)
    private const val CACHE_MS = 5 * 60 * 1000L

    fun isEnabled(context: Context): Boolean =
        SettingsStore(context).load().engineMode == EngineMode.ADVANCED

    fun probe(context: Context? = null, force: Boolean = false): Result {
        val now = System.currentTimeMillis()
        if (!force && now - lastProbeAt.get() < 15_000L) {
            val c = cached
            if (c != null) return Result(c.root, if (c.root) "Root cached" else c.detail, c.latencyMs)
        }
        lastProbeAt.set(now)
        val r = runCommand("id", 2500L)
        context?.let {
            RuntimeStatus.set(it, "root_state", if (r.granted) "Granted" else "Unavailable / denied")
            RuntimeStatus.setLong(it, "root_last_check", System.currentTimeMillis())
            RuntimeStatus.setLong(it, "root_latency_ms", r.latencyMs)
            rootLog("ROOT probe granted=${r.granted} latency=${r.latencyMs}ms detail=${r.detail.take(100)}")
        }
        return r
    }

    fun capabilities(context: Context, force: Boolean = false): Capabilities {
        val now = System.currentTimeMillis()
        if (!force) cached?.takeIf { now - it.checkedAt < CACHE_MS }?.let { return it }

        val rootProbe = probe(context, force = true)
        if (!rootProbe.granted) {
            val caps = Capabilities(
                root = false,
                provider = "Unavailable",
                renice = false,
                deviceIdle = false,
                appOps = false,
                oomScore = false,
                checkedAt = now,
                latencyMs = rootProbe.latencyMs,
                detail = rootProbe.detail,
            )
            cached = caps
            publish(context, caps)
            return caps
        }

        fun has(command: String): Boolean = runCommand(command, 1800L).granted
        val provider = runCommand("su --version 2>/dev/null || su -V 2>/dev/null || echo root", 1800L)
            .detail.lineSequence().firstOrNull()?.take(80)?.ifBlank { "su" } ?: "su"

        val caps = Capabilities(
            root = true,
            provider = provider,
            renice = has("command -v renice >/dev/null 2>&1"),
            deviceIdle = has("cmd deviceidle help >/dev/null 2>&1"),
            appOps = has("cmd appops help >/dev/null 2>&1"),
            oomScore = has("test -w /proc/${Process.myPid()}/oom_score_adj"),
            checkedAt = now,
            latencyMs = rootProbe.latencyMs,
            detail = "Capability scan complete",
        )
        cached = caps
        publish(context, caps)
        return caps
    }

    fun requestAndTune(context: Context): Result {
        val started = System.currentTimeMillis()
        val caps = capabilities(context, force = true)
        if (!caps.root) {
            rootLog("ROOT request denied/unavailable: ${caps.detail}")
            return Result(false, "Root not granted", System.currentTimeMillis() - started)
        }

        val tuned = tuneBackground(context)
        val dirs = runCommand(
            "mkdir -p /sdcard/wallpaper/{backup,cache/hot,cache/warm,local,queue,quee,saved,logs,quarantine} && chmod -R 0775 /sdcard/wallpaper",
            4000L,
        )
        RuntimeStatus.set(context, "root_dirs", if (dirs.granted) "Verified" else "Skipped/failed: ${dirs.detail.take(80)}")
        rootLog("ROOT request complete tune=${tuned.granted} dirs=${dirs.granted}")
        return Result(true, "Root granted; ${tuned.detail}", System.currentTimeMillis() - started)
    }

    fun tuneBackground(context: Context): Result {
        val caps = capabilities(context)
        if (!caps.root) return Result(false, "Root unavailable")

        val pkg = context.packageName
        val actions = mutableListOf<String>()
        fun runVerified(label: String, command: String): Result {
            val r = runCommand(command, 2500L)
            actions += "$label=${if (r.granted) "ok" else "fail"}"
            rootLog("ROOT action $label granted=${r.granted} latency=${r.latencyMs}ms detail=${r.detail.take(90)}")
            return r
        }

        if (caps.renice) runVerified(
            "renice",
            "renice -n -10 -p ${Process.myPid()} 2>/dev/null || " +
                "renice -n -10 ${Process.myPid()} 2>/dev/null || " +
                "renice -10 ${Process.myPid()} 2>/dev/null"
        )
        if (caps.deviceIdle) runVerified("doze-whitelist", "cmd deviceidle whitelist +$pkg")
        if (caps.appOps) {
            runVerified("appops-bg", "cmd appops set $pkg RUN_IN_BACKGROUND allow")
            runVerified("appops-any-bg", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        }
        if (caps.oomScore) runVerified("oom-score", "echo -800 > /proc/${Process.myPid()}/oom_score_adj")

        val nice = if (caps.renice) runCommand(
            "cut -d ' ' -f 19 /proc/${Process.myPid()}/stat 2>/dev/null",
            1800L
        ).detail.trim() else "unsupported"
        val dozeVerified = if (caps.deviceIdle) runCommand("cmd deviceidle whitelist | grep -F '$pkg'", 1800L).granted else false
        val appOpsVerified = if (caps.appOps) {
            val check = runCommand("cmd appops get $pkg RUN_IN_BACKGROUND 2>/dev/null | grep -qi allow", 1800L)
            check.granted
        } else false
        val oom = if (caps.oomScore) runCommand("cat /proc/${Process.myPid()}/oom_score_adj 2>/dev/null", 1800L).detail.trim() else "unsupported"

        RuntimeStatus.set(context, "root_priority", "nice=${nice.ifBlank { "unknown" }}")
        RuntimeStatus.set(context, "root_doze_whitelist", dozeVerified.toString())
        RuntimeStatus.set(context, "root_appops_verified", appOpsVerified.toString())
        RuntimeStatus.set(context, "root_oom_score", oom.ifBlank { "unknown" })
        RuntimeStatus.setLong(context, "root_last_tune", System.currentTimeMillis())
        RuntimeStatus.set(context, "root_last_result", if (actions.isEmpty()) "No supported tuning hooks" else actions.joinToString(", "))

        return Result(true, RuntimeStatus.get(context, "root_last_result", "Tuned"))
    }

    fun diagnosticsText(context: Context): String {
        val caps = capabilities(context, force = false)
        return buildString {
            appendLine("Root: ${if (caps.root) "Granted" else "Unavailable / denied"}")
            appendLine("Provider: ${caps.provider}")
            appendLine("Probe latency: ${RuntimeStatus.getLong(context, "root_latency_ms", caps.latencyMs)} ms")
            appendLine("renice: ${caps.renice}")
            appendLine("deviceidle: ${caps.deviceIdle}")
            appendLine("appops: ${caps.appOps}")
            appendLine("oom_score_adj writable: ${caps.oomScore}")
            appendLine("Priority: ${RuntimeStatus.get(context, "root_priority", "Not tuned")}")
            appendLine("Doze whitelist verified: ${RuntimeStatus.get(context, "root_doze_whitelist", "Unknown")}")
            appendLine("AppOps verified: ${RuntimeStatus.get(context, "root_appops_verified", "Unknown")}")
            appendLine("OOM score: ${RuntimeStatus.get(context, "root_oom_score", "Unknown")}")
            appendLine("Last tune: ${RuntimeStatus.get(context, "root_last_result", "Not tuned")}")
        }.trim()
    }

    private fun publish(context: Context, caps: Capabilities) {
        RuntimeStatus.set(context, "root_state", if (caps.root) "Granted" else "Unavailable / denied")
        RuntimeStatus.set(context, "root_provider", caps.provider)
        RuntimeStatus.setLong(context, "root_last_check", caps.checkedAt)
        RuntimeStatus.setLong(context, "root_latency_ms", caps.latencyMs)
        RuntimeStatus.set(
            context,
            "root_caps",
            "renice=${caps.renice}, deviceidle=${caps.deviceIdle}, appops=${caps.appOps}, oom=${caps.oomScore}",
        )
        rootLog("ROOT capability root=${caps.root} provider=${caps.provider} renice=${caps.renice} deviceidle=${caps.deviceIdle} appops=${caps.appOps} oom=${caps.oomScore}")
    }

    private fun runCommand(command: String, timeoutMs: Long): Result {
        val started = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                Result(false, "timeout", System.currentTimeMillis() - started)
            } else {
                val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }.trim()
                val code = p.exitValue()
                val granted = code == 0 && (command != "id" || out.contains("uid=0"))
                Result(granted, if (out.isBlank()) "exit=$code" else out, System.currentTimeMillis() - started)
            }
        } catch (t: Throwable) {
            Result(false, t.message ?: "su unavailable", System.currentTimeMillis() - started)
        }
    }

    private fun rootLog(message: String) {
        runCatching {
            if (WallpaperFiles.ensure()) WallpaperFiles.runtimeLog.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
