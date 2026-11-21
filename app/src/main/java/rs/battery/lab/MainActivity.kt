package rs.battery.lab

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val bg = Color(0xFF04111D)
private val panel = Color(0xFF091F30)
private val border = Color(0xFF17384B)
private val cyan = Color(0xFF03B9FF)
private val green = Color(0xFF00DB88)
private val muted = Color(0xFF9AB1C1)
private val red = Color(0xFFFF6471)

class MainActivity : ComponentActivity() {
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (connectAfterLocation) { connectAfterLocation = false; ObdBus.start(this) }
        else ObdBus.refreshGps(this)
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] != true)
            GpsTracker.report(this, GpsTrackerStatus.NO_PERMISSION, "Precizna lokacija nije odobrena")
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (connectAfterPermission) { connectAfterPermission = false; startConnection() }
            else { scanPaired(); showDevices.value = true }
        } else status.value = "Bluetooth dozvola nije odobrena"
    }
    private val prefs by lazy { getSharedPreferences("battery_lab", MODE_PRIVATE) }
    private val status = mutableStateOf("Nije povezano")
    private val deviceName = mutableStateOf("Nema izabranog adaptera")
    private val paired = mutableStateListOf<Pair<String, String>>()
    private val records = mutableStateListOf<JSONObject>()
    private val samples = mutableStateListOf<BmsReading>()
    private val showDevices = mutableStateOf(false)
    private val keepScreenOn = mutableStateOf(false)
    private var address: String? = null
    private val historyJobs = mutableListOf<kotlinx.coroutines.Job>()
    private var switchingVehicle = false
    private var connectAfterPermission = false
    private var connectAfterLocation = false

    override fun attachBaseContext(newBase: android.content.Context) {
        AppLanguage.initialize(newBase)
        super.attachBaseContext(VehicleDataContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("reload_vehicle", false)) {
            VehicleProfiles.reload(this)
            intent.removeExtra("reload_vehicle")
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(4, 17, 29)
        window.navigationBarColor = android.graphics.Color.rgb(4, 17, 29)
        address = prefs.getString("address", null)
        keepScreenOn.value = prefs.getBoolean("keep_screen_on", false)
        applyScreenAwake()
        deviceName.value = prefs.getString("device", null) ?: "Nema izabranog adaptera"
        val saved = JSONArray(prefs.getString("records", "[]"))
        for (i in 0 until saved.length()) records.add(saved.getJSONObject(i))
        val savedSamples = JSONArray(prefs.getString("samples", "[]"))
        for (i in 0 until savedSamples.length()) {
            val sample = savedSamples.getJSONObject(i)
            samples.add(BmsReading(soc = sample.optDouble("soc").takeIf { !it.isNaN() }, soh = sample.optDouble("soh").takeIf { !it.isNaN() }, energy = sample.optDouble("energy").takeIf { !it.isNaN() }, voltage = sample.optDouble("voltage").takeIf { !it.isNaN() }, current = sample.optDouble("current").takeIf { !it.isNaN() }, odometerKm = sample.optDouble("km").takeIf { !it.isNaN() }, tempAvg = sample.optDouble("temp").takeIf { !it.isNaN() }, tempMin = sample.optDouble("tempMin").takeIf { !it.isNaN() }, tempMax = sample.optDouble("tempMax").takeIf { !it.isNaN() }, cellMin = sample.optDouble("cellMin").takeIf { !it.isNaN() }, cellMax = sample.optDouble("cellMax").takeIf { !it.isNaN() }, timestamp = sample.optLong("time")))
        }
        RecordingControl.load(this)
        LastReadingStore.load(this)
        val legacySummary = samples.maxByOrNull { it.timestamp }
        historyJobs += lifecycleScope.launch(Dispatchers.IO) { LastReadingStore.migrateIfMissing(this@MainActivity, legacySummary) }
        TripMonitor.load(this)
        ChargeMonitor.load(this)
        BatteryHistory.load(this)
        ChargerMeterStore.load(this)
        ChargeGapMonitor.load(this)
        historyJobs += lifecycleScope.launch(Dispatchers.IO) { backgroundHistory { ChargeGapMonitor.reconcile(this@MainActivity) } }
        historyJobs += lifecycleScope.launch(Dispatchers.IO) { backgroundHistory { ChartHistory.load(this@MainActivity) } }
        setContent { App() }
        if (!intent.getBooleanExtra("skip_auto_connect", false) && address != null && prefs.getBoolean("auto_connect", false)) {
            if (hasBtPermission()) startConnection() else { connectAfterPermission = true; permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
        }
    }

    override fun onResume() {
        super.onResume()
        ObdBus.refreshGps(this)
    }

    private fun bluetooth(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
    private fun hasBtPermission() = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun scanPaired() {
        if (!hasBtPermission()) { permission.launch(Manifest.permission.BLUETOOTH_CONNECT); status.value = "Odobrite Bluetooth dozvolu"; return }
        paired.clear()
        val adapter = bluetooth()
        if (adapter == null) { status.value = "Telefon nema Bluetooth"; return }
        if (!adapter.isEnabled) { status.value = "Uključite Bluetooth i uparite OBD u podešavanjima telefona"; return }
        adapter.bondedDevices?.map { (it.name ?: "OBD uređaj") to it.address }
            ?.sortedBy { if (it.first.equals("Android-Vlink", ignoreCase = true)) 0 else 1 }
            ?.forEach { paired.add(it) }
        status.value = if (paired.isEmpty()) "Uparite Android-Vlink u podešavanjima telefona (PIN 1234)" else "Za iCar Pro 2S izaberite Android-Vlink"
    }

    private fun selectDevice(addr: String, name: String) {
        if (address != addr) ObdBus.stop(this)
        address = addr; deviceName.value = name
        prefs.edit().putString("address", addr).putString("device", name).putBoolean("auto_connect", false).apply()
        status.value = "Izabran $name. Pritisnite Poveži."
        ObdBus.status.value = status.value
    }

    private fun startConnection() {
        if (address == null) { scanPaired(); showDevices.value = true; return }
        if (!hasBtPermission()) { connectAfterPermission = true; permission.launch(Manifest.permission.BLUETOOTH_CONNECT); return }
        prefs.edit().putBoolean("auto_connect", true).apply()
        ObdBus.status.value = "Povezivanje sa ${deviceName.value}..."
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("gps_prompted", false)) {
            connectAfterLocation = true
            requestGpsPermission()
        } else ObdBus.start(this)
    }

    private fun requestGpsPermission() {
        prefs.edit().putBoolean("gps_prompted", true).apply()
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun repairGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (prefs.getBoolean("gps_prompted", false) && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION))
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else requestGpsPermission()
        } else if (getSystemService(LocationManager::class.java)?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else ObdBus.refreshGps(this)
    }

    private fun stopConnection() {
        prefs.edit().putBoolean("auto_connect", false).commit()
        ObdBus.stop(this)
    }

    private fun stopAndExit() {
        connectAfterPermission = false
        connectAfterLocation = false
        if (!AppExit.stopAndExit(this)) {
            status.value = "Praćenje je zaustavljeno, ali podešavanje automatskog povezivanja nije sačuvano. Pokušajte ponovo."
            ObdBus.status.value = status.value
        }
    }

    private fun applyScreenAwake() {
        if (keepScreenOn.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toggleScreenAwake() {
        keepScreenOn.value = !keepScreenOn.value
        prefs.edit().putBoolean("keep_screen_on", keepScreenOn.value).apply()
        applyScreenAwake()
    }

    private fun addRecord(type: String, detail: String) {
        val item = JSONObject().put("type", type).put("detail", detail).put("time", System.currentTimeMillis())
        records.add(0, item)
        prefs.edit().putString("records", JSONArray(records).toString()).apply()
    }

    private suspend fun clearMeasurementHistory() {
        connectAfterPermission = false
        connectAfterLocation = false
        stopService(Intent(this, ObdService::class.java))
        kotlinx.coroutines.withTimeout(10_000L) {
            while (ObdBus.serviceRunning) delay(50L)
        }
        val legacySummary = samples.maxByOrNull { it.timestamp }
        withContext(Dispatchers.IO) {
            LastReadingStore.migrateIfMissing(this@MainActivity, legacySummary)
            MeasurementStorage.clear(this@MainActivity)
        }
        records.clear()
        samples.clear()
    }

    private fun switchVehicle(profile: VehicleProfile) {
        if (switchingVehicle || profile == VehicleProfiles.current(this)) return
        switchingVehicle = true
        connectAfterPermission = false
        connectAfterLocation = false
        lifecycleScope.launch {
            try {
                stopService(Intent(this@MainActivity, ObdService::class.java))
                kotlinx.coroutines.withTimeout(10_000L) {
                    while (ObdBus.serviceRunning) delay(50L)
                }
                historyJobs.forEach { it.cancel(); it.join() }
                // Reload on a new Activity: its Context and prefs are bound to the new vehicle.
                if (!VehicleProfiles.select(this@MainActivity, profile)) error("Izbor vozila nije sačuvan")
                startActivity(Intent(this@MainActivity, MainActivity::class.java).putExtra("reload_vehicle", true)
                    .putExtra("skip_auto_connect", true))
                finish()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                status.value = "Promena vozila nije uspela: ${e.message}"
                switchingVehicle = false
            }
        }
    }

    private fun export() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { MeasurementStorage.export(this@MainActivity) }
            }
            result.onSuccess { archive ->
                runCatching {
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", archive)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, UiStrings.text("Pošalji merenja za analizu")))
                }.onFailure { error ->
                    status.value = "Deljenje nije uspelo: ${error.message}"
                    ObdBus.status.value = status.value
                }
            }.onFailure { error ->
                status.value = "Izvoz nije uspeo: ${error.message}"
                ObdBus.status.value = status.value
            }
        }
    }

    @Composable private fun App() {
        var page by remember { mutableStateOf(0) }
        var tab by remember { mutableStateOf(0) }
        val demo = false
        val reading by ObdBus.reading.collectAsState()
        val confirmedAt by ObdBus.confirmedAt.collectAsState()
        val lastReading by LastReadingStore.reading.collectAsState()
        val recordingEnabled by RecordingControl.enabled.collectAsState()
        var connectionClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(5_000); if (ObdBus.serviceRunning) connectionClock = System.currentTimeMillis() } }
        val now = maxOf(connectionClock, System.currentTimeMillis())
        val liveReading = reading?.takeIf { ObdBus.serviceRunning && confirmedAt > 0 &&
            now - confirmedAt in 0..30_000 && now - it.timestamp in 0..120_000 }
        val displayedReading = liveReading ?: lastReading
        val connectionStatus by ObdBus.status.collectAsState()
        val shownStatus = if (liveReading == null && connectionStatus.startsWith("Povezano")) "Čekam svež odgovor vozila" else connectionStatus
        val serviceSamples by ObdBus.samples.collectAsState()
        val chartHistory by ChartHistory.readings.collectAsState()
        val chartSamples = remember(chartHistory, serviceSamples, samples.size) { (samples.toList() + serviceSamples + chartHistory).associateBy { it.timestamp }.values.sortedBy { it.timestamp } }
        val activeTrip by TripMonitor.active.collectAsState()
        val trips by TripMonitor.history.collectAsState()
        val storedSamples: List<BmsReading> = if (serviceSamples.isEmpty()) samples else serviceSamples
        val labels = listOf("Početna", "Baterija", "Vožnja", "Punjenje", "Više")
        val icons = listOf(Icons.Default.Home, Icons.Default.BatteryFull, Icons.Default.DirectionsCar, Icons.Default.Bolt, Icons.Default.MoreHoriz)
        BatteryLabTheme {
            Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page != 0 || tab != 0) {
                        IconButton(onClick = { page = 0; tab = 0 }, modifier = Modifier.size(40.dp)) {
                            LIcon(Icons.Default.ArrowBack, contentDescription = "Nazad na početnu", tint = Color.White)
                        }
                    }
                    LText(VehicleProfiles.current(this@MainActivity).name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    LText(if (liveReading != null) "● POVEZANO" else "● ČEKA VEZU", color = if (liveReading != null) green else muted,
                        fontSize = 10.sp, maxLines = 1)
                    RecordingSwitch(recordingEnabled) { enabled -> ObdBus.setRecording(this@MainActivity, enabled) }
                    IconButton(onClick = { toggleScreenAwake() }, modifier = Modifier.size(42.dp)) {
                        LIcon(Icons.Default.Lightbulb, contentDescription = if (keepScreenOn.value) "Ekran ostaje uključen; dodirni da isključiš" else "Dozvoli da se ekran ugasi; dodirni da ostane uključen",
                            tint = if (keepScreenOn.value) Color(0xFFFFD34E) else muted, modifier = Modifier.size(23.dp))
                    }
                }
                val tabs = when (page) { 0 -> listOf("POČETNA", "UŽIVO"); 1 -> listOf("BATERIJA", "ĆELIJE", "TEMPERATURE"); 2 -> listOf("VOŽNJA", "POTROŠNJA", "REKUPERACIJA"); 3 -> listOf("PUNJENJE", "ISTORIJA", "GRAFICI"); else -> listOf("DIJAGNOSTIKA", "ANALIZA", "PODEŠAVANJA") }
                if (page != 0 || tab != 0) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF071B2A))) {
                    tabs.forEachIndexed { i, title -> Column(Modifier.clickable { tab = i }.padding(horizontal = 14.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) { LText(title, color = if (tab == i) Color.White else muted, fontSize = 11.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal); if (tab == i) Spacer(Modifier.padding(top = 8.dp).width(45.dp).height(2.dp).background(cyan)) } }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = remember(page, tab) { LazyListState() }, contentPadding = PaddingValues(horizontal = if (page == 0 && tab == 0) 10.dp else 14.dp, vertical = if (page == 0 && tab == 0) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        if ((page != 0 || tab != 0) && liveReading == null) {
                            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LIcon(Icons.Default.Bluetooth, null, tint = muted, modifier = Modifier.size(20.dp))
                                LText(shownStatus, color = muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { startConnection() }) { LText("Poveži", fontWeight = FontWeight.Bold) }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (liveReading == null && displayedReading != null) {
                            LText("Poslednje očitavanje · ${date(displayedReading.timestamp)}", color = cyan, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
                        }
                        when (page) {
                            0 -> Home(tab, demo, if (tab == 0) liveReading else displayedReading, shownStatus, deviceName.value, onChoose = { scanPaired(); showDevices.value = true }, onConnect = { startConnection() }, onVehicle = { switchVehicle(it) }, onOpen = { p -> page = p; tab = if (p == 0) 1 else 0 })
                            1 -> { Battery(tab, demo, displayedReading); if (tab == 0) { ExtraSensorPanel(displayedReading); ChargeCountsCard() } }
                            2 -> Drive(tab, liveReading, chartSamples, activeTrip, trips, onFinish = { ObdBus.endTrip(this@MainActivity) }, onGpsRepair = ::repairGps)
                            3 -> Charge(tab, demo, displayedReading, storedSamples, chartSamples, trips, onSave = { addRecord("Punjenje", it) }, onExport = { export() })
                            4 -> More(tab, demo, displayedReading, storedSamples, chartSamples, records, onDemo = {}, onConnect = { scanPaired(); showDevices.value = true }, onStart = { startConnection() }, onStop = { stopConnection() }, onGpsPermission = { requestGpsPermission() }, onExport = { export() }, onClear = { clearMeasurementHistory() }, onReading = { soc, energy, voltage, temp -> addRecord("Merenje", "$soc|$energy|$voltage|$temp") })
                        }
                    }
                    val visibleRecords = if (page == 3 && tab == 1) records.filter { it.optString("type") == "Punjenje" } else if (page == 4 && tab == 1) records.toList() else emptyList()
                    items(visibleRecords.size) { i -> RecordCard(visibleRecords[i]) }
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF071B2A)).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    labels.forEachIndexed { i, label -> Column(Modifier.weight(1f).clickable { page = i; tab = 0 }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) { LIcon(icons[i], label, tint = if (page == i) cyan else muted, modifier = Modifier.size(21.dp)); LText(label, color = if (page == i) cyan else muted, fontSize = 10.sp) } }
                    IconButton(onClick = { stopAndExit() }, modifier = Modifier.size(48.dp)) {
                        LIcon(Icons.Default.PowerSettingsNew, contentDescription = "Zaustavi praćenje i izađi iz aplikacije",
                            tint = red, modifier = Modifier.size(23.dp))
                    }
                }
            }
            if (showDevices.value) AlertDialog(onDismissRequest = { showDevices.value = false }, title = { LText("Izaberi upareni OBD adapter") }, text = { Column { LText(status.value, fontSize = 13.sp); paired.forEach { item -> LText("${item.first}  ${item.second}", Modifier.fillMaxWidth().clickable { showDevices.value = false; selectDevice(item.second, item.first) }.padding(12.dp)) } } }, confirmButton = { TextButton(onClick = { scanPaired() }) { LText("OSVEŽI") } }, dismissButton = { TextButton(onClick = { showDevices.value = false }) { LText("ZATVORI") } })
        }
    }
}

