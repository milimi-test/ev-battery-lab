package rs.battery.lab

import android.content.Context
import android.util.LruCache
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/** Integer metres in geographic pixel-centred grids derived from Copernicus GLO-90. */
internal object RouteTerrain {
    private const val SIDE = 512
    private const val NO_DATA = -32768
    private val cache = LruCache<Int, ShortArray>(10)
    private val absent = mutableSetOf<Int>()

    private fun tile(context: Context, lon: Int, lat: Int): ShortArray? {
        if (lon !in 17..24 || lat !in 40..47) return null
        val id = (lat - 40) * 8 + lon - 17
        synchronized(this) {
            cache.get(id)?.let { return it }
            if (id in absent) return null
            val path = String.format(Locale.ROOT, "route-heights/N%02dE%03d.hgtz", lat, lon)
            val values = try {
                context.assets.open(path).use { raw ->
                    GZIPInputStream(raw).use { gz ->
                        val bytes = gz.readBytes()
                        if (bytes.size != SIDE * SIDE * 2) throw java.io.IOException(path)
                        ShortArray(SIDE * SIDE).also {
                            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(it)
                        }
                    }
                }
            } catch (_: FileNotFoundException) {
                absent.add(id)
                return null
            }
            for (y in 0 until SIDE) {
                val left = y * SIDE
                for (x in 1 until SIDE) {
                    val i = left + x
                    values[i] = (values[i] + values[i - 1]).toShort()
                }
            }
            cache.put(id, values)
            return values
        }
    }

    private fun sample(context: Context, x: Int, y: Int): Double? {
        val data = tile(context, Math.floorDiv(x, SIDE), Math.floorDiv(y, SIDE)) ?: return null
        val row = SIDE - 1 - Math.floorMod(y, SIDE)
        return data[row * SIDE + Math.floorMod(x, SIDE)].toInt()
            .takeUnless { it == NO_DATA }?.toDouble()
    }

    fun height(context: Context, lat: Double, lon: Double): Double? {
        val xf = lon * SIDE - .5
        val yf = lat * SIDE - .5
        val x = floor(xf).toInt()
        val y = floor(yf).toInt()
        val lowerLeft = sample(context, x, y) ?: return null
        val lowerRight = sample(context, x + 1, y) ?: return null
        val upperLeft = sample(context, x, y + 1) ?: return null
        val upperRight = sample(context, x + 1, y + 1) ?: return null
        val dx = xf - x
        val dy = yf - y
        return (lowerLeft * (1 - dx) + lowerRight * dx) * (1 - dy) +
            (upperLeft * (1 - dx) + upperRight * dx) * dy
    }
}
