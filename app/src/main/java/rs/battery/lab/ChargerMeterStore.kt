package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** Manual kWh shown by the charger meter, distinct from the vehicle's battery-side telemetry. */
internal data class ChargerMeterEntry(val id: String, val chargeId: String?, val recordedAt: Long, val deliveredKwh: Double)

internal object ChargerMeterStore {
    val entries = MutableStateFlow<List<ChargerMeterEntry>>(emptyList())
    private var loaded = false
    private fun file(context: Context) = File(context.filesDir, "charger-meter.jsonl")

    @Synchronized fun reload(context: Context) {
        loaded = false
        load(context)
    }

    @Synchronized fun load(context: Context) {
        if (loaded) return
        entries.value = if (file(context).isFile) file(context).useLines { lines -> lines.mapNotNull { line ->
            runCatching { parse(JSONObject(line)) }.getOrNull()
        }.toList() } else emptyList()
        loaded = true
    }

    @Synchronized fun record(context: Context, chargeId: String?, deliveredKwh: Double): ChargerMeterEntry {
        require(deliveredKwh.isFinite() && deliveredKwh in 0.01..500.0) { "Neispravan iznos kWh" }
        load(context)
        val now = System.currentTimeMillis()
        val id = if (chargeId == null) "manual-$now" else
            entries.value.lastOrNull { it.chargeId == chargeId }?.id ?: chargeId
        val item = ChargerMeterEntry(id, chargeId, now, deliveredKwh)
        entries.value = (entries.value.filterNot { it.id == id || (chargeId != null && it.chargeId == chargeId) } + item).sortedBy { it.recordedAt }
        file(context).writeText(entries.value.joinToString("\n") { toJson(it).toString() } + "\n", Charsets.UTF_8)
        return item
    }

    /** Link a charger display entry made while OBD was asleep to the inferred before/after interval. */
    @Synchronized fun attachStandaloneToGap(context: Context, gapId: String, beforeAt: Long, afterAt: Long) {
        load(context)
        if (entries.value.any { it.chargeId == gapId }) return
        val candidates = entries.value.filter { it.chargeId == null && it.recordedAt in beforeAt..(afterAt + 10 * 60_000L) }
        val candidate = candidates.singleOrNull() ?: return
        entries.value = entries.value.map { if (it.id == candidate.id) it.copy(chargeId = gapId) else it }
        file(context).writeText(entries.value.joinToString("\n") { toJson(it).toString() } + "\n", Charsets.UTF_8)
    }

    private fun toJson(item: ChargerMeterEntry) = JSONObject()
        .put("id", item.id).put("charge_id", item.chargeId ?: JSONObject.NULL)
        .put("recorded_at_ms", item.recordedAt).put("charger_delivered_kwh", item.deliveredKwh)
        .put("source", "manual charger display")
    private fun parse(j: JSONObject) = ChargerMeterEntry(j.getString("id"),
        if (j.isNull("charge_id")) null else j.getString("charge_id"),
        j.getLong("recorded_at_ms"), j.getDouble("charger_delivered_kwh"))
}
