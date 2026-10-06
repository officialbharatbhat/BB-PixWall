package bb.pix.wall.engine

import android.content.Context
import bb.pix.wall.settings.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import kotlin.math.abs

/**
 * Mega Phase 4 premium observability/control layer.
 *
 * No scheduler, polling loop or bitmap analysis is started here.
 * refresh() only reads already persisted engine state and publishes
 * developer-grade diagnostics.
 */
object PremiumIntelligenceCenter {

    private const val PREFS =
        "bb_pixwall_premium_intelligence_v4"

    private const val EVENT_LIMIT = 14

    data class SlotSnapshot(
        val available: Boolean,
        val dna: String,
        val family: String,
        val palette: String,
        val style: String,
        val quality: Int,
        val amoled: Int,
        val readability: Int,
        val crop: Int,
        val entropy: Int,
        val role: Int,
        val dimensions: String,
        val brightness: Float,
        val saturation: Float,
        val contrast: Float,
        val busy: Int,
    )

    // --------------------------------------------------------
    // PUBLIC REFRESH / EVENT API
    // --------------------------------------------------------

    @Synchronized
    fun refresh(
        context: Context,
        reason: String = "manual",
    ) {
        WallpaperFiles.ensure()

        val home =
            slotSnapshot(
                context,
                WallpaperFiles.currentHome,
                homeRole = true,
            )

        val lock =
            slotSnapshot(
                context,
                WallpaperFiles.currentLock,
                homeRole = false,
            )

        publishSlot(
            context,
            "home",
            home,
        )

        publishSlot(
            context,
            "lock",
            lock,
        )

        publishPair(
            context,
            home,
            lock,
        )

        publishCache(
            context,
        )

        publishSources(
            context,
        )

        publishEngine(
            context,
        )

        publishLibrary(
            context,
        )

        RuntimeStatus.set(
            context,
            "premium_refresh_reason",
            reason,
        )

        RuntimeStatus.setLong(
            context,
            "premium_refresh_at",
            System.currentTimeMillis(),
        )

    }

    @Synchronized
    fun recordEvent(
        context: Context,
        type: String,
        detail: String,
    ) {
        val prefs =
            prefs(context)

        val now =
            System.currentTimeMillis()

        val cleanType =
            type.trim()
                .uppercase(Locale.US)
                .take(24)

        val cleanDetail =
            detail.trim()
                .replace(
                    "\n",
                    " ",
                )
                .take(220)

        val newest =
            prefs.getString(
                "event_0",
                "",
            ).orEmpty()

        val signature =
            "$cleanType|$cleanDetail"

        val lastSignature =
            prefs.getString(
                "event_signature",
                "",
            ).orEmpty()

        val lastAt =
            prefs.getLong(
                "event_signature_at",
                0L,
            )

        if (
            signature == lastSignature &&
            now - lastAt < 5_000L
        ) {
            return
        }

        val line =
            "$now|$cleanType|$cleanDetail"

        val old =
            (0 until EVENT_LIMIT)
                .mapNotNull {
                    prefs.getString(
                        "event_$it",
                        null,
                    )
                }
                .filter {
                    it.isNotBlank()
                }
                .filterNot { raw ->
                    val parts =
                        raw.split(
                            "|",
                            limit = 3,
                        )

                    parts.size == 3 &&
                        "${parts[1]}|${parts[2]}" ==
                        signature
                }

        val next =
            (
                listOf(line) +
                    old
                )
                .distinct()
                .take(EVENT_LIMIT)

        val edit =
            prefs.edit()
                .putString(
                    "event_signature",
                    signature,
                )
                .putLong(
                    "event_signature_at",
                    now,
                )

        for (
            i in
            0 until EVENT_LIMIT
        ) {
            if (i < next.size) {
                edit.putString(
                    "event_$i",
                    next[i],
                )
            } else {
                edit.remove(
                    "event_$i"
                )
            }
        }

        edit.apply()

        RuntimeStatus.set(
            context,
            "premium_event_timeline",
            timeline(context),
        )
    }

