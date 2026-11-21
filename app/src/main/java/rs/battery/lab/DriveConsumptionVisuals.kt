package rs.battery.lab

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val comparisonCyan = Color(0xFF52DAF3)
private val comparisonAmber = Color(0xFFFFC078)
private val comparisonMuted = Color(0xFF9AB1C1)
private val comparisonGrid = Color(0xFF294151)

internal data class DrivingWindow(
    val endedAt: Long,
    val meanSpeedKmh: Double,
    val netKwhPer100Km: Double,
    val distanceKm: Double,
    val breakBefore: Boolean
)

internal data class DrivingEfficiency(
    val windows: List<DrivingWindow>,
    val coveredMs: Long,
    val tripMs: Long,
    val coveredDistanceKm: Double,
    val odometerDistanceKm: Double?,
    val stationaryMs: Long
) {
    val timeCoveragePercent: Double get() = if (tripMs > 0) (coveredMs * 100.0 / tripMs).coerceIn(0.0, 100.0) else 0.0
    val distanceCoveragePercent: Double? get() = odometerDistanceKm?.takeIf { it > 0.0 }?.let {
        (coveredDistanceKm * 100.0 / it).coerceIn(0.0, 100.0)
    }
}

/** Every plotted pair uses the same fresh speed and V×I interval. No division while stopped. */
internal fun drivingEfficiency(readings: List<BmsReading>, trip: TripRecord): DrivingEfficiency {
    val ordered = readings.filter { it.timestamp in trip.startedAt..trip.endedAt }
        .sortedBy { it.timestamp }.distinctBy { it.timestamp }
    val windows = mutableListOf<DrivingWindow>()
    var bucketEnd = 0L
    var bucketMs = 0L
    var bucketKm = 0.0
    var bucketKwh = 0.0
    var nextBreak = false
    var bucketBreak = false
    var coveredMs = 0L
    var coveredKm = 0.0
    var stationaryMs = 0L
    fun flush() {
        if (bucketMs >= 10_000 && bucketKm >= .04) {
            val speed = bucketKm * 3_600_000.0 / bucketMs
            val consumption = bucketKwh * 100.0 / bucketKm
            if (speed.isFinite() && consumption.isFinite())
                windows += DrivingWindow(bucketEnd, speed, consumption, bucketKm, bucketBreak)
        }
        bucketMs = 0L
        bucketKm = 0.0
        bucketKwh = 0.0
    }
    fun speed(reading: BmsReading): Double? = reading.speedKmh?.takeIf { value ->
        value.isFinite() && value in 0.0..200.0 &&
            reading.speedAt?.let { reading.timestamp - it in 0..15_000 } == true
    }
    ordered.zipWithNext().forEach { (before, after) ->
        val dt = after.timestamp - before.timestamp
        if (dt !in 1..60_000) { flush(); nextBreak = true; return@forEach }
        val s0 = speed(before)
        val s1 = speed(after)
        if (s0 != null && s1 != null && s0 < 10.0 && s1 < 10.0) stationaryMs += dt
        if (s0 == null || s1 == null || s0 < 10.0 || s1 < 10.0) { flush(); nextBreak = true; return@forEach }
        val p0 = before.powerKw?.takeIf { it.isFinite() && it in -200.0..200.0 }
        val p1 = after.powerKw?.takeIf { it.isFinite() && it in -200.0..200.0 }
        if (p0 == null || p1 == null) { flush(); nextBreak = true; return@forEach }
        val km = (s0 + s1) / 2.0 * dt / 3_600_000.0
        val kwh = (p0 + p1) / 2.0 * dt / 3_600_000.0
        if (km <= 0.0 || !km.isFinite() || !kwh.isFinite()) { flush(); nextBreak = true; return@forEach }
        if (bucketMs == 0L) { bucketBreak = nextBreak; nextBreak = false }
        bucketMs += dt
        bucketKm += km
        bucketKwh += kwh
        bucketEnd = after.timestamp
        coveredMs += dt
        coveredKm += km
        if (bucketMs >= 30_000 && bucketKm >= .08) flush()
    }
    flush()
    return DrivingEfficiency(windows, coveredMs,
        (trip.endedAt - trip.startedAt).coerceAtLeast(0), coveredKm, trip.distanceKm, stationaryMs)
}

