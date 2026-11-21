package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.max

/** Battery-side V×I integral only; this does not estimate energy taken from the wall. */
internal data class ChargeSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val firstSocPercent: Double?,
    val lastSocPercent: Double?,
    /** D865 is an estimated remaining-energy value, never a charged-energy counter. */
    val firstRemainingEstimateKwh: Double?,
    val lastRemainingEstimateKwh: Double?,
    val batteryEnergyAddedKwh: Double,
    val energyBasis: String,
    val socGainedPercent: Double?,
    val durationMillis: Long,
    val observationCount: Int,
    val observedPowerDurationMillis: Long,
    val chargeKind: String = "unknown"
) {
    val deltaRemainingEstimateKwh: Double?
        get() = if (firstRemainingEstimateKwh != null && lastRemainingEstimateKwh != null)
            lastRemainingEstimateKwh - firstRemainingEstimateKwh else null
    val powerCoveragePercent: Double
        get() = if (durationMillis <= 0) 0.0
            else (100.0 * observedPowerDurationMillis / durationMillis).coerceIn(0.0, 100.0)
}

/**
 * Detects charging from real BMS readings. Negative battery power is required; a known
 * disconnected cable vetoes charging. Without cable telemetry, fresh zero wheel speed is required.
 * Starts after three matching readings spanning ten seconds, ends after three misses spanning
 * fifteen seconds, and splits a silent telemetry gap over two minutes.
 */
internal object ChargeMonitor {
    private const val FILE_NAME = "charge-sessions.jsonl"
    private const val MIN_CHARGE_KW = 0.5
    private const val STATIONARY_KMH = 1.0
    private const val SPEED_FRESH_MS = 30_000L
    private const val MAX_SAMPLE_GAP_MS = 120_000L
    private const val MAX_INTEGRATION_GAP_MS = 30_000L
    private const val START_COUNT = 3
    private const val START_SPAN_MS = 10_000L
    private const val STOP_COUNT = 3
    private const val STOP_SPAN_MS = 15_000L
    private const val MAX_HISTORY = 500
    private val lock = Any()
    private var loaded = false
    private var loadedPath: String? = null
    private var pendingStart = ArrayList<BmsReading>()
    private var pendingStop = ArrayList<BmsReading>()
    private var lastQualifying: BmsReading? = null
    private var integratedKwh = 0.0

    private val mutableActive = MutableStateFlow<ChargeSession?>(null)
    val active: StateFlow<ChargeSession?> = mutableActive.asStateFlow()
    private val mutableHistory = MutableStateFlow<List<ChargeSession>>(emptyList())
    val history: StateFlow<List<ChargeSession>> = mutableHistory.asStateFlow()

