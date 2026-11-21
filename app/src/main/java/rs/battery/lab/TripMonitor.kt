package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** A BMS-defined drive. GPS points are linked by this time window, never by the whole day. */
internal data class TripRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val startOdoKm: Double?,
    val endOdoKm: Double?,
    val startSoc: Double?,
    val endSoc: Double?,
    val startEnergyKwh: Double?,
    val endEnergyKwh: Double?,
    val integratedNetKwh: Double,
    val grossDischargeKwh: Double,
    val recuperatedKwh: Double,
    val integratedMs: Long,
    val maxSpeedKmh: Double,
    val counterStartInKwh: Double?,
    val counterEndInKwh: Double?,
    val counterStartOutKwh: Double?,
    val counterEndOutKwh: Double?,
    val counterStartAt: Long?,
    val counterEndAt: Long?,
    val sampleCount: Int,
    val endReason: String
) {
    val distanceKm: Double? get() = if (startOdoKm != null && endOdoKm != null && endOdoKm >= startOdoKm)
        endOdoKm - startOdoKm else null
    val bmsRemainingEnergyChangeKwh: Double? get() = if (startEnergyKwh != null && endEnergyKwh != null)
        startEnergyKwh - endEnergyKwh else null
    val counterNetKwh: Double? get() {
        val input = if (counterStartInKwh != null && counterEndInKwh != null) counterEndInKwh - counterStartInKwh else return null
        val output = if (counterStartOutKwh != null && counterEndOutKwh != null) counterEndOutKwh - counterStartOutKwh else return null
        return (output - input).takeIf { input >= 0 && output >= 0 }
    }
    val powerCoveragePercent: Double get() = if (endedAt <= startedAt) 0.0 else
        (integratedMs * 100.0 / (endedAt - startedAt)).coerceIn(0.0, 100.0)
    val counterCoveragePercent: Double get() = if (counterStartAt == null || counterEndAt == null || endedAt <= startedAt) 0.0 else
        ((counterEndAt - counterStartAt) * 100.0 / (endedAt - startedAt)).coerceIn(0.0, 100.0)
    /** Prefer metered D42E only if its start/end cover the trip; otherwise integrate V×I. */
    val consumptionEnergyKwh: Double? get() = when {
        counterCoveragePercent >= 90 && counterNetKwh != null && counterNetKwh!! >= 0 -> counterNetKwh
        powerCoveragePercent >= 75 && integratedNetKwh >= 0 -> integratedNetKwh
        else -> null
    }
    val consumptionSource: String get() = if (counterCoveragePercent >= 90 && counterNetKwh != null) "BMS brojači D42E"
        else if (powerCoveragePercent >= 75) "integracija V×I" else "nedovoljno podataka"
    val consumptionKwhPer100Km: Double? get() = distanceKm?.takeIf { it >= 1.0 }?.let { km ->
        consumptionEnergyKwh?.let { it * 100 / km }
    }
}

internal data class TripChange(val started: TripRecord? = null, val ended: TripRecord? = null)

internal object TripMonitor {
    val active = MutableStateFlow<TripRecord?>(null)
    val history = MutableStateFlow<List<TripRecord>>(emptyList())
    private var previous: BmsReading? = null
    private var stationarySince: Long? = null
    private var loaded = false
    private const val PREFS = "battery_lab"
    private const val ACTIVE_KEY = "active_trip_v2"
    private const val FILE_NAME = "trip-sessions.jsonl"

    @Synchronized fun reload(context: Context) {
        loaded = false
        previous = null
        stationarySince = null
        load(context)
    }

