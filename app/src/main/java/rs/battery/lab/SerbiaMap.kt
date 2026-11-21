package rs.battery.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

private val mapNight = Color(0xFF071826)
private val mapGrid = Color(0xFF1A4051)
private val mapCyan = Color(0xFF03B9FF)
private val mapGreen = Color(0xFF00DB88)
private val mapAmber = Color(0xFFFFB957)
private val mapMuted = Color(0xFF9AB1C1)

private fun goodFixes(points: List<GpsPoint>): List<GpsPoint> {
    return points.filter {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
            (it.accuracyMeters == null || it.accuracyMeters <= 60f)
    }.sortedBy { it.timeMillis }
}

internal fun gapBetween(a: GpsPoint, b: GpsPoint, gaps: List<GpsGap>): Boolean {
    val elapsed = b.timeMillis - a.timeMillis
    if (elapsed <= 0 || elapsed > 30_000) return true
    // A bad location fix must not draw a straight line across the map.
    if (mapDistance(a, b) / (elapsed / 1000.0) > 60.0) return true
    return gaps.any { a.timeMillis < it.toMillis && b.timeMillis > it.fromMillis }
}

internal class MapSelection {
    var overview by mutableStateOf<Boolean?>(null)
    var rangeMode by mutableStateOf(false)
    var target by mutableStateOf<MapDestination?>(null)
    var manualOrigin by mutableStateOf<GpsPoint?>(null)
    var targetName by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    var layersOpen by mutableStateOf(false)
    var relief by mutableStateOf(false)
    var contours by mutableStateOf(true)
    var chargingStops by mutableStateOf<List<ChargingStop>>(emptyList())
}

@Composable internal fun TripMapDialog(points: List<GpsPoint>, gaps: List<GpsGap>, isLive: Boolean,
                                      range: MapRangeEstimate? = null, selection: MapSelection = remember { MapSelection() }, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(mapNight).statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            MapToolbar({ selection.searchOpen = true }, { selection.layersOpen = true }, dismiss)
            Column(Modifier.verticalScroll(rememberScrollState())) { TripMap(points, gaps, isLive, expanded = true, range = range, selection = selection) }
        }
    }
}

