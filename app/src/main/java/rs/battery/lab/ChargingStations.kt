package rs.battery.lab

import android.content.Context
import org.json.JSONObject

internal data class ChargingStation(
    val id: String, val lat: Double, val lon: Double, val name: String,
    val operator: String, val sockets: String, val kind: String, val compatible: Boolean,
    val free: Boolean, val fee: String, val price: String, val access: String,
    val hours: String, val powerKw: Double?, val address: String, val checked: String,
    val sourceLabel: String = "OpenStreetMap · ODbL 1.0",
    val sourceUrl: String = "https://www.openstreetmap.org/copyright",
    val notes: String = ""
) {
    val destination get() = MapDestination(lat, lon)
    val marker get() = MapChargerMarker(id, lat, lon, name, free)
    val priceText get() = when (fee) {
        "free" -> "Besplatna energija prema izvoru"
        "paid" -> if (price.isBlank()) "Naplata · cenu proveri u aplikaciji operatora" else price
        else -> price.ifBlank { "Cena nije objavljena" }
    }
}

internal object ChargingStations {
    @Volatile private var cached: List<ChargingStation>? = null
    @Synchronized fun load(context: Context): List<ChargingStation> {
        cached?.let { return it }
        val rows = context.assets.open("charging-stations.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("stations")
        }
        return (0 until rows.length()).map { i ->
            val j = rows.getJSONObject(i)
            ChargingStation(j.getString("id"), j.getDouble("lat"), j.getDouble("lon"), j.getString("name"),
                j.optString("operator"), j.optString("sockets"), j.optString("kind", "unknown"),
                j.optBoolean("compatible"), j.optBoolean("free"), j.optString("fee", "unknown"),
                j.optString("price"), j.optString("access"), j.optString("hours"),
                j.optDouble("powerKw").takeIf { it.isFinite() && it > 0 },
                j.optString("address"), j.optString("checked"),
                j.optString("sourceLabel", "OpenStreetMap · ODbL 1.0"),
                j.optString("sourceUrl", "https://www.openstreetmap.org/copyright"), j.optString("notes"))
        }.also { cached = it }
    }
}
