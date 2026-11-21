package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** A likely charge while OBD was unavailable, inferred only from before/after BMS snapshots. */
internal data class ChargeGapRecord(val id: String, val beforeAt: Long, val afterAt: Long,
    val socBefore: Double, val socAfter: Double, val remainingBeforeKwh: Double?,
    val remainingAfterKwh: Double?, val odometerBeforeKm: Double?, val odometerAfterKm: Double?) {
    val socGain: Double get() = socAfter - socBefore
    val remainingEstimateChangeKwh: Double? get() = if (remainingBeforeKwh != null && remainingAfterKwh != null)
        remainingAfterKwh - remainingBeforeKwh else null
    val untrackedDistanceKm: Double? get() = if (odometerBeforeKm != null && odometerAfterKm != null)
        (odometerAfterKm - odometerBeforeKm).takeIf { it > 0 } else null
}

internal data class UntrackedInterval(val id: String, val beforeAt: Long, val afterAt: Long,
    val odometerBeforeKm: Double, val odometerAfterKm: Double) {
    val distanceKm: Double get() = odometerAfterKm - odometerBeforeKm
}

internal object ChargeGapMonitor {
    val history = MutableStateFlow<List<ChargeGapRecord>>(emptyList())
    val untracked = MutableStateFlow<List<UntrackedInterval>>(emptyList())
    private var loaded = false
    private const val LAST_KEY = "last_bms_for_charge_gap"
    private fun file(context: Context) = File(context.filesDir, "charge-gaps.jsonl")
    private fun movementFile(context: Context) = File(context.filesDir, "untracked-intervals.jsonl")

    @Synchronized fun reload(context: Context) {
        loaded = false
        load(context)
    }

    @Synchronized fun load(context: Context) {
        if (loaded) return
        history.value = if (file(context).isFile) file(context).useLines { lines -> lines.mapNotNull { line ->
            runCatching { parse(JSONObject(line)) }.getOrNull()
        }.toList() } else emptyList()
        untracked.value = if (movementFile(context).isFile) movementFile(context).useLines { lines -> lines.mapNotNull { line ->
            runCatching { val j = JSONObject(line); UntrackedInterval(j.getString("id"), j.getLong("before_at_ms"),
                j.getLong("after_at_ms"), j.getDouble("odometer_before_km"), j.getDouble("odometer_after_km")) }.getOrNull()
        }.toList() } else emptyList()
        loaded = true
    }

    /** Recover intervals from prior raw logs after an app update; run on a background thread. */
    fun reconcile(context: Context) {
        load(context)
        val recordingToken = RecordingControl.token() ?: return
        val source = File(context.filesDir, "bms-readings.jsonl")
        if (!source.isFile) return
        var before: Snapshot? = null
        source.forEachLine(Charsets.UTF_8) { line ->
            val current = runCatching { JSONObject(line).let { j -> Snapshot(j.optLong("timestamp_ms"),
                j.optDouble("soc_percent").takeUnless { it.isNaN() },
                j.optDouble("battery_energy_kwh").takeUnless { it.isNaN() },
                j.optDouble("odometer_km").takeUnless { it.isNaN() }) } }.getOrNull()
            if (current != null) {
                before?.takeIf { current.time - it.time in 2 * 60_000L..48 * 60 * 60_000L }?.let {
                    recordInterval(context, it, current, recordingToken)
                }
                before = current
            }
        }
    }

    @Synchronized fun onReading(context: Context, value: BmsReading) {
        val recordingToken = RecordingControl.token() ?: return
        load(context)
        val soc = value.soc?.takeIf { it in 0.0..100.0 }
        val prefs = context.getSharedPreferences("battery_lab", Context.MODE_PRIVATE)
        val previous = prefs.getString(LAST_KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (previous != null) {
            recordInterval(context, Snapshot(previous.optLong("time"), previous.optDouble("soc").takeUnless { it.isNaN() },
                previous.optDouble("remaining").takeUnless { it.isNaN() },
                previous.optDouble("odo").takeUnless { it.isNaN() }),
                Snapshot(value.timestamp, soc, value.energy, value.odometerKm), recordingToken)
        }
        RecordingControl.withActive(recordingToken) {
            prefs.edit().putString(LAST_KEY, JSONObject().put("time", value.timestamp).put("soc", soc)
                .put("remaining", value.energy).put("odo", value.odometerKm).toString()).apply()
        }
    }

    private data class Snapshot(val time: Long, val soc: Double?, val energy: Double?, val odo: Double?)

    @Synchronized private fun recordInterval(context: Context, before: Snapshot, after: Snapshot, recordingToken: Long) {
        RecordingControl.withActive(recordingToken) {
        if (RecordingControl.crossesPause(before.time, after.time)) return@withActive
        val gapMs = after.time - before.time
        if (gapMs !in 2 * 60_000L..48 * 60 * 60_000L) return@withActive
        val distance = if (before.odo != null && after.odo != null) after.odo - before.odo else null
        if (distance != null && distance >= 1.0) {
            val id = "unknown-${before.time}"
            if (untracked.value.none { it.id == id }) {
                val record = UntrackedInterval(id, before.time, after.time, before.odo!!, after.odo!!)
                movementFile(context).appendText(JSONObject().put("id", id).put("before_at_ms", before.time)
                    .put("after_at_ms", after.time).put("odometer_before_km", before.odo)
                    .put("odometer_after_km", after.odo).put("distance_km", distance).toString() + "\n")
                untracked.value = (untracked.value + record).takeLast(1000)
            }
        }
        val soc0 = before.soc ?: return@withActive
        val soc1 = after.soc ?: return@withActive
        if (soc1 - soc0 < 2.0 || (distance != null && distance < -0.1)) return@withActive
        val id = "gap-${before.time}"
        if (history.value.any { it.id == id }) return@withActive
        val record = ChargeGapRecord(id, before.time, after.time, soc0, soc1,
            before.energy, after.energy, before.odo, after.odo)
        file(context).appendText(toJson(record).toString() + "\n", Charsets.UTF_8)
        history.value = (history.value + record).takeLast(1000)
        ChargerMeterStore.attachStandaloneToGap(context, record.id, record.beforeAt, record.afterAt)
        }
    }

    private fun toJson(r: ChargeGapRecord) = JSONObject().put("id", r.id).put("before_at_ms", r.beforeAt)
        .put("after_at_ms", r.afterAt).put("soc_before_percent", r.socBefore).put("soc_after_percent", r.socAfter)
        .put("remaining_before_kwh", r.remainingBeforeKwh).put("remaining_after_kwh", r.remainingAfterKwh)
        .put("odometer_before_km", r.odometerBeforeKm).put("odometer_after_km", r.odometerAfterKm)
        .put("basis", "SOC increase during OBD gap; charging inferred, not directly observed")
    private fun parse(j: JSONObject): ChargeGapRecord {
        fun number(key: String) = j.optDouble(key).takeUnless { it.isNaN() }
        return ChargeGapRecord(j.getString("id"), j.getLong("before_at_ms"), j.getLong("after_at_ms"),
            j.getDouble("soc_before_percent"), j.getDouble("soc_after_percent"),
            number("remaining_before_kwh"), number("remaining_after_kwh"),
            number("odometer_before_km"), number("odometer_after_km"))
    }
}
