package rs.battery.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CancellationException
import java.text.Normalizer
import java.util.Locale

private val navInk = Color(0xFF092130)
private val navCyan = Color(0xFF36C8FF)
private val navMuted = Color(0xFFB2CAD5)

internal fun searchName(text: String): String {
    val cyrillic = "абвгдђежзијклљмнњопрстћуфхцчџш"
    val latin = listOf("a","b","v","g","d","dj","e","z","z","i","j","k","l","lj","m","n","nj","o","p","r","s","t","c","u","f","h","c","c","dz","s")
    val transliterated = text.lowercase(Locale.ROOT).map { c ->
        val index = cyrillic.indexOf(c)
        if (index >= 0) latin[index] else c.toString()
    }.joinToString("")
    return Normalizer.normalize(transliterated, Normalizer.Form.NFD).filter {
        Character.getType(it) != Character.NON_SPACING_MARK.toInt()
    }.replace("đ", "dj")
}

internal data class MapSearchIndex(val stationSource: List<ChargingStation>,
    val stations: List<Pair<ChargingStation, String>>, val places: List<Pair<AtlasPlace, String>>)

internal fun matchingPlaces(index: MapSearchIndex, query: String): List<AtlasPlace> {
    if (query.isBlank()) return emptyList()
    return index.places.asSequence().filter { it.second.contains(query) }
        .sortedWith(compareBy<Pair<AtlasPlace, String>> { if (it.second == query) 0 else 1 }
            .thenBy { it.first.tier }.thenByDescending { it.first.population }.thenBy { it.first.name })
        .take(80).map { it.first }.toList()
}

internal object MapSearchCatalog {
    @Volatile private var index: MapSearchIndex? = null
    @Synchronized fun load(context: android.content.Context, stations: List<ChargingStation>): MapSearchIndex {
        index?.takeIf { it.stationSource === stations }?.let { return it }
        val stationNames = stations.map {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            it to searchName("${it.name} ${it.operator} ${it.address}")
        }
        val placeNames = index?.places ?: OfflineAtlas.vectors(context).places.map {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            it to searchName(it.name)
        }
        return MapSearchIndex(stations, stationNames, placeNames).also { index = it }
    }
}