/** The atlas draws only the real points of the selected trip; it needs no network. */
@Composable internal fun TripMap(points: List<GpsPoint>, gaps: List<GpsGap>, isLive: Boolean = false,
                                 expanded: Boolean = false, range: MapRangeEstimate? = null,
                                 selection: MapSelection = remember { MapSelection() }) {
    val context = LocalContext.current
    val fixes = remember(points) { goodFixes(points) }
    LaunchedEffect(context) { RangePreferences.load(context) }
    val reserveSoc by RangePreferences.reserveSoc.collectAsState()
    var fullScreen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var rangeInfoOpen by remember { mutableStateOf(false) }
    var routeInfoOpen by remember { mutableStateOf(false) }
    var stationOpen by remember { mutableStateOf<ChargingStation?>(null) }
    var stationGroup by remember { mutableStateOf<List<ChargingStation>?>(null) }
    var stations by remember { mutableStateOf<List<ChargingStation>>(emptyList()) }
    var stationStatus by remember { mutableStateOf("Učitavam punjače…") }
    LaunchedEffect(context) {
        try {
            stations = runInterruptible(Dispatchers.IO) { ChargingStations.load(context.applicationContext) }
            stationStatus = "${stations.size} lokacija u offline katalogu"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { stationStatus = "Katalog punjača nije dostupan." }
    }
    val stationMarkers = remember(stations) { stations.map { it.marker } }
    var selectedOverview by selection::overview
    var rangeMode by selection::rangeMode
    val relief = selection.relief
    var terrainStatus by remember { mutableStateOf("") }
    val overview = fixes.size < 2 || selectedOverview == true
    val displayRange = range?.let { if (selection.manualOrigin != null) it.copy(origin = selection.manualOrigin) else it }
    val hasRange = displayRange?.let { estimate ->
        estimate.origin?.let { it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 } == true &&
            listOf(estimate.oneWayKm, estimate.returnKm).any { it != null && it.isFinite() && it > 0 }
    } == true
    var atlasView by remember(context) { mutableStateOf<OfflineAtlasView?>(null) }

    val destination = selection.target
    val chargingStops = selection.chargingStops
    var routeOrigin by remember { mutableStateOf<GpsPoint?>(null) }
    val manualOrigin = selection.manualOrigin
    val currentOrigin = manualOrigin ?: range?.origin
    val lastReading by LastReadingStore.reading.collectAsState()
    val pastTrips by TripMonitor.history.collectAsState()
    val pastHistory = remember(pastTrips) { rangeHistory(pastTrips) }
    val planningBattery = if (range?.soc != null || lastReading == null) range else
        mapRange(lastReading, null, pastHistory, lastReading!!.timestamp, reserveSoc)
    val planningFromSnapshot = range?.soc == null && lastReading != null
    LaunchedEffect(currentOrigin?.latitude, currentOrigin?.longitude, destination, manualOrigin) {
        val last = routeOrigin
        if (destination == null || currentOrigin == null) routeOrigin = null
        else if (last == null || manualOrigin != null || mapDistance(last, currentOrigin) >= 250) routeOrigin = currentOrigin
    }
    val stopStations = chargingStops.map { it.station }
    var journey by remember(destination, routeOrigin, stopStations) { mutableStateOf<PlannedJourney?>(null) }
    val roadPlan = remember(journey) { journey?.combined }
    var planStatus by remember(destination, routeOrigin, stopStations) { mutableStateOf("") }
    LaunchedEffect(destination, routeOrigin, stopStations) {
        val target = destination ?: return@LaunchedEffect
        val origin = routeOrigin ?: return@LaunchedEffect
        planStatus = "Računam put do odredišta…"
        try {
            val plan = runInterruptible(Dispatchers.IO) { planJourney(context.applicationContext, origin, target, chargingStops) }
            journey = plan
            planStatus = if (plan == null) "Do izabrane tačke nema povezane rute u offline mreži Srbije. Dodirni bliži javni put." else ""
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            planStatus = "Čitanje mreže puteva nije uspelo. Ponovo izaberi odredište."
        }
    }
    val weather by WeatherTracker.latest.collectAsState()
    var heightData by remember(roadPlan) { mutableStateOf<PlannedHeights?>(null) }
    LaunchedEffect(journey?.legs) {
        val legs = journey?.legs ?: return@LaunchedEffect
        heightData = runInterruptible(Dispatchers.IO) {
            plannedHeights(legs) { lat, lon -> RouteTerrain.height(context.applicationContext, lat, lon) }
        }
    }
    var analysis by remember(roadPlan) { mutableStateOf("") }
    LaunchedEffect(roadPlan, chargingStops, planningBattery?.soc?.times(10)?.roundToInt(), planningBattery?.usableKwh?.times(10)?.roundToInt(),
        planningBattery?.consumption?.times(10)?.roundToInt(), planningBattery?.reserveSoc, weather?.fetchedAt) {
        val plan = roadPlan ?: return@LaunchedEffect
        analysis = ""
        try {
            val currentJourney = journey?.copy(stops = chargingStops) ?: return@LaunchedEffect
            analysis = runInterruptible(Dispatchers.IO) {
                if (currentJourney.stops.isEmpty()) routeAnalysis(context.applicationContext, plan, planningBattery, weather)
                else journeyAnalysis(context.applicationContext, currentJourney, planningBattery, weather)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            analysis = "Put je pronađen. Proračun za ovu deonicu nije dostupan."
        }
    }

    if (rangeInfoOpen) RangeSettingsDialog(displayRange) { rangeInfoOpen = false }
    if (routeInfoOpen) MetricHelpDialog("Proračun rute",
        "Energija koristi zabeleženu potrošnju, model terena i dostupnu prognozu vetra. Model vozila ima masu 1.650 kg i okvirni povrat energije na spustu. AC limit je 11 kW za e-CMP i 22 kW za Zoe; DC vreme uključuje okvirno opadanje snage. Čekanje na slobodan punjač nije uračunato.\n\n" + analysis) { routeInfoOpen = false }
    if (fullScreen) TripMapDialog(points, gaps, isLive, range, selection) { fullScreen = false }
    if (selection.searchOpen && !fullScreen) MapSearchDialog(stations, currentOrigin,
        onPlace = { name, point -> selection.target = point; selection.targetName = name; atlasView?.focusOn(point); selection.searchOpen = false },
        onStation = { stationOpen = it; selection.searchOpen = false }, onDismiss = { selection.searchOpen = false })
    if (selection.layersOpen && !fullScreen) MapLayersDialog(relief, selection.contours,
        onRelief = { selection.relief = it }, onContours = { selection.contours = it },
        onInfo = { selection.layersOpen = false; infoOpen = true }, onDismiss = { selection.layersOpen = false })
    stationGroup?.let { group -> ChargingStationGroupDialog(group,
        onStation = { stationOpen = it; stationGroup = null }, onDismiss = { stationGroup = null }) }
    stationOpen?.let { station ->
        ChargingStationDialog(station, canAddStop = destination != null && chargingStops.size < 4 &&
            chargingStops.none { it.station.id == station.id },
            onNavigate = { selection.target = station.destination; selection.targetName = station.name; atlasView?.focusOn(station.destination); stationOpen = null },
            onAddStop = { selection.chargingStops = chargingStops + ChargingStop(station); stationOpen = null },
            onDismiss = { stationOpen = null })
    }
    if (infoOpen) Dialog(onDismissRequest = { infoOpen = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(Color(0xFF102B3B), RoundedCornerShape(14.dp)).padding(18.dp)) {
            LText("Podaci na mapi", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LText("Satelit · ESA WorldCover 2021. Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium. RGB kompozit je pripremljen u više rezolucija. Svaki piksel najdetaljnijeg sloja u Srbiji predstavlja oko 20–27 m.\n\nGranice · Natural Earth. Naselja · GeoNames, CC BY 4.0 (creativecommons.org/licenses/by/4.0/).\n\nPutevi · © OpenStreetMap contributors, ODbL 1.0 (openstreetmap.org/copyright), izvodi Geofabrik iz novembra 2026.\n\nVisine · Copernicus DEM GLO-90, prerađen u bojeni sloj. Contains modified Copernicus data (2019).",
                color = Color(0xFFD0E2EB), fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            LText("Boja putanje pokazuje relativnu GPS visinu u ovoj vožnji; siva označava tačke bez visine. Prekidi GPS-a se ne povezuju.",
                color = mapMuted, fontSize = 12.sp, lineHeight = 16.sp)
            LText("Izohipse su izvedene iz istih offline visina. Pripremljena mreža ima korak 1/512° (oko 150–220 m), pa linije ne predstavljaju detaljan geodetski plan. Interval prati zum. Šrafirana područja su van dostupnih podataka; nisu nulta nadmorska visina.",
                color = mapMuted, fontSize = 13.sp, lineHeight = 18.sp)
            LText("Ulice · © OpenStreetMap contributors, ODbL 1.0. Punjači · OSM, Open Charge Map (CC BY 4.0), otvoreni BlokVolt katalog (ODbL) i pojedinačne lokalne dopune. Katalog dopunjen 15.11.2026; izvor i datum svakog unosa su na kartici. Zelena: energija označena kao besplatna; plava: naplata ili nepoznata cena. Plan puta radi u mreži Srbije; dostupnost i cena nisu podaci uživo.",
                color = mapMuted, fontSize = 12.sp, lineHeight = 16.sp)
            TextButton(onClick = { infoOpen = false }, modifier = Modifier.align(Alignment.End)) {
                LText("ZATVORI", color = mapCyan)
            }
        }
    }

    if (!expanded) MapToolbar({ selection.searchOpen = true }, { selection.layersOpen = true })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MapMode("Putanja", !overview && !rangeMode, Modifier.weight(1f), enabled = fixes.size >= 2) {
            rangeMode = false; selectedOverview = false
        }
        MapMode("Srbija", overview && !rangeMode, Modifier.weight(1f)) { rangeMode = false; selectedOverview = true }
        MapMode("Domet", rangeMode, Modifier.weight(1f), enabled = hasRange) {
            rangeMode = true
        }
    }
    Spacer(Modifier.height(4.dp))
    val largeLabels = LocalDensity.current.fontScale > 1.2f
    val showRangeBadge = displayRange?.origin != null && displayRange.oneWayKm?.let { it.isFinite() && it >= 0 } == true
    if (largeLabels && showRangeBadge) {
        RangeMapBadge(displayRange!!, Modifier.fillMaxWidth().padding(bottom = 4.dp)) { rangeInfoOpen = true }
    }
    val mapHeight = if (expanded) (LocalConfiguration.current.screenHeightDp - if (largeLabels && showRangeBadge) 210f else 130f)
        .coerceIn(240f, 580f).dp else 315.dp
    Box(Modifier.fillMaxWidth().height(mapHeight).clip(RoundedCornerShape(12.dp))
        .background(mapNight, RoundedCornerShape(12.dp))
        .border(1.dp, mapGrid, RoundedCornerShape(12.dp))) {
        AndroidView(
            factory = { OfflineAtlasView(it).also { view -> atlasView = view } },
            update = { view ->
                atlasView = view
                view.onDestination = { point ->
                    routeOrigin = currentOrigin
                    selection.target = point
                    selection.targetName = ""
                }
                view.setChargers(stationMarkers)
                view.onChargerSelected = { id -> stationOpen = stations.firstOrNull { it.id == id } }
                view.onChargerGroupSelected = { ids -> stationGroup = stations.filter { it.id in ids } }
                view.setDestination(destination, roadPlan)
                view.onTerrainStatus = { terrainStatus = it }
                view.setContoursVisible(selection.contours)
                view.setContent(fixes, gaps, overview, relief, displayRange, rangeMode)
            },
            modifier = Modifier.fillMaxSize().padding(1.dp)
        )
        LaunchedEffect(atlasView, overview, rangeMode, fixes.firstOrNull()?.timeMillis) { atlasView?.resetCamera() }
        if (showRangeBadge && !largeLabels)
            RangeMapBadge(displayRange!!, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = 64.dp).fillMaxWidth()) { rangeInfoOpen = true }
        else if (!showRangeBadge) LText(if (overview) "SRBIJA" else "GPS PUTANJA", color = Color.White,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp)
                .background(Color(0xEE082431), RoundedCornerShape(6.dp)).padding(6.dp))
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MapZoomControl("+", "Uvećaj mapu") { atlasView?.zoomBy(1.5) }
            MapZoomControl("−", "Umanji mapu") { atlasView?.zoomBy(1.0 / 1.5) }
            MapZoomControl("⌖", "Prikaži celu mapu") { atlasView?.resetCamera() }
            if (roadPlan != null) MapZoomControl("↗", "Prikaži celu rutu") { atlasView?.showRoute() }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LText("● Besplatna energija", color = mapGreen, fontSize = 12.sp, modifier = Modifier.weight(1f))
        LText("● Ostali punjači", color = mapCyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
    LText(stationStatus, color = mapMuted, fontSize = 12.sp)
    if (relief) { TerrainLegend(); if (terrainStatus.isNotBlank()) LText(terrainStatus, color = mapMuted, fontSize = 12.sp) }
    val target = destination
    if (target != null) {
        Column(Modifier.fillMaxWidth().padding(top = 7.dp)
            .background(Color(0xFF102C3B), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LText(selection.targetName.ifBlank { "Izabrano odredište" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    LText("%.4f°, %.4f°".format(Locale.US, target.lat, target.lon), color = mapMuted, fontSize = 12.sp)
                }
                TextButton(onClick = { selection.target = null; selection.chargingStops = emptyList() }) { LText("Ukloni", color = mapCyan) }
            }
            if (manualOrigin != null)
                LText("Polazak sa ručno izabranog mesta", color = mapCyan, fontSize = 12.sp)
            if (planningFromSnapshot && currentOrigin != null)
                LText("Plan sa sačuvanim SOC-om · ${SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(lastReading!!.timestamp))}", color = mapAmber, fontSize = 12.sp)
            heightData?.let { PlannedElevationProfile(it, chargingStops) }
            if (chargingStops.isNotEmpty()) ChargingStopControls(chargingStops) { selection.chargingStops = it }
            TextButton(onClick = { selection.searchOpen = true }) { LText("Dodaj punjač na rutu", color = mapCyan) }
            if (analysis.isNotEmpty() && planStatus.isEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LText("Procena rute", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                MetricInfoIcon("Proračun rute") { routeInfoOpen = true }
            }
            LText(when {
                currentOrigin == null -> "Za plan bez GPS-a odaberi polazak ovde, pa dodirni odredište."
                planStatus.isNotEmpty() -> planStatus
                analysis.isEmpty() -> "Računam profil puta i potrošnju…"
                else -> compactRouteReport(analysis)
            }, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
            TextButton(onClick = {
                selection.manualOrigin = GpsPoint(System.currentTimeMillis(), target.lat, target.lon, null, null, null)
                selection.target = null
                selection.chargingStops = emptyList()
            }) { LText("Polazak sa ovog mesta", color = mapCyan) }
            if (manualOrigin != null) TextButton(onClick = { selection.manualOrigin = null }) {
                LText("Koristi GPS polazak", color = mapCyan)
            }
        }
    } else LText(if (manualOrigin != null) "Polazak je izabran. Dodirni odredište." else "Dodirni mapu za procenu puta do odredišta.", modifier = Modifier.padding(top = 5.dp),
        color = mapCyan, fontSize = 12.sp)
    RangeLegend(displayRange) { rangeInfoOpen = true }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText("Auto-put · narandžasto    Glavni put · žuto    Granica · svetloplavo", color = mapMuted, fontSize = 12.sp,
            modifier = Modifier.weight(1f))
        MetricInfoIcon("Podaci na mapi") { infoOpen = true }
    }
    RouteHeightLegend(fixes)
    if (!expanded) OutlinedButton(onClick = { fullScreen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
        LText("OTVORI MAPU I PROFIL VISINE", color = mapCyan, fontSize = 12.sp)
    }
    Spacer(Modifier.height(8.dp))
    if (fixes.isNotEmpty()) {
        RouteEndpoint("Početak", fixes.first(), mapGreen)
        if (fixes.size >= 2) RouteEndpoint(if (isLive) "Poslednja" else "Kraj", fixes.last(), mapAmber)
    } else LText("Mapa Srbije radi offline. Putanja se dodaje po snimanju GPS vožnje.",
        color = mapMuted, fontSize = 12.sp)
    Spacer(Modifier.height(14.dp))
    ElevationProfile(fixes, gaps)
}

@Composable private fun TerrainLegend() {
    Spacer(Modifier.height(6.dp))
    LText("Nadmorska visina terena (m)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val ranges = listOf(
        "0 m" to Color(0xFF087A77), "250" to Color(0xFF56AD69),
        "500" to Color(0xFFB2C660), "1000" to Color(0xFFE8BC65),
        "1500" to Color(0xFFCA8459), "2500+" to Color(0xFFA69AC4)
    )
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ranges.forEach { (label, color) ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(5.dp).background(color, RoundedCornerShape(2.dp)))
                LText(label, color = mapMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable private fun RangeMapBadge(range: MapRangeEstimate, modifier: Modifier, onInfo: () -> Unit) {
    Row(modifier.background(Color(0xEE082431), RoundedCornerShape(6.dp)).padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            LText("REZERVA ${range.reserveSoc}% · PROCENA", color = mapMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
            LText("Putem ≈ %.0f km".format(Locale.getDefault(), range.roadKm ?: 0.0), color = Color(0xFF32D8F0),
                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            LText("Krug %.0f · povratak %.0f km".format(Locale.getDefault(), range.oneWayKm ?: 0.0, range.returnKm ?: 0.0), color = Color(0xFFFFD567),
                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        MetricInfoIcon("Krugovi dometa", onInfo)
    }
}

private fun compactRouteReport(report: String): String = report.lineSequence()
    .filterNot { it.startsWith("Model terena ") || it.startsWith("Procena koristi ") }
    .map { if (it.startsWith("Osnova ")) it.substringBefore(". Model") else it }
    .joinToString("\n")

@Composable private fun RangeLegend(range: MapRangeEstimate?, onInfo: () -> Unit) {
    Spacer(Modifier.height(7.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText("Procena dometa · rezerva ${range?.reserveSoc ?: RangePreferences.DEFAULT_RESERVE_SOC}%", color = Color.White,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        MetricInfoIcon("Procena dometa") { onInfo() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RangeLegendItem("Putem do rezerve", range?.roadKm, Color(0xFF32D8F0), Modifier.weight(1f))
        RangeLegendItem("Putem do 0%", range?.roadKmToZero, Color(0xFFFFD567), Modifier.weight(1f))
    }
    val status = when {
        range?.origin == null -> "Čekam GPS polazak. Detalji i podešavanja na ⓘ."
        range.oneWayKm == null -> "Čekam dovoljno očitavanja za procenu dometa."
        range.detail.contains("Poslednje očitavanje baterije") -> "Poslednji sačuvani podaci · vreme očitavanja na ⓘ."
        else -> "Krugovi su vazdušna udaljenost. Detalji na ⓘ."
    }
    LText(status, color = mapMuted, fontSize = 12.sp, lineHeight = 16.sp, minLines = 2, maxLines = 2)

}

@Composable private fun RangeLegendItem(label: String, km: Double?, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).border(2.dp, color, RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            LText(label, color = mapMuted, fontSize = 12.sp, lineHeight = 16.sp, minLines = 2, maxLines = 2)
            LText(km?.let { "%.0f km".format(Locale.US, it) } ?: "—", color = if (km != null) Color.White else mapMuted,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun RouteHeightLegend(fixes: List<GpsPoint>) {
    if (fixes.isEmpty()) return
    val heights = remember(fixes) { fixes.mapNotNull { it.altitudeMeters?.takeIf(Double::isFinite) } }
    if (heights.isEmpty()) {
        LText("GPS putanja: siva — visina nije snimljena", color = mapMuted, fontSize = 12.sp)
        return
    }
    val min = heights.minOrNull() ?: return
    val max = heights.maxOrNull() ?: return
    if (min == max) {
        LText("GPS visina putanje · ${"%.0f".format(Locale.US,min)} m", color = mapCyan, fontSize = 12.sp)
        return
    }
    val mid = (min + max) / 2
    LText("GPS VISINA PUTANJE", color = mapMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(
            Triple("${"%.0f".format(Locale.US, min)} m", Color(0xFF32D8F0), "min"),
            Triple("${"%.0f".format(Locale.US, mid)} m", Color(0xFFFFD567), "sred."),
            Triple("${"%.0f".format(Locale.US, max)} m", Color(0xFFF2669C), "max")
        ).forEach { (value, color, label) ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    LText(label, color = mapMuted, fontSize = 12.sp)
                    LText(value, color = color, fontSize = 12.sp)
                }
            }
        }
    }
    if (heights.size < fixes.size) LText("Siva: tačke bez GPS visine", color = mapMuted, fontSize = 12.sp)
}

@Composable private fun MapZoomControl(label: String, accessibilityLabel: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).background(Color(0xFF0B3045), RoundedCornerShape(10.dp))
        .border(1.dp, mapGrid, RoundedCornerShape(10.dp))
        .semantics { contentDescription = UiStrings.text(accessibilityLabel) }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        LText(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun MapMode(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).background(if (selected) Color(0xFF064E73) else mapNight, RoundedCornerShape(8.dp))
        .border(1.dp, if (selected) mapCyan else mapGrid, RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        LText(label, color = if (!enabled) mapMuted.copy(alpha = .55f) else if (selected) Color.White else mapMuted, fontSize = 14.sp,
            lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable private fun RouteEndpoint(label: String, point: GpsPoint, color: Color) {
    val time = remember(point.timeMillis) {
        SimpleDateFormat("HH:mm", AppLanguage.locale).format(Date(point.timeMillis))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        LText("● $label · $time", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        LText("${"%.4f".format(Locale.US, point.latitude)}, ${"%.4f".format(Locale.US, point.longitude)}",
            color = mapMuted, fontSize = 12.sp)
    }
}

@Composable internal fun ElevationProfile(points: List<GpsPoint>, gaps: List<GpsGap>) {
    val elevatedIndices = points.indices.filter { points[it].altitudeMeters?.isFinite() == true }
    val elevated = elevatedIndices.map { points[it] }
    LText("Profil visine", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    if (elevated.size < 2) {
        LText("Profil će se pojaviti kada GPS zabeleži bar dve tačke sa visinom.",
            color = mapMuted, fontSize = 12.sp)
        return
    }
    val elevations = elevated.map { it.altitudeMeters!! }
    val minimum = elevations.minOrNull() ?: 0.0
    val maximum = elevations.maxOrNull() ?: minimum
    val span = (maximum - minimum).coerceAtLeast(2.0)
    fun profileGap(index: Int): Boolean = index > 0 &&
        (elevatedIndices[index] != elevatedIndices[index - 1] + 1 ||
            gapBetween(elevated[index - 1], elevated[index], gaps))
    val distance = mutableListOf(0.0)
    elevated.indices.drop(1).forEach { index ->
        val add = if (profileGap(index)) 0.0 else mapDistance(elevated[index - 1], elevated[index])
        distance.add(distance.last() + add)
    }
    val total = distance.last()
    val firstLabel = if (elevatedIndices.first() == 0) "Početak" else "Prva visina"
    val lastLabel = if (elevatedIndices.last() == points.lastIndex) "Kraj" else "Poslednja"
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ElevationMetric(firstLabel, elevations.first(), mapGreen, Modifier.weight(1f))
        ElevationMetric(lastLabel, elevations.last(), mapAmber, Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ElevationMetric("Minimum", minimum, mapMuted, Modifier.weight(1f))
        ElevationMetric("Maksimum", maximum, mapCyan, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    LText("GPS visina (m)", color = mapMuted, fontSize = 12.sp)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val axisWidth = with(density) {
        listOf(minimum, maximum, (minimum + maximum) / 2).maxOf {
            measurer.measure("%.0f".format(Locale.US, it), style = TextStyle(fontSize = 12.sp)).size.width
        }.toDp() + 8.dp
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(axisWidth).height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
            LText("${"%.0f".format(Locale.US, maximum)}", color = mapMuted, fontSize = 12.sp)
            LText("${"%.0f".format(Locale.US, (minimum + maximum) / 2)}", color = mapMuted, fontSize = 12.sp)
            LText("${"%.0f".format(Locale.US, minimum)}", color = mapMuted, fontSize = 12.sp)
        }
        Canvas(Modifier.weight(1f).height(160.dp).background(mapNight, RoundedCornerShape(8.dp))) {
            val horizontalInset = 8.dp.toPx()
            fun xAt(index: Int): Float = horizontalInset +
                (if (total > 1.0) distance[index] / total else index.toDouble() / elevated.lastIndex) .toFloat() *
                (size.width - horizontalInset * 2)
            fun yAt(index: Int): Float = size.height *
                (.9f - ((elevations[index] - minimum) / span).toFloat() * .8f)
            for (fraction in listOf(.1f, .5f, .9f)) {
                val y = size.height * fraction
                drawLine(mapGrid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            for (i in 1 until elevated.size) {
                if (profileGap(i)) continue
                val relative = ((elevations[i]+elevations[i-1])*.5-minimum)/span
                val color = if (relative <= .5) androidx.compose.ui.graphics.lerp(Color(0xFF32D8F0), Color(0xFFFFD567), (relative*2).toFloat())
                    else androidx.compose.ui.graphics.lerp(Color(0xFFFFD567), Color(0xFFF2669C), (relative*2-1).toFloat())
                drawLine(color, Offset(xAt(i-1),yAt(i-1)), Offset(xAt(i),yAt(i)), 2.dp.toPx())
            }
            drawCircle(mapGreen, 4.dp.toPx(), Offset(xAt(0), yAt(0)))
            drawCircle(mapAmber, 4.dp.toPx(), Offset(xAt(elevated.lastIndex), yAt(elevated.lastIndex)))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = axisWidth), horizontalArrangement = Arrangement.SpaceBetween) {
        LText("0 km", color = mapGreen, fontSize = 12.sp)
        LText("${"%.1f".format(Locale.US, total / 1000)} km", color = mapAmber, fontSize = 12.sp)
    }
    LText("Samo snimljene GPS visine • prekidi nisu spojeni", color = mapMuted, fontSize = 12.sp)
}

@Composable private fun ElevationMetric(label: String, meters: Double, color: Color, modifier: Modifier) {
    Column(modifier.background(mapNight, RoundedCornerShape(7.dp))
        .border(1.dp, mapGrid, RoundedCornerShape(7.dp)).padding(horizontal = 5.dp, vertical = 7.dp)) {
        LText(label, color = mapMuted, fontSize = 12.sp)
        LText("${"%.0f".format(Locale.US, meters)} m", color = color, fontSize = 16.sp,
            fontWeight = FontWeight.Bold)
    }
}

private fun mapDistance(a: GpsPoint, b: GpsPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val s = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 12_742_000.0 * asin(sqrt(s.coerceIn(0.0, 1.0)))
}
