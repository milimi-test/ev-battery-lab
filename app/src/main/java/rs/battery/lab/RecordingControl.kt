package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Durable user choice for logging. Live OBD readings are independent of this switch. */
internal object RecordingControl {
    private const val PREFS = "battery_lab"
    private const val ENABLED_KEY = "recording_enabled"
    private const val PAUSES_KEY = "recording_pause_intervals"
    private const val MAX_PAUSES = 500
    private val lock = Any()
    val enabled = MutableStateFlow(true)
    private var loaded = false
    private var generation = 0L
    private var pauses = JSONArray()

    fun reload(context: Context) = synchronized(lock) {
        generation++
        loaded = false
        load(context)
    }

    fun load(context: Context) = synchronized(lock) {
        if (loaded) return@synchronized
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean(ENABLED_KEY, true)
        pauses = runCatching { JSONArray(prefs.getString(PAUSES_KEY, "[]")) }.getOrDefault(JSONArray())
        loaded = true
    }

    /** Returns false only if Android failed to persist the choice. */
    fun set(context: Context, on: Boolean): Boolean = synchronized(lock) {
        load(context)
        if (enabled.value == on) return@synchronized true
        val changedAt = System.currentTimeMillis()
        val next = JSONArray()
        for (i in (pauses.length() - MAX_PAUSES + 1).coerceAtLeast(0) until pauses.length()) {
            next.put(pauses.getJSONObject(i))
        }
        if (!on) next.put(JSONObject().put("start", changedAt).put("end", JSONObject.NULL))
        else if (next.length() > 0 && next.getJSONObject(next.length() - 1).isNull("end"))
            next.getJSONObject(next.length() - 1).put("end", changedAt)
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED_KEY, on).putString(PAUSES_KEY, next.toString()).commit()
        if (saved) { pauses = next; generation++; enabled.value = on }
        saved
    }

    /** A token becomes invalid at every mode change, including re-enabling recording. */
    fun token(): Long? = synchronized(lock) { if (enabled.value) generation else null }

    /** Serializes a final write against an off transition. */
    fun withActive(token: Long?, block: () -> Unit): Boolean = synchronized(lock) {
        if (token == null || !enabled.value || token != generation) return@synchronized false
        block()
        true
    }

    /** Prevents charge-gap reconstruction across a period the user chose not to log. */
    fun crossesPause(before: Long, after: Long): Boolean = synchronized(lock) {
        if (before >= after) return@synchronized false
        (0 until pauses.length()).any { i ->
            val pause = pauses.optJSONObject(i) ?: return@any false
            val start = pause.optLong("start", Long.MAX_VALUE)
            val end = if (pause.isNull("end")) Long.MAX_VALUE else pause.optLong("end", Long.MAX_VALUE)
            before < end && after > start
        }
    }

    /** Called after the service has stopped, before deleting its historical files. */
    fun discardHistory(context: Context): Boolean = synchronized(lock) {
        load(context)
        val next = JSONArray()
        if (!enabled.value) next.put(JSONObject().put("start", System.currentTimeMillis()).put("end", JSONObject.NULL))
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PAUSES_KEY, next.toString()).remove("last_bms_for_charge_gap").commit()
        if (saved) { pauses = next; generation++ }
        saved
    }
}
