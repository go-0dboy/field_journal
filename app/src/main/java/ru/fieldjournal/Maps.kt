package ru.fieldjournal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

internal enum class MapPackage { STREET, SATELLITE, HYBRID, HISTORICAL, REFERENCES }

internal enum class MapLayer(val title: String, val archive: MapPackage) {
    STREET("Карта", MapPackage.STREET), SATELLITE("Спутник", MapPackage.SATELLITE),
    HYBRID("Гибрид", MapPackage.HYBRID)
}

internal enum class TilePass { BASE, HISTORICAL, ROADS, PLACES }

internal data class Tile(val z: Int, val x: Int, val y: Int)

internal data class OfflineInfo(
    val name: String,
    val attribution: String,
    val minZoom: Int,
    val maxZoom: Int,
    val center: Coordinate?
)

internal class MapTiles(context: Context) {
    private val folder = File(context.filesDir, "maps").apply { mkdirs() }
    private val settings = context.getSharedPreferences("map", Context.MODE_PRIVATE)
    var offline: Boolean
        get() = settings.getBoolean("offline", false)
        set(value) { settings.edit().putBoolean("offline", value).apply() }
    var layer: MapLayer
        get() = MapLayer.entries.find { it.name == settings.getString("layer", null) } ?: MapLayer.STREET
        set(value) { settings.edit().putString("layer", value.name).apply() }
    var customUrl: String
        get() = settings.getString("xyz", "").orEmpty()
        set(value) { settings.edit().putString("xyz", value).apply() }
    var customCredit: String
        get() = settings.getString("credit", "").orEmpty()
        set(value) { settings.edit().putString("credit", value).apply() }

    var historicalEnabled: Boolean
        get() = settings.getBoolean("historicalEnabled", false)
        set(value) { settings.edit().putBoolean("historicalEnabled", value).apply() }
    var historicalOpacity: Int
        get() = settings.getInt("historicalOpacity", 75)
        set(value) { settings.edit().putInt("historicalOpacity", value.coerceIn(10, 100)).apply() }
    var referencesEnabled: Boolean
        get() = settings.getBoolean("referencesEnabled", true)
        set(value) { settings.edit().putBoolean("referencesEnabled", value).apply() }

    fun showReferences() = referencesEnabled && (layer == MapLayer.HYBRID || historicalEnabled)

    fun setViewport(center: Coordinate, zoom: Double) {
        settings.edit().putString("latitude", center.latitude.toString())
            .putString("longitude", center.longitude.toString()).putString("zoom", zoom.toString()).apply()
    }

    fun viewport(): Pair<Coordinate, Double> = Coordinate(
        settings.getString("latitude", "58.0")!!.toDouble(),
        settings.getString("longitude", "37.0")!!.toDouble()
    ) to settings.getString("zoom", "5.0")!!.toDouble()

    private data class Package(val db: SQLiteDatabase, val info: OfflineInfo)
    private val offlinePackages = mutableMapOf<MapPackage, Package>()
    private val packageLock = Any()

    private fun file(slot: MapPackage) = File(folder, "${slot.name}.mbtiles")

    fun baseArchive(layer: MapLayer) =
        if (layer == MapLayer.HYBRID && !file(MapPackage.HYBRID).isFile) MapPackage.SATELLITE else layer.archive

    fun info(slot: MapPackage): OfflineInfo? = synchronized(packageLock) {
        runCatching { offlinePackage(slot)?.info }.getOrNull()
    }

