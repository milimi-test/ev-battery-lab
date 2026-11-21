package rs.battery.lab

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal data class ChartSignal(val key: String, val label: String, val unit: String, val color: Color,
    val points: List<ChartPoint>, val decimals: Int = 1)

private val chartMuted = Color(0xFFACC0D0)
private val chartCyan = Color(0xFF52DAF3)
private val chartLine = Color(0xFF294151)
private fun chartFormat(value: Double?, digits: Int) = value?.let { "%.${digits}f".format(Locale.US, it) } ?: "—"
private fun clock(time: Long, date: Boolean = false) = SimpleDateFormat(if (date) "dd.MM. HH:mm:ss" else "HH:mm:ss", Locale.US).format(Date(time))

@Composable
internal fun ChartExplorer(signals: List<ChartSignal>, defaults: List<String> = signals.take(2).map { it.key }) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(defaults) } }
    var separated by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf<Long?>(15 * 60_000L) }
    var manualStart by remember { mutableStateOf<Long?>(null) }
    var cursor by remember { mutableStateOf<Long?>(null) }
    val visible = signals.filter { it.key in selected }
    val times = remember(visible) { visible.flatMap { it.points }.filter { it.value != null }.map { it.time }.distinct().sorted() }
    val first = times.firstOrNull() ?: 0L
    val last = times.lastOrNull() ?: first
    val total = (last - first).coerceAtLeast(1L)
    val span = (duration ?: total).coerceIn(1L, total)
    val start = (manualStart ?: (last - span)).coerceIn(first, (last - span).coerceAtLeast(first))
    val end = start + span
    val at = (cursor ?: last).coerceIn(start, end)
    val windowStats = remember(visible, start, end) { visible.associate { signal ->
        val values = signal.points.filter { it.time in start..end }.mapNotNull { it.value }
        signal.key to (values.minOrNull() to values.maxOrNull())
    } }
    fun setDuration(next: Long?) {
        duration = next
        val width = (next ?: total).coerceIn(1, total)
        manualStart = if (next == null) null else (at - width / 2).coerceIn(first, (last - width).coerceAtLeast(first))
    }
    val onPoint: (Long) -> Unit = { target ->
        // Snap to a real timestamp, never invent an interpolated value.
        val i = times.binarySearch(target)
        val pick = if (i >= 0) times[i] else listOfNotNull(times.getOrNull(-i - 2), times.getOrNull(-i - 1)).minByOrNull { abs(it - target) }
        cursor = (pick?.takeIf { abs(it - target) <= 30_000 } ?: target).coerceIn(start, end)
    }
    val content: @Composable (Boolean) -> Unit = { full ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LText("POREĐENJE MERENJA", color = chartCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                ChartButton(if (full) "ZATVORI" else "UVEĆAJ ↗") { enlarged = !full }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartButton("Parametri (${selected.size})", Modifier.weight(1f), choose) { choose = !choose }
                if (signals.size > 1) ChartButton(if (separated) "Preklopi" else "Odvojeno", Modifier.weight(1f), separated) { separated = !separated }
            }
            if (choose) {
              LText("Izaberite signale • lista se pomera", color = chartMuted, fontSize = 11.sp)
              Column(Modifier.fillMaxWidth().heightIn(max = 290.dp).verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(6.dp)) {
                signals.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { s ->
                            val available = s.points.any { it.value != null }
                            ChartButton("${if (s.key in selected) "✓ " else "+ "}${s.label}\n${s.unit}${if (available) "" else " · bez podataka"}", Modifier.weight(1f), s.key in selected, s.color) {
                                if (s.key in selected) selected.remove(s.key) else selected.add(s.key)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
              }
            }
            if (times.size < 2) {
                LText("Za izabrane parametre potrebna su najmanje dva stvarna očitavanja.", color = chartMuted, fontSize = 13.sp)
            } else {
                LText("${clock(start, true)}  →  ${clock(end, true)}", color = chartMuted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartButton("Sve", Modifier.weight(1f), active = duration == null) { setDuration(null) }
                    listOf(15L to "15m", 5L to "5m", 1L to "1m").forEach { (minutes, label) ->
                        ChartButton(label, Modifier.weight(1f), active = duration == minutes * 60_000) { setDuration(minutes * 60_000) }
                    }
                    ChartButton("+", Modifier.weight(1f)) { setDuration((span / 2).coerceAtLeast(10_000).coerceAtMost(total)) }
                }
                if (visible.size > 2 && !separated) LText("Preklop: svaki signal je skaliran od 0 do 100% svog raspona u ovom intervalu. Tačne vrednosti su ispod.", color = Color(0xFFFFC77C), fontSize = 11.sp)
                else LText("Dodirni ili povuci prst vodoravno za tačne vrednosti.", color = chartMuted, fontSize = 11.sp)
                LText("${clock(at)} · očitane vrednosti", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                visible.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { signal ->
                            val p = nearestChartPoint(signal.points, at)?.takeIf { it.time in start..end }
                            Column(Modifier.weight(1f).background(Color(0xFF112B3B), RoundedCornerShape(8.dp)).padding(9.dp)) {
                                LText(signal.label, color = signal.color, fontSize = 10.sp)
                                LText("${chartFormat(p?.value, signal.decimals)} ${signal.unit}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (separated) visible.forEach { s ->
                    LText("● ${s.label} · ${s.unit}", color = s.color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    MeasurementPlot(listOf(s), start, end, at, false, if (full) 175 else 150, onPoint)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (visible.size <= 2) visible.forEach { LText("● ${it.label} (${it.unit})", modifier = Modifier.weight(1f), color = it.color, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        else LText("Relativni raspon (%)", color = chartMuted, fontSize = 12.sp)
                    }
                    MeasurementPlot(visible, start, end, at, visible.size > 2, if (full) 320 else 250, onPoint)
                }
                if (span < total) {
                    LText("Pomeri vremenski prozor", color = chartMuted, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ChartButton("←") { manualStart = (start - span / 2).coerceAtLeast(first) }
                        Slider(value = ((start - first).toDouble() / (total - span)).toFloat().coerceIn(0f, 1f),
                            onValueChange = { manualStart = first + ((total - span) * it.toDouble()).toLong() }, modifier = Modifier.weight(1f))
                        ChartButton("→") { manualStart = (start + span / 2).coerceAtMost(last - span) }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Color(0xFF112B3B), RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        LText("IZABRANO ${clock(at)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        LText("${SimpleDateFormat("dd.MM.", Locale.US).format(Date(at))}", color = chartMuted, fontSize = 11.sp)
                    }
                    visible.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { signal ->
                                val sample = nearestChartPoint(signal.points, at)?.takeIf { it.time in start..end }
                                val range = windowStats[signal.key]
                                Column(Modifier.weight(1f).background(Color(0xFF081D2A), RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LText("● ${signal.label}", color = signal.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    LText(chartFormat(sample?.value, signal.decimals), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    LText(signal.unit, color = signal.color, fontSize = 12.sp)
                                    LText(sample?.let { "Očitano ${clock(it.time)}" } ?: "Nema uzorka blizu kursora", color = chartMuted, fontSize = 10.sp)
                                    LText("MIN ${chartFormat(range?.first, signal.decimals)}\nMAX ${chartFormat(range?.second, signal.decimals)}", color = chartMuted, fontSize = 10.sp)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                LText("MIN/MAX važe za prikazani interval. Kursor prikazuje najbliže stvarno očitavanje (do 30 s), sa njegovim vremenom. Prekidi duži od 60 s nisu spojeni.", color = chartMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
    content(false)
    if (enlarged) Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF07131F)).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp)) { content(true) }
    }
}

@Composable
private fun ChartButton(text: String, modifier: Modifier = Modifier, active: Boolean = false, color: Color = chartCyan, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).background(if (active) Color(0xFF164455) else Color(0xFF102634), RoundedCornerShape(8.dp))
        .border(1.dp, if (active) color else chartLine, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        LText(text, color = if (active) color else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MeasurementPlot(signals: List<ChartSignal>, start: Long, end: Long, cursor: Long, relative: Boolean, height: Int, onPoint: (Long) -> Unit) {
    val density = LocalDensity.current
    val left = with(density) { 53.dp.toPx() }
    val right = with(density) { (if (signals.size == 2 && !relative) 53 else 12).dp.toPx() }
    var width by remember { mutableIntStateOf(1) }
    val update by rememberUpdatedState(onPoint)
    val rangePoints = remember(signals, start, end) { signals.map { signal -> signal.points.filter { it.time in start..end } } }
    val bounds = remember(rangePoints) { rangePoints.map(::chartBounds) }
    val segments = remember(rangePoints) { rangePoints.map { chartSegments(it).map { segment -> chartEnvelope(segment) } } }
    val choose: (Float) -> Unit = { x -> update(start + ((x - left) / (width - left - right).coerceAtLeast(1f)).coerceIn(0f, 1f).toDouble().times(end - start).toLong()) }
    val currentChoose by rememberUpdatedState(choose)
    Canvas(Modifier.fillMaxWidth().height(height.dp).background(Color(0xFF071A27), RoundedCornerShape(8.dp))
        .onSizeChanged { width = it.width }
        .pointerInput(start, end) { detectTapGestures(onTap = { currentChoose(it.x) }) }
        .pointerInput(start, end) { detectHorizontalDragGestures { change, _ -> change.consume(); currentChoose(change.position.x) } }) {
        val top = 12.dp.toPx(); val bottom = size.height - 32.dp.toPx()
        val plotW = (size.width - left - right).coerceAtLeast(1f); val plotH = bottom - top
        fun x(t: Long) = left + ((t - start).toDouble() / (end - start).coerceAtLeast(1)).toFloat() * plotW
        fun y(v: Double, b: ChartBounds) = bottom - ((v - b.low) / (b.high - b.low)).toFloat() * plotH
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = chartMuted.toArgb() }
        repeat(5) { tick ->
            val fraction = tick / 4.0
            val lineY = bottom - (plotH * fraction).toFloat()
            drawLine(chartLine, Offset(left, lineY), Offset(left + plotW, lineY), 1.dp.toPx())
            if (signals.isNotEmpty()) {
                val b = bounds[0]
                val number = if (relative) chartFormat(fraction * 100, 0) else if (rangePoints[0].none { it.value != null }) "—" else chartFormat(b.low + (b.high - b.low) * fraction, signals[0].decimals)
                paint.textAlign = Paint.Align.RIGHT; paint.color = (if (relative) chartMuted else signals[0].color).toArgb()
                drawContext.canvas.nativeCanvas.drawUiText(number, left - 7.dp.toPx(), lineY + 3.dp.toPx(), paint)
                if (signals.size == 2 && !relative) {
                    val b2 = bounds[1]; paint.textAlign = Paint.Align.LEFT; paint.color = signals[1].color.toArgb()
                    drawContext.canvas.nativeCanvas.drawUiText(if (rangePoints[1].none { it.value != null }) "—" else chartFormat(b2.low + (b2.high - b2.low) * fraction, signals[1].decimals), left + plotW + 7.dp.toPx(), lineY + 3.dp.toPx(), paint)
                }
            }
        }
        signals.forEachIndexed { index, signal ->
            val b = if (relative) {
                val vals = rangePoints[index].mapNotNull { it.value }; val lo = vals.minOrNull() ?: 0.0; val hi = vals.maxOrNull() ?: 1.0
                if (hi == lo) ChartBounds(lo - .5, hi + .5) else ChartBounds(lo, hi)
            } else bounds[index]
            segments[index].forEach { segment ->
                if (segment.size == 1) drawCircle(signal.color, 2.dp.toPx(), Offset(x(segment[0].time), y(segment[0].value!!, b)))
                else {
                    val path = Path(); segment.forEachIndexed { i, point -> if (i == 0) path.moveTo(x(point.time), y(point.value!!, b)) else path.lineTo(x(point.time), y(point.value!!, b)) }
                    drawPath(path, signal.color, style = Stroke(2.dp.toPx()))
                }
            }
            nearestChartPoint(signal.points, cursor)?.takeIf { it.time in start..end }?.let { sample ->
                val pos = Offset(x(sample.time), y(sample.value!!, b))
                drawCircle(Color(0xFF071A27), 5.dp.toPx(), pos); drawCircle(signal.color, 4.dp.toPx(), pos, style = Stroke(2.dp.toPx()))
            }
        }
        drawLine(Color.White.copy(alpha = .8f), Offset(x(cursor), top), Offset(x(cursor), bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        listOf(start, start + (end - start) / 2, end).forEachIndexed { i, time ->
            paint.color = chartMuted.toArgb(); paint.textAlign = if (i == 0) Paint.Align.LEFT else if (i == 2) Paint.Align.RIGHT else Paint.Align.CENTER
            val pattern = if (end - start >= 24 * 60 * 60_000) "dd.MM" else if (end - start < 5 * 60_000) "HH:mm:ss" else "HH:mm"
            drawContext.canvas.nativeCanvas.drawUiText(SimpleDateFormat(pattern, Locale.US).format(Date(time)), if (i == 0) left else if (i == 2) left + plotW else left + plotW / 2, size.height - 9.dp.toPx(), paint)
        }
    }
}
