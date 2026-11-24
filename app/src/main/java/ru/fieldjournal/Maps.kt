package ru.fieldjournal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.DataOutputStream
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

internal enum class TilePass { BASE, HISTORICAL, ROADS, PLACES, LOCAL_REFERENCES }

internal enum class ReferenceSource(val title: String) {
    ESRI("Онлайн · Esri"), LOCAL("Локальный пакет")
}

internal class PreparedRaster(
    val slot: MapPackage, val file: File, val name: String, val options: List<OfflineInfo>, val preview: Bitmap?
) : AutoCloseable {
    override fun close() {
        file.delete()
    }
}

internal data class Tile(val z: Int, val x: Int, val y: Int)

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

    var coordinatesAtDevice: Boolean
        get() = settings.getBoolean("coordinatesAtDevice", false)
        set(value) { settings.edit().putBoolean("coordinatesAtDevice", value).apply() }

    var historicalEnabled: Boolean
        get() = settings.getBoolean("historicalEnabled", false)
        set(value) { settings.edit().putBoolean("historicalEnabled", value).apply() }
    var historicalOpacity: Int
        get() = settings.getInt("historicalOpacity", 75)
        set(value) { settings.edit().putInt("historicalOpacity", value.coerceIn(10, 100)).apply() }
    var referencesEnabled: Boolean
        get() = settings.getBoolean("referencesEnabled", true)
        set(value) { settings.edit().putBoolean("referencesEnabled", value).apply() }

    var onlineReferenceSource: ReferenceSource
        get() = if (settings.getBoolean("localOnlineReferences", false)) ReferenceSource.LOCAL else ReferenceSource.ESRI
        set(value) { settings.edit().putBoolean("localOnlineReferences", value == ReferenceSource.LOCAL).apply() }

    fun referenceSource() = if (offline) ReferenceSource.LOCAL else onlineReferenceSource
    fun showReferences() = referencesEnabled && (layer == MapLayer.HYBRID || historicalEnabled)

    fun setViewport(center: Coordinate, zoom: Double) {
        settings.edit().putString("latitude", center.latitude.toString())
            .putString("longitude", center.longitude.toString()).putString("zoom", zoom.toString()).apply()
    }

    fun viewport(): Pair<Coordinate, Double> = Coordinate(
        settings.getString("latitude", "58.0")!!.toDouble(),
        settings.getString("longitude", "37.0")!!.toDouble()
    ) to settings.getString("zoom", "5.0")!!.toDouble()

    private sealed class Package : AutoCloseable {
        abstract val info: OfflineInfo

        class Sqlite(private val db: SQLiteDatabase, val archive: RasterArchive) : Package() {
            override val info get() = archive.info
            override fun close() = db.close()
        }

        class Ozi(val archive: Ozf2Archive) : Package() {
            override val info get() = archive.info
            override fun close() = archive.close()
        }
    }
    private val offlinePackages = mutableMapOf<MapPackage, Package>()
    private val packageLock = Any()

    // Keep legacy slot paths. The file signature selects the reader.
    private fun file(slot: MapPackage) = File(folder, "${slot.name}.mbtiles")

    private fun packageNameKey(slot: MapPackage) = "archive-name-${slot.name}"
    private fun numberingKey(slot: MapPackage) = "archive-numbering-${slot.name}"

    fun baseArchive(layer: MapLayer) =
        if (layer == MapLayer.HYBRID && !file(MapPackage.HYBRID).isFile) MapPackage.SATELLITE else layer.archive

    fun info(slot: MapPackage): OfflineInfo? = synchronized(packageLock) {
        runCatching { offlinePackage(slot)?.info }.getOrNull()
    }

    private fun offlinePackage(slot: MapPackage): Package? {
        offlinePackages[slot]?.let { return it }
        val file = file(slot)
        if (!file.isFile) return null
        val name = settings.getString(packageNameKey(slot), "").orEmpty()
        if (file.inputStream().use { it.read() == 0x78 && it.read() == 0x77 }) {
            return Package.Ozi(Ozf2Archive(file, name)).also { offlinePackages[slot] = it }
        }
        val db = open(file)
        try {
            val numbering = SqliteNumbering.entries.find { it.name == settings.getString(numberingKey(slot), null) }
            return Package.Sqlite(db, RasterArchive.open(db, name, numbering)).also { offlinePackages[slot] = it }
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }

    private fun open(file: File) = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    fun prepare(slot: MapPackage, input: InputStream, filename: String): PreparedRaster {
        val temp = File(folder, "import-${newId()}.sqlite")
        val name = filename.substringBeforeLast('.', filename)
        try {
            temp.outputStream().use { input.copyLimited(it, 8L * 1024 * 1024 * 1024) }
            return open(temp).use { db ->
                val options = RasterArchive.options(db, name)
                val bytes = requireNotNull(options.first().sample()) { "В карте нет тайлов" }
                val bitmap = requireNotNull(decode(bytes)) { "Не удалось прочитать растровый тайл" }
                if (slot == MapPackage.REFERENCES && !bitmap.hasAlpha()) {
                    bitmap.recycle()
                    throw IllegalArgumentException("Для подписей нужен прозрачный растр PNG или WebP")
                }
                val preview = bitmap.takeIf { options.size > 1 }
                if (preview == null) bitmap.recycle()
                PreparedRaster(slot, temp, name, options.map(RasterArchive::info), preview)
            }
        } catch (error: Exception) {
            temp.delete()
            if (error is android.database.sqlite.SQLiteException) {
                throw IllegalArgumentException("Не удалось прочитать карту. Нужен растровый MBTiles, SQLiteDB или GeoPackage", error)
            }
            throw error
        }
    }

    fun prepareOzf2(slot: MapPackage, image: InputStream, calibration: InputStream, filename: String): PreparedRaster {
        require(slot != MapPackage.REFERENCES) { "Для слоя подписей нужен пакет с прозрачными тайлами" }
        val map = java.io.ByteArrayOutputStream()
        calibration.copyLimited(map, OziCalibration.MAX_SIZE.toLong())
        val temp = File(folder, "import-${newId()}.ozf2")
        val name = filename.substringBeforeLast('.', filename)
        try {
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                image.copyLimited(output, 0xffffffffL)
                map.writeTo(output)
                output.writeInt(map.size())
            }
            return Ozf2Archive(temp, name).use { archive ->
                require(archive.rasterName.equals(filename, ignoreCase = true) || filename.isBlank()) {
                    "В .map указано изображение «${archive.rasterName}». Выберите соответствующий файл OZF2"
                }
                PreparedRaster(slot, temp, name, listOf(archive.info), null)
            }
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    fun install(prepared: PreparedRaster, numbering: SqliteNumbering? = null): OfflineInfo = prepared.use {
        val info = if (numbering == null) {
            requireNotNull(prepared.options.singleOrNull()) { "Выберите схему SQLiteDB: XYZ или BigPlanet" }
        } else prepared.options.first { it.numbering == numbering }
        val slot = prepared.slot
        synchronized(packageLock) {
            offlinePackages.remove(slot)?.close()
            check(prepared.file.renameTo(file(slot))) { "Не удалось установить слой карты" }
            settings.edit().putString(packageNameKey(slot), prepared.name)
                .putString(numberingKey(slot), info.numbering?.name).apply()
        }
        info
    }

    fun install(slot: MapPackage, input: InputStream, filename: String = "") = install(prepare(slot, input, filename))

    fun credit(layer: MapLayer, offline: Boolean): String = if (offline) {
        info(baseArchive(layer))?.let { it.attribution.ifBlank { "Локальная карта · ${it.format.title}" } }.orEmpty()
    } else when (layer) {
        MapLayer.STREET -> if (customUrl.isBlank()) "© OpenStreetMap contributors" else customCredit
        MapLayer.SATELLITE -> "© Esri, Vantor, Earthstar Geographics, GIS User Community"
        MapLayer.HYBRID -> "© Esri, Vantor, Earthstar Geographics, GIS User Community"
    }

    fun read(layer: MapLayer, offline: Boolean, urls: String, tile: Tile, pass: TilePass): Bitmap? {
        if (pass == TilePass.HISTORICAL) return readOffline(MapPackage.HISTORICAL, tile)
        if (pass == TilePass.LOCAL_REFERENCES) return readOffline(MapPackage.REFERENCES, tile)
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

    data class Coverage(val slot: MapPackage, val zoom: Int, val native: Int, val enlarged: Int, val missing: List<Coordinate>)

    fun coverage(points: List<Coordinate>, zooms: List<Int>, slots: List<MapPackage>): List<Coverage> = synchronized(packageLock) {
        fun tile(point: Coordinate, zoom: Int): Tile {
            val count = 1 shl zoom
            return Tile(zoom, (((point.longitude + 180) / 360) * count).toInt().coerceIn(0, count - 1),
                (mercator(point.latitude) * count).toInt().coerceIn(0, count - 1))
        }
        slots.distinct().flatMap { slot ->
            val archive = offlinePackage(slot)
            zooms.map { zoom ->
                var native = 0
                var enlarged = 0
                val missing = mutableListOf<Coordinate>()
                for (point in points.distinctBy { tile(it, zoom) }) {
                    val key = tile(point, zoom)
                    if (archive == null) { missing += point; continue }
                    val info = archive.info
                    val hit = when (archive) {
                        is Package.Ozi -> min(zoom, info.maxZoom).takeIf {
                            it >= info.minZoom && archive.archive.covers(point)
                        }
                        is Package.Sqlite -> (min(zoom, info.maxZoom) downTo max(info.minZoom, zoom - 7)).firstOrNull { z ->
                            val shift = zoom - z
                            archive.archive.tile(Tile(z, key.x shr shift, key.y shr shift)) != null
                        }
                    }
                    when (hit) {
                        zoom -> native++
                        null -> missing += point
                        else -> enlarged++
                    }
                }
                Coverage(slot, zoom, native, enlarged, missing)
            }
        }
    }

    private fun readOffline(slot: MapPackage, tile: Tile): Bitmap? {
        return synchronized(packageLock) {
            val source = offlinePackage(slot) ?: return@synchronized null
            if (source is Package.Ozi) return@synchronized source.archive.read(tile)
            val archive = (source as Package.Sqlite).archive
            val info = archive.info
            for (z in min(tile.z, info.maxZoom) downTo max(info.minZoom, tile.z - 7)) {
                val shift = tile.z - z
                val key = Tile(z, tile.x shr shift, tile.y shr shift)
                val image = archive.tile(key)?.let(::decode) ?: continue
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
