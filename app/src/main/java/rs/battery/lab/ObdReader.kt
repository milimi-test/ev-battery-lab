package rs.battery.lab

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.util.Locale

/** Read-only UDS service 22 values published for the 2022 Peugeot e-208 BMS. */
internal data class BmsReading(
    val soc: Double? = null,
    val soh: Double? = null,
    val voltage: Double? = null,
    val current: Double? = null,
    val energy: Double? = null,
    val odometerKm: Double? = null,
    val tempMin: Double? = null,
    val tempMax: Double? = null,
    val tempAvg: Double? = null,
    val cellMin: Double? = null,
    val cellMax: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** D440: cell groups 1–108, volts. Empty when the adapter cannot receive long replies. */
    val cellVoltages: List<Double> = emptyList(),
    /** D442: temperature probes 1–54, degrees Celsius. */
    val probeTemperatures: List<Double> = emptyList(),
    /** D4B5: cell health 1–108, percent. */
    val cellSoh: List<Double> = emptyList(),
    /** D42E counters. "Into" includes regeneration, so it is not charger energy. */
    val lifetimeEnergyInKwh: Double? = null,
    val lifetimeEnergyOutKwh: Double? = null,
    val lifetimeCountersAt: Long? = null,
    /** D402 from the VCU, wheel speed in km/h. */
    val speedKmh: Double? = null,
    val speedAt: Long? = null,
    val cellVoltagesAt: Long? = null,
    val probeTemperaturesAt: Long? = null,
    val cellSohAt: Long? = null,
    val measurementWarnings: List<String> = emptyList(),
    /** Additional read-only measurements; null means the ECU has not answered. */
    val cellAverageVoltage: Double? = null,
    val cellVoltageSum: Double? = null,
    val lowestCellIndex: Int? = null,
    val highestCellIndex: Int? = null,
    /** D445/D446: zero-based BMS probe indices converted to 1..54. */
    val hottestProbeIndex: Int? = null,
    val coldestProbeIndex: Int? = null,
    val insulationKohm: Double? = null,
    val auxiliaryVoltage: Double? = null,
    val outsideTemp: Double? = null,
    val motorTemp: Double? = null,
    val dcdcTemp: Double? = null,
    val obcTemp: Double? = null,
    val heatingCoolantTemp: Double? = null,
    val motorCoolantTemp: Double? = null,
    val tractionCoolantTemp: Double? = null,
    val inverterTemp: Double? = null,
    val motorPowerKw: Double? = null,
    val chargeCableConnected: Boolean? = null,
    val chargerHvVoltage: Double? = null,
    val bmsExtraAt: Long? = null,
    val vcuExtraAt: Long? = null,
    val inverterAt: Long? = null,
    val chargerAt: Long? = null,
    /** Alternative BMS percentage scales; exact meaning (gross/min-cell/etc.) is unverified. */
    val socCandidateD862: Double? = null,
    val socCandidateD863: Double? = null,
    val socCandidateD413: Double? = null,
    val socCandidatesAt: Long? = null,
    val chargeCounterWords: List<Int> = emptyList(),
    val chargeCounterAt: Long? = null,
    val gridVoltage: Double? = null
) {
    val powerKw: Double? get() = if (voltage != null && current != null) voltage * current / 1000.0 else null
}

internal data class CapacityEstimate(val kwh: Double, val socChange: Double, val energyChange: Double)
internal data class DriveEstimate(val distanceKm: Double, val energyKwh: Double, val consumption: Double)
internal data class ChargeEstimate(val socChange: Double, val energyKwh: Double)

private const val MAX_SAMPLE_GAP_MS = 30 * 60 * 1000L