@Composable internal fun TripEfficiencyComparison(samples: List<BmsReading>, trip: TripRecord?) {
    if (trip == null) {
        LText("Izaberite snimljenu vožnju za poređenje brzine i potrošnje.", color = comparisonMuted, fontSize = 12.sp)
        return
    }
    val result = remember(samples, trip.id, trip.endedAt) { drivingEfficiency(samples, trip) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComparisonStat("Pokriveno vreme", "%.0f %%".format(Locale.US, result.timeCoveragePercent), Modifier.weight(1f))
        ComparisonStat("Pokriveni km", result.distanceCoveragePercent?.let { "%.0f %%".format(Locale.US, it) } ?: "—",
            Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    LText("Plava: srednja brzina · narandžasta: neto kWh/100 km u istoj deonici.", color = Color.White,
        fontSize = 12.sp, lineHeight = 17.sp)
    LText("V×I i brzina iz BMS-a; rastojanje unutar deonice procenjeno iz brzine. Prikazuju se samo intervali sa svežim podacima i brzinom ≥10 km/h. Stajanje i prekidi nisu povezani.",
        color = comparisonMuted, fontSize = 10.sp, lineHeight = 14.sp)
    if (result.windows.size < 2) {
        Spacer(Modifier.height(8.dp))
        LText("Za trend treba bar dve deonice kretanja sa pouzdanom brzinom i snagom. Ukupna potrošnja vožnje je prikazana iznad kada je potvrđena.",
            color = comparisonAmber, fontSize = 12.sp)
        return
    }
    val speed = ChartSignal("drive_speed", "Srednja brzina", "km/h", comparisonCyan,
        result.windows.toChartPoints { it.meanSpeedKmh }, 1)
    val consumption = ChartSignal("drive_cons", "Neto potrošnja", "kWh/100 km", comparisonAmber,
        result.windows.toChartPoints { it.netKwhPer100Km }, 1)
    ChartExplorer(listOf(speed, consumption), listOf("drive_speed", "drive_cons"))
}

private fun List<DrivingWindow>.toChartPoints(value: (DrivingWindow) -> Double): List<ChartPoint> = buildList {
    this@toChartPoints.forEach { window ->
        if (window.breakBefore && isNotEmpty()) {
            val previous = last().time
            if (window.endedAt - previous > 1) add(ChartPoint(previous + (window.endedAt - previous) / 2, null))
        }
        add(ChartPoint(window.endedAt, value(window)))
    }
}

@Composable private fun ComparisonStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.border(1.dp, comparisonGrid, RoundedCornerShape(10.dp))
        .background(Color(0xFF081D2C), RoundedCornerShape(10.dp)).padding(10.dp)) {
        LText(label, color = comparisonMuted, fontSize = 10.sp)
        LText(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

private data class TripComparisonPoint(val trip: TripRecord, val meanSpeedKmh: Double, val netKwhPer100Km: Double)

@Composable internal fun TripHistoryComparison(trips: List<TripRecord>, selectedId: String?, onSelect: (String) -> Unit) {
    val qualified = remember(trips) { trips.mapNotNull { trip ->
        val km = trip.distanceKm ?: return@mapNotNull null
        val duration = trip.endedAt - trip.startedAt
        val energy = trip.consumptionKwhPer100Km ?: return@mapNotNull null
        if (km < 3.0 || duration < 180_000 || !energy.isFinite() || energy !in 0.0..60.0) return@mapNotNull null
        val speed = km * 3_600_000.0 / duration
        speed.takeIf { it in 3.0..150.0 }?.let { TripComparisonPoint(trip, it, energy) }
    }.takeLast(20) }
    if (qualified.isEmpty()) {
        LText("Poređenje će se pojaviti posle vožnje od bar 3 km sa potvrđenom neto energijom.",
            color = comparisonMuted, fontSize = 12.sp)
        return
    }
    LText("Svaka tačka je jedna stvarna vožnja. Srednja brzina uključuje stajanja; za kratke vožnje ispod 3 km odnos nije prikazan.",
        color = comparisonMuted, fontSize = 11.sp, lineHeight = 15.sp)
    Spacer(Modifier.height(7.dp))
    LText("Neto potrošnja (kWh/100 km)", color = comparisonAmber, fontSize = 11.sp)
    val maxSpeed = (qualified.maxOf { it.meanSpeedKmh } * 1.15).coerceAtLeast(30.0)
    val maxConsumption = (qualified.maxOf { it.netKwhPer100Km } * 1.15).coerceAtLeast(20.0)
    Canvas(Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF071826), RoundedCornerShape(10.dp))) {
        val left = 32.dp.toPx()
        val right = size.width - 10.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 23.dp.toPx()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.sp.toPx(); color = comparisonMuted.toArgb() }
        repeat(4) { row ->
            val fraction = row / 3f
            val y = bottom - fraction * (bottom - top)
            drawLine(comparisonGrid, Offset(left, y), Offset(right, y), 1f)
            paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawUiText("%.0f".format(Locale.US, maxConsumption * fraction), left - 5.dp.toPx(), y + 3.dp.toPx(), paint)
        }
        qualified.forEach { point ->
            val x = left + (point.meanSpeedKmh / maxSpeed * (right - left)).toFloat()
            val y = bottom - (point.netKwhPer100Km / maxConsumption * (bottom - top)).toFloat()
            val active = point.trip.id == selectedId
            drawCircle(if (active) comparisonAmber else comparisonCyan.copy(alpha = .78f),
                if (active) 5.dp.toPx() else 3.5.dp.toPx(), Offset(x, y))
            if (active) drawCircle(Color.White, 7.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        paint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawUiText("0", left, size.height - 6.dp.toPx(), paint)
        paint.textAlign = Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawUiText("%.0f km/h".format(Locale.US, maxSpeed), right, size.height - 6.dp.toPx(), paint)
    }
    Spacer(Modifier.height(8.dp))
    qualified.asReversed().take(8).forEach { point ->
        Row(Modifier.fillMaxWidth().clickable { onSelect(point.trip.id) }.padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LText(if (selectedId == point.trip.id) "●" else "○", color = if (selectedId == point.trip.id) comparisonAmber else comparisonMuted)
            LText(SimpleDateFormat("dd.MM. HH:mm", AppLanguage.locale).format(Date(point.trip.startedAt)),
                color = if (selectedId == point.trip.id) Color.White else comparisonMuted, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            LText("%.0f km/h".format(Locale.US, point.meanSpeedKmh), color = comparisonCyan, fontSize = 11.sp)
            LText("%.1f".format(Locale.US, point.netKwhPer100Km), color = comparisonAmber, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}
