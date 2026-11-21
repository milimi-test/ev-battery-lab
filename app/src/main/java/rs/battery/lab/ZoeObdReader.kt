package rs.battery.lab

import java.io.IOException
import java.util.Locale

/** Independently implemented reader using the protocol facts in CanZE ZOE/_Fields.csv and
 * ZOE_Ph2/_Fields.csv. Only 21/22 read requests; no writes, resets or security access. */
internal class ZoeObdReader(private val link: ElmLink, private val phase2: Boolean,
    private val onVehicleResponse: () -> Unit = {}, private val clock: () -> Long = System::currentTimeMillis) : BatteryReader {
    private var address = ""
    private var pass = 0
    private var cells = emptyList<Double>()
    private var temperatures = emptyList<Double>()
    private var cellsAt: Long? = null
    private var probesAt: Long? = null
    private val pendingCells = arrayOfNulls<Double>(96)
    private var cursor = 0
    private var sweepStart = 0L
    private var longReplies = true
    private var failures = 0

    companion object {
        // Renault leaves 9040, 9060 and 9080 unused. They are NOT cell numbers.
        val PHASE2_CELL_DIDS = (0x9021..0x9083).filter { it !in setOf(0x9040, 0x9060, 0x9080) }
        fun currentFromEvc(raw: Long?, phase2: Boolean): Double? = raw?.let {
            // CanZE's current is positive while charging; Battery Lab is positive on discharge.
            -((it - if (phase2) 2000 else 32768) * .25)
        }?.takeIf { it in -600.0..600.0 }
    }

    override fun initialize() {
        link.command("ATZ", 2500)
        listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAL", "ATCAF1", "ATAT1", "ATST64",
            if (phase2) "ATSP7" else "ATSP6").forEach(::requireOk)
        if (phase2) requireOk("ATCP18")
        address = ""
        selectBms()
    }

    private fun requireOk(command: String) {
        val reply = link.command(command)
        if (!reply.uppercase().contains("OK")) throw IOException("Zoe: adapter nije prihvatio $command")
    }

    private fun select(request: String, response: String) {
        if (address == request) return
        requireOk("ATSH${if (phase2) request.takeLast(6) else request}")
        requireOk("ATCRA$response")
        val results = listOf("ATFCSH$request", "ATFCSD300000", "ATFCSM1").map { link.command(it) }
        longReplies = results.all { it.uppercase().contains("OK") }
        address = request
    }
    private fun selectBms() = if (phase2) select("18DADBF1", "18DAF1DB") else select("79B", "7BB")
    private fun selectEvc() = if (phase2) select("18DADAF1", "18DAF1DA") else select("7E4", "7EC")

    private fun readData(command: String, count: Int): List<Int>? {
        check(command.startsWith("21") || command.startsWith("22"))
        val prefix = (if (command.startsWith("21")) "61" else "62") + command.drop(2)
        val response = try { link.command(command, if (count > 7) 2200 else 1100) }
            catch (_: ElmReplyTimeout) { failures++; return null }
        return zoePayload(response, prefix, count).also { if (it == null) failures++ }
    }
    private fun number(command: String, bytes: Int = 2): Long? = readData(command, bytes)?.unsignedAt(0, bytes)
    private fun scaled(command: String, bytes: Int = 2, factor: Double, offset: Long = 0,
        valid: ClosedFloatingPointRange<Double>): Double? = number(command, bytes)?.let { (it - offset) * factor }?.takeIf { it in valid }

    override fun read(): BmsReading {
        failures = 0
        selectBms()
        if (phase2) readPhase2Cells() else if (pass % 4 == 0) readPhase1Cells()
        if (pass % 6 == 0) readTemperatures()

        val soc: Double?
        val soh: Double?
        val voltage: Double?
        val energy: Double?
        val average: Double?
        val minimum: Double?
        val maximum: Double?
        val freshTemperatures = temperatures.takeIf { probesAt?.let { at -> clock() - at in 0..120_000 } == true }
        var minTemp = freshTemperatures?.minOrNull()
        var maxTemp = freshTemperatures?.maxOrNull()
        var avgTemp = freshTemperatures?.takeIf { it.isNotEmpty() }?.average()
        if (phase2) {
            soc = scaled("229002", factor = .01, valid = 0.0..100.0)
            soh = scaled("229003", factor = .01, valid = 0.0..110.0)
            energy = scaled("2291C8", 3, .001, valid = 0.0..65.0)
            voltage = scaled("229005", factor = .1, valid = 200.0..450.0)
            minimum = scaled("229009", factor = 1.0 / 1024, valid = 2.0..4.5)
            maximum = scaled("229007", factor = 1.0 / 1024, valid = 2.0..4.5)
            average = null // Full cell scan is timestamped separately; never call it an instantaneous average.
            avgTemp = scaled("229012", factor = .0625, offset = 640, valid = -40.0..100.0)
            minTemp = scaled("229013", factor = .0625, offset = 640, valid = -40.0..100.0)
            maxTemp = scaled("229014", factor = .0625, offset = 640, valid = -40.0..100.0)
            selectEvc()
        } else {
            selectEvc()
            soc = scaled("222002", factor = .02, valid = 0.0..100.0)
            soh = scaled("223206", 1, 1.0, valid = 0.0..110.0)
            energy = scaled("22320C", factor = .005, valid = 0.0..55.0)
            voltage = scaled("223203", factor = .5, valid = 200.0..450.0)
            val freshCells = cells.takeIf { cellsAt?.let { at -> clock() - at in 0..120_000 } == true }
            minimum = freshCells?.minOrNull(); maximum = freshCells?.maxOrNull(); average = null
        }
        val current = currentFromEvc(number(if (phase2) "2221CC" else "223204"), phase2)
        val speed = scaled("222003", factor = .01, valid = 0.0..250.0)
        val speedAt = speed?.let { clock() }
        val odo = scaled("222006", 3, 1.0, valid = 0.0..2_000_000.0)
        val aux = if (pass % 6 == 0) scaled("222005", factor = .01, valid = 8.0..16.0) else null
        val outside = if (phase2 && pass % 6 == 0) scaled("2220DE", factor = .1, offset = 2730, valid = -50.0..70.0) else null
        if (soc == null && voltage == null && energy == null)
            throw IOException("Zoe ${if (phase2) "faza 2" else "faza 1"}: nema osnovnog odgovora. Proveri izabranu generaciju i kontakt vozila.")
        onVehicleResponse()
        pass++
        val warnings = buildList {
            if (failures > 0) add("Zoe: $failures upita bez validnog odgovora; nepoznata polja ostaju prazna.")
            if (cells.isEmpty()) add(if (phase2) "Očitavanje 96 grupa: ${pendingCells.count { it != null }}/96. Kompletan ciklus traje više osvežavanja."
                else "Čekam kompletne napone 96 grupa (2141/2142).")
            if (!longReplies && !phase2) add("Adapter ne podržava duge odgovore za napone i sonde.")
            if (cellsAt?.let { clock() - it > 120_000 } == true) add("Naponi grupa: prethodni ciklus je stariji od 2 min.")
            if (probesAt?.let { clock() - it > 120_000 } == true) add("Temperature sondi: prethodni ciklus je stariji od 2 min.")
        }
        return BmsReading(soc = soc, soh = soh, voltage = voltage, current = current, energy = energy,
            odometerKm = odo, tempMin = minTemp, tempMax = maxTemp, tempAvg = avgTemp,
            cellMin = minimum, cellMax = maximum, timestamp = clock(), cellVoltages = cells,
            cellVoltagesAt = cellsAt, probeTemperatures = temperatures, probeTemperaturesAt = probesAt,
            cellAverageVoltage = average, speedKmh = speed, speedAt = speedAt, auxiliaryVoltage = aux,
            outsideTemp = outside, measurementWarnings = warnings)
    }

    private fun readPhase1Cells() {
        if (!longReplies) return
        val first = readData("2141", 124)
        val second = readData("2142", 68)
        if (first == null || second == null) return
        val values = (first + second).chunked(2).map { (it[0] * 256 + it[1]) / 1000.0 }
        if (values.size == 96 && values.all { it in 2.0..4.5 }) { cells = values; cellsAt = clock() }
    }

    private fun readPhase2Cells() {
        if (cursor == 0) { pendingCells.fill(null); sweepStart = clock() }
        repeat(8) {
            val did = PHASE2_CELL_DIDS[cursor]
            pendingCells[cursor] = scaled("22%04X".format(Locale.US, did), factor = 1.0 / 1024, valid = 2.0..4.5)
            cursor++
        }
        if (cursor == 96) {
            if (pendingCells.all { it != null } && clock() - sweepStart <= 120_000) {
                cells = pendingCells.map { it!! }
                cellsAt = sweepStart // conservative age: first cell, not completion of the scan
            }
            cursor = 0
        }
    }

    private fun readTemperatures() {
        val values = if (phase2) (0 until 12).map {
            scaled("22%04X".format(Locale.US, 0x9131 + it), factor = .0625, offset = 640, valid = -40.0..100.0)
        } else {
            if (!longReplies) return
            val data = readData("2104", 36) ?: return
            (0 until 12).map { (data[2 + it * 3] - 40).toDouble().takeIf { t -> t in -40.0..100.0 } }
        }
        if (values.all { it != null }) { temperatures = values.map { it!! }; probesAt = clock() }
    }
}
