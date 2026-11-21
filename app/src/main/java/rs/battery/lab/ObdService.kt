package rs.battery.lab

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** In-process stream consumed by the UI; saved samples survive process death in SharedPreferences. */
internal object ObdBus {
    val status = MutableStateFlow("Praćenje nije pokrenuto")
    val reading = MutableStateFlow<BmsReading?>(null)
    val samples = MutableStateFlow<List<BmsReading>>(emptyList())
    val confirmedAt = MutableStateFlow(0L)
    @Volatile var serviceRunning = false
    val gpsPaused = MutableStateFlow(false)

    fun resumeTrip(context: Context) {
        if (serviceRunning) context.startService(Intent(context, ObdService::class.java).setAction(ObdService.ACTION_RESUME_TRIP))
    }

    fun refreshGps(context: Context) {
        if (serviceRunning) context.startService(Intent(context, ObdService::class.java).setAction(ObdService.ACTION_REFRESH_GPS))
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java).setAction(ObdService.ACTION_START))
    }

    fun stop(context: Context) {
        val service = Intent(context, ObdService::class.java)
        if (serviceRunning) {
            context.startService(service.setAction(ObdService.ACTION_STOP))
        } else {
            context.stopService(service)
            status.value = "Praćenje je zaustavljeno"
        }
    }

    fun endTrip(context: Context) {
        context.startService(Intent(context, ObdService::class.java).setAction(ObdService.ACTION_END_TRIP))
    }

    fun setRecording(context: Context, enabled: Boolean) {
        if (serviceRunning) context.startService(Intent(context, ObdService::class.java)
            .setAction(ObdService.ACTION_SET_RECORDING).putExtra(ObdService.EXTRA_RECORDING_ENABLED, enabled))
        else RecordingControl.set(context, enabled)
    }
}

