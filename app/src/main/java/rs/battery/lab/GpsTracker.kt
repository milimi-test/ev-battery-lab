package rs.battery.lab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A recorded GPS fix. Speed is metres/second and accuracy is metres. */
data class GpsPoint(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float?
)

/** A BMS trip's time window. A closed trip excludes all later GPS fixes. */
data class GpsTripSession(val id: String, val startedAtMillis: Long, val endedAtMillis: Long?)

data class GpsGap(val fromMillis: Long, val toMillis: Long) {
    val durationMillis: Long get() = toMillis - fromMillis
}

/** Coverage is conservative: any interval without a fix for more than 30 s is incomplete. */
data class GpsTripSummary(
    val tripId: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val pointCount: Int,
    val segmentCount: Int,
    val gaps: List<GpsGap>,
    val missingDurationMillis: Long,
    val coveragePercent: Double,
    val isComplete: Boolean
)

enum class GpsTrackerStatus { STOPPED, NO_PERMISSION, GPS_UNAVAILABLE, WAITING_FIX, READY, TRACKING, POOR_ACCURACY, STORAGE_ERROR, FOREGROUND_REQUIRED }

/**
 * Read-only GPS recorder. Call [start] from the foreground service after location permission is
 * granted; call [stop] when that service stops. Points are stored in app-private daily CSV files.
 * No location is synthesized when permission, GPS hardware, or a satellite fix is unavailable.
 */
object GpsTracker {
    private const val DIRECTORY = "gps_tracks"
    private const val MAX_VISIBLE_POINTS = 10_000
    private const val MIN_TIME_MS = 2_000L
    private const val MIN_DISTANCE_METERS = 0f
    const val SEGMENT_GAP_MS = 30_000L
    private const val TRIPS_FILE = "trips.json"
    private val lock = Any()
    private val zone = ZoneId.systemDefault()

    private val mutableStatus = MutableStateFlow(GpsTrackerStatus.STOPPED)
    val status: StateFlow<GpsTrackerStatus> = mutableStatus.asStateFlow()

    private val mutablePoints = MutableStateFlow<List<GpsPoint>>(emptyList())
    val points: StateFlow<List<GpsPoint>> = mutablePoints.asStateFlow()

    private val mutableLastPoint = MutableStateFlow<GpsPoint?>(null)
    val lastPoint: StateFlow<GpsPoint?> = mutableLastPoint.asStateFlow()

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var appContext: Context? = null
    private val callbackThread by lazy { HandlerThread("gps-recorder").apply { start() } }
    private var recording: GpsTripSession? = null
    private val pending = ArrayDeque<GpsPoint>()
    private var lastSavedAt = 0L
    private var generation = 0L
    val isRunning: Boolean get() = synchronized(lock) { listener != null }

    /** Status changes are included in the ZIP; no coordinates are stored in this log. */
    fun report(context: Context, status: GpsTrackerStatus, reason: String) = synchronized(lock) {
        appContext = context.applicationContext
        setStatus(status, reason)
    }

    private fun setStatus(status: GpsTrackerStatus, reason: String) {
        if (mutableStatus.value == status) return
        mutableStatus.value = status
        if (!RecordingControl.enabled.value) return
        appContext?.let { app -> runCatching {
            val file = File(app.filesDir, "$DIRECTORY/gps-events.jsonl")
            file.parentFile?.mkdirs()
            if (file.length() > 512_000) file.writeText(file.readLines().takeLast(1000).joinToString("\n", postfix = "\n"))
            file.appendText(JSONObject().put("time", System.currentTimeMillis()).put("status", status.name)
                .put("reason", reason).put("trip", recording?.id).toString() + "\n")
        } }
    }

    /** Called by the connected-vehicle watchdog, independently of the next BMS poll. */
    fun checkSignal() = synchronized(lock) {
        if (listener != null && mutableStatus.value in listOf(GpsTrackerStatus.TRACKING, GpsTrackerStatus.READY) &&
            System.currentTimeMillis() - (mutableLastPoint.value?.timeMillis ?: 0L) > 15_000) {
            setStatus(GpsTrackerStatus.WAITING_FIX, "Nema sveže lokacije duže od 15 s")
        }
    }