    @Synchronized fun load(context: Context) {
        if (loaded) return
        val file = File(context.filesDir, FILE_NAME)
        history.value = if (file.exists()) file.useLines { lines -> lines.mapNotNull { runCatching { parse(JSONObject(it)) }.getOrNull() }.toList() } else emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_KEY, null)
        active.value = raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
        loaded = true
    }

    @Synchronized fun onReading(context: Context, value: BmsReading): TripChange {
        load(context)
        var ended: TripRecord? = null
        active.value?.let { stale ->
            if (value.timestamp - stale.endedAt > 120_000) ended = finish(context, "prekid telemetrije", stale.endedAt)
        }
        val before = previous
        val speedFresh = value.speedAt?.let { value.timestamp - it in 0..15_000 } == true
        val movingBySpeed = speedFresh && value.speedKmh?.let { it.isFinite() && it in 3.0..250.0 } == true
        val movingByOdo = before?.odometerKm != null && value.odometerKm != null &&
            value.odometerKm - before.odometerKm >= 0.01 && value.timestamp - before.timestamp in 1..60_000
        val moving = movingBySpeed || movingByOdo
        // Missing/stale speed and a coarse odometer do not prove that the vehicle stopped.
        val stationary = !moving && speedFresh && value.speedKmh?.let { it in 0.0..<3.0 } == true
        var started: TripRecord? = null
        if (active.value == null && moving) {
            val baseline = before?.takeIf { value.timestamp - it.timestamp in 1..60_000 } ?: value
            val freshStartCounter = baseline.lifetimeCountersAt?.takeIf {
                baseline.timestamp - it in 0..30_000 && baseline.lifetimeEnergyInKwh != null && baseline.lifetimeEnergyOutKwh != null
            }
            val freshEndCounter = value.lifetimeCountersAt?.takeIf {
                value.timestamp - it in 0..30_000 && value.lifetimeEnergyInKwh != null && value.lifetimeEnergyOutKwh != null
            }
            val initial = TripRecord(
                id = "trip-${baseline.timestamp}", startedAt = baseline.timestamp, endedAt = value.timestamp,
                startOdoKm = baseline.odometerKm, endOdoKm = value.odometerKm,
                startSoc = baseline.soc, endSoc = value.soc,
                startEnergyKwh = baseline.energy, endEnergyKwh = value.energy,
                integratedNetKwh = 0.0, grossDischargeKwh = 0.0, recuperatedKwh = 0.0,
                integratedMs = 0L, maxSpeedKmh = value.speedKmh ?: 0.0,
                counterStartInKwh = if (freshStartCounter != null) baseline.lifetimeEnergyInKwh else null,
                counterEndInKwh = if (freshEndCounter != null) value.lifetimeEnergyInKwh else null,
                counterStartOutKwh = if (freshStartCounter != null) baseline.lifetimeEnergyOutKwh else null,
                counterEndOutKwh = if (freshEndCounter != null) value.lifetimeEnergyOutKwh else null,
                counterStartAt = freshStartCounter, counterEndAt = freshEndCounter,
                sampleCount = 0, endReason = "u toku"
            )
            active.value = initial
            started = initial
        }
        active.value?.let { trip ->
            val dt = before?.let { value.timestamp - it.timestamp } ?: 0L
            val powerBefore = before?.powerKw
            val powerNow = value.powerKw
            val integrated = if (dt in 1..60_000 && powerBefore != null && powerNow != null)
                (powerBefore + powerNow) / 2.0 * dt / 3_600_000.0 else null
            val freshCounter = value.lifetimeCountersAt?.takeIf {
                value.timestamp - it in 0..30_000 && it >= trip.startedAt &&
                    value.lifetimeEnergyInKwh != null && value.lifetimeEnergyOutKwh != null
            }
            val updated = trip.copy(
                endedAt = value.timestamp,
                endOdoKm = value.odometerKm ?: trip.endOdoKm,
                endSoc = value.soc ?: trip.endSoc,
                endEnergyKwh = value.energy ?: trip.endEnergyKwh,
                integratedNetKwh = trip.integratedNetKwh + (integrated ?: 0.0),
                grossDischargeKwh = trip.grossDischargeKwh + (integrated?.coerceAtLeast(0.0) ?: 0.0),
                recuperatedKwh = trip.recuperatedKwh + (-(integrated?.coerceAtMost(0.0) ?: 0.0)),
                integratedMs = trip.integratedMs + if (integrated != null) dt else 0L,
                maxSpeedKmh = maxOf(trip.maxSpeedKmh, value.speedKmh ?: 0.0),
                counterStartInKwh = trip.counterStartInKwh ?: if (freshCounter != null) value.lifetimeEnergyInKwh else null,
                counterStartOutKwh = trip.counterStartOutKwh ?: if (freshCounter != null) value.lifetimeEnergyOutKwh else null,
                counterStartAt = trip.counterStartAt ?: freshCounter,
                counterEndInKwh = if (freshCounter != null) value.lifetimeEnergyInKwh else trip.counterEndInKwh,
                counterEndOutKwh = if (freshCounter != null) value.lifetimeEnergyOutKwh else trip.counterEndOutKwh,
                counterEndAt = freshCounter ?: trip.counterEndAt,
                sampleCount = trip.sampleCount + 1
            )
            active.value = updated
            if (!stationary) stationarySince = null else if (stationarySince == null) stationarySince = value.timestamp
            if (stationarySince?.let { value.timestamp - it >= 120_000 } == true) ended = finish(context, "zaustavljeno", value.timestamp)
            else persistActive(context)
        }
        previous = value
        return TripChange(started, ended)
    }

    @Synchronized fun finish(context: Context, reason: String, endAt: Long = System.currentTimeMillis()): TripRecord? {
        load(context)
        val current = active.value ?: return null
        val closed = current.copy(endedAt = minOf(endAt, current.endedAt.coerceAtLeast(current.startedAt)), endReason = reason)
        if ((closed.distanceKm ?: 0.0) >= 0.1 || closed.endedAt - closed.startedAt >= 60_000) {
            File(context.filesDir, FILE_NAME).appendText(toJson(closed).toString() + "\n", Charsets.UTF_8)
            history.value = (history.value + closed).takeLast(10_000)
        }
        active.value = null
        stationarySince = null
        previous = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_KEY).apply()
        return closed
    }

    private fun persistActive(context: Context) {
        active.value?.let { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE_KEY, toJson(it).toString()).apply() }
    }

    fun dayOf(record: TripRecord): String = Instant.ofEpochMilli(record.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    fun monthOf(record: TripRecord): String = dayOf(record).take(7)

    private fun toJson(r: TripRecord) = JSONObject()
        .put("id", r.id).put("start", r.startedAt).put("end", r.endedAt)
        .put("odo0", r.startOdoKm).put("odo1", r.endOdoKm)
        .put("soc0", r.startSoc).put("soc1", r.endSoc)
        .put("energy0", r.startEnergyKwh).put("energy1", r.endEnergyKwh)
        .put("net", r.integratedNetKwh).put("gross", r.grossDischargeKwh).put("regen", r.recuperatedKwh)
        .put("integratedMs", r.integratedMs).put("maxSpeed", r.maxSpeedKmh)
        .put("counterIn0", r.counterStartInKwh).put("counterIn1", r.counterEndInKwh)
        .put("counterOut0", r.counterStartOutKwh).put("counterOut1", r.counterEndOutKwh)
        .put("counterAt0", r.counterStartAt).put("counterAt1", r.counterEndAt)
        .put("samples", r.sampleCount).put("reason", r.endReason)

    private fun parse(j: JSONObject): TripRecord {
        fun number(key: String) = j.optDouble(key).takeUnless { it.isNaN() }
        fun time(key: String) = j.optLong(key).takeIf { it > 0 }
        return TripRecord(j.getString("id"), j.getLong("start"), j.getLong("end"),
            number("odo0"), number("odo1"), number("soc0"), number("soc1"), number("energy0"), number("energy1"),
            j.optDouble("net"), j.optDouble("gross"), j.optDouble("regen"), j.optLong("integratedMs"), j.optDouble("maxSpeed"),
            number("counterIn0"), number("counterIn1"), number("counterOut0"), number("counterOut1"),
            time("counterAt0"), time("counterAt1"), j.optInt("samples"), j.optString("reason"))
    }
}
