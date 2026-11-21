package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Optional model weather snapshot. These values are never presented as vehicle sensors. */
internal data class WeatherSample(val fetchedAt: Long, val latitude: Double, val longitude: Double,
    val airTempC: Double?, val precipitationMm: Double?, val rainMm: Double?,
    val snowfallCm: Double?, val code: Int?, val windSpeedKmh: Double?, val windFromDegrees: Double?)

/** Positive means estimated headwind. Weather direction is the direction wind comes from. */
internal fun relativeWindKmh(weather: WeatherSample, from: GpsPoint, to: GpsPoint): Double? {
    val speed = weather.windSpeedKmh ?: return null
    val direction = weather.windFromDegrees ?: return null
    if (to.timeMillis - from.timeMillis !in 1..30_000) return null
    val lat0 = Math.toRadians(from.latitude)
    val lat1 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat1)
    val x = cos(lat0) * sin(lat1) - sin(lat0) * cos(lat1) * cos(dLon)
    val heading = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    return speed * cos(Math.toRadians(heading - direction))
}

internal fun windCompass(degrees: Double?): String {
    if (degrees == null) return "—"
    val labels = if (AppLanguage.code == "en") listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW") else listOf("S", "SI", "I", "JI", "J", "JZ", "Z", "SZ")
    return labels[((degrees / 45.0 + 0.5).toInt() % 8 + 8) % 8]
}

internal object WeatherTracker {
    val latest = MutableStateFlow<WeatherSample?>(null)
    private var lastAttemptAt = 0L
    fun reset() { lastAttemptAt = 0L; latest.value = null }
    private const val INTERVAL_MS = 30 * 60_000L

    /** Call from an IO coroutine only while a BMS trip is active. */
    fun sampleIfDue(context: Context, point: GpsPoint) {
        val recordingToken = RecordingControl.token() ?: return
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < INTERVAL_MS) return
        lastAttemptAt = now
        if (point.accuracyMeters != null && point.accuracyMeters > 60f) return
        runCatching {
            val lat = String.format(Locale.US, "%.5f", point.latitude)
            val lon = String.format(Locale.US, "%.5f", point.longitude)
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,precipitation,rain,snowfall,weather_code,wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh")
            val connection = url.openConnection() as HttpsURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", "e208-battery-lab/2 (weather observations)")
            try {
                val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                fun number(key: String) = current.optDouble(key).takeUnless { it.isNaN() }
                val sample = WeatherSample(now, point.latitude, point.longitude, number("temperature_2m"),
                    number("precipitation"), number("rain"), number("snowfall"),
                    current.optInt("weather_code").takeIf { current.has("weather_code") },
                    number("wind_speed_10m"), number("wind_direction_10m"))
                RecordingControl.withActive(recordingToken) {
                    latest.value = sample
                    File(context.filesDir, "weather-samples.jsonl").appendText(JSONObject()
                        .put("fetched_at_ms", now).put("latitude", sample.latitude).put("longitude", sample.longitude)
                        .put("temperature_2m_c", sample.airTempC).put("precipitation_mm", sample.precipitationMm)
                        .put("rain_mm", sample.rainMm).put("snowfall_cm", sample.snowfallCm)
                        .put("wind_speed_10m_kmh", sample.windSpeedKmh).put("wind_from_degrees", sample.windFromDegrees)
                        .put("weather_code", sample.code).put("source", "Open-Meteo current model").toString() + "\n")
                }
            } finally { connection.disconnect() }
        }
    }
}
