package rs.battery.lab

import android.content.Context
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

internal data class ChargingStop(val station: ChargingStation, val targetSoc: Int = 80)
internal data class PlannedJourney(val legs: List<RoadPlan>, val stops: List<ChargingStop>) {
    val combined: RoadPlan get() = RoadPlan(legs.flatMap { it.points }, legs.sumOf { it.distanceKm },
        legs.firstOrNull()?.startSnapKm ?: 0.0, legs.lastOrNull()?.endSnapKm ?: 0.0)
}

internal fun planJourney(context: Context, origin: GpsPoint, target: MapDestination,
                         stops: List<ChargingStop>): PlannedJourney? {
    val legs = ArrayList<RoadPlan>()
    var start = origin
    for (end in stops.map { it.station.destination } + target) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val leg = RoadRouter.route(context, start, end) ?: return null
        legs.add(leg)
        start = GpsPoint(origin.timeMillis, end.lat, end.lon, null, null, null)
    }
    return PlannedJourney(legs, stops)
}

internal data class RouteChargeEstimate(val kwh: Double, val minutes: Int, val assumedPower: Boolean)

internal fun routeChargeEstimate(arrival: Double, target: Int, capacity: Double,
                                 station: ChargingStation, vehicle: VehicleProfile? = null, confirmedCcs: Boolean = false): RouteChargeEstimate? {
    if (!arrival.isFinite() || arrival !in 0.0..100.0 || target !in 0..100 ||
        !capacity.isFinite() || capacity !in 8.0..80.0 || !station.compatible ||
        (station.powerKw != null && (!station.powerKw.isFinite() || station.powerKw <= 0))) return null
    val gain = (target - arrival).coerceAtLeast(0.0)
    if (gain == 0.0) return RouteChargeEstimate(0.0, 0, false)
    val dc = station.kind == "dc"
    if (dc && vehicle?.isZoe == true && (!vehicle.dcCapable || !confirmedCcs)) return null
    val vehicleLimit = if (dc) (if (vehicle?.isZoe == true) 50.0 else 100.0) else if (station.sockets.contains("Type 2")) (if (vehicle?.isZoe == true) 22.0 else 11.0) else 2.3
    val maximum = min(station.powerKw ?: if (dc) 50.0 else vehicleLimit, vehicleLimit)
    // Approximate average DC power and taper, not a measured vehicle charge curve.
    val before80 = (min(target.toDouble(), 80.0) - min(arrival, 80.0)).coerceAtLeast(0.0)
    val after80 = gain - before80
    val power = maximum * if (dc) .65 else .90
    val hours = capacity / 100 * (before80 / power + after80 / (power * if (dc) .5 else .85))
    return RouteChargeEstimate(capacity * gain / 100, ceil(hours * 60).toInt(), station.powerKw == null)
}

internal fun journeyAnalysis(context: Context, journey: PlannedJourney, battery: MapRangeEstimate?,
                             weather: WeatherSample?, now: Long = System.currentTimeMillis()): String {
    val text = ArrayList<String>()
    var soc = battery?.soc
    val capacity = battery?.usableKwh
    val reserve = battery?.reserveSoc ?: RangePreferences.DEFAULT_RESERVE_SOC
    var departure = now
    var totalDriveMinutes = 0
    var totalChargeMinutes = 0
    var chargeTimeKnown = true
    var reachable = true
    fun value(v: Double) = String.format(AppLanguage.locale, "%.1f", v)
    for ((index, leg) in journey.legs.withIndex()) {
        val assessment = assessRoute(context, leg, battery?.copy(soc = soc), weather, departure)
        val energy = assessment.energyKwh
        val arrival = if (soc != null && capacity != null && energy != null && capacity > 0)
            soc - 100 * energy / capacity else null
        totalDriveMinutes += assessment.minutes
        departure += assessment.minutes * 60_000L
        val stop = journey.stops.getOrNull(index)
        val name = stop?.station?.name ?: "Odredište"
        text.add("${index + 1}. $name · ${value(leg.distanceKm)} km · ≈ ${assessment.minutes} min")
        text.add(if (energy == null) "Za energiju je potrebna zabeležena potrošnja."
            else "≈ ${value(energy)} kWh" + (arrival?.let { " · dolazak ${value(it)}%" } ?: ""))
        if (assessment.terrain.coverage >= .98)
            text.add("Uspon ↑ ${assessment.terrain.ascent.toInt()} m · pad ↓ ${assessment.terrain.descent.toInt()} m")
        else text.add("Visine nisu potpune; uspon i pad nisu uračunati.")
        text.addAll(listOf(assessment.windDescription, assessment.speedDescription, assessment.accessDescription).filter { it.isNotBlank() })
        if (arrival != null && arrival < 0) {
            reachable = false
            text.add("Nedovoljno energije za ovu deonicu. Dodaj raniji punjač.")
            break
        }
        if (arrival != null && arrival < reserve) text.add("Dolazak ispod rezerve $reserve%.")
        soc = arrival
        if (stop != null) {
            val added = if (arrival != null && capacity != null)
                routeChargeEstimate(arrival, stop.targetSoc, capacity, stop.station, VehicleProfiles.current(context),
                    context.getSharedPreferences("vehicle_equipment", Context.MODE_PRIVATE).getBoolean("ccs", false)) else null
            if (added != null) {
                soc = maxOf(arrival!!, stop.targetSoc.toDouble())
                totalChargeMinutes += added.minutes
                departure += added.minutes * 60_000L
                text.add("Dopuna do ${stop.targetSoc}% · +${value(added.kwh)} kWh · ≈ ${added.minutes} min" +
                    if (added.assumedPower) " (pretpostavljena snaga)" else "")
            } else {
                chargeTimeKnown = false
                soc = null
                text.add("Dopuna čeka SOC, kapacitet i kompatibilan priključak. Zoe DC zahteva ZE50 i potvrđenu CCS opremu na početnom ekranu.")
            }
        }
    }
    val chargeTime = if (chargeTimeKnown) "≈ $totalChargeMinutes min" else "nema dovoljno podataka"
    val total = if (reachable) "Ukupno ${value(journey.combined.distanceKm)} km · vožnja ≈ $totalDriveMinutes min · punjenje $chargeTime\n\n" else ""
    return total + text.joinToString("\n") +
        "\n\nProcena koristi potrošnju iz vožnji, teren i dostupnu prognozu vetra. AC limit: e-CMP 11 kW, Zoe 22 kW (konzervativno za Q motore). DC vreme uključuje okvirno opadanje snage. Čekanje na slobodan priključak nije uračunato."
}