/** Group only consecutive observations of the same apparent drive or charge. */
private fun latestSegment(samples: List<BmsReading>, charging: Boolean): List<BmsReading> {
    val ordered = samples.sortedBy { it.timestamp }
    if (ordered.isEmpty()) return emptyList()
    val segment = mutableListOf(ordered.last())
    for (i in ordered.size - 2 downTo 0) {
        val previous = ordered[i]
        val next = segment.first()
        val km0 = previous.odometerKm ?: break
        val km1 = next.odometerKm ?: break
        val energy0 = previous.energy ?: break
        val energy1 = next.energy ?: break
        val soc0 = previous.soc ?: break
        val soc1 = next.soc ?: break
        if (next.timestamp - previous.timestamp !in 1..MAX_SAMPLE_GAP_MS) break
        if (km1 < km0 - 0.2) break
        if (charging) {
            if (km1 - km0 > 0.3 || energy1 < energy0 - 0.2 || soc1 < soc0 - 0.5) break
        } else {
            if (energy1 > energy0 + 0.3 || soc1 > soc0 + 0.5) break
        }
        segment.add(0, previous)
    }
    return segment
}

/**
 * Parse one complete UDS response from ELM text. CAF1/H0 long replies are rendered as a
 * hexadecimal length line followed by indexed `0:`, `1:` ... `F:` chunks. A missing frame is
 * discarded instead of silently shifting every subsequent cell value.
 */
internal fun parseDidPayload(raw: String, did: String, byteCount: Int): List<Int>? {
    if (!did.matches(Regex("[0-9A-Fa-f]{4}")) || byteCount !in 1..512) return null
    val marker = "62${did.uppercase(Locale.US)}"
    val lines = raw.uppercase(Locale.US).split('\r', '\n').map { it.trim().replace(" ", "").replace("\t", "") }
        .filter { it.isNotEmpty() }
    for ((index, line) in lines.withIndex()) {
        // The length is hexadecimal and includes the 3-byte 62/DID prefix.
        if (line.length in 1..3 && line.all { it.isHex() } &&
            index + 1 < lines.size && lines[index + 1].startsWith("0:")) {
            val declared = line.toInt(16)
            if (declared < byteCount + 3 || declared > 1024) continue
            val assembled = StringBuilder()
            var next = index + 1
            var frame = 0
            var valid = true
            while (next < lines.size && lines[next].length >= 2 && lines[next][1] == ':') {
                val part = lines[next]
                if (part[0].digitToIntOrNull(16) != frame % 16 || part.length % 2 != 0 ||
                    !part.substring(2).all { it.isHex() }) valid = false
                assembled.append(part.substring(2))
                frame++
                next++
            }
            if (valid && assembled.length >= declared * 2) {
                val payload = assembled.substring(0, declared * 2)
                if (payload.startsWith(marker)) return payload.substring(marker.length, marker.length + byteCount * 2)
                    .chunked(2).map { it.toInt(16) }
            }
        }
        // Single-frame replies sometimes carry a CAN header and byte count before 62/DID.
        if (line.length % 2 != 0 || !line.all { it.isHex() }) continue
        val at = line.indexOf(marker)
        if (at >= 0 && line.length >= at + marker.length + byteCount * 2)
            return line.substring(at + marker.length, at + marker.length + byteCount * 2)
                .chunked(2).map { it.toInt(16) }
    }
    return null
}

private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F'

private class ObdTimeoutException(value: String) : IOException("OBD odgovor nije stigao: $value")

internal fun capacityFromSamples(samples: List<BmsReading>): CapacityEstimate? {
    val segment = latestSegment(samples, charging = true)
    val end = segment.lastOrNull() ?: return null
    return segment.asSequence().mapNotNull { start ->
        val deltaSoc = end.soc?.minus(start.soc ?: return@mapNotNull null) ?: return@mapNotNull null
        val deltaEnergy = end.energy?.minus(start.energy ?: return@mapNotNull null) ?: return@mapNotNull null
        val tempA = start.tempAvg ?: return@mapNotNull null
        val tempB = end.tempAvg ?: return@mapNotNull null
        if (deltaSoc < 10 || deltaEnergy < 2 || kotlin.math.abs(tempB - tempA) > 8) return@mapNotNull null
        val kwh = deltaEnergy * 100.0 / deltaSoc
        if (kwh !in 8.0..80.0) null else CapacityEstimate(kwh, deltaSoc, deltaEnergy)
    }.maxByOrNull { it.socChange }
}

