package rs.battery.lab

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** A measured, rested-voltage anchor for the unverified D862 BMS percentage scale. */
internal data class VoltageSocAnchor(val atMillis: Long, val meanVoltageV: Double,
    val candidateD862Percent: Double, val temperatureC: Double)

internal data class VoltageSocResult(val percent: Double?, val reason: String,
    val anchorCount: Int, val measuredAtMillis: Long?)

/**
 * Vehicle-local interpolation, not a CATL OCV curve or a proven gross-SOC measurement.
 * Anchors are collected only after 20 minutes with near-zero current, stable voltage/SOC,
 * temperature and odometer. No percentage is extrapolated beyond measured anchors.
 */
internal object VoltageSocCalibration {
    const val FILE_NAME = "voltage-soc-calibration.jsonl"
    private const val REST_MS = 20 * 60_000L
    private const val FRESH_MS = 120_000L
    private val lock = Any()
    private var loadedPath: String? = null
    private var anchors = emptyList<VoltageSocAnchor>()
    private var rest: RestStreak? = null
    private var lastObservedAt = 0L

    fun reset() = synchronized(lock) {
        anchors = emptyList()
        loadedPath = null
        rest = null
        lastObservedAt = 0L
    }

    private data class RestStreak(val startAt: Long, val firstOdoKm: Double?,
        val firstSoc: Double, val firstVoltage: Double, val firstTemp: Double,
        val minVoltage: Double, val maxVoltage: Double, val minSoc: Double, val maxSoc: Double,
        val minTemp: Double, val maxTemp: Double, val saved: Boolean)

    /** Called for every real BMS snapshot by TelemetryStore, including while the UI is closed. */
    fun observe(context: Context, reading: BmsReading) = synchronized(lock) {
        loadLocked(context)
        val now = reading.timestamp
        if (now - lastObservedAt !in 1..60_000) rest = null
        lastObservedAt = now
        val voltage = measuredMean(reading, now)
        val candidate = reading.socCandidateD862.takeIf {
            reading.socCandidatesAt?.let { at -> now - at in 0..FRESH_MS } == true &&
                it != null && it.isFinite() && it in 0.0..100.0
        }
        val temp = reading.tempAvg
        val current = reading.current
        val speed = reading.speedKmh.takeIf {
            reading.speedAt?.let { at -> now - at in 0..30_000 } == true
        }
        if (voltage == null || candidate == null || temp == null || temp !in 10.0..35.0 ||
            current == null || !current.isFinite() || abs(current) > 2.0 ||
            speed != null && speed > 1.0 || reading.chargeCableConnected == true) {
            rest = null
            return@synchronized
        }
        val prior = rest
        val stable = prior != null && now - prior.startAt in 0..(24 * 60 * 60_000L) &&
            abs(voltage - prior.firstVoltage) <= 0.010 &&
            abs(candidate - prior.firstSoc) <= 1.0 &&
            abs(temp - prior.firstTemp) <= 3.0 &&
            (prior.firstOdoKm == null || reading.odometerKm == null ||
                abs(reading.odometerKm - prior.firstOdoKm) <= 0.05)
        val streak = if (!stable) RestStreak(now, reading.odometerKm, candidate, voltage, temp,
            voltage, voltage, candidate, candidate, temp, temp, false)
        else prior!!.copy(minVoltage = minOf(prior.minVoltage, voltage),
            maxVoltage = maxOf(prior.maxVoltage, voltage), minSoc = minOf(prior.minSoc, candidate),
            maxSoc = maxOf(prior.maxSoc, candidate), minTemp = minOf(prior.minTemp, temp),
            maxTemp = maxOf(prior.maxTemp, temp))
        rest = streak
        if (!streak.saved && now - streak.startAt >= REST_MS &&
            streak.maxVoltage - streak.minVoltage <= 0.010 &&
            streak.maxSoc - streak.minSoc <= 1.0 && streak.maxTemp - streak.minTemp <= 3.0) {
            addAnchor(context, VoltageSocAnchor(now, voltage, candidate, temp))
            rest = streak.copy(saved = true)
        }
    }