@Composable
private fun RecordingSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val currentOnChange by rememberUpdatedState(onChange)
    Box(
        modifier = Modifier.size(48.dp)
            .semantics {
                contentDescription = UiStrings.text("Snimanje merenja")
                stateDescription = UiStrings.text(if (enabled) "Snimanje uključeno" else "Snimanje isključeno")
            }
            .pointerInput(enabled) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragEnd = {
                        if (distance > 16f) currentOnChange(true)
                        else if (distance < -16f) currentOnChange(false)
                    },
                    onHorizontalDrag = { change, delta ->
                        distance += delta
                        change.consume()
                    }
                )
            }
            .clickable(role = Role.Switch, onClickLabel = if (enabled) "Isključi snimanje" else "Uključi snimanje") {
                onChange(!enabled)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 44.dp, height = 26.dp)
            .background(if (enabled) green.copy(alpha = 0.65f) else border, RoundedCornerShape(13.dp)))
        Box(Modifier.offset(x = if (enabled) 9.dp else (-9).dp).size(20.dp)
            .background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            LIcon(if (enabled) Icons.Default.FiberManualRecord else Icons.Default.Pause,
                contentDescription = null, tint = if (enabled) red else muted, modifier = Modifier.size(12.dp))
        }
    }
}

private fun date(time: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", AppLanguage.locale).format(Date(time))

@Composable private fun CardBox(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val explanation = title?.let(::metricExplanation)
    var showHelp by remember(title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFF24465A), shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF0C2638), Color(0xFF071927))), shape)
        .padding(15.dp)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(19.dp).background(cyan, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(9.dp))
                LText(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f),
                    lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (explanation != null) MetricInfoIcon(title) { showHelp = true }
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
    if (showHelp && title != null && explanation != null) MetricHelpDialog(title, explanation) { showHelp = false }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    val explanation = metricExplanation(label)
    var showHelp by remember(label) { mutableStateOf(false) }
    val firstSpace = value.indexOf(' ')
    val prefix = if (firstSpace > 0) value.substring(0, firstSpace) else value
    val hasUnit = firstSpace > 0 && prefix.any(Char::isDigit) &&
        prefix.all { it.isDigit() || it in "+−-.," }
    val number = if (hasUnit) prefix else value
    val unit = if (hasUnit) value.substring(firstSpace + 1) else null
    val shape = RoundedCornerShape(11.dp)
    val helpModifier = if (explanation != null) Modifier.clickable(role = Role.Button, onClickLabel = "Objašnjenje — $label") { showHelp = true } else Modifier
    Column(modifier.then(helpModifier).heightIn(min = 77.dp).border(1.dp, Color(0xFF24475A), shape)
        .background(Color(0xFF081D2C), shape).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LText(label, color = muted, fontSize = 11.sp, lineHeight = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (explanation != null) LIcon(Icons.Default.Info, null, tint = cyan.copy(alpha = .8f), modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LText(number, color = if (number == "—") muted else color, fontSize = 19.sp,
                fontWeight = FontWeight.Bold, modifier = if (unit != null) Modifier.weight(1f, fill = false) else Modifier,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (unit != null) LText(unit, color = muted, fontSize = 10.sp, lineHeight = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp), maxLines = 2)
        }
    }
    if (showHelp && explanation != null) MetricHelpDialog(label, explanation) { showHelp = false }
}
@Composable private fun Metrics(vararg items: Pair<String, String>) {
    items.toList().chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            row.forEach { Metric(it.first, it.second, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
@Composable private fun Action(text: String, onClick: () -> Unit) {
    Button(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF086FDF), contentColor = Color.White)) {
        LText(text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            letterSpacing = .2.sp, textAlign = TextAlign.Center)
    }
}
@Composable private fun Placeholder() { CardBox { LText("Čekam stvarna očitavanja iz izabranog vozila. Uparite Vlink adapter u Android podešavanjima, zatim ga izaberite na početnom ekranu.", color = muted, fontSize = 13.sp) } }
private fun fmt(value: Double?, decimals: Int = 1, unit: String = ""): String = if (value == null) "—" else "${"%.${decimals}f".format(Locale.US, value)}$unit"

@Composable private fun Home(tab: Int, demo: Boolean, live: BmsReading?, status: String, device: String, onChoose: () -> Unit, onConnect: () -> Unit, onVehicle: (VehicleProfile) -> Unit, onOpen: (Int) -> Unit) {
    val activeTrip by TripMonitor.active.collectAsState()
    val context = LocalContext.current
    val vehicle = remember(context) { VehicleProfiles.current(context) }
    var chooseVehicle by remember { mutableStateOf(false) }
    if (chooseVehicle) VehiclePicker(vehicle, { chooseVehicle = false; onVehicle(it) }) { chooseVehicle = false }
    if (tab == 0) {
        OutlinedButton(onClick = { chooseVehicle = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            LIcon(Icons.Default.DirectionsCar, null, tint = cyan)
            Spacer(Modifier.width(8.dp))
            LText("${vehicle.name}  ·  Promeni vozilo", modifier = Modifier.weight(1f))
            LIcon(Icons.Default.ExpandMore, "Izaberi vozilo")
        }
        if (vehicle.isZoe && vehicle.dcCapable) {
            val equipment = remember(context) { context.getSharedPreferences("vehicle_equipment", android.content.Context.MODE_PRIVATE) }
            var ccs by remember(context) { mutableStateOf(equipment.getBoolean("ccs", false)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ccs, onCheckedChange = { ccs = it; equipment.edit().putBoolean("ccs", it).apply() })
                LText("Vozilo ima CCS priključak za DC punjenje", color = muted, fontSize = 12.sp)
            }
        }
        val originalHero = vehicle.id == VehicleProfiles.all.first().id
        Box(Modifier.fillMaxWidth().height(if (originalHero) 390.dp else 340.dp).background(Color(0xFF04111D))) {
            LImage(painterResource(vehicle.image), contentDescription = vehicle.name,
                modifier = if (vehicle.id == VehicleProfiles.all.first().id) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter),
                contentScale = if (vehicle.id == VehicleProfiles.all.first().id) ContentScale.Crop else ContentScale.Fit)
            Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color(0xBF031321), Color.Transparent))))
            Column(Modifier.align(Alignment.TopCenter).padding(top = if (originalHero) 70.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(22.dp).height(1.dp).background(Color.White.copy(alpha = .8f)))
                    LText(vehicle.brand.uppercase(Locale.ROOT), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Box(Modifier.width(22.dp).height(1.dp).background(Color.White.copy(alpha = .8f)))
                }
                Spacer(Modifier.height(5.dp))
                LText(vehicle.model, modifier = Modifier.padding(horizontal = 12.dp), color = Color.White, fontSize = (if (vehicle.model.length > 14) 25 else 34).sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                LText("Battery Lab", color = cyan, fontSize = 25.sp, fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                Spacer(Modifier.height(3.dp))
                LText(vehicle.variant, color = Color.White, fontSize = 12.sp)
            }
            if (demo) LText("DEMO • primeri, nisu podaci iz vozila", color = Color(0xFFFFD167), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC04111D), RoundedCornerShape(5.dp)).padding(5.dp))
        }
        Spacer(Modifier.height(9.dp))
        CardBox { Row(verticalAlignment = Alignment.CenterVertically) { LIcon(Icons.Default.Bluetooth, null, tint = cyan, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { LText(device, color = Color.White, fontWeight = FontWeight.Bold); LText("Bluetooth OBD adapter", color = muted, fontSize = 11.sp) }; LText("● ${if (demo) "DEMO" else if (live != null) "Povezan" else "Nije povezan"}", color = if (live != null) green else muted, fontSize = 11.sp) }; Spacer(Modifier.height(8.dp)); LText(status, color = if (live != null) green else muted, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { LText("Izaberi adapter", color = cyan, fontSize = 13.sp, modifier = Modifier.weight(1f).clickable(onClick = onChoose)); LText("Poveži", color = cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onConnect)) } }
        Spacer(Modifier.height(8.dp))
        CardBox { Row(verticalAlignment = Alignment.CenterVertically) { LIcon(Icons.Default.BluetoothConnected, null, tint = green); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { LText("Automatsko povezivanje", color = Color.White, fontSize = 13.sp); LText("Izaberite upareni OBD adapter", color = muted, fontSize = 10.sp) } } }
        Spacer(Modifier.height(8.dp))
        Action(if (live == null) "POVEŽI I POKRENI NADZOR" else "OTVORI NADZOR") { if (live == null) onConnect(); onOpen(0) }
        if (activeTrip != null) { Spacer(Modifier.height(8.dp)); Action("ZAVRŠI VOŽNJU I GPS") { ObdBus.endTrip(context) } }
    } else {
        CardBox("Baterija i potrošnja") { if (demo) { SocGauge(); Metrics("Preostala energija" to "29.7 kWh", "Struja" to "−12.3 A", "Snaga" to "−4.9 kW", "Spoljna temperatura" to "23 °C", "Baterija" to "24 °C", "Potrošnja" to "12.4 kWh/100 km") } else if (live != null) { SocGauge(live.soc); Metrics("Energija" to fmt(live.energy, 1, " kWh"), "Struja" to fmt(live.current, 1, " A"), "Snaga" to fmt(live.powerKw, 1, " kW"), "Napon" to fmt(live.voltage, 1, " V"), "Baterija min" to fmt(live.tempMin, 0, " °C"), "Baterija max" to fmt(live.tempMax, 0, " °C")); LText("Očitano ${date(live.timestamp)}", color = muted, fontSize = 11.sp) } else Placeholder() }
    }
}
@Composable private fun CarArt() { Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0xFF203A55),Color(0xFF102638),Color(0xFF07131E))),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) { Canvas(Modifier.fillMaxSize()) { val w=size.width; val h=size.height
    drawCircle(cyan.copy(alpha=.12f),h*.42f,Offset(w*.5f,h*.72f))
    val shadow=Path().apply { moveTo(w*.12f,h*.82f); quadraticBezierTo(w*.5f,h*.94f,w*.88f,h*.82f) }; drawPath(shadow,Color.Black.copy(alpha=.8f),style=Stroke(h*.08f))
    val body=Path().apply { moveTo(w*.09f,h*.66f); quadraticBezierTo(w*.16f,h*.57f,w*.26f,h*.55f); lineTo(w*.34f,h*.27f); quadraticBezierTo(w*.49f,h*.20f,w*.66f,h*.27f); lineTo(w*.74f,h*.55f); quadraticBezierTo(w*.86f,h*.57f,w*.91f,h*.66f); lineTo(w*.89f,h*.82f); quadraticBezierTo(w*.5f,h*.91f,w*.11f,h*.82f); close() }
    drawPath(body,Brush.verticalGradient(listOf(Color.White,Color(0xFFDEEAF1),Color(0xFF8FA8B8))))
    val glass=Path().apply { moveTo(w*.35f,h*.31f); quadraticBezierTo(w*.5f,h*.26f,w*.65f,h*.31f); lineTo(w*.71f,h*.54f); lineTo(w*.29f,h*.54f); close() }; drawPath(glass,Brush.verticalGradient(listOf(Color(0xFF06121E),Color(0xFF31536D))))
    drawLine(Color(0xFF899EAD),Offset(w*.5f,h*.3f),Offset(w*.5f,h*.55f),2f)
    drawLine(Color(0xFFF9FFFF),Offset(w*.12f,h*.68f),Offset(w*.28f,h*.65f),h*.055f); drawLine(cyan,Offset(w*.12f,h*.7f),Offset(w*.27f,h*.67f),h*.02f)
    drawLine(Color(0xFFF9FFFF),Offset(w*.72f,h*.65f),Offset(w*.88f,h*.68f),h*.055f); drawLine(cyan,Offset(w*.73f,h*.67f),Offset(w*.88f,h*.7f),h*.02f)
    drawLine(Color(0xFF233744),Offset(w*.34f,h*.7f),Offset(w*.66f,h*.7f),h*.025f)
    drawRect(Color(0xFF102231),Offset(w*.37f,h*.77f),androidx.compose.ui.geometry.Size(w*.26f,h*.075f)); drawRect(Color(0xFF1878D8),Offset(w*.37f,h*.77f),androidx.compose.ui.geometry.Size(w*.023f,h*.075f))
    drawLine(Color.White.copy(alpha=.55f),Offset(w*.19f,h*.59f),Offset(w*.33f,h*.33f),2f)
}; LText("BG 3428 KJ",color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=23.dp)) } }
@Composable private fun SocGauge(soc: Double? = 64.0) { Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) { Canvas(Modifier.size(175.dp)) { drawArc(Color(0xFF104055), 145f, 250f, false, style = Stroke(18f)); drawArc(Brush.sweepGradient(listOf(green, cyan, green)), 145f, 250f * ((soc ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat(), false, style = Stroke(18f)) }; Column(horizontalAlignment = Alignment.CenterHorizontally) { LText("SOC", color = muted); LText(fmt(soc, 0, " %"), color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold); LText("● Baterija", color = green) } } }

@Composable private fun BatterySocGauge(soc: Double?) {
    Box(Modifier.size(154.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(143.dp)) {
            drawArc(Color(0xFF104055), 145f, 250f, false, style = Stroke(14.dp.toPx()))
            if (soc != null) drawArc(Brush.sweepGradient(listOf(green, cyan, green)), 145f,
                250f * (soc.coerceIn(0.0, 100.0) / 100.0).toFloat(), false,
                style = Stroke(14.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LText("SOC", color = muted, fontSize = 11.sp)
            LText(fmt(soc, 0, " %"), color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            LText("BMS", color = green, fontSize = 10.sp)
        }
    }
}

@Composable private fun BatteryHeroMetric(label: String, value: String, color: Color = Color.White, onClick: (() -> Unit)? = null) {
    val explanation = metricExplanation(label)
    var showHelp by remember(label) { mutableStateOf(false) }
    val action = onClick ?: explanation?.let { { showHelp = true } }
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .then(if (action != null) Modifier.clickable(role = Role.Button, onClickLabel = "Objašnjenje — $label", onClick = action) else Modifier)
        .border(1.dp, border, RoundedCornerShape(8.dp))
        .background(Color(0xFF071826), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LText(label, color = muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (action != null) LIcon(Icons.Default.Info, null, tint = cyan, modifier = Modifier.size(14.dp))
        }
        LText(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    if (showHelp && explanation != null) MetricHelpDialog(label, explanation) { showHelp = false }
}

@Composable private fun Battery(tab: Int, demo: Boolean, live: BmsReading?) {
    if (!demo && live == null && tab == 0) { Placeholder(); return }
    val cells = if (demo) remember { List(108) { 3.674 + ((it * 37) % 13) * .001 } } else live?.cellVoltages.orEmpty()
    val probes = if (demo) remember { List(54) { 21.0 + ((it * 17) % 12) } } else live?.probeTemperatures.orEmpty()
    val context = LocalContext.current
    val vehicle = VehicleProfiles.current(context)
    LaunchedEffect(context) { VoltageSocPreferences.load(context) }
    val voltageModel by VoltageSocPreferences.selectedId.collectAsState()
    // A stored reading is evaluated at its original timestamp and is labelled by App().
    val voltageSoc = remember(live, voltageModel) {
        packSocDisplay(context, live, live?.timestamp ?: System.currentTimeMillis(), voltageModel)
    }
    var showVoltageHelp by remember { mutableStateOf(false) }
    if (showVoltageHelp) {
        if (vehicle.isZoe) MetricHelpDialog("SOC i srednji napon", voltageSoc.voltageReason) { showVoltageHelp = false }
        else VoltageSocHelpDialog(voltageSoc, live?.soc) { showVoltageHelp = false }
    }
    when (tab) {
        0 -> {
            CardBox(if (demo) "Stanje baterije • primer" else "Stanje baterije • BMS") {
                val recentCells = live?.takeIf { it.cellVoltages.size == vehicle.seriesGroups && it.cellVoltagesAt != null &&
                    it.timestamp - it.cellVoltagesAt in 0..120_000 }
                val extrema = if (live?.cellMin != null && live.cellMax != null)
                    live.cellMin to live.cellMax
                    else recentCells?.cellVoltages?.let { it.minOrNull() to it.maxOrNull() }
                val vmin = live?.cellMin ?: extrema?.first
                val deltaMv = if (extrema?.first != null && extrema.second != null)
                    (extrema.second!! - extrema.first!!) * 1000.0 else null
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { BatterySocGauge(live?.soc) }
                    Column(Modifier.width(142.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        BatteryHeroMetric("Vmin • ćelija", fmt(vmin, 3, " V"), cyan)
                        BatteryHeroMetric("ΔV • ćelije", fmt(deltaMv, 0, " mV"), green)
                        if (vehicle.isZoe) BatteryHeroMetric("SOH · paket", fmt(live?.soh, 1, " %"), Color.White) { showVoltageHelp = true }
                        else BatteryHeroMetric("SOC po naponu", voltageSoc.voltageSocPercent?.let { "≈ " + fmt(it, 1, " %") } ?: "Nema procene", Color.White) { showVoltageHelp = true }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showVoltageHelp = true }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LText("Srednji napon ${fmt(voltageSoc.meanGroupVoltageV, 3, " V")}", color = cyan, fontSize = 12.sp)
                        LText(if (vehicle.isZoe) "96 grupa · SOC iz vozila" else "Model: ${VoltageSocModels.byId(voltageSoc.modelId)?.title.orEmpty()}", color = muted, fontSize = 10.sp)
                    }
                    MetricInfoIcon("SOC po naponu i dijagram") { showVoltageHelp = true }
                }
                Spacer(Modifier.height(8.dp))
                if (live != null) Metrics("SOH (BMS)" to fmt(live.soh, 1, " %"), "BMS energija" to fmt(live.energy, 1, " kWh"), "Napon" to fmt(live.voltage, 1, " V"), "Struja" to fmt(live.current, 1, " A"), "Snaga (V × A)" to fmt(live.powerKw, 1, " kW"), "Baterija · prosek" to fmt(live.tempAvg, 1, " °C"))
                else LText("Čekam stvarna BMS očitavanja.", color = muted, fontSize = 11.sp)
            }
            CardBox("Detaljni parametri") {
                if (demo) {
                    MetricLine("Napon baterije", "397.4 V"); MetricLine("Struja", "−12.3 A")
                    MetricLine("Trenutna snaga", "−4.9 kW"); MetricLine("Maks. punjenje", "85 kW")
                    MetricLine("Unutrašnji otpor", "12.4 mΩ")
                } else {
                    MetricLine("Napon baterije", fmt(live!!.voltage, 1, " V"))
                    MetricLine("Srednji napon grupe", fmt(voltageSoc.meanGroupVoltageV, 3, " V"))
                    MetricLine("Zbir napona grupa", fmt(live.cellVoltageSum, 2, " V"))
                    MetricLine("Struja", fmt(live.current, 1, " A"))
                    MetricLine("Snaga", fmt(live.powerKw, 1, " kW"))
                    MetricLine("12 V akumulator", fmt(live.auxiliaryVoltage, 2, " V"))
                    MetricLine("Izolacija HV", fmt(live.insulationKohm, 0, " kΩ"))
                    MetricLine("Ćelije", if (cells.isEmpty()) "Čeka podatke" else "${cells.size} grupa")
                    MetricLine("Temp. sonde", if (probes.isEmpty()) "Čeka podatke" else "${probes.size} sondi")
                    LText("Očitano ${date(live.timestamp)}", color = muted, fontSize = 11.sp)
                }
            }
        }
        1 -> {
            CardBox("Baterija · slika i moduli") { PackMap(live, 0) }
            CardBox(if (demo) "108 serijskih grupa • primer" else "Napon ćelija • BMS") {
                val min = cells.minOrNull() ?: live?.cellMin
                val max = cells.maxOrNull() ?: live?.cellMax
                val avg = cells.takeIf { it.isNotEmpty() }?.average()
                Metrics("Minimum" to fmt(min, 3, " V"), "Prosek" to fmt(avg, 3, " V"), "Maksimum" to fmt(max, 3, " V"), "Razlika" to fmt(if (min != null && max != null) (max - min) * 1000 else null, 0, " mV"))
                if (!demo && live != null) Metrics("Najniža grupa" to (live.lowestCellIndex?.toString() ?: "—"), "Najviša grupa" to (live.highestCellIndex?.toString() ?: "—"))
                if (cells.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    LText("Raspodela napona po grupama", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    SeriesBars(cells, cyan, green)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LText("1", color = muted, fontSize = 10.sp)
                        LText("${cells.size} grupa", color = muted, fontSize = 10.sp)
                    }
                } else LText("Grafikon će se pojaviti kada BMS vrati sve napone ćelija.", color = muted, fontSize = 11.sp)
                if (!demo && live?.cellVoltagesAt != null) LText(
                    "Ćelije: ${readingAge(live.cellVoltagesAt)} • ${date(live.cellVoltagesAt)}",
                    color = muted, fontSize = 11.sp
                )
            }
            if (cells.isNotEmpty()) CardBox(if (demo) "Primer pojedinačnih grupa" else "Pojedinačne grupe") {
                var showAll by remember { mutableStateOf(false) }
                val shown = if (showAll) cells else cells.take(12)
                shown.forEachIndexed { index, voltage -> MetricLine("Grupa ${index + 1}", fmt(voltage, 3, " V")) }
                if (cells.size > 12) LText(
                    if (showAll) "Prikaži manje" else "Prikaži svih ${cells.size}",
                    color = cyan, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 12.dp)
                )
            }
            val cellHealth = if (demo) emptyList() else live?.cellSoh.orEmpty()
            if (cellHealth.isNotEmpty()) CardBox("SOH pojedinačnih grupa • BMS") {
                Metrics(
                    "Minimum" to fmt(cellHealth.minOrNull(), 1, " %"),
                    "Prosek" to fmt(cellHealth.average(), 1, " %"),
                    "Maksimum" to fmt(cellHealth.maxOrNull(), 1, " %"),
                    "Broj grupa" to "${cellHealth.size}"
                )
                Spacer(Modifier.height(8.dp))
                SeriesBars(cellHealth, Color(0xFFFFA43D), green)
                if (live?.cellSohAt != null) LText("SOH: ${readingAge(live.cellSohAt)} • ${date(live.cellSohAt)}", color = muted, fontSize = 11.sp)
                var showAllHealth by remember { mutableStateOf(false) }
                (if (showAllHealth) cellHealth else cellHealth.take(12)).forEachIndexed { index, soh ->
                    MetricLine("Grupa ${index + 1}", fmt(soh, 1, " %"))
                }
                if (cellHealth.size > 12) LText(
                    if (showAllHealth) "Prikaži manje" else "Prikaži svih ${cellHealth.size}",
                    color = cyan, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { showAllHealth = !showAllHealth }.padding(vertical = 12.dp)
                )
            }
        }
        else -> {
            CardBox("Mapa baterije · temperature") { PackMap(live, 1) }
            CardBox(if (demo) "Termička mapa • primer" else "Temperaturne sonde • BMS") {
                val low = probes.minOrNull() ?: live?.tempMin
                val high = probes.maxOrNull() ?: live?.tempMax
                Metrics("Najniža" to fmt(low, 0, " °C"), "Prosek" to fmt(probes.takeIf { it.isNotEmpty() }?.average() ?: live?.tempAvg, 1, " °C"), "Najviša" to fmt(high, 0, " °C"))
                if (probes.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    SeriesBars(probes, cyan, Color(0xFFFFA43D))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LText("Hladnije", color = cyan, fontSize = 10.sp)
                        LText("${probes.size} sondi", color = muted, fontSize = 10.sp)
                        LText("Toplije", color = Color(0xFFFFA43D), fontSize = 10.sp)
                    }
                } else LText("Detaljna mapa se prikazuje kada BMS vrati temperature sondi.", color = muted, fontSize = 11.sp)
                if (!demo && live?.probeTemperaturesAt != null) LText(
                    "Sonde: ${readingAge(live.probeTemperaturesAt)} • ${date(live.probeTemperaturesAt)}",
                    color = muted, fontSize = 11.sp
                )
            }
            if (!demo && live != null) CardBox("Temperature vozila") {
                Metrics("Baterija (BMS prosek)" to fmt(live.tempAvg, 1, " °C"), "OBC · ugrađeni punjač" to fmt(live.obcTemp, 1, " °C"), "Motor · stator" to fmt(live.motorTemp, 1, " °C"), "Inverter" to fmt(live.inverterTemp, 1, " °C"), "DC/DC" to fmt(live.dcdcTemp, 1, " °C"), "Spolja" to fmt(live.outsideTemp, 1, " °C"))
                Metrics("Tečnost · ulaz u motor" to fmt(live.motorCoolantTemp, 1, " °C"), "Tečnost · pogonski krug" to fmt(live.tractionCoolantTemp, 1, " °C"), "Tečnost · grejanje" to fmt(live.heatingCoolantTemp, 1, " °C"))
                LText("— znači da ECU nije potvrdio očitavanje. Temperatura hladnjaka kao posebna sonda nije potvrđena; ulaz u motor nije isto što i hladnjak.", color = muted, fontSize = 11.sp)
            }
            if (probes.isNotEmpty()) CardBox("Temperature po sondi") {
                var showAll by remember { mutableStateOf(false) }
                (if (showAll) probes else probes.take(12)).forEachIndexed { index, temp ->
                    MetricLine("Sonda ${index + 1}", fmt(temp, 0, " °C"))
                }
                if (probes.size > 12) LText(
                    if (showAll) "Prikaži manje" else "Prikaži svih ${probes.size}",
                    color = cyan, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 12.dp)
                )
            }
        }
    }
    if (!demo && !live?.measurementWarnings.isNullOrEmpty()) CardBox("Napomene očitavanja") {
        live!!.measurementWarnings.forEach { warning ->
            LText("• $warning", color = Color(0xFFFFC66D), fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}
private fun readingAge(time: Long): String {
    val minutes = ((System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "upravo"
        minutes < 60 -> "pre ${minutes} min"
        else -> "pre ${minutes / 60} h"
    }
}
@Composable private fun MetricLine(label: String, value: String) {
    val explanation = metricExplanation(label)
    var showHelp by remember(label) { mutableStateOf(false) }
    val helpModifier = if (explanation != null) Modifier.clickable(role = Role.Button, onClickLabel = "Objašnjenje — $label") { showHelp = true } else Modifier
    Row(Modifier.fillMaxWidth().then(helpModifier).heightIn(min = 48.dp).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LText(label, color = muted, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        LText(value, color = green, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        if (explanation != null) LIcon(Icons.Default.Info, null, tint = cyan.copy(alpha = .8f), modifier = Modifier.size(15.dp))
    }
    HorizontalDivider(color = border)
    if (showHelp && explanation != null) MetricHelpDialog(label, explanation) { showHelp = false }
}
@Composable private fun SeriesBars(values: List<Double>, lowColor: Color, highColor: Color) {
    if (values.isEmpty()) return
    val low = values.minOrNull() ?: return
    val range = ((values.maxOrNull() ?: low) - low).coerceAtLeast(.001)
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        for (row in 0..3) drawLine(border, Offset(0f, size.height * row / 3), Offset(size.width, size.height * row / 3), 1f)
        val step = size.width / values.size
        values.forEachIndexed { index, value ->
            val ratio = ((value - low) / range).toFloat().coerceIn(0f, 1f)
            val height = size.height * (.28f + .66f * ratio)
            val x = step * (index + .5f)
            drawLine(
                androidx.compose.ui.graphics.lerp(lowColor, highColor, ratio),
                Offset(x, size.height),
                Offset(x, size.height - height),
                (step * .7f).coerceAtLeast(1f)
            )
        }
    }
}

@Composable private fun SampleChart(samples: List<BmsReading>) { OverlayTelemetryChart(samples, defaults = listOf("soc", "energy")) }

@Composable private fun Drive(tab: Int, live: BmsReading?, samples: List<BmsReading>, active: TripRecord?, trips: List<TripRecord>, onFinish: () -> Unit, onGpsRepair: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(context) { RangePreferences.load(context) }
    val reserveSoc by RangePreferences.reserveSoc.collectAsState()
    val gpsPaused by ObdBus.gpsPaused.collectAsState()
    val gpsPoint by GpsTracker.lastPoint.collectAsState()
    val gpsPoints by GpsTracker.points.collectAsState()
    val gpsStatus by GpsTracker.status.collectAsState()
    val weather by WeatherTracker.latest.collectAsState()
    val untracked by ChargeGapMonitor.untracked.collectAsState()
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    val now = maxOf(clockTick, System.currentTimeMillis())
    LaunchedEffect(live != null, active != null) {
        while (true) { clockTick = System.currentTimeMillis(); delay(if (live != null || active != null) 1_000 else 30_000) }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showDetailedDriveSignals by remember { mutableStateOf(false) }
    LaunchedEffect(active?.id) { if (active != null) selectedId = active.id }
    val selected = (listOfNotNull(active) + trips.asReversed()).firstOrNull { it.id == selectedId }
        ?: active ?: trips.lastOrNull()
    val isLiveTrip = selected != null && selected.id == active?.id
    val intervalEnd = if (isLiveTrip) maxOf(selected!!.endedAt, now) else selected?.endedAt ?: now
    var routeReadError by remember { mutableStateOf(false) }
    val routeKey = selected?.id
    val loadedRoute by produceState<Pair<String?, List<GpsPoint>>>(null to emptyList(), routeKey, selected?.endedAt, gpsPoints.lastOrNull()?.timeMillis) {
        val result = withContext(Dispatchers.IO) {
            runCatching { selected?.let { GpsTracker.readPointsInInterval(context, it.startedAt,
                if (isLiveTrip) maxOf(System.currentTimeMillis(), it.endedAt) else it.endedAt) }.orEmpty() }
        }
        routeReadError = result.isFailure
        value = routeKey to result.getOrDefault(emptyList())
    }
    val tripPoints = if (loadedRoute.first == routeKey) loadedRoute.second else emptyList()
    val gps = selected?.let { GpsTracker.summarizeInterval(tripPoints, it.startedAt, intervalEnd, it.id) }
    val route = summarizeGps(tripPoints)
    val freshPoint = gpsPoint?.takeIf { now - it.timeMillis in -5_000..15_000 }
    val selectedSamples = if (selected == null) emptyList() else samples.filter { it.timestamp in selected.startedAt..selected.endedAt }
    val historyForRange = remember(trips, active) { rangeHistory(trips, active) }
    val lastBmsReading by LastReadingStore.reading.collectAsState()
    val persistedFix by produceState<GpsPoint?>(null, trips.lastOrNull()?.endedAt) {
        value = withContext(Dispatchers.IO) { GpsTracker.lastRecordedPoint(context) }
    }
    val lastKnownFix = listOfNotNull(gpsPoint, gpsPoints.lastOrNull(), persistedFix).maxByOrNull { it.timeMillis }
    var lastRangeReading by remember { mutableStateOf<BmsReading?>(null) }
    val rangeReading = (live ?: lastBmsReading)?.takeIf { it.soc?.let { value -> value in 0.0..100.0 } == true }
        ?: lastRangeReading
    SideEffect { if (rangeReading != null) lastRangeReading = rangeReading }
    val range = mapRange(rangeReading, lastKnownFix, historyForRange, now, reserveSoc)
        if (tab == 0) {
            var mapOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            val mapSelection = remember { MapSelection() }
            if (mapOpen) TripMapDialog(tripPoints, gps?.gaps.orEmpty(), isLiveTrip, range, mapSelection) { mapOpen = false }
            OutlinedButton(onClick = { mapOpen = true }, modifier = Modifier.fillMaxWidth()) {
                LIcon(Icons.Default.Route, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                LText("Otvori mapu vožnje", color = cyan)
            }
            CardBox("Vožnja • merenje uživo") {
                val gpsLabel = when (gpsStatus) {
                    GpsTrackerStatus.STOPPED -> if (gpsPaused) "GPS ručno zaustavljen" else if (live == null) "GPS miruje • čekam vozilo" else "GPS miruje • čekam vožnju"
                    GpsTrackerStatus.NO_PERMISSION -> "Potrebna je precizna lokacija"
                    GpsTrackerStatus.GPS_UNAVAILABLE -> "Lokacija telefona je isključena"
                    GpsTrackerStatus.WAITING_FIX -> "Tražim GPS signal"
                    GpsTrackerStatus.READY -> "GPS spreman • čekam kretanje"
                    GpsTrackerStatus.TRACKING -> if (freshPoint != null) "Putanja se snima" else "Čekam svežu GPS tačku"
                    GpsTrackerStatus.POOR_ACCURACY -> "GPS signal nije dovoljno precizan"
                    GpsTrackerStatus.STORAGE_ERROR -> "GPS tačka nije sačuvana • proverite memoriju"
                    GpsTrackerStatus.FOREGROUND_REQUIRED -> "Potrebno je ponovo aktivirati GPS"
                }
                val gpsColor = if (gpsStatus == GpsTrackerStatus.TRACKING && freshPoint != null) green
                    else if (gpsStatus in listOf(GpsTrackerStatus.READY, GpsTrackerStatus.STOPPED)) cyan else Color(0xFFFFC66D)
                Row(Modifier.fillMaxWidth().background(gpsColor.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LIcon(Icons.Default.MyLocation, null, tint = gpsColor, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        LText(gpsLabel, color = gpsColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        LText(freshPoint?.let { "Tačnost ±${fmt(it.accuracyMeters?.toDouble(), 0)} m • pre ${(now - it.timeMillis).coerceAtLeast(0) / 1000} s" }
                            ?: "Lokacija se čuva samo uz prepoznatu vožnju.", color = muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val vehicleSpeed = live?.speedKmh?.takeIf { live.speedAt?.let { now - it in 0..15_000 } == true }
                Metrics("Brzina vozila" to fmt(vehicleSpeed, 0, " km/h"), "GPS brzina" to fmt(freshPoint?.speedMetersPerSecond?.toDouble()?.times(3.6), 0, " km/h"),
                    "Nadmorska visina" to fmt(freshPoint?.altitudeMeters, 0, " m"), "Tačke ove vožnje" to if (active != null) "${gpsPoints.size}" else "—")
                if (gpsStatus in listOf(GpsTrackerStatus.NO_PERMISSION, GpsTrackerStatus.GPS_UNAVAILABLE, GpsTrackerStatus.FOREGROUND_REQUIRED)) {
                    Spacer(Modifier.height(8.dp)); Action("PROVERI GPS I DOZVOLU", onGpsRepair)
                }
                if (gpsStatus in listOf(GpsTrackerStatus.WAITING_FIX, GpsTrackerStatus.POOR_ACCURACY))
                    LText("Sačekajte signal na otvorenom. Slabe i stare lokacije se ne upisuju u putanju.", color = muted, fontSize = 12.sp)
                if (live == null) LText("Povežite vozilo da bi se GPS automatski pripremio.", color = muted, fontSize = 12.sp)
                if (gpsPaused) { Spacer(Modifier.height(8.dp)); Action("NASTAVI AUTOMATSKO SNIMANJE") { ObdBus.resumeTrip(context) } }
                if (active != null) { Spacer(Modifier.height(8.dp)); Action("ZAVRŠI VOŽNJU I GPS", onFinish) }
            }
            CardBox(if (isLiveTrip) "Putanja • uživo" else "Putanja • istorija") {
                var tripMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { tripMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        LIcon(Icons.Default.Route, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        LText(selected?.let { "${date(it.startedAt)}${if (isLiveTrip) " • u toku" else ""}" } ?: "Nema zabeležene vožnje", modifier = Modifier.weight(1f))
                        LIcon(Icons.Default.ExpandMore, "Izaberi vožnju")
                    }
                    DropdownMenu(expanded = tripMenu, onDismissRequest = { tripMenu = false }) {
                        (listOfNotNull(active) + trips.takeLast(20).asReversed()).forEach { item ->
                            DropdownMenuItem(text = { LText("${date(item.startedAt)} • ${if (item.id == active?.id) "u toku" else fmt(item.distanceKm, 1, " km")}") },
                                onClick = { selectedId = item.id; tripMenu = false })
                        }
                    }
                }
                if (routeReadError) LText("Čitanje sačuvane putanje nije uspelo. Pokušajte ponovo.", color = red)
                if (!mapOpen) TripMap(tripPoints, gps?.gaps.orEmpty(), isLive = isLiveTrip, range = range, selection = mapSelection)
                if (selected != null) Metrics("Kilometraža vozila" to fmt(selected.distanceKm, 1, " km"), "GPS pokrivenost" to fmt(gps?.coveragePercent, 0, " %"), "GPS snimljeno" to fmt(route?.distanceKm, 1, " km"), "Vreme vožnje" to "${(intervalEnd - selected.startedAt).coerceAtLeast(0) / 60_000} min")
                if (gps != null && gps.pointCount == 0) LText("Za ovu vožnju nema sačuvanih GPS tačaka.", color = Color(0xFFFFC66D), fontSize = 12.sp)
                else if (gps != null && !gps.isComplete) LText("Putanja ima ${gps.gaps.size} prekida; GPS dužina nije ukupna dužina vožnje.", color = Color(0xFFFFC66D), fontSize = 12.sp)
            }
            if (live != null) CardBox("Vozilo • uživo") { Metrics("SOC" to fmt(live.soc, 1, " %"), "Snaga baterije" to fmt(live.powerKw, 1, " kW"), "Kilometraža" to fmt(live.odometerKm, 1, " km"), "BMS energija" to fmt(live.energy, 1, " kWh")) }
            CardBox("Spoljašnji uslovi") {
                val relative = if (weather != null && tripPoints.size >= 2)
                    relativeWindKmh(weather!!, tripPoints[tripPoints.lastIndex - 1], tripPoints.last()) else null
                Metrics("Temperatura vozila" to fmt(live?.outsideTemp, 1, " °C"), "Model vremena" to fmt(weather?.airTempC, 1, " °C"), "Padavine" to fmt(weather?.precipitationMm, 1, " mm"), "Sneg" to fmt(weather?.snowfallCm, 1, " cm"),
                    "Vetar" to fmt(weather?.windSpeedKmh, 0, " km/h"), "Odakle duva" to "${windCompass(weather?.windFromDegrees)} / ${fmt(weather?.windFromDegrees, 0, "°")}")
                if (relative != null) LText("${if (relative >= 0) "Čeoni" else "Leđni"} vetar ≈ ${fmt(kotlin.math.abs(relative), 0, " km/h")} na poslednjem delu putanje", color = cyan, fontSize = 12.sp)
                LText("Vreme: Open-Meteo model na lokaciji tokom vožnje ${weather?.let { "(${date(it.fetchedAt)})" } ?: ""} • CC BY 4.0. Vetar je prognoza, ne senzor vozila.", color = muted, fontSize = 11.sp)
            }
        } else if (tab == 1) {
            CardBox("Potrošnja iz BMS • izabrana vožnja") {
                if (selected == null) LText("Čekam automatski zabeleženu vožnju.", color = muted, fontSize = 12.sp)
                else Metrics("BMS rastojanje" to fmt(selected.distanceKm, 1, " km"), "Neto energija" to fmt(selected.consumptionEnergyKwh, 2, " kWh"), "Neto potrošnja" to fmt(selected.consumptionKwhPer100Km, 1, " kWh/100 km"), "Pokrivenost snage" to fmt(selected.powerCoveragePercent, 0, " %"))
                if (selected != null) {
                    LText("Izvor: ${selected.consumptionSource}. Pozitivna snaga prazni bateriju; negativna vraća energiju.", color = muted, fontSize = 11.sp)
                    LText("Promena preostale BMS energije: ${fmt(selected.bmsRemainingEnergyChangeKwh, 2, " kWh")} (zaseban pokazatelj)", color = muted, fontSize = 11.sp)
                }
            }
            CardBox("Brzina ↔ potrošnja u kretanju") {
                TripEfficiencyComparison(selectedSamples, selected)
            }
            CardBox("Uporedi snimljene vožnje") {
                TripHistoryComparison(trips, selected?.id) { selectedId = it }
            }
            CardBox("Dnevno i dugoročno") {
                val today = java.time.LocalDate.now().toString()
                val todayTrips = trips.filter { TripMonitor.dayOf(it) == today }
                val monthTrips = trips.filter { TripMonitor.monthOf(it) == today.take(7) }
                Metrics("Danas vožnji" to "${todayTrips.size}", "Danas km" to fmt(todayTrips.mapNotNull { it.distanceKm }.sum(), 1, " km"), "Ovog meseca vožnji" to "${monthTrips.size}", "Ovog meseca km" to fmt(monthTrips.mapNotNull { it.distanceKm }.sum(), 1, " km"), "Ukupno vožnji" to "${trips.size}", "Ukupno km" to fmt(trips.mapNotNull { it.distanceKm }.sum(), 1, " km"))
            }
            if (untracked.isNotEmpty()) CardBox("Kilometraža bez snimljene vožnje") {
                untracked.takeLast(10).asReversed().forEach { gap -> LText("${date(gap.beforeAt)} → ${date(gap.afterAt)} • ${fmt(gap.distanceKm, 0, " km")} bez telemetrije. Brzina i trasa nisu poznate.", color = Color(0xFFFFC66D), fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp)) }
            }
            CardBox("Dodatni signali vožnje") {
                LText(if (showDetailedDriveSignals) "Sakrij izbor signala" else "Prikaži sve BMS i GPS signale",
                    color = cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { showDetailedDriveSignals = !showDetailedDriveSignals }.padding(vertical = 8.dp))
                if (showDetailedDriveSignals) OverlayTelemetryChart(selectedSamples, tripPoints)
            }
        } else {
            CardBox("Rekuperacija") {
                LText("Trenutna snaga baterije: ${fmt(live?.powerKw, 1, " kW")}", color = Color.White)
                if (selected != null) Metrics("Vraćeno" to fmt(selected.recuperatedKwh, 2, " kWh"), "Bruto potrošeno" to fmt(selected.grossDischargeKwh, 2, " kWh"), "Neto V×I" to fmt(selected.integratedNetKwh, 2, " kWh"), "Maks. brzina" to fmt(selected.maxSpeedKmh, 0, " km/h"))
                HelpCaption("Kako se računa rekuperacija", "Energija se dobija integracijom snage baterije V × I kroz vreme. Negativna snaga tokom vožnje predstavlja rekuperaciju. Obračun obuhvata intervale do 60 s između očitavanja. Duže praznine imaju nepoznatu energiju.")
            }
        }
}
@Composable private fun Charge(tab: Int, demo: Boolean, live: BmsReading?, samples: List<BmsReading>, chartSamples: List<BmsReading>, trips: List<TripRecord>, onSave: (String) -> Unit, onExport: () -> Unit) {
    val charge = chargeFromSamples(samples)
    val activeCharge by ChargeMonitor.active.collectAsState()
    val chargeSessions by ChargeMonitor.history.collectAsState()
    val chargeGaps by ChargeGapMonitor.history.collectAsState()
    when (tab) {
        0 -> {
            CardBox("Punjenje • BMS") {
            if (live == null) LText("BMS može prestati da odgovara tokom punjenja. SOC pre i posle će se uporediti kada se vozilo ponovo poveže.", color = muted)
            else {
                LText(if (charge != null) "Porast BMS procene preostale energije je zabeležen" else "Čekam nova BMS očitavanja", color = if (charge != null) green else muted)
                Metrics("SOC" to fmt(live.soc, 1, " %"), "Snaga baterije" to fmt(live.powerKw, 1, " kW"), "BMS energija" to fmt(live.energy, 1, " kWh"), "Napon baterije" to fmt(live.voltage, 1, " V"), "Struja baterije" to fmt(live.current, 1, " A"))
                if (charge != null) Metrics("Promena SOC" to fmt(charge.socChange, 1, " %"), "Promena D865 procene" to fmt(charge.energyKwh, 2, " kWh"))
                activeCharge?.let { Metrics("Izmereno V×I" to fmt(it.batteryEnergyAddedKwh, 2, " kWh"), "Pokrivenost" to fmt(it.powerCoveragePercent, 0, " %")) }
                HelpCaption("Osnova merenja punjenja", "BMS procenjuje preostalu energiju u bateriji. Izmereno V × I predstavlja energiju tokom intervala sa dostupnim naponom i strujom. Negativna snaga označava punjenje. Energiju preuzetu iz mreže možete upisati sa brojila punjača.")
            }
            }
            CardBox { ChargePlanner(live, samples, trips) }
            ChargerMeterForm(activeCharge, chargeSessions, chargeGaps)
        }
        1 -> CardBox("Istorija punjenja") {
            LText("Automatske sesije iz stvarnih BMS očitavanja.", color = muted, fontSize = 12.sp)
            val today = java.time.LocalDate.now().toString()
            val day = { c: ChargeSession -> java.time.Instant.ofEpochMilli(c.startedAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }
            ChargeCountsCard()
            Metrics("Danas snimljeno" to "${chargeSessions.count { day(it) == today }}", "Snimljene sesije" to "${chargeSessions.size}", "Izmereno V×I" to fmt(chargeSessions.sumOf { it.batteryEnergyAddedKwh }, 1, " kWh"))
            HelpCaption("Pokrivenost istorije punjenja", "Zbir izmerene energije obuhvata vreme sa dostupnim BMS podacima. Pri prekidima predstavlja donju granicu energije koja je ušla u bateriju. Porast SOC pre i posle prekida čuva se posebno kao procenjen događaj.")
            activeCharge?.let { LText("U toku: ${fmt(it.socGainedPercent, 1)}% SOC • ${fmt(it.batteryEnergyAddedKwh, 2)} kWh", color = green) }
            chargeSessions.takeLast(10).asReversed().forEach { c -> LText("${date(c.startedAtMillis)} • ${fmt(c.socGainedPercent, 1)}% • ${fmt(c.batteryEnergyAddedKwh, 2)} kWh • ${c.durationMillis / 60_000} min", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            chargeGaps.takeLast(10).asReversed().forEach { g -> LText("${date(g.beforeAt)} → ${date(g.afterAt)} • SOC +${fmt(g.socGain, 1)}% • BMS nije dostupan tokom intervala${g.untrackedDistanceKm?.let { " • ${fmt(it, 0, " km")} bez zapisa vožnje" } ?: ""}", color = cyan, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            Spacer(Modifier.height(8.dp)); Action("Izvezi merenja za analizu", onExport)
        }
        else -> {
            CardBox("Preklopi signale") { OverlayTelemetryChart(chartSamples) }
            CardBox("SOC") { TelemetryChart("Stanje napunjenosti", chartSamples, "%", green) { it.soc } }
            CardBox("Energija") { TelemetryChart("Procena preostale energije (D865)", chartSamples, "kWh", cyan) { it.energy } }
            CardBox("Snaga") { TelemetryChart("Snaga baterije", chartSamples, "kW", Color(0xFFFFB957)) { it.powerKw } }
            CardBox("Temperatura") { TelemetryChart("Prosečna temperatura baterije", chartSamples, "°C", red) { it.tempAvg } }
        }
    }
    if (tab == 1) ChargerMeterForm(activeCharge, chargeSessions, chargeGaps)
}
@Composable private fun ChargeCountsCard() {
    val sessions by ChargeMonitor.history.collectAsState()
    val active by ChargeMonitor.active.collectAsState()
    val gaps by ChargeGapMonitor.history.collectAsState()
    val counts = remember(sessions, active, gaps) { countChargeEvents(sessions + listOfNotNull(active), gaps) }
    CardBox("Broj punjenja") {
        Metrics("Obično · AC" to counts.acSessions.toString(), "Brzo · DC" to counts.dcSessions.toString())
        MetricLine("Vrsta nepoznata", counts.unknownSessions.toString())
        MetricLine("Snimljene sesije", counts.observedSessions.toString())
        MetricLine("Dodatni porasti SOC u prekidu", (counts.inferredGaps - counts.gapsOverlappingObserved).toString())
        MetricLine("Evidentirano i procenjeno", counts.minimumDistinctEvents.toString())
        LText("U sačuvanoj istoriji aplikacije", color = muted, fontSize = 11.sp)
    }
}

@Composable private fun ChargerMeterForm(active: ChargeSession?, history: List<ChargeSession>, gaps: List<ChargeGapRecord>) {
    val context = LocalContext.current
    val meterEntries by ChargerMeterStore.entries.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val allSessions = listOfNotNull(active) + history.asReversed()
    val newestSession = active ?: history.lastOrNull()
    val newestGap = gaps.lastOrNull()
    val defaultId = if (newestGap != null && (newestSession == null || newestGap.afterAt > newestSession.startedAtMillis)) newestGap.id else newestSession?.id
    val targetId = selectedId ?: defaultId
    val session = allSessions.firstOrNull { it.id == targetId }
    val gap = gaps.firstOrNull { it.id == targetId }
    var input by remember(targetId) { mutableStateOf(meterEntries.lastOrNull { it.chargeId == targetId }?.deliveredKwh?.toString() ?: "") }
    var error by remember { mutableStateOf("") }
    CardBox("Energija koju pokazuje punjač") {
        LText(when {
            gap != null -> "Pre/posle BMS: ${date(gap.beforeAt)} → ${date(gap.afterAt)} • SOC ${fmt(gap.socBefore, 1)} → ${fmt(gap.socAfter, 1)} %"
            session != null -> "Sesija ${date(session.startedAtMillis)} • SOC ${fmt(session.firstSocPercent, 1)} → ${fmt(session.lastSocPercent, 1)} %"
            else -> "Nema automatske sesije; unos će biti samostalan."
        }, color = muted, fontSize = 11.sp)
        val choices = (listOfNotNull(active) + history.takeLast(4).asReversed()).map { it.id to "${date(it.startedAtMillis)} • BMS tokom punjenja" } +
            gaps.takeLast(5).asReversed().map { it.id to "${date(it.beforeAt)} • BMS pre/posle" }
        if (choices.size > 1) choices.forEach { (id, label) ->
            LText(label, color = if (id == targetId) cyan else muted,
                fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { selectedId = id }.padding(vertical = 4.dp))
        }
        OutlinedTextField(input, onValueChange = { input = it; error = "" }, label = { LText("Energija na punjaču (kWh)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error.isNotEmpty()) LText(error, color = if (error == "Sačuvano.") green else red, fontSize = 11.sp)
        Spacer(Modifier.height(7.dp))
        Action("Sačuvaj očitavanje punjača") {
            val kwh = input.replace(',', '.').toDoubleOrNull()
            if (kwh == null || kwh !in 0.01..500.0) error = "Unesite broj u kWh sa ekrana punjača."
            else { ChargerMeterStore.record(context, targetId, kwh); error = "Sačuvano." }
        }
        val entry = meterEntries.lastOrNull { it.chargeId == targetId }
        if (entry != null) {
            LText("Punjač: ${fmt(entry.deliveredKwh, 2, " kWh")}", color = cyan, fontSize = 13.sp)
            if (gap != null) LText("SOC +${fmt(gap.socGain, 1)}% • promena procene preostale energije ${fmt(gap.remainingEstimateChangeKwh, 2, " kWh")}. BMS nije merio punjenje između dva očitavanja.${gap.untrackedDistanceKm?.let { " U istom intervalu ${fmt(it, 0, " km")} bez zapisa vožnje." } ?: ""}", color = muted, fontSize = 11.sp)
            if (session != null) {
                LText("Baterija V×I: ${fmt(session.batteryEnergyAddedKwh, 2, " kWh")} • pokrivenost ${fmt(session.powerCoveragePercent, 0, " %")}", color = muted, fontSize = 12.sp)
                if (session.powerCoveragePercent >= 90 && session.batteryEnergyAddedKwh > 0 && entry.deliveredKwh >= session.batteryEnergyAddedKwh) {
                    LText("Razlika: ${fmt(entry.deliveredKwh - session.batteryEnergyAddedKwh, 2, " kWh")} • efikasnost ≈ ${fmt(session.batteryEnergyAddedKwh / entry.deliveredKwh * 100, 0, " %")}", color = green, fontSize = 12.sp)
                } else LText("Za pouzdano poređenje potrebna je ≥90% pokrivenost BMS očitavanjima tokom celog punjenja.", color = muted, fontSize = 11.sp)
            }
        }
        LText("Unos je sa displeja punjača; BMS ga ne očitava automatski.", color = muted, fontSize = 11.sp)
    }
}
@Composable private fun More(tab: Int, demo: Boolean, live: BmsReading?, samples: List<BmsReading>, chartSamples: List<BmsReading>, records: List<JSONObject>, onDemo: (Boolean)->Unit, onConnect:()->Unit, onStart:()->Unit, onStop:()->Unit, onGpsPermission:()->Unit, onExport:()->Unit, onReading:(Double,Double,Double,Double)->Unit, onClear: suspend () -> Unit) {
    val gpsStatus by GpsTracker.status.collectAsState()
    val gpsPoints by GpsTracker.points.collectAsState()
    if (!demo && tab == 1) {
        val batteryDays by BatteryHistory.days.collectAsState()
        CardBox("Automatska analiza baterije") {
            if (live == null) LText("Čekam BMS očitavanja. Povežite adapter sa vozilom.", color = muted)
            else {
                Metrics("SOC" to fmt(live.soc, 1, " %"), "SOH (BMS)" to fmt(live.soh, 1, " %"), "BMS energija" to fmt(live.energy, 1, " kWh"), "Napon" to fmt(live.voltage, 1, " V"), "Najniža temp." to fmt(live.tempMin, 0, " °C"), "Najviša temp." to fmt(live.tempMax, 0, " °C"))
                val capacity = capacityFromSamples(samples)
                if (capacity != null) LText("Procenjeni kapacitet: ${fmt(capacity.kwh, 1, " kWh")}", color = green, fontWeight = FontWeight.Bold)
                else LText("Za procenu kapaciteta potrebno je punjenje sa promenom SOC od bar 10% i stabilnom temperaturom.", color = muted, fontSize = 11.sp)
                val charge = chargeFromSamples(samples)
                if (capacity == null) {
                    Spacer(Modifier.height(8.dp))
                    LText("Automatski test kapaciteta • ${fmt(charge?.socChange, 1, " / 10 % SOC")}", color = cyan, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { ((charge?.socChange ?: 0.0) / 10.0).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), color = cyan)
                    LText("Za pouzdaniji test zabeležite jedno neprekinuto punjenje sa većom promenom SOC, po mogućstvu od oko 20% do 80%. Aplikacija sama računa rezultat kada ima dovoljno podataka.", color = muted, fontSize = 11.sp)
                }
                Metrics("Razlika ćelija" to if (live.cellMin != null && live.cellMax != null) fmt((live.cellMax - live.cellMin) * 1000, 0, " mV") else "—", "Temperaturni raspon" to if (live.tempMin != null && live.tempMax != null) fmt(live.tempMax - live.tempMin, 1, " °C") else "—")
                if (live.measurementWarnings.isNotEmpty()) LText(live.measurementWarnings.joinToString(" • "), color = Color(0xFFFFCA50), fontSize = 11.sp)
                LText("ΔBMS energija / ΔSOC × 100 • ${samples.size} automatskih uzoraka. Procena iz energije u bateriji, bez gubitaka punjača.", color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                SampleChart(chartSamples)
            }
        }
        CardBox("Dugoročno zdravlje baterije") {
            if (batteryDays.isEmpty()) LText("Dnevni BMS sažeci počinju od sledećeg povezivanja.", color = muted, fontSize = 12.sp)
            else batteryDays.takeLast(15).asReversed().forEach { day ->
                LText("${day.day} • SOH ${fmt(day.soh, 1, " %")} • Δćelija ${fmt(day.cellSpreadMv, 0, " mV")} • T ${fmt(day.meanTemp, 1, " °C")}", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 5.dp))
            }
            LText("Jedno sveže merenje po danu; SOH je BMS procena, a razlika ćelija zavisi od opterećenja i SOC.", color = muted, fontSize = 11.sp)
        }
        return
    }
    when (tab) {
        0 -> CardBox("Dijagnostika") {
            LText("Bez potvrđenih ECU očitavanja", color=muted)
            Spacer(Modifier.height(8.dp))
            LText("DTC čitanje i brisanje zahtevaju potvrđen protokol vozila.", color=muted)
            Spacer(Modifier.height(10.dp))
            Action("Proveri Bluetooth adapter", onConnect)
        }
        1 -> {
            ReadingForm(onReading)
            val readings = records.filter { it.optString("type") == "Merenje" }.mapNotNull { item ->
                val values = item.optString("detail").split("|").mapNotNull { it.toDoubleOrNull() }
                if (values.size == 4) values else null
            }
            CardBox("Analiza baterije") {
                LText("Iz stvarno unetih merenja • ${VehicleProfiles.current(LocalContext.current).seriesGroups} serijskih grupa", color=muted, fontSize=12.sp)
                if (readings.isNotEmpty()) {
                    val current=readings.first()
                    Metrics("SOC" to "${current[0]} %", "Napon grupe" to "${"%.3f".format(Locale.US,current[2]/VehicleProfiles.current(LocalContext.current).seriesGroups)} V", "BMS energija" to "${current[1]} kWh", "Temperatura" to "${current[3]} °C")
                }
                if (readings.size >= 2) {
                    val a=readings[0]; val b=readings[1]
                    val deltaSoc=kotlin.math.abs(a[0]-b[0]); val deltaEnergy=kotlin.math.abs(a[1]-b[1])
                    val sameDirection = (a[0]-b[0])*(a[1]-b[1]) > 0
                    if (deltaSoc >= 10 && deltaEnergy > 0 && sameDirection) {
                        val capacity=deltaEnergy*100/deltaSoc
                        LText("Kapacitet iz dva merenja: ${"%.1f".format(Locale.US,capacity)} kWh",color=green,fontWeight=FontWeight.Bold)
                        LText("Δenergija / ΔSOC × 100. Gruba procena; validna samo za isti ciklus, uz pouzdan BMS Energy PID.",color=muted,fontSize=11.sp)
                    } else LText("Za procenu kapaciteta potrebna su dva merenja istog ciklusa, sa promenom SOC od bar 10% i saglasnom promenom BMS energije.",color=muted,fontSize=12.sp)
                } else LText("Unesite bar dva merenja za proračun.",color=muted,fontSize=12.sp)
                Spacer(Modifier.height(8.dp)); Action("Izvezi CSV",onExport)
            }
        }
        else -> {
            CardBox("Podešavanja") {
                LanguageOptions()
                Spacer(Modifier.height(12.dp))
                LText("Bluetooth: Vgate iCar Pro 2S / Android-Vlink", color = Color.White)
                LText("Izaberite adapter, pa pritisnite Poveži. Posle prvog povezivanja veza se automatski obnavlja.", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp)); Action("Izaberi OBD adapter", onConnect)
                Spacer(Modifier.height(8.dp)); Action("Poveži i pokreni praćenje", onStart)
                Spacer(Modifier.height(8.dp)); Action("Dozvoli precizan GPS", onGpsPermission)
                Spacer(Modifier.height(8.dp)); Action("Prekini OBD vezu i GPS", onStop)
            }
            CardBox("Merenja za analizu") {
                Metrics("BMS sažeci" to "${samples.size}", "GPS tačke u memoriji" to "${gpsPoints.size}")
                LText("GPS: $gpsStatus. ZIP uključuje sirova BMS očitavanja i GPS CSV/GPX kada postoje.", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp)); Action("Izvezi merenja kao ZIP", onExport)
            }
            HistoryStoragePanel(onClear)
            CardBox("Test baterije") {
                LText("1. Sačekajte očitavanje ćelija dok auto miruje. 2. Zabeležite vožnju. 3. Napunite bateriju u jednom ciklusu uz promenu SOC od bar 10%. 4. Pošaljite ZIP za detaljnu analizu.", color = muted, fontSize = 12.sp)
            }
            TireProfile()
        }
    }
}
@Composable private fun HistoryStoragePanel(onClear: suspend () -> Unit) {
    val context = LocalContext.current
    var bytes by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { MeasurementStorage.byteCount(context) } }
    val size = bytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
    CardBox("Memorija merenja") {
        LText(size?.let { "Istorija i privremeni izvozi  ·  $it" } ?: "Računam veličinu…", color = muted)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirm = true }, enabled = !busy && (bytes ?: 0L) > 0,
            modifier = Modifier.fillMaxWidth()) {
            LIcon(Icons.Default.DeleteOutline, null, tint = red)
            Spacer(Modifier.width(8.dp))
            LText(if (busy) "Brišem istoriju…" else "Obriši istoriju merenja", color = red)
        }
        if (message.isNotEmpty()) LText(message, color = Color.White, fontSize = 12.sp)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { LText("Obrisati istoriju merenja?") },
        text = { LText("Praćenje će se zaustaviti. Brišu se snimljena očitavanja, vožnje, GPS putanje, punjenja, ručni zapisi i privremeni ZIP fajlovi aplikacije. Poslednje očitavanje za proračun i podešavanja ostaju sačuvani. Za kopiju prvo izvezi ZIP.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            busy = true
            scope.launch {
                try {
                    onClear()
                    message = "Istorija je obrisana."
                } catch (error: Exception) {
                    message = "Brisanje nije završeno. ${error.localizedMessage.orEmpty()}"
                } finally {
                    bytes = withContext(Dispatchers.IO) { MeasurementStorage.byteCount(context) }
                    busy = false
                }
            }
        }) { LText("Obriši istoriju", color = red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { LText("Odustani") } }
    )
}

@Composable private fun TireProfile() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("battery_lab", android.content.Context.MODE_PRIVATE) }
    var tire by remember { mutableStateOf(prefs.getString("tire_profile", "") ?: "") }
    CardBox("Gume • kontekst za analizu") {
        LText("Tip i dimenzija guma ne stižu preko potvrđenih OBD parametara. Unesite ih jednom; ažurirajte posle zamene.", color = muted, fontSize = 11.sp)
        OutlinedTextField(tire, onValueChange = { tire = it.take(100) }, label = { LText("npr. letnje 195/55 R16") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Action("Sačuvaj gume") {
            val saved = tire.trim()
            if (saved.isNotEmpty() && saved != prefs.getString("tire_profile", "")) {
                java.io.File(context.filesDir, "tire-history.jsonl").appendText(JSONObject()
                    .put("changed_at_ms", System.currentTimeMillis()).put("tire_profile", saved)
                    .put("odometer_km", ObdBus.reading.value?.odometerKm).toString() + "\n")
            }
            prefs.edit().putString("tire_profile", saved).apply()
        }
    }
}
@Composable private fun ReadingForm(onReading:(Double,Double,Double,Double)->Unit) {
    var soc by remember { mutableStateOf("") }; var energy by remember { mutableStateOf("") }
    var voltage by remember { mutableStateOf("") }; var temp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    CardBox("Novo BMS merenje") {
        LText("Unesite vrednosti iz Pelican/OBD aplikacije.",color=muted,fontSize=12.sp)
        OutlinedTextField(soc,{soc=it},label={LText("SOC (%)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(energy,{energy=it},label={LText("BMS energija (kWh)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(voltage,{voltage=it},label={LText("Napon paketa (V)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(temp,{temp=it},label={LText("Temperatura baterije (°C)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        if(error.isNotBlank()) LText(error,color=red,fontSize=12.sp)
        Spacer(Modifier.height(8.dp))
        Action("Sačuvaj merenje") {
            val values=listOf(soc,energy,voltage,temp).map { it.replace(',','.').toDoubleOrNull() }
            if(values.any { it==null } || (values[0] ?: -1.0) !in 0.0..100.0 || (values[1] ?: -1.0) !in 0.0..100.0 || (values[2] ?: -1.0) !in 250.0..500.0 || (values[3] ?: -100.0) !in -40.0..100.0) error="Proverite brojeve i jedinice."
            else { onReading(values[0]!!,values[1]!!,values[2]!!,values[3]!!); soc=""; energy=""; voltage=""; temp=""; error="" }
        }
    }
}
@Composable private fun ManualEventForm(type: String, onSave: (String)->Unit) {
    var first by remember { mutableStateOf("") }; var second by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    CardBox("Ručni zapis: $type") {
        val firstLabel=if(type=="Vožnja") "Pređeno (km)" else "Primljena energija (kWh)"
        val secondLabel=if(type=="Vožnja") "Potrošeno (kWh)" else "SOC porast (%)"
        OutlinedTextField(first,{first=it},label={LText(firstLabel)},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(second,{second=it},label={LText(secondLabel)},modifier=Modifier.fillMaxWidth(),singleLine=true)
        if(error.isNotBlank()) LText(error,color=red,fontSize=12.sp)
        Spacer(Modifier.height(8.dp))
        Action("Sačuvaj $type") {
            val a=first.replace(',','.').toDoubleOrNull(); val b=second.replace(',','.').toDoubleOrNull()
            if(a==null || b==null || a<=0 || b<=0 || (type=="Punjenje" && b>100)) error="Unesite pozitivne brojeve u navedenim jedinicama."
            else { val detail=if(type=="Vožnja") "${"%.1f".format(Locale.US,a)} km • ${"%.2f".format(Locale.US,b)} kWh • ${"%.1f".format(Locale.US,b/a*100)} kWh/100 km" else "${"%.2f".format(Locale.US,a)} kWh • SOC +${"%.1f".format(Locale.US,b)}%"; onSave(detail); first=""; second=""; error="" }
        }
    }
}
@Composable private fun RecordCard(item: JSONObject) { CardBox { LText(item.optString("type"),color=cyan,fontWeight=FontWeight.Bold); LText(date(item.optLong("time")),color=muted,fontSize=11.sp); val detail=item.optString("detail"); LText(if(item.optString("type")=="Merenje") detail.split("|").let { if(it.size==4) "SOC ${it[0]}% • ${it[1]} kWh • ${it[2]} V • ${it[3]} °C" else detail } else detail,color=Color.White,fontSize=13.sp) } }