internal fun driveFromSamples(samples: List<BmsReading>): DriveEstimate? {
    val recent = latestSegment(samples, charging = false)
    val end = recent.lastOrNull() ?: return null
    val lastKm = end.odometerKm ?: return null
    val lastEnergy = end.energy ?: return null
    val start = recent.firstOrNull { sample ->
        val km = sample.odometerKm; val energy = sample.energy
        km != null && energy != null && lastKm - km >= 1 && energy - lastEnergy >= 0.1
    } ?: return null
    val distance = lastKm - start.odometerKm!!
    val energy = start.energy!! - lastEnergy
    return DriveEstimate(distance, energy, energy * 100.0 / distance)
}

internal fun chargeFromSamples(samples: List<BmsReading>): ChargeEstimate? {
    val recent = latestSegment(samples, charging = true)
    val end = recent.lastOrNull() ?: return null
    val endSoc = end.soc ?: return null; val endEnergy = end.energy ?: return null; val endKm = end.odometerKm ?: return null
    val start = recent.firstOrNull { sample ->
        val soc = sample.soc; val energy = sample.energy; val km = sample.odometerKm
        soc != null && energy != null && km != null && endSoc - soc >= 1 && endEnergy - energy >= 0.1 && endKm - km in -0.2..0.3
    } ?: return null
    return ChargeEstimate(endSoc - start.soc!!, endEnergy - start.energy!!)
}

internal class ObdReader(private val socket: BluetoothSocket, private val onVehicleResponse: () -> Unit = {}) : BatteryReader {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private var readCount = 0L
    private var longReplySupported = true
    private var cellVoltages = emptyList<Double>()
    private var probeTemperatures = emptyList<Double>()
    private var cellSoh = emptyList<Double>()
    private var cellsAt: Long? = null
    private var probesAt: Long? = null
    private var sohAt: Long? = null
    private var energyIn: Double? = null
    private var energyOut: Double? = null
    private var lifetimeCountersAt: Long? = null
    private var speed: Double? = null
    private var speedAt: Long? = null
    private var cellAverageVoltage: Double? = null
    private var cellVoltageSum: Double? = null
    private var lowestCellIndex: Int? = null
    private var highestCellIndex: Int? = null
    private var hottestProbeIndex: Int? = null
    private var coldestProbeIndex: Int? = null
    private var insulationKohm: Double? = null
    private var auxiliaryVoltage: Double? = null
    private var outsideTemp: Double? = null
    private var motorTemp: Double? = null
    private var dcdcTemp: Double? = null
    private var obcTemp: Double? = null
    private var heatingCoolantTemp: Double? = null
    private var motorCoolantTemp: Double? = null
    private var tractionCoolantTemp: Double? = null
    private var inverterTemp: Double? = null
    private var motorPowerKw: Double? = null
    private var chargeCableConnected: Boolean? = null
    private var chargerHvVoltage: Double? = null
    private var gridVoltage: Double? = null
    private var bmsExtraAt: Long? = null
    private var vcuExtraAt: Long? = null
    private var inverterAt: Long? = null
    private var chargerAt: Long? = null
    private var socCandidateD862: Double? = null
    private var socCandidateD863: Double? = null
    private var socCandidateD413: Double? = null
    private var socCandidatesAt: Long? = null
    private var chargeCounterWords = emptyList<Int>()
    private var chargeCounterAt: Long? = null

