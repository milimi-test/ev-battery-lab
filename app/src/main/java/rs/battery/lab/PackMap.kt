package rs.battery.lab

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun PackMap(reading: BmsReading?, initialMode: Int = 0) {
    val context = LocalContext.current
    if (VehicleProfiles.current(context).id != VehicleProfiles.all.first().id) {
        IndexedPackPanel(reading, initialMode)
        return
    }
    var mode by remember { mutableIntStateOf(initialMode) }
    val cyan = Color(0xFF52DAF3)
    val muted = Color(0xFF9BAEBD)
    val open: (Int) -> Unit = { module ->
        context.startActivity(Intent(context, PackViewerActivity::class.java).putExtra("mode", mode).putExtra("module", module))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("NAPON", "TEMPERATURA", "SOH").forEachIndexed { index, title ->
                Box(Modifier.weight(1f).background(if (mode == index) Color(0xFF174150) else Color(0xFF132330), RoundedCornerShape(8.dp))
                    .border(1.dp, if (mode == index) cyan else Color(0xFF304654), RoundedCornerShape(8.dp))
                    .clickable { mode = index }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    LText(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
        }
        LText("18 MODULA · TVOJI OZNAČENI POLOŽAJI", color = cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LImage(painterResource(R.drawable.e208_pack_reference), contentDescription = "Originalna fotografija šasije Peugeot e-208; otvori mapu sa oznakama",
            modifier = Modifier.fillMaxWidth().aspectRatio(794f / 425f).clickable { open(1) }, contentScale = ContentScale.Fit)
        Box(Modifier.fillMaxWidth().background(cyan, RoundedCornerShape(9.dp)).clickable { open(1) }.padding(13.dp), contentAlignment = Alignment.Center) {
            LText("OTVORI SLIKU SA SVIM VREDNOSTIMA ↗", color = Color(0xFF071C26), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        LText("Uvećaj sliku, pomeri je i izaberi modul za stvarnu fotografiju i očitavanja ćelijskih parova.", color = muted, fontSize = 11.sp)
        val now = System.currentTimeMillis()
        val source = when (mode) {
            0 -> reading?.cellVoltages?.takeIf { it.size == 108 && reading.cellVoltagesAt?.let { at -> now - at in 0..120_000 } == true }
            1 -> reading?.probeTemperatures?.takeIf { it.size == 54 && reading.probeTemperaturesAt?.let { at -> now - at in 0..120_000 } == true }
            else -> reading?.cellSoh?.takeIf { it.size == 108 && reading.cellSohAt?.let { at -> now - at in 0..600_000 } == true }
        }.orEmpty()
        repeat(6) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { col ->
                    val i = row * 3 + col
                    val width = if (mode == 1) 3 else 6
                    val group = source.drop(i * width).take(width)
                    val value = if (mode == 1) group.maxOrNull() else group.minOrNull()
                    Column(Modifier.weight(1f).background(Color(0xFF132330), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF304654), RoundedCornerShape(8.dp))
                        .clickable { open(i + 1) }.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        LText("M%02d · B%02d*".format(Locale.US, i + 1, i + 1), color = muted, fontSize = 9.sp)
                        LText(value?.let { (if (mode == 0) "%.3f V" else if (mode == 1) "%.1f °C" else "%.1f %%").format(Locale.US, it) } ?: "—",
                            color = cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        LText(when (mode) { 0 -> "Prikazan je najniži napon grupe u bloku."; 1 -> "Prikazana je najviša temperatura tri sonde u bloku."; else -> "Prikazan je najmanji SOH grupe u bloku." }, color = muted, fontSize = 10.sp)
        LText("* M↔B je radno povezivanje po rednom broju. Tvoje fizičke pozicije su sačuvane; veza sa dijagnostičkim indeksima još nije potvrđena.", color = Color(0xFFEDC078), fontSize = 10.sp, lineHeight = 14.sp)
    }
}