    private fun offlinePackage(slot: MapPackage): Package? {
        offlinePackages[slot]?.let { return it }
        val file = file(slot)
        if (!file.isFile) return null
        val db = open(file)
        try {
            return Package(db, readInfo(db)).also { offlinePackages[slot] = it }
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }

    private fun open(file: File) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun readInfo(db: SQLiteDatabase): OfflineInfo {
        val metadata = db.rawQuery("SELECT name,value FROM metadata", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        require(metadata["format"]?.lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
            "Для офлайн-карты нужны растровые MBTiles (PNG, JPEG или WebP)"
        }
        val range = db.rawQuery(
            "SELECT (SELECT MIN(zoom_level) FROM tiles), (SELECT MAX(zoom_level) FROM tiles)", null
        ).use { cursor ->
            check(cursor.moveToFirst() && !cursor.isNull(0)) { "В MBTiles нет тайлов" }
            cursor.getInt(0) to cursor.getInt(1)
        }
        require(range.first in 0..22 && range.second in range.first..22) { "Неподдерживаемый масштаб MBTiles" }
        fun coordinate(lon: Double, lat: Double) = runCatching { Coordinate(lat, lon) }.getOrNull()
        val center = metadata["center"]?.split(",")?.mapNotNull(String::toDoubleOrNull)
        val bounds = metadata["bounds"]?.split(",")?.mapNotNull(String::toDoubleOrNull)
        val point = when {
            center?.size == 3 -> coordinate(center[0], center[1])
            bounds?.size == 4 -> coordinate(
                wrapLongitude(bounds[0] + (if (bounds[2] >= bounds[0]) bounds[2] - bounds[0] else bounds[2] - bounds[0] + 360.0) / 2),
                (bounds[1] + bounds[3]) / 2
            )
            else -> db.rawQuery(
                "SELECT tile_column,tile_row FROM tiles WHERE zoom_level=? LIMIT 1",
                arrayOf(range.first.toString())
            ).use {
                if (!it.moveToFirst()) null else {
                    val count = (1 shl range.first).toDouble()
                    coordinate((it.getInt(0) + 0.5) / count * 360 - 180, degreeLatitude(1 - (it.getInt(1) + 0.5) / count))
                }
            }
        }
        return OfflineInfo(metadata["name"] ?: "MBTiles", metadata["attribution"].orEmpty().replace(Regex("<[^>]+>"), ""),
            range.first, range.second, point)
    }

    fun install(slot: MapPackage, input: InputStream): OfflineInfo {
        val temp = File(folder, "import-${newId()}.mbtiles")
        try {
            temp.outputStream().use { input.copyLimited(it, 8L * 1024 * 1024 * 1024) }
            val info = open(temp).use { db ->
                val result = readInfo(db)
                db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use {
                    require(it.moveToFirst()) { "В MBTiles нет тайлов" }
                    val bitmap = requireNotNull(decode(it.getBlob(0))) { "Не удалось прочитать растровый тайл" }
                    try {
                        require(slot != MapPackage.REFERENCES || bitmap.hasAlpha()) {
                            "Для подписей нужен прозрачный растр PNG или WebP"
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                result
            }
            synchronized(packageLock) {
                offlinePackages.remove(slot)?.db?.close()
                check(temp.renameTo(file(slot))) { "Не удалось установить слой карты" }
            }
            return info
        } finally {
            temp.delete()
        }
    }

    fun credit(layer: MapLayer, offline: Boolean): String = if (offline) {
        info(baseArchive(layer))?.attribution?.ifBlank { "Локальный архив MBTiles" } ?: ""
    } else when (layer) {
        MapLayer.STREET -> if (customUrl.isBlank()) "© OpenStreetMap contributors" else customCredit
        MapLayer.SATELLITE -> "© Esri, Vantor, Earthstar Geographics, GIS User Community"
        MapLayer.HYBRID -> "© Esri, Vantor, Earthstar Geographics, GIS User Community"
    }

    fun read(layer: MapLayer, offline: Boolean, urls: String, tile: Tile, pass: TilePass): Bitmap? {
        if (pass == TilePass.HISTORICAL) return readOffline(MapPackage.HISTORICAL, tile)
        if (offline) return when (pass) {
            TilePass.BASE -> readOffline(baseArchive(layer), tile)
            TilePass.PLACES -> readOffline(MapPackage.REFERENCES, tile)
            else -> null
        }
        val server = "https://server.arcgisonline.com/ArcGIS/rest/services"
        val template = when (pass) {
            TilePass.ROADS -> "$server/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
            TilePass.PLACES -> "$server/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
            else -> if (layer == MapLayer.STREET) urls.ifBlank { "https://tile.openstreetmap.org/{z}/{x}/{y}.png" }
                else "$server/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        }
        val connection = URL(template.replace("{z}", tile.z.toString()).replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6500
            connection.readTimeout = 6500
            connection.setRequestProperty("User-Agent", "FieldJournal/0.1 (https://github.com/go-0dboy/field_journal)")
            if (connection.responseCode != 200) null else connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                stream.copyLimited(out, 8L * 1024 * 1024)
                decode(out.toByteArray())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readOffline(slot: MapPackage, tile: Tile): Bitmap? {
        return synchronized(packageLock) {
            val source = offlinePackage(slot) ?: return@synchronized null
            val info = source.info
            for (z in min(tile.z, info.maxZoom) downTo max(info.minZoom, tile.z - 7)) {
                val shift = tile.z - z
                val x = tile.x shr shift
                val tms = (1 shl z) - 1 - (tile.y shr shift)
                val image = source.db.rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                    arrayOf(z.toString(), x.toString(), tms.toString())
                ).use { if (it.moveToFirst()) decode(it.getBlob(0)) else null } ?: continue
                if (shift == 0) return@synchronized image
                val divisions = 1 shl shift
                val left = tile.x % divisions * image.width / divisions
                val top = tile.y % divisions * image.height / divisions
                return@synchronized Bitmap.createBitmap(image, left, top, max(1, image.width / divisions), max(1, image.height / divisions))
            }
            null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth !in 1..2048 || options.outHeight !in 1..2048) return null
        options.inJustDecodeBounds = false
        while (max(options.outWidth, options.outHeight) / max(1, options.inSampleSize) > 512) {
            options.inSampleSize = max(1, options.inSampleSize) * 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        private var instance: MapTiles? = null

        @Synchronized fun get(context: Context) =
            instance ?: MapTiles(context.applicationContext).also { instance = it }

        fun validTemplate(value: String): Boolean {
            if (value.isBlank()) return true
            val url = Uri.parse(value.replace("{x}", "0").replace("{y}", "0").replace("{z}", "0"))
            return listOf("{x}", "{y}", "{z}").all(value::contains) && url.scheme == "https" &&
                !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null
        }
    }
}

internal fun mercator(latitude: Double): Double {
    val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
    return (1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2
}

internal fun degreeLatitude(mercator: Double) =
    Math.toDegrees(atan(sinh(PI * (1 - 2 * mercator.coerceIn(0.0, 1.0)))))
