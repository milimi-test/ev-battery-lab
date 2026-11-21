package rs.battery.lab

import kotlin.math.abs
import kotlin.math.max

internal data class ChartPoint(val time: Long, val value: Double?)
internal data class ChartBounds(val low: Double, val high: Double)

internal fun chartBounds(points: List<ChartPoint>): ChartBounds {
    val values = points.mapNotNull { it.value?.takeIf(Double::isFinite) }
    if (values.isEmpty()) return ChartBounds(0.0, 1.0)
    val lo = values.minOrNull()!!
    val hi = values.maxOrNull()!!
    val pad = max((hi - lo) * .08, max(abs(lo) * .002, .05))
    return ChartBounds(if (lo >= 0) max(0.0, lo - pad) else lo - pad, hi + pad)
}

/** No interpolation or carrying a value across a recording gap. */
internal fun nearestChartPoint(points: List<ChartPoint>, time: Long, tolerance: Long = 30_000): ChartPoint? {
    if (points.isEmpty()) return null
    val index = points.binarySearchBy(time) { it.time }
    val candidates = if (index >= 0) listOf(points[index]) else {
        val insertion = -index - 1
        listOfNotNull(points.getOrNull(insertion - 1), points.getOrNull(insertion))
    }
    val nearest = candidates.minByOrNull { abs(it.time - time) } ?: return null
    return nearest.takeIf { abs(it.time - time) <= tolerance && it.value?.isFinite() == true }
}

/** Preserve explicit missing readings and >60 s gaps as separate drawing segments. */
internal fun chartSegments(points: List<ChartPoint>, gapMs: Long = 60_000): List<List<ChartPoint>> {
    val result = mutableListOf<List<ChartPoint>>()
    var segment = mutableListOf<ChartPoint>()
    points.forEach { point ->
        if (point.value?.isFinite() != true || segment.lastOrNull()?.let { point.time - it.time > gapMs } == true) {
            if (segment.isNotEmpty()) result.add(segment)
            segment = mutableListOf()
        }
        if (point.value?.isFinite() == true) segment.add(point)
    }
    if (segment.isNotEmpty()) result.add(segment)
    return result
}

/** Retain min/max excursions when a long recording is wider than the screen. */
internal fun chartEnvelope(points: List<ChartPoint>, buckets: Int = 500): List<ChartPoint> {
    if (points.size <= buckets * 4 || buckets < 1) return points
    val chunk = (points.size + buckets - 1) / buckets
    return points.chunked(chunk).flatMap { block ->
        listOfNotNull(block.firstOrNull(), block.minByOrNull { it.value ?: Double.POSITIVE_INFINITY },
            block.maxByOrNull { it.value ?: Double.NEGATIVE_INFINITY }, block.lastOrNull())
            .distinctBy { it.time }.sortedBy { it.time }
    }
}
