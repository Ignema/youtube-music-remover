package com.musicremover.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicremover.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One reading of Murem's own load and the phone's state. Null = not readable on this phone. */
data class StatSample(
    /** Murem's CPU use, % of all cores. */
    val cpuPct: Float?,
    /** Murem's resident memory (MB). */
    val appMb: Float?,
    /** Memory the system reports as available (MB). */
    val freeMb: Float,
    /** Murem's disk reads / writes since the last sample (KB/s). */
    val readKbs: Float?,
    val writeKbs: Float?,
    /** Thermal headroom: 1.0 = the system starts throttling (Android 11+). */
    val thermal: Float?,
    /** PowerManager.THERMAL_STATUS_* (Android 10+). */
    val thermalStatus: Int?,
    /** GPU busy %, only where the driver exposes it to apps. */
    val gpuPct: Float?,
)

/** Pure parsers for the /proc and /sys files. */
object StatParsers {
    /** utime + stime (clock ticks) from /proc/self/stat; the name in parentheses may contain spaces. */
    fun cpuTicks(stat: String): Long? {
        val rest = stat.substringAfterLast(')', "").trim().split(' ').filter { it.isNotEmpty() }
        // rest[0] is field 3 (state); utime and stime are fields 14 and 15
        if (rest.size < 13) return null
        val u = rest[11].toLongOrNull() ?: return null
        val s = rest[12].toLongOrNull() ?: return null
        return u + s
    }

    /** VmRSS in KB from /proc/self/status. */
    fun rssKb(status: String): Long? =
        status.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
            ?.removePrefix("VmRSS:")?.trim()?.substringBefore(' ')?.toLongOrNull()

    /** (read_bytes, write_bytes) from /proc/self/io. */
    fun ioBytes(io: String): Pair<Long, Long>? {
        val map = io.lineSequence().mapNotNull { line ->
            val k = line.substringBefore(':', "").trim()
            val v = line.substringAfter(':', "").trim().toLongOrNull()
            if (k.isEmpty() || v == null) null else k to v
        }.toMap()
        val r = map["read_bytes"] ?: return null
        val w = map["write_bytes"] ?: return null
        return r to w
    }

    /**
     * GPU busy % from a driver file: a single number ("37" or "37 %"), or Adreno's
     * gpubusy pair "busy total" (busy / total).
     */
    fun gpuBusy(text: String): Float? {
        val nums = Regex("\\d+").findAll(text).map { it.value.toLong() }.toList()
        return when {
            nums.size >= 2 && nums[1] > 0 && !text.contains('%') -> (100f * nums[0] / nums[1]).coerceIn(0f, 100f)
            nums.isNotEmpty() -> nums[0].toFloat().coerceIn(0f, 100f)
            else -> null
        }
    }
}

/** Reads one [StatSample] per call; keeps the previous counters for the rates. Not thread-safe. */
class SystemStatsReader(context: Context) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private var lastAt = 0L
    private var lastTicks: Long? = null
    private var lastIo: Pair<Long, Long>? = null
    private var lastThermalAt = 0L
    private var lastThermal: Float? = null

    /** The first GPU file that read as a number; null after it fails once (SELinux usually blocks them). */
    private var gpuFile: File? = GPU_FILES.map(::File).firstOrNull { f -> readGpu(f) != null }

    fun sample(): StatSample {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastAt).coerceAtLeast(1L) / 1000f
        val first = lastAt == 0L
        lastAt = now

        val ticks = read("/proc/self/stat")?.let(StatParsers::cpuTicks)
        val cpu = if (!first && ticks != null) lastTicks?.let { prev ->
            ((ticks - prev).toFloat() / clkTck / dt / cores * 100f).coerceIn(0f, 100f)
        } else null
        lastTicks = ticks

        val io = read("/proc/self/io")?.let(StatParsers::ioBytes)
        val (rKbs, wKbs) = if (!first && io != null) lastIo?.let { prev ->
            ((io.first - prev.first).coerceAtLeast(0) / 1024f / dt) to ((io.second - prev.second).coerceAtLeast(0) / 1024f / dt)
        } ?: (null to null) else (null to null)
        lastIo = io

        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        // getThermalHeadroom returns NaN when called more than about once per second
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && now - lastThermalAt >= THERMAL_EVERY_MS) {
            lastThermalAt = now
            lastThermal = pm.getThermalHeadroom(0).takeIf { !it.isNaN() } ?: lastThermal
        }
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else null

        val gpu = gpuFile?.let { f -> readGpu(f).also { if (it == null) gpuFile = null } }

        return StatSample(
            cpuPct = cpu,
            appMb = read("/proc/self/status")?.let(StatParsers::rssKb)?.let { it / 1024f },
            freeMb = mi.availMem / 1_048_576f,
            readKbs = rKbs,
            writeKbs = wKbs,
            thermal = lastThermal,
            thermalStatus = status,
            gpuPct = gpu,
        )
    }

    private fun readGpu(f: File): Float? = runCatching { StatParsers.gpuBusy(f.readText()) }.getOrNull()

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    companion object {
        private const val THERMAL_EVERY_MS = 2_000L

        /** Adreno (Snapdragon), Mali / Xclipse (Samsung Exynos) and generic devfreq locations. */
        val GPU_FILES = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
        )
    }
}