    override fun initialize() {
        command("ATZ", 2500)
        listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATSP6", "ATAT0", "ATST32", "ATSH6B4", "ATCRA694").forEach {
            val reply = command(it)
            if (reply.trim().endsWith("?")) throw IOException("ELM ne podržava komandu $it")
        }
        // ISO-TP flow control is required by D440/D442/D4B5. An adapter that rejects it can
        // still supply ordinary one-frame values; do not make those disappear.
        longReplySupported = listOf("ATFCSH6B4", "ATFCSD300000", "ATFCSM1")
            .all { !command(it).trim().endsWith("?") }
    }

    override fun read(): BmsReading {
        val warnings = mutableListOf<String>()
        val soc = number("D410", 2)?.div(512.0)?.takeIf { it in 0.0..100.0 }
        val voltage = number("D815", 2)?.div(16.0)?.takeIf { it in 200.0..500.0 }
        val current = number("D816", 4)?.div(64.0)?.minus(1200.0)?.takeIf { it in -600.0..600.0 }
        val energy = number("D865", 2)?.div(64.0)?.takeIf { it in 0.0..70.0 }
        val odometer = number("D49C", 4)?.div(1024.0)?.takeIf { it in 0.0..1000000.0 }
        val sohBytes = data("D860", 3)
        val soh = if (sohBytes != null && sohBytes.size >= 3) ((sohBytes[1] shl 8) or sohBytes[2]) / 16.0 else null
        val minTemp = data("D87D", 1)?.firstOrNull()?.minus(40.0)?.takeIf { it in -40.0..100.0 }
        val maxTemp = data("D817", 1)?.firstOrNull()?.minus(40.0)?.takeIf { it in -40.0..100.0 }
        val avgTemp = data("D877", 1)?.firstOrNull()?.minus(40.0)?.takeIf { it in -40.0..100.0 }
        val minCell = number("D86F", 2)?.div(1000.0)?.takeIf { it in 2.0..5.0 }
        val maxCell = number("D870", 2)?.div(1000.0)?.takeIf { it in 2.0..5.0 }
        if (soc == null && voltage == null && energy == null) throw IOException("BMS ne vraća osnovne podatke")
        onVehicleResponse()

        // The arrays are long ISO-TP replies, so read them less frequently than the live gauges.
        // A failed/incomplete reply never overwrites the previous valid array; timestamps expose
        // its age to the UI/export so stale cells cannot be mistaken for a fresh measurement.
        if (!longReplySupported) warnings += "Adapter ne podržava ISO-TP flow control za ćelije (ATFC)."
        if (longReplySupported && readCount % 6L == 0L) {
            val cells = data("D440", 240, 5500)
            if (cells == null) warnings += "Nedostaje kompletan odgovor D440 za 108 napona ćelija."
            else {
                val parsed = cells.take(216).chunked(2).map { ((it[0] shl 8) or it[1]) / 1000.0 }
                if (parsed.size == 108 && parsed.all { it in 2.0..4.5 }) {
                    cellVoltages = parsed
                    cellsAt = System.currentTimeMillis()
                } else warnings += "Odgovor D440 sadrži nevažeće napone ćelija."
            }
            val probes = data("D442", 54, 4000)
            if (probes == null) warnings += "Nedostaje kompletan odgovor D442 za 54 temperature."
            else {
                val parsed = probes.map { it - 40.0 }
                if (parsed.size == 54 && parsed.all { it in -40.0..90.0 }) {
                    probeTemperatures = parsed
                    probesAt = System.currentTimeMillis()
                } else warnings += "Odgovor D442 sadrži nevažeće temperature."
            }
        }
        val slow = readCount % 6L == 0L
        if (slow) {
            // Independent scalar BMS DIDs from the 2022 e-208 catalog; read after the arrays
            // while the adapter is still addressed at 6B4/694.
            val mean = number("D43D", 2)?.div(1000.0)?.takeIf { it in 2.0..4.5 }
            val cellSum = number("D438", 2)?.div(16.0)?.takeIf { it in 200.0..500.0 }
            val lowIndex = data("D43C", 1)?.firstOrNull()?.plus(1)?.takeIf { it in 1..108 }
            val highIndex = data("D43B", 1)?.firstOrNull()?.plus(1)?.takeIf { it in 1..108 }
            val hotProbe = data("D445", 1)?.firstOrNull()?.plus(1)?.takeIf { it in 1..54 }
            val coldProbe = data("D446", 1)?.firstOrNull()?.plus(1)?.takeIf { it in 1..54 }
            val insulation = number("D47A", 4)?.takeIf { it in 0.0..1_000_000.0 }
            val aux = number("D822", 2)?.div(1000.0)?.takeIf { it in 5.0..17.0 }
            cellAverageVoltage = mean
            cellVoltageSum = cellSum
            lowestCellIndex = lowIndex
            highestCellIndex = highIndex
            hottestProbeIndex = hotProbe
            coldestProbeIndex = coldProbe
            insulationKohm = insulation
            auxiliaryVoltage = aux
            bmsExtraAt = if (listOf(mean, cellSum, insulation, aux).any { it != null })
                System.currentTimeMillis() else null
        }
        if (readCount % 12L == 0L) {
            // EscoEvObdScanner Stellantis e-CMP catalog: 2-byte /512 candidate SOC scales.
            // D413 is also described as minimum-cell capacity; none is verified as gross SOC.
            // Clear missing replies so a partial old poll cannot appear current.
            socCandidateD862 = number("D862", 2)?.div(512.0)?.takeIf { it in 0.0..100.0 }
            socCandidateD863 = number("D863", 2)?.div(512.0)?.takeIf { it in 0.0..100.0 }
            socCandidateD413 = number("D413", 2)?.div(512.0)?.takeIf { it in 0.0..100.0 }
            socCandidatesAt = if (socCandidateD862 != null || socCandidateD863 != null || socCandidateD413 != null)
                System.currentTimeMillis() else null
            if (socCandidatesAt == null) warnings += "D862/D863/D413: alternativne BMS skale nisu očitane."
        }
        readVcu(slow, warnings)
        if (slow) {
            readInverter(warnings)
            readCharger(warnings)
        }
        if (longReplySupported && readCount % 60L == 0L) {
            data("D412", 20)?.takeIf { it.size == 20 }?.let { bytes ->
                chargeCounterWords = bytes.chunked(2).map { (it[0] shl 8) or it[1] }
                chargeCounterAt = System.currentTimeMillis()
            }
            val health = data("D4B5", 216, 5500)
            if (health == null) warnings += "Nedostaje kompletan odgovor D4B5 za zdravlje ćelija."
            else {
                val parsed = health.chunked(2).map { ((it[0] shl 8) or it[1]) / 16.0 }
                if (parsed.size == 108 && parsed.all { it in 1.0..110.0 }) {
                    cellSoh = parsed
                    sohAt = System.currentTimeMillis()
                } else warnings += "Odgovor D4B5 sadrži nevažeće SOH ćelija."
            }
        }
        // Retry sooner until the first valid response; a waking ECU may return zero once.
        if (longReplySupported && (readCount % 60L == 0L ||
                (lifetimeCountersAt == null && readCount % 6L == 0L))) {
            // Lifetime counters are not the same as trip consumption, but their deltas can
            // independently validate a drive's energy accounting.
            val counters = data("D42E", 8, 4000)
            if (counters != null) {
                fun word(offset: Int) = counters.subList(offset, offset + 4)
                    .fold(0L) { value, byte -> (value shl 8) or byte.toLong() } / 16.0
                val freshIn = word(0).takeIf { it in 1.0..10_000_000.0 }
                val freshOut = word(4).takeIf { it in 1.0..10_000_000.0 }
                if (freshIn != null && freshOut != null) {
                    energyIn = freshIn
                    energyOut = freshOut
                    lifetimeCountersAt = System.currentTimeMillis()
                } else warnings += "D42E: brojači energije nisu validni; prikazana su poslednja ispravna očitavanja."
            } else warnings += "D42E: brojači energije nisu osveženi."
        }
        val now = System.currentTimeMillis()
        if (longReplySupported && cellVoltages.isEmpty() && warnings.none { it.contains("D440") })
            warnings += "Naponi 108 ćelija još nisu očitani (D440)."
        if (longReplySupported && probeTemperatures.isEmpty() && warnings.none { it.contains("D442") })
            warnings += "Temperature 54 sondi još nisu očitane (D442)."
        if (cellsAt != null && now - cellsAt!! > 120_000)
            warnings += "Poslednji naponi ćelija su stariji od 2 min."
        if (probesAt != null && now - probesAt!! > 120_000)
            warnings += "Poslednje temperature sondi su starije od 2 min."
        if (slow && bmsExtraAt == null) warnings += "D43D/D822: dodatni BMS podaci nisu očitani."
        if (slow && vcuExtraAt == null) warnings += "VCU: dodatne temperature nisu očitane."
        if (slow && inverterAt == null) warnings += "MCU: temperatura invertera nije očitana."
        if (slow && chargerAt == null) warnings += "Punjač: podaci nisu očitani."
        if (vcuExtraAt != null && now - vcuExtraAt!! > 120_000)
            warnings += "VCU temperature su starije od 2 min."
        if (inverterAt != null && now - inverterAt!! > 120_000)
            warnings += "Temperatura invertera je starija od 2 min."
        if (chargerAt != null && now - chargerAt!! > 120_000)
            warnings += "Podaci punjača su stariji od 2 min."
        readCount++
        return BmsReading(
            soc = soc, soh = soh?.takeIf { it in 0.0..110.0 }, voltage = voltage,
            current = current, energy = energy, odometerKm = odometer, tempMin = minTemp,
            tempMax = maxTemp, tempAvg = avgTemp, cellMin = minCell, cellMax = maxCell,
            timestamp = now, cellVoltages = cellVoltages, probeTemperatures = probeTemperatures,
            cellSoh = cellSoh, lifetimeEnergyInKwh = energyIn, lifetimeEnergyOutKwh = energyOut,
            lifetimeCountersAt = lifetimeCountersAt,
            speedKmh = speed, speedAt = speedAt, cellVoltagesAt = cellsAt, probeTemperaturesAt = probesAt,
            cellSohAt = sohAt, measurementWarnings = warnings,
            cellAverageVoltage = cellAverageVoltage, auxiliaryVoltage = auxiliaryVoltage,
            cellVoltageSum = cellVoltageSum, lowestCellIndex = lowestCellIndex,
            highestCellIndex = highestCellIndex, hottestProbeIndex = hottestProbeIndex,
            coldestProbeIndex = coldestProbeIndex, insulationKohm = insulationKohm,
            outsideTemp = outsideTemp, motorTemp = motorTemp, dcdcTemp = dcdcTemp,
            obcTemp = obcTemp, heatingCoolantTemp = heatingCoolantTemp,
            motorCoolantTemp = motorCoolantTemp, tractionCoolantTemp = tractionCoolantTemp,
            inverterTemp = inverterTemp, motorPowerKw = motorPowerKw,
            chargeCableConnected = chargeCableConnected, chargerHvVoltage = chargerHvVoltage,
            bmsExtraAt = bmsExtraAt, vcuExtraAt = vcuExtraAt, inverterAt = inverterAt,
            chargerAt = chargerAt,
            socCandidateD862 = socCandidateD862, socCandidateD863 = socCandidateD863,
            socCandidateD413 = socCandidateD413, socCandidatesAt = socCandidatesAt,
            chargeCounterWords = chargeCounterWords, chargeCounterAt = chargeCounterAt, gridVoltage = gridVoltage
        )
    }

    private fun readVcu(slow: Boolean, warnings: MutableList<String>) {
        if (!withEcu("6A2", "682", "VCU", warnings) {
            val freshSpeed = number("D402", 2)?.div(128.0)?.takeIf { it in 0.0..220.0 }
            if (freshSpeed != null) {
                speed = freshSpeed
                speedAt = System.currentTimeMillis()
            } else if (speedAt == null || System.currentTimeMillis() - speedAt!! > 15_000) speed = null
            val power = signedNumber("D8D9", 2)?.div(10.0)?.takeIf { it in -150.0..150.0 }
            motorPowerKw = power
            if (slow) {
                outsideTemp = data("D434", 1)?.firstOrNull()?.let { signed8(it).toDouble() }
                    ?.takeIf { it in -50.0..70.0 }
                motorTemp = signedNumber("D8CD", 2)?.takeIf { it in -40.0..200.0 }
                dcdcTemp = signedNumber("D8CE", 2)?.takeIf { it in -40.0..150.0 }
                obcTemp = signedNumber("D8E1", 2)?.takeIf { it in -40.0..150.0 }
                motorCoolantTemp = signedNumber("D8D6", 2)?.takeIf { it in -40.0..150.0 }
                tractionCoolantTemp = signedNumber("D8F9", 2)?.div(10.0)?.takeIf { it in -40.0..150.0 }
                heatingCoolantTemp = signedNumber("D8FB", 2)?.div(10.0)?.takeIf { it in -40.0..150.0 }
                vcuExtraAt = if (listOf(outsideTemp, motorTemp, dcdcTemp, motorCoolantTemp,
                        tractionCoolantTemp, obcTemp, heatingCoolantTemp).any { it != null }) System.currentTimeMillis() else null
            }
        }) {
            if (speedAt == null || System.currentTimeMillis() - speedAt!! > 15_000) speed = null
            motorPowerKw = null
        }
    }

    private fun readInverter(warnings: MutableList<String>) {
        withEcu("6A6", "686", "MCU", warnings) {
            inverterTemp = signedNumber("DC6A", 2)?.div(64.0)?.takeIf { it in -40.0..200.0 }
            inverterAt = if (inverterTemp != null) System.currentTimeMillis() else null
        }
    }

    private fun readCharger(warnings: MutableList<String>) {
        withEcu("590", "58F", "punjač", warnings) {
            // D854 indicates that a cable is present; it does not prove current is flowing.
            chargeCableConnected = data("D854", 1)?.firstOrNull()?.let { when (it) {
                0 -> false
                100 -> true
                else -> null
            } }
            chargerHvVoltage = number("D824", 2)?.takeIf { it in 0.0..500.0 }
            // Published OBC D82B grid voltage, zero during DC charging. Missing reply stays unknown.
            gridVoltage = number("D82B", 2)?.takeIf { it in 0.0..300.0 }
            chargerAt = if (chargeCableConnected != null || chargerHvVoltage != null || gridVoltage != null)
                System.currentTimeMillis() else null
        }
    }

    private fun withEcu(request: String, response: String, label: String,
                        warnings: MutableList<String>, block: () -> Unit): Boolean {
        return try {
            if (command("ATSH$request").trim().endsWith("?") ||
                command("ATCRA$response").trim().endsWith("?")) {
                warnings += "$label: adapter nije prihvatio adresu ECU."
                false
            } else {
                block()
                true
            }
        } catch (failure: IOException) {
            warnings += "$label: ${failure.message?.take(70) ?: "nema odgovora"}"
            false
        } finally {
            // The next pass begins at the BMS. A failed restore must stop this pass rather
            // than filing an unrelated ECU response under a BMS DID.
            if (command("ATSH6B4").trim().endsWith("?") || command("ATCRA694").trim().endsWith("?"))
                throw IOException("ELM nije vratio BMS adresu posle $label")
        }
    }

    private fun signed8(value: Int) = if (value >= 128) value - 256 else value

    private fun signedNumber(did: String, bytes: Int): Double? {
        val value = number(did, bytes)?.toLong() ?: return null
        val modulus = 1L shl (bytes * 8)
        return (if (value >= modulus / 2) value - modulus else value).toDouble()
    }

    private fun number(did: String, bytes: Int): Double? {
        val values = data(did, bytes) ?: return null
        if (values.size < bytes) return null
        var result = 0L
        for (i in 0 until bytes) result = (result shl 8) or values[i].toLong()
        return result.toDouble()
    }

    private fun data(did: String, byteCount: Int, timeoutMs: Long = 1500): List<Int>? {
        // An unsupported DID or one lost response must not suppress the other measurements.
        val raw = try { command("22$did", timeoutMs) } catch (_: ObdTimeoutException) { return null }
        return parseDidPayload(raw, did, byteCount)
    }

    private fun command(value: String, timeoutMs: Long = 1800): String {
        while (input.available() > 0) input.read()
        output.write("$value\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val result = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) {
                val ch = input.read()
                if (ch == -1) throw IOException("OBD veza je zatvorena")
                if (ch == '>'.code) return result.toString()
                if (result.length < 4096) result.append(ch.toChar())
            } else Thread.sleep(15)
        }
        throw ObdTimeoutException(value)
    }
}
