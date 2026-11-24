package ru.fieldjournal

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateReferenceSystem
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import kotlin.math.abs

internal class OziCalibration(data: ByteArray, val width: Int, val height: Int) {
    val title: String
    val rasterName: String
    private val corners: List<Pixel>
    private val toMap: org.locationtech.proj4j.CoordinateTransform
    private val toWgs: org.locationtech.proj4j.CoordinateTransform
    private val affine: Affine

    init {
        val lines = decode(data).lineSequence().map { it.trim() }.toList()
        require(lines.firstOrNull()?.startsWith("OziExplorer Map Data File Version ", ignoreCase = true) == true && lines.size >= 5) {
            "Выберите файл геопривязки OziExplorer .map"
        }
        title = lines[1].take(160)
        rasterName = lines[2].replace('\\', '/').substringAfterLast('/').trim('"')
        val datum = lines[4].substringBefore(',').trim()
        val ellipsoid = when (datum.uppercase()) {
            "WGS 84", "WGS84" -> "+datum=WGS84"
            "PULKOVO 1942", "PULKOVO 1942 (1)", "PULKOVO 1942 (2)", "СК-42", "SK-42" ->
                "+ellps=krass +towgs84=28,-130,-95"
            "POTSDAM RAUENBERG DHDN" -> "+ellps=bessel +towgs84=606,23,413"
            "EUROPEAN 1950" -> "+ellps=intl +towgs84=-87,-98,-121"
            else -> throw IllegalArgumentException("Система координат «$datum» в файле OziExplorer не поддерживается")
        }
        val rows = lines.drop(5).map { line -> line.split(',').map(String::trim) }
        fun row(key: String) = rows.firstOrNull { it[0].equals(key, ignoreCase = true) }.orEmpty()
        val projection = row("Map Projection")
        val polyCal = projection.indexOfFirst { it.equals("PolyCal", ignoreCase = true) }
        require(polyCal < 0 || !projection.getOrNull(polyCal + 1).equals("Yes", ignoreCase = true)) {
            "Полиномиальная привязка OziExplorer не поддерживается"
        }
        val name = projection.getOrNull(1).orEmpty()
        val setup = row("Projection Setup")
        val points = rows.filter { POINT.matches(it[0]) && it.number(2) != null && it.number(3) != null }
        fun setupValue(index: Int, default: Double) = setup.number(index) ?: default
        fun offsets() = "+x_0=${setupValue(4, 0.0)} +y_0=${setupValue(5, 0.0)}"
        fun origin() = "+lat_0=${setupValue(1, 0.0)} +lon_0=${setupValue(2, 0.0)}"
        val lonLat = name.equals("Latitude/Longitude", ignoreCase = true)
        val projectionParameters = when (name.lowercase()) {
            "bonne" -> null
            "latitude/longitude" -> "+proj=longlat"
            "mercator" -> "+proj=merc ${origin()} +k_0=${setupValue(3, 1.0)} ${offsets()}"
            "transverse mercator", "gauss-kruger", "gauss-krüger" ->
                "+proj=tmerc ${origin()} +k_0=${setupValue(3, 1.0)} ${offsets()}"
            "lambert conformal conic", "albers equal area" -> {
                val code = if (name.startsWith("lambert", ignoreCase = true)) "lcc" else "aea"
                val lat1 = requireNotNull(setup.number(6)) { "В .map отсутствует первая стандартная параллель" }
                val lat2 = requireNotNull(setup.number(7)) { "В .map отсутствует вторая стандартная параллель" }
                "+proj=$code ${origin()} ${offsets()} +lat_1=$lat1 +lat_2=$lat2"
            }
            "sinusoidal" -> "+proj=sinu +lon_0=${setupValue(2, 0.0)} ${offsets()}"
            "(utm) universal transverse mercator", "utm" -> {
                val grid = points.mapNotNull { point ->
                    val zone = point.getOrNull(13)?.toIntOrNull()
                    val hemisphere = point.getOrNull(16)?.uppercase()
                    if (zone != null && zone in 1..60 && hemisphere in setOf("N", "S")) zone to hemisphere else null
                }.distinct()
                require(grid.size <= 1) { "В .map заданы разные зоны UTM" }
                val center = points.mapNotNull(::geographicPoint).takeIf { it.isNotEmpty() }
                val zone = grid.singleOrNull()?.first ?: center?.let { ((it.map(Pixel::x).average() + 180) / 6).toInt().plus(1).coerceIn(1, 60) }
                requireNotNull(zone) { "Не удалось определить зону UTM из .map" }
                val south = grid.singleOrNull()?.second == "S" ||
                    (grid.isEmpty() && center != null && center.map(Pixel::y).average() < 0)
                "+proj=utm +zone=$zone" + if (south) " +south" else ""
            }
            else -> throw IllegalArgumentException("Проекция «$name» в файле OziExplorer не поддерживается")
        }
        val factory = CRSFactory()
        val transforms = CoordinateTransformFactory()
        val wgs = factory.createFromParameters("WGS84", "+proj=longlat +datum=WGS84")
        val geographic = factory.createFromParameters("Ozi datum", "+proj=longlat $ellipsoid")
        val projected = if (projectionParameters == null) {
            // OziExplorer files such as the three-verst sheets store the Bonne parallel as latitude of origin.
            val parallel = requireNotNull(setup.number(6) ?: setup.number(1)) {
                "В .map не указана стандартная параллель проекции Бонна (Bonne)"
            }
            val bonne = OziBonneProjection(geographic.datum.ellipsoid, parallel, setupValue(2, 0.0),
                setupValue(4, 0.0), setupValue(5, 0.0))
            CoordinateReferenceSystem("Ozi Bonne", null, geographic.datum, bonne)
        } else factory.createFromParameters("Ozi projection", "$projectionParameters $ellipsoid" +
            if (lonLat) "" else " +units=m")
        toMap = transforms.createTransform(wgs, projected)
        toWgs = transforms.createTransform(projected, wgs)
        val localToMap = transforms.createTransform(geographic, projected)

        val scale = row("MSF").number(1)?.takeIf { it > 0.01 } ?: 1.0
        val border = rows.filter { it[0].equals("MMPXY", ignoreCase = true) }.mapNotNull { entry ->
            val pixel = entry.pixel(2, 3) ?: return@mapNotNull null
            entry[1] to Pixel(pixel.x / scale, pixel.y / scale)
        }.toMap()
        val controls = points.mapNotNull { point ->
            val geo = geographicPoint(point)
            val position = if (geo != null) {
                val target = localToMap.transform(ProjCoordinate(geo.x, geo.y), ProjCoordinate())
                Pixel(target.x, target.y)
            } else if (!lonLat) point.pixel(14, 15) else null
            position?.let { Control(Pixel(point.number(2)!! / scale, point.number(3)!! / scale), it) }
        }
        val calibration = if (controls.size >= 2) controls else rows
            .filter { it[0].equals("MMPLL", ignoreCase = true) }
            .mapNotNull { entry ->
                val pixel = border[entry.getOrNull(1)] ?: return@mapNotNull null
                val geo = entry.pixel(2, 3) ?: return@mapNotNull null
                if (abs(geo.x) > 180 || abs(geo.y) > 90) return@mapNotNull null
                val target = toMap.transform(ProjCoordinate(geo.x, geo.y), ProjCoordinate())
                Control(pixel, Pixel(target.x, target.y))
            }
        affine = fit(calibration.distinct())
        corners = border.values.toList().takeIf { polygon ->
            polygon.size in 3..100 && polygon.all { it.x in 0.0..width.toDouble() && it.y in 0.0..height.toDouble() } &&
                abs(polygon.indices.sumOf { i ->
                    val a = polygon[i]
                    val b = polygon[(i + 1) % polygon.size]
                    a.x * b.y - a.y * b.x
                }) > 1
        }.orEmpty()
    }

