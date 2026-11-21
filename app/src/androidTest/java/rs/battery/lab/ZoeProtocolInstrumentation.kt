package rs.battery.lab

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Synthetic protocol frames exercise decoding and isolation; not a physical vehicle test. */
class ZoeProtocolInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = mutableListOf<String>()
        var failed = 0
        fun case(name: String, block: () -> Unit) {
            try { block(); report += "PASS $name" } catch (e: Throwable) { failed++; report += "FAIL $name: ${e.stackTraceToString()}" }
        }
        case("CAF1 frames, missing and reordered frames rejected") {
            val payload = (0 until 124).map { it }
            val response = frames("6141", payload)
            check(zoePayload(response, "6141", 124) == payload) { "complete long response" }
            check(zoePayload(response.replace("2:", "3:"), "6141", 124) == null) { "out-of-order frame accepted" }
            check(zoePayload(response.lines().dropLast(2).joinToString("\r"), "6141", 124) == null) { "truncated frames accepted" }
            check(zoePayload("7F2231", "629002", 2) == null)
            check(zoePayload("6290031234", "629002", 2) == null)
            check(zoePayload("016290020123", "629002", 2) == null) { "invalid single-frame length accepted" }
            check(zoePayload("05629002012300", "629002", 2) == listOf(1, 35)) { "padded single frame rejected" }
        }
        case("phase 1: current sign, all 96 cells, 12 temperatures, absent data") {
            val elm = Fixture(false)
            val reader = ZoeObdReader(elm, false, clock = { 100_000L })
            reader.initialize()
            val r = reader.read()
            check(r.soc == 50.0 && r.soh == 90.0 && r.voltage == 360.0)
            check(r.current == 10.0 && r.powerKw == 3.6 && r.energy == 18.0)
            check(r.cellVoltages.size == 96 && r.cellVoltages.first() == 3.6 && r.cellVoltages.last() == 3.695)
            check(r.probeTemperatures == (20..31).map { it.toDouble() })
            check(r.speedKmh == 60.0 && r.cellSoh.isEmpty())
            check("ATSP6" in elm.sent && "ATSH79B" in elm.sent && "ATCRA7BB" in elm.sent)
            check(elm.sent.none { it.startsWith("22D4") || it.startsWith("2E") || it.startsWith("27") })
            val broken = Fixture(false, missing = "2142")
            val partial = ZoeObdReader(broken, false).apply { initialize() }.read()
            check(partial.cellVoltages.isEmpty() && partial.cellVoltagesAt == null)
            check(ZoeObdReader.currentFromEvc(32808, false) == -10.0)
        }
        case("phase 2: extended addressing and a complete 96-DID sweep") {
            val elm = Fixture(true)
            var now = 100_000L
            val reader = ZoeObdReader(elm, true, clock = { now })
            reader.initialize()
            var last: BmsReading? = null
            repeat(12) { index ->
                last = reader.read()
                if (index < 11) check(last!!.cellVoltages.isEmpty())
                now += 3_000
            }
            val r = last!!
            check(r.soc == 50.0 && r.soh == 90.0 && r.energy == 26.0 && r.voltage == 360.0)
            check(r.current == 10.0 && r.cellVoltages.size == 96 && r.probeTemperatures.size == 12)
            check(r.cellVoltagesAt == 100_000L && r.probeTemperatures.first() == 20.0)
            r.cellVoltages.forEachIndexed { i, v -> check(abs(v - (3686 + i) / 1024.0) < 1e-8) }
            check("ATCP18" in elm.sent && "ATSHDADBF1" in elm.sent && "ATCRA18DAF1DB" in elm.sent)
            check("ATFCSH18DADBF1" in elm.sent && "ATSHDADAF1" in elm.sent && "ATSP7" in elm.sent)
            check(listOf("229040", "229060", "229080").none { it in elm.sent })
            check(ZoeObdReader.currentFromEvc(2040, true) == -10.0)
            val bad = ZoeObdReader(Fixture(true, "229050"), true, clock = { 100_000L }).apply { initialize() }
            repeat(12) { check(bad.read().cellVoltages.isEmpty()) }
        }
        case("vehicle files/preferences isolation and voltage model separation") {
            val token = "zoe_test_" + UUID.randomUUID()
            val base = object : ContextWrapper(targetContext) {
                override fun getFilesDir() = File(super.getCacheDir(), token).apply { mkdirs() }
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences(token + name, mode)
            }
            val a = VehicleDataContext(base, VehicleProfiles.all.first())
            val b = VehicleDataContext(base, VehicleProfiles.all.first { it.id == "renault_zoe_q210_22" })
            val c = VehicleDataContext(base, VehicleProfiles.all.last())
            check(setOf(a.filesDir, b.filesDir, c.filesDir).size == 3)
            a.getSharedPreferences("data", 0).edit().putInt("value", 1).commit()
            b.getSharedPreferences("data", 0).edit().putInt("value", 2).commit()
            check(a.getSharedPreferences("data", 0).getInt("value", 0) == 1)
            check(c.getSharedPreferences("data", 0).getInt("value", 0) == 0)
            check(VehicleProfiles.current(b.applicationContext) == b.profile)
            val display = packSocDisplay(b, BmsReading(voltage = 360.0, soc = 50.0, timestamp = 100_000), 100_000)
            check(display.meanGroupVoltageV == 3.75 && display.voltageSocPercent == null)
            check(capacityForPlanner(emptyList(), emptyList(), b.profile.nominalUsableKwh).first == 22.0)
            check(chargingEta(50.0, 80.0, 18.0, 9.9) != null)
            check(VehicleProfiles.all.map { it.id }.distinct().size == 15)
        }
        val summary = report.joinToString("\n") + "\n${report.size - failed}/${report.size} passed"
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", summary) })
    }
}