    fun estimate(context: Context, reading: BmsReading?, nowMillis: Long = System.currentTimeMillis()): VoltageSocResult = synchronized(lock) {
        loadLocked(context)
        if (reading == null || nowMillis - reading.timestamp !in 0..FRESH_MS)
            return@synchronized noValue("Čeka sveže BMS očitavanje.")
        val voltage = measuredMean(reading, nowMillis)
            ?: return@synchronized noValue("Srednji napon D43D/D440 nije svež.")
        if (reading.current == null || abs(reading.current) > 2.0 ||
            reading.chargeCableConnected == true ||
            reading.speedAt?.let { nowMillis - it in 0..30_000 && (reading.speedKmh ?: 0.0) > 1.0 } == true)
            return@synchronized noValue("Pod opterećenjem ili pri punjenju nema naponske procene.")
        val temp = reading.tempAvg?.takeIf { it in 10.0..35.0 }
            ?: return@synchronized noValue("Temperatura izvan opsega 10–35 °C ili nije dostupna.")
        val streak = rest
        if (streak == null || reading.timestamp != lastObservedAt || reading.timestamp - streak.startAt < REST_MS)
            return@synchronized noValue("Potrebno je 20 min stabilnog mirovanja baterije.")
        val usable = anchors.filter { abs(it.temperatureC - temp) <= 3.0 }.sortedBy { it.meanVoltageV }
        if (usable.size < 3 || usable.last().candidateD862Percent - usable.first().candidateD862Percent < 15.0 ||
            usable.last().meanVoltageV - usable.first().meanVoltageV < 0.04)
            return@synchronized noValue("Nedostaju tri različita mirna sidra D862 (≥15% i ≥0,04 V).")
        val lower = usable.lastOrNull { it.meanVoltageV <= voltage }
        val upper = usable.firstOrNull { it.meanVoltageV >= voltage }
        if (lower == null || upper == null)
            return@synchronized noValue("Napon je izvan izmerenog raspona; nema ekstrapolacije.")
        val result = if (lower === upper) lower.candidateD862Percent else {
            val deltaV = upper.meanVoltageV - lower.meanVoltageV
            val deltaSoc = upper.candidateD862Percent - lower.candidateD862Percent
            if (deltaV <= 0 || deltaV > 0.15 || deltaSoc <= 0 || deltaSoc > 50)
                return@synchronized noValue("Sidra nisu dovoljno bliska ili monotona.")
            lower.candidateD862Percent + (voltage - lower.meanVoltageV) / deltaV * deltaSoc
        }
        VoltageSocResult(result.takeIf { it.isFinite() && it in 0.0..100.0 },
            "Interpolacija mirnog srednjeg napona ka D862 skali; nije potvrđeni bruto SOC.",
            usable.size, reading.timestamp)
    }

    private fun noValue(reason: String) = VoltageSocResult(null, reason, anchors.size, null)

    private fun measuredMean(reading: BmsReading, nowMillis: Long): Double? {
        val scalar = reading.cellAverageVoltage?.takeIf {
            reading.bmsExtraAt?.let { at -> nowMillis - at in 0..FRESH_MS } == true &&
                it.isFinite() && it in 2.0..4.5
        }
        val cells = reading.cellVoltages.takeIf {
            reading.cellVoltagesAt?.let { at -> nowMillis - at in 0..FRESH_MS } == true &&
                it.size == 108 && it.all { v -> v.isFinite() && v in 2.0..4.5 }
        }
        return scalar ?: cells?.average()
    }

    private fun addAnchor(context: Context, anchor: VoltageSocAnchor) {
        if (anchors.any { abs(it.candidateD862Percent - anchor.candidateD862Percent) < 2.0 ||
                abs(it.meanVoltageV - anchor.meanVoltageV) < 0.012 }) return
        anchors = (anchors + anchor).takeLast(100)
        file(context).writeText(anchors.joinToString("\n", postfix = "\n") {
            JSONObject().put("at_ms", it.atMillis).put("mean_voltage_v", it.meanVoltageV)
                .put("soc_candidate_d862_percent", it.candidateD862Percent)
                .put("temperature_c", it.temperatureC).toString()
        }, Charsets.UTF_8)
    }

    private fun loadLocked(context: Context) {
        val source = file(context)
        if (loadedPath == source.absolutePath && (source.exists() || anchors.isEmpty())) return
        anchors = if (source.exists()) source.useLines { lines -> lines.mapNotNull { line ->
            runCatching { JSONObject(line).let { j -> VoltageSocAnchor(j.getLong("at_ms"),
                j.getDouble("mean_voltage_v"), j.getDouble("soc_candidate_d862_percent"),
                j.getDouble("temperature_c")) } }.getOrNull()
        }.toList().takeLast(100) } else emptyList()
        rest = null
        loadedPath = source.absolutePath
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
