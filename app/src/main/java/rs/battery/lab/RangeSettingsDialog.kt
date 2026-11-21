package rs.battery.lab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable internal fun RangeSettingsDialog(range: MapRangeEstimate?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved by RangePreferences.reserveSoc.collectAsState()
    var selected by remember(saved) { mutableIntStateOf(saved) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { LText("Krugovi dometa") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LText("Rezerva pri dolasku: $selected%", color = Color(0xFF52DAF3), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Slider(value = selected.toFloat(), onValueChange = { selected = (it / 5).roundToInt() * 5 },
                valueRange = 5f..50f, steps = 8,
                onValueChangeFinished = { error = !RangePreferences.setReserveSoc(context, selected) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LText("5%", fontSize = 11.sp); LText("50%", fontSize = 11.sp)
            }
            if (error) LText("Izbor nije sačuvan. Pokušaj ponovo.", color = Color(0xFFFFD567))
            LText("Izbor se pamti. Oba kruga i upozorenje za dolazak na ruti koriste ovu rezervu.", fontSize = 12.sp)
            LText("Plavi krug je jednosmerni poluprečnik, a žuti isprekidani uključuje i povratak. Kilometri uz njih predstavljaju vazdušnu udaljenost. Izbor odredišta računa stvarnu trasu po putevima.", fontSize = 12.sp, lineHeight = 18.sp)
            range?.detail?.let { LText(it, fontSize = 12.sp, lineHeight = 18.sp) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { LText("Zatvori") } }, dismissButton = {
        TextButton(onClick = { error = !RangePreferences.setReserveSoc(context, 20); selected = RangePreferences.reserveSoc.value }) {
            LText("Vrati 20%")
        }
    })
}
