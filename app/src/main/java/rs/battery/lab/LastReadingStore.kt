package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One real BMS snapshot, kept separately from all recording history. */
internal object LastReadingStore {
    private const val PREFS = "battery_lab_last_reading"
    private const val KEY = "snapshot_json"
    private val lock = Any()
    val reading = MutableStateFlow<BmsReading?>(null)

    fun load(context: Context) = synchronized(lock) {
        val json = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        reading.value = json?.let { parse(it) }
    }

    /** Read only the end of the existing log on the first upgrade from an older version. */
    fun migrateIfMissing(context: Context, legacySummary: BmsReading?) = synchronized(lock) {
        if (reading.value != null) return@synchronized
        val latest = listOf("bms-readings.jsonl", "bms-readings-previous.jsonl").firstNotNullOfOrNull { name ->
            val file = java.io.File(context.filesDir, name)
            if (!file.isFile) return@firstNotNullOfOrNull null
            runCatching {
                java.io.RandomAccessFile(file, "r").use { input ->
                    val count = minOf(64 * 1024L, input.length()).toInt()
                    input.seek(input.length() - count)
                    val bytes = ByteArray(count)
                    input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8).lines().asReversed().firstNotNullOfOrNull { line ->
                        ChartHistory.parseLine(line)?.let { scalar ->
                            val row = JSONObject(line)
                            scalar.copy(cellVoltages = row.doubles("cell_voltages_v"),
                                probeTemperatures = row.doubles("probe_temperatures_c"), cellSoh = row.doubles("cell_soh_percent"))
                        }
                    }
                }
            }.getOrNull()
        }
        listOfNotNull(latest, legacySummary).maxByOrNull { it.timestamp }?.let { save(context, it) }
        Unit
    }

    /** Called for every actual BMS response, including while recording is switched off. */
    fun save(context: Context, value: BmsReading): Boolean = synchronized(lock) {
        if (value.timestamp <= 0 || listOf(value.soc, value.voltage, value.energy).all { it == null }) return@synchronized false
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(value).toString()).commit()
        if (stored) reading.value = value
        stored
    }

    private fun encode(r: BmsReading) = JSONObject()
        .put("at", r.timestamp).put("soc", r.soc).put("soh", r.soh)
        .put("voltage", r.voltage).put("current", r.current).put("energy", r.energy)
        .put("odo", r.odometerKm).put("tmin", r.tempMin).put("tmax", r.tempMax).put("tavg", r.tempAvg)
        .put("vmin", r.cellMin).put("vmax", r.cellMax).put("vavg", r.cellAverageVoltage)
        .put("vsum", r.cellVoltageSum).put("imin", r.lowestCellIndex).put("imax", r.highestCellIndex)
        .put("thot", r.hottestProbeIndex).put("tcold", r.coldestProbeIndex)
        .put("isolation", r.insulationKohm).put("aux", r.auxiliaryVoltage)
        .put("outside", r.outsideTemp).put("motor", r.motorTemp).put("dcdc", r.dcdcTemp)
        .put("obc", r.obcTemp).put("heater", r.heatingCoolantTemp)
        .put("motorCoolant", r.motorCoolantTemp).put("tractionCoolant", r.tractionCoolantTemp)
        .put("inverter", r.inverterTemp).put("motorPower", r.motorPowerKw)
        .put("cable", r.chargeCableConnected).put("chargerVoltage", r.chargerHvVoltage)
        .put("gridVoltage", r.gridVoltage)
        .put("speed", r.speedKmh).put("speedAt", r.speedAt)
        .put("cellsAt", r.cellVoltagesAt).put("probesAt", r.probeTemperaturesAt)
        .put("cellSohAt", r.cellSohAt).put("bmsExtraAt", r.bmsExtraAt)
        .put("vcuExtraAt", r.vcuExtraAt).put("inverterAt", r.inverterAt).put("chargerAt", r.chargerAt)
        .put("counterIn", r.lifetimeEnergyInKwh).put("counterOut", r.lifetimeEnergyOutKwh)
        .put("countersAt", r.lifetimeCountersAt)
        .put("d862", r.socCandidateD862).put("d863", r.socCandidateD863).put("d413", r.socCandidateD413)
        .put("socCandidatesAt", r.socCandidatesAt)
        .put("d412", JSONArray(r.chargeCounterWords)).put("d412At", r.chargeCounterAt)
        .put("cells", JSONArray(r.cellVoltages)).put("probes", JSONArray(r.probeTemperatures))
        .put("cellSoh", JSONArray(r.cellSoh))

    private fun parse(raw: String): BmsReading? = runCatching {
        val j = JSONObject(raw)
        val timestamp = j.long("at") ?: return@runCatching null
        BmsReading(
            timestamp = timestamp, soc = j.double("soc"), soh = j.double("soh"),
            voltage = j.double("voltage"), current = j.double("current"), energy = j.double("energy"),
            odometerKm = j.double("odo"), tempMin = j.double("tmin"), tempMax = j.double("tmax"),
            tempAvg = j.double("tavg"), cellMin = j.double("vmin"), cellMax = j.double("vmax"),
            chargeCounterWords = j.doubles("d412").map { it.toInt() }, chargeCounterAt = j.long("d412At"),
            socCandidateD862 = j.double("d862"), socCandidateD863 = j.double("d863"),
            socCandidateD413 = j.double("d413"), socCandidatesAt = j.long("socCandidatesAt"),
            cellAverageVoltage = j.double("vavg"), cellVoltageSum = j.double("vsum"),
            lowestCellIndex = j.int("imin"), highestCellIndex = j.int("imax"),
            hottestProbeIndex = j.int("thot"), coldestProbeIndex = j.int("tcold"),
            insulationKohm = j.double("isolation"), auxiliaryVoltage = j.double("aux"),
            outsideTemp = j.double("outside"), motorTemp = j.double("motor"),
            dcdcTemp = j.double("dcdc"), obcTemp = j.double("obc"),
            heatingCoolantTemp = j.double("heater"), motorCoolantTemp = j.double("motorCoolant"),
            tractionCoolantTemp = j.double("tractionCoolant"), inverterTemp = j.double("inverter"),
            motorPowerKw = j.double("motorPower"), chargeCableConnected = j.boolean("cable"),
            chargerHvVoltage = j.double("chargerVoltage"), gridVoltage = j.double("gridVoltage"), speedKmh = j.double("speed"),
            speedAt = j.long("speedAt"), cellVoltagesAt = j.long("cellsAt"),
            probeTemperaturesAt = j.long("probesAt"), cellSohAt = j.long("cellSohAt"),
            bmsExtraAt = j.long("bmsExtraAt"), vcuExtraAt = j.long("vcuExtraAt"),
            inverterAt = j.long("inverterAt"), chargerAt = j.long("chargerAt"),
            lifetimeEnergyInKwh = j.double("counterIn"), lifetimeEnergyOutKwh = j.double("counterOut"),
            lifetimeCountersAt = j.long("countersAt"), cellVoltages = j.doubles("cells"),
            probeTemperatures = j.doubles("probes"), cellSoh = j.doubles("cellSoh")
        )
    }.getOrNull()

    private fun JSONObject.double(key: String) = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun JSONObject.long(key: String) = (opt(key) as? Number)?.toLong()?.takeIf { it > 0 }
    private fun JSONObject.int(key: String) = (opt(key) as? Number)?.toInt()
    private fun JSONObject.boolean(key: String) = opt(key) as? Boolean
    private fun JSONObject.doubles(key: String): List<Double> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { (array.opt(it) as? Number)?.toDouble()?.takeIf(Double::isFinite) }
    }
}
