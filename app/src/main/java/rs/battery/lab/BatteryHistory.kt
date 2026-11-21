package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

internal data class BatteryDay(val day: String, val time: Long, val soc: Double?, val soh: Double?,
    val cellSpreadMv: Double?, val meanCellVoltage: Double?, val meanTemp: Double?, val odometerKm: Double?)

/** One representative snapshot per day, with sensor freshness checked before accepting arrays. */
internal object BatteryHistory {
    val days = MutableStateFlow<List<BatteryDay>>(emptyList())
    private var loaded = false
    private fun file(context: Context) = File(context.filesDir, "battery-days.jsonl")

    @Synchronized fun reload(context: Context) {
        loaded = false
        load(context)
    }

    @Synchronized fun load(context: Context) {
        if (loaded) return
        days.value = if (file(context).exists()) file(context).useLines { it.mapNotNull { line ->
            runCatching { parse(JSONObject(line)) }.getOrNull()
        }.toList() } else emptyList()
        loaded = true
    }

    @Synchronized fun record(context: Context, reading: BmsReading) {
        load(context)
        val day = Instant.ofEpochMilli(reading.timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val freshCells = reading.cellVoltagesAt?.let { reading.timestamp - it in 0..45_000 } == true
        val freshTemps = reading.probeTemperaturesAt?.let { reading.timestamp - it in 0..45_000 } == true
        val spread = if (freshCells && reading.cellVoltages.size >= 100)
            (reading.cellVoltages.maxOrNull()!! - reading.cellVoltages.minOrNull()!!) * 1000 else null
        val meanCell = if (freshCells && reading.cellVoltages.size >= 100) reading.cellVoltages.average() else null
        val temp = if (freshTemps && reading.probeTemperatures.size >= 40) reading.probeTemperatures.average() else reading.tempAvg
        if (reading.soh == null && spread == null && temp == null) return
        val candidate = BatteryDay(day, reading.timestamp, reading.soc, reading.soh, spread, meanCell, temp, reading.odometerKm)
        val existing = days.value.find { it.day == day }
        val quality = { item: BatteryDay -> listOf(item.soh, item.cellSpreadMv, item.meanCellVoltage, item.meanTemp).count { it != null } }
        if (existing != null && quality(candidate) <= quality(existing)) return
        days.value = (days.value.filterNot { it.day == day } + candidate).sortedBy { it.day }
        file(context).writeText(days.value.joinToString("\n") { toJson(it).toString() } + "\n", Charsets.UTF_8)
    }

    private fun toJson(d: BatteryDay) = JSONObject().put("day", d.day).put("time_ms", d.time)
        .put("soc_percent", d.soc).put("soh_percent", d.soh).put("cell_spread_mv", d.cellSpreadMv)
        .put("mean_cell_voltage_v", d.meanCellVoltage).put("mean_temp_c", d.meanTemp).put("odometer_km", d.odometerKm)
    private fun parse(j: JSONObject): BatteryDay {
        fun number(key: String) = j.optDouble(key).takeUnless { it.isNaN() }
        return BatteryDay(j.getString("day"), j.getLong("time_ms"), number("soc_percent"), number("soh_percent"),
            number("cell_spread_mv"), number("mean_cell_voltage_v"), number("mean_temp_c"), number("odometer_km"))
    }
}
