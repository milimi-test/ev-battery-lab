package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The same persisted arrival reserve is used for both range circles and route warnings. */
internal object RangePreferences {
    const val DEFAULT_RESERVE_SOC = 20
    const val MIN_RESERVE_SOC = 5
    const val MAX_RESERVE_SOC = 50
    const val RESERVE_STEP = 5
    private const val PREFS = "battery_lab"
    private const val RESERVE_KEY = "range_reserve_soc"
    private val lock = Any()
    private val currentReserveSoc = MutableStateFlow(DEFAULT_RESERVE_SOC)
    val reserveSoc: StateFlow<Int> = currentReserveSoc.asStateFlow()

    fun validReserveSoc(value: Int): Boolean =
        value in MIN_RESERVE_SOC..MAX_RESERVE_SOC && value % RESERVE_STEP == 0

    /** Invalid or older malformed preferences fall back to the original 20% reserve. */
    fun load(context: Context) = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = try { prefs.getInt(RESERVE_KEY, DEFAULT_RESERVE_SOC) }
            catch (_: ClassCastException) { DEFAULT_RESERVE_SOC }
        currentReserveSoc.value = saved.takeIf(::validReserveSoc) ?: DEFAULT_RESERVE_SOC
    }

    /** Call when the user finishes adjusting the control; invalid values leave the setting alone. */
    fun setReserveSoc(context: Context, value: Int): Boolean = synchronized(lock) {
        if (!validReserveSoc(value)) return@synchronized false
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(RESERVE_KEY, value).commit()
        if (saved) currentReserveSoc.value = value
        saved
    }
}
