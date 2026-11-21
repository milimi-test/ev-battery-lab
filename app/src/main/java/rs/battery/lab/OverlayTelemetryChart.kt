package rs.battery.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun OverlayTelemetryChart(samples: List<BmsReading>, gps: List<GpsPoint> = emptyList(), defaults: List<String> = listOf("speed", "power")) {
    val signals by produceState<List<ChartSignal>>(emptyList(), samples, gps) {
      value = withContext(Dispatchers.Default) {
        val ordered = samples.filter { it.timestamp > 0 }.sortedBy { it.timestamp }
        fun signal(key: String, label: String, unit: String, color: Long, decimals: Int = 1,
                   at: (BmsReading) -> Long? = { it.timestamp }, ttl: Long = 120_000,
                   value: (BmsReading) -> Double?): ChartSignal {
            val points = ordered.map { r ->
                val measuredAt = at(r)
                val measured = value(r)?.takeIf { it.isFinite() && measuredAt != null && r.timestamp - measuredAt in 0..ttl }
                ChartPoint(if (measured != null) measuredAt!! else r.timestamp, measured)
            }.associateBy { it.time }.values.sortedBy { it.time }
            return ChartSignal(key, label, unit, Color(color), points, decimals)
        }
        listOf(
            signal("speed", "Brzina", "km/h", 0xFF52DAF3, 1, { it.speedAt }, 15_000) { it.speedKmh },
            signal("power", "Snaga", "kW", 0xFFFFC078) { it.powerKw },
            signal("soc", "SOC", "%", 0xFF77E6AE) { it.soc },
            signal("energy", "BMS energija", "kWh", 0xFFA0B7FF, 2) { it.energy },
            signal("current", "Struja", "A", 0xFFFF879A) { it.current },
            signal("voltage", "Napon paketa", "V", 0xFFFFE28B) { it.voltage },
            signal("temp", "Temp. baterije", "°C", 0xFFBD9BFF) { it.tempAvg },
            signal("outside", "Spoljna temp.", "°C", 0xFF80DCD5, 1, { it.vcuExtraAt }) { it.outsideTemp },
            signal("cellAvg", "Srednji napon ćelija", "V", 0xFF7BCBFF, 3, { it.bmsExtraAt }) { it.cellAverageVoltage },
            signal("cellMin", "Najniži napon ćelija", "V", 0xFFFF9ACD, 3) { it.cellMin },
            signal("cellMax", "Najviši napon ćelija", "V", 0xFF81D6FF, 3) { it.cellMax },
            signal("delta", "Razlika ćelija", "mV", 0xFFFF9F72, 0) { r -> if (r.cellMax != null && r.cellMin != null) (r.cellMax - r.cellMin) * 1000 else null },
            signal("soh", "SOH paketa", "%", 0xFFB5DC8B, 2) { it.soh },
            signal("motor", "Motor", "°C", 0xFFE8B199, 1, { it.vcuExtraAt }) { it.motorTemp },
            signal("coolant", "Rashladna tečnost", "°C", 0xFF9CAEF3, 1, { it.vcuExtraAt }) { it.motorCoolantTemp },
            signal("motorPower", "Snaga motora", "kW", 0xFFFFB389) { it.motorPowerKw },
            signal("obcTemp", "OBC temperatura", "°C", 0xFF66D5CB, 1, { it.vcuExtraAt }) { it.obcTemp },
            signal("inverterTemp", "Inverter", "°C", 0xFFDBACFF, 1, { it.inverterAt }) { it.inverterTemp },
            signal("dcdcTemp", "DC/DC", "°C", 0xFFE7CC86, 1, { it.vcuExtraAt }) { it.dcdcTemp },
            signal("tractionCoolant", "Tečnost · pogon", "°C", 0xFF80B7FA, 1, { it.vcuExtraAt }) { it.tractionCoolantTemp },
            signal("heatingCoolant", "Tečnost · grejanje", "°C", 0xFFFF9C9C, 1, { it.vcuExtraAt }) { it.heatingCoolantTemp },
            signal("auxVoltage", "12 V akumulator", "V", 0xFFA8DB84, 2, { it.bmsExtraAt }) { it.auxiliaryVoltage },
            signal("insulation", "Izolacija HV", "kΩ", 0xFFC5A9F7, 0, { it.bmsExtraAt }) { it.insulationKohm },
            signal("chargerVoltage", "OBC izlaz HV", "V", 0xFF8FD4B8, 1, { it.chargerAt }) { it.chargerHvVoltage },
            ChartSignal("altitude", "GPS visina", "m", Color(0xFFFF87C2), gps.sortedBy { it.timeMillis }.map {
                ChartPoint(it.timeMillis, it.altitudeMeters?.takeIf { h -> h in -200.0..3000.0 && (it.accuracyMeters == null || it.accuracyMeters <= 60f) })
            }.distinctBy { it.time }, 0)
        )
      }
    }
    ChartExplorer(signals, defaults)
}