    fun timeline(
        context: Context,
    ): String {
        val now =
            System.currentTimeMillis()

        return (0 until EVENT_LIMIT)
            .mapNotNull {
                prefs(context)
                    .getString(
                        "event_$it",
                        null,
                    )
            }
            .mapNotNull { raw ->
                val parts =
                    raw.split(
                        "|",
                        limit = 3,
                    )

                if (parts.size < 3) {
                    null
                } else {
                    val at =
                        parts[0]
                            .toLongOrNull()
                            ?: 0L

                    "${age(now - at)} • " +
                        "${parts[1]} • " +
                        parts[2]
                }
            }
            .joinToString("\n")
            .ifBlank {
                "No premium events yet"
            }
    }

    // --------------------------------------------------------
    // ACTIONS
    // --------------------------------------------------------

    fun reanalyzeCurrent(
        context: Context,
    ): Boolean {
        return runCatching {
            VisualIntelligenceEngine
                .analyzeCurrent(
                    context
                )

            VisualIntelligenceEngine
                .publishForFile(
                    context,
                    WallpaperFiles.currentHome,
                    "current_home",
                )

            VisualIntelligenceEngine
                .publishForFile(
                    context,
                    WallpaperFiles.currentLock,
                    "current_lock",
                )

            refresh(
                context,
                "current-wallpaper-reanalyzed",
            )

            recordEvent(
                context,
                "ANALYZE",
                "Current Home/Lock visual profiles refreshed",
            )

            true
        }.getOrDefault(false)
    }

    fun rebuildNext(
        context: Context,
    ): Boolean {
        val settings =
            SettingsStore(context)
                .load()

        return runCatching {
            WallpaperController
                .invalidateQueue()

            var ready =
                WallpaperController
                    .ensureNext(
                        context,
                        settings,
                        allowNetwork = false,
                    )

            if (
                !ready &&
                WallpaperSourceEngine
                    .networkAvailable(
                        context
                    )
            ) {
                ready =
                    WallpaperController
                        .ensureNext(
                            context,
                            settings,
                            allowNetwork = true,
                        )
            }

            recordEvent(
                context,
                "QUEUE",
                if (ready) {
                    "Next Home/Lock queue rebuilt"
                } else {
                    "Next queue rebuild deferred"
                },
            )

            refresh(
                context,
                "next-queue-rebuild",
            )

            ready
        }.getOrDefault(false)
    }

    fun cacheIntegrityAudit(
        context: Context,
    ): Int {
        val bad =
            runCatching {
                WallpaperController
                    .verifyCacheIntegrity()
            }.getOrDefault(0)

        recordEvent(
            context,
            "CACHE",
            if (bad == 0) {
                "Cache integrity clean"
            } else {
                "Quarantined $bad invalid cache item(s)"
            },
        )

        refresh(
            context,
            "cache-integrity-audit",
        )

        return bad
    }

    fun fullRepair(
        context: Context,
    ): String {
        val settings =
            SettingsStore(context)
                .load()

        val result =
            AutonomousIntelligenceEngine
                .auditAndRepair(
                    context = context,
                    settings = settings,
                    allowNetworkRefill = false,
                )

        recordEvent(
            context,
            "REPAIR",
            "${result.grade} • ${result.action}",
        )

        refresh(
            context,
            "full-self-heal",
        )

        return "${result.grade} • " +
            "${result.engineScore}/100 • " +
            result.action
    }

    fun resetSource(
        context: Context,
        source: String,
    ) {
        AutonomousIntelligenceEngine
            .resetSourceReputation(
                context,
                source,
            )

        recordEvent(
            context,
            "SOURCE",
            "$source reputation reset",
        )

        refresh(
            context,
            "source-reset-$source",
        )
    }

