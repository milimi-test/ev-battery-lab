package rs.battery.lab

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.math.roundToInt

/** Forecasts at coarse points along a user-selected route. No GPS fix or track is stored here. */
internal object RouteWind {
    data class Site(val latTenths: Int, val lonTenths: Int) {
        val lat get() = latTenths / 10.0
        val lon get() = lonTenths / 10.0
    }
    data class Sample(val lat: Double, val lon: Double, val at: Long, val speed: Double, val from: Double)
    private data class Forecast(val fetched: Long, val samples: List<Sample>)
    private val cache = object : LinkedHashMap<Site, Forecast>(80, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Site, Forecast>?): Boolean = size > 80
    }
    private val retry = mutableMapOf<Site, Long>()

    fun site(lat: Double, lon: Double) = Site((lat * 10).roundToInt(), (lon * 10).roundToInt())

    /** Blocking, called on the route calculation's IO thread. At most eight sites per request. */
    fun fetch(sites: List<Site>, now: Long): List<List<Sample>> {
        val missing = synchronized(this) {
            retry.entries.removeAll { now - it.value !in 0..120_000 }
            sites.distinct().filter { site ->
                cache[site]?.let { now - it.fetched in 0..1_800_000 } != true && site !in retry
            }.onEach { retry[it] = now }
        }
        if (missing.isNotEmpty()) {
            val lat = missing.joinToString(",") { String.format(Locale.US, "%.1f", it.lat) }
            val lon = missing.joinToString(",") { String.format(Locale.US, "%.1f", it.lon) }
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh&timeformat=unixtime&forecast_hours=36")
            try {
                val conn = url.openConnection() as HttpsURLConnection
                try {
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3500
                    conn.setRequestProperty("User-Agent", "e208-battery-lab/route-weather")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    val places = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
                    for (i in 0 until minOf(places.length(), missing.size)) {
                        val place = places.getJSONObject(i)
                        val site = missing[i]
                        val modelLat = place.getDouble("latitude")
                        val modelLon = place.getDouble("longitude")
                        val hourly = place.getJSONObject("hourly")
                        val times = hourly.getJSONArray("time")
                        val speeds = hourly.getJSONArray("wind_speed_10m")
                        val directions = hourly.getJSONArray("wind_direction_10m")
                        val readings = (0 until times.length()).mapNotNull { j ->
                            val at = times.optLong(j) * 1000
                            val speed = speeds.optDouble(j)
                            val from = directions.optDouble(j)
                            if (at <= 0 || speed !in 0.0..250.0 || from !in 0.0..360.0) null
                            else Sample(modelLat, modelLon, at, speed, from)
                        }
                        if (readings.isNotEmpty()) synchronized(this) { cache[site] = Forecast(now, readings) }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: java.io.IOException) {
                // The offline route and terrain remain usable without a forecast.
            } catch (_: org.json.JSONException) {
                // An upstream error document is not weather.
            }
        }
        return synchronized(this) {
            sites.distinct().mapNotNull { cache[it]?.takeIf { entry -> now - entry.fetched in 0..7_200_000 }?.samples }
        }
    }

    fun at(forecasts: List<List<Sample>>, last: WeatherSample?, lat: Double, lon: Double, at: Long, now: Long): Sample? {
        val site = forecasts.minByOrNull { routeKm(lat, lon, it.first().lat, it.first().lon) }
        val reading = site?.minByOrNull { abs(it.at - at) }?.takeIf {
            abs(it.at - at) <= 3_600_000 && routeKm(lat, lon, it.lat, it.lon) <= 40
        }
        if (reading != null) return reading
        if (last == null || now - last.fetchedAt !in 0..3_600_000 ||
            abs(at - last.fetchedAt) > 5_400_000 || routeKm(lat, lon, last.latitude, last.longitude) > 25) return null
        val speed = last.windSpeedKmh?.takeIf { it in 0.0..250.0 } ?: return null
        val from = last.windFromDegrees?.takeIf { it in 0.0..360.0 } ?: return null
        return Sample(last.latitude, last.longitude, last.fetchedAt, speed, from)
    }
}
