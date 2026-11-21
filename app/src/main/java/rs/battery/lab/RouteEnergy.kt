package rs.battery.lab

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.*

internal fun routeKm(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double {
    val dLat = Math.toRadians(lat1 - lat0)
    val dLon = Math.toRadians(lon1 - lon0)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat0)) * cos(Math.toRadians(lat1)) * sin(dLon / 2).pow(2)
    return 12742.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private val routeLocale = AppLanguage.locale
private fun number(value: Double, digits: Int) = String.format(routeLocale, "%.${digits}f", value)
private data class Segment(val a: RouteVertex, val b: RouteVertex, val km: Double, val start: Double)
private data class RoadBand(val name: String, val speed: Int, val text: String)
private val bands = listOf(
    RoadBand("auto-put", 90, "85–95"), RoadBand("glavni brzi pravac", 70, "65–75"),
    RoadBand("državni put", 60, "55–65"), RoadBand("sporedni put", 50, "45–55"),
    RoadBand("lokalni put", 40, "35–45"), RoadBand("ulica", 30, "25–35")
)

private fun segments(plan: RoadPlan): List<Segment> {
    val segments = ArrayList<Segment>(plan.points.size)
    var distance = 0.0
    for (i in 1 until plan.points.size) {
        val a = plan.points[i - 1]
        val b = plan.points[i]
        val km = routeKm(a.lat, a.lon, b.lat, b.lon)
        if (km <= 0 || !km.isFinite()) continue
        segments.add(Segment(a, b, km, distance))
        distance += km
    }
    return segments
}

private fun interpolate(s: Segment, km: Double): Pair<Double, Double> {
    val f = ((km - s.start) / s.km).coerceIn(0.0, 1.0)
    return s.a.lat + (s.b.lat - s.a.lat) * f to s.a.lon + (s.b.lon - s.a.lon) * f
}

internal data class RouteProfile(val ascent: Double, val descent: Double, val coverage: Double)

/** Fixed-distance sampling avoids counting extra curvature vertices as extra hilltops. */
internal fun terrainProfile(points: List<RouteVertex>, heightAt: (Double, Double) -> Double?): RouteProfile {
    var total = 0.0
    var covered = 0.0
    var ascent = 0.0
    var descent = 0.0
    var cursor = 0.0
    var prior: Double? = null
    var lastKm = 0.0
    var lastGood = false
    val samples = ArrayDeque<Double>(3)
    fun count(value: Double, endpoint: Boolean = false) {
        val previous = prior
        if (previous != null) {
            val diff = value - previous
            if (!endpoint && abs(diff) < 4) return
            if (diff > 0) ascent += diff else descent -= diff
        }
        prior = value
    }
    fun sample(lat: Double, lon: Double, km: Double, endpoint: Boolean = false) {
        val h = heightAt(lat, lon)?.takeIf { it.isFinite() }
        if (h == null) {
            samples.clear()
            prior = null
        } else {
            if (lastGood) covered += km - lastKm
            if (prior == null || endpoint) count(h, true)
            if (samples.size == 3) samples.removeFirst()
            samples.addLast(h)
            if (!endpoint && samples.size == 3) count(samples.sorted()[1])
        }
        lastGood = h != null
        lastKm = km
    }
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val length = routeKm(a.lat, a.lon, b.lat, b.lon)
        if (length <= 0) continue
        while (cursor < total + length) {
            val f = ((cursor - total) / length).coerceIn(0.0, 1.0)
            sample(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, cursor)
            cursor += .20
        }
        total += length
    }
    points.lastOrNull()?.let { sample(it.lat, it.lon, total, true) }
    return RouteProfile(ascent, descent, if (total > 0) (covered / total).coerceIn(0.0, 1.0) else 0.0)
}

private fun forecastSites(legs: List<Segment>, distance: Double): List<RouteWind.Site> {
    val count = ceil(distance / 35).toInt().coerceIn(1, 8)
    var index = 0
    return (0 until count).map { i ->
        val at = distance * (i + .5) / count
        while (index < legs.lastIndex && legs[index].start + legs[index].km < at) index++
        val (lat, lon) = interpolate(legs[index], at)
        RouteWind.site(lat, lon)
    }
}