@Composable internal fun MapSearchDialog(stations: List<ChargingStation>, origin: GpsPoint?,
    onPlace: (String, MapDestination) -> Unit, onStation: (ChargingStation) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var onlyChargers by remember { mutableStateOf(true) }
    var freeOnly by remember { mutableStateOf(false) }
    var compatibleOnly by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf<MapSearchIndex?>(null) }
    var searchError by remember { mutableStateOf(false) }
    LaunchedEffect(context, stations) {
        try { index = runInterruptible(Dispatchers.IO) { MapSearchCatalog.load(context.applicationContext, stations) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { searchError = true }
    }
    val q = remember(query) { searchName(query.trim()) }
    var foundStations by remember { mutableStateOf<List<ChargingStation>>(emptyList()) }
    var foundPlaces by remember { mutableStateOf<List<AtlasPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(index, q, freeOnly, compatibleOnly, onlyChargers, origin?.latitude, origin?.longitude) {
        val catalog = index ?: return@LaunchedEffect
        searching = true; foundStations = emptyList(); foundPlaces = emptyList()
        try {
            if (onlyChargers) foundStations = runInterruptible(Dispatchers.Default) {
                catalog.stations.asSequence().filter { (s, name) ->
                    (!freeOnly || s.free) && (!compatibleOnly || s.compatible) && (q.isBlank() || name.contains(q))
                }.map { (s, _) -> s to (origin?.let { routeKm(it.latitude, it.longitude, s.lat, s.lon) }
                    ?: if (s.lat in 41.7..46.3 && s.lon in 18.7..23.2) 0.0 else 1.0) }
                    .sortedBy { it.second }.map { it.first }.toList()
            } else foundPlaces = runInterruptible(Dispatchers.Default) {
                matchingPlaces(catalog, q)
            }
        } finally { searching = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(navInk).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                LText("Pretraga mape", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { LText("Zatvori", color = navCyan) }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { LText("Grad, mesto ili naziv punjača") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(onlyChargers, { onlyChargers = true }, label = { LText("Punjači") })
                FilterChip(!onlyChargers, { onlyChargers = false }, label = { LText("Mesta") })
            }
            if (onlyChargers) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(freeOnly, { freeOnly = !freeOnly }, label = { LText("Besplatni") })
                    FilterChip(compatibleOnly, { compatibleOnly = !compatibleOnly }, label = { LText("Za e-208") })
                }
                LText("${foundStations.size} lokacija · katalog dopunjen 15.11.2026.\nOSM, Open Charge Map i dopune mreža. Podaci nisu uživo.", color = navMuted, fontSize = 11.sp)
            }
            if (searchError) LText("Pretraga nije učitana. Zatvori i otvori ponovo.", color = navMuted, fontSize = 12.sp)
            else if (searching || index == null) LText("Pretražujem…", color = navCyan, fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (onlyChargers) {
                    items(foundStations, key = { it.id }) { station ->
                        Column(Modifier.fillMaxWidth().clickable { onStation(station) }.padding(vertical = 12.dp, horizontal = 4.dp)) {
                            LText("● ${station.name}", color = if (station.free) Color(0xFF00DB88) else navCyan, fontWeight = FontWeight.Bold)
                            LText(station.address.ifBlank { "%.4f, %.4f".format(Locale.US, station.lat, station.lon) }, color = navMuted, fontSize = 11.sp)
                            LText(station.sockets.ifBlank { "Priključci nisu upisani" } + " · " + station.priceText, color = Color.White, fontSize = 12.sp)
                            origin?.let { LText("≈ %.1f km vazdušno".format(routeKm(it.latitude, it.longitude, station.lat, station.lon)), color = navMuted, fontSize = 11.sp) }
                        }
                        HorizontalDivider(color = Color(0xFF244252))
                    }
                    if (!searching && index != null && foundStations.isEmpty()) item { LText("Nema lokacija za ovaj izbor.", color = navMuted) }
                } else {
                    items(foundPlaces) { place ->
                        Column(Modifier.fillMaxWidth().clickable {
                            onPlace(place.name, MapDestination(place.lat, place.lon))
                        }.padding(vertical = 12.dp)) {
                            LText(place.name, color = Color.White, fontWeight = FontWeight.Bold)
                            LText("%.4f° N, %.4f° E".format(Locale.US, place.lat, place.lon), color = navMuted, fontSize = 11.sp)
                            origin?.let {
                                LText("≈ %.1f km vazdušno od polaska".format(routeKm(it.latitude, it.longitude, place.lat, place.lon)),
                                    color = navMuted, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF244252))
                    }
                    if (q.isBlank()) item { LText("Upiši naziv mesta. Polazak i cilj možeš izabrati i dodirom na mapu.", color = navMuted) }
                    else if (!searching && index != null && foundPlaces.isEmpty()) item { LText("Mesto nije pronađeno u offline katalogu.", color = navMuted) }
                }
            }
        }
    }
}

@Composable internal fun ChargingStationGroupDialog(stations: List<ChargingStation>,
    onStation: (ChargingStation) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { LText("Punjači na ovoj lokaciji") }, text = {
        LazyColumn(Modifier.heightIn(max = 350.dp)) {
            items(stations, key = { it.id }) { station ->
                Column(Modifier.fillMaxWidth().clickable { onStation(station) }.padding(vertical = 12.dp)) {
                    LText(station.name, color = if (station.free) Color(0xFF00DB88) else navCyan, fontWeight = FontWeight.Bold)
                    LText(station.sockets.ifBlank { "Priključci nisu upisani" }, fontSize = 12.sp)
                    LText(station.priceText, fontSize = 12.sp)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { LText("Zatvori") } })
}

@Composable internal fun ChargingStationDialog(station: ChargingStation, canAddStop: Boolean,
    onNavigate: () -> Unit, onAddStop: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { LText(station.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LText(station.priceText, color = if (station.free) Color(0xFF00DB88) else navCyan)
            LText(station.sockets.ifBlank { "Priključci nisu navedeni u katalogu." })
            station.powerKw?.let { LText("Snaga priključka do ${"%.0f".format(it)} kW") }
            if (station.operator.isNotBlank()) LText(station.operator)
            if (station.address.isNotBlank()) LText(station.address)
            if (station.notes.isNotBlank()) LText(station.notes, fontSize = 12.sp)
            if (station.hours.isNotBlank()) LText("Radno vreme ${station.hours}")
            if (station.access == "customers") LText("Pristup za korisnike objekta.")
            if (station.access == "unknown") LText("Uslovi pristupa nisu upisani.")
            if (station.sockets.contains("Šuko")) LText("Šuko zahteva odgovarajući prenosni EVSE. Za procenu je ograničen na 2,3 kW.", fontSize = 12.sp)
            LText("Cenu, radno vreme i uslove parkinga proveri kod operatora. Zelena označava besplatnu energiju prema izvoru; plava naplatu ili nepoznatu cenu.", fontSize = 12.sp)
            if (!station.compatible) LText("Katalog nema potvrđen Type 2, CCS2 ili šuko priključak. Dopuna za e-208 nije izračunata.")
            if (station.checked.isNotBlank()) LText("Datum podatka u izvoru: ${station.checked}", fontSize = 11.sp)
            LText("Izvor: ${station.sourceLabel}\nOffline katalog dopunjen 15.11.2026.", fontSize = 11.sp)
            if (station.sourceUrl.startsWith("https://")) TextButton(onClick = {
                runCatching { uriHandler.openUri(station.sourceUrl) }
            }) { LText("Otvori izvor podataka") }
        }
    }, confirmButton = {
        Column {
            TextButton(onClick = onNavigate) { LText("Vodi do punjača") }
            if (canAddStop && station.compatible) TextButton(onClick = onAddStop) { LText("Dodaj dopunu na rutu") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { LText("Zatvori") } })
}

@Composable internal fun ChargingStopControls(stops: List<ChargingStop>, onChange: (List<ChargingStop>) -> Unit) {
    stops.forEachIndexed { index, stop ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                LText("${index + 1}. ${stop.station.name}", color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { onChange(stops.filterIndexed { i, _ -> i != index }) }) { LText("Ukloni", color = navCyan) }
            }
            LText("Dopuni do ${stop.targetSoc}%", color = navCyan, fontSize = 12.sp)
            Slider(value = stop.targetSoc.toFloat(), onValueChange = { value ->
                onChange(stops.mapIndexed { i, old -> if (i == index) old.copy(targetSoc = value.toInt()) else old })
            }, valueRange = 30f..100f, steps = 13)
        }
    }
}
