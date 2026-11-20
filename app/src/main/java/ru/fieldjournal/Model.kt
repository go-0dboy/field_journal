package ru.fieldjournal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.*

internal data class Coordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) {
            "Координаты должны быть в пределах WGS 84"
        }
    }

    fun json() = JSONArray().put(longitude).put(latitude)
    fun display() = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    companion object {
        fun read(json: JSONArray) = Coordinate(json.getDouble(1), json.getDouble(0))
    }
}

internal data class ObjectType(val id: String, val title: String, val color: Int)

internal val objectTypes = listOf(
    ObjectType("camp", "Стоянка", 0xff28665b.toInt()),
    ObjectType("settlement", "Селище", 0xff356295.toInt()),
    ObjectType("fort", "Городище", 0xff325668.toInt()),
    ObjectType("mound", "Курган", 0xffa4512f.toInt()),
    ObjectType("mounds", "Курганная группа", 0xff963f28.toInt()),
    ObjectType("cemetery", "Могильник / некрополь", 0xff765287.toInt()),
    ObjectType("find", "Место находки", 0xff8a641f.toInt()),
    ObjectType("excavation", "Раскоп / шурф", 0xff48764b.toInt()),
    ObjectType("tract", "Историческое урочище", 0xff4e6744.toInt()),
    ObjectType("complex", "Исторический комплекс", 0xff325668.toInt()),
    ObjectType("church", "Храм", 0xff87516b.toInt()),
    ObjectType("bell_tower", "Колокольня / звонница", 0xff326f73.toInt()),
    ObjectType("building", "Историческое сооружение", 0xff855746.toInt()),
    ObjectType("road", "Дорога / переправа", 0xff575c70.toInt()),
    ObjectType("other", "Другое / не определено", 0xff536358.toInt())
)

internal fun objectType(id: String) = objectTypes.find { it.id == id } ?: objectTypes.last()
internal fun newId() = UUID.randomUUID().toString()
internal val attachmentId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.arrays() = (0 until length()).map { getJSONArray(it) }
internal fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
internal fun JSONObject.nullableDouble(key: String) = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

internal data class Attachment(val id: String, val name: String, val mime: String) {
    init { require(attachmentId.matches(id)) { "Недопустимый идентификатор вложения" } }
    fun json() = JSONObject().put("id", id).put("name", name).put("mime", mime)
    companion object {
        fun read(value: JSONObject) = Attachment(value.getString("id"), value.getString("name"), value.optString("mime", "application/octet-stream"))
    }
}

internal enum class CoordinateOrigin(val id: String, val title: String) {
    DEVICE("device", "Положение телефона"),
    MAP("map", "Точка на карте"),
    MANUAL("manual", "Введено вручную");

    companion object {
        fun read(id: String) = entries.firstOrNull { it.id == id }
    }
}

internal val verifiableSiteFields = setOf("period", "culture", "condition", "context")

