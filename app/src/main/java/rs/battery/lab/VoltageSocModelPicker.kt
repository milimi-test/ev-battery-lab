package rs.battery.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val modelCyan = Color(0xFF52DAF3)
private val modelMuted = Color(0xFFB0C3D0)
private val modelAmber = Color(0xFFFFD567)

@Composable internal fun VoltageSocModelPicker(modelId: String, voltage: Double?) {
    val context = LocalContext.current
    val model = VoltageSocModels.byId(modelId) ?: VoltageSocModels.all.first()
    var menu by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var expandedChart by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = UiStrings.text("Izaberi SOC model"); stateDescription = UiStrings.text(model.title) }) {
            LText("Model: ${model.title} ▾", fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            VoltageSocModels.all.forEach { option ->
                DropdownMenuItem(text = { LText((if (option.id == model.id) "✓ " else "") + option.title,
                    fontSize = 16.sp, lineHeight = 22.sp) },
                    onClick = {
                        saveError = !VoltageSocPreferences.select(context, option.id)
                        menu = false
                    })
            }
        }
    }
    if (saveError) LText("Izbor nije sačuvan. Pokušaj ponovo.", color = modelAmber, fontSize = 14.sp, lineHeight = 20.sp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText("Napon grupe (V)", color = modelMuted, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { expandedChart = true }) {
            LIcon(Icons.Outlined.ZoomIn, null, Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            LText("Uvećaj model", fontSize = 14.sp)
        }
    }
    VoltageSocModelChart(model, voltage, chartHeight = 184.dp)
    LText(model.limitations, color = modelAmber, fontSize = 14.sp, lineHeight = 20.sp)
    LText(model.description, fontSize = 14.sp, lineHeight = 20.sp)
    LText("Izvor: ${model.source}", color = modelMuted, fontSize = 12.sp, lineHeight = 18.sp)
    LText(model.sourceDetails, color = modelMuted, fontSize = 12.sp, lineHeight = 18.sp)
    if (expandedChart) VoltageSocModelDialog(model, voltage) { expandedChart = false }
}