private fun bytes(number: Int, count: Int = 2) = (count - 1 downTo 0).map { (number shr (8 * it)) and 255 }
private fun frames(prefix: String, payload: List<Int>): String {
    val hex = prefix + payload.joinToString("") { "%02X".format(it) }
    return "%03X\r".format(hex.length / 2) + hex.chunked(14).mapIndexed { i, part -> "${(i % 16).toString(16)}:$part" }.joinToString("\r") + "\r"
}
private class Fixture(val phase2: Boolean, val missing: String = "") : ElmLink {
    val sent = mutableListOf<String>()
    override fun command(value: String, timeoutMs: Long): String {
        sent += value
        if (value.startsWith("AT")) return "OK"
        if (value == missing) return "NO DATA"
        val cells = ZoeObdReader.PHASE2_CELL_DIDS
        val did = value.drop(2).toIntOrNull(16) ?: 0
        val data = when {
            phase2 && did in cells -> bytes(3686 + cells.indexOf(did))
            phase2 && did in 0x9131..0x913C -> bytes(960 + (did - 0x9131) * 16)
            value == "2141" -> (0 until 62).flatMap { bytes(3600 + it) }
            value == "2142" -> (62 until 96).flatMap { bytes(3600 + it) }
            value == "2104" -> MutableList(36) { 0 }.apply { repeat(12) { this[2 + it * 3] = 60 + it } }
            else -> when (value) {
                "222002" -> bytes(2500); "223206" -> bytes(90, 1); "223203" -> bytes(720)
                "22320C" -> bytes(3600); "223204" -> bytes(32728)
                "229002" -> bytes(5000); "229003" -> bytes(9000); "2291C8" -> bytes(26000, 3)
                "229005" -> bytes(3600); "229009" -> bytes(3686); "229007" -> bytes(3781)
                "229012", "229013", "229014" -> bytes(960); "2221CC" -> bytes(1960)
                "222003" -> bytes(6000); "222006" -> bytes(100000, 3); "222005" -> bytes(1400)
                "2220DE" -> bytes(2880)
                else -> return "NO DATA"
            }
        }
        val prefix = (if (value.startsWith("21")) "61" else "62") + value.drop(2)
        return if (data.size > 4) frames(prefix, data) else prefix + data.joinToString("") { "%02X".format(it) }
    }
}
