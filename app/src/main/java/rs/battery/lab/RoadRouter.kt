package rs.battery.lab

import android.content.Context
import java.io.DataInputStream
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream
import kotlin.math.*

internal data class RouteVertex(val lat: Double, val lon: Double, val roadClass: Int)
internal data class RoadPlan(
    val points: List<RouteVertex>,
    val distanceKm: Double,
    val startSnapKm: Double,
    val endSnapKm: Double
)

/** Directed car network for estimates. Built from local Geofabrik Serbia/Kosovo PBF data. */
internal object RoadRouter {
    @Volatile private var cached: RoadGraph? = null

    fun route(context: Context, from: GpsPoint, to: MapDestination): RoadPlan? {
        if (!inRegion(from.latitude, from.longitude) || !inRegion(to.lat, to.lon)) return null
        val graph = cached ?: synchronized(this) {
            cached ?: context.assets.open("serbia-roads.graph").use { source ->
                DataInputStream(GZIPInputStream(source, 65536).buffered(65536)).use { RoadGraph(it) }
            }.also { cached = it }
        }
        return graph.plan(from.latitude, from.longitude, to.lat, to.lon)
    }

    private fun inRegion(lat: Double, lon: Double) = lat in 41.7..46.3 && lon in 18.7..23.2
}

private class RoadGraph(input: DataInputStream) {
    private val count: Int
    private val edges: Int
    private val lat: IntArray
    private val lon: IntArray
    private val from: IntArray
    private val to: IntArray
    private val decimeters: IntArray
    private val flags: ByteArray
    private val locations: IntArray
    private val shape: ByteArray
    private val first: IntArray
    private val next: IntArray
    private val turns: HashSet<Long>
    private val speeds = doubleArrayOf(105.0, 80.0, 67.0, 56.0, 46.0, 34.0)

    init {
        require(input.readInt() == 0x4f535232)
        count = input.readInt()
        edges = input.readInt()
        val shapeBytes = input.readInt()
        val restrictions = input.readInt()
        lat = IntArray(count) { input.readInt() }
        lon = IntArray(count) { input.readInt() }
        from = IntArray(edges) { input.readInt() }
        to = IntArray(edges) { input.readInt() }
        decimeters = IntArray(edges) { input.readInt() }
        locations = IntArray(edges + 1) { input.readInt() }
        shape = ByteArray(shapeBytes).also(input::readFully)
        flags = ByteArray(edges).also(input::readFully)
        turns = HashSet(restrictions * 2)
        repeat(restrictions) { turns.add(key(input.readInt(), input.readInt())) }
        first = IntArray(count) { -1 }
        next = IntArray(edges * 2)
        for (edge in 0 until edges) {
            val bits = flags[edge].toInt()
            if (bits and 1 != 0) insert(from[edge], edge * 2)
            if (bits and 2 != 0) insert(to[edge], edge * 2 + 1)
        }
    }

