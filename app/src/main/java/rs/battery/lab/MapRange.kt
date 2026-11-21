package rs.battery.lab

import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

internal data class MapDestination(val lat: Double, val lon: Double)
internal data class MapRangeEstimate(val origin: GpsPoint?, val oneWayKm: Double?, val returnKm: Double?,
    val detail: String, val soc: Double?, val usableKwh: Double?, val consumption: Double?,
    val reserveSoc: Int = RangePreferences.DEFAULT_RESERVE_SOC) {
    val roadKm: Double? get() = oneWayKm?.times(1.2)
    val roadKmToZero: Double? get() = if (soc != null && usableKwh != null && consumption != null && consumption > 0)
        soc * usableKwh / consumption else null
}

internal data class RangeHistory(val usableKwh: Double?, val consumption: Double?, val tripsUsed: Int,
    val distanceKm: Double = 0.0)

/** Never divide a partial energy integral by the complete trip distance or SOC change. */
internal fun rangeEnergyKwh(trip: TripRecord): Double? = when {
    trip.counterCoveragePercent >= 98 && trip.counterNetKwh?.let { it.isFinite() && it >= 0 } == true -> trip.counterNetKwh
    trip.powerCoveragePercent >= 98 && trip.integratedNetKwh.isFinite() && trip.integratedNetKwh >= 0 -> trip.integratedNetKwh
    else -> null
}

/** Weight energy by distance: a cold three-kilometre trip must not outweigh a long drive. */
internal fun rangeHistory(trips: List<TripRecord>, active: TripRecord? = null): RangeHistory {
    val recent = (trips.takeLast(30) + listOfNotNull(active)).associateBy { it.id }.values
    val measured = recent.filter { (it.distanceKm ?: 0.0) >= 3.0 }
        .mapNotNull { trip ->
            val energy = rangeEnergyKwh(trip) ?: return@mapNotNull null
            val km = trip.distanceKm!!
            if (energy * 100 / km in 3.0..85.0) km to energy else null
        }
    val distance = measured.sumOf { it.first }
    val rate = if (distance > 0) measured.sumOf { it.second } * 100 / distance else null
    val capacities = recent.mapNotNull { trip ->
        val drop = (trip.startSoc ?: return@mapNotNull null) - (trip.endSoc ?: return@mapNotNull null)
        val energy = rangeEnergyKwh(trip) ?: return@mapNotNull null
        if (drop < 8.0 || energy <= 0.0 || (trip.distanceKm ?: 0.0) < 10) null
        else (100 * energy / drop).takeIf { it in 8.0..80.0 }
    }.sorted()
    return RangeHistory(capacities.takeIf { it.isNotEmpty() }?.let { (it[(it.size-1)/2] + it[it.size/2])/2 },
        rate, measured.size, distance)
}

internal fun mapRange(live: BmsReading?, fix: GpsPoint?, history: RangeHistory, now: Long,
                      reserveSoc: Int = RangePreferences.DEFAULT_RESERVE_SOC): MapRangeEstimate {
    val reserve = reserveSoc.takeIf(RangePreferences::validReserveSoc) ?: RangePreferences.DEFAULT_RESERVE_SOC
    // UI timer ticks can precede a just-arrived sensor value. Allow a small clock skew,
    // without accepting implausibly future-dated data or turning valid readings off each tick.
    val origin = fix?.takeIf { it.timeMillis > 0 && it.timeMillis <= now + 5_000 && it.latitude in -90.0..90.0 &&
        it.longitude in -180.0..180.0 && (it.accuracyMeters == null || it.accuracyMeters in 0f..60f) }
    val sample = live?.takeIf { it.timestamp > 0 && it.timestamp <= now + 5_000 }
    val soc = sample?.soc?.takeIf { it in 0.0..100.0 }
    val observedCapacity = if (soc != null && soc >= 20 && sample.energy != null)
        (100 * sample.energy / soc).takeIf { it in 8.0..80.0 } else null
    val historyCapacity = history.usableKwh?.takeIf { it.isFinite() && it in 8.0..80.0 }
    val capacity = historyCapacity ?: observedCapacity
    val rate = history.consumption?.takeIf { it.isFinite() && it > 0 }
    val km = when {
        soc == null -> null
        soc <= reserve -> 0.0
        capacity == null || rate == null || rate <= 0 -> null
        else -> (soc - reserve) * capacity / rate / 1.2
    }
    val messages = mutableListOf<String>()
    val clock = SimpleDateFormat("dd.MM. HH:mm", AppLanguage.locale)
    if (soc != null) messages += "SOC vozila %.1f %% · rezerva %d %%".format(Locale.getDefault(), soc, reserve)
    if (sample != null && now - sample.timestamp > 120_000)
        messages += "Poslednje očitavanje baterije ${clock.format(Date(sample.timestamp))}."
    if (soc == null) messages += "Čeka se očitavanje SOC-a vozila."
    else if (soc <= reserve) messages += "Dostignuta je rezerva od $reserve%."
    else {
        if (rate == null) messages += "Čeka se vožnja od najmanje 3 km sa ≥98% pokrivenosti energije."
        else messages += "Prosek po pređenom putu %.1f kWh/100 km · %.0f km iz %d vožnji".format(Locale.getDefault(), rate, history.distanceKm, history.tripsUsed)
        if (rate != null && history.distanceKm < 20) messages += "Malo podataka: kratke vožnje i zagrevanje mogu snažno promeniti procenu."
        if (capacity == null) messages += "Čeka se podatak za procenu raspoloživog kapaciteta."
        else messages += "%.1f kWh (%s)".format(Locale.getDefault(), capacity, if (historyCapacity != null) "iz vožnji" else "iz BMS energije i SOC-a")
    }
    if (origin == null) messages += "Za krugove izaberi polazak ili sačekaj GPS poziciju."
    else if (now - origin.timeMillis > 30_000)
        messages += "Polazak sa poslednje GPS pozicije ${clock.format(Date(origin.timeMillis))}."
    if (km != null && km > 0)
        messages += "Orijentacioni krugovi uključuju 20% duži put od vazdušne udaljenosti. Povratak pretpostavlja iste uslove u oba smera. Ruta računa stvarne puteve i teren."
    messages += "Domet putem koristi vašu izmerenu potrošnju. Instrument tabla koristi sopstveni model, obično bez izabrane rezerve. Nijedna procena nije garantovani domet."
    return MapRangeEstimate(origin, km, km?.div(2), messages.joinToString(" · "), soc, capacity, rate, reserve)
}
