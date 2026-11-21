package rs.battery.lab

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

internal enum class VehicleProtocol { STELLANTIS_ECMP, ZOE_PHASE1, ZOE_PHASE2 }

internal data class VehicleProfile(val id: String, val brand: String, val model: String,
    val variant: String, val image: Int, val protocol: VehicleProtocol = VehicleProtocol.STELLANTIS_ECMP,
    val nominalUsableKwh: Double = 46.3, val dcCapable: Boolean = true, val photoKey: String = "") {
    val name: String get() = "$brand $model"
    val isZoe: Boolean get() = protocol != VehicleProtocol.STELLANTIS_ECMP
    val seriesGroups: Int get() = if (isZoe) 96 else 108
    val protocolLabel: String get() = when (protocol) {
        VehicleProtocol.STELLANTIS_ECMP -> "Stellantis e-CMP · 50 kWh"
        VehicleProtocol.ZOE_PHASE1 -> "Zoe faza 1 · CAN 11-bit"
        VehicleProtocol.ZOE_PHASE2 -> "Zoe faza 2 · CAN 29-bit"
    }
}

internal object VehicleProfiles {
    const val PREFS = "vehicle_selection"
    val all = listOf(
        VehicleProfile("peugeot_e208_50", "Peugeot", "e-208", "50 kWh · 2020–2023", R.drawable.e208_home),
        VehicleProfile("opel_corsa_e_50", "Opel", "Corsa-e", "50 kWh · 2020–2023", R.drawable.corsa_e_home, photoKey = "corsa_e_home"),
        VehicleProfile("peugeot_e2008_50", "Peugeot", "e-2008", "50 kWh · prva e-CMP baterija", R.drawable.e2008_home, photoKey = "e2008_home"),
        VehicleProfile("opel_mokka_e_50", "Opel", "Mokka-e", "50 kWh · prva e-CMP baterija", R.drawable.mokka_e_home, photoKey = "mokka_e_home"),
        VehicleProfile("citroen_ec4_50", "Citroën", "ë-C4", "50 kWh · prva e-CMP baterija", R.drawable.ec4_home, photoKey = "ec4_home"),
        VehicleProfile("ds3_etense_50", "DS", "3 Crossback E-Tense", "50 kWh · pre baterije 54 kWh", R.drawable.ds3_home, photoKey = "ds3_home"),
        zoe("q210_22", "Q210", "22 kWh · faza 1", 22.0, false),
        zoe("r240_22", "R240", "22 kWh · faza 1", 22.0, false),
        zoe("q90_41", "Q90 ZE40", "41 kWh · faza 1", 41.0, false),
        zoe("r75_41", "R75 ZE40", "41 kWh · faza 1", 41.0, false),
        zoe("r90_41", "R90 ZE40", "41 kWh · faza 1", 41.0, false),
        zoe("r110_41_ph1", "R110 ZE40", "41 kWh · faza 1 · pre redizajna", 41.0, false),
        zoe("r110_41_ph2", "R110 ZE40", "41 kWh · faza 2 · redizajn od 2019", 41.0, true),
        zoe("r110_52", "R110 ZE50", "52 kWh · faza 2", 52.0, true),
        zoe("r135_52", "R135 ZE50", "52 kWh · faza 2", 52.0, true)
    )
    private fun zoe(id: String, motor: String, variant: String, kwh: Double, phase2: Boolean) =
        VehicleProfile("renault_zoe_$id", "Renault", "Zoe $motor", variant,
            if (phase2) R.drawable.zoe_phase2_home else R.drawable.zoe_phase1_home,
            if (phase2) VehicleProtocol.ZOE_PHASE2 else VehicleProtocol.ZOE_PHASE1,
            kwh, phase2 && kwh == 52.0, if (phase2) "zoe_phase2_home" else "zoe_phase1_home")
    fun selected(context: Context): VehicleProfile {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("selected", null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }
    fun current(context: Context): VehicleProfile {
        var base = context
        while (base is ContextWrapper) {
            if (base is VehicleDataContext) return base.profile
            base = base.baseContext
        }
        return selected(context)
    }
    fun select(context: Context, profile: VehicleProfile): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("selected", profile.id).commit()

    /** Switches in-memory caches only. The original e-208 files remain at their existing paths. */
    fun reload(context: Context) {
        check(!ObdBus.serviceRunning)
        GpsTracker.forgetHistory()
        ChartHistory.clear()
        VoltageSocCalibration.reset()
        RecordingControl.reload(context)
        ObdBus.reading.value = null
        ObdBus.samples.value = emptyList()
        ObdBus.confirmedAt.value = 0L
        ObdBus.gpsPaused.value = false
        ObdBus.status.value = "Izabran ${current(context).name} · izaberi adapter i poveži"
        LastReadingStore.load(context)
        TripMonitor.reload(context)
        ChargeMonitor.reload(context)
        ChargeGapMonitor.reload(context)
        BatteryHistory.reload(context)
        ChargerMeterStore.reload(context)
        WeatherTracker.reset()
    }
}

/** Immutable profile binding also keeps late IO writes in the original vehicle's directory. */
internal class VehicleDataContext(base: Context, val profile: VehicleProfile = VehicleProfiles.selected(base)) : ContextWrapper(base) {
    override fun getFilesDir(): File = if (profile == VehicleProfiles.all.first()) super.getFilesDir()
        else File(super.getFilesDir(), "vehicles/${profile.id}").apply { mkdirs() }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(if (name == VehicleProfiles.PREFS || name == AppLanguage.PREFS || profile == VehicleProfiles.all.first()) name
            else "${profile.id}_$name", mode)

    override fun getApplicationContext(): Context = VehicleDataContext(baseContext.applicationContext, profile)
}
