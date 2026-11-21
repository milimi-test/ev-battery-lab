package rs.battery.lab

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Dependency-free instrumented regression suite. It uses a private test directory and a temporary
 * GPS test provider, and never opens the app's actual GPS, trip, or charger files.
 *
 * Run on an emulator after granting mock-location appops to rs.battery.lab:
 *   adb shell pm grant rs.battery.lab android.permission.ACCESS_FINE_LOCATION
 *   adb shell appops set rs.battery.lab android:mock_location allow
 *   adb shell am instrument -w -r rs.battery.lab.test/rs.battery.lab.GpsRegressionInstrumentation
 */
class GpsRegressionInstrumentation : Instrumentation() {
    private val results = ArrayList<String>()
    private var failed = 0
    private var skipped = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val isolated = IsolatedContext(targetContext)
        var foreground: Activity? = null
        try {
            runCase("packaged offline atlas, satellite levels and elevation") { testOfflineAtlas() }
            runCase("offline contours, terrain agreement and coverage boundaries") { checkContours(isolated) }
            runCase("SOC by voltage, stored reading, dynamic 20% reserve and return radius") { testRangeAndVoltage(isolated) }
            runCase("adjustable reserve, both radii and persisted settings") { checkRangeReserve(isolated) }
            runCase("voltage SOC reference curves and persistent model choice") { checkVoltageModels(isolated) }
            runCase("offline route terrain, weather and energy") { checkRouteEnergy(targetContext) }
            runCase("AC/DC session detection, persistence and duplicate counting") { checkChargingCounters(isolated) }
            runCase("offline chargers, streets, charge stops and same-source voltage SOC") { checkNavigationCharging(isolated) }
            runCase("offline driving route from Kragujevac to Kraljevo") { testOfflineRoute() }
            runCase("TripMonitor motion classification") { testTripMonitor(isolated) }
            foreground = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("MainActivity foreground", 8_000) {
                foreground?.window?.decorView?.hasWindowFocus() == true
            }
            runCase("map redraws both range rings with changing live SOC") { testRangeDrawing(foreground!!) }
            val manager = targetContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = installTestProvider(manager)
            if (provider) {
                try {
                    runCase("GPS early buffer, quality, end, provider recovery") {
                        testGpsLifecycle(isolated, manager)
                    }
                    runCase("isolated ZIP contains GPS status, CSV, and trip GPX") {
                        testGpsZipExport(isolated)
                    }
                } finally {
                    GpsTracker.stop()
                    runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
                }
            } else {
                skipped++
                results += "SKIP GPS provider: grant android:mock_location to rs.battery.lab on emulator"
            }
            runCase("denied fine-location permission does not register GPS") {
                testDeniedPermission(isolated)
            }
            runCase("voltage calibration and coverage gaps") { testVoltageEstimate(isolated) }
            runCase("storage purge preserves snapshot, settings and unrelated files") { testStorage(isolated) }
        } catch (error: Throwable) {
            failed++
            results += "FAIL suite setup: ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            GpsTracker.stop()
            foreground?.let { activity -> runOnMainSync { activity.finish() } }
            isolated.cleanUp()
        }
        val summary = "GPS regression: ${if (failed == 0) "PASS" else "FAIL"}, failed=$failed, skipped=$skipped\n" +
            results.joinToString("\n") + "\n"
        val bundle = Bundle().apply { putString(REPORT_KEY_STREAMRESULT, summary) }
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, bundle)
    }

    private fun runCase(name: String, test: () -> Unit) {
        try {
            test()
            results += "PASS $name"
        } catch (error: Throwable) {
            failed++
            results += "FAIL $name: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun testOfflineAtlas() {
        val data = OfflineAtlas.vectors(targetContext)
        check(data.places.any { it.name == "Kragujevac" && it.lon in 20.8..21.1 && it.lat in 43.9..44.1 })
        check(data.places.any { it.tier == 4 })
        check(data.borders.any { it.kind == 0 })
        check(data.roads.any { it.kind == 0 && it.label.isNotBlank() })
        val sector = AtlasTile(20, 44, 0, 0, 0)
        val broad = checkNotNull(OfflineAtlas.read(targetContext, sector))
        val fine = checkNotNull(OfflineAtlas.read(targetContext, sector.copy(level = 4, x = 14, y = 15)))
        val relief = checkNotNull(OfflineAtlas.read(targetContext, sector.copy(level = -1)))
        check(broad.width == 256 && fine.width == 256 && relief.width >= 256)
        check(!broad.sameAs(fine))
    }

    private fun testRangeAndVoltage(context: Context) {
        val now = System.currentTimeMillis()
        val energy = RangeHistory(40.0, 20.0, 6)
        val fix = GpsPoint(now, 44.01, 20.91, null, null, 5f)
        val record = BmsReading(timestamp = now, soc = 60.0, cellAverageVoltage = 3.723,
            bmsExtraAt = now, current = -23.9, voltage = 402.3)
        val at60 = mapRange(record, fix, energy, now)
        val at40 = mapRange(record.copy(soc = 40.0), fix, energy, now)
        check(at60.oneWayKm == 80.0 / 1.2 && at60.returnKm == 40.0 / 1.2)
        check(at40.oneWayKm == 40.0 / 1.2 && at40.returnKm == 20.0 / 1.2)
        check(mapRange(record.copy(soc = 20.0), fix, energy, now).oneWayKm == 0.0)
        check(mapRange(record.copy(soc = 10.0), fix, energy, now).returnKm == 0.0)
        val stored = mapRange(record.copy(timestamp = now-121_000), fix.copy(timeMillis = now-31_000), energy, now)
        check(stored.origin != null && stored.oneWayKm == at60.oneWayKm)
        check(stored.detail.contains("Poslednje očitavanje") && stored.detail.contains("poslednje GPS"))
        check(mapRange(record, fix.copy(accuracyMeters = 100f), energy, now).origin == null)
        check(mapRange(null, fix, energy, now).oneWayKm == null)
        check(mapRange(record, fix, RangeHistory(null, null, 0), now).oneWayKm == null)
        val display = packSocDisplay(context, record, now)
        check(display.voltageSocPercent!! in 51.0..53.0)
        val byPack = packSocDisplay(context, record.copy(bmsExtraAt = null), now)
        check(byPack.voltageSocPercent != null && byPack.voltageReason.contains("÷ 108"))
        val byGroups = packSocDisplay(context, record.copy(bmsExtraAt = null, voltage = null,
            cellVoltages = List(108) { 3.75 }, cellVoltagesAt = now), now)
        check(byGroups.meanGroupVoltageV == 3.75 && byGroups.voltageSocPercent != null)
        check(packSocDisplay(context, record.copy(bmsExtraAt = null, voltage = null), now).voltageSocPercent == null)
        check(theoreticalVoltageSoc(Double.NaN) == null && theoreticalVoltageSoc(4.8) == null)
        LastReadingStore.save(context, record)
        LastReadingStore.load(context)
        val saved = checkNotNull(LastReadingStore.reading.value)
        check(packSocDisplay(context, saved, saved.timestamp).voltageSocPercent == display.voltageSocPercent)
    }

    private fun testRangeDrawing(activity: Activity) {
        val start = System.currentTimeMillis()
        val fix = GpsPoint(start,44.0125,20.9167,null,null,5f)
        val history = RangeHistory(40.0, 20.0, 6)
        lateinit var view: OfflineAtlasView
        val parent = activity.findViewById<android.widget.FrameLayout>(android.R.id.content)
        fun update(soc: Double) {
            val estimate = mapRange(BmsReading(soc=soc,timestamp=start), fix, history, start)
            view.setContent(emptyList(), emptyList(), true, false, estimate, true)
        }
        fun picture(): android.graphics.Bitmap {
            val b = android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(b))
            return b
        }
        fun rightReach(b: android.graphics.Bitmap, expected: Int): Int {
            var far = 0
            for (y in b.height/2-6..b.height/2+6)
                for (x in b.width/2 until b.width) if (b.getPixel(x,y) == expected) far = maxOf(far,x-b.width/2)
            return far
        }
        try {
            runOnMainSync {
                view = OfflineAtlasView(activity)
                parent.addView(view, android.widget.FrameLayout.LayoutParams(400,400))
                update(60.0)
            }
            await("test map layout") { view.width > 0 && view.height > 0 }
            Thread.sleep(1500)
            runOnMainSync {
                view.showWidthKm(200.0)
                val cyan = android.graphics.Color.rgb(50,216,240)
                val amber = android.graphics.Color.rgb(255,213,103)
                val high = picture()
                val highRadius = rightReach(high, cyan)
                val highReturn = rightReach(high, amber)
                update(40.0)
                val low = picture()
                val lowRadius = rightReach(low, cyan)
                val lowReturn = rightReach(low, amber)
                check(highRadius in 127..143 && highReturn in 60..77) { "60% radii: $highRadius, $highReturn" }
                check(lowRadius in 60..77 && lowReturn in 28..43) { "40% radii: $lowRadius, $lowReturn" }
                update(20.0)
                val reserve = picture()
                check(rightReach(reserve, cyan)==0 && rightReach(reserve, amber)==0)
                high.recycle(); low.recycle(); reserve.recycle()
            }
        } finally { runOnMainSync { parent.removeView(view) } }
    }

    private fun testOfflineRoute() {
        val start = GpsPoint(System.currentTimeMillis(), 44.0125,20.9167,null,null,null)
        val plan = checkNotNull(RoadRouter.route(targetContext, start, MapDestination(43.7245,20.6898)))
        check(plan.distanceKm in 35.0..80.0 && plan.points.size >= 10)
        check(plan.startSnapKm < 2.0 && plan.endSnapKm < 2.0)
        val data = MapRangeEstimate(start, 80.0, 40.0, "", 60.0, 40.0, 20.0)
        val result = routeAnalysis(targetContext,plan,data,null,System.currentTimeMillis())
        check(result.contains("kWh") && result.contains("km") && result.contains("dolazak", true))
        results += result
    }

    private fun installTestProvider(manager: LocationManager): Boolean = try {
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false,
            true, true, true, android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun testGpsLifecycle(context: Context, manager: LocationManager) {
        uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        GpsTracker.stop()
        assertThat(GpsTracker.start(context), "GPS registration failed")
        assertThat(GpsTracker.start(context), "idempotent second start failed")
        assertThat(GpsTracker.isRunning, "start did not retain a registered listener")

        val tripStart = System.currentTimeMillis()
        val tripId = "gps-regression-${UUID.randomUUID()}"
        sendFix(manager, tripStart, 44.3791, 20.2677, 8f)
        await("first pre-trip fix reaches READY", 8_000) {
            GpsTracker.status.value == GpsTrackerStatus.READY &&
                GpsTracker.lastPoint.value?.timeMillis == tripStart
        }
        assertThat(GpsTracker.readPointsInInterval(context, tripStart - 1_000, tripStart + 1_000).isEmpty(),
            "pre-trip fix was written to CSV before startTrip")

        GpsTracker.startTrip(context, tripId, tripStart - 1_000)
        await("buffered first fix saved") {
            GpsTracker.readPointsInInterval(context, tripStart - 1_000, System.currentTimeMillis()).size == 1
        }
        val savedFirst = GpsTracker.readPointsInInterval(context, tripStart - 1_000, System.currentTimeMillis())
        assertThat(savedFirst.first().latitude == 44.3791, "first pre-trip fix was not flushed")

        Thread.sleep(2_100)
        val secondAt = System.currentTimeMillis()
        sendFix(manager, secondAt, 44.3795, 20.2681, 7f)
        await("second accurate fix saved") {
            GpsTracker.readPointsInInterval(context, tripStart - 1_000, System.currentTimeMillis()).size == 2
        }

        Thread.sleep(2_100)
        sendFix(manager, System.currentTimeMillis(), 44.3800, 20.2686, 500f)
        waitForMainQueue()
        assertCount(context, tripStart, 2, "poor-accuracy fix was persisted")

        Thread.sleep(2_100)
        sendFix(manager, System.currentTimeMillis() - 5 * 60_000, 44.3801, 20.2687, 8f)
        waitForMainQueue()
        assertCount(context, tripStart, 2, "stale fix was persisted")

        // Provider recovery is tested only when the emulator allows test-provider toggling.
        val providerToggled = runCatching {
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            waitForMainQueue()
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            waitForMainQueue()
        }.isSuccess
        if (providerToggled) {
            Thread.sleep(2_100)
            sendFix(manager, System.currentTimeMillis(), 44.3802, 20.2689, 8f)
            await("GPS recovery after provider re-enable") {
                GpsTracker.readPointsInInterval(context, tripStart - 1_000, System.currentTimeMillis()).size == 3
            }
        } else {
            skipped++
            results += "SKIP provider off/on: emulator disallowed toggle"
        }

        val countBeforeEnd = GpsTracker.readPointsInInterval(context, tripStart - 1_000,
            System.currentTimeMillis()).size
        val endedAt = System.currentTimeMillis()
        GpsTracker.stopTrip(context, tripId, endedAt)
        Thread.sleep(2_100)
        sendFix(manager, System.currentTimeMillis(), 44.3805, 20.2693, 8f)
        waitForMainQueue()
        assertCount(context, tripStart, countBeforeEnd, "fix after stopTrip was persisted")
        GpsTracker.stop()
        assertThat(!GpsTracker.isRunning, "stop did not unregister GPS")
        val latest = GpsTracker.readPointsInInterval(context, tripStart - 1_000, System.currentTimeMillis()).last()
        assertThat(GpsTracker.lastRecordedPoint(context) == latest, "last saved location was not restored")
        assertThat(!GpsTracker.isRunning, "restoring the map location unexpectedly restarted GPS")
    }

    private fun assertCount(context: Context, tripStart: Long, expected: Int, failure: String) {
        val actual = GpsTracker.readPointsInInterval(context, tripStart - 1_000, System.currentTimeMillis()).size
        assertThat(actual == expected, "$failure (expected $expected, got $actual)")
    }

    private fun testGpsZipExport(context: Context) {
        val gpsFiles = GpsTracker.exportFiles(context)
        val archive = TelemetryStore.makeExport(context, gpsFiles)
        assertThat(archive.isFile && archive.length() > 0, "ZIP export was not created")
        ZipFile(archive).use { zip ->
            val names = mutableSetOf<String>()
            val items = zip.entries()
            while (items.hasMoreElements()) names += items.nextElement().name
            assertThat("gps-events.jsonl" in names, "GPS status events missing from ZIP: $names")
            assertThat(names.any { it.matches(Regex("gps-\\d{4}-\\d{2}-\\d{2}\\.csv")) },
                "GPS daily CSV missing from ZIP: $names")
            assertThat(names.any { it.startsWith("trip-") && it.endsWith(".gpx") },
                "per-trip GPX missing from ZIP: $names")
        }
    }

    private fun testDeniedPermission(context: Context) {
        GpsTracker.stop()
        val denied = DeniedLocationContext(context)
        assertThat(!GpsTracker.start(denied), "GPS started despite denied fine-location permission")
        assertThat(GpsTracker.status.value == GpsTrackerStatus.NO_PERMISSION,
            "denied permission did not set NO_PERMISSION: ${GpsTracker.status.value}")
        assertThat(!GpsTracker.isRunning, "GPS listener remains registered after denied permission")
    }

    private fun sendFix(manager: LocationManager, at: Long, lat: Double, lon: Double, accuracy: Float) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            time = at
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            this.accuracy = accuracy
            speed = 5f
        }
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
    }

    private fun waitForMainQueue() {
        waitForIdleSync()
        Thread.sleep(300)
    }

    private fun await(label: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            waitForMainQueue()
            if (condition()) return
        }
        throw AssertionError("Timed out: $label; status=${GpsTracker.status.value}; ${locationDiagnostics()}")
    }

    private fun locationDiagnostics(): String = runCatching {
        val descriptor = uiAutomation.executeShellCommand(
            "dumpsys location | grep -i -E 'gps|mock|rs.battery.lab' | head -n 20")
        descriptor.use { fd -> InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd))
            .use { it.readText().replace('\n', ' ').take(1_500) } }
    }.getOrElse { "dumpsys unavailable: ${it.javaClass.simpleName}" }

    private fun testTripMonitor(context: Context) {
        val start = System.currentTimeMillis()
        fun reading(at: Long, speed: Double?, speedFresh: Boolean) = BmsReading(
            timestamp = at,
            speedKmh = speed,
            speedAt = if (speedFresh) at else null,
            odometerKm = 100.0,
            voltage = 400.0,
            current = 10.0
        )
        val initial = TripMonitor.onReading(context, reading(start, 5.0, true))
        assertThat(initial.started != null && TripMonitor.active.value != null,
            "first fresh speed >=3 km/h did not start trip")
        for (seconds in listOf(30L, 60L, 90L, 119L, 150L)) {
            val change = TripMonitor.onReading(context, reading(start + seconds * 1_000, null, false))
            assertThat(change.ended == null && TripMonitor.active.value != null,
                "missing motion ended trip at ${seconds}s")
        }
        val stoppedAt = start + 160_000
        val firstStop = TripMonitor.onReading(context, reading(stoppedAt, 0.0, true))
        assertThat(firstStop.ended == null, "one speed-zero reading ended trip")
        val midway = TripMonitor.onReading(context, reading(stoppedAt + 60_000, 0.0, true))
        assertThat(midway.ended == null, "trip ended before 120s confirmed stop")
        val final = TripMonitor.onReading(context, reading(stoppedAt + 121_000, 0.0, true))
        assertThat(final.ended != null && TripMonitor.active.value == null,
            "confirmed speed 0 for 120s did not end trip")
    }

    private fun testVoltageEstimate(context: Context) {
        val base = System.currentTimeMillis() - 12 * 3_600_000L
        fun point(at: Long, pct: Double, volts: Double) = BmsReading(timestamp = at,
            soc = pct + 4, socCandidateD862 = pct, socCandidatesAt = at,
            cellAverageVoltage = volts, bmsExtraAt = at, current = 0.5, tempAvg = 18.0,
            odometerKm = 120.0, speedKmh = 0.0, speedAt = at)
        VoltageSocCalibration.reset()
        val first = point(base, 35.0, 3.60)
        VoltageSocCalibration.observe(context, first)
        val isolatedPoint = first.copy(timestamp = base + 30 * 60_000L,
            bmsExtraAt = base + 30 * 60_000L, socCandidatesAt = base + 30 * 60_000L)
        VoltageSocCalibration.observe(context, isolatedPoint)
        assertThat(!File(context.filesDir, VoltageSocCalibration.FILE_NAME).exists(), "gap counted as rest")
        var last = first
        for ((n, pct) in listOf(35.0, 50.0, 65.0, 55.0).withIndex()) {
            val volts = listOf(3.60, 3.70, 3.80, 3.73)[n]
            for (i in 0..120) {
                last = point(base + (n+1) * 3_600_000L + i * 10_000L, pct, volts)
                VoltageSocCalibration.observe(context, last)
            }
        }
        assertThat(VoltageSocCalibration.estimate(context, last, last.timestamp).percent == 55.0,
            "rested in-range D862 estimate missing")
        assertThat(VoltageSocCalibration.estimate(context, last.copy(current = 50.0), last.timestamp).percent == null,
            "voltage estimate shown under load")
    }

    private fun testStorage(context: Context) {
        val prefs = context.getSharedPreferences("battery_lab", Context.MODE_PRIVATE)
        prefs.edit().putString("tire_profile", "test tires").putString("address", "saved adapter")
            .putString("records", "[{}]").commit()
        val now = System.currentTimeMillis()
        val stored = BmsReading(timestamp = now, soc = 58.0, current = 2.0, voltage = 391.0,
            socCandidateD862 = 55.0, socCandidatesAt = now, chargeCounterWords = listOf(15, 1),
            chargeCounterAt = now, cellVoltages = List(108) { 3.62 })
        LastReadingStore.save(context, stored)
        TelemetryStore.append(context, stored)
        val archive = MeasurementStorage.export(context)
        check(archive.isFile)
        val unrelated = File(context.filesDir, "keep-this.txt").apply { writeText("ok") }
        val token = RecordingControl.token()
        assertThat(RecordingControl.set(context, false), "pause not saved")
        assertThat(!RecordingControl.withActive(token) { error("obsolete write") }, "stale token accepted")
        assertThat(MeasurementStorage.byteCount(context) > 0, "missing size")
        MeasurementStorage.clear(context)
        LastReadingStore.load(context)
        assertThat(LastReadingStore.reading.value?.soc == 58.0 && LastReadingStore.reading.value?.socCandidateD862 == 55.0
            && LastReadingStore.reading.value?.cellVoltages?.size == 108, "offline snapshot lost")
        assertThat(MeasurementStorage.byteCount(context) == 0L && !archive.exists(), "old logs or ZIP survived")
        assertThat(unrelated.readText() == "ok" && prefs.getString("tire_profile", "") == "test tires"
            && prefs.getString("address","") == "saved adapter", "unrelated file or settings lost")
        assertThat(ChartHistory.readings.value.isEmpty() && TripMonitor.history.value.isEmpty() &&
            !RecordingControl.enabled.value, "old history or recording setting leaked")
        MeasurementStorage.clear(context)
        assertThat(RecordingControl.set(context, true), "cannot resume")
    }

    private fun assertThat(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val tag = "gps-regression-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, tag).apply { mkdirs() }
        private val prefsName = "$tag-battery_lab"
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$tag-$name", mode)
        fun cleanUp() {
            baseContext.deleteSharedPreferences(prefsName)
            baseContext.deleteSharedPreferences("$tag-battery_lab_last_reading")
            root.deleteRecursively()
        }
    }

    /** Denies only this test's Context; the real app permission is never revoked. */
    private class DeniedLocationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.ACCESS_FINE_LOCATION) PackageManager.PERMISSION_DENIED
            else super.checkPermission(permission, pid, uid)
        override fun checkSelfPermission(permission: String): Int =
            if (permission == Manifest.permission.ACCESS_FINE_LOCATION) PackageManager.PERMISSION_DENIED
            else super.checkSelfPermission(permission)
    }
}
