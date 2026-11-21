package rs.battery.lab

/** App history begins when logging was enabled; it is not a factory lifetime charge count. */
internal data class ChargeEventCounts(
    val observedSessions: Int,
    val inferredGaps: Int,
    val gapsOverlappingObserved: Int,
    val minimumDistinctEvents: Int,
    val acSessions: Int = 0,
    val dcSessions: Int = 0,
    val unknownSessions: Int = 0
)

internal fun countChargeEvents(
    sessions: List<ChargeSession>,
    gaps: List<ChargeGapRecord>
): ChargeEventCounts {
    val observed = sessions.associateBy { it.id }.values.toList()
    val inferred = gaps.distinctBy { it.id }
    // A gap's before/after snapshots bound an unknown period. A live charging session near
    // reconnect can be the same event; count that gap only once with the observed session.
    val overlap = inferred.count { gap -> observed.any { session ->
        val end = session.endedAtMillis ?: session.startedAtMillis + session.durationMillis
        session.startedAtMillis <= gap.afterAt + 10 * 60_000L &&
            end >= gap.beforeAt - 10 * 60_000L
    } }
    return ChargeEventCounts(observed.size, inferred.size, overlap,
        observed.size + inferred.size - overlap,
        observed.count { it.chargeKind == "ac" }, observed.count { it.chargeKind == "dc" },
        observed.count { it.chargeKind != "ac" && it.chargeKind != "dc" })
}

/** Classify only while power flows into a stationary vehicle. A low-power DC charge stays
 * unknown until stronger evidence arrives. The e-208 OBC is limited to 11 kW AC. */
internal fun observedChargeKind(reading: BmsReading): String {
    val power = reading.powerKw ?: return "unknown"
    if (!power.isFinite() || power >= -0.5 || reading.chargeCableConnected == false) return "unknown"
    if (reading.speedAt != null && reading.timestamp - reading.speedAt in 0..30_000 &&
        (reading.speedKmh ?: 0.0) > 1.0) return "unknown"
    val stationary = reading.speedAt?.let { reading.timestamp - it in 0..30_000 } == true &&
        reading.speedKmh?.let { it.isFinite() && it in 0.0..1.0 } == true
    if (reading.chargeCableConnected != true && !stationary) return "unknown"
    val chargerFresh = reading.chargerAt?.let { reading.timestamp - it in 0..30_000 } == true
    if (chargerFresh && reading.gridVoltage?.let { it in 80.0..300.0 } == true) return "ac"
    if (chargerFresh && reading.chargeCableConnected == true && power < -12.0) return "dc"
    return "unknown"
}