internal fun routeWindAdjustmentKwh(distanceKm: Double, speedKmh: Int, headKmh: Double, crossKmh: Double): Double {
    val effectiveForward = (speedKmh + headKmh) / 3.6
    val air = hypot(effectiveForward, crossKmh / 3.6)
    val dragChange = .5 * 1.225 * .65 * (air * effectiveForward - (speedKmh / 3.6).pow(2))
    return dragChange * distanceKm / (3600 * .90)
}

internal fun routeEnergyKwh(distanceKm: Double, consumption: Double, terrain: RouteProfile, windKwh: Double): Double {
    val gravityKwhPerMeter = 1650 * 9.81 / 3_600_000.0
    val hills = if (terrain.coverage >= .98) gravityKwhPerMeter *
        (terrain.ascent / .90 - terrain.descent * .55) else 0.0
    return (consumption * distanceKm / 100 + hills + windKwh).coerceAtLeast(0.0)
}

private class WindSummary {
    var covered = 0.0
    var east = 0.0
    var north = 0.0
    var speedSum = 0.0
    var headSum = 0.0
    var energy = 0.0
    var timeMin = Long.MAX_VALUE
    var timeMax = Long.MIN_VALUE
    fun add(s: Segment, weather: RouteWind.Sample, speed: Int) {
        val dLon = Math.toRadians(s.b.lon - s.a.lon)
        val latA = Math.toRadians(s.a.lat)
        val latB = Math.toRadians(s.b.lat)
        val bearing = atan2(sin(dLon) * cos(latB),
            cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(dLon))
        val from = Math.toRadians(weather.from)
        val head = weather.speed * cos(from - bearing)
        val cross = weather.speed * sin(from - bearing)
        energy += routeWindAdjustmentKwh(s.km, speed, head, cross)
        east += sin(from) * weather.speed * s.km
        north += cos(from) * weather.speed * s.km
        speedSum += weather.speed * s.km
        headSum += head * s.km
        covered += s.km
        timeMin = min(timeMin, weather.at)
        timeMax = max(timeMax, weather.at)
    }
    fun describe(km: Double): String {
        if (covered <= 0) return "Vetar · nema dostupne prognoze na trasi. Uticaj vetra nije uračunat."
        val clock = SimpleDateFormat("dd.MM. HH:mm", routeLocale)
        val start = clock.format(Date(timeMin))
        val time = if (timeMin == timeMax) start else "$start – ${clock.format(Date(timeMax))}"
        val heading = if (hypot(east,north) < speedSum * .25) "promenljiv smer" else
            "iz pravca ${windCompass((Math.toDegrees(atan2(east, north)) + 360) % 360)}"
        val head = headSum / covered
        val component = if (abs(head) < 1) "uzdužna komponenta <1 km/h" else
            "${if (head >= 0) "čeona" else "leđna"} komponenta ${number(abs(head), 0)} km/h"
        return "Vetar · ${number(speedSum / covered, 0)} km/h, $heading. Prosečna $component. " +
            "Open-Meteo · $time · ${number((100 * covered / km).coerceIn(0.0,100.0), 0)}% trase."
    }
}

internal data class RouteAssessment(val energyKwh: Double?, val minutes: Int, val terrain: RouteProfile,
                                    val description: String, val windDescription: String = "",
                                    val speedDescription: String = "", val accessDescription: String = "")

internal fun routeAnalysis(context: Context, plan: RoadPlan, range: MapRangeEstimate?, weather: WeatherSample?,
                           now: Long = System.currentTimeMillis()): String =
    assessRoute(context, plan, range, weather, now).description

