package com.musicremover.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    private var lastThermalAt = 0L
    private var lastThermal: Float? = null

    /** The first GPU file that read as a number; null after it fails once (SELinux blocks them on many phones). */
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
            thermal = lastThermal,
            thermalStatus = status,
            gpuPct = gpu,
        )
    }

    private fun readGpu(f: File): Float? = runCatching { StatParsers.gpuBusy(f.readText()) }.getOrNull()

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    companion object {
        private const val THERMAL_EVERY_MS = 2_000L

        /** Adreno (Snapdragon), Mali / Xclipse (Samsung Exynos) and generic locations. */
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

/** One tile's data: what it shows and its graph (values 0..[max]). */
private data class Tile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val sub: String?,
    val values: List<Float?>,
    val max: Float,
    val color: Color,
)

/** Live graphs of the GPU (where readable), Murem's CPU and memory, and the phone's temperature. */
@Composable
fun SystemStatsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    var history by remember { mutableStateOf(emptyList<StatSample>()) }

    // Samples keep running while collapsed so the graphs are full when it opens again
    LaunchedEffect(Unit) {
        val reader = SystemStatsReader(context.applicationContext)
        while (isActive) {
            val s = withContext(Dispatchers.IO) { reader.sample() }
            history = (history + s).takeLast(HISTORY)
            delay(SAMPLE_MS)
        }
    }

    val tiles = tiles(history)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        val expandLabel = stringResource(if (expanded) R.string.stats_hide else R.string.stats_show)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = expandLabel) { expanded = !expanded }
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot()
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.stats_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            // Collapsed: the current values on one line
            Text(
                if (expanded) "" else tiles.joinToString("  ·  ") { "${it.label} ${it.value}" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = expandLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { StatTile(it, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun tiles(history: List<StatSample>): List<Tile> {
    val last = history.lastOrNull()
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    return buildList {
        if (history.any { it.gpuPct != null }) add(
            Tile(
                Icons.Outlined.DeveloperBoard, stringResource(R.string.stats_gpu),
                last?.gpuPct?.let(::pct) ?: DASH, null,
                history.map { it.gpuPct }, 100f, tertiary,
            ),
        )
        add(
            Tile(
                Icons.Outlined.Memory, stringResource(R.string.stats_cpu),
                last?.cpuPct?.let(::pct) ?: DASH, null,
                history.map { it.cpuPct }, 100f, primary,
            ),
        )
        val mbs = history.mapNotNull { it.appMb }
        add(
            Tile(
                Icons.Outlined.Storage, stringResource(R.string.stats_memory),
                last?.appMb?.let(::mb) ?: DASH,
                last?.let { stringResource(R.string.stats_free, mb(it.freeMb)) },
                history.map { it.appMb }, (mbs.maxOrNull() ?: 1f) * 1.25f, primary,
            ),
        )
        if (history.any { it.thermal != null || it.thermalStatus != null }) add(
            Tile(
                Icons.Outlined.Thermostat, stringResource(R.string.stats_temperature),
                stringResource(thermalLabel(last?.thermalStatus)),
                last?.thermal?.let { stringResource(R.string.stats_headroom, pct(it * 100f)) },
                history.map { it.thermal?.times(100f) }, 100f, thermalColor(last?.thermalStatus),
            ),
        )
    }
}

@Composable
private fun StatTile(tile: Tile, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(tile.label, tile.value, tile.sub).joinToString(", ")
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, null, tint = tile.color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                tile.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tile.value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        // Same height with or without a second line, so the graphs line up across a row
        Text(
            tile.sub ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Sparkline(tile.values, tile.max, tile.color, Modifier.fillMaxWidth().height(40.dp))
    }
}

/** Line with a soft fill under it and a dot on the newest value; newest sample at the right edge. */
@Composable
private fun Sparkline(values: List<Float?>, max: Float, color: Color, modifier: Modifier) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val step = size.width / (HISTORY - 1)
        val offset = HISTORY - values.size
        fun y(v: Float) = size.height * (1f - (v / max).coerceIn(0f, 1f))
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

        // Segments between missing samples
        val segments = mutableListOf<List<Offset>>()
        var current = mutableListOf<Offset>()
        values.forEachIndexed { i, v ->
            if (v == null) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            } else {
                current += Offset((offset + i) * step, y(v))
            }
        }
        if (current.isNotEmpty()) segments += current

        val fill = Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)))
        segments.forEach { pts ->
            if (pts.size >= 2) {
                val area = Path().apply {
                    moveTo(pts.first().x, size.height)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, size.height)
                    close()
                }
                drawPath(area, fill)
                val line = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        segments.lastOrNull()?.lastOrNull()?.let { drawCircle(color, radius = 3.dp.toPx(), center = it) }
    }
}

/** A small dot that fades in and out, so the card reads as live. */
@Composable
private fun LiveDot() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(SAMPLE_MS)
            on = !on
        }
    }
    val alpha by animateFloatAsState(if (on) 1f else 0.35f, label = "liveDot")
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

private const val DASH = "–"

private fun pct(v: Float) = String.format(Locale.getDefault(), "%.0f%%", v)

private fun mb(v: Float) =
    if (v >= 1024f) String.format(Locale.getDefault(), "%.1f GB", v / 1024f)
    else String.format(Locale.getDefault(), "%.0f MB", v)

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