    /** Call once per new BMS snapshot in timestamp order. Duplicate or older snapshots are ignored. */
    fun onReading(context: Context, reading: BmsReading) = synchronized(lock) {
        ensureLoaded(context)
        if (reading.timestamp <= 0) return@synchronized
        val current = mutableActive.value
        val previous = lastQualifying
        val lastTime = previous?.timestamp ?: current?.let { it.startedAtMillis + it.durationMillis }
        if (current != null && lastTime != null && reading.timestamp <= lastTime) return@synchronized
        if (current != null && lastTime != null && reading.timestamp - lastTime > MAX_SAMPLE_GAP_MS) {
            finish(context, lastTime)
            pendingStart.clear()
            pendingStop.clear()
        }
        if (isCharging(reading)) {
            val hadNonChargingReadings = pendingStop.isNotEmpty()
            pendingStop.clear()
            val open = mutableActive.value
            if (open == null) {
                val lastCandidate = pendingStart.lastOrNull()
                if (lastCandidate != null && reading.timestamp <= lastCandidate.timestamp) return@synchronized
                if (lastCandidate != null && reading.timestamp - lastCandidate.timestamp > MAX_SAMPLE_GAP_MS) pendingStart.clear()
                pendingStart += reading
                if (pendingStart.size >= START_COUNT && reading.timestamp - pendingStart.first().timestamp >= START_SPAN_MS) {
                    val first = pendingStart.first()
                    val observed = observedDuration(pendingStart)
                    val session = ChargeSession("charge-${first.timestamp}", first.timestamp, null,
                        first.soc.validSoc(), reading.soc.validSoc(), first.energy.validEnergy(),
                        reading.energy.validEnergy(), 0.0, "negative_power_integral",
                        socGain(first.soc, reading.soc), reading.timestamp - first.timestamp, pendingStart.size,
                        observed)
                    integratedKwh = integrateCandidates(pendingStart)
                    lastQualifying = reading
                    mutableActive.value = session.copy(batteryEnergyAddedKwh = integratedKwh,
                        chargeKind = pendingStart.map(::observedChargeKind).firstOrNull { it != "unknown" } ?: "unknown")
                    persist(context, mutableActive.value!!)
                    pendingStart.clear()
                }
            } else {
                if (previous != null && reading.timestamp <= previous.timestamp) return@synchronized
                val interval = if (!hadNonChargingReadings && previous != null &&
                    reading.timestamp - previous.timestamp in 1..MAX_INTEGRATION_GAP_MS)
                    reading.timestamp - previous.timestamp else 0L
                if (interval > 0) {
                    integratedKwh += trapezoidKwh(previous!!, reading)
                }
                lastQualifying = reading
                val updated = open.copy(
                    lastSocPercent = reading.soc.validSoc() ?: open.lastSocPercent,
                    lastRemainingEstimateKwh = reading.energy.validEnergy() ?: open.lastRemainingEstimateKwh,
                    socGainedPercent = socGain(open.firstSocPercent, reading.soc.validSoc() ?: open.lastSocPercent),
                    durationMillis = reading.timestamp - open.startedAtMillis,
                    observationCount = open.observationCount + 1,
                    observedPowerDurationMillis = open.observedPowerDurationMillis + interval,
                    batteryEnergyAddedKwh = integratedKwh,
                    energyBasis = "negative_power_integral",
                    chargeKind = if (open.chargeKind == "unknown") observedChargeKind(reading) else open.chargeKind
                )
                mutableActive.value = updated
                persist(context, updated)
            }
        } else {
            pendingStart.clear()
            if (mutableActive.value != null) {
                val last = pendingStop.lastOrNull()
                if (last == null || reading.timestamp > last.timestamp) pendingStop += reading
                if (pendingStop.size >= STOP_COUNT &&
                    pendingStop.last().timestamp - pendingStop.first().timestamp >= STOP_SPAN_MS) {
                    val session = mutableActive.value!!
                    finish(context, lastQualifying?.timestamp ?: session.startedAtMillis + session.durationMillis)
                }
            }
        }
    }

    /** End a live session on service shutdown without extending it through an unobserved period. */
    fun stop(context: Context) = synchronized(lock) {
        ensureLoaded(context)
        mutableActive.value?.let { finish(context, lastQualifying?.timestamp ?: it.startedAtMillis + it.durationMillis) }
        pendingStart.clear()
        pendingStop.clear()
    }

    fun reload(context: Context) = synchronized(lock) {
        loaded = false
        ensureLoaded(context)
    }

    /** Restore persisted completed and active sessions after process restart. */
    fun load(context: Context) = synchronized(lock) { ensureLoaded(context) }

    fun exportFile(context: Context): File? = synchronized(lock) {
        ensureLoaded(context)
        file(context).takeIf { it.isFile && it.length() > 0 }
    }

    private fun isCharging(value: BmsReading): Boolean {
        val power = value.powerKw ?: return false
        if (!power.isFinite() || power > -MIN_CHARGE_KW || value.chargeCableConnected == false) return false
        val freshSpeed = value.speedKmh?.takeIf {
            it.isFinite() && value.speedAt != null &&
                value.timestamp - value.speedAt in 0..SPEED_FRESH_MS
        }
        if (freshSpeed != null && freshSpeed > STATIONARY_KMH) return false
        return value.chargeCableConnected == true || (freshSpeed != null && freshSpeed <= STATIONARY_KMH)
    }

    private fun integrateCandidates(values: List<BmsReading>): Double =
        values.zipWithNext().sumOf { (a, b) -> trapezoidKwh(a, b) }

    private fun observedDuration(values: List<BmsReading>): Long =
        values.zipWithNext().sumOf { (a, b) ->
            (b.timestamp - a.timestamp).takeIf { it in 1..MAX_INTEGRATION_GAP_MS } ?: 0L
        }