/** Estimates for departure at now. Call on an IO dispatcher; weather is optional and cached. */
internal fun assessRoute(context: Context, plan: RoadPlan, range: MapRangeEstimate?, weather: WeatherSample?,
                        now: Long = System.currentTimeMillis()): RouteAssessment {
    val legs = segments(plan)
    if (legs.isEmpty() || plan.distanceKm <= 0) return RouteAssessment(0.0, 0, RouteProfile(0.0, 0.0, 1.0), "Ista tačka.")
    val km = legs.last().start + legs.last().km
    val elevations = terrainProfile(plan.points) { lat, lon -> RouteTerrain.height(context, lat, lon) }
    val forecasts = RouteWind.fetch(forecastSites(legs, km), now)
    val wind = WindSummary()
    val classKm = DoubleArray(bands.size)
    var hours = 0.0
    for (s in legs) {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        val type = s.a.roadClass.coerceIn(0, bands.lastIndex)
        val speed = bands[type].speed
        classKm[type] += s.km
        val duration = s.km / speed
        val arrival = now + ((hours + duration / 2) * 3_600_000).toLong()
        val (lat, lon) = interpolate(s, s.start + s.km / 2)
        RouteWind.at(forecasts, weather, lat, lon, arrival, now)?.let { wind.add(s, it, speed) }
        hours += duration
    }
    val heightComplete = elevations.coverage >= .98
    val profile = if (heightComplete)
        "Uspon ↑ ${number(elevations.ascent, 0)} m   Pad ↓ ${number(elevations.descent, 0)} m"
    else "Visine · dostupno za ${number(elevations.coverage*100, 0)}% trase. Uspon i pad nisu uračunati."
    val battery = range?.consumption?.takeIf { it.isFinite() && it > 0 }?.let { usage ->
        val needed = routeEnergyKwh(plan.distanceKm, usage, elevations, wind.energy)
        val soc = range.soc?.takeIf { it in 0.0..100.0 }
        val capacity = range.usableKwh?.takeIf { it.isFinite() && it > 0 }
        val arrival = if (capacity != null && soc != null) soc - needed / capacity * 100 else null
        buildString {
            append("Energija ≈ ${number(needed, 1)} kWh")
            if (arrival != null) {
                when {
                    arrival < 0 -> append(" · potrebno dopunjavanje pre odredišta")
                    arrival < range.reserveSoc -> append(" · dolazak ≈ ${number(arrival, 0)}% (ispod rezerve ${range.reserveSoc}%)")
                    else -> append(" · dolazak ≈ ${number(arrival, 0)}%")
                }
            } else append(" · za SOC na dolasku potrebni su napunjenost i kapacitet")
            append("\nOsnova ${number(usage, 1)} kWh/100 km. Model mase 1.650 kg i okvirnog povrata energije na spustu.")
        }
    } ?: "Potrošnja i SOC na dolasku · potrebno je dovoljno snimljenih vožnji."
    val dominant = classKm.indices.filter { classKm[it] > 0 }.sortedByDescending { classKm[it] }.take(3)
    val speeds = dominant.joinToString(" · ") { "${bands[it].name} ${bands[it].text} km/h" }
    val windDescription = wind.describe(km)
    val speedDescription = "Ekonomičan tempo · $speeds. Prilagodi signalizaciji i uslovima."
    val accessDescription = if (plan.startSnapKm >= .05 || plan.endSnapKm >= .05)
        "Priključci do mreže puteva · početak ${number(plan.startSnapKm * 1000, 0)} m, " +
            "cilj ${number(plan.endSnapKm * 1000, 0)} m. Izvan obračuna." else ""
    val description = buildString {
        append("Putem ${number(plan.distanceKm, 1)} km\n$battery\n\n$profile")
        if (heightComplete) append("\nModel terena Copernicus GLO-90. Mostovi i tuneli mogu odstupati.")
        append("\n\n$windDescription\n\n$speedDescription")
        if (accessDescription.isNotBlank()) append("\n$accessDescription")
    }
    val energy = range?.consumption?.takeIf { it.isFinite() && it > 0 }?.let {
        routeEnergyKwh(plan.distanceKm, it, elevations, wind.energy)
    }
    return RouteAssessment(energy, (hours * 60).roundToInt(), elevations, description,
        windDescription, speedDescription, accessDescription)
}
