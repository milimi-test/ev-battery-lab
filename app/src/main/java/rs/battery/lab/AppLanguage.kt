package rs.battery.lab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Image as MaterialImage
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

/** Language is global; vehicle histories and protocol/persistence keys are never translated. */
internal object AppLanguage {
    const val PREFS = "app_language"
    var code by mutableStateOf("en")
        private set
    val locale: Locale get() = if (code == "sr") Locale.forLanguageTag("sr-Latn-RS") else Locale.UK
    fun initialize(context: Context) {
        code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("code", "en")
            .takeIf { it == "sr" } ?: "en"
        UiStrings.load(context)
        Locale.setDefault(locale)
    }
    fun select(context: Context, value: String): Boolean {
        require(value in setOf("en", "sr"))
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", value).commit()) return false
        code = value
        Locale.setDefault(locale)
        return true
    }
}

/** Presentation-only catalogue, including templates from legacy Serbian messages.
 * Matching is anchored. Captured numbers, names and IDs stay intact; nested UI labels are translated.
 * Never call this for OBD commands, stored keys, numeric parsing or routing decisions. */
internal object UiStrings {
    private data class Template(val regex: Regex, val target: String, val slots: List<String>)
    private var loaded = false
    private var exact: Map<String, String> = emptyMap()
    private var templates: List<Template> = emptyList()
    private var phrases: Regex? = null
    private val cache = LruCache<String, String>(4096)
    private val slot = Regex("\\{(\\d+)\\}")
    @Synchronized fun load(context: Context) {
        if (loaded) return
        val json = JSONObject(context.assets.open("i18n/en.json").bufferedReader().use { it.readText() })
        install(json.keys().asSequence().associateWith { json.getString(it) })
        loaded = true
    }
    internal fun install(entries: Map<String, String>) {
        exact = entries.filterKeys { !slot.containsMatchIn(it) }
        templates = entries.filterKeys { slot.containsMatchIn(it) }.entries
            .sortedByDescending { slot.replace(it.key, "").length }.map { (source, target) ->
                val matches = slot.findAll(source).toList()
                val pattern = buildString {
                    append("\\A"); var end = 0
                    matches.forEach { m -> append(Regex.escape(source.substring(end, m.range.first))); append("(.*?)"); end = m.range.last + 1 }
                    append(Regex.escape(source.substring(end))); append("\\z")
                }
                Template(Regex(pattern, RegexOption.DOT_MATCHES_ALL), target, matches.map { it.groupValues[1] })
            }
        // One replacement pass prevents translating words introduced by the English result.
        val literals = exact.keys.filter { it.length >= 3 }.sortedByDescending { it.length }
        phrases = if (literals.isEmpty()) null else Regex("(?<![\\p{L}\\p{N}_])(?:" + literals.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}_])")
        cache.evictAll()
    }
    fun text(source: String): String {
        if (AppLanguage.code == "sr" || source.isBlank() || !loaded) return source
        cache.get(source)?.let { return it }
        return translate(source, 0).also { cache.put(source, it) }
    }
    private fun translate(source: String, depth: Int): String {
        exact[source]?.let { return it }
        if (depth > 4 || source.length > 16000) return source
        for (template in templates) {
            val match = template.regex.matchEntire(source) ?: continue
            val args = template.slots.mapIndexed { i, key -> key to match.groupValues[i + 1] }.toMap()
            return slot.replace(template.target) { m ->
                args[m.groupValues[1]]?.let { if (it == source) it else translate(it, depth + 1) } ?: m.value
            }
        }
        // Compound cards and multi-line reports are assembled from separately catalogued messages.
        if ('\n' in source) return source.split('\n').joinToString("\n") { translate(it, depth + 1) }
        for (separator in listOf(" • ", " · ")) if (separator in source)
            return source.split(separator).joinToString(separator) { translate(it, depth + 1) }
        return phrases?.replace(source) { exact[it.value] ?: it.value } ?: source
    }
}

@Composable internal fun LanguageOptions() {
    val context = LocalContext.current
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LText("Language / Jezik", color = Color.White, style = MaterialTheme.typography.titleMedium)
        listOf("en" to "English", "sr" to "Srpski").forEach { (code, label) ->
            OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                if (AppLanguage.select(context, code)) {
                    var base = context
                    while (base is ContextWrapper && base !is Activity) base = base.baseContext
                    (base as? Activity)?.let { activity ->
                        activity.intent.putExtra("skip_auto_connect", true)
                        activity.recreate() // Recreate native map/WebView labels without stopping the OBD service.
                    }
                } else error = true
            }) { RadioButton(selected = AppLanguage.code == code, onClick = null); Spacer(Modifier.width(8.dp)); MaterialText(label) }
        }
        LText("Izbor jezika važi za sva vozila. Snimanje i istorija ostaju sačuvani.",
            color = Color(0xFFB0C3D0), fontSize = 13.sp, lineHeight = 19.sp)
        if (error) LText("Izbor nije sačuvan. Pokušaj ponovo.", color = Color(0xFFFF6471), fontSize = 13.sp)
    }
}

@Composable internal fun LText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current) {
    MaterialText(UiStrings.text(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily,
        letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style)
}
@Composable internal fun LIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) = MaterialIcon(imageVector, contentDescription?.let(UiStrings::text), modifier, tint)
@Composable internal fun LIcon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) = MaterialIcon(painter, contentDescription?.let(UiStrings::text), modifier, tint)
@Composable internal fun LImage(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center, contentScale: ContentScale = ContentScale.Fit, alpha: Float = 1f,
    colorFilter: ColorFilter? = null) = MaterialImage(painter, contentDescription?.let(UiStrings::text), modifier, alignment, contentScale, alpha, colorFilter)
internal fun Canvas.drawUiText(text: String, x: Float, y: Float, paint: Paint) = drawText(UiStrings.text(text), x, y, paint)
internal fun Paint.measureUiText(text: String): Float = measureText(UiStrings.text(text))