    // Called under the map package lock. Reuse coordinates while sampling a tile.
    inner class Sampler {
        private val geographic = ProjCoordinate()
        private val projected = ProjCoordinate()
        var x = 0.0
            private set
        var y = 0.0
            private set

        fun locate(longitude: Double, latitude: Double): Boolean {
            geographic.setValue(longitude, latitude)
            toMap.transform(geographic, projected)
            val dx = projected.x - affine.origin.x
            val dy = projected.y - affine.origin.y
            x = affine.center.x + (affine.yy * dx - affine.xy * dy) / affine.determinant
            y = affine.center.y + (affine.xx * dy - affine.yx * dx) / affine.determinant
            if (!(x >= 0 && x < width && y >= 0 && y < height)) return false
            if (corners.isEmpty()) return true
            var inside = false
            var previous = corners.last()
            for (current in corners) {
                if ((current.y > y) != (previous.y > y) &&
                    x < current.x + (previous.x - current.x) * (y - current.y) / (previous.y - current.y)) inside = !inside
                previous = current
            }
            return inside
        }
    }

    fun coordinate(x: Double, y: Double): Coordinate {
        val dx = x - affine.center.x
        val dy = y - affine.center.y
        val result = toWgs.transform(ProjCoordinate(
            affine.origin.x + affine.xx * dx + affine.xy * dy,
            affine.origin.y + affine.yx * dx + affine.yy * dy
        ), ProjCoordinate())
        require(result.x.isFinite() && result.y.isFinite() && result.y in -90.0..90.0) { INVALID }
        return Coordinate(result.y, result.x.coerceIn(-180.0, 180.0))
    }