    private fun trapezoidKwh(a: BmsReading, b: BmsReading): Double {
        val delta = b.timestamp - a.timestamp
        if (delta !in 1..MAX_INTEGRATION_GAP_MS) return 0.0
        val p0 = a.powerKw ?: return 0.0
        val p1 = b.powerKw ?: return 0.0
        return max(0.0, -(p0 + p1) / 2.0 * delta / 3_600_000.0)
    }

    private fun finish(context: Context, atMillis: Long) {
        val current = mutableActive.value ?: return
        val ended = current.copy(endedAtMillis = max(current.startedAtMillis, atMillis),
            durationMillis = max(0L, atMillis - current.startedAtMillis))
        persist(context, ended)
        mutableHistory.value = (mutableHistory.value + ended).takeLast(MAX_HISTORY)
        mutableActive.value = null
        lastQualifying = null
        integratedKwh = 0.0
        pendingStop.clear()
    }

    private fun Double?.validSoc(): Double? = this?.takeIf { it.isFinite() && it in 0.0..100.0 }
    private fun Double?.validEnergy(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }
    private fun socGain(start: Double?, end: Double?): Double? {
        val a = start.validSoc(); val b = end.validSoc()
        return if (a != null && b != null) max(0.0, b - a) else null
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    private fun persist(context: Context, session: ChargeSession) {
        val row = JSONObject()
            .put("id", session.id).put("started_at_ms", session.startedAtMillis)
            .put("ended_at_ms", session.endedAtMillis ?: JSONObject.NULL)
            .put("first_soc_percent", session.firstSocPercent ?: JSONObject.NULL)
            .put("last_soc_percent", session.lastSocPercent ?: JSONObject.NULL)
            .put("first_remaining_estimate_kwh", session.firstRemainingEstimateKwh ?: JSONObject.NULL)
            .put("last_remaining_estimate_kwh", session.lastRemainingEstimateKwh ?: JSONObject.NULL)
            .put("battery_energy_added_kwh", session.batteryEnergyAddedKwh)
            .put("energy_basis", session.energyBasis)
            .put("charge_kind", session.chargeKind)
            .put("observed_power_duration_ms", session.observedPowerDurationMillis)
            .put("power_coverage_percent", session.powerCoveragePercent)
            .put("soc_gained_percent", session.socGainedPercent ?: JSONObject.NULL)
            .put("duration_ms", session.durationMillis)
            .put("observation_count", session.observationCount)
        file(context).appendText(row.toString() + "\n", Charsets.UTF_8)
    }

    private fun ensureLoaded(context: Context) {
        val path = file(context).absolutePath
        if (loaded && loadedPath == path) return
        val latest = LinkedHashMap<String, ChargeSession>()
        val source = file(context)
        if (source.isFile) source.forEachLine(Charsets.UTF_8) { line ->
            runCatching {
                val row = JSONObject(line)
                val verifiedIntegral = row.optString("energy_basis") == "negative_power_integral"
                val session = ChargeSession(
                    row.getString("id"), row.getLong("started_at_ms"), row.optLongOrNull("ended_at_ms"),
                    row.optDoubleOrNull("first_soc_percent"), row.optDoubleOrNull("last_soc_percent"),
                    row.optDoubleOrNull("first_remaining_estimate_kwh") ?: row.optDoubleOrNull("first_battery_energy_kwh"),
                    row.optDoubleOrNull("last_remaining_estimate_kwh") ?: row.optDoubleOrNull("last_battery_energy_kwh"),
                    if (verifiedIntegral) row.getDouble("battery_energy_added_kwh") else 0.0,
                    if (verifiedIntegral) "negative_power_integral" else "unverified_legacy",
                    row.optDoubleOrNull("soc_gained_percent"), row.getLong("duration_ms"),
                    row.getInt("observation_count"),
                    if (verifiedIntegral) row.optLong("observed_power_duration_ms") else 0L,
                    row.optString("charge_kind", "unknown").takeIf { it in listOf("ac", "dc") } ?: "unknown"
                )
                latest[session.id] = session
            }
        }
        mutableHistory.value = latest.values.filter { it.endedAtMillis != null }.takeLast(MAX_HISTORY)
        mutableActive.value = latest.values.lastOrNull { it.endedAtMillis == null }
        lastQualifying = null // A new snapshot will close a stale session after the telemetry gap.
        integratedKwh = mutableActive.value?.batteryEnergyAddedKwh ?: 0.0
        pendingStart.clear(); pendingStop.clear()
        loadedPath = path
        loaded = true
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else getLong(key)
}