private const val HISTORY = 60
private const val SAMPLE_MS = 1_000L

/** Live graphs of Murem's CPU, memory and disk use plus the phone's temperature (and GPU where readable). */
@Composable
fun SystemStatsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf(true) }
    var history by remember { mutableStateOf(emptyList<StatSample>()) }

    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        val reader = SystemStatsReader(context.applicationContext)
        while (isActive) {
            val s = withContext(Dispatchers.IO) { reader.sample() }
            history = (history + s).takeLast(HISTORY)
            delay(SAMPLE_MS)
        }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { shown = !shown }) {
            Text(
                stringResource(if (shown) R.string.stats_hide else R.string.stats_show),
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(if (shown) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        AnimatedVisibility(visible = shown) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatsGrid(history)
            }
        }
    }
}

@Composable
private fun StatsGrid(history: List<StatSample>) {
    val last = history.lastOrNull()
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        add { m ->
            StatTile(
                label = stringResource(R.string.stats_cpu),
                value = last?.cpuPct?.let { pct(it) } ?: DASH,
                series = listOf(history.map { it.cpuPct } to primary),
                max = 100f,
                modifier = m,
            )
        }
        add { m ->
            val mb = history.mapNotNull { it.appMb }
            StatTile(
                label = stringResource(R.string.stats_memory),
                value = last?.appMb?.let { mb(it) } ?: DASH,
                sub = last?.let { stringResource(R.string.stats_free, mb(it.freeMb)) },
                series = listOf(history.map { it.appMb } to primary),
                max = (mb.maxOrNull() ?: 1f) * 1.2f,
                modifier = m,
            )
        }
        add { m ->
            val peak = history.flatMap { listOfNotNull(it.readKbs, it.writeKbs) }.maxOrNull() ?: 1f
            StatTile(
                label = stringResource(R.string.stats_disk),
                value = last?.let { stringResource(R.string.stats_disk_value, rate(it.readKbs), rate(it.writeKbs)) } ?: DASH,
                series = listOf(history.map { it.readKbs } to primary, history.map { it.writeKbs } to tertiary),
                max = peak.coerceAtLeast(64f) * 1.2f,
                modifier = m,
            )
        }
        if (history.any { it.thermal != null || it.thermalStatus != null }) add { m ->
            StatTile(
                label = stringResource(R.string.stats_temperature),
                value = stringResource(thermalLabel(last?.thermalStatus)),
                sub = last?.thermal?.let { stringResource(R.string.stats_headroom, pct(it * 100f)) },
                series = listOf(history.map { it.thermal?.times(100f) } to thermalColor(last?.thermalStatus)),
                max = 100f,
                modifier = m,
            )
        }
        if (history.any { it.gpuPct != null }) add { m ->
            StatTile(
                label = stringResource(R.string.stats_gpu),
                value = last?.gpuPct?.let { pct(it) } ?: DASH,
                series = listOf(history.map { it.gpuPct } to primary),
                max = 100f,
                modifier = m,
            )
        }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
        Text(
            stringResource(R.string.stats_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    series: List<Pair<List<Float?>, Color>>,
    max: Float,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value" }) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
        val grid = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            val step = size.width / (HISTORY - 1)
            series.forEach { (values, color) ->
                val path = Path()
                var started = false
                val offset = HISTORY - values.size
                values.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    val x = (offset + i) * step
                    val y = size.height * (1f - (v / max).coerceIn(0f, 1f))
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

private const val DASH = "–"

private fun pct(v: Float) = String.format(Locale.getDefault(), "%.0f%%", v)

private fun mb(v: Float) =
    if (v >= 1024f) String.format(Locale.getDefault(), "%.1f GB", v / 1024f)
    else String.format(Locale.getDefault(), "%.0f MB", v)

private fun rate(kbs: Float?): String = when {
    kbs == null -> DASH
    kbs >= 1024f -> String.format(Locale.getDefault(), "%.1f MB/s", kbs / 1024f)
    else -> String.format(Locale.getDefault(), "%.0f KB/s", kbs)
}

private fun thermalLabel(status: Int?): Int = when (status) {
    null, PowerManager.THERMAL_STATUS_NONE -> R.string.stats_thermal_normal
    PowerManager.THERMAL_STATUS_LIGHT -> R.string.stats_thermal_warm
    PowerManager.THERMAL_STATUS_MODERATE -> R.string.stats_thermal_hot
    else -> R.string.stats_thermal_throttling
}

@Composable
private fun thermalColor(status: Int?): Color = when (status) {
    null, PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}