/** Owns the Classic Bluetooth connection while the app is in the background. */
internal class ObdService : Service() {
    companion object {
        const val ACTION_START = "rs.battery.lab.START_OBD"
        const val ACTION_STOP = "rs.battery.lab.STOP_OBD"
        const val ACTION_END_TRIP = "rs.battery.lab.END_TRIP"
        const val ACTION_REFRESH_GPS = "rs.battery.lab.REFRESH_GPS"
        const val ACTION_RESUME_TRIP = "rs.battery.lab.RESUME_TRIP"
        const val ACTION_SET_RECORDING = "rs.battery.lab.SET_RECORDING"
        const val EXTRA_RECORDING_ENABLED = "recording_enabled"
        private const val CHANNEL_ID = "obd_tracking"
        private const val NOTIFICATION_ID = 208
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val prefs by lazy { getSharedPreferences("battery_lab", MODE_PRIVATE) }
    private var lastNotificationAt = 0L
    private var stopRequested = false
    private var phase = "Bluetooth"
    private val gpsLock = Any()
    private var lastVehicleReply = 0L
    private var prepareGpsUntil = 0L
    private var warmupUsed = false
    private var manuallyEnded = false
    @Volatile private var locationForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(VehicleDataContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        ObdBus.serviceRunning = true
        RecordingControl.load(this)
        LastReadingStore.load(this)
        manuallyEnded = prefs.getBoolean("gps_manually_paused", false)
        ObdBus.gpsPaused.value = manuallyEnded
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "OBD praćenje", NotificationManager.IMPORTANCE_LOW))
        ObdBus.samples.value = loadSamples()
        TripMonitor.load(this)
        ChargeMonitor.load(this)
        ChargeGapMonitor.load(this)
        BatteryHistory.load(this)
        scope.launch {
            while (isActive) {
                delay(5_000)
                synchronized(gpsLock) {
                    if (lastVehicleReply > 0 && SystemClock.elapsedRealtime() - lastVehicleReply > 120_000) {
                        ObdBus.reading.value = null
                        closeTrip("vozilo ne odgovara 120 s")
                    } else updateGpsLocked()
                    GpsTracker.checkSignal()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_END_TRIP) {
            synchronized(gpsLock) {
                manuallyEnded = true
                ObdBus.gpsPaused.value = true
                prefs.edit().putBoolean("gps_manually_paused", true).apply()
                closeTrip("ručno završeno")
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_SET_RECORDING) {
            val on = intent.getBooleanExtra(EXTRA_RECORDING_ENABLED, true)
            synchronized(gpsLock) {
                if (RecordingControl.set(this, on)) {
                    if (!on) {
                        TripMonitor.finish(this, "snimanje isključeno")?.let { GpsTracker.stopTrip(this, it.id, it.endedAt) }
                        ChargeMonitor.stop(this)
                        GpsTracker.stop()
                        prepareGpsUntil = 0L
                    } else if (lastVehicleReply > 0 && !manuallyEnded) {
                        prepareGpsUntil = Long.MAX_VALUE
                    }
                    updateGpsLocked()
                }
            }
            return START_STICKY
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ObdBus.status.value = "Odobrite Bluetooth dozvolu u aplikaciji"
            stopSelf()
            return START_NOT_STICKY
        }
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationForeground = hasLocation
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            try { startForeground(NOTIFICATION_ID, notification("Povezivanje sa vozilom"), type) }
            catch (_: SecurityException) {
                locationForeground = false
                startForeground(NOTIFICATION_ID, notification("Povezivanje sa vozilom"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                GpsTracker.report(this, if (hasLocation) GpsTrackerStatus.FOREGROUND_REQUIRED else GpsTrackerStatus.NO_PERMISSION,
                    "Android nije dozvolio lokacijski servis; otvorite aplikaciju i proverite GPS")
            }
        } else startForeground(NOTIFICATION_ID, notification("Povezivanje sa vozilom"))
        if (intent?.action == ACTION_RESUME_TRIP) synchronized(gpsLock) {
            manuallyEnded = false
            ObdBus.gpsPaused.value = false
            prefs.edit().putBoolean("gps_manually_paused", false).apply()
            if (lastVehicleReply > 0) prepareGpsUntil = Long.MAX_VALUE
            updateGpsLocked()
        }
        if (intent?.action == ACTION_REFRESH_GPS) synchronized(gpsLock) {
            if (lastVehicleReply > 0 && !manuallyEnded) prepareGpsUntil = Long.MAX_VALUE
            updateGpsLocked()
        }
        if (job?.isActive != true) job = scope.launch { track() }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        ObdBus.serviceRunning = false
        closeTrip("praćenje zaustavljeno")
        ChargeMonitor.stop(this)
        GpsTracker.stop()
        try { socket?.close() } catch (_: Exception) { }
        scope.coroutineContext[Job]?.cancel()
        ObdBus.reading.value = null
        ObdBus.confirmedAt.value = 0L
        if (stopRequested) ObdBus.status.value = "Praćenje je zaustavljeno"
        super.onDestroy()
    }

    private suspend fun track() {
        var retrySeconds = 10L
        while (scope.isActive) {
            try {
                val address = prefs.getString("address", null)
                if (address.isNullOrBlank()) {
                    publish("Izaberite upareni Vlink adapter u aplikaciji")
                    stopSelf()
                    return
                }
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) {
                    publish("Bluetooth je isključen; ponovni pokušaj")
                    delay(30_000)
                    continue
                }
                val connected = openSocket(adapter, address)
                phase = "ELM inicijalizacija"
                publish("Bluetooth povezan • inicijalizujem adapter")
                synchronized(gpsLock) { warmupUsed = false }
                val onVehicleReply: () -> Unit = { synchronized(gpsLock) {
                    lastVehicleReply = SystemClock.elapsedRealtime()
                    ObdBus.confirmedAt.value = System.currentTimeMillis()
                    if (!warmupUsed) {
                        warmupUsed = true
                        // Keep fixes ready until the first drive, only while the vehicle answers.
                        // A parking delay must not discard the start of the coming route.
                        if (!manuallyEnded) prepareGpsUntil = Long.MAX_VALUE
                    }
                    updateGpsLocked()
                } }
                val profile = VehicleProfiles.current(this)
                val reader: BatteryReader = if (profile.isZoe) ZoeObdReader(BluetoothElmLink(connected),
                    profile.protocol == VehicleProtocol.ZOE_PHASE2, onVehicleReply) else ObdReader(connected, onVehicleReply)
                reader.initialize()
                retrySeconds = 10L
                phase = "BMS očitavanje"
                publish("Adapter spreman • čekam odgovor vozila")
                while (scope.isActive) {
                    val value = reader.read()
                    if (!scope.isActive || stopRequested || !ObdBus.serviceRunning) return
                    ObdBus.reading.value = value
                    ObdBus.status.value = "Povezano • BMS očitan"
                    runCatching { LastReadingStore.save(this, value) }
                    synchronized(gpsLock) {
                        if (!scope.isActive || stopRequested || !ObdBus.serviceRunning) return
                        if (RecordingControl.enabled.value) {
                            runCatching { TelemetryStore.append(this, value) }
                            saveSample(value)
                            ChargeGapMonitor.onReading(this, value)
                            runCatching { BatteryHistory.record(this, value) }
                            ChargeMonitor.onReading(this, value)
                        if (manuallyEnded && value.speedAt?.let { value.timestamp - it in 0..15_000 } == true &&
                            value.speedKmh?.let { it in 0.0..<3.0 } == true) {
                            manuallyEnded = false
                            ObdBus.gpsPaused.value = false
                            prefs.edit().putBoolean("gps_manually_paused", false).apply()
                        }
                        if (!manuallyEnded) {
                            val trip = TripMonitor.onReading(this, value)
                            trip.ended?.let {
                                GpsTracker.stopTrip(this, it.id, it.endedAt)
                                GpsTracker.stop()
                                prepareGpsUntil = 0L
                            }
                            trip.started?.let { GpsTracker.startTrip(this, it.id, it.startedAt) }
                            // Rebind a restored trip after a process restart, too.
                            TripMonitor.active.value?.let { GpsTracker.startTrip(this, it.id, it.startedAt) }
                        }
                        }
                        updateGpsLocked()
                    }
                    if (RecordingControl.enabled.value && TripMonitor.active.value != null) GpsTracker.lastPoint.value?.let { point ->
                        if (value.timestamp - point.timeMillis in 0..60_000) scope.launch { WeatherTracker.sampleIfDue(this@ObdService, point) }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationAt >= 60_000) {
                        val soc = value.soc?.let { " • SOC ${"%.1f".format(it)}%" } ?: ""
                        updateNotification("BMS se očitava$soc")
                        lastNotificationAt = now
                    }
                    delay(5_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ObdBus.reading.value = null
                ObdBus.confirmedAt.value = 0L
                closeTrip("prekid OBD veze")
                ChargeMonitor.stop(this)
                val reason = failure.message?.take(100)?.ifBlank { null } ?: failure.javaClass.simpleName
                publish("$phase: $reason • ponovo za $retrySeconds s")
                delay(retrySeconds * 1000)
                retrySeconds = (retrySeconds * 2).coerceAtMost(300)
            } finally {
                try { socket?.close() } catch (_: Exception) { }
                socket = null
            }
        }
    }

    private fun closeTrip(reason: String) = synchronized(gpsLock) {
        TripMonitor.finish(this, reason)?.let { GpsTracker.stopTrip(this, it.id, it.endedAt) }
        GpsTracker.stop()
        prepareGpsUntil = 0L
        lastVehicleReply = 0L
    }

    /** Never acquire GPS on an adapter connection alone; require an answering vehicle. */
    private fun updateGpsLocked() {
        val now = SystemClock.elapsedRealtime()
        val wanted = RecordingControl.enabled.value && ObdBus.serviceRunning && scope.isActive && !stopRequested && !manuallyEnded && lastVehicleReply > 0 && now - lastVehicleReply <= 120_000 &&
            (TripMonitor.active.value != null || now < prepareGpsUntil)
        if (!wanted) {
            if (GpsTracker.isRunning) GpsTracker.stop()
            return
        }
        if (!locationForeground) {
            if (GpsTracker.isRunning) GpsTracker.stop()
            val permitted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            GpsTracker.report(this, if (permitted) GpsTrackerStatus.FOREGROUND_REQUIRED else GpsTrackerStatus.NO_PERMISSION,
                "Otvorite aplikaciju i proverite preciznu lokaciju")
            return
        }
        GpsTracker.start(this)
    }

    private suspend fun openSocket(adapter: BluetoothAdapter, address: String): BluetoothSocket {
        val device = adapter.getRemoteDevice(address)
        var lastError = "Nepoznata greška"
        for (secure in listOf(true, false)) {
            phase = if (secure) "Bluetooth sigurna veza" else "Bluetooth alternativna veza"
            publish(if (secure) "Povezujem Bluetooth (1/2)" else "Povezujem Bluetooth (2/2)")
            val candidate = if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
                else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            socket = candidate
            val expired = AtomicBoolean(false)
            val watchdog = scope.launch {
                delay(12_000)
                expired.set(true)
                runCatching { candidate.close() }
            }
            try {
                candidate.connect()
                return candidate
            } catch (failure: Exception) {
                lastError = if (expired.get()) "isteklo 12 s" else failure.message?.take(80) ?: failure.javaClass.simpleName
                runCatching { candidate.close() }
            } finally {
                watchdog.cancel()
            }
        }
        throw IOException("adapter se nije povezao ($lastError)")
    }

    private fun publish(text: String) {
        ObdBus.status.value = text
        updateNotification(text)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("${VehicleProfiles.current(this).name} · Battery Lab")
            .setContentText(UiStrings.text(text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun loadSamples(): List<BmsReading> = try {
        val saved = JSONArray(prefs.getString("samples", "[]"))
        (0 until saved.length()).mapNotNull { i ->
            val item = saved.optJSONObject(i) ?: return@mapNotNull null
            BmsReading(
                soc = item.optDouble("soc").takeUnless { it.isNaN() },
                soh = item.optDouble("soh").takeUnless { it.isNaN() },
                energy = item.optDouble("energy").takeUnless { it.isNaN() },
                voltage = item.optDouble("voltage").takeUnless { it.isNaN() },
                current = item.optDouble("current").takeUnless { it.isNaN() },
                odometerKm = item.optDouble("km").takeUnless { it.isNaN() },
                tempAvg = item.optDouble("temp").takeUnless { it.isNaN() },
                tempMin = item.optDouble("tempMin").takeUnless { it.isNaN() },
                tempMax = item.optDouble("tempMax").takeUnless { it.isNaN() },
                cellMin = item.optDouble("cellMin").takeUnless { it.isNaN() },
                cellMax = item.optDouble("cellMax").takeUnless { it.isNaN() },
                timestamp = item.optLong("time")
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun saveSample(value: BmsReading) {
        if (value.soc == null && value.energy == null && value.voltage == null) return
        val old = ObdBus.samples.value
        if (old.lastOrNull()?.let { value.timestamp - it.timestamp < 30_000 } == true) return
        val updated = (old + value).takeLast(2000)
        ObdBus.samples.value = updated
        val json = JSONArray()
        updated.forEach { item ->
            json.put(JSONObject()
                .put("soc", item.soc)
                .put("soh", item.soh)
                .put("energy", item.energy)
                .put("voltage", item.voltage)
                .put("current", item.current)
                .put("km", item.odometerKm)
                .put("temp", item.tempAvg)
                .put("tempMin", item.tempMin)
                .put("tempMax", item.tempMax)
                .put("cellMin", item.cellMin)
                .put("cellMax", item.cellMax)
                .put("time", item.timestamp))
        }
        prefs.edit().putString("samples", json.toString()).apply()
    }
}
