package rs.battery.lab

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray

@Composable internal fun VehiclePicker(current: VehicleProfile, onSelect: (VehicleProfile) -> Unit, onDismiss: () -> Unit) {
    val links = LocalUriHandler.current
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<VehicleProfile?>(null) }
    val credits = remember { JSONArray(context.assets.open("vehicle-photo-credits.json").bufferedReader().use { it.readText() }) }
    photo?.let { vehicle ->
        val credit = (0 until credits.length()).map { credits.getJSONObject(it) }.firstOrNull { it.optString("asset") == vehicle.photoKey }
        if (credit != null) AlertDialog(onDismissRequest = { photo = null }, title = { LText("Fotografija vozila") }, text = {
            Column { LText(credit.optString("author") + " · " + credit.optString("license"))
                LText("Wikimedia Commons, umanjena fotografija; ilustracija modela i generacije, ne dokaz opreme ili baterije.", fontSize = 12.sp)
                TextButton(onClick = { links.openUri(credit.getString("source")) }) { LText("Izvor") }
                if (credit.optString("licenseUrl").startsWith("http")) TextButton(onClick = { links.openUri(credit.getString("licenseUrl")) }) { LText("Licenca") }
            }
        }, confirmButton = { TextButton(onClick = { photo = null }) { LText("Zatvori") } })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LText("Izaberi vozilo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { LText("Zatvori") }
                }
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { LText("Marka, model, baterija ili motor") })
                LText("${VehicleProfiles.all.size} profila · zasebna istorija i poslednja očitavanja", fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(VehicleProfiles.all.filter { (it.name + " " + it.variant).contains(search, true) }, key = { it.id }) { profile ->
                        Card(Modifier.fillMaxWidth().clickable { onSelect(profile) }) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LImage(painterResource(profile.image), profile.name, Modifier.size(86.dp, 66.dp), contentScale = ContentScale.Fit)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    LText(profile.name, style = MaterialTheme.typography.titleSmall)
                                    LText(profile.variant, fontSize = 12.sp)
                                    if (profile == current) LText("Izabrano", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                                if (profile.photoKey.isNotBlank()) MetricInfoIcon("Izvor fotografije") { photo = profile }
                            }
                        }
                    }
                    item {
                        LText("Promena završava OBD vezu. Zatim poveži adapter u izabranom vozilu. Za Zoe R110 ZE40 proveri fazu: redizajn 2019. koristi drugi protokol. Izaberi stvarno ugrađenu bateriju; godina registracije sama nije dovoljna.", fontSize = 13.sp)
                        LText("Dostupnost signala zavisi od ECU-a i adaptera. Novi profili su zasnovani na objavljenim protokolima i još nisu provereni na svim fizičkim vozilima. Stellantis varijante od 54 kWh nisu uključene. CCS kod Zoe ZE50 je opciona oprema.", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = { links.openUri("https://github.com/fesch/CanZE") }) { LText("Zoe: izvor protokola CanZE") }
                        TextButton(onClick = { links.openUri("https://github.com/EsaHub/EscoEvObdScanner") }) { LText("Stellantis: izvor e-CMP protokola") }
                    }
                }
            }
        }
    }
}
