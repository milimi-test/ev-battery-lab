package rs.battery.lab

import android.content.Context

/** The theoretical NMC cell scale, dashboard SOC and optional rested D862 calibration are independent. */
internal data class PackSocDisplay(
    val meanGroupVoltageV: Double?,
    val voltageSocPercent: Double?,
    val voltageReason: String,
    val voltageEstimateAtMillis: Long?,
    val voltageAnchorCount: Int,
    val candidateD862: Double?,
    val candidateD863: Double?,
    val candidateD413: Double?,
    val calibratedD862Percent: Double? = null,
    val voltageSource: String = "",
    val modelId: String = VoltageSocModels.DEFAULT_ID
)

internal fun packSocDisplay(context: Context, reading: BmsReading?, nowMillis: Long = System.currentTimeMillis(),
                            modelId: String = VoltageSocModels.DEFAULT_ID): PackSocDisplay {
    val vehicle = VehicleProfiles.current(context)
    val groups = vehicle.seriesGroups
    val model = VoltageSocModels.byId(modelId) ?: VoltageSocModels.all.first()
    if (reading == null || nowMillis - reading.timestamp !in 0..120_000)
        return PackSocDisplay(null, null, "Čeka sveže BMS očitavanje.", null, 0, null, null, null, modelId = model.id)

    val scalarFresh = reading.bmsExtraAt?.let { nowMillis - it in 0..120_000 } == true
    val arrayFresh = reading.cellVoltagesAt?.let { nowMillis - it in 0..120_000 } == true
    val scalar = reading.cellAverageVoltage?.takeIf { scalarFresh && it.isFinite() && it in 2.0..4.5 }
    val array = reading.cellVoltages.takeIf { arrayFresh && it.size == groups && it.all { v -> v.isFinite() && v in 2.0..4.5 } }
    val packMean = reading.voltage?.div(groups.toDouble())?.takeIf { it.isFinite() && it in 2.0..4.5 }
    // D43D and D440 arrive in different polling cycles. Use the newest complete cell
    // measurement for both the displayed voltage and the interpolated percentage.
    val useArray = array != null && (scalar == null || reading.cellVoltagesAt!! > reading.bmsExtraAt!!)
    val mean = if (useArray) array!!.average() else scalar ?: packMean
    val source = when {
        useArray -> "prosek $groups grupa"
        scalar != null -> "srednji napon BMS"
        else -> "napon paketa ÷ $groups"
    }
    val measuredAt = when {
        useArray -> reading.cellVoltagesAt
        scalar != null -> reading.bmsExtraAt
        packMean != null -> reading.timestamp
        else -> null
    }
    if (vehicle.isZoe) return PackSocDisplay(mean, null,
        "Zoe: prikazan je stvarni SOC koji prijavljuje vozilo. Za ovu bateriju nije uključen potvrđen naponski model. Srednji napon: $source. SOH paketa nije SOH pojedinačnih grupa.",
        measuredAt, 0, null, null, null, voltageSource = source, modelId = "")
    val candidatesFresh = reading.socCandidatesAt?.let { nowMillis - it in 0..180_000 } == true
    val calibrated = VoltageSocCalibration.estimate(context, reading, nowMillis)
    val percent = VoltageSocModels.estimate(mean, model.id)
    val reason = when {
        mean == null -> "Čeka se srednji napon grupa ili napon paketa."
        percent == null -> "Napon je van izmerenog opsega izabranog modela; nema ekstrapolacije."
        else -> {
            val condition = when {
                reading.chargeCableConnected == true -> "Punjenje menja napon."
                reading.current == null -> "Opterećenje nije poznato."
                kotlin.math.abs(reading.current) > 5 -> "Pod opterećenjem procena može odstupati."
                else -> "Najpouzdanije posle mirovanja."
            }
            "Okvirno po naponu ($source). Model: ${model.title}. $condition"
        }
    }
    return PackSocDisplay(mean, percent, reason, measuredAt, calibrated.anchorCount,
        reading.socCandidateD862.takeIf { candidatesFresh },
        reading.socCandidateD863.takeIf { candidatesFresh },
        reading.socCandidateD413.takeIf { candidatesFresh }, calibrated.percent, source, model.id)
}

// Preserve the original default-model API for stored-data callers and regression checks.
internal fun theoreticalVoltageSoc(voltage: Double?): Double? = VoltageSocModels.estimate(voltage)