    private data class Pixel(val x: Double, val y: Double)
    private data class Control(val pixel: Pixel, val position: Pixel)
    private data class Affine(
        val center: Pixel, val origin: Pixel, val xx: Double, val xy: Double, val yx: Double, val yy: Double
    ) {
        val determinant = xx * yy - xy * yx
    }

    private fun fit(points: List<Control>): Affine {
        require(points.size >= 2) { "В .map нужны хотя бы две опорные точки с координатами" }
        val center = Pixel(points.map { it.pixel.x }.average(), points.map { it.pixel.y }.average())
        val origin = Pixel(points.map { it.position.x }.average(), points.map { it.position.y }.average())
        val xx = points.sumOf { (it.pixel.x - center.x) * (it.pixel.x - center.x) }
        val yy = points.sumOf { (it.pixel.y - center.y) * (it.pixel.y - center.y) }
        val xy = points.sumOf { (it.pixel.x - center.x) * (it.pixel.y - center.y) }
        val determinant = xx * yy - xy * xy
        require(xx > 0 && yy > 0 && (points.size == 2 || determinant > xx * yy * 1e-12)) { INVALID }

        fun slopes(target: (Pixel) -> Double): Pixel {
            val u = points.sumOf { (target(it.position) - target(origin)) * (it.pixel.x - center.x) }
            val v = points.sumOf { (target(it.position) - target(origin)) * (it.pixel.y - center.y) }
            return if (points.size == 2) Pixel(u / xx, v / yy)
            else Pixel((yy * u - xy * v) / determinant, (xx * v - xy * u) / determinant)
        }
        val x = slopes(Pixel::x)
        val y = slopes(Pixel::y)
        val result = Affine(center, origin, x.x, if (points.size == 2) 0.0 else x.y, if (points.size == 2) 0.0 else y.x, y.y)
        require(result.determinant.isFinite() && result.determinant != 0.0) { INVALID }
        return result
    }

    private fun List<String>.number(index: Int) = getOrNull(index)?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun List<String>.pixel(x: Int, y: Int): Pixel? {
        val horizontal = number(x) ?: return null
        return Pixel(horizontal, number(y) ?: return null)
    }

    private fun geographicPoint(point: List<String>): Pixel? {
        fun degrees(index: Int, limit: Int, positive: String, negative: String): Double? {
            val whole = point.number(index) ?: return null
            val minutes = point.number(index + 1) ?: 0.0
            if (minutes < 0 || minutes >= 60) return null
            val value = abs(whole) + minutes / 60
            if (value > limit) return null
            return when (point.getOrNull(index + 2)?.uppercase()) {
                positive -> value
                negative -> -value
                else -> null
            }
        }
        val latitude = degrees(6, 90, "N", "S") ?: return null
        return Pixel(degrees(9, 180, "E", "W") ?: return null, latitude)
    }

    companion object {
        const val MAX_SIZE = 1024 * 1024
        private val POINT = Regex("Point[0-9]+", RegexOption.IGNORE_CASE)
        private const val INVALID = "Некорректные опорные точки в файле OziExplorer .map"

        private fun decode(data: ByteArray): String {
            val utf8 = String(data, Charsets.UTF_8).removePrefix("\uFEFF")
            return if ('\uFFFD' in utf8) String(data, charset("windows-1251")) else utf8
        }
    }
}
