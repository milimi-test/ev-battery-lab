package rs.battery.lab

import android.content.Context
import kotlin.math.abs

/** Pass the suite's isolated context so these checks cannot change the user's saved reserve. */
internal fun checkRangeReserve(context: Context) {
    val now = 1_800_000_000_000L
    val reading = BmsReading(timestamp = now, soc = 60.0)
    val fix = GpsPoint(now, 44.01, 20.91, null, null, 5f)
    val history = RangeHistory(40.0, 20.0, 6)
    val defaultRange = mapRange(reading, fix, history, now)
    check(defaultRange.reserveSoc == 20)
    check(defaultRange.oneWayKm == 80.0 / 1.2 && defaultRange.returnKm == 40.0 / 1.2)
    check(MapRangeEstimate(fix, null, null, "", null, null, null).reserveSoc == 20)

    var previousKm = Double.POSITIVE_INFINITY
    for (reserve in 5..50 step 5) {
        val range = mapRange(reading, fix, history, now, reserve)
        val oneWay = checkNotNull(range.oneWayKm)
        check(range.reserveSoc == reserve && oneWay < previousKm)
        check(abs(oneWay - (60.0 - reserve) * 40.0 / 20.0 / 1.2) < 1e-9)
        check(range.returnKm == oneWay / 2)
        check(range.detail.contains("rezerva $reserve %"))
        for (soc in listOf(0.0, reserve - .1, reserve.toDouble())) {
            val atReserve = mapRange(reading.copy(soc = soc), fix, history, now, reserve)
            check(atReserve.oneWayKm == 0.0 && atReserve.returnKm == 0.0)
            check(atReserve.detail.contains("Dostignuta je rezerva od $reserve%"))
        }
        check(mapRange(reading.copy(soc = reserve + .1), fix, history, now, reserve).oneWayKm!! > 0.0)
        previousKm = oneWay
    }
    for (invalid in listOf(Int.MIN_VALUE, -1, 0, 4, 7, 51, 100, Int.MAX_VALUE)) {
        val range = mapRange(reading, fix, history, now, invalid)
        check(range.reserveSoc == 20 && range.oneWayKm == defaultRange.oneWayKm)
    }
    check(mapRange(null, fix, history, now, 35).oneWayKm == null)
    check(mapRange(reading, fix, RangeHistory(null, null, 0), now, 35).oneWayKm == null)
    check(mapRange(reading.copy(soc = 30.0), fix, RangeHistory(null, null, 0), now, 35).oneWayKm == 0.0)
    // The 20% minimum for inferring capacity from BMS remains independent of the chosen reserve.
    check(mapRange(reading.copy(soc = 15.0, energy = 6.0), fix, RangeHistory(null, 20.0, 1), now, 5).oneWayKm == null)

    val prefs = context.getSharedPreferences("battery_lab", Context.MODE_PRIVATE)
    check(prefs.edit().remove("range_reserve_soc").commit())
    RangePreferences.load(context)
    check(RangePreferences.reserveSoc.value == 20)
    for (saved in listOf(5, 35, 50)) {
        check(RangePreferences.setReserveSoc(context, saved))
        check(RangePreferences.reserveSoc.value == saved)
        check(prefs.getInt("range_reserve_soc", -1) == saved)
        // Loading after a distinct in-memory value models an application restart.
        check(RangePreferences.setReserveSoc(context, 20))
        check(prefs.edit().putInt("range_reserve_soc", saved).commit())
        RangePreferences.load(context)
        check(RangePreferences.reserveSoc.value == saved)
    }
    for (invalid in listOf(Int.MIN_VALUE, -1, 0, 4, 7, 51, 100, Int.MAX_VALUE)) {
        check(!RangePreferences.setReserveSoc(context, invalid))
        check(RangePreferences.reserveSoc.value == 50 && prefs.getInt("range_reserve_soc", -1) == 50)
    }
    for (invalid in listOf(-5, 7, 55)) {
        check(prefs.edit().putInt("range_reserve_soc", invalid).commit())
        RangePreferences.load(context)
        check(RangePreferences.reserveSoc.value == 20)
    }
    check(prefs.edit().putString("range_reserve_soc", "bad legacy value").commit())
    RangePreferences.load(context)
    check(RangePreferences.reserveSoc.value == 20)
    check(RangePreferences.setReserveSoc(context, 20))
}
