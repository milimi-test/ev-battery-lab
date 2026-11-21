package rs.battery.lab

import android.content.Context
import kotlin.math.abs

/** Generator interpolation and masked-cell tests also live in build_contours_v019.py --test. */
internal fun checkContours(context: Context) {
    check(ContourPolicy.intervalFor(65.0) == 100)
    check(ContourPolicy.intervalFor(65.001) == 500)
    check(ContourPolicy.intervalFor(240.0) == 500)
    check(ContourPolicy.intervalFor(240.001) == 1000)
    check(ContourPolicy.intervalFor(Double.NaN) == 1000)
    check(ContourPolicy.intervalFor(0.0) == 1000)
    check(ContourPolicy.majorInterval(100) == 500 && ContourPolicy.majorInterval(500) == 1000)
    check(ContourPolicy.hasTerrain(20.9, 43.3))
    check(!ContourPolicy.hasTerrain(18.5, 41.5))
    check(!ContourPolicy.hasTerrain(25.0, 44.0))
    check(!ContourPolicy.hasTerrain(21.0, 48.0))
    check(!ContourPolicy.hasTerrain(Double.NaN, 44.0))
    check(OfflineContours.visibleTiles(17.0, 25.0, 40.0, 48.0, 1000).size == 63)
    check(OfflineContours.visibleTiles(18.0, 19.0, 41.0, 42.0, 100).isEmpty())
    check(OfflineContours.visibleTiles(10.0, 16.0, 44.0, 45.0, 1000).isEmpty())
    check(OfflineContours.visibleTiles(20.0, 20.25, 43.0, 43.25, 100) == listOf(ContourTile(80, 172, 100)))
    // Kopaonik: real contour vertices must agree with independent bilinear terrain sampling.
    // The tolerance includes 0.25-cell simplification and integer-metre source quantization.
    val key = ContourTile(83, 173, 100)
    val detail = OfflineContours.read(context, key)
    check(detail.lineCount > 0 && detail.pointCount > detail.lineCount * 2 && detail.labels.isNotEmpty())
    val checks = detail.labels.take(80).map { label ->
        val lat = key.south + label.y * key.size
        val lon = key.west + label.x * key.size
        abs(checkNotNull(RouteTerrain.height(context, lat, lon)) - label.height)
    }
    check(checks.maxOrNull()!! < 65.0) { "Contour/DEM disagreement: ${checks.maxOrNull()}m" }
    val coarse = OfflineContours.read(context, ContourTile(20, 43, 1000))
    check(coarse.lineCount > 0 && coarse.labels.all { it.height % 1000 == 0 })
    check(OfflineContours.cached(key) === detail)
}
