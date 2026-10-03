package bb.pix.wall.root

import android.content.Context
import android.os.Process
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.SettingsStore
import java.io.BufferedReader
import java.io.InputStreamReader

object RootAccess {
    data class Result(val granted: Boolean, val detail: String)
    data class Capabilities(
        val root: Boolean,
        val renice: Boolean,
        val deviceIdle: Boolean,
        val appOps: Boolean,
        val oomScore: Boolean,
        val detail: String,
    )
    @Volatile private var cached: Capabilities? = null

    fun isEnabled(context: Context): Boolean = SettingsStore(context).load().engineMode == EngineMode.ADVANCED
    fun probe(): Result = runCommand("id", 2500L)

    fun capabilities(context: Context, force: Boolean = false): Capabilities {
        if (!force) cached?.let { return it }
        val root = runCommand("id", 2500L).granted
        if (!root) return Capabilities(false,false,false,false,false,"su unavailable").also { cached = it }
        fun has(cmd: String) = runCommand("command -v $cmd >/dev/null 2>&1", 1800L).granted
        val caps = Capabilities(
            true,
            has("renice"),
            runCommand("cmd deviceidle help >/dev/null 2>&1", 1800L).granted,
            runCommand("cmd appops help >/dev/null 2>&1", 1800L).granted,
            runCommand("test -w /proc/${Process.myPid()}/oom_score_adj", 1800L).granted,
            "root capability scan complete"
        )
        cached = caps
        RuntimeStatus.set(context,"root_caps","renice=${caps.renice},deviceidle=${caps.deviceIdle},appops=${caps.appOps},oom=${caps.oomScore}")
        return caps
    }

    fun requestAndTune(context: Context): Result {
        val caps = capabilities(context, true)
        if (!caps.root) return Result(false, "Root not granted")
        val tuned = tuneBackground(context)
        runCommand("mkdir -p /sdcard/wallpaper/{backup,cache,queue,saved,logs,quarantine} && chmod -R 0775 /sdcard/wallpaper", 4000L)
        return Result(true, "Root granted; ${tuned.detail}")
    }

    fun tuneBackground(context: Context): Result {
        val caps = capabilities(context)
        if (!caps.root) return Result(false,"Root unavailable")
        val pkg = context.packageName
        val commands = buildList {
            if (caps.renice) add("renice -10 -p ${Process.myPid()}")
            if (caps.deviceIdle) add("cmd deviceidle whitelist +$pkg")
            if (caps.appOps) { add("cmd appops set $pkg RUN_IN_BACKGROUND allow"); add("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow") }
            if (caps.oomScore) add("echo -800 > /proc/${Process.myPid()}/oom_score_adj")
        }
        val details = commands.map { c -> "$c => ${runCommand(c,2500L).detail.take(60)}" }
        val verifyNice = if (caps.renice) runCommand("ps -o NI= -p ${Process.myPid()} 2>/dev/null", 1800L).detail else "unsupported"
        val whitelist = if (caps.deviceIdle) runCommand("cmd deviceidle whitelist | grep -F '$pkg'", 1800L).granted else false
        RuntimeStatus.set(context,"root_priority","nice=$verifyNice")
        RuntimeStatus.set(context,"root_doze_whitelist", whitelist.toString())
        return Result(true, if (details.isEmpty()) "No supported tuning hooks" else details.joinToString(" | "))
    }

    private fun runCommand(command: String, timeoutMs: Long): Result = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) { p.destroyForcibly(); Result(false, "timeout") } else {
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }.trim()
            val code = p.exitValue()
            val granted = code == 0 && (command != "id" || out.contains("uid=0"))
            Result(granted, if (out.isBlank()) "exit=$code" else out)
        }
    } catch (t: Throwable) { Result(false, t.message ?: "su unavailable") }
}