internal data class Site(
    val id: String = newId(),
    val title: String = "",
    val type: String = "other",
    val vertices: List<Coordinate>,
    val holes: List<List<Coordinate>> = emptyList(),
    val description: String = "",
    val period: String = "",
    val culture: String = "",
    val condition: String = "",
    val inventory: String = "",
    val author: String = "",
    val confidence: String = "",
    val context: String = "",
    val sources: String = "",
    val accuracy: Double? = null,
    val altitude: Double? = null,
    val attachments: List<Attachment> = emptyList(),
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
    val coordinateOrigin: CoordinateOrigin? = null,
    val measuredAt: Long? = null,
    val uncertainFields: Set<String> = emptySet(),
    val parentId: String? = null,
    val customColor: Int? = null
) {
    init { require(vertices.size == 1 || vertices.size >= 3) }
    val symbol get() = objectType(type).let { customColor?.let { color -> it.copy(color = color) } ?: it }
    val area get() = vertices.size > 1
    val anchor get() = if (!area) vertices.first() else polygonCenter(vertices)

    fun feature(normalizeWinding: Boolean = false): JSONObject {
        val geometry = JSONObject()
        if (!area) {
            geometry.put("type", "Point").put("coordinates", anchor.json())
        } else {
            val rings = listOf(vertices) + holes
            geometry.put("type", "Polygon").put("coordinates", JSONArray(rings.mapIndexed { index, ring ->
                val coordinates = if (normalizeWinding && counterclockwise(ring) != (index == 0)) ring.reversed() else ring
                JSONArray((coordinates + coordinates.first()).map(Coordinate::json))
            }))
        }
        val properties = JSONObject()
            .put("title", title).put("type", type).put("description", description)
            .put("period", period).put("culture", culture).put("condition", condition)
            .put("inventory", inventory).put("author", author).put("confidence", confidence)
            .put("context", context).put("sources", sources)
            .put("accuracy_m", accuracy ?: JSONObject.NULL).put("altitude_m", altitude ?: JSONObject.NULL)
            .put("attachments", JSONArray(attachments.map(Attachment::json)))
            .put("created", created).put("updated", updated)
        parentId?.let { properties.put("parent_id", it) }
        customColor?.let { properties.put("color", String.format(Locale.ROOT, "#%06X", it and 0xffffff)) }
        coordinateOrigin?.let { properties.put("coordinate_origin", it.id) }
        measuredAt?.let { properties.put("measured_at", it) }
        if (uncertainFields.isNotEmpty()) properties.put("uncertain_fields", JSONArray(uncertainFields.sorted()))
        return JSONObject().put("type", "Feature").put("id", id).put("geometry", geometry).put("properties", properties)
    }

    companion object {
        fun read(
            feature: JSONObject, withAttachments: Boolean, validate: Boolean = true,
            validateTopology: Boolean = validate
        ): Site {
            require(feature.getString("type") == "Feature") { "Ожидается объект GeoJSON Feature" }
            val p = feature.optJSONObject("properties") ?: JSONObject()
            val title = p.optString("title").ifBlank { p.optString("name", "Объект без названия") }
            val geometry = feature.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")
            val rings = when (geometry.getString("type")) {
                "Point" -> listOf(listOf(Coordinate.read(coords)))
                "Polygon" -> coords.arrays().mapIndexed { index, ring ->
                    val points = ring.arrays().map(Coordinate::read)
                    require(points.size >= 4 && points.first() == points.last()) { "«$title», контур ${index + 1} должен быть замкнут" }
                    points.dropLast(1).also {
                        if (validate) polygonError(it)?.let { error ->
                            throw IllegalArgumentException("«$title», контур ${index + 1}. ${error.message}")
                        }
                    }
                }.also { require(it.isNotEmpty()) { "У полигона отсутствует контур" } }
                else -> error("Поддерживаются геометрии Point и Polygon")
            }
            if (validateTopology) holeError(rings)?.let { error -> throw IllegalArgumentException("«$title». $error") }
            val attachments = if (withAttachments) p.array("attachments").objects().map(Attachment::read) else emptyList()
            val created = p.optLong("created", System.currentTimeMillis())
            val id = feature.optString("id").takeIf { !feature.isNull("id") && it.isNotBlank() }
                ?: if (withAttachments) newId() else UUID.nameUUIDFromBytes(feature.toString().toByteArray(Charsets.UTF_8)).toString()
            return Site(
                id, title,
                p.optString("type", "other"), rings.first(), rings.drop(1), p.optString("description"),
                p.optString("period"), p.optString("culture"), p.optString("condition"),
                p.optString("inventory"), p.optString("author"), p.optString("confidence"),
                p.optString("context"), p.optString("sources"), p.nullableDouble("accuracy_m"),
                p.nullableDouble("altitude_m"), attachments, created, p.optLong("updated", created),
                coordinateOrigin = CoordinateOrigin.read(p.optString("coordinate_origin")),
                measuredAt = p.optLong("measured_at").takeIf { it > 0 },
                parentId = p.optString("parent_id").takeIf { !p.isNull("parent_id") && it.isNotBlank() && it != id },
                customColor = p.optString("color").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                    ?.substring(1)?.toInt(16)?.or(0xff000000.toInt()),
                uncertainFields = p.array("uncertain_fields").let { fields ->
                    (0 until fields.length()).map(fields::optString).filterTo(linkedSetOf()) { it in verifiableSiteFields }
                }
            )
        }
    }
}

