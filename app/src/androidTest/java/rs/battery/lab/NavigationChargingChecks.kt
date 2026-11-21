package rs.battery.lab

import android.content.Context
import kotlin.math.abs

internal fun checkChargingCounters(context: Context) {
    val now = System.currentTimeMillis()
    val ac = BmsReading(timestamp = now, voltage = 400.0, current = -20.0,
        chargeCableConnected = true, chargerAt = now, gridVoltage = 230.0,
        speedKmh = 0.0, speedAt = now, soc = 30.0)
    check(observedChargeKind(ac) == "ac")
    check(observedChargeKind(ac.copy(current = -100.0, gridVoltage = 0.0)) == "dc")
    check(observedChargeKind(ac.copy(gridVoltage = 0.0)) == "unknown")
    check(observedChargeKind(ac.copy(chargerAt = now - 40_000)) == "unknown")
    check(observedChargeKind(ac.copy(chargeCableConnected = false)) == "unknown")
    check(observedChargeKind(ac.copy(speedKmh = 50.0)) == "unknown")
    check(observedChargeKind(ac.copy(chargeCableConnected = null, speedAt = null)) == "unknown")
    check(observedChargeKind(ac.copy(current = 30.0)) == "unknown")
    ChargeMonitor.load(context)
    for (i in 0..2) {
        val at = now + i * 6_000
        ChargeMonitor.onReading(context, ac.copy(timestamp = at, chargerAt = at, speedAt = at))
    }
    val active = checkNotNull(ChargeMonitor.active.value)
    check(active.chargeKind == "ac")
    ChargeMonitor.reload(context)
    ChargeMonitor.stop(context)
    ChargeMonitor.reload(context)
    val saved = ChargeMonitor.history.value.last()
    check(saved.chargeKind == "ac")
    check(saved.durationMillis == active.durationMillis && saved.endedAtMillis == active.startedAtMillis + active.durationMillis)
    check(saved.batteryEnergyAddedKwh == active.batteryEnergyAddedKwh)
    val counts = countChargeEvents(listOf(saved.copy(chargeKind = "unknown"), saved,
        saved.copy(id = "dc", chargeKind = "dc"), saved.copy(id = "old", chargeKind = "unknown")), emptyList())
    check(counts.observedSessions == 3 && counts.acSessions == 1 && counts.dcSessions == 1 && counts.unknownSessions == 1)
}