    private fun insert(node: Int, arc: Int) {
        next[arc] = first[node]
        first[node] = arc
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or b.toLong()
    private fun roadClass(edge: Int) = flags[edge].toInt() ushr 2
    private fun seconds(edge: Int) = decimeters[edge] * .36 / speeds[roadClass(edge)]
    private fun start(arc: Int) = if (arc and 1 == 0) from[arc / 2] else to[arc / 2]
    private fun end(arc: Int) = if (arc and 1 == 0) to[arc / 2] else from[arc / 2]
    private fun nodePoint(node: Int, road: Int) = RouteVertex(lat[node] * .00001, lon[node] * .00001, road)
    private fun arcs(edge: Int) = when (flags[edge].toInt() and 3) {
        1 -> intArrayOf(2 * edge)
        2 -> intArrayOf(2 * edge + 1)
        else -> intArrayOf(2 * edge, 2 * edge + 1)
    }

    private fun geometry(edge: Int): List<RouteVertex> {
        val points = ArrayList<RouteVertex>()
        val road = roadClass(edge)
        var x = lon[from[edge]]
        var y = lat[from[edge]]
        var cursor = locations[edge]
        fun delta(): Int {
            var bits = 0
            var n = 0
            do {
                val v = shape[cursor++].toInt() and 255
                n = n or ((v and 127) shl bits)
                bits += 7
            } while (v >= 128)
            return (n ushr 1) xor -(n and 1)
        }
        points.add(nodePoint(from[edge], road))
        while (cursor < locations[edge + 1]) {
            y += delta()
            x += delta()
            points.add(RouteVertex(y * .00001, x * .00001, road))
        }
        points.add(nodePoint(to[edge], road))
        return points
    }

    private fun km(a: RouteVertex, b: RouteVertex): Double {
        val scale = cos(Math.toRadians((a.lat + b.lat) / 2))
        return 111.195 * hypot(b.lat - a.lat, (b.lon - a.lon) * scale)
    }

    private data class Snap(val edge: Int, val fraction: Double, val point: RouteVertex, val offset: Double)

    private fun snap(y: Double, x: Double): Snap? {
        // Links are split every 180 m wherever an OSM geometry node is present.
        // A wider endpoint margin covers curves and sparse motorway geometry.
        val yLimit = (.75 + 1.0) / 111.195
        val xCos = cos(Math.toRadians(y))
        val xLimit = yLimit / xCos
        val yLo = ((y - yLimit) * 100000).toInt()
        val yHi = ((y + yLimit) * 100000).toInt()
        val xLo = ((x - xLimit) * 100000).toInt()
        val xHi = ((x + xLimit) * 100000).toInt()
        var best: Snap? = null
        for (edge in 0 until edges) {
            if (edge and 4095 == 0) checkInterrupted()
            val f = from[edge]
            val t = to[edge]
            if (max(lat[f], lat[t]) < yLo || min(lat[f], lat[t]) > yHi ||
                max(lon[f], lon[t]) < xLo || min(lon[f], lon[t]) > xHi) continue
            val p = geometry(edge)
            var total = 0.0
            var near = .750001
            var along = 0.0
            var hit: RouteVertex? = null
            for (i in 1 until p.size) {
                val a = p[i - 1]
                val b = p[i]
                val dx = (b.lon - a.lon) * xCos
                val dy = b.lat - a.lat
                val square = dx * dx + dy * dy
                val factor = if (square <= 0) 0.0 else ((((x - a.lon) * xCos * dx + (y - a.lat) * dy) / square)).coerceIn(0.0, 1.0)
                val py = a.lat + dy * factor
                val px = a.lon + (b.lon - a.lon) * factor
                val offset = 111.195 * hypot(py - y, (px - x) * xCos)
                val length = km(a, b)
                if (offset < near) {
                    near = offset
                    along = total + length * factor
                    hit = RouteVertex(py, px, a.roadClass)
                }
                total += length
            }
            if (hit != null && total > 0 && near <= .75 && (best == null || near < best.offset))
                best = Snap(edge, (along / total).coerceIn(0.0, 1.0), hit, near)
        }
        return best
    }

    private data class Visit(val arc: Int, val elapsed: Float, val priority: Double) : Comparable<Visit> {
        override fun compareTo(other: Visit) = priority.compareTo(other.priority)
    }

    fun plan(ay: Double, ax: Double, by: Double, bx: Double): RoadPlan? {
        val a = snap(ay, ax) ?: return null
        val b = snap(by, bx) ?: return null
        fun fraction(arc: Int, f: Double) = if (arc and 1 == 0) f else 1 - f
        if (a.edge == b.edge) {
            val direct = arcs(a.edge).firstOrNull { fraction(it, b.fraction) >= fraction(it, a.fraction) }
            if (direct != null) return makePlan(listOf(direct), a, b)
        }
        val queue = PriorityQueue<Visit>()
        val prior = IntArray(edges * 2) { -1 }
        val times = FloatArray(edges * 2) { Float.POSITIVE_INFINITY }
        val targetArcs = arcs(b.edge)
        val destCos = cos(Math.toRadians(b.point.lat))
        fun add(arc: Int, time: Double, before: Int) {
            if (time >= times[arc]) return
            times[arc] = time.toFloat()
            prior[arc] = before
            val node = end(arc)
            val estimate = 111.195 * hypot(lat[node] * .00001 - b.point.lat,
                (lon[node] * .00001 - b.point.lon) * destCos)
            // Lower than the cost attainable by any road class, including rounding and map projection.
            queue.add(Visit(arc, times[arc], time + estimate * 3600 / 135.0))
        }
        for (arc in arcs(a.edge)) {
            add(arc, (1 - fraction(arc, a.fraction)) * seconds(a.edge), -1)
        }
        var best = Double.POSITIVE_INFINITY
        var last = -1
        var finishArc = -1
        var examined = 0
        while (queue.isNotEmpty()) {
            if (examined++ and 1023 == 0) checkInterrupted()
            val current = queue.remove()
            if (current.elapsed != times[current.arc]) continue
            if (current.priority >= best) break
            val node = end(current.arc)
            for (target in targetArcs) {
                if (start(target) == node && key(current.arc, target) !in turns) {
                    val total = current.elapsed + fraction(target, b.fraction) * seconds(b.edge)
                    if (total < best) { best = total; last = current.arc; finishArc = target }
                }
            }
            var arc = first[node]
            while (arc >= 0) {
                if (key(current.arc, arc) !in turns)
                    add(arc, current.elapsed + seconds(arc / 2), current.arc)
                arc = next[arc]
            }
        }
        if (finishArc < 0) return null
        val route = ArrayList<Int>()
        route.add(finishArc)
        while (last >= 0) { route.add(last); last = prior[last] }
        route.reverse()
        return makePlan(route, a, b)
    }

    private fun makePlan(route: List<Int>, a: Snap, b: Snap): RoadPlan {
        val out = ArrayList<RouteVertex>()
        fun add(p: RouteVertex) {
            if (out.isNotEmpty() && km(out.last(), p) < .000001) out[out.lastIndex] = p
            else out.add(p)
        }
        for ((index, arc) in route.withIndex()) {
            val geom = geometry(arc / 2)
            val points = if (arc and 1 == 0) geom else geom.asReversed()
            val lo = if (index == 0) (if (arc and 1 == 0) a.fraction else 1 - a.fraction) else 0.0
            val hi = if (index == route.lastIndex) (if (arc and 1 == 0) b.fraction else 1 - b.fraction) else 1.0
            val total = points.zipWithNext { u, v -> km(u, v) }.sum()
            add(if (index == 0) a.point else points.first())
            var along = 0.0
            for (i in 1 until points.lastIndex) {
                along += km(points[i - 1], points[i])
                if (along > lo * total && along < hi * total) add(points[i])
            }
            add(if (index == route.lastIndex) b.point else points.last())
        }
        val distance = out.zipWithNext { u, v -> km(u, v) }.sum()
        return RoadPlan(out, distance, a.offset, b.offset)
    }
}