internal data class Note(
    val id: String = newId(),
    val title: String = "",
    val body: String = "",
    val author: String = "",
    val weather: String = "",
    val siteId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
    val tripId: String? = null
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("body", body)
        .put("author", author).put("weather", weather).put("site_id", siteId ?: JSONObject.NULL)
        .put("attachments", JSONArray(attachments.map(Attachment::json)))
        .put("created", created).put("updated", updated)
        .apply { tripId?.let { put("trip_id", it) } }

    companion object {
        fun read(value: JSONObject): Note = Note(
            value.getString("id"), value.getString("title"), value.optString("body"),
            value.optString("author"), value.optString("weather"),
            if (value.isNull("site_id")) null else value.optString("site_id"),
            value.array("attachments").objects().map(Attachment::read), value.getLong("created"),
            value.optLong("updated", value.getLong("created")),
            value.optString("trip_id").takeIf(attachmentId::matches)
        )
    }
}

internal fun dateLabel(time: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

internal fun longitudeDelta(lon: Double, reference: Double): Double {
    val delta = lon - reference
    return when {
        delta >= 180.0 -> delta - 360.0
        delta < -180.0 -> delta + 360.0
        else -> delta
    }
}
internal fun wrapLongitude(lon: Double) = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

internal fun polygonCenter(points: List<Coordinate>): Coordinate {
    val reference = points.first().longitude
    return Coordinate(points.map { it.latitude }.average(), wrapLongitude(reference + points.map { longitudeDelta(it.longitude, reference) }.average()))
}

internal fun areaSquareMeters(points: List<Coordinate>): Double {
    val radians = PI / 180.0
    val sum = points.indices.sumOf { index ->
        val a = points[index]
        val b = points[(index + 1) % points.size]
        longitudeDelta(b.longitude, a.longitude) * radians * (sin(a.latitude * radians) + sin(b.latitude * radians))
    }
    return abs(sum) * 6371008.8.pow(2) / 2
}

private fun counterclockwise(ring: List<Coordinate>): Boolean {
    val reference = ring.first()
    val signedArea = ring.indices.sumOf { index ->
        val a = ring[index]
        val b = ring[(index + 1) % ring.size]
        longitudeDelta(a.longitude, reference.longitude) * (b.latitude - reference.latitude) -
            longitudeDelta(b.longitude, reference.longitude) * (a.latitude - reference.latitude)
    }
    return signedArea > 0
}

internal enum class BoundaryError(val message: String) {
    TOO_FEW("Добавьте не менее трёх вершин"),
    TOO_MANY("Допускается не более 2000 вершин"),
    REPEATED("Две вершины находятся в одной точке"),
    NO_AREA("Вершины лежат на одной линии и не образуют область"),
    CROSSED("Стороны границы пересекаются или накладываются")
}

internal fun validPolygon(points: List<Coordinate>) = polygonError(points) == null

internal fun polygonError(points: List<Coordinate>): BoundaryError? {
    if (points.size < 3) return BoundaryError.TOO_FEW
    if (points.size > 2000) return BoundaryError.TOO_MANY
    val reference = points.first()
    val plane = points.map { longitudeDelta(it.longitude, reference.longitude) to it.latitude - reference.latitude }
    if (plane.distinct().size != plane.size) return BoundaryError.REPEATED

    if (plane.drop(2).all { orientation(plane[0], plane[1], it) == 0 }) return BoundaryError.NO_AREA

    for (i in plane.indices) {
        val a = plane[i]
        val b = plane[(i + 1) % plane.size]
        val next = plane[(i + 2) % plane.size]
        val dot = (a.first - b.first) * (next.first - b.first) + (a.second - b.second) * (next.second - b.second)
        if (orientation(a, b, next) == 0 && dot > 0) return BoundaryError.CROSSED

        for (j in i + 2 until plane.size) {
            if (i == 0 && j == plane.lastIndex) continue
            val c = plane[j]
            val d = plane[(j + 1) % plane.size]
            if (segmentsMeet(a, b, c, d)) return BoundaryError.CROSSED
        }
    }
    return null
}

private fun orientation(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Int {
    val left = (b.first - a.first) * (c.second - a.second)
    val right = (b.second - a.second) * (c.first - a.first)
    val cross = left - right
    if (abs(cross) <= 4 * Math.ulp(max(abs(left), abs(right)))) return 0
    return if (cross < 0) -1 else 1
}

private fun segmentsMeet(
    a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>
): Boolean {
    if (max(min(a.first, b.first), min(c.first, d.first)) > min(max(a.first, b.first), max(c.first, d.first)) ||
        max(min(a.second, b.second), min(c.second, d.second)) > min(max(a.second, b.second), max(c.second, d.second))) return false
    return orientation(a, b, c) * orientation(a, b, d) <= 0 &&
        orientation(c, d, a) * orientation(c, d, b) <= 0
}

internal fun holeError(rings: List<List<Coordinate>>): String? {
    if (rings.size < 2) return null
    val reference = rings.first().first()
    val plane = rings.map { ring -> ring.map { longitudeDelta(it.longitude, reference.longitude) to it.latitude - reference.latitude } }

    fun contains(ring: List<Pair<Double, Double>>, point: Pair<Double, Double>): Boolean {
        var inside = false
        for (i in ring.indices) {
            val (ax, ay) = ring[i]
            val (bx, by) = ring[(i + 1) % ring.size]
            if ((ay > point.second) != (by > point.second) &&
                point.first < ax + (bx - ax) * (point.second - ay) / (by - ay)) inside = !inside
        }
        return inside
    }

    fun boundariesMeet(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>) = a.indices.any { i ->
        b.indices.any { j -> segmentsMeet(a[i], a[(i + 1) % a.size], b[j], b[(j + 1) % b.size]) }
    }

    val outer = plane.first()
    for (i in 1 until plane.size) {
        val hole = plane[i]
        if (!contains(outer, hole.first()) || boundariesMeet(outer, hole)) {
            return "Внутренний контур $i должен полностью лежать внутри внешнего"
        }
        for (j in 1 until i) {
            val other = plane[j]
            if (contains(other, hole.first()) || contains(hole, other.first()) || boundariesMeet(hole, other)) {
                return "Внутренние контуры $j и $i перекрываются или касаются"
            }
        }
    }
    return null
}

internal fun Site.containsGeometry(points: List<Coordinate>, innerRings: List<List<Coordinate>> = emptyList()): Boolean {
    if (!area || points.isEmpty()) return false
    val reference = vertices.first()
    fun plane(point: Coordinate) = longitudeDelta(point.longitude, reference.longitude) to
        (point.latitude - reference.latitude)
    val boundaries = (listOf(vertices) + holes).map { it.map(::plane) }
    val child = points.map(::plane)
    fun contains(ring: List<Pair<Double, Double>>, point: Pair<Double, Double>): Boolean {
        var inside = false
        for (index in ring.indices) {
            val a = ring[index]
            val b = ring[(index + 1) % ring.size]
            if (segmentsMeet(a, b, point, point)) return false
            if ((a.second > point.second) != (b.second > point.second) &&
                point.first < a.first + (point.second - a.second) * (b.first - a.first) / (b.second - a.second)) {
                inside = !inside
            }
        }
        return inside
    }
    if (!contains(boundaries.first(), child.first())) return false
    val childEdges = if (child.size == 1) listOf(child.first() to child.first())
        else child.indices.map { child[it] to child[(it + 1) % child.size] }
    if (boundaries.any { ring -> ring.indices.any { i ->
            childEdges.any { (a, b) -> segmentsMeet(ring[i], ring[(i + 1) % ring.size], a, b) }
        } }) return false
    return boundaries.drop(1).none { hole ->
        contains(hole, child.first()) ||
            (child.size > 1 && contains(child, hole.first()) &&
                innerRings.none { contains(it.map(::plane), hole.first()) })
    }
}

internal fun areaLabel(squareMeters: Double) = if (squareMeters >= 10000)
    String.format(Locale.forLanguageTag("ru"), "%.2f га", squareMeters / 10000)
else String.format(Locale.forLanguageTag("ru"), "%.1f м²", squareMeters)