@Composable private fun VoltageSocModelDialog(model: VoltageSocModel, voltage: Double?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF04111D)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LText("Kriva izabranog modela", color = Color.White, fontSize = 20.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        LIcon(Icons.Outlined.Close, "Zatvori uvećani model", tint = Color.White)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val chartHeight = (maxHeight * .5f).coerceIn(260.dp, 440.dp)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LText(model.title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = modelCyan)
                        LText("Poredbena kriva druge ćelije. Nije CATL kalibracija ovog vozila.",
                            color = modelAmber, fontSize = 14.sp, lineHeight = 20.sp)
                        if (VoltageSocModels.estimate(voltage, model.id) == null) {
                            LText(if (voltage == null) "Naponska procena nije dostupna: nema svežeg očitavanja."
                                else "Očitani napon je van raspona modela; nema ekstrapolacije.",
                                color = modelAmber, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        LText("Napon grupe (V)", color = modelMuted, fontSize = 14.sp)
                        VoltageSocModelChart(model, voltage, chartHeight = chartHeight, interactive = true)
                        LText("Žuto označava naponsku procenu iz očitavanja, kada je dostupna. Bela oznaka pokazuje izabranu izvornu tačku modela.",
                            color = modelMuted, fontSize = 14.sp, lineHeight = 20.sp)
                        LText(model.limitations, color = modelAmber, fontSize = 14.sp, lineHeight = 20.sp)
                        LText(model.description, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                        LText("Izvor: ${model.source}\n${model.sourceDetails}", color = modelMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun VoltageSocModelChart(
    model: VoltageSocModel,
    voltage: Double?,
    chartHeight: Dp,
    interactive: Boolean = false
) {
    val points = model.points
    val low = floor(points.minOf { it.volts } * 10) / 10
    val high = ceil(points.maxOf { it.volts } * 10) / 10
    val estimate = VoltageSocModels.estimate(voltage, model.id)
    fun n(v: Double, digits: Int = 1) = "%.${digits}f".format(AppLanguage.locale, v)
    var selectedIndex by remember(model.id) { mutableIntStateOf(points.indices.minBy { abs(points[it].percent - (estimate ?: 50.0)) }) }
    val selected = points[selectedIndex]
    val axisStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisLabels = listOf(n(high), n((high + low) / 2), n(low))
    val axisSizes = axisLabels.map { measurer.measure(it, style = axisStyle).size }
    val gutter = with(density) { axisSizes.maxOf { it.width }.toDp() } + 10.dp
    val labelHalfHeight = axisSizes.maxOf { it.height } / 2f
    if (interactive) {
        LText("Tačka modela · dodirni krivu", color = modelMuted, fontSize = 14.sp, lineHeight = 20.sp)
        LText("${n(selected.percent)} % · ${n(selected.volts, 3)} V", color = Color.White,
            fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(gutter).height(chartHeight).padding(end = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            axisLabels.forEach { LText(it, color = modelMuted, style = axisStyle, maxLines = 1, softWrap = false) }
        }
        val inspection = if (interactive) Modifier.pointerInput(model.id) {
            detectTapGestures { position ->
                val inset = 5.dp.toPx()
                val percent = ((position.x - inset) / (size.width - inset * 2) * 100).coerceIn(0f, 100f)
                selectedIndex = points.indices.minBy { abs(points[it].percent - percent) }
            }
        }.semantics {
            contentDescription = UiStrings.text("Izaberi tačku SOC modela")
            stateDescription = UiStrings.text("${n(selected.percent)} procenata, ${n(selected.volts, 3)} volti")
            progressBarRangeInfo = ProgressBarRangeInfo(selected.percent.toFloat(), points.first().percent.toFloat()..points.last().percent.toFloat())
            setProgress { percent ->
                selectedIndex = points.indices.minBy { abs(points[it].percent - percent) }
                true
            }
        } else Modifier.semantics { contentDescription = UiStrings.text("Kriva napona prema SOC-u: ${model.title}") }
        Canvas(Modifier.weight(1f).height(chartHeight).background(Color(0xFF071826)).then(inspection)) {
            val inset = 5.dp.toPx()
            fun x(soc: Double) = inset + (soc / 100 * (size.width - inset * 2)).toFloat()
            fun y(v: Double) = labelHalfHeight + ((high - v) / (high - low) * (size.height - labelHalfHeight * 2)).toFloat()
            for (f in listOf(0f, .5f, 1f)) {
                val lineY = labelHalfHeight + (size.height - labelHalfHeight * 2) * f
                drawLine(Color(0xFF244252), Offset(inset, lineY), Offset(size.width - inset, lineY))
                drawLine(Color(0xFF244252), Offset(x(f * 100.0), labelHalfHeight), Offset(x(f * 100.0), size.height - labelHalfHeight))
            }
            points.zipWithNext().forEach { (a, b) ->
                drawLine(modelCyan, Offset(x(a.percent), y(a.volts)), Offset(x(b.percent), y(b.volts)), 2.dp.toPx())
            }
            if (points.size <= 15) points.forEach {
                drawCircle(modelCyan, 3.dp.toPx(), Offset(x(it.percent), y(it.volts)))
            }
            if (estimate != null && voltage != null) {
                drawLine(modelAmber.copy(alpha = .6f), Offset(x(estimate), labelHalfHeight), Offset(x(estimate), size.height - labelHalfHeight))
                drawCircle(modelAmber, 5.dp.toPx(), Offset(x(estimate), y(voltage)))
            }
            if (interactive) {
                drawLine(Color.White.copy(alpha = .6f), Offset(x(selected.percent), labelHalfHeight), Offset(x(selected.percent), size.height - labelHalfHeight))
                drawCircle(Color.White, 4.dp.toPx(), Offset(x(selected.percent), y(selected.volts)))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = gutter), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0%", "50%", "100%").forEach { LText(it, color = modelMuted, style = axisStyle) }
    }
    LText("SOC modela (%)", color = modelMuted, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
}

