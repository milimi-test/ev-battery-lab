package rs.battery.lab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private val helpMuted = Color(0xFFB0C3D0)
private val helpCyan = Color(0xFF52DAF3)

/** Labels reuse the same explanations in small cards, detail rows and section headings. */
internal fun metricExplanation(label: String): String? {
    val key = label.lowercase(Locale.ROOT)
    return when {
        key == "minimum" -> "Najniža vrednost u skupu očitavanja ove kartice. Jedinica uz broj pokazuje da li je prikazan napon, SOH, temperatura ili drugi parametar."
        key == "maksimum" -> "Najviša vrednost u skupu očitavanja ove kartice. Tumači se u jedinici prikazanoj uz broj i zajedno sa ostalim vrednostima iste kartice."
        key == "prosek" -> "Aritmetička sredina dostupnih vrednosti u ovoj kartici. Jedinica uz broj određuje parametar; napon se prikazuje u V, SOH u procentima, a temperatura u °C."
        key == "razlika" -> "Razlika između najveće i najmanje vrednosti u istom skupu očitavanja. Za napone se razlika pretvara iz V u mV množenjem sa 1.000."
        key == "obično · ac" -> "Broj zabeleženih sesija sa protokom energije u bateriju i sveže očitanim mrežnim naponom OBC-a. Tip se određuje tokom punjenja. Istorija pre ove verzije i punjenja bez odgovora vozila mogu imati nepoznat tip."
        key == "brzo · dc" -> "Broj zabeleženih sesija sa priključenim kablom i ulaznom snagom preko 12 kW. OBC ovog vozila prihvata najviše 11 kW AC. Utvrđeni DC tip zadržava se kada snaga opadne. Sporo DC punjenje bez ovih podataka ostaje nepoznatog tipa. Brojač obuhvata snimljene sesije ove aplikacije."
        key.contains("v×i") || key == "vraćeno" || key == "bruto potrošeno" -> "Energija se računa integracijom snage baterije kroz vreme. Bruto potrošeno sabira pražnjenje, vraćeno sabira rekuperaciju, a neto predstavlja njihovu razliku. Praznine između očitavanja mogu smanjiti pokrivenost obračuna."
        key == "pokrivenost" -> "Udeo trajanja sesije sa dovoljno čestim očitavanjima napona i struje za integraciju energije. Veća pokrivenost daje potpuniji zbir."
        key.contains("broj punjenja") -> "AC i DC označavaju vrstu zabeleženih punjenja. Jedna sesija se broji jednom. Kada nema pouzdanog podatka o vrsti punjača, ostaje nepoznata vrsta. Porast SOC tokom prekida pokazuje moguće dodatno punjenje. Brojači obuhvataju sačuvanu istoriju ove aplikacije, najviše 500 sesija. Brisanje istorije briše i ove brojače."
        key.contains("soh") -> "SOH je procena zdravlja koju vraća BMS, izražena u odnosu na njegov referentni kapacitet. Na procenu utiču temperatura, prethodni ciklusi i kalibracija BMS-a. Vrednost za ćelijsku grupu odnosi se na par paralelno povezanih ćelija. Za procenu stvarno dostupne energije koristi se zabeležena vožnja ili kontrolisani test."
        key == "soc" || key.contains("stanje napunjenosti") -> "SOC očitava sistem vozila. Njegova skala prikazuje upotrebljivi opseg baterije. BMS koristi napon, protok struje i svoj model baterije. Gornji i donji bafer za ovaj primerak vozila još nisu zasebno utvrđeni."
        key.contains("potrošnj") || key.contains("neto energij") -> "Potrošnja se računa kao neto utrošena energija podeljena pređenim kilometrima, pomnoženo sa 100. Prikaz u kWh/100 km može se porediti samo uz dovoljno dužu vožnju. Ubrzanje, brzina, nagib, temperatura i potrošači menjaju rezultat. Osnova izračunavanja navedena je uz pojedinačnu vožnju."
        key.contains("vmin") || key == "minimum" || key == "najniža grupa" -> "Najniži napon među očitanim serijskim grupama baterije. e-208 ima 108 grupa, sa po dve paralelne ćelije. Kratkotrajni pad pod opterećenjem posmatra se zajedno sa strujom, temperaturom i SOC-om."
        key.contains("δv") || key == "razlika" -> "Razlika između najvišeg i najnižeg napona grupe iz istog skupa očitavanja, pomnožena sa 1.000 da se dobije mV. Poređenje pri maloj struji i sličnoj temperaturi je najkorisnije za praćenje balansa."
        key.contains("srednji napon") || key == "prosek" -> "Srednji napon iz BMS-a ili aritmetički prosek kompletnog niza grupa (108 kod e-CMP, 96 kod Zoe). Izbor koristi novije dostupno očitavanje. Ako oba nedostaju, za naponsku procenu može se koristiti napon paketa podeljen brojem serijskih grupa izabranog vozila. Izvor i vreme su navedeni u opisu SOC-a po naponu."
        key.contains("napon") || key == "maksimum" -> "Napon dolazi iz očitavanja vozila. Napon paketa je zbir serijski povezanih grupa, a napon jedne grupe odgovara njenom paru paralelnih ćelija. Struja i vreme od poslednjeg punjenja ili vožnje utiču na trenutnu vrednost."
        key.contains("struja") -> "Struja visokonaponske baterije je očitavanje BMS-a. U ovoj aplikaciji pozitivan smer označava pražnjenje, a negativan punjenje ili rekuperaciju."
        key.contains("snaga") -> "Snaga baterije se računa kao napon × struja ÷ 1.000, u kW. Pozitivna vrednost označava potrošnju, negativna ulaz energije. Posebno očitana snaga motora može odstupati jer baterija napaja i druge uređaje."
        key.contains("energij") -> "BMS energija je procena preostale energije koju vozilo vraća preko parametra D865. Ukupni energetski brojači su posebna očitavanja. Energija u bateriju obuhvata i rekuperaciju, dok unos sa brojila punjača uključuje gubitke punjenja."
        key.contains("kapacitet") -> "Kapacitet izveden iz vožnje ili punjenja računa se iz promene energije i SOC-a. Mala promena SOC-a povećava grešku. Temperatura, nepotpuna pokrivenost i pouzdanost energetskog podatka utiču na procenu."
        key.contains("gps pokrivenost") -> "Udeo vremena odabrane vožnje pokriven kvalitetnim GPS očitavanjima. Prekidi duži od 30 sekundi prekidaju liniju putanje. GPS prestaje da se prati po završetku vožnje ili prekidu veze sa vozilom."
        key.contains("gps brzina") -> "Brzina koju prijavljuje GPS telefona. Prikazuje se za svežu lokaciju prihvatljive tačnosti. Može kratkotrajno odstupati od brzine koju očitava vozilo."
        key.contains("brzina vozila") -> "Brzina očitana iz kontrolera vozila preko OBD-a. Zasebno se beleži GPS brzina telefona, kada je lokacija dostupna."
        key.contains("nadmorska") || key.contains("uspon") || key.contains("pad") -> "GPS visina potiče od telefona i zavisi od tačnosti satelitskog prijema. Model visine terena na mapi potiče od Copernicus GLO-90. Mostovi i tuneli mogu odstupati od terena ispod njih."
        key.contains("izolacija") -> "Otpornost izolacije visokonaponskog sistema koju vraća BMS, u kΩ. Za procenu problema potrebni su uslovi merenja i fabrička dijagnostika."
        key.contains("temperatur") || key.startsWith("baterija ·") || key.startsWith("baterija min") || key.startsWith("baterija max") || key == "baterija" -> "Temperatura je očitana iz odgovarajućeg senzora vozila. Baterija koristi 54 sonde. Prosek, minimum i maksimum obuhvataju očitanja koja je BMS vratio. Broj sonde označava njen logički redosled."
        key == "ćelije" || key.contains("serijskih grupa") || key.contains("temp. sonde") -> "e-CMP profil čita 108 serijskih grupa i 54 temperaturne sonde; Zoe profil čita 96 grupa i 12 sondi. BMS meri zajednički napon paralelnog para. Prikazuje se samo kompletan primljen niz za proračun proseka."
        else -> null
    }
}

