package rs.battery.lab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.*

internal data class AtlasTile(val west: Int, val south: Int, val level: Int, val x: Int, val y: Int) {
    val cells get() = 1 shl level.coerceAtLeast(0)
    val file get() = java.lang.String.format(java.util.Locale.US, "N%02dE%03d", south, west)
    fun parent() = copy(level = level - 1, x = x / 2, y = y / 2)
}

internal data class AtlasPlace(val lon: Double, val lat: Double, val name: String, val population: Int, val tier: Int)
internal class AtlasLine(val kind: Int, val label: String, val coords: FloatArray) {
    val west: Float
    val east: Float
    val south: Float
    val north: Float
    init {
        var w=Float.POSITIVE_INFINITY; var e=Float.NEGATIVE_INFINITY
        var s=Float.POSITIVE_INFINITY; var n=Float.NEGATIVE_INFINITY
        for (i in coords.indices step 2) {
            w=min(w,coords[i]); e=max(e,coords[i]); s=min(s,coords[i+1]); n=max(n,coords[i+1])
        }
        west=w; east=e; south=s; north=n
    }
}
internal data class AtlasVectors(val places: List<AtlasPlace>, val roads: List<AtlasLine>, val borders: List<AtlasLine>)
internal data class StreetTile(val x: Int, val y: Int)

internal object OfflineAtlas {
    const val WEST=17
    const val EAST=25
    const val SOUTH=40
    const val NORTH=48
    fun maxLevel(west:Int,south:Int) = if (west in 18..23 && south in 41..46) 4 else 3

    private val images = object : LruCache<AtlasTile,Bitmap>(30*1024*1024) {
        override fun sizeOf(key:AtlasTile,value:Bitmap):Int = value.allocationByteCount
    }
    // Overview images remain resident. Sharing their cache with 512px relief tiles caused
    // continuous eviction and reload on a country-wide view, making the map flash black.
    private val overviewImages = ConcurrentHashMap<AtlasTile, Bitmap>()
    private val streets = object : LruCache<StreetTile, List<AtlasLine>>(12 * 1024 * 1024) {
        override fun sizeOf(key: StreetTile, value: List<AtlasLine>) =
            value.sumOf { it.coords.size * 4 + it.label.length * 2 + 100 }
    }
    private val indices = ConcurrentHashMap<String, IntArray>()
    @Volatile private var geometry: AtlasVectors? = null
    fun cached(key:AtlasTile):Bitmap? = if (key.level <= 0) overviewImages[key] else images.get(key)
    fun read(context:Context, key:AtlasTile):Bitmap? {
        cached(key)?.let { return it }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            if (key.level < 0) inSampleSize = 2
        }
        val image = if (key.level < 0) {
            context.assets.open("relief/"+key.file+".jpg").use { BitmapFactory.decodeStream(it, null, options) }
        } else {
            val path="atlas/"+key.file+".s2map"
            val offsets = indices[key.file] ?: context.assets.open(path).use { raw ->
                val input=DataInputStream(raw)
                val head=ByteArray(12); input.readFully(head)
                check(String(head,0,4,Charsets.US_ASCII)=="S2P1")
                val header=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val count=header.getInt(8); require(count in 1..341)
                val bytes=ByteArray((count+1)*4); input.readFully(bytes)
                val ints=IntArray(count+1)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
                indices[key.file]=ints; ints
            }
            val entry=(key.cells*key.cells-1)/3+key.y*key.cells+key.x
            val size=offsets[entry+1]-offsets[entry]
            require(size in 1..200000)
            val bytes=ByteArray(size)
            context.assets.openFd(path).use { fd ->
                fd.createInputStream().use { stream ->
                    var skipped=0L
                    while(skipped<offsets[entry]) {
                        val n=stream.skip(offsets[entry]-skipped)
                        if(n<=0) throw java.io.EOFException(path)
                        skipped+=n
                    }
                    DataInputStream(stream).readFully(bytes)
                }
            }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        }
        if(image!=null) {
            if (key.level <= 0) overviewImages.putIfAbsent(key, image) else images.put(key,image)
        }
        return image
    }

    fun cachedStreets(key: StreetTile): List<AtlasLine>? = streets.get(key)

    fun readStreets(context: Context, key: StreetTile): List<AtlasLine> {
        cachedStreets(key)?.let { return it }
        val lines = ArrayList<AtlasLine>()
        context.assets.open("streets/${key.x}_${key.y}.atlasdata").use { source ->
            DataInputStream(GZIPInputStream(source).buffered(65536)).use { input ->
                require(input.readInt() == 0x45565331)
                val count = input.readInt()
                require(count in 0..200000)
                repeat(count) {
                    val kind = input.readUnsignedByte()
                    val label = ByteArray(input.readUnsignedShort()).also(input::readFully)
                    val points = input.readUnsignedShort()
                    require(points in 2..200)
                    lines.add(AtlasLine(kind, label.toString(Charsets.UTF_8), FloatArray(points * 2) { input.readFloat() }))
                }
            }
        }
        return lines.also { streets.put(key, it) }
    }

    @Synchronized fun vectors(context:Context):AtlasVectors {
        geometry?.let { return it }
        val places=ArrayList<AtlasPlace>()
        val roads=ArrayList<AtlasLine>()
        val borders=ArrayList<AtlasLine>()
        context.assets.open("atlas_vectors.atlasdata").use { src ->
            DataInputStream(GZIPInputStream(src).buffered(65536)).use { input ->
                check(input.readInt() == 0x45564731)
                val placeCount = input.readInt()
                val borderCount = input.readInt()
                val roadCount = input.readInt()
                fun readLabel(): String {
                    val bytes = ByteArray(input.readUnsignedShort())
                    input.readFully(bytes)
                    return String(bytes, Charsets.UTF_8)
                }
                fun readLine(label: String): AtlasLine {
                    val kind = input.readUnsignedByte()
                    val count = input.readUnsignedShort()
                    val coords = FloatArray(count * 2) { input.readFloat() }
                    return AtlasLine(kind, label, coords)
                }
                repeat(placeCount) {
                    val lon = input.readFloat().toDouble()
                    val lat = input.readFloat().toDouble()
                    val population = input.readInt()
                    val tier = input.readUnsignedByte()
                    places.add(AtlasPlace(lon, lat, readLabel(), population, tier))
                }
                repeat(borderCount) { borders.add(readLine("")) }
                repeat(roadCount) { roads.add(readLine(readLabel())) }
            }
        }
        places.sortWith(compareBy<AtlasPlace> { it.tier }.thenByDescending { it.population })
        return AtlasVectors(places,roads,borders).also { geometry=it }
    }
}

