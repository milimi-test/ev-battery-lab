package rs.battery.lab

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Complete, unrounded sensor snapshots for later analysis; no synthetic values. */
internal object TelemetryStore {
    private const val LOG_NAME = "bms-readings.jsonl"
    private const val MAX_LOG_BYTES = 50L * 1024 * 1024

    @Synchronized fun append(context: Context, value: BmsReading) {
        val file = File(context.filesDir, LOG_NAME)
        if (file.length() > MAX_LOG_BYTES) {
            val previous = File(context.filesDir, "bms-readings-previous.jsonl")
            previous.delete()
            file.renameTo(previous)
        }
        val row = JSONObject()
            .put("timestamp_ms", value.timestamp)
            .put("soc_percent", value.soc)
            .put("soc_candidate_d862_percent", value.socCandidateD862)
            .put("soc_candidate_d863_percent", value.socCandidateD863)
            .put("soc_candidate_d413_percent", value.socCandidateD413)
            .put("soc_candidates_at_ms", value.socCandidatesAt)
            .put("d412_counter_words", JSONArray(value.chargeCounterWords)).put("d412_at_ms", value.chargeCounterAt)
            .put("soh_percent", value.soh)
            .put("battery_voltage_v", value.voltage)
            .put("battery_current_a", value.current)
            .put("battery_power_kw", value.powerKw)
            .put("battery_energy_kwh", value.energy)
            .put("odometer_km", value.odometerKm)
            .put("battery_temp_min_c", value.tempMin)
            .put("battery_temp_max_c", value.tempMax)
            .put("battery_temp_avg_c", value.tempAvg)
            .put("cell_voltage_min_v", value.cellMin)
            .put("cell_voltage_max_v", value.cellMax)
            .put("cell_voltage_average_v", value.cellAverageVoltage)
            .put("cell_voltage_sum_v", value.cellVoltageSum)
            .put("lowest_cell_index", value.lowestCellIndex)
            .put("highest_cell_index", value.highestCellIndex)
            .put("hottest_probe_index", value.hottestProbeIndex)
            .put("coldest_probe_index", value.coldestProbeIndex)
            .put("insulation_kohm", value.insulationKohm)
            .put("auxiliary_voltage_v", value.auxiliaryVoltage)
            .put("outside_temp_c", value.outsideTemp)
            .put("motor_temp_c", value.motorTemp)
            .put("dcdc_temp_c", value.dcdcTemp)
            .put("obc_temp_c", value.obcTemp)
            .put("heating_coolant_temp_c", value.heatingCoolantTemp)
            .put("motor_coolant_temp_c", value.motorCoolantTemp)
            .put("traction_coolant_temp_c", value.tractionCoolantTemp)
            .put("inverter_temp_c", value.inverterTemp)
            .put("motor_power_kw", value.motorPowerKw)
            .put("charge_cable_connected", value.chargeCableConnected)
            .put("charger_hv_voltage_v", value.chargerHvVoltage)
            .put("grid_voltage_v", value.gridVoltage)
            .put("bms_extra_at_ms", value.bmsExtraAt)
            .put("vcu_extra_at_ms", value.vcuExtraAt)
            .put("inverter_at_ms", value.inverterAt)
            .put("charger_at_ms", value.chargerAt)
            .put("cell_voltages_v", JSONArray(value.cellVoltages))
            .put("cell_voltages_at_ms", value.cellVoltagesAt)
            .put("probe_temperatures_c", JSONArray(value.probeTemperatures))
            .put("probe_temperatures_at_ms", value.probeTemperaturesAt)
            .put("cell_soh_percent", JSONArray(value.cellSoh))
            .put("cell_soh_at_ms", value.cellSohAt)
            .put("lifetime_energy_in_kwh", value.lifetimeEnergyInKwh)
            .put("lifetime_energy_out_kwh", value.lifetimeEnergyOutKwh)
            .put("lifetime_counters_at_ms", value.lifetimeCountersAt)
            .put("vehicle_speed_kmh", value.speedKmh)
            .put("vehicle_speed_at_ms", value.speedAt)
            .put("warnings", JSONArray(value.measurementWarnings))
        file.appendText(row.toString() + "\n", Charsets.UTF_8)
        ChartHistory.append(value)
        if (!VehicleProfiles.current(context).isZoe) VoltageSocCalibration.observe(context, value)
    }

    fun makeExport(context: Context, extraFiles: List<File> = emptyList()): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archive = File(context.cacheDir, "e208-merenje-$stamp.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            val info = JSONObject()
                .put("format", "e208-battery-lab-2")
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("vehicle_profile", VehicleProfiles.current(context).id)
                .put("vehicle_name", VehicleProfiles.current(context).name)
                .put("vehicle_protocol", VehicleProfiles.current(context).protocol.name)
                .put("series_groups", VehicleProfiles.current(context).seriesGroups)
                .put("exported_at_ms", System.currentTimeMillis())
                .put("tire_profile", context.getSharedPreferences("battery_lab", Context.MODE_PRIVATE).getString("tire_profile", ""))
                .put("note", "BMS readings are read-only OBD data; GPS is from the phone. Null means no verified reading.")
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(info.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            (listOf(File(context.filesDir, LOG_NAME), File(context.filesDir, "bms-readings-previous.jsonl"),
                File(context.filesDir, "trip-sessions.jsonl"), File(context.filesDir, "charge-sessions.jsonl"),
                File(context.filesDir, "battery-days.jsonl"), File(context.filesDir, "weather-samples.jsonl"),
                File(context.filesDir, "charger-meter.jsonl"), File(context.filesDir, "tire-history.jsonl"),
                File(context.filesDir, "charge-gaps.jsonl"), File(context.filesDir, "untracked-intervals.jsonl"),
                File(context.filesDir, VoltageSocCalibration.FILE_NAME)) + extraFiles)
                .filter { it.isFile && it.length() > 0 }
                .distinctBy { it.absolutePath }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return archive
    }
}