internal fun checkNavigationCharging(context: Context) {
    val stations = ChargingStations.load(context)
    check(stations.size > 1000 && stations.any { it.free } && stations.any { it.fee == "unknown" })
    check(stations.map { it.id }.toSet().size == stations.size)
    check(stations.all { it.lat in 40.0..48.0 && it.lon in 17.0..25.0 })
    check(stations.all { it.free == (it.fee == "free") })
    check(searchName("Ниш") == searchName("Niš"))
    check(searchName("Ђорђа") == searchName("Đorđa"))
    val searchIndex = MapSearchCatalog.load(context, stations)
    check(searchIndex.places.any { it.second == "kragujevac" })
    check(MapSearchCatalog.load(context, stations) === searchIndex)
    val kraljevo = matchingPlaces(searchIndex, "kraljevo")
    check(kraljevo.size > 1)
    check(kraljevo.first().lat in 43.70..43.75 && kraljevo.first().lon in 20.65..20.72)
    check(matchingPlaces(searchIndex, "").isEmpty())
    val ac = stations.first { it.kind == "ac" && it.compatible }.copy(powerKw = 22.0, sockets = "Type 2")
    val dc = ac.copy(kind = "dc", powerKw = 50.0)
    val estimate = checkNotNull(routeChargeEstimate(30.0, 80, 40.0, ac))
    check(estimate.kwh == 20.0 && estimate.minutes == 122)
    check(routeChargeEstimate(-1.0, 80, 40.0, dc) == null)
    check(routeChargeEstimate(30.0, 80, 40.0, ac.copy(powerKw = 0.0)) == null)
    check(routeChargeEstimate(30.0, 80, 40.0, ac.copy(powerKw = Double.NaN)) == null)
    check(routeChargeEstimate(30.0, 80, 40.0, ac.copy(compatible = false)) == null)
    check(routeChargeEstimate(85.0, 80, 40.0, ac)?.kwh == 0.0)
    check(routeChargeEstimate(30.0, 80, 40.0, dc)?.minutes == 37)
    check(routeChargeEstimate(80.0, 100, 40.0, dc)!!.minutes > routeChargeEstimate(60.0, 80, 40.0, dc)!!.minutes)
    check(routeChargeEstimate(30.0, 80, 40.0, ac.copy(powerKw = null))!!.assumedPower)
    check(routeChargeEstimate(30.0, 80, 40.0, ac.copy(powerKw = null, sockets = "Šuko"))!!.minutes > 500)
    val start = GpsPoint(System.currentTimeMillis(), 44.0125, 20.9167, null, null, null)
    val stop = stations.filter { it.compatible }.minBy { routeKm(start.latitude, start.longitude, it.lat, it.lon) }
    val journey = checkNotNull(planJourney(context, start, MapDestination(43.7245, 20.6898), listOf(ChargingStop(stop))))
    check(journey.legs.size == 2 && journey.combined.distanceKm > 35)
    val flatProfile = plannedHeights(journey.legs) { _, _ -> 100.0 }
    check(flatProfile.stopKm.size == 1 && flatProfile.points.size > 10)
    check(abs(flatProfile.distanceKm - journey.combined.distanceKm) < .1)
    check(flatProfile.ascent == 0.0 && flatProfile.descent == 0.0 && flatProfile.coverage >= .99)
    val missingProfile = plannedHeights(journey.legs) { _, _ -> null }
    check(missingProfile.points.all { it.meters == null } && missingProfile.coverage == 0.0)
    val battery = MapRangeEstimate(start, 80.0, 40.0, "", 60.0, 40.0, 20.0)
    val report = journeyAnalysis(context, journey, battery, null)
    check(report.contains("Dopuna do 80%") && report.contains("2. Odredište"))
    check(report.contains("Vetar") && report.contains("Ekonomičan tempo"))
    val noBattery = journeyAnalysis(context, journey, null, null)
    check(noBattery.contains("punjenje nema dovoljno podataka"))
    val unreachable = journeyAnalysis(context, journey, battery.copy(soc = 0.0), null)
    check(unreachable.contains("Nedovoljno energije") && !unreachable.contains("Dopuna do"))
    val streets = OfflineAtlas.readStreets(context, StreetTile(81, 179))
    check(streets.any { it.kind == 5 && it.label.isNotBlank() })
    val now = System.currentTimeMillis()
    // Independent arithmetic for the user's 12 Nov export, rounded inputs.
    val historicalRange = mapRange(BmsReading(timestamp = now, soc = 52.1074), start,
        RangeHistory(36.02197, 13.92906, 22), now)
    check(abs(historicalRange.oneWayKm!! - 69.1942) < .002)
    check(abs(historicalRange.returnKm!! - 34.5971) < .002)
    val trip = TripRecord("coverage", now - 100_000, now, 0.0, 20.0, 80.0, 70.0, null, null,
        3.6, 3.6, 0.0, 100_000, 50.0, 0.0, 0.0, 0.0, 3.2, now - 90_000, now, 100, "test")
    check(rangeEnergyKwh(trip) == 3.6)
    check(rangeHistory(listOf(trip)).usableKwh == 36.0)
    check(rangeHistory(listOf(trip)).consumption == 18.0)
    check(rangeEnergyKwh(trip.copy(integratedMs = 97_000)) == null)
    val reading = BmsReading(timestamp = now, cellAverageVoltage = 3.604, bmsExtraAt = now)
    check(abs(packSocDisplay(context, reading, now).voltageSocPercent!! - 35.85185185) < .0001)
    val newerArray = packSocDisplay(context, reading.copy(cellVoltagesAt = now + 1, cellVoltages = List(108) { 4.052 }), now + 1)
    check(abs(newerArray.meanGroupVoltageV!! - 4.052) < .000001 && abs(newerArray.voltageSocPercent!! - 82.97) < .0001)
    val overview = AtlasTile(20, 44, -1, 0, 0)
    val retained = OfflineAtlas.read(context, overview)
    for (y in 0..7) for (x in 0..7) OfflineAtlas.read(context, AtlasTile(20, 44, 3, x, y))
    check(OfflineAtlas.cached(overview) === retained)
}
