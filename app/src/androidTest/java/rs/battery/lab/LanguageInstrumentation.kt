package rs.battery.lab

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import java.util.UUID

class LanguageInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val lines = mutableListOf<String>()
        var failures = 0
        val token = "language_test_" + UUID.randomUUID()
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(token + name, mode)
        }
        fun case(name: String, block: () -> Unit) {
            try { block(); lines += "PASS $name" } catch (e: Throwable) { failures++; lines += "FAIL $name: ${e.stackTraceToString()}" }
        }
        case("default English and complete dynamic labels") {
            AppLanguage.initialize(isolated)
            check(AppLanguage.code == "en")
            check(UiStrings.text("Baterija") == "Battery")
            check(UiStrings.text("● ČEKA VEZU") == "● WAITING")
            check(UiStrings.text("Renault Zoe Q210  ·  Promeni vozilo") == "Renault Zoe Q210  ·  Change vehicle")
            check(UiStrings.text("Čekam kompletan niz: 96 grupa.") == "Waiting for a complete array: 96 groups.")
            check(UiStrings.text("SOC vozila 48.5 % · rezerva 20 %") == "Vehicle SOC 48.5 % · reserve 20 %")
            check(UiStrings.text("Putem 72.3 km\nPotrošnja\n\nUspon ↑ 234 m · pad ↓ 182 m") == "By road 72.3 km\nConsumption\n\nAscent ↑ 234 m · descent ↓ 182 m")
            check(windCompass(180.0) == "S" && UiStrings.text(windCompass(180.0)) == "S")
        }
        case("switch, persistence and global language across vehicle profiles") {
            val peugeot = VehicleDataContext(isolated, VehicleProfiles.all.first())
            val zoe = VehicleDataContext(isolated, VehicleProfiles.all.last())
            check(AppLanguage.select(zoe, "sr"))
            AppLanguage.initialize(peugeot)
            check(AppLanguage.code == "sr" && UiStrings.text("Baterija") == "Baterija")
            check(UiStrings.text("Čekam kompletan niz: 96 grupa.") == "Čekam kompletan niz: 96 grupa.")
            check(windCompass(180.0) == "J")
            check(AppLanguage.select(peugeot, "en"))
            AppLanguage.initialize(zoe)
            check(AppLanguage.code == "en")
            check(peugeot.filesDir != zoe.filesDir)
        }
        case("technical values and station names are preserved") {
            listOf("229002", "ATSHDADBF1", "3.604 V", "SOC 40.5%", "eDrive Ingrap-Omni Divčibare", "Valjevo", "48.0521, 20.0042").forEach {
                check(UiStrings.text(it) == it) { it + " -> " + UiStrings.text(it) }
            }
            check(UiStrings.text("Ćelije: pre 2 min • 18.11.2026 10:05") == "Cells: 2 min ago • 18.11.2026 10:05")
        }
        AppLanguage.initialize(targetContext)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", lines.joinToString("\n") + "\n${lines.size-failures}/${lines.size} passed") })
    }
}
