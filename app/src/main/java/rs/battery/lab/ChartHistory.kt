package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.TreeMap

/** Bounded scalar history for charts; the full sensor arrays remain in the telemetry log. */
internal object ChartHistory {
    private const val MAX_READINGS = 30_000
    private val lock = Any()
    val readings = MutableStateFlow<List<BmsReading>>(emptyList())

    private var generation = 0L

    fun clear() = synchronized(lock) {
        generation++
        readings.value = emptyList()
    }

    /** Call from an IO dispatcher. A concurrent append wins over a row loaded from disk. */
    fun load(context: Context) {
        val initialGeneration = synchronized(lock) { generation }
        val dir = context.applicationContext.filesDir
        val loaded = TreeMap<Long, BmsReading>()
        for (name in listOf("bms-readings-previous.jsonl", "bms-readings.jsonl")) {
            val file = File(dir, name)
            if (!file.isFile) continue
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        parseLine(line)?.let { loaded[it.timestamp] = it }
                        trim(loaded)
                    }
                }
            }
        }
        synchronized(lock) {
            if (initialGeneration != generation) return
            // Snapshots added while the files were being read must not be lost.
            readings.value.forEach { loaded[it.timestamp] = it }
            trim(loaded)
            readings.value = loaded.values.toList()
        }
    }

    /** Adds a real reading; duplicate timestamps use the latest snapshot. */
    fun append(reading: BmsReading) {
        if (reading.timestamp <= 0L) return
        synchronized(lock) {
            val ordered = readings.value.toMutableList()
            val position = ordered.binarySearchBy(reading.timestamp) { it.timestamp }
            if (position >= 0) ordered[position] = reading.scalarOnly()
            else ordered.add(-position - 1, reading.scalarOnly())
            if (ordered.size > MAX_READINGS) ordered.subList(0, ordered.size - MAX_READINGS).clear()
            readings.value = ordered
        }
    }

    private fun trim(rows: TreeMap<Long, BmsReading>) {
        while (rows.size > MAX_READINGS) rows.pollFirstEntry()
    }

    /** Invalid JSON and rows without a valid timestamp are ignored. Missing values stay null. */
    internal fun parseLine(line: String): BmsReading? = runCatching {
        val row = JSONObject(line)
        val timestamp = row.number("timestamp_ms")?.toLong()?.takeIf { it > 0L } ?: return@runCatching null
        BmsReading(
            timestamp = timestamp,
            soc = row.double("soc_percent"), soh = row.double("soh_percent"),
            voltage = row.double("battery_voltage_v"), current = row.double("battery_current_a"),
            energy = row.double("battery_energy_kwh"), odometerKm = row.double("odometer_km"),
            tempMin = row.double("battery_temp_min_c"), tempMax = row.double("battery_temp_max_c"),
            tempAvg = row.double("battery_temp_avg_c"),
            cellMin = row.double("cell_voltage_min_v"), cellMax = row.double("cell_voltage_max_v"),
            cellAverageVoltage = row.double("cell_voltage_average_v"),
            cellVoltageSum = row.double("cell_voltage_sum_v"),
            lowestCellIndex = row.int("lowest_cell_index"),
            highestCellIndex = row.int("highest_cell_index"),
            hottestProbeIndex = row.int("hottest_probe_index"),
            coldestProbeIndex = row.int("coldest_probe_index"),
            insulationKohm = row.double("insulation_kohm"),
            auxiliaryVoltage = row.double("auxiliary_voltage_v"),
            outsideTemp = row.double("outside_temp_c"), motorTemp = row.double("motor_temp_c"),
            dcdcTemp = row.double("dcdc_temp_c"), obcTemp = row.double("obc_temp_c"),
            heatingCoolantTemp = row.double("heating_coolant_temp_c"),
            motorCoolantTemp = row.double("motor_coolant_temp_c"),
            tractionCoolantTemp = row.double("traction_coolant_temp_c"),
            inverterTemp = row.double("inverter_temp_c"), motorPowerKw = row.double("motor_power_kw"),
            chargeCableConnected = row.boolean("charge_cable_connected"),
            chargerHvVoltage = row.double("charger_hv_voltage_v"),
            gridVoltage = row.double("grid_voltage_v"),
            lifetimeEnergyInKwh = row.double("lifetime_energy_in_kwh"),
            lifetimeEnergyOutKwh = row.double("lifetime_energy_out_kwh"),
            lifetimeCountersAt = row.long("lifetime_counters_at_ms"),
            speedKmh = row.double("vehicle_speed_kmh"), speedAt = row.long("vehicle_speed_at_ms"),
            cellVoltagesAt = row.long("cell_voltages_at_ms"),
            probeTemperaturesAt = row.long("probe_temperatures_at_ms"),
            cellSohAt = row.long("cell_soh_at_ms"),
            bmsExtraAt = row.long("bms_extra_at_ms"), vcuExtraAt = row.long("vcu_extra_at_ms"),
            inverterAt = row.long("inverter_at_ms"), chargerAt = row.long("charger_at_ms")
        )
    }.getOrNull()

    private fun JSONObject.number(key: String): Number? = opt(key) as? Number
    private fun JSONObject.double(key: String): Double? = number(key)?.toDouble()?.takeIf { it.isFinite() }
    private fun JSONObject.long(key: String): Long? = number(key)?.toLong()?.takeIf { it > 0L }
    private fun JSONObject.int(key: String): Int? = number(key)?.toInt()
    private fun JSONObject.boolean(key: String): Boolean? = opt(key) as? Boolean

    private fun BmsReading.scalarOnly(): BmsReading = copy(
        cellVoltages = emptyList(), probeTemperatures = emptyList(), cellSoh = emptyList(),
        measurementWarnings = emptyList()
    )
}
