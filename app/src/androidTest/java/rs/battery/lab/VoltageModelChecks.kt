package rs.battery.lab

import android.content.Context
import kotlin.math.abs

/** Source-point, boundary, ambiguity and persisted-selection regressions; no vehicle needed. */
internal fun checkVoltageModels(context: Context) {
    val lg = checkNotNull(VoltageSocModels.byId(VoltageSocModels.DEFAULT_ID))
    val calb = checkNotNull(VoltageSocModels.byId("calb-nmc532"))
    val nmc622 = checkNotNull(VoltageSocModels.byId("nmc622-lp57"))
    check(VoltageSocModels.all.map { it.id }.distinct().size == VoltageSocModels.all.size)
    check(lg.points.size == 101 && calb.points.size == 26 && nmc622.points.size == 4)

    // Protect the prior default calculation and three independent published data scales.
    check(abs(checkNotNull(VoltageSocModels.estimate(3.604)) - 35.85185185185185) < 1e-9)
    check(abs(checkNotNull(VoltageSocModels.estimate(4.052)) - 82.97) < 1e-9)
    check(VoltageSocModels.estimate(2.4977) == 0.0)
    check(VoltageSocModels.estimate(4.1929) == 100.0)
    check(VoltageSocModels.estimate(3.6059, calb.id) == 27.555410)
    check(VoltageSocModels.estimate(4.0576, calb.id) == 79.876376)
    check(VoltageSocModels.estimate(2.9810, calb.id) == 0.0)
    check(VoltageSocModels.estimate(4.3123, calb.id) == 100.0)
    check(VoltageSocModels.estimate(3.36, nmc622.id) == 5.0)
    check(VoltageSocModels.estimate(3.57, nmc622.id) == 30.0)
    check(VoltageSocModels.estimate(3.94, nmc622.id) == 80.0)
    check(VoltageSocModels.estimate(4.18, nmc622.id) == 100.0)
    // The sparse model must interpolate in actual SOC coordinates, not point indices.
    check(abs(checkNotNull(VoltageSocModels.estimate(3.755, nmc622.id)) - 55.0) < 1e-9)
    check(VoltageSocModels.estimate(3.604, lg.id) != VoltageSocModels.estimate(3.604, calb.id))

    for (model in VoltageSocModels.all) {
        for (voltage in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            model.points.first().volts - 0.000001, model.points.last().volts + 0.000001)) {
            check(VoltageSocModels.estimate(voltage, model.id) == null)
        }
    }
    check(VoltageSocModels.estimate(4.2, lg.id) == null)
    check(VoltageSocModels.estimate(4.2, calb.id) != null)
    check(VoltageSocModels.estimate(4.2, nmc622.id) == null)
    check(VoltageSocModels.estimate(3.7, "unknown-model") == null)
    check(VoltageSocModels.byId("unknown-model") == null)

    // Reject voltage plateaus/reversals; a multi-valued inverse cannot be made precise.
    val invalidCurves = listOf(
        listOf(VoltageSocPoint(0.0, 3.0)),
        listOf(VoltageSocPoint(0.0, 3.0), VoltageSocPoint(100.0, 3.0)),
        listOf(VoltageSocPoint(0.0, 4.0), VoltageSocPoint(100.0, 3.0)),
        listOf(VoltageSocPoint(10.0, 3.0), VoltageSocPoint(10.0, 4.0)),
        listOf(VoltageSocPoint(0.0, 3.0), VoltageSocPoint(101.0, 4.0)),
        listOf(VoltageSocPoint(0.0, 3.0), VoltageSocPoint(100.0, Double.NaN))
    )
    for (points in invalidCurves) check(runCatching { lg.copy(points = points) }.isFailure)

    val prefs = context.applicationContext.getSharedPreferences("battery_lab", Context.MODE_PRIVATE)
    val key = "voltage_soc_reference_model"
    val previous = prefs.getString(key, null)
    try {
        check(prefs.edit().remove(key).commit())
        VoltageSocPreferences.load(context)
        check(VoltageSocPreferences.selectedId.value == VoltageSocModels.DEFAULT_ID)
        check(VoltageSocPreferences.select(context, calb.id))
        check(VoltageSocPreferences.selectedId.value == calb.id)
        check(prefs.getString(key, null) == calb.id)
        VoltageSocPreferences.load(context)
        check(VoltageSocPreferences.selectedId.value == calb.id)
        check(!VoltageSocPreferences.select(context, "unknown-model"))
        check(VoltageSocPreferences.selectedId.value == calb.id)
        check(prefs.getString(key, null) == calb.id)
        check(VoltageSocPreferences.select(context, nmc622.id))
        check(VoltageSocPreferences.selectedId.value == nmc622.id)
        check(prefs.getString(key, null) == nmc622.id)
        check(prefs.edit().putString(key, "removed-reference").commit())
        VoltageSocPreferences.load(context)
        check(VoltageSocPreferences.selectedId.value == VoltageSocModels.DEFAULT_ID)
        check(prefs.edit().putInt(key, 42).commit())
        VoltageSocPreferences.load(context)
        check(VoltageSocPreferences.selectedId.value == VoltageSocModels.DEFAULT_ID)
        check(VoltageSocPreferences.select(context, calb.id))
        check(prefs.getString(key, null) == calb.id)
    } finally {
        val edit = prefs.edit()
        if (previous == null) edit.remove(key) else edit.putString(key, previous)
        check(edit.commit())
        VoltageSocPreferences.load(context)
    }
}
