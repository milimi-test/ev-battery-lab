package rs.battery.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

@Composable internal fun TelemetryChart(title: String, samples: List<BmsReading>, unit: String,
    color: Color = Color(0xFF52DAF3), measuredAt: (BmsReading) -> Long? = { it.timestamp },
    freshnessMs: Long = 120_000, value: (BmsReading) -> Double?) {
    Column {
        LText(title, color = Color.White, fontSize = 14.sp)
        val points = remember(samples, measuredAt, value, freshnessMs) { samples.filter { it.timestamp > 0 }.map { r ->
            val time = measuredAt(r)
            val reading = value(r)?.takeIf { it.isFinite() && time != null && r.timestamp - time in 0..freshnessMs }
            ChartPoint(if (reading != null) time!! else r.timestamp, reading)
        }.associateBy { it.time }.values.sortedBy { it.time } }
        ChartExplorer(listOf(ChartSignal("value", title, unit, color, points, if (unit == "kWh") 2 else 1)))
    }
}
