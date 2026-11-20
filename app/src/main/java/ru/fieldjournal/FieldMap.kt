package ru.fieldjournal

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.*

@android.annotation.SuppressLint("ViewConstructor")
internal class FieldMap(context: Context, private val tiles: MapTiles) : View(context) {
    private data class TileKey(val generation: Int, val tile: Tile, val pass: TilePass)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scale = resources.displayMetrics.density
    private val workers = Executors.newFixedThreadPool(4)
    private val memory = object : LruCache<TileKey, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: TileKey, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableSetOf<TileKey>()
    private val unavailable = mutableMapOf<TileKey, Long>()
    @Volatile private var generation = 0
    private var offlineInfo: OfflineInfo? = null
    private var layer = tiles.layer
    private var offline = tiles.offline
    private var historyInfo: OfflineInfo? = null
    private var referenceInfo: OfflineInfo? = null
    private var onlineReferences = false
    private var attribution = emptyList<String>()
    var onTap: ((Coordinate, Site?) -> Unit)? = null
    var onMove: ((Coordinate, Double) -> Unit)? = null
    var onVertexTap: ((Int) -> Unit)? = null
    var onVertexMove: ((Int, Coordinate) -> Unit)? = null
    var pointPreview: Coordinate? = null
        set(value) { field = value; invalidate() }
    var editingGeometry = false
        set(value) {
            if (field == value) return
            field = value
            drag = null
            gestures.setOnDoubleTapListener(if (value) null else gestureListener)
        }

    private data class VertexDrag(val index: Int, val offset: PointF, var point: Coordinate)
    private var drag: VertexDrag? = null
    var sites: List<Site> = emptyList()
        set(value) { field = value; invalidate() }
    var draft: List<Coordinate> = emptyList()
        set(value) { field = value; invalidate() }
    var locate: LocationFix? = null
        set(value) { field = value; invalidate() }
    var crosshair = false
        set(value) { field = value; invalidate() }
    var center = tiles.viewport().first
        private set
    var zoom = tiles.viewport().second
        private set

    private val world get() = 256.0 * scale * 2.0.pow(zoom)
    private val rootX get() = (center.longitude + 180) / 360 * world
    private val rootY get() = mercator(center.latitude) * world

    init {
        contentDescription = "Карта исследуемых объектов"
        isFocusable = true
        refresh()
    }

    fun refresh() {
        layer = tiles.layer
        offline = tiles.offline
        offlineInfo = if (offline) tiles.info(tiles.baseArchive(layer)) else null
        historyInfo = if (tiles.historicalEnabled) tiles.info(MapPackage.HISTORICAL) else null
        val showReferences = tiles.showReferences()
        onlineReferences = showReferences && tiles.referenceSource() == ReferenceSource.ESRI
        referenceInfo = if (showReferences && !onlineReferences) tiles.info(MapPackage.REFERENCES) else null
        attribution = listOfNotNull(
            tiles.credit(layer, offline).takeIf(String::isNotBlank),
            historyInfo?.let { it.attribution.ifBlank { it.name } },
            referenceInfo?.let { it.attribution.ifBlank { it.name } },
            "Дороги и подписи © Esri, HERE, Garmin, OpenStreetMap contributors, GIS User Community".takeIf { onlineReferences }
        ).distinct()
        generation++
        memory.evictAll()
        unavailable.clear()
        invalidate()
    }

    fun release() = workers.shutdownNow()

    fun moveTo(point: Coordinate, requestedZoom: Double = zoom) {
        center = Coordinate(point.latitude.coerceIn(-85.05112878, 85.05112878), point.longitude)
        zoom = requestedZoom.coerceIn(2.0, 22.0)
        onMove?.invoke(center, zoom)
        invalidate()
    }

    fun show(site: Site) {
        val anchor = site.anchor
        if (!site.area || width == 0 || height == 0) {
            moveTo(anchor, 16.0)
            return
        }
        val longitudeSpan = site.vertices.maxOf { abs(longitudeDelta(it.longitude, anchor.longitude)) } / 360 * 2
        val latitudeSpan = site.vertices.maxOf { abs(mercator(it.latitude) - mercator(anchor.latitude)) } * 2
        val factor = min(width * 0.7 / max(longitudeSpan, 1e-8), height * 0.65 / max(latitudeSpan, 1e-8))
        moveTo(anchor, log2(factor / (256 * scale)).coerceAtMost(19.0))
    }