    /** Start or resume a named trip; id is the caller's stable BMS trip identifier. */
    fun startTrip(context: Context, tripId: String, startedAtMillis: Long = System.currentTimeMillis()): GpsTripSession = synchronized(lock) {
        require(tripId.isNotBlank()) { "Trip id is required" }
        require(startedAtMillis > 0) { "Invalid trip start" }
        recording?.takeIf { it.id == tripId }?.let { return@synchronized it }
        val app = context.applicationContext
        val trips = readTripsLocked(app).toMutableList()
        val existing = trips.indexOfFirst { it.id == tripId }
        val session = if (existing >= 0) trips[existing].copy(endedAtMillis = null)
            else GpsTripSession(tripId, startedAtMillis, null)
        if (existing >= 0) trips[existing] = session else trips += session
        writeTripsLocked(app, trips)
        if (recording?.id != session.id) {
            recording = session
            mutablePoints.value = emptyList()
            pending.filter { it.timeMillis >= session.startedAtMillis }.forEach { persist(app, it) }
            if (lastSavedAt >= session.startedAtMillis && mutableLastPoint.value?.let { System.currentTimeMillis() - it.timeMillis in 0..15_000 } == true && mutableStatus.value == GpsTrackerStatus.READY)
                setStatus(GpsTrackerStatus.TRACKING, "Početak vožnje; rane tačke pridružene vožnji")
        }
        session
    }

    /** Close exactly this trip at the BMS end, excluding later GPS tracking from its export. */
    fun stopTrip(context: Context, tripId: String, endedAtMillis: Long = System.currentTimeMillis()): GpsTripSession? = synchronized(lock) {
        val app = context.applicationContext
        if (recording?.id == tripId) {
            recording = null
            pending.clear()
        }
        val trips = readTripsLocked(app).toMutableList()
        val index = trips.indexOfFirst { it.id == tripId }
        if (index < 0) return@synchronized null
        val old = trips[index]
        require(endedAtMillis >= old.startedAtMillis) { "Trip end precedes start" }
        val closed = old.copy(endedAtMillis = endedAtMillis)
        trips[index] = closed
        writeTripsLocked(app, trips)
        closed
    }

    fun readTrips(context: Context): List<GpsTripSession> = synchronized(lock) {
        readTripsLocked(context.applicationContext)
    }

    /** Reads only fixes in the requested time interval, including cross-midnight trips. */
    fun readPointsInInterval(context: Context, startedAtMillis: Long, endedAtMillis: Long): List<GpsPoint> {
        if (endedAtMillis < startedAtMillis) return emptyList()
        val startDay = Instant.ofEpochMilli(startedAtMillis).atZone(zone).toLocalDate()
        val endDay = Instant.ofEpochMilli(endedAtMillis).atZone(zone).toLocalDate()
        val result = ArrayList<GpsPoint>()
        var day = startDay
        while (!day.isAfter(endDay)) {
            result += readPoints(context, day).filter { it.timeMillis in startedAtMillis..endedAtMillis }
            day = day.plusDays(1)
        }
        return result.sortedBy { it.timeMillis }
    }

    fun summarizeTrip(context: Context, trip: GpsTripSession, nowMillis: Long = System.currentTimeMillis()): GpsTripSummary {
        val end = trip.endedAtMillis ?: nowMillis
        return summarizeInterval(readPointsInInterval(context, trip.startedAtMillis, end), trip.startedAtMillis, end, trip.id)
    }

    /** Pure helper for imported data and tests. Gaps include missing start/end fixes. */
    fun summarizeInterval(points: List<GpsPoint>, startedAtMillis: Long, endedAtMillis: Long, tripId: String? = null): GpsTripSummary {
        require(endedAtMillis >= startedAtMillis) { "Trip end precedes start" }
        val fixes = points.filter { it.timeMillis in startedAtMillis..endedAtMillis }.sortedBy { it.timeMillis }
        val gaps = ArrayList<GpsGap>()
        var previous = startedAtMillis
        for (point in fixes) {
            if (point.timeMillis - previous > SEGMENT_GAP_MS) gaps += GpsGap(previous, point.timeMillis)
            previous = point.timeMillis
        }
        if (endedAtMillis - previous > SEGMENT_GAP_MS) gaps += GpsGap(previous, endedAtMillis)
        val missing = if (fixes.isEmpty()) endedAtMillis - startedAtMillis
            else gaps.sumOf { it.durationMillis - SEGMENT_GAP_MS }
        val duration = endedAtMillis - startedAtMillis
        val coverage = if (duration == 0L) { if (fixes.isEmpty()) 0.0 else 100.0 }
            else (100.0 * (duration - missing).coerceAtLeast(0) / duration).coerceIn(0.0, 100.0)
        val internalGaps = fixes.zipWithNext().count { (a, b) -> b.timeMillis - a.timeMillis > SEGMENT_GAP_MS }
        return GpsTripSummary(tripId, startedAtMillis, endedAtMillis, fixes.size,
            if (fixes.isEmpty()) 0 else internalGaps + 1, gaps, missing, coverage,
            fixes.isNotEmpty() && gaps.isEmpty())
    }

