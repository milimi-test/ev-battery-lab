package rs.battery.lab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A full-cell reference point. Percent belongs to the cited experiment's SOC scale. */
internal data class VoltageSocPoint(val percent: Double, val volts: Double)

internal data class VoltageSocModel(
    val id: String,
    val title: String,
    val description: String,
    val source: String,
    val points: List<VoltageSocPoint>,
    val chemistry: String,
    val temperatureC: Double,
    val limitations: String,
    val sourceDetails: String
) {
    init {
        require(id.isNotBlank() && points.size >= 2)
        require(points.all { it.percent.isFinite() && it.percent in 0.0..100.0 && it.volts.isFinite() && it.volts > 0.0 })
        // Voltage inversion is unambiguous only for a strictly increasing branch.
        // Do not silently sort, smooth, clamp or discard contradictory source points.
        require(points.zipWithNext().all { (a, b) -> b.percent > a.percent && b.volts > a.volts })
    }
}

/** Comparative cell references, never a vehicle BMS calibration or a buffer estimate. */
internal object VoltageSocModels {
    const val DEFAULT_ID = "lg-m50"

    private val lgM50 = VoltageSocModel(
        id = DEFAULT_ID,
        title = "LG M50 · Chen2020",
        description = "Referentna OCV kriva LG M50 na 25 °C, iz modela Chen2020. Dosadašnja podrazumevana skala.",
        source = "https://doi.org/10.1149/1945-7111/ab9050",
        // These 101 values are unchanged from the original PackSocDisplay.kt table.
        // Chen et al., JES 167 (2020) 080534, Table VIII stoichiometric bounds;
        // full-cell U_p(y) - U_n(x) using PyBaMM Chen2020 electrode fits, 1% steps.
        points = doubleArrayOf(
            2.4977, 2.6953, 2.8374, 2.9403, 3.0159, 3.0728, 3.1175, 3.1550, 3.1892, 3.2230,
            3.2577, 3.2926, 3.3255, 3.3542, 3.3777, 3.3961, 3.4105, 3.4221, 3.4320, 3.4409,
            3.4493, 3.4576, 3.4662, 3.4750, 3.4844, 3.4942, 3.5045, 3.5152, 3.5262, 3.5373,
            3.5483, 3.5590, 3.5693, 3.5791, 3.5884, 3.5971, 3.6052, 3.6130, 3.6204, 3.6274,
            3.6343, 3.6411, 3.6477, 3.6545, 3.6613, 3.6682, 3.6754, 3.6829, 3.6908, 3.6991,
            3.7078, 3.7169, 3.7265, 3.7365, 3.7468, 3.7573, 3.7678, 3.7784, 3.7887, 3.7989,
            3.8089, 3.8188, 3.8289, 3.8392, 3.8502, 3.8621, 3.8748, 3.8878, 3.9006, 3.9126,
            3.9236, 3.9338, 3.9434, 3.9527, 3.9619, 3.9713, 3.9808, 3.9907, 4.0008, 4.0111,
            4.0215, 4.0320, 4.0423, 4.0523, 4.0616, 4.0700, 4.0773, 4.0833, 4.0880, 4.0915,
            4.0941, 4.0963, 4.0988, 4.1025, 4.1080, 4.1158, 4.1264, 4.1397, 4.1555, 4.1734,
            4.1929
        ).mapIndexed { percent, volts -> VoltageSocPoint(percent.toDouble(), volts) },
        chemistry = "NMC811 / grafit–silicijum",
        temperatureC = 25.0,
        limitations = "Model druge ćelije; nije potvrđena CATL kriva ovog vozila. Ne koriguje temperaturu, opterećenje, histerezu ni starenje.",
        sourceDetails = "Chen i sar. (2020), JES 167 080534, tabela VIII; PyBaMM Chen2020. " +
            "https://github.com/pybamm-team/PyBaMM/blob/v25.10.2/src/pybamm/input/parameters/lithium_ion/Chen2020.py"
    )