    fun zoomBy(delta: Double) = moveTo(center, zoom + delta)

    private fun coordinateAt(x: Float, y: Float) = Coordinate(degreeLatitude((rootY + y - height / 2.0) / world),
        wrapLongitude((rootX + x - width / 2.0) / world * 360 - 180))

    private fun pixel(point: Coordinate) = PointF(
        (width / 2.0 + longitudeDelta(point.longitude, center.longitude) / 360 * world).toFloat(),
        (height / 2.0 + mercator(point.latitude) * world - rootY).toFloat()
    )

    private fun vertexAt(x: Float, y: Float): Int? {
        var nearest: Int? = null
        var distance = 20 * scale
        for ((index, point) in draft.withIndex()) {
            val p = pixel(point)
            val candidate = hypot(p.x - x, p.y - y)
            if (candidate <= distance) {
                nearest = index
                distance = candidate
            }
        }
        return nearest
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(first: MotionEvent?, next: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaler.isInProgress) moveTo(coordinateAt(width / 2f + distanceX, height / 2f + distanceY))
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAt(e.x, e.y, 1.0)
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!editingGeometry) return false
            performClick()
            val index = vertexAt(e.x, e.y)
            if (index == null) onTap?.invoke(coordinateAt(e.x, e.y), null)
            else onVertexTap?.invoke(index)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!editingGeometry || onVertexMove == null) return
            val index = vertexAt(e.x, e.y) ?: return
            val pixel = pixel(draft[index])
            drag = VertexDrag(index, PointF(pixel.x - e.x, pixel.y - e.y), draft[index])
            invalidate()
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (editingGeometry) return false
            performClick()
            val site = sites.firstOrNull {
                val p = pixel(it.anchor)
                hypot(p.x - e.x, p.y - e.y) <= 26 * scale ||
                    it.area && inside(it.vertices, e.x, e.y) && it.holes.none { hole -> inside(hole, e.x, e.y) }
            }
            onTap?.invoke(coordinateAt(e.x, e.y), site)
            return true
        }
    }
    private val gestures = GestureDetector(context, gestureListener)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(detector.focusX, detector.focusY, log2(detector.scaleFactor.toDouble()))
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private fun zoomAt(x: Float, y: Float, delta: Double) {
        val focus = coordinateAt(x, y)
        zoom = (zoom + delta).coerceIn(2.0, 22.0)
        val movedFocus = pixel(focus)
        moveTo(coordinateAt(width / 2f + movedFocus.x - x, height / 2f + movedFocus.y - y))
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector calls performClick for confirmed taps.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        drag?.let { active ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    active.point = coordinateAt(event.x + active.offset.x, event.y + active.offset.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    drag = null
                    gestures.onTouchEvent(event)
                    onVertexMove?.invoke(active.index, active.point)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    drag = null
                    invalidate()
                }
            }
        }
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun inside(points: List<Coordinate>, x: Float, y: Float): Boolean {
        val screen = points.map(::pixel)
        var found = false
        var previous = screen.last()
        for (p in screen) {
            if ((p.y > y) != (previous.y > y) && x < (previous.x - p.x) * (y - p.y) / (previous.y - p.y) + p.x) found = !found
            previous = p
        }
        return found
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xffe3e8db.toInt())
        drawGrid(canvas)
        val baseZoom = if (offline) offlineInfo?.maxZoom else if (layer == MapLayer.STREET) 19 else 22
        val baseDrawn = baseZoom?.let { drawTiles(canvas, TilePass.BASE, it) } ?: false
        val historyDrawn = historyInfo?.let {
            drawTiles(canvas, TilePass.HISTORICAL, it.maxZoom, tiles.historicalOpacity * 255 / 100)
        } ?: false
        if (onlineReferences) {
            drawTiles(canvas, TilePass.ROADS, 19)
            drawTiles(canvas, TilePass.PLACES, 19)
        } else referenceInfo?.let { drawTiles(canvas, TilePass.LOCAL_REFERENCES, it.maxZoom) }
        for (site in sites.asReversed()) {
            val type = objectType(site.type)
            if (site.area) drawShape(canvas, listOf(site.vertices) + site.holes, type.color, true)
            val p = pixel(site.anchor)
            if (p.x in -28f..width + 28f && p.y in -28f..height + 28f) {
                drawSymbol(canvas, p.x, p.y, 16 * scale, type)
                if (zoom >= 14) drawLabel(canvas, site.title.take(24), p.x + 18 * scale, p.y + 4 * scale)
            }
        }
        if (draft.isNotEmpty()) {
            val active = drag
            val boundary = if (active == null) draft else draft.mapIndexed { index, point ->
                if (index == active.index) active.point else point
            }
            drawShape(canvas, listOf(boundary), Palette.rust, boundary.size >= 3)
            for ((index, point) in boundary.withIndex()) {
                val p = pixel(point)
                paint.color = Color.WHITE
                val radius = if (index == active?.index) 13 * scale else 10 * scale
                canvas.drawCircle(p.x, p.y, radius + 2 * scale, paint)
                paint.color = if (index == active?.index) Palette.pine else Palette.rust
                canvas.drawCircle(p.x, p.y, radius, paint)
                paint.color = Color.WHITE
                paint.textSize = 10 * scale
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText((index + 1).toString(), p.x, p.y + 3.4f * scale, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        locate?.let { fix ->
            val p = pixel(fix.point)
            val radius = (fix.accuracy / metersPerPixel()).toFloat().coerceIn(4 * scale, max(width, height).toFloat().coerceAtLeast(4 * scale))
            paint.color = 0x334078e5
            canvas.drawCircle(p.x, p.y, radius, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, 8 * scale, paint)
            paint.color = 0xff2d63c9.toInt()
            canvas.drawCircle(p.x, p.y, 6 * scale, paint)
        }
        pointPreview?.let {
            val p = pixel(it)
            paint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, 10 * scale, paint)
            paint.color = Palette.rust
            canvas.drawCircle(p.x, p.y, 7 * scale, paint)
        }
        if (crosshair) drawCrosshair(canvas)
        val creditLines = attribution.flatMap { credit -> wrapMapLabel(credit, 9f) }
        if (!baseDrawn && !historyDrawn && pending.isEmpty()) {
            val message = when {
                offline && offlineInfo == null -> "Подключите офлайн-карту в разделе «Слои»"
                offline -> "Для этого участка нет офлайн-тайлов"
                unavailable.isNotEmpty() -> "Подложка недоступна · проверьте сеть"
                else -> null
            }
            message?.let {
                val lines = wrapMapLabel(it, 11f)
                val bottom = height - (38 + 12 * creditLines.size) * scale
                lines.forEachIndexed { index, line ->
                    drawLabel(canvas, line, 12 * scale, bottom - 15 * scale * (lines.lastIndex - index))
                }
            }
        }
        drawScale(canvas, creditLines.size)
        creditLines.forEachIndexed { index, line ->
            drawLabel(canvas, line, 12 * scale, height - (7 + 11 * (creditLines.lastIndex - index)) * scale, 9f)
        }
    }

    private fun wrapMapLabel(text: String, size: Float): List<String> {
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = size * scale
        val available = (width - 74 * scale).coerceAtLeast(0f)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(Regex("\\s+"))) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(next) > available) {
                lines.add(line)
                line = word
            } else line = next
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines.take(2).map { it.take(paint.breakText(it, true, available, null)) }
    }

    private fun drawGrid(canvas: Canvas) {
        paint.color = 0xffcbd4c6.toInt()
        paint.strokeWidth = scale
        val spacing = 64 * scale
        var x = (-rootX % spacing).toFloat()
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paint); x += spacing }
        var y = (-rootY % spacing).toFloat()
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paint); y += spacing }
    }

    private fun drawTiles(canvas: Canvas, pass: TilePass, nativeLimit: Int, alpha: Int = 255): Boolean {
        val z = floor(zoom).toInt().coerceAtMost(nativeLimit)
        val size = world / (1 shl z)
        val count = 1 shl z
        val left = rootX - width / 2.0
        val top = rootY - height / 2.0
        val firstX = floor(left / size).toInt()
        val firstY = floor(top / size).toInt().coerceAtLeast(0)
        val lastX = floor((left + width) / size).toInt()
        val lastY = floor((top + height) / size).toInt().coerceAtMost(count - 1)
        paint.color = Color.WHITE
        paint.alpha = alpha
        var drawn = false
        for (y in firstY..lastY) for (x in firstX..lastX) {
            val tile = Tile(z, Math.floorMod(x, count), y)
            val rect = Rect(
                (x * size - left).roundToInt(), (y * size - top).roundToInt(),
                ((x + 1) * size - left).roundToInt(), ((y + 1) * size - top).roundToInt()
            )
            val bitmap = image(tile, pass)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, paint)
                drawn = true
            } else cachedAncestor(tile, pass)?.let { (ancestor, source) ->
                canvas.drawBitmap(ancestor, source, rect, paint)
                drawn = true
            }
        }
        paint.alpha = 255
        return drawn
    }

    private fun cachedAncestor(tile: Tile, pass: TilePass): Pair<Bitmap, Rect>? {
        for (delta in 1..min(tile.z, 5)) {
            val parent = Tile(tile.z - delta, tile.x shr delta, tile.y shr delta)
            val bitmap = memory.get(TileKey(generation, parent, pass)) ?: continue
            val divisions = 1 shl delta
            val stepX = max(1, bitmap.width / divisions)
            val stepY = max(1, bitmap.height / divisions)
            val left = tile.x % divisions * stepX
            val top = tile.y % divisions * stepY
            if (left < bitmap.width && top < bitmap.height) {
                return bitmap to Rect(left, top, min(bitmap.width, left + stepX), min(bitmap.height, top + stepY))
            }
        }
        return null
    }

    private fun image(tile: Tile, pass: TilePass): Bitmap? {
        val key = TileKey(generation, tile, pass)
        memory.get(key)?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        if (pending.size >= 48 || key in pending || (unavailable[key] ?: 0L) > now || workers.isShutdown) return null
        pending.add(key)
        val currentLayer = layer
        val currentOffline = offline
        val currentTemplate = tiles.customUrl
        workers.execute {
            val image = if (key.generation != generation) null else
                runCatching { tiles.read(currentLayer, currentOffline, currentTemplate, tile, pass) }.getOrNull()
            post {
                pending.remove(key)
                if (key.generation == generation) {
                    if (image != null) memory.put(key, image) else {
                        if (unavailable.size > 512) unavailable.clear()
                        unavailable[key] = android.os.SystemClock.elapsedRealtime() + 30000
                    }
                }
                invalidate()
            }
        }
        return null
    }

    private fun drawShape(canvas: Canvas, rings: List<List<Coordinate>>, color: Int, closed: Boolean) {
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (ring in rings) {
            for ((index, coordinate) in ring.withIndex()) {
                val p = pixel(coordinate)
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            if (closed) path.close()
        }
        if (closed) {
            paint.color = color and 0x00ffffff or 0x48000000
            canvas.drawPath(path, paint)
        }
        paint.color = color
        paint.strokeWidth = 2.5f * scale
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCrosshair(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        for ((color, lineWidth) in listOf(Color.WHITE to 4f, 0xff184536.toInt() to 2f)) {
            paint.color = color
            paint.strokeWidth = lineWidth * scale
            canvas.drawLine(x - 13 * scale, y, x + 13 * scale, y, paint)
            canvas.drawLine(x, y - 13 * scale, x, y + 13 * scale, paint)
        }
    }

    private fun metersPerPixel() = 40075016.686 * cos(Math.toRadians(center.latitude)) / world

    private fun drawScale(canvas: Canvas, creditLines: Int) {
        val approximate = metersPerPixel() * 94 * scale
        val unit = 10.0.pow(floor(log10(approximate)))
        val distance = listOf(1, 2, 5).last { it * unit <= approximate + 1e-10 } * unit
        val x = 12 * scale
        val y = height - (13 + 12 * creditLines) * scale
        val w = (distance / metersPerPixel()).toFloat()
        val text = when {
            distance >= 1000 -> "${(distance / 1000).roundToInt()} км"
            distance < 1 -> "${(distance * 100).roundToInt()} см"
            else -> "${distance.roundToInt()} м"
        }
        drawLabel(canvas, text, x, y - 4 * scale, 10f)
        paint.color = Color.WHITE
        paint.strokeWidth = 5 * scale
        canvas.drawLine(x, y, x + w, y, paint)
        paint.color = 0xff243a30.toInt()
        paint.strokeWidth = 2 * scale
        canvas.drawLine(x, y, x + w, y, paint)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, size: Float = 11f) {
        paint.textSize = size * scale
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.color = 0xeafffdf6.toInt()
        canvas.drawRoundRect(x - 3 * scale, y - (size + 1) * scale, x + paint.measureText(text) + 3 * scale,
            y + 3 * scale, 3 * scale, 3 * scale, paint)
        paint.color = 0xff25372d.toInt()
        canvas.drawText(text, x, y, paint)
        paint.typeface = Typeface.DEFAULT
    }
}
