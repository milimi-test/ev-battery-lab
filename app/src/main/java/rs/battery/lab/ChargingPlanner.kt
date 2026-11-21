package rs.battery.lab

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal data class ChargingEta(val neededBatteryKwh: Double, val minutes: Int, val batteryPowerKw: Double)

internal fun chargingEta(currentSoc: Double, targetSoc: Double, usableCapacityKwh: Double, batteryPowerKw: Double): ChargingEta? {
    if (currentSoc !in 0.0..100.0 || targetSoc !in 0.0..100.0 || targetSoc <= currentSoc ||
        usableCapacityKwh !in 8.0..80.0 || batteryPowerKw !in 0.5..22.0) return null
    val needed = (targetSoc - currentSoc) / 100.0 * usableCapacityKwh
    return ChargingEta(needed, (needed / batteryPowerKw * 60).roundToInt().coerceAtLeast(1), batteryPowerKw)
}

internal fun capacityForPlanner(samples: List<BmsReading>, trips: List<TripRecord>, nominalKwh: Double = 42.0): Pair<Double, String> {
    capacityFromSamples(samples)?.let { return it.kwh to "iz punjenja" }
    trips.asReversed().firstNotNullOfOrNull { trip ->
        val deltaSoc = (trip.startSoc ?: return@firstNotNullOfOrNull null) - (trip.endSoc ?: return@firstNotNullOfOrNull null)
        val deltaEnergy = trip.bmsRemainingEnergyChangeKwh ?: return@firstNotNullOfOrNull null
        val estimate = deltaEnergy / deltaSoc * 100
        estimate.takeIf { deltaSoc >= 10 && deltaEnergy >= 2 && it in 8.0..80.0 }
    }?.let { return it to "iz poslednje vožnje" }
    return nominalKwh to "nazivni kapacitet profila; procena do testa kapaciteta"
}

@Composable internal fun ChargePlanner(live: BmsReading?, samples: List<BmsReading>, trips: List<TripRecord>) {
    var target by remember { mutableFloatStateOf(80f) }
    var wallPower by remember { mutableFloatStateOf(11f) }
    val vehicle = VehicleProfiles.current(LocalContext.current)
    val capacity = capacityForPlanner(samples, trips, vehicle.nominalUsableKwh)
    LText("Plan punjenja", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(5.dp))
    LText("Ciljni SOC: ${target.roundToInt()}%", color = Color(0xFF03B9FF), fontSize = 13.sp)
    Slider(value = target, onValueChange = { target = it }, valueRange = 50f..100f, steps = 49)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = wallPower == 11f, onClick = { wallPower = 11f }, label = { LText("3×16 A • 11 kW") })
        FilterChip(selected = wallPower == 7.4f, onClick = { wallPower = 7.4f }, label = { LText("7,4 kW") })
    }
    if (vehicle.isZoe) FilterChip(selected = wallPower == 22f, onClick = { wallPower = 22f }, label = { LText("Zoe AC · 22 kW") })
    val current = live?.soc
    if (current == null) {
        LText("Čekam stvarni SOC iz vozila.", color = Color(0xFF9AB1C1), fontSize = 12.sp)
        return
    }
    LText("Polazni SOC ${"%.1f".format(current)}% · ${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(live.timestamp))}", color = Color.White, fontSize = 13.sp)
    if (target <= current) {
        LText("Cilj je već dostignut (${String.format("%.1f", current)}%).", color = Color(0xFF00DB88), fontSize = 12.sp)
        return
    }
    val theoretical = chargingEta(current, target.toDouble(), capacity.first, wallPower * 0.90)
    val recent = samples.filter { sample -> live.timestamp - sample.timestamp in 0..15 * 60_000L &&
        (sample.powerKw ?: 0.0) < -0.5 && (sample.odometerKm == null || live.odometerKm == null ||
            kotlin.math.abs(sample.odometerKm - live.odometerKm) < 0.3) }
    val observed = if (recent.size >= 6 && recent.last().timestamp - recent.first().timestamp >= 5 * 60_000L)
        recent.mapNotNull { it.powerKw?.let { p -> -p } }.average().takeIf { it in 0.5..22.0 } else null
    val measuredEta = observed?.let { chargingEta(current, target.toDouble(), capacity.first, it) }
    if (theoretical != null) {
        LText("Potrebno u bateriji ≈ ${"%.1f".format(theoretical.neededBatteryKwh)} kWh", color = Color.White, fontSize = 13.sp)
        LText("Pri ${"%.1f".format(wallPower)} kW i 90% efikasnosti: oko ${theoretical.minutes} min", color = Color(0xFF00DB88), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    if (measuredEta != null) LText("Po poslednjih ≥5 min stvarnog punjenja (${"%.1f".format(measuredEta.batteryPowerKw)} kW u bateriji): oko ${measuredEta.minutes} min", color = Color(0xFF03B9FF), fontSize = 12.sp)
    LText("Kapacitet ${"%.1f".format(capacity.first)} kWh • ${capacity.second}. Snaga zavisi od ugrađenog punjača i priključka; pri kraju punjenja snaga može opasti.", color = Color(0xFF9AB1C1), fontSize = 11.sp)
}
