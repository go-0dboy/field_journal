package ru.fieldjournal

import android.graphics.Bitmap
import org.locationtech.proj4j.Proj4jException
import java.io.File
import kotlin.math.*

/** Samples the calibrated OziExplorer image on the same Web Mercator grid as the other layers. */
internal class Ozf2Archive(file: File, fallbackName: String) : AutoCloseable {
    private val image = Ozf2Image(file)
    private val calibration: OziCalibration
    val rasterName: String get() = calibration.rasterName
    val info: OfflineInfo
    private val bounds: Bounds

    init {
        try {
            val w = image.width.toDouble()
            val h = image.height.toDouble()
            calibration = OziCalibration(image.mapData, image.width, image.height)
            val outline = (0..16).flatMap { i ->
                val x = w * i / 16
                val y = h * i / 16
                listOf(calibration.coordinate(x, 0.0), calibration.coordinate(x, h),
                    calibration.coordinate(0.0, y), calibration.coordinate(w, y))
            }
            val center = calibration.coordinate(w / 2, h / 2)
            val xs = outline.map { worldX(it.longitude) }
            val ys = outline.map { mercator(it.latitude) }
            val span = max(xs.max() - xs.min(), ys.max() - ys.min())
            require(span > 0) { "Карта OZF2 находится вне диапазона широт карты" }
            val padding = span / 200
            bounds = Bounds((xs.min() - padding).coerceAtLeast(0.0), (ys.min() - padding).coerceAtLeast(0.0),
                (xs.max() + padding).coerceAtMost(1.0), (ys.max() + padding).coerceAtMost(1.0))
            val atScale = max(w, h) / (256 * span)
            val maxZoom = ceil(log2(atScale)).toInt().coerceIn(0, 22)
            val minZoom = floor(log2(1 / span)).toInt().coerceIn(0, maxZoom)
            info = OfflineInfo(calibration.title.ifBlank { fallbackName.ifBlank { "OZF2" } }, "",
                minZoom, maxZoom, center, RasterFormat.OZF2)
        } catch (error: Exception) {
            image.close()
            throw error
        }
    }

    fun covers(point: Coordinate) = locate(calibration.Sampler(), point.longitude, point.latitude)

    private fun locate(sampler: OziCalibration.Sampler, longitude: Double, latitude: Double): Boolean =
        try {
            sampler.locate(longitude, latitude)
        } catch (_: Proj4jException) {
            false
        }

    fun read(tile: Tile): Bitmap? {
        if (tile.z < info.minZoom) return null
        val tiles = 1 shl tile.z
        val globalPixels = 256.0 * tiles
        val minX = (floor(bounds.left * globalPixels).toLong() - tile.x * 256L).coerceIn(0, 256).toInt()
        val maxX = (ceil(bounds.right * globalPixels).toLong() - tile.x * 256L).coerceIn(0, 256).toInt()
        val minY = (floor(bounds.top * globalPixels).toLong() - tile.y * 256L).coerceIn(0, 256).toInt()
        val maxY = (ceil(bounds.bottom * globalPixels).toLong() - tile.y * 256L).coerceIn(0, 256).toInt()
        if (minX >= maxX || minY >= maxY) return null

        val sampler = calibration.Sampler()
        val raster = image.Sampler(image.level(2.0.pow(info.maxZoom - tile.z)))
        val longitudes = DoubleArray(256) { (tile.x * 256.0 + it + 0.5) / globalPixels * 360 - 180 }
        val pixels = IntArray(256 * 256)
        var drawn = false
        for (y in minY until maxY) {
            val latitude = degreeLatitude((tile.y * 256.0 + y + 0.5) / globalPixels)
            for (x in minX until maxX) {
                if (locate(sampler, longitudes[x], latitude)) {
                    pixels[y * 256 + x] = raster.color(sampler.x, sampler.y)
                    drawn = true
                }
            }
        }
        return if (drawn) Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888) else null
    }

    override fun close() = image.close()

    private data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
    private fun worldX(longitude: Double) = (longitude + 180) / 360
}