    fun exportReport(
        context: Context,
    ): File {
        refresh(
            context,
            "diagnostics-export",
        )

        val out =
            File(
                WallpaperFiles.logs,
                "premium_intelligence_report.txt",
            )

        val keys =
            listOf(
                "premium_home_dna",
                "premium_home_family",
                "premium_home_palette",
                "premium_home_visual",
                "premium_home_role",
                "premium_home_quality",
                "premium_home_amoled",
                "premium_home_readability",
                "premium_home_crop",
                "premium_home_entropy",
                "premium_lock_dna",
                "premium_lock_family",
                "premium_lock_palette",
                "premium_lock_visual",
                "premium_lock_role",
                "premium_lock_quality",
                "premium_lock_amoled",
                "premium_lock_readability",
                "premium_lock_crop",
                "premium_lock_entropy",
                "premium_pair_summary",
                "premium_pair_harmony",
                "premium_pair_brightness",
                "premium_pair_palette",
                "premium_pair_style",
                "premium_cache_map",
                "premium_cache_size",
                "premium_cache_readiness",
                "premium_source_photos",
                "premium_source_drive",
                "premium_watchdog_age",
                "premium_last_change_age",
                "premium_apply_timing",
                "premium_decision",
                "premium_learning",
                "premium_context",
                "premium_library",
                "premium_recovery",
                "premium_event_timeline",
                "autonomous_grade",
                "autonomous_health",
                "self_heal_last",
                "decision_trace_id",
                "decision_why_v2",
                "decision_why_not_runner",
                "decision_breakdown_v2",
                "shadow_rank",
                "quality_guard_last",
                "resource_policy",
                "visual_pairing",
            )

        val generated =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.US,
            ).format(Date())

        val content =
            buildString {
                appendLine(
                    "BB-PixWall Premium Intelligence Report"
                )
                appendLine(
                    "Generated: $generated"
                )
                appendLine(
                    "========================================"
                )

                keys.forEach { key ->
                    appendLine(
                        "$key=${RuntimeStatus.get(context, key, "-")}"
                    )
                }

                appendLine()
                appendLine(
                    "EVENT TIMELINE"
                )
                appendLine(
                    "========================================"
                )
                appendLine(
                    timeline(context)
                )
            }

        out.parentFile
            ?.mkdirs()

        out.writeText(
            content
        )

        RuntimeStatus.set(
            context,
            "premium_export_path",
            out.absolutePath,
        )

        recordEvent(
            context,
            "EXPORT",
            out.name,
        )

