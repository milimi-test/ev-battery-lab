package rs.battery.lab

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Local, offline visualisation. This activity never opens an OBD connection of its own. */
class PackViewerActivity : ComponentActivity() {
    private lateinit var browser: WebView
    private var ready = false
    private var lastPayload: String? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        AppLanguage.initialize(newBase)
        super.attachBaseContext(VehicleDataContext(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 15, 23)
        window.navigationBarColor = Color.rgb(8, 15, 23)
        if (getSharedPreferences("battery_lab", MODE_PRIVATE).getBoolean("keep_screen_on", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        browser = WebView(this)
        browser.setBackgroundColor(Color.rgb(8, 15, 23))
        browser.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url
                val name = uri?.path?.removePrefix("/") ?: ""
                val allowed = setOf("pack.html", "pack.css", "pack.js", "positions.js", "i18n.js", "i18n-en.json", "original.png", "module-real.jpg", "module-top.jpg")
                if (uri?.host != "appassets.androidplatform.net" || name !in allowed) {
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                val mime = when (name.substringAfterLast('.')) {
                    "html" -> "text/html"
                    "css" -> "text/css"
                    "js" -> "application/javascript"
                    "json" -> "application/json"
                    "png" -> "image/png"
                    else -> "image/jpeg"
                }
                return WebResourceResponse(mime, "UTF-8", assets.open(if (name == "i18n-en.json") "i18n/en.json" else "pack/$name"))
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") return false
                if (uri.scheme == "app" && uri.host == "close") finish()
                else if (uri.scheme == "app" && uri.host == "stop-and-exit") AppExit.stopAndExit(this@PackViewerActivity)
                else if (uri.scheme == "https" && uri.host != "appassets.androidplatform.net") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) { ready = true; lastPayload = null; publish() }
        }
        LastReadingStore.load(this)
        setContentView(browser)
        val mode = intent.getIntExtra("mode", 0).coerceIn(0, 2)
        val module = intent.getIntExtra("module", 1).coerceIn(1, 18)
        browser.loadUrl("https://appassets.androidplatform.net/pack.html?native=1&mode=$mode&module=$module&lang=${AppLanguage.code}")
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { publish(); delay(3_000) } } }
    }

    private fun publish() {
        if (!ready) return
        val now = System.currentTimeMillis()
        val live = ObdBus.reading.value?.takeIf { ObdBus.serviceRunning &&
            now - ObdBus.confirmedAt.value in 0..30_000 && now - it.timestamp in 0..120_000 }
        val reading = live ?: LastReadingStore.reading.value
        val payload = "window.setPackReading(${packReadingJson(reading)}, ${live == null && reading != null});"
        if (payload != lastPayload) { browser.evaluateJavascript(payload, null); lastPayload = payload }
    }

    override fun onDestroy() { ready = false; browser.stopLoading(); browser.destroy(); super.onDestroy() }
    override fun onPause() { browser.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::browser.isInitialized) browser.onResume() }
}

/** Transport preserves timestamps; the viewer independently expires each sensor family. */
internal fun packReadingJson(r: BmsReading?): JSONObject {
    if (r == null) return JSONObject()
    return JSONObject().apply {
        put("timestamp_ms", r.timestamp)
        put("soc_percent", r.soc); put("soh_percent", r.soh)
        put("cell_voltages_v", JSONArray(r.cellVoltages)); put("cell_voltages_at_ms", r.cellVoltagesAt)
        put("probe_temperatures_c", JSONArray(r.probeTemperatures)); put("probe_temperatures_at_ms", r.probeTemperaturesAt)
        put("cell_soh_percent", JSONArray(r.cellSoh)); put("cell_soh_at_ms", r.cellSohAt)
        put("battery_temp_avg_c", r.tempAvg)
        put("motor_temp_c", r.motorTemp); put("obc_temp_c", r.obcTemp)
        put("outside_temp_c", r.outsideTemp); put("motor_coolant_temp_c", r.motorCoolantTemp)
        put("traction_coolant_temp_c", r.tractionCoolantTemp); put("heating_coolant_temp_c", r.heatingCoolantTemp)
        put("dcdc_temp_c", r.dcdcTemp); put("inverter_temp_c", r.inverterTemp)
        put("vcu_extra_at_ms", r.vcuExtraAt); put("inverter_at_ms", r.inverterAt)
    }
}
