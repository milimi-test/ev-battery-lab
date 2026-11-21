package rs.battery.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun MapToolbar(onSearch: () -> Unit, onLayers: () -> Unit, onClose: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        LText("Mapa", color = Color.White, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
            LIcon(Icons.Outlined.Search, "Traži mesto ili punjač", tint = Color(0xFF42CFFF))
        }
        IconButton(onClick = onLayers, modifier = Modifier.size(48.dp)) {
            LIcon(Icons.Outlined.Layers, "Slojevi mape", tint = Color(0xFF42CFFF))
        }
        if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
            LIcon(Icons.Outlined.Close, "Zatvori mapu", tint = Color.White)
        }
    }
}

@Composable internal fun MapLayersDialog(relief: Boolean, contours: Boolean, onRelief: (Boolean) -> Unit,
    onContours: (Boolean) -> Unit, onInfo: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { LText("Slojevi mape") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf("Satelit" to false, "Visina terena" to true).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onRelief(value) },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = relief == value, onClick = { onRelief(value) })
                    LText(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                LText("Izohipse", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = contours, onCheckedChange = onContours, enabled = relief)
            }
            if (!relief) LText("Izohipse su dostupne uz visinu terena.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LText("Podaci i pokriće", fontSize = 14.sp, modifier = Modifier.weight(1f))
                MetricInfoIcon("Podaci i pokriće mape", onInfo)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { LText("Zatvori") } })
}
