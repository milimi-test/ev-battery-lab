package rs.battery.lab

import android.content.Context
import android.graphics.Path
import android.util.LruCache
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.*

/** Intervals are vertical metres; the source mesh is 1/512 degree, not a 100m survey. */
internal object ContourPolicy {
    fun intervalFor(widthKm: Double): Int = when {
        !widthKm.isFinite() || widthKm <= 0 -> 1000
        widthKm <= 65 -> 100
        widthKm <= 240 -> 500
        else -> 1000
    }
    fun majorInterval(interval: Int) = if (interval == 100) 500 else 1000
    fun hasTerrain(lon: Int, lat: Int) = lon in 17..24 && lat in 40..47 && !(lon == 18 && lat == 41)
    fun hasTerrain(lon: Double, lat: Double) = lon.isFinite() && lat.isFinite() &&
        lon >= 17 && lon < 25 && lat >= 40 && lat < 48 && hasTerrain(floor(lon).toInt(), floor(lat).toInt())
}

internal data class ContourTile(val x: Int, val y: Int, val interval: Int) {
    val cells get() = if (interval == 100) 4 else 1
    val west get() = x.toDouble() / cells
    val south get() = y.toDouble() / cells
    val size get() = 1.0 / cells
    val path get() = "contours/$interval/${x}_${y}.ctz"
}

internal data class ContourLabel(val height: Int, val x: Float, val y: Float)
internal class ContourGeometry(val minor: Path, val major: Path, val labels: List<ContourLabel>,
                               val lineCount: Int, val pointCount: Int)

/** Decoding AND path construction run on a worker; drawing only transforms these immutable paths. */
internal object OfflineContours {
    private val cache = object : LruCache<ContourTile, ContourGeometry>(12 * 1024 * 1024) {
        override fun sizeOf(key: ContourTile, value: ContourGeometry) =
            256 + value.pointCount * 24 + value.labels.size * 32 + value.lineCount * 20
    }

    fun cached(key: ContourTile): ContourGeometry? = cache.get(key)

    fun visibleTiles(west: Double, east: Double, south: Double, north: Double, interval: Int): List<ContourTile> {
        if (!listOf(west, east, south, north).all(Double::isFinite) || east <= west || north <= south) return emptyList()
        val cells = if (interval == 100) 4 else 1
        val left = max(17 * cells, floor(west * cells).toInt())
        val right = min(25 * cells, ceil(east * cells).toInt())
        val bottom = max(40 * cells, floor(south * cells).toInt())
        val top = min(48 * cells, ceil(north * cells).toInt())
        val result = ArrayList<ContourTile>()
        for (yy in bottom until top) for (xx in left until right) {
            if (ContourPolicy.hasTerrain(Math.floorDiv(xx, cells), Math.floorDiv(yy, cells)))
                result.add(ContourTile(xx, yy, interval))
        }
        // The central tiles load first while a pan/zoom is in flight.
        val cx = (west + east) * .5; val cy = (south + north) * .5
        return result.sortedBy { abs(it.west + it.size * .5 - cx) + abs(it.south + it.size * .5 - cy) }
    }

    fun read(context: Context, key: ContourTile): ContourGeometry {
        cached(key)?.let { return it }
        require(key.interval in listOf(100, 500, 1000))
        require(ContourPolicy.hasTerrain(floor(key.west).toInt(), floor(key.south).toInt()))
        val minor = Path(); val major = Path()
        val labels = ArrayList<ContourLabel>()
        var pointCount = 0
        var lineCount = 0
        context.assets.open(key.path).use { source ->
            DataInputStream(GZIPInputStream(source).buffered(32768)).use { input ->
                require(input.readInt() == 0x45564331)
                lineCount = input.readInt()
                require(lineCount in 0..100000)
                repeat(lineCount) {
                    val height = input.readShort().toInt()
                    val count = input.readUnsignedShort()
                    require(height in 100..4000 && height % key.interval == 0 && count in 2..65535)
                    pointCount += count
                    require(pointCount <= 1_000_000)
                    val isMajor = height % ContourPolicy.majorInterval(key.interval) == 0
                    val target = if (isMajor) major else minor
                    var px = 0f; var py = 0f; var distance = 0f
                    for (i in 0 until count) {
                        val x = input.readUnsignedShort() / 65535f
                        val y = input.readUnsignedShort() / 65535f
                        if (i == 0) target.moveTo(x, y) else {
                            target.lineTo(x, y)
                            distance += hypot(x - px, y - py)
                            if (isMajor && distance >= .045f && labels.size < 6000) {
                                labels.add(ContourLabel(height, x, y))
                                distance = 0f
                            }
                        }
                        px = x; py = y
                    }
                }
                require(input.read() == -1)
            }
        }
        return ContourGeometry(minor, major, labels, lineCount, pointCount).also { cache.put(key, it) }
    }
}