        return out
    }

    // --------------------------------------------------------
    // SLOT / PAIR PUBLISHING
    // --------------------------------------------------------

    private fun slotSnapshot(
        context: Context,
        file: File,
        homeRole: Boolean,
    ): SlotSnapshot {
        val id =
            candidateId(file)

        val p =
            id?.let {
                VisualIntelligenceEngine
                    .profile(
                        context,
                        it,
                    )
            }

        if (p == null) {
            return SlotSnapshot(
                available = false,
                dna = "Waiting",
                family = "Waiting",
                palette = "Waiting",
                style = "Waiting",
                quality = 0,
                amoled = 0,
                readability = 0,
                crop = 0,
                entropy = 0,
                role = 0,
                dimensions = "Unknown",
                brightness = 0f,
                saturation = 0f,
                contrast = 0f,
                busy = 0,
            )
        }

        return SlotSnapshot(
            available = true,
            dna =
                AutonomousIntelligenceEngine
                    .dna(p),
            family =
                AutonomousIntelligenceEngine
                    .family(p),
            palette =
                p.palette.ifBlank {
                    "Unavailable"
                },
            style =
                p.styleClass,
            quality =
                p.quality,
            amoled =
                p.amoledScore,
            readability =
                p.lockReadability,
            crop =
                p.cropSafety,
            entropy =
                AutonomousIntelligenceEngine
                    .visualEntropy(p),
            role =
                if (homeRole) {
                    AutonomousIntelligenceEngine
                        .homeRoleScore(p)
                } else {
                    AutonomousIntelligenceEngine
                        .lockRoleScore(p)
                },
            dimensions =
                "${p.width}×${p.height}",
            brightness =
                p.brightness,
            saturation =
                p.saturation,
            contrast =
                p.contrast,
            busy =
                p.busyScore,
        )
    }

    private fun publishSlot(
        context: Context,
        slot: String,
        p: SlotSnapshot,
    ) {
        RuntimeStatus.set(
            context,
            "premium_${slot}_dna",
            p.dna,
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_family",
            p.family,
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_palette",
            p.palette,
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_visual",
            if (p.available) {
                "${p.style} • ${p.dimensions} • " +
                    "brightness=${pct(p.brightness)} • " +
                    "saturation=${pct(p.saturation)} • " +
                    "contrast=${pct(p.contrast)} • " +
                    "busy=${p.busy}"
            } else {
                "Waiting for analyzed current wallpaper"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_role",
            if (p.available) {
                "${p.role}/100"
            } else {
                "Waiting"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_quality",
            if (p.available) {
                "${p.quality}/100"
            } else {
                "Waiting"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_amoled",
            if (p.available) {
                "${p.amoled}/100"
            } else {
                "Waiting"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_readability",
            if (p.available) {
                "${p.readability}/100"
            } else {
                "Waiting"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_crop",
            if (p.available) {
                "${p.crop}/100"
            } else {
                "Waiting"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_${slot}_entropy",
            if (p.available) {
                "${p.entropy}/100"
            } else {
                "Waiting"
            },
        )
    }

    private fun publishPair(
        context: Context,
        home: SlotSnapshot,
        lock: SlotSnapshot,
    ) {
        if (
            !home.available ||
            !lock.available
        ) {
            listOf(
                "premium_pair_summary",
                "premium_pair_harmony",
                "premium_pair_brightness",
                "premium_pair_palette",
                "premium_pair_style",
            ).forEach {
                RuntimeStatus.set(
                    context,
                    it,
                    "Waiting for Home + Lock profiles",
                )
            }

            return
        }

        val homeId =
            candidateId(
                WallpaperFiles.currentHome
            )

        val lockId =
            candidateId(
                WallpaperFiles.currentLock
            )

        val same =
            homeId != null &&
                homeId == lockId

        val harmony =
            if (same) {
                "Same wallpaper"
            } else {
                homeId?.let { a ->
                    lockId?.let { b ->
                        VisualIntelligenceEngine
                            .pairScore(
                                context,
                                a,
                                b,
                            )
                    }
                }?.let {
                    "$it/100"
                } ?: "Unavailable"
            }

        val brightnessDiff =
            abs(
                home.brightness -
                    lock.brightness
            )

        val brightnessLabel =
            when {
                brightnessDiff <= .08f ->
                    "Very close"

                brightnessDiff <= .20f ->
                    "Balanced contrast"

                brightnessDiff <= .38f ->
                    "Intentional contrast"

                else ->
                    "Strong contrast"
            }

        val paletteLabel =
            when {
                home.palette ==
                    lock.palette ->
                    "Same dominant palette"

                home.family ==
                    lock.family ->
                    "Closely related family"

                home.style ==
                    lock.style ->
                    "Related style • varied palette"

                else ->
                    "Complementary variation"
            }

        val styleLabel =
            if (
                home.style ==
                lock.style
            ) {
                "${home.style} + ${lock.style} • coherent"
            } else {
                "${home.style} + ${lock.style} • contrast"
            }

        val summary =
            "Home role ${home.role} • " +
                "Lock role ${lock.role} • " +
                "harmony $harmony • " +
                paletteLabel

        RuntimeStatus.set(
            context,
            "premium_pair_summary",
            summary,
        )

        RuntimeStatus.set(
            context,
            "premium_pair_harmony",
            harmony,
        )

        RuntimeStatus.set(
            context,
            "premium_pair_brightness",
            "$brightnessLabel • Δ${pct(brightnessDiff)}",
        )

        RuntimeStatus.set(
            context,
            "premium_pair_palette",
            paletteLabel,
        )

        RuntimeStatus.set(
            context,
            "premium_pair_style",
            styleLabel,
        )
    }

    // --------------------------------------------------------
    // CACHE / SOURCE / ENGINE
    // --------------------------------------------------------

    private fun publishCache(
        context: Context,
    ) {
        val hot =
            imageCount(
                WallpaperFiles.hotCache
            )

        val warm =
            imageCount(
                WallpaperFiles.warmCache
            )

        val queue =
            imageCount(
                WallpaperFiles.queue
            ) +
                imageCount(
                    WallpaperFiles.legacyQueue
                )

        val total =
            WallpaperController
                .cacheCount()

        val offline =
            WallpaperController
                .offlineReadyCount()

        val bytes =
            WallpaperController
                .cacheBytes()

        val readiness =
            if (total <= 0) {
                0
            } else {
                (
                    offline
                        .coerceAtMost(total) *
                        100 /
                        total
                    )
            }

        RuntimeStatus.set(
            context,
            "premium_cache_map",
            "HOT $hot • WARM $warm • QUEUE $queue • TOTAL $total",
        )

        RuntimeStatus.set(
            context,
            "premium_cache_size",
            humanBytes(bytes),
        )

        RuntimeStatus.set(
            context,
            "premium_cache_readiness",
            "$offline offline-ready • readiness ${readiness}%",
        )

        RuntimeStatus.set(
            context,
            "premium_cache_health",
            when {
                total == 0 ->
                    "Empty"

                offline >= 4 ->
                    "Excellent"

                offline >= 2 ->
                    "Ready"

                else ->
                    "Thin"
            },
        )
    }

    private fun publishSources(
        context: Context,
    ) {
        listOf(
            "photos",
            "drive",
        ).forEach { source ->
            val state =
                AutonomousIntelligenceEngine
                    .sourceState(
                        context,
                        source,
                    )

            val circuit =
                if (
                    state.circuitUntil >
                    System.currentTimeMillis()
                ) {
                    "OPEN • ${age(state.circuitUntil - System.currentTimeMillis())} remaining"
                } else {
                    "closed"
                }

            RuntimeStatus.set(
                context,
                "premium_source_$source",
                "${state.trust}/100 ${state.grade} • " +
                    "ok=${state.successes} • " +
                    "fail=${state.failures} • " +
                    "streak=${state.consecutiveFailures} • " +
                    "latency=${state.latencyEwmaMs}ms • " +
                    "circuit=$circuit",
            )
        }
    }

    private fun publishEngine(
        context: Context,
    ) {
        val now =
            System.currentTimeMillis()

        val auditAt =
            RuntimeStatus.getLong(
                context,
                "autonomous_audit_at",
                0L,
            )

        val lastChange =
            RuntimeStatus.getLong(
                context,
                "last_change",
                0L,
            )

        val homeMs =
            RuntimeStatus.getLong(
                context,
                "apply_home_ms",
                0L,
            )

        val lockMs =
            RuntimeStatus.getLong(
                context,
                "apply_lock_ms",
                0L,
            )

        RuntimeStatus.set(
            context,
            "premium_watchdog_age",
            if (auditAt > 0L) {
                age(now - auditAt)
            } else {
                "No audit yet"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_last_change_age",
            if (lastChange > 0L) {
                age(now - lastChange)
            } else {
                "No wallpaper change yet"
            },
        )

        RuntimeStatus.set(
            context,
            "premium_apply_timing",
            "Home ${homeMs}ms • Lock ${lockMs}ms",
        )

        RuntimeStatus.set(
            context,
            "premium_decision",
            RuntimeStatus.get(
                context,
                "decision_confidence_v2",
                "Waiting",
            ) +
                " • " +
                RuntimeStatus.get(
                    context,
                    "decision_trace_id",
                    "-",
                ),
        )

        RuntimeStatus.set(
            context,
            "premium_learning",
            RuntimeStatus.get(
                context,
                "taste_confidence_v2",
                "Learning",
            ) +
                " • " +
                RuntimeStatus.get(
                    context,
                    "learning_mode",
                    "Explore",
                ) +
                " • " +
                RuntimeStatus.get(
                    context,
                    "diversity_budget",
                    "Waiting",
                ),
        )

        RuntimeStatus.set(
            context,
            "premium_context",
            RuntimeStatus.get(
                context,
                "context_confidence",
                "Waiting",
            ) +
                " • " +
                RuntimeStatus.get(
                    context,
                    "context_lux_smoothed",
                    "Light unavailable",
                ) +
                " • " +
                RuntimeStatus.get(
                    context,
                    "context_weather_stable",
                    "Weather unavailable",
                ),
        )

        RuntimeStatus.set(
            context,
            "premium_recovery",
            "Self-heal " +
                RuntimeStatus.get(
                    context,
                    "self_heal_last",
                    "Waiting",
                ) +
                " • crash recoveries=" +
                RuntimeStatus.getInt(
                    context,
                    "crash_recovery_count",
                    0,
                ) +
                " • quality guard=" +
                RuntimeStatus.get(
                    context,
                    "quality_guard_last",
                    "No rejection",
                ),
        )

        RuntimeStatus.set(
            context,
            "premium_event_timeline",
            timeline(context),
        )
    }

    private fun publishLibrary(
        context: Context,
    ) {
        val history =
            WallpaperLibrary
                .history()
                .size

        val favorites =
            WallpaperFiles
                .favorites
                .listFiles()
                .orEmpty()
                .count {
                    it.isFile &&
                        !it.name.endsWith(
                            ".library"
                        )
                }

        val quarantine =
            WallpaperFiles
                .quarantine
                .listFiles()
                .orEmpty()
                .count {
                    it.isFile
                }

        val blocked =
            WallpaperLibrary
                .blockedIds()
                .size

        RuntimeStatus.set(
            context,
            "premium_library",
            "History $history • Favorites $favorites • " +
                "Blocked $blocked • Quarantine $quarantine",
        )
    }

    // --------------------------------------------------------
    // HELPERS
    // --------------------------------------------------------

    private fun prefs(
        context: Context,
    ) =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    private fun candidateId(
        file: File,
    ): String? {
        if (!file.exists()) {
            return null
        }

        val meta =
            File(
                file.absolutePath +
                    ".meta"
            )

        if (!meta.exists()) {
            return null
        }

        return runCatching {
            Properties().apply {
                meta.inputStream()
                    .use(::load)
            }.getProperty(
                "id"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
        }.getOrNull()
    }

    private fun imageCount(
        dir: File,
    ): Int =
        dir.listFiles()
            .orEmpty()
            .count {
                it.isFile &&
                    it.extension
                        .lowercase(
                            Locale.US
                        ) in
                    setOf(
                        "jpg",
                        "jpeg",
                        "png",
                        "webp",
                        "avif",
                    )
            }

    private fun pct(
        value: Float,
    ): Int =
        (
            value *
                100f
            )
            .toInt()
            .coerceIn(
                0,
                100,
            )

    private fun humanBytes(
        bytes: Long,
    ): String =
        when {
            bytes >=
                1024L *
                1024L *
                1024L ->
                String.format(
                    Locale.US,
                    "%.1f GB",
                    bytes.toDouble() /
                        (
                            1024.0 *
                                1024.0 *
                                1024.0
                            ),
                )

            bytes >=
                1024L *
                1024L ->
                String.format(
                    Locale.US,
                    "%.1f MB",
                    bytes.toDouble() /
                        (
                            1024.0 *
                                1024.0
                            ),
                )

            bytes >= 1024L ->
                String.format(
                    Locale.US,
                    "%.1f KB",
                    bytes.toDouble() /
                        1024.0,
                )

            else ->
                "$bytes B"
        }

    private fun age(
        millis: Long,
    ): String {
        if (millis <= 0L) {
            return "now"
        }

        val sec =
            millis /
                1000L

        return when {
            sec < 60L ->
                "${sec}s"

            sec < 3600L ->
                "${sec / 60L}m"

            sec < 86_400L ->
                "${sec / 3600L}h"

            else ->
                "${sec / 86_400L}d"
        }
    }
}
