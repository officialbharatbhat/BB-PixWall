package bb.pix.wall.engine

import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object EngineExecutors {
    private val ids = AtomicInteger(1)
    private fun factory(prefix: String) = java.util.concurrent.ThreadFactory { r ->
        Thread(r, "$prefix-${ids.getAndIncrement()}").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = false
        }
    }

    val io: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 4, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(48), factory("bbpw-io"),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    /*
     * Latency-sensitive blur lane.
     *
     * Do not share this with cloud/cache IO. Blur Wall is user initiated
     * and should begin immediately even while the wallpaper cache is busy.
     */
    val blur = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bbpw-blur-${ids.getAndIncrement()}").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = false
        }
    }

    val serial = Executors.newSingleThreadExecutor(factory("bbpw-serial"))
    val scheduler = ScheduledThreadPoolExecutor(1, factory("bbpw-sched")).apply {
        removeOnCancelPolicy = true
    }

    fun io(block: () -> Unit) = io.execute(block)
    fun blur(block: () -> Unit) = blur.execute(block)
    fun serial(block: () -> Unit) = serial.execute(block)
}