    private val calbNmc532 = VoltageSocModel(
        id = "calb-nmc532",
        title = "CALB NMC532 · GITT",
        description = "Poredbena prizmatična CALB ćelija 58 Ah: 26 izmerenih napona posle odmora tokom GITT pražnjenja. Komora 25 °C; ćelija 24,2–24,4 °C.",
        source = "https://doi.org/10.5281/zenodo.22301764",
        // Original numeric source: 3_Characterization/Cell_4/Cell_4_2-GITT.csv.
        // CSV SHA256: 0cf81f19d22ee272a5ef0a6eb84218e9499b3a944ef7ab352f325c02985ab0b8.
        // Extracted by the authors' MATLAB PlotCharacterizationResults.m, Steps 1-3:
        // https://github.com/robinson-medina/Analysis_Of_NMC_Dataset/blob/
        // eced3d173c758ed91bc2d4f382c056e23d30cd72/matlab_scripts/PlotCharacterizationResults.m
        // Integrate I using per-row delta t and trapezoids. Rest = |I| <= 0.3 A;
        // retain last rest sample before each pulse, starting at the first discharge.
        // Full-charge rest is 100%; minimum cumulative charge over the GITT span is
        // 0% (60.0468212559 Ah discharge depth). Keep only the discharge branch.
        // SOC values rounded to 6 decimal places; source voltages unchanged.
        points = listOf(
            VoltageSocPoint(0.0, 2.9810),
            VoltageSocPoint(3.406630, 3.3391),
            VoltageSocPoint(7.431655, 3.4549),
            VoltageSocPoint(11.456307, 3.4852),
            VoltageSocPoint(15.481425, 3.5147),
            VoltageSocPoint(19.506093, 3.5499),
            VoltageSocPoint(23.530744, 3.5767),
            VoltageSocPoint(27.555410, 3.6059),
            VoltageSocPoint(31.580071, 3.6240),
            VoltageSocPoint(35.604725, 3.6387),
            VoltageSocPoint(39.629367, 3.6530),
            VoltageSocPoint(43.654415, 3.6690),
            VoltageSocPoint(47.679060, 3.6891),
            VoltageSocPoint(51.703740, 3.7185),
            VoltageSocPoint(55.728402, 3.7532),
            VoltageSocPoint(59.753069, 3.8068),
            VoltageSocPoint(63.777693, 3.8601),
            VoltageSocPoint(67.802319, 3.9103),
            VoltageSocPoint(71.826989, 3.9581),
            VoltageSocPoint(75.851752, 4.0098),
            VoltageSocPoint(79.876376, 4.0576),
            VoltageSocPoint(83.901069, 4.1073),
            VoltageSocPoint(87.926120, 4.1548),
            VoltageSocPoint(91.950777, 4.1997),
            VoltageSocPoint(95.975395, 4.2466),
            VoltageSocPoint(100.0, 4.3123)
        ),
        chemistry = "NMC532 / grafit",
        temperatureC = 25.0,
        limitations = "Jedna sveža CALB ćelija, grana pražnjenja; njena SOC skala važi za raspon tog testa. Nije identifikacija CATL ćelije, apsolutni SOC vozila ili merenje bafera.",
        sourceDetails = "Medina i sar. (2026), Cell_4, izvorni GITT podaci, CC BY 4.0. " +
            "https://arxiv.org/abs/2610.23049"
    )

    private val nmc622Lp57 = VoltageSocModel(
        id = "nmc622-lp57",
        title = "NMC622 · 4 tačke C/10",
        description = "Grubo poređenje laboratorijske NMC622 ćelije 5,2 Ah sa elektrolitom LP57 na 25 °C. Samo četiri objavljene tačke pri pražnjenju C/10, povezane pravim linijama.",
        source = "https://doi.org/10.3390/batteries10080275",
        // Geuder et al. (2024), Batteries 10(8), 275, Table 2: LP57 column.
        // These are C/10 discharge voltages, not equilibrium OCV measurements.
        // Do not add a guessed 0% endpoint, or digitize a denser-looking curve.
        points = listOf(
            VoltageSocPoint(5.0, 3.36),
            VoltageSocPoint(30.0, 3.57),
            VoltageSocPoint(80.0, 3.94),
            VoltageSocPoint(100.0, 4.18)
        ),
        chemistry = "NMC622 / grafit, LP57",
        temperatureC = 25.0,
        limitations = "Nije OCV kriva u mirovanju. Veoma gruba interpolacija samo od 5 do 100%; ne prikazuje detaljan oblik krive između tačaka i nije CATL kalibracija.",
        sourceDetails = "Geuder i sar. (2024), Batteries 10(8), 275, tabela 2, kolona LP57; odeljci 2.3 i 2.4.1."
    )

    val all: List<VoltageSocModel> = listOf(lgM50, calbNmc532, nmc622Lp57)

    fun byId(id: String): VoltageSocModel? = all.firstOrNull { it.id == id }

    /** Piecewise-linear inversion, inclusive endpoints, and no extrapolation. */
    fun estimate(voltage: Double?, modelId: String = DEFAULT_ID): Double? {
        if (voltage == null || !voltage.isFinite()) return null
        val points = byId(modelId)?.points ?: return null
        if (voltage !in points.first().volts..points.last().volts) return null
        val index = points.binarySearch { it.volts.compareTo(voltage) }
        if (index >= 0) return points[index].percent
        val high = -index - 1
        val low = high - 1
        val a = points[low]
        val b = points[high]
        return a.percent + (voltage - a.volts) / (b.volts - a.volts) * (b.percent - a.percent)
    }
}

/** The reference choice changes local interpretation only; it never writes to the car. */
internal object VoltageSocPreferences {
    private const val PREFS = "battery_lab"
    private const val KEY = "voltage_soc_reference_model"
    private val mutableSelectedId = MutableStateFlow(VoltageSocModels.DEFAULT_ID)
    val selectedId: StateFlow<String> = mutableSelectedId.asStateFlow()

    @Synchronized fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = try {
            prefs.getString(KEY, null)
        } catch (_: ClassCastException) {
            // A malformed/legacy preference must not prevent opening the dashboard.
            null
        }
        mutableSelectedId.value = stored?.takeIf { VoltageSocModels.byId(it) != null } ?: VoltageSocModels.DEFAULT_ID
    }

    /** True means a valid choice was saved; failed writes leave the current choice intact. */
    @Synchronized fun select(context: Context, id: String): Boolean {
        if (VoltageSocModels.byId(id) == null) return false
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, id).commit()
        if (saved) mutableSelectedId.value = id
        return saved
    }
}
