package rs.battery.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val extraMuted = Color(0xFF9AB1C1)
private val extraGreen = Color(0xFF00DB88)
private val extraCyan = Color(0xFF52DAF3)
private val extraBorder = Color(0xFF304654)
private const val SLOW_FRESH_MS = 120_000L
private const val COUNTER_FRESH_MS = 15 * 60_000L

private fun sensor(value: Double?, decimals: Int, unit: String): String =
    value?.takeIf { it.isFinite() }?.let { "%.${decimals}f".format(Locale.US, it) + unit } ?: "—"

private fun fresh(at: Long?, maxAgeMs: Long, now: Long): Boolean =
    at != null && now - at in 0..maxAgeMs

private fun ageText(at: Long?, maxAgeMs: Long, now: Long): String {
    if (at == null || at > now) return "Nema potvrđenog vremena očitavanja"
    val ageSeconds = (now - at) / 1000
    val age = if (ageSeconds < 60) "pre ${ageSeconds} s" else "pre ${ageSeconds / 60} min"
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
    return if (now - at > maxAgeMs) "Zastarelo · $clock · $age" else "Očitano $clock · $age"
}

@Composable internal fun ExtraSensorPanel(live: BmsReading?) {
    if (live == null) return
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(5_000) }
    }
    val bmsFresh = fresh(live.bmsExtraAt, SLOW_FRESH_MS, now)
    val vcuFresh = fresh(live.vcuExtraAt, SLOW_FRESH_MS, now)
    val inverterFresh = fresh(live.inverterAt, SLOW_FRESH_MS, now)
    val chargerFresh = fresh(live.chargerAt, SLOW_FRESH_MS, now)
    val probeFresh = live.probeTemperatures.size == 54 &&
        fresh(live.probeTemperaturesAt, SLOW_FRESH_MS, now)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        LText("Dodatna očitavanja iz vozila", color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = 17.sp, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))

        SensorGroup("BMS · električni parametri", live.bmsExtraAt, SLOW_FRESH_MS, now) {
            SensorLine("Srednji napon grupe", sensor(live.cellAverageVoltage.takeIf { bmsFresh }, 3, " V"))
            SensorLine("Zbir napona grupa", sensor(live.cellVoltageSum.takeIf { bmsFresh }, 2, " V"))
            SensorLine("Najniža / najviša grupa", if (bmsFresh)
                "${live.lowestCellIndex ?: "—"} / ${live.highestCellIndex ?: "—"}" else "—")
            SensorLine("Izolacija HV", sensor(live.insulationKohm.takeIf { bmsFresh }, 0, " kΩ"))
            SensorLine("Pomoćni akumulator", sensor(live.auxiliaryVoltage.takeIf { bmsFresh }, 2, " V"))
        }

        SensorGroup("Sonde · logički indeksi", live.bmsExtraAt, SLOW_FRESH_MS, now) {
            SensorLine("Najtoplija", probeValue(live.hottestProbeIndex.takeIf { bmsFresh },
                live.probeTemperatures, probeFresh))
            SensorLine("Najhladnija", probeValue(live.coldestProbeIndex.takeIf { bmsFresh },
                live.probeTemperatures, probeFresh))
            LText("Vrednost sonde: ${ageText(live.probeTemperaturesAt, SLOW_FRESH_MS, now)}. " +
                "Broj sonde 1–54 nije položaj fizičkog modula.", color = extraMuted,
                fontSize = 10.sp, lineHeight = 14.sp)
        }

        SensorGroup("Pogon · trenutno", live.timestamp, 15_000L, now) {
            SensorLine("Snaga motora", sensor(live.motorPowerKw.takeIf {
                fresh(live.timestamp, 15_000L, now)
            }, 1, " kW"), large = true)
            LText("Očitavanje motora, odvojeno od snage baterije V × I.", color = extraMuted,
                fontSize = 10.sp, lineHeight = 14.sp)
        }

        SensorGroup("Pogon · temperature", live.vcuExtraAt, SLOW_FRESH_MS, now) {
            SensorLine("Spolja", sensor(live.outsideTemp.takeIf { vcuFresh }, 1, " °C"))
            SensorLine("Motor · stator", sensor(live.motorTemp.takeIf { vcuFresh }, 1, " °C"))
            SensorLine("DC/DC", sensor(live.dcdcTemp.takeIf { vcuFresh }, 1, " °C"))
            SensorLine("OBC · ugrađeni punjač", sensor(live.obcTemp.takeIf { vcuFresh }, 1, " °C"))
            SensorLine("Tečnost · ulaz u motor", sensor(live.motorCoolantTemp.takeIf { vcuFresh }, 1, " °C"))
            SensorLine("Tečnost · pogonski krug", sensor(live.tractionCoolantTemp.takeIf { vcuFresh }, 1, " °C"))
            SensorLine("Tečnost · grejanje", sensor(live.heatingCoolantTemp.takeIf { vcuFresh }, 1, " °C"))
        }

        SensorGroup("Inverter", live.inverterAt, SLOW_FRESH_MS, now) {
            SensorLine("Temperatura", sensor(live.inverterTemp.takeIf { inverterFresh }, 1, " °C"), large = true)
        }

        SensorGroup("Ugrađeni punjač", live.chargerAt, SLOW_FRESH_MS, now) {
            SensorLine("Kabl", if (chargerFresh) when (live.chargeCableConnected) {
                true -> "Priključen"; false -> "Nije priključen"; null -> "—"
            } else "—")
            SensorLine("HV napon punjača", sensor(live.chargerHvVoltage.takeIf { chargerFresh }, 1, " V"))
            LText("Priključen kabl ne potvrđuje da punjenje trenutno teče.",
                color = extraMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }

        SensorGroup("Ukupni brojači baterije", live.lifetimeCountersAt, COUNTER_FRESH_MS, now) {
            live.chargeCounterWords.firstOrNull()?.let { raw ->
                SensorLine("Sirovi brojač D412 (W0)", raw.toString())
                LText("Značenje D412 nije potvrđeno. Poslednji upit: ${live.chargeCounterAt?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "—"}.", color = extraMuted, fontSize = 10.sp)
            }
            SensorLine("Energija u bateriju", sensor(live.lifetimeEnergyInKwh, 1, " kWh"), large = true)
            SensorLine("Energija iz baterije", sensor(live.lifetimeEnergyOutKwh, 1, " kWh"), large = true)
            LText("Brojač ulaza uključuje rekuperaciju; ovo nije mrežno brojilo ni energija samo iz punjača. " +
                "Prikazano je poslednje očitavanje brojača; osvežava se ređe od trenutnih vrednosti.", color = extraMuted,
                fontSize = 10.sp, lineHeight = 14.sp)
        }
        LText("— znači da odgovor nije potvrđen ili je očitavanje zastarelo.",
            color = extraMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

private fun probeValue(index: Int?, probes: List<Double>, freshProbes: Boolean): String {
    if (index == null || index !in 1..54) return "—"
    val value = if (freshProbes) probes.getOrNull(index - 1) else null
    return "Sonda $index · ${sensor(value, 0, " °C")}" 
}

@Composable private fun SensorGroup(title: String, measuredAt: Long?, maxAgeMs: Long,
                                    now: Long, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, extraBorder, RoundedCornerShape(12.dp))
        .background(Color(0xFF091F30), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LText(title, color = extraCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        LText(ageText(measuredAt, maxAgeMs, now),
            color = if (fresh(measuredAt, maxAgeMs, now)) extraMuted else Color(0xFFFFC66D),
            fontSize = 10.sp, lineHeight = 13.sp)
        content()
    }
}

@Composable private fun SensorLine(label: String, value: String, large: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LText(label, color = extraMuted, fontSize = 11.sp, lineHeight = 14.sp,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        LText(value, color = if (value == "—") extraMuted else extraGreen,
            fontSize = if (large) 16.sp else 14.sp, fontWeight = FontWeight.Bold,
            lineHeight = if (large) 20.sp else 18.sp)
    }
}
