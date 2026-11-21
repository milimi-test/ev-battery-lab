package rs.battery.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

internal data class GpsSummary(
    val distanceKm: Double,
    val elapsedMinutes: Long,
    val movingMinutes: Long,
    val maxSpeedKmh: Double,
    val ascentMeters: Double,
    val pointCount: Int
)

internal fun summarizeGps(points: List<GpsPoint>): GpsSummary? {
    val valid = points.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
        (it.accuracyMeters == null || it.accuracyMeters <= 60f) }.sortedBy { it.timeMillis }
    if (valid.isEmpty()) return null
    var distance = 0.0
    var movingMs = 0L
    var ascent = 0.0
    var maxSpeed = 0.0
    valid.zipWithNext().forEach { (a, b) ->
        val dt = b.timeMillis - a.timeMillis
        if (dt !in 1..120_000) return@forEach
        val meters = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
        val speed = meters / (dt / 1000.0)
        if (speed > 60.0) return@forEach // Reject GPS jumps.
        val noiseFloor = max(8.0, ((a.accuracyMeters ?: 8f) + (b.accuracyMeters ?: 8f)) * 0.75)
        if (meters < noiseFloor && (b.speedMetersPerSecond ?: 0f) < 1f) return@forEach
        distance += meters
        if (speed >= 1.5 || (b.speedMetersPerSecond ?: 0f) >= 1.5f) movingMs += dt
        val rise = (b.altitudeMeters ?: return@forEach) - (a.altitudeMeters ?: return@forEach)
        if (rise in 2.0..100.0) ascent += rise
    }
    valid.forEach { p -> p.speedMetersPerSecond?.let { if (it in 0f..60f) maxSpeed = max(maxSpeed, it * 3.6) } }
    val elapsed = (valid.last().timeMillis - valid.first().timeMillis).coerceAtLeast(0)
    return GpsSummary(distance / 1000.0, elapsed / 60_000, movingMs / 60_000, maxSpeed, ascent, valid.size)
}

private fun haversine(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
    val r = 6_371_000.0
    val a = Math.toRadians(latA)
    val b = Math.toRadians(latB)
    val dLat = b - a
    val dLon = Math.toRadians(lonB - lonA)
    val h = sin(dLat / 2).pow(2) + cos(a) * cos(b) * sin(dLon / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}

@Composable internal fun RouteChart(points: List<GpsPoint>) {
    val visible = points.filter { it.latitude.isFinite() && it.longitude.isFinite() &&
        (it.accuracyMeters == null || it.accuracyMeters <= 60f) }.takeLast(2000)
    if (visible.size < 2) {
        LText("Putanja će se pojaviti kada GPS zabeleži bar dve tačke.", color = Color(0xFF9AB1C1), fontSize = 12.sp)
        return
    }
    val midLat = visible.map { it.latitude }.average()
    val xValues = visible.map { it.longitude * cos(Math.toRadians(midLat)) }
    val yValues = visible.map { it.latitude }
    val minX = xValues.minOrNull() ?: 0.0
    val maxX = xValues.maxOrNull() ?: 1.0
    val minY = yValues.minOrNull() ?: 0.0
    val maxY = yValues.maxOrNull() ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFF071826))) {
        val margin = 20.dp.toPx()
        val w = (size.width - margin * 2).coerceAtLeast(1f)
        val h = (size.height - margin * 2).coerceAtLeast(1f)
        for (i in 1..3) {
            drawLine(Color(0xFF17384B), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
            drawLine(Color(0xFF17384B), Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 1f)
        }
        val spanX = (maxX - minX).coerceAtLeast(0.00001)
        val spanY = (maxY - minY).coerceAtLeast(0.00001)
        val path = Path()
        var first = Offset.Zero
        var last = Offset.Zero
        visible.indices.forEach { index ->
            val pos = Offset(
                margin + ((xValues[index] - minX) / spanX).toFloat() * w,
                margin + (1f - ((yValues[index] - minY) / spanY).toFloat()) * h
            )
            if (index == 0) { first = pos; path.moveTo(pos.x, pos.y) } else path.lineTo(pos.x, pos.y)
            last = pos
        }
        drawPath(path, Color(0xFF03B9FF), style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color(0xFF00DB88), 5.dp.toPx(), first)
        drawCircle(Color(0xFFFFB957), 5.dp.toPx(), last)
    }
    LText("● Polazak     ● Poslednja GPS tačka  •  skica putanje bez mape", color = Color(0xFF9AB1C1), fontSize = 10.sp)
}