    /** Returns true only when GPS location updates were registered. */
    fun start(context: Context): Boolean = synchronized(lock) {
        val app = context.applicationContext
        appContext = app
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) stopLocked()
            setStatus(GpsTrackerStatus.NO_PERMISSION, "Nedostaje dozvola za preciznu lokaciju")
            return@synchronized false
        }
        if (listener != null) return@synchronized true
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null || !locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            setStatus(GpsTrackerStatus.GPS_UNAVAILABLE, "Telefon nema GPS prijemnik")
            return@synchronized false
        }
        mutableLastPoint.value = null
        val token = ++generation
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) { record(app, location, token) }
            // Android 8/9 still invoke this interface method; it became a default only in R.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderDisabled(provider: String) = synchronized(lock) {
                if (token == generation) {
                    mutableLastPoint.value = null
                    setStatus(GpsTrackerStatus.GPS_UNAVAILABLE, "Lokacija je isključena u telefonu")
                }
            }
            override fun onProviderEnabled(provider: String) = synchronized(lock) {
                if (token == generation) setStatus(GpsTrackerStatus.WAITING_FIX, "GPS ponovo uključen; čekam signal")
            }
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_METERS,
                callback,
                callbackThread.looper
            )
        } catch (_: SecurityException) {
            setStatus(GpsTrackerStatus.NO_PERMISSION, "Android je odbio zahtev za lokaciju")
            return@synchronized false
        } catch (_: IllegalArgumentException) {
            setStatus(GpsTrackerStatus.GPS_UNAVAILABLE, "GPS prijemnik nije dostupan")
            return@synchronized false
        }
        manager = locationManager
        listener = callback
        setStatus(if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsTrackerStatus.WAITING_FIX
            else GpsTrackerStatus.GPS_UNAVAILABLE, "GPS registrovan; čekam novu lokaciju")
        true
    }

    fun forgetHistory() = synchronized(lock) {
        stopLocked()
        recording = null
        lastSavedAt = 0L
        mutablePoints.value = emptyList()
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        generation++
        listener?.let { callback -> runCatching { manager?.removeUpdates(callback) } }
        listener = null
        manager = null
        mutableLastPoint.value = null
        pending.clear()
        setStatus(GpsTrackerStatus.STOPPED, "GPS odjavljen")
    }

    private fun record(context: Context, location: Location, token: Long) = synchronized(lock) {
        if (listener == null || token != generation) return@synchronized
        val age = System.currentTimeMillis() - location.time
        val monotonicAge = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (age !in -2_000..15_000 || monotonicAge !in 0..15_000) return@synchronized
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..60f) {
            setStatus(GpsTrackerStatus.POOR_ACCURACY, "GPS preciznost slabija od 60 m; tačka nije upisana")
            return@synchronized
        }
        val point = GpsPoint(
            timeMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() && it.isFinite() && it >= 0 },
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }
        )
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return@synchronized
        if (point.timeMillis <= (mutableLastPoint.value?.timeMillis ?: 0L)) return@synchronized
        mutableLastPoint.value = point
        pending.addLast(point)
        while (pending.isNotEmpty() && pending.first().timeMillis < point.timeMillis - 60_000) pending.removeFirst()
        if (recording?.let { point.timeMillis >= it.startedAtMillis } == true) {
            if (persist(context, point)) setStatus(GpsTrackerStatus.TRACKING, "Vožnja se snima")
        } else setStatus(GpsTrackerStatus.READY, "Signal spreman; čekam kretanje, lokacije se ne čuvaju")
    }

    private fun persist(context: Context, point: GpsPoint): Boolean {
        if (point.timeMillis <= lastSavedAt) return true
        val day = Instant.ofEpochMilli(point.timeMillis).atZone(zone).toLocalDate()
        val file = dailyFile(context, day)
        return try {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(CSV_HEADER)
            file.appendText(csvLine(point))
            lastSavedAt = point.timeMillis
            mutablePoints.value = (mutablePoints.value + point).takeLast(MAX_VISIBLE_POINTS)
            true
        } catch (_: java.io.IOException) {
            setStatus(GpsTrackerStatus.STORAGE_ERROR, "Nije moguće sačuvati GPS tačku u memoriju telefona")
            false
        }
    }

    /** Restore the most recent saved position without starting location tracking. */
    fun lastRecordedPoint(context: Context): GpsPoint? = synchronized(lock) {
        val files = File(context.applicationContext.filesDir, DIRECTORY).listFiles()
            ?.filter { it.name.matches(Regex("gps-\\d{4}-\\d{2}-\\d{2}\\.csv")) }
            ?.sortedByDescending { it.name }.orEmpty()
        files.firstNotNullOfOrNull { file ->
            runCatching {
                java.io.RandomAccessFile(file, "r").use { input ->
                    val length = minOf(input.length(), 32_768L).toInt()
                    input.seek(input.length() - length)
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    bytes.toString(Charsets.UTF_8).lineSequence().toList().asReversed()
                        .firstNotNullOfOrNull { parseCsvLine(it) }
                }
            }.getOrNull()
        }
    }

    /** Read one local calendar day's persisted GPS fixes. */
    fun readPoints(context: Context, day: LocalDate = LocalDate.now(zone)): List<GpsPoint> = synchronized(lock) {
        val file = dailyFile(context.applicationContext, day)
        if (!file.exists()) return@synchronized emptyList()
        file.useLines { lines -> lines.drop(1).mapNotNull(::parseCsvLine).toList() }
    }

    /** Export one day as CSV text, ready for ACTION_SEND or a document picker. */
    fun exportCsv(context: Context, day: LocalDate = LocalDate.now(zone)): String =
        buildString {
            append(CSV_HEADER)
            readPoints(context, day).forEach { append(csvLine(it)) }
        }

    /** One GPX track per trip, with a new segment after each gap over 30 s. */
    fun exportTripGpx(context: Context, trip: GpsTripSession, nowMillis: Long = System.currentTimeMillis()): String {
        val end = trip.endedAtMillis ?: nowMillis
        return gpx(listOf(trip.id to readPointsInInterval(context, trip.startedAtMillis, end)))
    }

    /** Daily GPX keeps named trips separate. Unassigned legacy fixes become separate gap-bounded tracks. */
    fun exportGpx(context: Context, day: LocalDate = LocalDate.now(zone)): String {
        val points = readPoints(context, day).sortedBy { it.timeMillis }
        val dayTrips = readTrips(context).filter { trip ->
            val start = Instant.ofEpochMilli(trip.startedAtMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(trip.endedAtMillis ?: System.currentTimeMillis()).atZone(zone).toLocalDate()
            !day.isBefore(start) && !day.isAfter(end)
        }
        val claimed = HashSet<Long>()
        val tracks = ArrayList<Pair<String, List<GpsPoint>>>()
        for (trip in dayTrips) {
            val selected = points.filter { it.timeMillis in trip.startedAtMillis..(trip.endedAtMillis ?: System.currentTimeMillis()) }
            if (selected.isNotEmpty()) {
                tracks += trip.id to selected
                selected.forEach { claimed += it.timeMillis }
            }
        }
        val unassigned = points.filter { it.timeMillis !in claimed }
        splitSegments(unassigned).forEachIndexed { index, segment ->
            tracks += "Unassigned $day #${index + 1}" to segment
        }
        return gpx(tracks)
    }

    /** Existing daily CSV files plus freshly generated GPX files, for a ZIP export. */
    fun exportFiles(context: Context): List<File> = synchronized(lock) {
        val app = context.applicationContext
        val csvFiles = File(app.filesDir, DIRECTORY).listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("gps-\\d{4}-\\d{2}-\\d{2}\\.csv")) }
            ?.sortedBy { it.name }
            ?: emptyList()
        val gpxDir = File(app.filesDir, "gps_exports").apply { mkdirs() }
        val gpxFiles = csvFiles.mapNotNull { csv ->
            val day = runCatching { LocalDate.parse(csv.name.removePrefix("gps-").removeSuffix(".csv")) }.getOrNull()
                ?: return@mapNotNull null
            File(gpxDir, "gps-$day.gpx").apply { writeText(exportGpx(app, day)) }
        }
        // Distinct per-trip exports include cross-midnight points without joining successive drives.
        val tripFiles = readTripsLocked(app).map { trip ->
            val safeName = trip.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
            val suffix = trip.startedAtMillis
            File(gpxDir, "trip-$suffix-$safeName.gpx").apply { writeText(exportTripGpx(app, trip)) }
        }
        val sessionsFile = File(app.filesDir, "$DIRECTORY/$TRIPS_FILE")
        csvFiles + gpxFiles + tripFiles + listOfNotNull(sessionsFile.takeIf { it.exists() },
            File(app.filesDir, "$DIRECTORY/gps-events.jsonl").takeIf { it.exists() })
    }

    private fun splitSegments(points: List<GpsPoint>): List<List<GpsPoint>> {
        if (points.isEmpty()) return emptyList()
        val segments = ArrayList<List<GpsPoint>>()
        var current = ArrayList<GpsPoint>()
        var previous: GpsPoint? = null
        for (point in points.sortedBy { it.timeMillis }) {
            if (previous != null && point.timeMillis - previous.timeMillis > SEGMENT_GAP_MS) {
                segments += current
                current = ArrayList()
            }
            current += point
            previous = point
        }
        segments += current
        return segments
    }

    private fun gpx(tracks: List<Pair<String, List<GpsPoint>>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"e-208 Battery Lab\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for ((name, points) in tracks) {
            if (points.isEmpty()) continue
            append("<trk><name>").append(xmlEscape(name)).append("</name>\n")
            for (segment in splitSegments(points)) {
                append("<trkseg>\n")
                for (point in segment) {
                    append("<trkpt lat=\"").append(fmt(point.latitude, 8)).append("\" lon=\"")
                        .append(fmt(point.longitude, 8)).append("\">")
                    point.altitudeMeters?.let { append("<ele>").append(fmt(it, 2)).append("</ele>") }
                    append("<time>").append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(point.timeMillis))).append("</time>")
                    append("</trkpt>\n")
                }
                append("</trkseg>\n")
            }
            append("</trk>\n")
        }
        append("</gpx>\n")
    }

    private fun xmlEscape(text: String): String = buildString {
        for (char in text) append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&apos;"
            else -> char.toString()
        })
    }

    private fun readTripsLocked(context: Context): List<GpsTripSession> {
        val file = File(File(context.filesDir, DIRECTORY), TRIPS_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                GpsTripSession(item.getString("id"), item.getLong("start"),
                    if (item.isNull("end")) null else item.getLong("end"))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeTripsLocked(context: Context, trips: List<GpsTripSession>) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val target = File(directory, TRIPS_FILE)
        val temporary = File(directory, "$TRIPS_FILE.tmp")
        val array = JSONArray()
        for (trip in trips) array.put(JSONObject().put("id", trip.id)
            .put("start", trip.startedAtMillis).put("end", trip.endedAtMillis ?: JSONObject.NULL))
        temporary.writeText(array.toString())
        if (!temporary.renameTo(target)) {
            target.writeText(array.toString())
            temporary.delete()
        }
    }

    private fun dailyFile(context: Context, day: LocalDate): File =
        File(File(context.filesDir, DIRECTORY), "gps-$day.csv")

    private const val CSV_HEADER = "time_utc,latitude,longitude,altitude_m,speed_m_s,accuracy_m\n"

    private fun csvLine(point: GpsPoint): String = buildString {
        append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(point.timeMillis))).append(',')
        append(fmt(point.latitude, 8)).append(',').append(fmt(point.longitude, 8)).append(',')
        append(point.altitudeMeters?.let { fmt(it, 2) } ?: "").append(',')
        append(point.speedMetersPerSecond?.let { fmt(it.toDouble(), 2) } ?: "").append(',')
        append(point.accuracyMeters?.let { fmt(it.toDouble(), 2) } ?: "").append('\n')
    }

    private fun parseCsvLine(line: String): GpsPoint? {
        val fields = line.split(',')
        if (fields.size != 6) return null
        return runCatching {
            GpsPoint(
                timeMillis = Instant.parse(fields[0]).toEpochMilli(),
                latitude = fields[1].toDouble(),
                longitude = fields[2].toDouble(),
                altitudeMeters = fields[3].takeIf { it.isNotEmpty() }?.toDouble(),
                speedMetersPerSecond = fields[4].takeIf { it.isNotEmpty() }?.toFloat(),
                accuracyMeters = fields[5].takeIf { it.isNotEmpty() }?.toFloat()
            )
        }.getOrNull()
    }

    private fun fmt(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)
}
