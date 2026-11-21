package rs.battery.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.*

internal data class RouteHeightPoint(val km: Double, val meters: Double?)
internal data class PlannedHeights(val points: List<RouteHeightPoint>, val stopKm: List<Double>,
    val distanceKm: Double, val ascent: Double, val descent: Double, val coverage: Double)

/** Sample each road leg separately so an off-road connector never invents an elevation trace. */
internal fun plannedHeights(legs: List<RoadPlan>, heightAt: (Double, Double) -> Double?): PlannedHeights {
    val points = ArrayList<RouteHeightPoint>()
    val stops = ArrayList<Double>()
    var offset = 0.0
    var ascent = 0.0
    var descent = 0.0
    var coveredKm = 0.0
    for ((index, leg) in legs.withIndex()) {
        if (index > 0) points.add(RouteHeightPoint(offset, null))
        val profile = terrainProfile(leg.points, heightAt)
        ascent += profile.ascent; descent += profile.descent
        var walked = 0.0
        var next = 0.0
        for (i in 1 until leg.points.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val a = leg.points[i - 1]; val b = leg.points[i]
            val length = routeKm(a.lat, a.lon, b.lat, b.lon)
            if (length <= 0) continue
            while (next < walked + length) {
                val f = ((next - walked) / length).coerceIn(0.0, 1.0)
                val height = heightAt(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                points.add(RouteHeightPoint(offset + next, height?.takeIf(Double::isFinite)))
                next += .2
            }
            walked += length
        }
        leg.points.lastOrNull()?.let {
            points.add(RouteHeightPoint(offset + walked, heightAt(it.lat, it.lon)?.takeIf(Double::isFinite)))
        }
        coveredKm += walked * profile.coverage
        offset += walked
        if (index < legs.lastIndex) stops.add(offset)
    }
    return PlannedHeights(points, stops, offset, ascent, descent,
        if (offset > 0) (coveredKm / offset).coerceIn(0.0, 1.0) else 0.0)
}

@Composable internal fun PlannedElevationProfile(data: PlannedHeights, stops: List<ChargingStop>) {
    val cyan = Color(0xFF32D8F0)
    val muted = Color(0xFFB0C3D0)
    val amber = Color(0xFFFFD567)
    val valid = remember(data) { data.points.mapNotNull { it.meters } }
    var help by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText("Profil planirane rute", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        MetricInfoIcon("Profil visine") { help = true }
    }
    if (help) MetricHelpDialog("Profil visine",
        "Dodirni liniju profila za kilometražu i nadmorsku visinu izabrane tačke. Žute oznake su planirane dopune.\n\nProfil koristi offline model terena Copernicus GLO-90, uz uzorkovanje trase na 200 m. To je visina terena, pa mostovi i tuneli mogu odstupati. Praznine u modelu nisu spojene linijom. Uspon i pad koriste filtrirane promene visine.") { help = false }
    if (valid.size < 2 || data.distanceKm <= 0) {
        LText("Model nema dovoljno visina za ovu rutu.", color = muted, fontSize = 14.sp, lineHeight = 20.sp)
        return
    }
    fun number(v: Double, digits: Int = 0) = String.format(AppLanguage.locale, "%.${digits}f", v)
    val minimum = valid.minOrNull()!!
    val maximum = valid.maxOrNull()!!
    val low = floor((minimum - 5) / 10) * 10
    val high = max(ceil((maximum + 5) / 10) * 10, low + 20)
    var selected by remember(data) { mutableDoubleStateOf(0.0) }
    val picked = data.points.minBy { abs(it.km - selected) }
    LText(if (data.coverage >= .98) "Uspon ↑ ${number(data.ascent)} m · pad ↓ ${number(data.descent)} m"
        else "Model pokriva ${number(data.coverage * 100)}% rute · praznine nisu spojene", color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
    LText("${number(picked.km, 1)} km · ${picked.meters?.let { number(it) + " m n.v." } ?: "visina nije dostupna"}",
        color = amber, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 6.dp))
    LText("Visina terena (m) · dodirni profil", color = muted, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(top = 4.dp))
    val axisStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisLabels = listOf(number(high), number((high + low) / 2), number(low))
    val labelSizes = axisLabels.map { measurer.measure(it, style = axisStyle).size }
    val gutter = with(density) { labelSizes.maxOf { it.width }.toDp() } + 10.dp
    val labelHalfHeight = labelSizes.maxOf { it.height } / 2f
    val kmLabels = listOf("0 km", "${number(data.distanceKm / 2, 1)} km", "${number(data.distanceKm, 1)} km")
    val allKmLabelsWidth = with(density) { kmLabels.sumOf { measurer.measure(it, style = axisStyle).size.width }.toDp() } + 24.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showMiddleKm = maxWidth - gutter >= allKmLabelsWidth
        Column {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.width(gutter).height(184.dp).padding(end = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    axisLabels.forEach { LText(it, color = muted, style = axisStyle, maxLines = 1, softWrap = false) }
                }
                Canvas(Modifier.weight(1f).height(184.dp).background(Color(0xFF071826))
                    .semantics {
                        contentDescription = UiStrings.text("Izaberi tačku profila visine")
                        stateDescription = UiStrings.text("${number(picked.km, 1)} kilometara, ${picked.meters?.let { number(it) + " metara" } ?: "visina nije dostupna"}")
                        progressBarRangeInfo = ProgressBarRangeInfo(picked.km.toFloat(), 0f..data.distanceKm.toFloat())
                        setProgress { km -> selected = km.toDouble().coerceIn(0.0, data.distanceKm); true }
                    }
                    .pointerInput(data) { detectTapGestures {
                        val inset = 5.dp.toPx()
                        selected = ((it.x - inset) / (size.width - inset * 2) * data.distanceKm).coerceIn(0.0, data.distanceKm)
                    } }) {
                    val inset = 5.dp.toPx()
                    fun x(km: Double) = inset + (km / data.distanceKm * (size.width - inset * 2)).toFloat()
                    fun y(meters: Double) = labelHalfHeight + ((high - meters) / (high - low) * (size.height - labelHalfHeight * 2)).toFloat()
                    for (f in listOf(0f, .5f, 1f)) {
                        val lineY = labelHalfHeight + (size.height - labelHalfHeight * 2) * f
                        drawLine(Color(0xFF1A4051), Offset(inset, lineY), Offset(size.width - inset, lineY))
                    }
                    data.points.zipWithNext().forEach { (a, b) ->
                        if (a.meters != null && b.meters != null) drawLine(cyan, Offset(x(a.km), y(a.meters)), Offset(x(b.km), y(b.meters)), 2.dp.toPx())
                    }
                    data.stopKm.forEach { km ->
                        drawLine(amber.copy(alpha = .5f), Offset(x(km), labelHalfHeight), Offset(x(km), size.height - labelHalfHeight), 1.dp.toPx())
                        drawCircle(amber, 4.dp.toPx(), Offset(x(km), labelHalfHeight))
                    }
                    drawLine(Color.White.copy(alpha = .55f), Offset(x(picked.km), labelHalfHeight), Offset(x(picked.km), size.height - labelHalfHeight))
                    picked.meters?.let { drawCircle(amber, 4.dp.toPx(), Offset(x(picked.km), y(it))) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = gutter, top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                LText(kmLabels.first(), color = muted, style = axisStyle, maxLines = 1, softWrap = false)
                if (showMiddleKm) LText(kmLabels[1], color = muted, style = axisStyle, maxLines = 1, softWrap = false)
                LText(kmLabels.last(), color = muted, style = axisStyle, maxLines = 1, softWrap = false)
            }
        }
    }
    data.stopKm.forEachIndexed { i, km ->
        LText("● ${i + 1}. ${stops.getOrNull(i)?.station?.name ?: "Dopuna"} · ${number(km, 1)} km", color = amber, fontSize = 13.sp, lineHeight = 19.sp)
    }
    Spacer(Modifier.height(8.dp))
}
