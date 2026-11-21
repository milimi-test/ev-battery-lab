package rs.battery.lab

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.math.*

internal data class MapChargerMarker(val id: String, val lat: Double, val lon: Double,
                                    val name: String, val isFree: Boolean)

/** Local geographic map. Pixel scale and vector label sizes are independent of raster level. */
internal class OfflineAtlasView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loading = mutableSetOf<AtlasTile>()
    private val missing = mutableSetOf<AtlasTile>()
    private val decodeSlots = Semaphore(4)
    private val loadingStreets = mutableSetOf<StreetTile>()
    private val absentStreets = mutableSetOf<StreetTile>()
    private val streetTiles = mutableMapOf<StreetTile, List<AtlasLine>>()
    private val streetLabels = ArrayList<Triple<String, Float, Float>>()
    private var vectors: AtlasVectors? = null
    private var lon = 20.95
    private var lat = 44.1
    private var degrees = .012
    private var followFit = true
    private var overview = true
    private var relief = false
    private var contoursVisible = true
    private val loadingContours = mutableSetOf<ContourTile>()
    private val missingContours = mutableSetOf<ContourTile>()
    private val visibleContours = ArrayList<Pair<ContourTile, ContourGeometry>>()
    private var terrainStatus = "Izohipse su vidljive uz reljef"
    var onTerrainStatus: ((String) -> Unit)? = null
    private var rangeMode = false
    private var range: MapRangeEstimate? = null
    var onDestination: ((MapDestination) -> Unit)? = null
    var onChargerSelected: ((String) -> Unit)? = null
    var onChargerGroupSelected: ((List<String>) -> Unit)? = null
    private var chargers = emptyList<MapChargerMarker>()
    private data class ChargerHit(val bounds: RectF, val entries: List<MapChargerMarker>)
    private val chargerHits = ArrayList<ChargerHit>()
    private var destination: MapDestination? = null
    private var roadPlan: RoadPlan? = null
    private var fixes = emptyList<GpsPoint>()
    private var gaps = emptyList<GpsGap>()
    private var altMin = 0.0
    private var altMax = 0.0
    private var breaks = BooleanArray(0)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val raster = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val labels = ArrayList<RectF>()
    private val shadow = Color.rgb(28,36,36)
    private val motor = Color.rgb(255,180,75)
    private val trunk = Color.rgb(255,220,142)
    private val primary = Color.rgb(233,219,181)
    private val border = Color.rgb(166,240,255)
    private val geoCos get() = cos(Math.toRadians(lat)).coerceAtLeast(.05)
    private val stepY get() = degrees*geoCos
    private val spanKm get() = width*degrees*geoCos*111.195
    private val west get() = lon-width*.5*degrees
    private val east get() = lon+width*.5*degrees
    private val north get() = lat+height*.5*stepY
    private val south get() = lat-height*.5*stepY
    private fun x(v:Double) = ((v-lon)/degrees + width*.5).toFloat()
    private fun y(v:Double) = ((lat-v)/stepY + height*.5).toFloat()
    private fun tileBounds(tile:AtlasTile):RectF = RectF(
        x(tile.west+tile.x.toDouble()/tile.cells),y(tile.south+1-tile.y.toDouble()/tile.cells),
        x(tile.west+(tile.x+1.0)/tile.cells),y(tile.south+1-(tile.y+1.0)/tile.cells))

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        scope.launch {
            val data = runCatching { OfflineAtlas.vectors(context.applicationContext) }
                .onFailure { android.util.Log.e("OfflineAtlas", "Cannot load packaged map geometry", it) }.getOrNull()
            withContext(Dispatchers.Main) { vectors=data; invalidate() }
        }
    }
    override fun onDetachedFromWindow() {
        scope.cancel(); loading.clear(); loadingStreets.clear(); streetTiles.clear()
        loadingContours.clear(); visibleContours.clear()
        super.onDetachedFromWindow()
    }
    fun setContoursVisible(visible: Boolean) {
        if (contoursVisible == visible) return
        contoursVisible = visible
        invalidate()
    }
    fun contourStatusText(): String = terrainStatus
    private fun publishTerrainStatus(value: String) {
        if (terrainStatus == value) return
        terrainStatus = value
        // A Compose consumer must never be mutated from the Android draw callback.
        post { if (isAttachedToWindow && terrainStatus == value) onTerrainStatus?.invoke(value) }
    }
    fun setContent(points:List<GpsPoint>, gaps:List<GpsGap>, overview:Boolean, relief:Boolean,
                   range:MapRangeEstimate? = null, rangeMode:Boolean = false) {
        val newTrip=points.firstOrNull()?.timeMillis!=fixes.firstOrNull()?.timeMillis
        val changed=this.fixes!==points || this.gaps!==gaps
        val changedRange=this.range!=range
        val fit=overview!=this.overview || newTrip || rangeMode!=this.rangeMode
        if (!changed && !changedRange && !fit && this.relief==relief) return
        if (changed) {
            this.fixes=points;this.gaps=gaps
            val alts=points.mapNotNull { it.altitudeMeters?.takeIf(Double::isFinite) }
            altMin=alts.minOrNull()?:0.0; altMax=alts.maxOrNull()?:0.0
            breaks=BooleanArray(points.size) { i->i==0 || gapBetween(points[i-1],points[i],gaps) }
        }
        this.overview=overview;this.relief=relief;this.range=range;this.rangeMode=rangeMode
        if (fit) resetCamera()
        else {
            if (followFit && rangeMode && changedRange) range?.origin?.let { lat = it.latitude; lon = it.longitude }
            // Keep the zoom stable as points arrive; only follow when the car approaches an edge.
            if (followFit && changed && !overview && !rangeMode) points.lastOrNull()?.let { point ->
                if (abs(point.longitude - lon) > degrees * width * .30 ||
                    abs(point.latitude - lat) > degrees * height * geoCos * .30) {
                    lat = point.latitude; lon = point.longitude
                }
            }
            invalidate()
        }
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        if (followFit) resetCamera()
    }
    fun resetCamera() {
        followFit=true
        if(width==0 || height==0) return
        var l=18.7;var r=23.2;var b=41.7;var t=46.3
        val estimate=range?.takeIf { rangeMode && it.origin != null }
        val radius=listOfNotNull(estimate?.oneWayKm,estimate?.returnKm)
            .filter { it.isFinite() && it>0 }.maxOrNull()
        if(estimate!=null && radius!=null) {
            val origin=estimate.origin!!
            lat=origin.latitude;lon=origin.longitude
            val latSpan=radius/111.195
            val lonSpan=radius/(111.195*cos(Math.toRadians(lat)).coerceAtLeast(.1))
            l=lon-lonSpan;r=lon+lonSpan;b=lat-latSpan;t=lat+latSpan
        } else if(!overview && fixes.isNotEmpty()) {
            l=fixes.minOf { it.longitude };r=fixes.maxOf { it.longitude }
            b=fixes.minOf { it.latitude };t=fixes.maxOf { it.latitude }
        }
        lat=(b+t)/2;lon=(l+r)/2
        degrees=max(max(r-l,.010)/max(1,width),max(t-b,.009)/(max(1,height)*geoCos))*(if(radius!=null)1.38 else 1.15)
        degrees=degrees.coerceIn(.000006, .05)
        invalidate()
    }
    fun showWidthKm(km:Double) {
        if(width<=0 || km<=0) return
        degrees=(km/(width*geoCos*111.195)).coerceIn(.000006,.05)
        followFit=false
        invalidate()
    }
    fun focusOn(target: MapDestination, widthKm: Double = 8.0) {
        lat = target.lat; lon = target.lon
        showWidthKm(widthKm)
    }
    fun showRoute() {
        val points = roadPlan?.points?.takeIf { it.isNotEmpty() } ?: return
        val left = points.minOf { it.lon }; val right = points.maxOf { it.lon }
        val bottom = points.minOf { it.lat }; val top = points.maxOf { it.lat }
        lat = (bottom + top) / 2; lon = (left + right) / 2
        degrees = (max(max(right - left, .010) / max(1, width),
            max(top - bottom, .009) / (max(1, height) * geoCos)) * 1.3).coerceIn(.000006, .05)
        followFit = false
        invalidate()
    }
    fun setDestination(target:MapDestination?, plan:RoadPlan?) {
        if(destination==target && roadPlan==plan) return
        destination=target;roadPlan=plan;invalidate()
    }
    fun setChargers(markers: List<MapChargerMarker>) {
        if (chargers == markers) return
        chargers = markers.filter { it.lat.isFinite() && it.lon.isFinite() }
        invalidate()
    }
    fun zoomBy(factor:Double) = zoomAt(factor,width*.5f,height*.5f)
    private fun zoomAt(factor:Double,fx:Float,fy:Float) {
        if (!factor.isFinite() || factor<=0) return
        val anchorLon=lon+(fx-width*.5)*degrees
        val anchorLat=lat-(fy-height*.5)*stepY
        degrees=(degrees/factor).coerceIn(.000006,.05)
        lat=anchorLat+(fy-height*.5)*stepY
        lon=anchorLon-(fx-width*.5)*degrees
        followFit=false; constrain(); invalidate()
    }
    private fun constrain() {
        val route = if (overview) emptyList() else fixes
        lon=lon.coerceIn(min(17.0,route.minOfOrNull { it.longitude } ?: 17.0),
            max(25.0,route.maxOfOrNull { it.longitude } ?: 25.0))
        lat=lat.coerceIn(min(40.0,route.minOfOrNull { it.latitude } ?: 40.0),
            max(48.0,route.maxOfOrNull { it.latitude } ?: 48.0))
    }

    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d:ScaleGestureDetector):Boolean { zoomAt(d.scaleFactor.toDouble(),d.focusX,d.focusY);return true }
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onDoubleTap(e:MotionEvent):Boolean {zoomAt(2.0,e.x,e.y);return true}
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
            performClick()
            val hit = chargerHits.lastOrNull { it.bounds.contains(e.x, e.y) }
            if (hit != null) {
                if (hit.entries.size == 1) onChargerSelected?.invoke(hit.entries.first().id)
                else if (degrees <= .0000061 ||
                    hit.entries.maxOf { it.lat } - hit.entries.minOf { it.lat } < .0001 &&
                    hit.entries.maxOf { it.lon } - hit.entries.minOf { it.lon } < .0001) {
                    onChargerGroupSelected?.invoke(hit.entries.map { it.id })
                }
                else {
                    lon = hit.entries.map { it.lon }.average()
                    lat = hit.entries.map { it.lat }.average()
                    zoomAt(2.5, width * .5f, height * .5f)
                }
                return true
            }
            onDestination?.invoke(MapDestination(lat-(e.y-height*.5)*stepY,
                lon+(e.x-width*.5)*degrees))
            return true
        }
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean {
            if(!scale.isInProgress) { lon+=dx*degrees;lat-=dy*stepY;followFit=false;constrain();invalidate() }
            return true
        }
    })
    override fun onTouchEvent(event:MotionEvent):Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked!=MotionEvent.ACTION_UP && event.actionMasked!=MotionEvent.ACTION_CANCEL)
        scale.onTouchEvent(event);gestures.onTouchEvent(event);return true
    }
    override fun performClick():Boolean {super.performClick();return true}

    private fun bitmap(tile:AtlasTile):Bitmap? {
        OfflineAtlas.cached(tile)?.let { return it }
        if (tile !in missing && loading.add(tile)) scope.launch {
            val value=decodeSlots.withPermit {
                runCatching { OfflineAtlas.read(context.applicationContext,tile) }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                loading.remove(tile)
                if(value==null) missing.add(tile)
                postInvalidateOnAnimation()
            }
        }
        return null
    }
    override fun onDraw(c:Canvas) {
        if(width<=0 || height<=0) return
        val saved = c.save()
        c.clipRect(0, 0, width, height)
        c.drawColor(Color.rgb(16,34,45))
        drawRaster(c)
        labels.clear()
        labels.add(RectF(width-58*dp,0f,width.toFloat(),211*dp))
        labels.add(RectF(0f,0f,84*dp,40*dp))
        labels.add(RectF(0f,height-35*sp,128*sp,height.toFloat()))
        if (relief) drawTerrainCoverage(c)
        drawContours(c)
        drawStreets(c)
        vectors?.let { drawNetwork(c,it) }
        drawRangeRings(c)
        drawRoadPlan(c)
        drawTrip(c)
        drawDestinationPin(c)
        drawChargers(c)
        vectors?.let { drawPlaces(c,it.places) }
        drawStreetLabels(c)
        drawContourLabels(c)
        drawScale(c)
        if (!relief && (east<=17 || west>=25 || north<=40 || south>=48))
            text(c,"Izvan satelitskog obuhvata",width*.20f,height*.50f,12f,Color.WHITE)
        c.restoreToCount(saved)
    }

    private fun drawTerrainCoverage(c: Canvas) {
        val area = RectF(x(17.0), y(48.0), x(25.0), y(40.0))
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val noData = ArrayList<Pair<RectF, String>>()
        fun add(rect: RectF, label: String) {
            if (rect.intersect(view) && rect.width() > 0 && rect.height() > 0) noData.add(rect to label)
        }
        add(RectF(0f, 0f, width.toFloat(), area.top.coerceIn(0f, height.toFloat())), "Van offline obuhvata")
        add(RectF(0f, area.bottom.coerceIn(0f, height.toFloat()), width.toFloat(), height.toFloat()), "Van offline obuhvata")
        add(RectF(0f, max(0f, area.top), max(0f, area.left), min(height.toFloat(), area.bottom)), "Van offline obuhvata")
        add(RectF(min(width.toFloat(), area.right), max(0f, area.top), width.toFloat(), min(height.toFloat(), area.bottom)), "Van offline obuhvata")
        // N41E018 is absent from both the packaged imagery and DEM, not a measured zero.
        add(RectF(x(18.0), y(42.0), x(19.0), y(41.0)), "Nema visinskih podataka")
        for ((rect, _) in noData) {
            val saved = c.save()
            c.clipRect(rect)
            ink.style = Paint.Style.FILL; ink.color = Color.argb(225, 29, 46, 52)
            c.drawRect(rect, ink)
            ink.strokeWidth = .7f * dp; ink.color = Color.argb(75, 181, 197, 199)
            var sx = rect.left - rect.height()
            while (sx < rect.right) {
                c.drawLine(sx, rect.bottom, sx + rect.height(), rect.top, ink)
                sx += 14 * dp
            }
            c.restoreToCount(saved)
        }
        ink.style = Paint.Style.STROKE; ink.strokeWidth = 1.1f * dp
        ink.color = Color.argb(200, 213, 223, 215)
        ink.pathEffect = DashPathEffect(floatArrayOf(5 * dp, 4 * dp), 0f)
        c.drawRect(area, ink)
        c.drawRect(x(18.0), y(42.0), x(19.0), y(41.0), ink)
        ink.pathEffect = null; ink.style = Paint.Style.FILL
        // One small label within the largest gap; it does not cover valid terrain or the origin.
        for ((rect, title) in noData.sortedByDescending { it.first.width() * it.first.height() }) {
            ink.textSize = 11 * sp; ink.typeface = Typeface.DEFAULT_BOLD
            val labelWidth = ink.measureUiText(title)
            if (rect.width() < labelWidth + 20 * dp || rect.height() < 38 * sp) continue
            val left = rect.left + 10 * dp
            val baseline = max(rect.top + 24 * sp, 48 * dp)
            val box = RectF(left - 3 * dp, baseline + ink.ascent() - 3 * dp,
                left + labelWidth + 3 * dp, baseline + ink.descent() + 3 * dp)
            if (!rect.contains(box) || labels.any { RectF.intersects(it, box) }) continue
            labels.add(box)
            text(c, title, left, baseline, 11f, Color.rgb(225, 235, 230))
            break
        }
    }

    private fun drawContours(c: Canvas) {
        visibleContours.clear()
        if (!relief || !contoursVisible) {
            publishTerrainStatus(if (relief) "Izohipse isključene" else "Izohipse su vidljive uz reljef")
            return
        }
        val interval = ContourPolicy.intervalFor(spanKm)
        val keys = OfflineContours.visibleTiles(west, east, south, north, interval)
        var awaiting = false
        var absent = false
        for (key in keys) {
            val geometry = OfflineContours.cached(key)
            if (geometry != null) {
                visibleContours.add(key to geometry)
                val left = x(key.west); val bottom = y(key.south)
                val w = x(key.west + key.size) - left
                val h = bottom - y(key.south + key.size)
                val localScale = sqrt(w * h).coerceAtLeast(1f)
                val saved = c.save()
                c.translate(left, bottom); c.scale(w, -h)
                ink.style = Paint.Style.STROKE; ink.strokeJoin = Paint.Join.ROUND
                ink.strokeCap = Paint.Cap.ROUND; ink.pathEffect = null
                ink.color = Color.argb(145, 81, 51, 37); ink.strokeWidth = .65f * dp / localScale
                c.drawPath(geometry.minor, ink)
                ink.color = Color.argb(175, 248, 232, 203); ink.strokeWidth = 2.0f * dp / localScale
                c.drawPath(geometry.major, ink)
                ink.color = Color.argb(220, 87, 48, 35); ink.strokeWidth = .95f * dp / localScale
                c.drawPath(geometry.major, ink)
                c.restoreToCount(saved)
                ink.style = Paint.Style.FILL; ink.strokeCap = Paint.Cap.BUTT
            } else if (key in missingContours) absent = true
            else {
                awaiting = true
                // Bounded launches as well as bounded decoding: panning cannot queue the atlas.
                if (loadingContours.size < 4 && loadingContours.add(key)) scope.launch {
                    val result = decodeSlots.withPermit {
                        runCatching { OfflineContours.read(context.applicationContext, key) }
                            .onFailure { android.util.Log.w("OfflineContours", "Cannot decode ${key.path}", it) }
                    }
                    withContext(Dispatchers.Main) {
                        loadingContours.remove(key)
                        if (result.isFailure) missingContours.add(key)
                        postInvalidateOnAnimation()
                    }
                }
            }
        }
        publishTerrainStatus(when {
            keys.isEmpty() -> "Van offline visinskog obuhvata"
            absent -> "Izohipse $interval m · deo podataka nedostaje"
            awaiting -> "Izohipse $interval m · učitavanje"
            else -> "Izohipse $interval m"
        })
    }

    private fun drawContourLabels(c: Canvas) {
        if (!relief || !contoursVisible) return
        val placed = ArrayList<Pair<Int, PointF>>()
        val limit = max(3, min(8, (height / (85 * sp)).toInt()))
        for ((key, geometry) in visibleContours) for (label in geometry.labels) {
            if (placed.size >= limit) return
            val px = x(key.west + label.x * key.size)
            val py = y(key.south + label.y * key.size)
            if (px < 18 * dp || px > width - 18 * dp || py < 20 * sp || py > height - 42 * sp) continue
            if (placed.any { (height, at) -> height == label.height && hypot(at.x - px, at.y - py) < 140 * dp }) continue
            val title = "${label.height} m"
            ink.textSize = 12 * sp; ink.typeface = Typeface.DEFAULT_BOLD
            val tw = ink.measureUiText(title)
            val baseline = py - (ink.ascent() + ink.descent()) * .5f
            val box = RectF(px - tw * .5f - 4 * dp, baseline + ink.ascent() - 2 * dp,
                px + tw * .5f + 4 * dp, baseline + ink.descent() + 2 * dp)
            if (box.left < 5 * dp || box.right > width - 5 * dp || labels.any { RectF.intersects(it, box) }) continue
            labels.add(box); placed.add(label.height to PointF(px, py))
            ink.style = Paint.Style.FILL; ink.color = Color.argb(230, 250, 237, 213)
            c.drawRoundRect(box, 3 * dp, 3 * dp, ink)
            ink.color = Color.rgb(80, 42, 31)
            c.drawUiText(title, px - tw * .5f, baseline, ink)
        }
    }
    private fun drawRaster(c:Canvas) {
        val left=max(OfflineAtlas.WEST,floor(west).toInt())
        val right=min(OfflineAtlas.EAST,ceil(east).toInt())
        val bottom=max(OfflineAtlas.SOUTH,floor(south).toInt())
        val top=min(OfflineAtlas.NORTH,ceil(north).toInt())
        val wanted=ceil(log2(1.0/(256*min(degrees,stepY)))).toInt().coerceIn(0,4)
        for (a in bottom until top) for (b in left until right) {
            val root=AtlasTile(b,a,0,0,0)
            val bounds=tileBounds(root)
            // This square is entirely in the Adriatic; the land-only mosaics have no tile.
            if(a==41 && b==18) {
                ink.style=Paint.Style.FILL
                ink.color=Color.rgb(20,67,87)
                c.drawRect(bounds,ink)
                continue
            }
            raster.alpha=255
            bitmap(root)?.let { c.drawBitmap(it,null,bounds,raster) }
            val z=wanted.coerceAtMost(OfflineAtlas.maxLevel(b,a))
            if(z>0) {
                val n=1 shl z
                for (row in floor((a+1-north)*n).toInt().coerceIn(0,n)..<ceil((a+1-south)*n).toInt().coerceIn(0,n))
                    for(col in floor((west-b)*n).toInt().coerceIn(0,n)..<ceil((east-b)*n).toInt().coerceIn(0,n)) {
                        val requested=AtlasTile(b,a,z,col,row)
                        val dst=tileBounds(requested)
                        var key=requested
                        while(key.level>0) {
                            val image=if (key==requested) bitmap(key) else OfflineAtlas.cached(key)
                            if(image!=null) {
                                val sub=1 shl (z-key.level)
                                val d=256/sub
                                val l=(col%sub)*d;val t=(row%sub)*d
                                c.drawBitmap(image,Rect(l,t,l+d,t+d),dst,raster);break
                            }
                            key=key.parent()
                        }
                    }
            }
            if(relief) bitmap(root.copy(level=-1))?.let { raster.alpha=225;c.drawBitmap(it,null,bounds,raster);raster.alpha=255 }
        }
    }

    private fun drawStreets(c: Canvas) {
        streetLabels.clear()
        if (spanKm > 85) { streetTiles.clear(); return }
        val keys = buildSet {
            for (yy in max(160, floor(south * 4).toInt())..min(191, floor(north * 4).toInt()))
                for (xx in max(68, floor(west * 4).toInt())..min(99, floor(east * 4).toInt()))
                    add(StreetTile(xx, yy))
        }
        streetTiles.keys.retainAll(keys)
        val roads = ArrayList<AtlasLine>()
        for (key in keys) {
            val data = streetTiles[key] ?: OfflineAtlas.cachedStreets(key)
            if (data != null) {
                streetTiles[key] = data
                roads.addAll(data)
            } else if (key !in absentStreets && loadingStreets.add(key)) scope.launch {
                val value = decodeSlots.withPermit {
                    runCatching { OfflineAtlas.readStreets(context.applicationContext, key) }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    loadingStreets.remove(key)
                    if (value == null) absentStreets.add(key)
                    else if (key.x / 4.0 <= east && (key.x + 1) / 4.0 >= west &&
                        key.y / 4.0 <= north && (key.y + 1) / 4.0 >= south) streetTiles[key] = value
                    postInvalidateOnAnimation()
                }
            }
        }
        val tier = when { spanKm > 38 -> 3; spanKm > 15 -> 4; spanKm > 6 -> 5; else -> 7 }
        val names = HashSet<String>()
        for (kind in tier downTo 3) for (line in roads) if (line.kind == kind && visible(line)) {
            trace(line)
            val weight = when (kind) { 3 -> 1.55f; 4 -> 1.15f; 5 -> 1f; else -> .75f }
            stroke(c, Color.argb(155, 22, 36, 38), weight + .9f)
            stroke(c, if (kind <= 4) Color.rgb(231, 218, 180) else Color.rgb(218, 225, 218), weight, kind == 7)
            if (spanKm <= 7 && line.label.isNotBlank() && line.label !in names && streetLabels.size < 100) {
                val middle = line.coords.size / 4 * 2
                val px = x(line.coords[middle].toDouble())
                val py = y(line.coords[middle + 1].toDouble())
                if (px in 12f*dp..width-60f*dp && py in 20f*dp..height-42f*dp) {
                    names.add(line.label)
                    streetLabels.add(Triple(line.label.take(42), px, py))
                }
            }
        }
    }

    private fun drawStreetLabels(c: Canvas) {
        for ((name, px, py) in streetLabels) {
            ink.typeface = Typeface.DEFAULT_BOLD
            ink.textSize = 9.5f * sp
            val bounds = RectF(px - 3*dp, py + ink.ascent() - 3*dp, px + ink.measureUiText(name) + 5*dp,
                py + ink.descent() + 3*dp)
            if (bounds.right > width || bounds.top < 0 || bounds.bottom > height ||
                labels.any { RectF.intersects(it, bounds) }) continue
            labels.add(bounds)
            text(c, name, px, py, 9.5f, Color.rgb(233, 242, 239))
        }
    }

    private fun drawChargers(c: Canvas) {
        chargerHits.clear()
        val cell = (when { spanKm > 300 -> 84; spanKm > 120 -> 68; else -> 44 }) * dp
        val groups = chargers.filter { it.lon in west..east && it.lat in south..north }
            .groupBy { (x(it.lon) / cell).toInt() to (y(it.lat) / cell).toInt() }
        for (entries in groups.values) {
            val px = entries.map { x(it.lon).toDouble() }.average().toFloat()
            val py = entries.map { y(it.lat).toDouble() }.average().toFloat()
            val bounds = RectF(px - 21*dp, py - 21*dp, px + 21*dp, py + 21*dp)
            // Keep the right-hand zoom buttons and lower-left scale unobstructed.
            if (px > width-58*dp && py < 211*dp || px < 150*dp && py > height-40*dp) continue
            chargerHits.add(ChargerHit(bounds, entries))
            labels.add(RectF(px - 13*dp, py - 13*dp, px + 13*dp, py + 13*dp))
            ink.style = Paint.Style.FILL
            ink.color = Color.WHITE
            c.drawCircle(px, py, 12*dp, ink)
            ink.color = if (entries.all { it.isFree }) Color.rgb(0, 147, 83) else Color.rgb(21, 105, 220)
            c.drawCircle(px, py, 10.3f*dp, ink)
            ink.color = Color.WHITE
            ink.textSize = (if (entries.size > 99) 8.5f else 10f)*dp
            ink.typeface = Typeface.DEFAULT_BOLD
            if (entries.size > 1) {
                val label = entries.size.toString()
                c.drawUiText(label, px - ink.measureUiText(label)/2, py - (ink.ascent()+ink.descent())/2, ink)
            } else {
                path.rewind()
                path.moveTo(px + 2*dp, py - 7*dp)
                path.lineTo(px - 5*dp, py + 1*dp)
                path.lineTo(px, py + 1*dp)
                path.lineTo(px - 2*dp, py + 7*dp)
                path.lineTo(px + 5*dp, py - 1*dp)
                path.lineTo(px, py - 1*dp)
                path.close()
                c.drawPath(path, ink)
            }
        }
    }
    private fun visible(s:AtlasLine) = s.east>=west && s.west<=east && s.north>=south && s.south<=north
    private fun trace(s:AtlasLine) {
        path.rewind()
        for(i in s.coords.indices step 2) {
            val xx=x(s.coords[i].toDouble());val yy=y(s.coords[i+1].toDouble())
            if(i==0)path.moveTo(xx,yy) else path.lineTo(xx,yy)
        }
    }
    private fun stroke(c:Canvas,color:Int,weight:Float,dashed:Boolean=false) {
        ink.style=Paint.Style.STROKE; ink.color=color;ink.strokeWidth=weight*dp
        ink.strokeJoin=Paint.Join.ROUND
        ink.pathEffect=if (dashed) DashPathEffect(floatArrayOf(5*dp,3*dp),0f) else null
        c.drawPath(path,ink); ink.pathEffect=null;ink.style=Paint.Style.FILL
    }
    private val badges=mutableListOf<Triple<String,Float,Float>>()
    private fun drawNetwork(c:Canvas,v:AtlasVectors) {
        badges.clear()
        val ids=HashSet<String>()
        for(k in 2 downTo 0) {
            if(k==2 && spanKm>195) continue
            for(s in v.roads) if(s.kind==k && visible(s)) {
                trace(s)
                val weight=(if(k==0)2.1f else if(k==1)1.55f else 1.05f)*(if(spanKm>320) .66f else 1f)
                stroke(c,shadow,weight+1.15f)
                stroke(c,if(k==0)motor else if(k==1)trunk else primary,weight)
                if(spanKm<230 && s.label.isNotBlank() && s.label !in ids && badges.size<24) {
                    val a=(s.coords.size/4)*2
                    val px=x(s.coords[a].toDouble());val py=y(s.coords[a+1].toDouble())
                    if(px in (16*dp)..(width-30*dp) && py in (18*dp)..(height-16*dp)) {
                        badges.add(Triple(s.label,px,py));ids.add(s.label)
                    }
                }
            }
        }
        for(s in v.borders) if(visible(s)) {
            trace(s)
            stroke(c,shadow,if(s.kind==0)2.8f else 1.7f)
            stroke(c,if(s.kind==0)border else Color.LTGRAY,if(s.kind==0)1.65f else 1f,s.kind==2)
        }
    }
    private fun text(c:Canvas,s:String,px:Float,py:Float,size:Float,color:Int) {
        ink.typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD);ink.textSize=size*sp
        ink.color=shadow;ink.style=Paint.Style.STROKE;ink.strokeWidth=2.7f*dp;ink.strokeJoin=Paint.Join.ROUND
        c.drawUiText(s,px,py,ink);ink.style=Paint.Style.FILL;ink.color=color;c.drawUiText(s,px,py,ink)
    }
    private fun drawPlaces(c:Canvas,places:List<AtlasPlace>) {
        val limit=when {spanKm>370->1;spanKm>125->2;spanKm>42->3;else->4}
        for(p in places) {
            if(p.tier>limit || p.lon !in west..east || p.lat !in south..north) continue
            val px=x(p.lon);val py=y(p.lat)
            val font=(if(p.tier<=1)12f else if(p.tier==2)11f else 10f)
            ink.typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD);ink.textSize=font*sp
            val hasCharger = chargerHits.any { it.bounds.contains(px, py) }
            val labelX = px + (if (hasCharger) 17 else 5)*dp
            val box=RectF(labelX-2*dp,py-3*dp+ink.ascent()-3*dp,
                labelX+ink.measureUiText(p.name)+3*dp,py-3*dp+ink.descent()+3*dp)
            if(box.left<0 || box.right>width || box.top<0 || box.bottom>height || labels.any { RectF.intersects(it,box) }) continue
            labels.add(box)
            if (!hasCharger) {
                ink.color=shadow; c.drawCircle(px,py,3.2f*dp,ink)
                ink.color=Color.WHITE;c.drawCircle(px,py,1.8f*dp,ink)
            }
            text(c,p.name,labelX,py-3*dp,font,Color.WHITE)
        }
        if(spanKm>230) return
        for((label,px,py) in badges) {
            ink.textSize=9.5f*sp; ink.typeface=Typeface.DEFAULT_BOLD
            val w=ink.measureUiText(label)+7*dp
            val box=RectF(px,py+ink.ascent()-3*dp,px+w,py+ink.descent()+2*dp)
            if(labels.any { RectF.intersects(it,box) })continue
            labels.add(box);ink.color=Color.rgb(73,53,31)
            c.drawRoundRect(box,2*dp,2*dp,ink)
            text(c,label,px+3*dp,py,9.5f,trunk)
        }
    }
    private fun drawTrip(c:Canvas) {
        for(i in 1 until fixes.size) {
            if(breaks[i]) continue
            val a=fixes[i-1];val b=fixes[i]
            if(max(a.longitude,b.longitude)<west || min(a.longitude,b.longitude)>east ||
                max(a.latitude,b.latitude)<south || min(a.latitude,b.latitude)>north) continue
            val m=if(a.altitudeMeters!=null && b.altitudeMeters!=null) (a.altitudeMeters+b.altitudeMeters)/2 else null
            val ax=x(a.longitude);val ay=y(a.latitude);val bx=x(b.longitude);val by=y(b.latitude)
            ink.strokeCap=Paint.Cap.ROUND;ink.strokeWidth=4.6f*dp;ink.color=shadow;c.drawLine(ax,ay,bx,by,ink)
            ink.strokeWidth=3f*dp;ink.color=altitudeColor(m);c.drawLine(ax,ay,bx,by,ink)
        }
        for ((p,color) in listOf(fixes.firstOrNull() to Color.rgb(24,212,140),fixes.lastOrNull() to Color.rgb(255,143,69))) {
            if(p==null)continue
            ink.color=Color.WHITE;c.drawCircle(x(p.longitude),y(p.latitude),5.6f*dp,ink)
            ink.color=color;c.drawCircle(x(p.longitude),y(p.latitude),3.8f*dp,ink)
        }
    }
    private fun drawRoadPlan(c:Canvas) {
        val points=roadPlan?.points ?: return
        if(points.size<2) return
        path.rewind()
        points.forEachIndexed { index,p ->
            val px=x(p.lon);val py=y(p.lat)
            if(index==0)path.moveTo(px,py) else path.lineTo(px,py)
        }
        ink.style=Paint.Style.STROKE;ink.strokeJoin=Paint.Join.ROUND;ink.strokeCap=Paint.Cap.ROUND
        ink.strokeWidth=6.3f*dp;ink.color=shadow;c.drawPath(path,ink)
        ink.strokeWidth=3.6f*dp;ink.color=Color.rgb(255,100,196);c.drawPath(path,ink)
        ink.style=Paint.Style.FILL
    }
    private fun drawDestinationPin(c:Canvas) {
        val target=destination ?: return
        val px=x(target.lon);val py=y(target.lat)
        if(px < -20*dp || px > width+20*dp || py < -20*dp || py > height+20*dp)return
        ink.color=shadow;c.drawCircle(px,py,10*dp,ink)
        ink.color=Color.rgb(255,100,196);c.drawCircle(px,py,7*dp,ink)
        ink.color=Color.WHITE;c.drawCircle(px,py,2.5f*dp,ink)
        text(c,"CILJ",px+11*dp,py+4*dp,11f,Color.WHITE)
    }
    private fun drawRangeRings(c:Canvas) {
        val origin=range?.origin ?: return
        if (!origin.latitude.isFinite() || !origin.longitude.isFinite()) return
        val circles=listOf(
            range?.oneWayKm to Color.rgb(50,216,240),
            range?.returnKm to Color.rgb(255,213,103)
        )
        for ((index, circle) in circles.withIndex()) {
            val (radius, color) = circle
            if(radius==null || !radius.isFinite() || radius<=0) continue
            path.rewind()
            val phi1=Math.toRadians(origin.latitude)
            val lambda1=Math.toRadians(origin.longitude)
            val arc=radius/6371.0088
            for(i in 0..180) {
                val bearing=2*PI*i/180
                val phi2=asin((sin(phi1)*cos(arc)+cos(phi1)*sin(arc)*cos(bearing)).coerceIn(-1.0,1.0))
                val lambda2=lambda1+atan2(sin(bearing)*sin(arc)*cos(phi1),cos(arc)-sin(phi1)*sin(phi2))
                val px=x(Math.toDegrees(lambda2));val py=y(Math.toDegrees(phi2))
                if(i==0) path.moveTo(px,py) else path.lineTo(px,py)
            }
            path.close()
            ink.style=Paint.Style.FILL
            ink.color=Color.argb(16,Color.red(color),Color.green(color),Color.blue(color))
            c.drawPath(path,ink)
            ink.style=Paint.Style.STROKE;ink.strokeWidth=5.5f*dp;ink.color=shadow
            c.drawPath(path,ink)
            ink.pathEffect=if(index==1) DashPathEffect(floatArrayOf(7*dp,5*dp),0f) else null
            ink.strokeWidth=2.7f*dp;ink.color=color;c.drawPath(path,ink)
            ink.style=Paint.Style.FILL;ink.pathEffect=null
        }
        ink.color=Color.WHITE;c.drawCircle(x(origin.longitude),y(origin.latitude),6*dp,ink)
        ink.color=Color.rgb(8,63,83);c.drawCircle(x(origin.longitude),y(origin.latitude),3.5f*dp,ink)
    }
    private fun altitudeColor(m:Double?):Int {
        if(m==null || !m.isFinite())return Color.LTGRAY
        val f=if(altMax>altMin)((m-altMin)/(altMax-altMin)).coerceIn(0.0,1.0) else 0.0
        val colors=intArrayOf(0xff32d8f0.toInt(),0xffffd567.toInt(),0xfff2669c.toInt())
        val i=if(f<.5)0 else 1;val t=f*2-i;val a=colors[i];val b=colors[i+1]
        fun mix(x:Int,y:Int)=(x+(y-x)*t).roundToInt()
        return Color.rgb(mix(Color.red(a),Color.red(b)),mix(Color.green(a),Color.green(b)),mix(Color.blue(a),Color.blue(b)))
    }
    private fun drawScale(c:Canvas) {
        val metersPerPx=degrees*cos(Math.toRadians(lat-(height*.5-9*dp)*stepY))*111195.0
        val maxMeters=metersPerPx*min(92.0*dp,width*.29)
        val base=10.0.pow(floor(log10(maxMeters)))
        val meters=(if(maxMeters>=5*base) 5 else if(maxMeters>=2*base) 2 else 1)*base
        val length=(meters/metersPerPx).toFloat()
        val distance=if(meters>=1000)format(meters/1000)+" km" else format(meters)+" m"
        val span="širina "+format(spanKm)+" km"
        val title=distance
        val left=9f*dp;val bottom=height-9*dp
        ink.textSize=10*sp;ink.typeface=Typeface.DEFAULT_BOLD
        val right=left+max(length,ink.measureUiText(title))+10*dp
        ink.style=Paint.Style.FILL
        ink.color=Color.argb(215,11,25,34)
        c.drawRoundRect(left-3*dp,bottom-10*dp+ink.ascent()-4*dp,right,bottom+5*dp,4*dp,4*dp,ink)
        text(c,title,left,bottom-10*dp,10f,Color.WHITE)
        ink.color=Color.WHITE;ink.strokeWidth=1.5f*dp
        c.drawLine(left,bottom,left+length,bottom,ink);c.drawLine(left,bottom,left,bottom-5*dp,ink)
        c.drawLine(left+length,bottom,left+length,bottom-5*dp,ink)
        val desc="Offline mapa, "+span+", razmernik "+distance +
            (if (vectors == null) ", učitavanje puteva i naselja" else ", učitani putevi i naselja") +
            (if (relief) ", $terrainStatus" else "")
        if(contentDescription!=UiStrings.text(desc)) contentDescription=UiStrings.text(desc)
    }
    private fun format(v:Double)=if(v>=10 || v==v.roundToInt().toDouble()) "%.0f".format(Locale.US,v)
        else "%.1f".format(Locale.US,v)
}

