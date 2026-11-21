package rs.battery.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Diagnostic indices only. A physical module assignment needs vehicle-specific evidence. */
@Composable internal fun IndexedPackPanel(reading: BmsReading?, initialMode: Int) {
    val vehicle = VehicleProfiles.current(LocalContext.current)
    var mode by remember { mutableIntStateOf(initialMode) }
    val cyan = Color(0xFF52DAF3)
    val muted = Color(0xFFB0C3D0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Napon", "Sonde", "SOH").forEachIndexed { i, label ->
                FilterChip(selected = mode == i, onClick = { mode = i }, label = { LText(label) })
            }
        }
        LText(vehicle.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        LText("${vehicle.seriesGroups} serijskih grupa · redosled BMS indeksa", color = cyan, fontSize = 13.sp)
        LText("Ovo je pregled očitavanja, bez pretpostavljenih fizičkih položaja modula i sondi.", color = muted, fontSize = 12.sp)
        val timestamp = when (mode) { 0 -> reading?.cellVoltagesAt; 1 -> reading?.probeTemperaturesAt; else -> reading?.cellSohAt }
        val values = when (mode) { 0 -> reading?.cellVoltages; 1 -> reading?.probeTemperatures; else -> reading?.cellSoh }.orEmpty()
        val expected = if (mode == 1) (if (vehicle.isZoe) 12 else 54) else vehicle.seriesGroups
        val complete = values.size == expected
        if (mode == 2 && vehicle.isZoe) {
            LText("SOH paketa: ${reading?.soh?.let { "%.1f %%".format(Locale.US, it) } ?: "—"}", color = cyan, fontSize = 22.sp)
            LText("Pojedinačni SOH grupa nije dostupan u ovom protokolu. SOH paketa se ne preslikava na ćelije.", color = muted, fontSize = 13.sp)
        } else if (!complete) {
            LText("Čekam kompletan niz: ${if (mode == 1) "$expected sondi" else "$expected grupa"}.", color = cyan, fontSize = 14.sp)
            reading?.measurementWarnings?.filter { it.contains("Zoe") || it.contains("96") }?.forEach { LText(it, color = muted, fontSize = 12.sp) }
        } else {
            val low = values.minOrNull()
            val high = values.maxOrNull()
            val unit = if (mode == 0) "V" else if (mode == 1) "°C" else "%"
            val format = if (mode == 0) "%.3f" else "%.1f"
            LText("Min ${format.format(Locale.US, low)} · prosek ${format.format(Locale.US, values.average())} · max ${format.format(Locale.US, high)} $unit", color = cyan, fontSize = 13.sp)
            timestamp?.let {
                LText("Ciklus: ${java.text.SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault()).format(java.util.Date(it))}" +
                    if (System.currentTimeMillis() - it > 120_000) " · sačuvano očitavanje" else "", color = muted, fontSize = 12.sp)
            }
            values.chunked(4).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { col, value ->
                        val number = rowIndex * 4 + col + 1
                        Column(Modifier.weight(1f).background(Color(0xFF132F40), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 9.dp)) {
                            LText("${if (mode == 1) "T" else "G"}$number", color = muted, fontSize = 12.sp)
                            LText(format.format(Locale.US, value), color = if (value == low) Color(0xFFFFD16C) else cyan,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            LText(unit, color = muted, fontSize = 10.sp)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