@Composable internal fun MetricInfoIcon(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        LIcon(Icons.Outlined.Info, contentDescription = "Objašnjenje — $label", tint = helpCyan,
            modifier = Modifier.size(20.dp))
    }
}

@Composable internal fun MetricHelpDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { LText(title) },
        text = { LText(text, modifier = Modifier.verticalScroll(rememberScrollState()), fontSize = 16.sp, lineHeight = 23.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { LText("Zatvori") } })
}

/** Collapses explanatory prose behind one reusable accessible control. */
@Composable internal fun HelpCaption(title: String, explanation: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LText(title, color = helpMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        MetricInfoIcon(title) { open = true }
    }
    if (open) MetricHelpDialog(title, explanation) { open = false }
}

@Composable internal fun VoltageSocHelpDialog(value: PackSocDisplay, bmsSoc: Double?, onDismiss: () -> Unit) {
    var graph by remember { mutableStateOf(false) }
    fun number(v: Double?, digits: Int) = v?.let { "%.${digits}f".format(AppLanguage.locale, it) } ?: "—"
    val at = value.voltageEstimateAtMillis?.let {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", AppLanguage.locale).format(Date(it))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF04111D)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LText("SOC po naponu", color = Color.White, fontSize = 22.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        LIcon(Icons.Outlined.Close, "Zatvori SOC po naponu", tint = Color.White)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LText("${number(value.meanGroupVoltageV, 3)} V → ≈ ${number(value.voltageSocPercent, 1)} %",
                    fontSize = 22.sp, lineHeight = 28.sp, color = helpCyan, fontWeight = FontWeight.Bold)
                LText("SOC vozila ${number(bmsSoc, 1)} %", color = helpMuted, fontSize = 14.sp, lineHeight = 20.sp)
                LText(if (value.voltageSocPercent == null) "${value.voltageReason}\nPoredbena kriva druge ćelije."
                    else "Okvirna procena · poredbena kriva druge ćelije.",
                    color = Color(0xFFFFD567), fontSize = 14.sp, lineHeight = 20.sp)
                VoltageSocModelPicker(value.modelId, value.meanGroupVoltageV)
                LText(value.voltageSource + (at?.let { "\nOčitano $it" } ?: ""),
                    color = helpMuted, fontSize = 14.sp, lineHeight = 20.sp)
                LText("Izbor modela se pamti i odmah menja naponsku procenu. Domet koristi SOC vozila.", fontSize = 14.sp, lineHeight = 20.sp)
                LText("Poređenje ranijih merenja", color = helpCyan, fontWeight = FontWeight.Bold)
                LImage(painterResource(R.drawable.soc_voltage_reference),
                    contentDescription = "Napon u voltima prema SOC-u vozila i referentnim krivama. Dodirni za uvećanje.",
                    modifier = Modifier.fillMaxWidth().clickable { graph = true }, contentScale = ContentScale.FillWidth)
                TextButton(onClick = { graph = true }, modifier = Modifier.fillMaxWidth()) {
                    LIcon(Icons.Outlined.ZoomIn, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp)); LText("Uvećaj dijagram")
                }
                LText("Procenat se dobija linearnom interpolacijom između susednih tačaka izabranog modela. Prikazani napon i procenat koriste isto očitavanje. Izvan dostupnog raspona modela prikazuje se crtica.", lineHeight = 21.sp)
                LText(value.voltageReason, lineHeight = 21.sp)
                LText("Fabrička CATL kriva za tačnu ćeliju ovog vozila još nije potvrđena. Apsolutni SOC i zasebni gornji i donji bafer zato ostaju neutvrđeni. Za raspoloživi domet koristi se SOC vozila.", lineHeight = 21.sp)
                LText("Dijagram je analiza snimljenih vožnji iz oktobra i novembra 2026. Levo su tvoja merenja i dve tačke drugog e-208 iz AVILOO testa. Desno su poredbene LG M50, CALB NMC532, NMC622 krive i jedna tačka Entroview modula ECMP125P0M5.", fontSize = 14.sp, lineHeight = 20.sp, color = helpMuted)
                LText("Izvori\nChen i sar., JES 167 (2020) 080534 / PyBaMM Chen2020\narxiv.org/abs/2610.23049\ndoi.org/10.3390/batteries10080275\nentroview.com — Battery Certification Report, 19.05.2025.\nAVILOO — e-208 50 kWh, 08.11.2022.", fontSize = 12.sp, lineHeight = 18.sp, color = helpMuted)
                if (value.candidateD862 != null || value.candidateD863 != null || value.candidateD413 != null) {
                    LText("Dodatne BMS skale\nD862 ${number(value.candidateD862, 1)} % · D863 ${number(value.candidateD863, 1)} % · D413 ${number(value.candidateD413, 1)} %\nNjihovo tačno značenje još nije potvrđeno.", fontSize = 14.sp, lineHeight = 20.sp, color = helpMuted)
                }
                value.calibratedD862Percent?.let {
                    LText("Interpolacija mirovanja na D862 skali ${number(it, 1)} % · ${value.voltageAnchorCount} sidara", color = helpMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
                }
                LText("Prevuci nagore za objašnjenja i izvore ↓", color = helpMuted, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
    if (graph) VoltageGraphDialog { graph = false }
}

@Composable private fun VoltageGraphDialog(onDismiss: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF04111D)).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                LText("Napon i SOC", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { LIcon(Icons.Outlined.Close, "Zatvori dijagram", tint = Color.White) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xFFF9FAF8))) {
                val density = LocalDensity.current
                val w = with(density) { maxWidth.toPx() }
                val h = with(density) { maxHeight.toPx() }
                val imageScale = min(w / 2142f, h / 1156f)
                fun bounded(p: Offset, scale: Float): Offset {
                    val dx = ((2142f * imageScale * scale - w) / 2).coerceAtLeast(0f)
                    val dy = ((1156f * imageScale * scale - h) / 2).coerceAtLeast(0f)
                    return Offset(p.x.coerceIn(-dx, dx), p.y.coerceIn(-dy, dy))
                }
                val transform = rememberTransformableState { factor, pan, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 8f)
                    offset = bounded(offset + pan, zoom)
                }
                LImage(painterResource(R.drawable.soc_voltage_reference), contentDescription = "Dijagram napona i SOC-a. Raširi prste za uvećanje i povuci za pomeranje.",
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 3f; offset = Offset.Zero })
                    }.transformable(transform).graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f); offset = Offset.Zero }) {
                    LIcon(Icons.Outlined.ZoomOut, "Umanji dijagram", tint = helpCyan)
                }
                TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { LText("Ceo dijagram") }
                IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(8f) }) {
                    LIcon(Icons.Outlined.ZoomIn, "Uvećaj dijagram", tint = helpCyan)
                }
            }
        }
    }
}
