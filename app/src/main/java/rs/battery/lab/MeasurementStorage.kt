package rs.battery.lab

import android.content.Context
import java.io.File
import java.io.IOException

internal object MeasurementStorage {
    private val logs = listOf(
        "bms-readings.jsonl", "bms-readings-previous.jsonl", "trip-sessions.jsonl",
        "charge-sessions.jsonl", "battery-days.jsonl", "weather-samples.jsonl", "charger-meter.jsonl",
        "tire-history.jsonl", "charge-gaps.jsonl", "untracked-intervals.jsonl", VoltageSocCalibration.FILE_NAME
    )
    private val historyKeys = listOf("records", "samples", "active_trip_v2", "last_bms_for_charge_gap")
    private val exportName = Regex("e208-merenje-[0-9]{8}-[0-9]{6}\\.zip")

    private fun files(context: Context): List<File> = buildList {
        addAll(logs.map { File(context.filesDir, it) }.filter { it.isFile })
        for (name in listOf("gps_tracks", "gps_exports")) {
            addAll(File(context.filesDir, name).listFiles().orEmpty().filter { it.isFile })
        }
        addAll(context.cacheDir.listFiles().orEmpty().filter { it.isFile && exportName.matches(it.name) })
    }

    @Synchronized fun byteCount(context: Context): Long {
        val prefs = context.getSharedPreferences("battery_lab", Context.MODE_PRIVATE)
        val preferenceBytes = historyKeys.sumOf { key ->
            prefs.getString(key, null)?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        }
        return files(context).sumOf { it.length() } + preferenceBytes
    }

    @Synchronized fun export(context: Context): File =
        TelemetryStore.makeExport(context, GpsTracker.exportFiles(context))

    /** Called after the service has closed trips and unregistered GPS. Keep the separate last reading. */
    @Synchronized fun clear(context: Context) {
        if (!RecordingControl.discardHistory(context)) throw IOException("Režim snimanja nije sačuvan.")
        GpsTracker.forgetHistory()
        // Reconciliation on startup can still be scanning the old log and writing inferred sessions.
        synchronized(ChargeGapMonitor) {
            val failedFiles = files(context).filter { !it.delete() && it.exists() }
            val edit = context.getSharedPreferences("battery_lab", Context.MODE_PRIVATE).edit()
            historyKeys.forEach { edit.remove(it) }
            val prefsSaved = edit.commit()
            ChartHistory.clear()
            VoltageSocCalibration.reset()
            ObdBus.samples.value = emptyList()
            TripMonitor.reload(context)
            ChargeMonitor.reload(context)
            ChargerMeterStore.reload(context)
            ChargeGapMonitor.reload(context)
            BatteryHistory.reload(context)
            WeatherTracker.latest.value = null
            if (failedFiles.isNotEmpty()) {
                throw IOException("Preostale datoteke: " + failedFiles.joinToString { it.name })
            }
            if (!prefsSaved) throw IOException("Android nije potvrdio brisanje sačuvanih sažetaka.")
        }
    }
}
