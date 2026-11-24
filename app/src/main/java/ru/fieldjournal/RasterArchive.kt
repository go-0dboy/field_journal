package ru.fieldjournal

import android.database.sqlite.SQLiteDatabase
import kotlin.math.PI
import kotlin.math.abs

internal enum class RasterFormat(val title: String) {
    MBTILES("MBTiles"), SQLITE_DB("SQLiteDB"), GEOPACKAGE("GeoPackage"), OZF2("OZF2")
}

internal enum class SqliteNumbering(val title: String) {
    XYZ("XYZ"), BIG_PLANET("BigPlanet")
}

internal data class OfflineInfo(
    val name: String,
    val attribution: String,
    val minZoom: Int,
    val maxZoom: Int,
    val center: Coordinate?,
    val format: RasterFormat,
    val numbering: SqliteNumbering? = null
)

internal class RasterArchive private constructor(
    private val db: SQLiteDatabase,
    val info: OfflineInfo,
    private val grid: TileGrid
) {
    fun tile(tile: Tile): ByteArray? = db.rawQuery(
        "SELECT ${grid.image} FROM ${grid.source} AND ${grid.zoom}=? AND ${grid.x}=? AND ${grid.y}=? LIMIT 1",
        arrayOf(grid.zoomNumber(tile.z).toString(), tile.x.toString(), grid.rowNumber(tile.z, tile.y).toString())
    ).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun sample(): ByteArray? = db.rawQuery(
        "SELECT ${grid.image} FROM ${grid.source} LIMIT 1", null
    ).use { if (it.moveToFirst()) it.getBlob(0) else null }

    private data class TileGrid(
        val format: RasterFormat,
        val table: String,
        val zoom: String = "zoom_level",
        val x: String = "tile_column",
        val y: String = "tile_row",
        val image: String = "tile_data",
        val invertedZoom: Boolean = false,
        val invertedY: Boolean = false,
        val layer: Long? = null
    ) {
        val source get() = "${quote(table)} WHERE ${layer?.let { "s=$it" } ?: "1=1"}"
        fun zoomNumber(value: Int) = if (invertedZoom) 17 - value else value
        fun rowNumber(z: Int, value: Int) = if (invertedY) (1 shl z) - 1 - value else value

        fun zoomRange(db: SQLiteDatabase): IntRange = db.rawQuery(
            "SELECT (SELECT MIN($zoom) FROM $source), (SELECT MAX($zoom) FROM $source)", null
        ).use {
            require(it.moveToFirst() && !it.isNull(0)) { "В карте нет тайлов" }
            val min = zoomNumber(it.getInt(if (invertedZoom) 1 else 0))
            val max = zoomNumber(it.getInt(if (invertedZoom) 0 else 1))
            require(min in 0..22 && max in min..22) { "Карта должна содержать масштабы от 0 до 22" }
            min..max
        }

        fun firstTileCenter(db: SQLiteDatabase, z: Int): Coordinate = db.rawQuery(
            "SELECT $x,$y FROM $source AND $zoom=? LIMIT 1", arrayOf(zoomNumber(z).toString())
        ).use {
            require(it.moveToFirst()) { "В карте нет тайлов на масштабе $z" }
            val count = 1 shl z
            val x = it.getInt(0)
            val y = rowNumber(z, it.getInt(1))
            require(x in 0 until count && y in 0 until count) {
                "Некорректные координаты тайлов или схема нумерации ${format.title}"
            }
            Coordinate(degreeLatitude((y + 0.5) / count), (x + 0.5) / count * 360 - 180)
        }
    }

    companion object {
        private const val HALF_WORLD = PI * 6_378_137.0
        private val images = setOf("png", "jpg", "jpeg", "webp")

        fun open(db: SQLiteDatabase, fallbackName: String, numbering: SqliteNumbering? = null): RasterArchive =
            options(db, fallbackName, numbering, inspectMissingNumbering = false).single()

        fun options(
            db: SQLiteDatabase, fallbackName: String, numbering: SqliteNumbering? = null,
            inspectMissingNumbering: Boolean = true
        ): List<RasterArchive> {
            val columns = columns(db, "tiles")
            val (grids, metadata) = when {
                hasGeoPackage(db) -> geoPackage(db).let { (grid, info) -> listOf(grid) to info }
                columns.containsAll(listOf("zoom_level", "tile_column", "tile_row", "tile_data")) ->
                    mbtiles(db).let { (grid, info) -> listOf(grid) to info }
                columns.containsAll(listOf("z", "x", "y", "image")) ->
                    sqliteDb(db, "s" in columns, numbering, inspectMissingNumbering)
                else -> throw IllegalArgumentException("Файл должен быть растровой картой MBTiles, SQLiteDB или GeoPackage")
            }
            return grids.map { grid ->
                val range = grid.zoomRange(db)
                val tileCenter = grid.firstTileCenter(db, range.first)
                val name = metadata["name"]?.takeIf(String::isNotBlank) ?: fallbackName.ifBlank { grid.format.title }
                val numberingUsed = if (grid.format == RasterFormat.SQLITE_DB) {
                    if (grid.invertedZoom) SqliteNumbering.BIG_PLANET else SqliteNumbering.XYZ
                } else null
                val info = OfflineInfo(
                    name, metadata["attribution"].orEmpty().replace(Regex("<[^>]+>"), ""),
                    range.first, range.last, metadataCenter(metadata) ?: tileCenter, grid.format, numberingUsed
                )
                RasterArchive(db, info, grid)
            }
        }

        private fun mbtiles(db: SQLiteDatabase): Pair<TileGrid, Map<String, String>> {
            val metadata = db.rawQuery("SELECT name,value FROM metadata", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0)
                        val value = cursor.getString(1)
                        if (key != null && value != null) put(key.lowercase(), value)
                    }
                }
            }
            require(metadata["format"]?.lowercase() in images) {
                "MBTiles должен содержать растровые тайлы PNG, JPEG или WebP"
            }
            return TileGrid(RasterFormat.MBTILES, "tiles", invertedY = true) to metadata
        }

        private fun sqliteDb(
            db: SQLiteDatabase, hasLayer: Boolean, savedNumbering: SqliteNumbering?, inspectMissingNumbering: Boolean
        ): Pair<List<TileGrid>, Map<String, String>> {
            val info = if (columns(db, "info").isEmpty()) emptyMap() else {
                db.rawQuery("SELECT * FROM info LIMIT 1", null).use { cursor ->
                    if (!cursor.moveToFirst()) emptyMap() else buildMap {
                        cursor.columnNames.forEachIndexed { i, name ->
                            cursor.getString(i)?.let { put(name.lowercase(), it) }
                        }
                    }
                }
            }
            require(info["ellipsoid"] != "1") { "SQLiteDB с эллипсоидом Меркатора не поддерживается. Нужен Web Mercator (EPSG:3857)" }
            val rawNumbering = info["tilenumbering"]?.trim()?.lowercase()
            val numbering = rawNumbering?.takeIf(String::isNotEmpty)
            require(numbering in listOf(null, "simple", "bigplanet", "xyz")) {
                "Неизвестная схема нумерации SQLiteDB: $numbering"
            }
            val layer = if (hasLayer) db.rawQuery("SELECT MIN(s) FROM tiles", null).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            } else null
            val grid = TileGrid(RasterFormat.SQLITE_DB, "tiles", "z", "x", "y", "image",
                invertedY = info["inverted_y"] == "1", layer = layer)
            val declared = when (numbering) {
                "bigplanet" -> SqliteNumbering.BIG_PLANET
                "simple", "xyz" -> SqliteNumbering.XYZ
                else -> savedNumbering ?: SqliteNumbering.XYZ.takeIf { !inspectMissingNumbering && rawNumbering == "" }
            }
            val grids = when {
                declared != null -> listOf(grid.copy(invertedZoom = declared == SqliteNumbering.BIG_PLANET))
                inspectMissingNumbering -> detectSqliteGrids(db, grid)
                else -> {
                    // Retain the interpretation of maps installed by earlier versions.
                    val legacy = grid.copy(invertedZoom = true)
                    val legacyFits = runCatching {
                        val range = legacy.zoomRange(db)
                        legacy.firstTileCenter(db, range.first)
                        legacy.firstTileCenter(db, range.last)
                    }.isSuccess
                    listOf(if (legacyFits) legacy else grid)
                }
            }
            return grids to (info + ("name" to (info["title"] ?: info["name"].orEmpty())))
        }

        private fun detectSqliteGrids(db: SQLiteDatabase, grid: TileGrid): List<TileGrid> {
            val candidates = mutableListOf(grid, grid.copy(invertedZoom = true))
            db.rawQuery(
                "SELECT z, MIN(x), MAX(x), MIN(y), MAX(y), COUNT(*), COUNT(x), COUNT(y) FROM ${grid.source} GROUP BY z",
                null
            ).use {
                require(it.moveToFirst()) { "В карте нет тайлов" }
                do {
                    require(!it.isNull(0) && it.getLong(0) in 0..22 &&
                        it.getLong(5) == it.getLong(6) && it.getLong(5) == it.getLong(7)) {
                        "Некорректные координаты тайлов SQLiteDB"
                    }
                    candidates.removeAll { candidate ->
                        val z = candidate.zoomNumber(it.getInt(0))
                        z !in 0..22 || it.getLong(1) < 0 || it.getLong(3) < 0 ||
                            it.getLong(2) >= (1L shl z) || it.getLong(4) >= (1L shl z)
                    }
                } while (candidates.isNotEmpty() && it.moveToNext())
            }
            require(candidates.isNotEmpty()) { "Координаты SQLiteDB выходят за границы сетки XYZ и BigPlanet" }
            return candidates
        }

        private fun hasGeoPackage(db: SQLiteDatabase) = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='gpkg_contents'", null
        ).use { it.moveToFirst() }

        private fun geoPackage(db: SQLiteDatabase): Pair<TileGrid, Map<String, String>> {
            val coverage = db.rawQuery(
                "SELECT table_name,identifier,min_x,min_y,max_x,max_y FROM gpkg_contents WHERE data_type='tiles' LIMIT 2",
                null
            ).use {
                require(it.moveToFirst()) { "В GeoPackage нет слоя растровых тайлов" }
                val table = it.getString(0)
                val name = it.getString(1)?.takeIf(String::isNotBlank) ?: table
                val center = if ((2..5).none(it::isNull)) {
                    val x = (it.getDouble(2) + it.getDouble(4)) / 2
                    val y = (it.getDouble(3) + it.getDouble(5)) / 2
                    runCatching { Coordinate(degreeLatitude((HALF_WORLD - y) / (2 * HALF_WORLD)), x / HALF_WORLD * 180) }.getOrNull()
                } else null
                require(!it.moveToNext()) { "Для импорта GeoPackage оставьте один растровый слой" }
                Triple(table, name, center)
            }
            val (table, name, center) = coverage
            validateGeoPackageGrid(db, table)
            val metadata = buildMap {
                put("name", name)
                center?.let { put("center", "${it.longitude},${it.latitude}") }
            }
            return TileGrid(RasterFormat.GEOPACKAGE, table) to metadata
        }

        private fun validateGeoPackageGrid(db: SQLiteDatabase, table: String) {
            val compatible = "Для GeoPackage нужна сетка GoogleMapsCompatible в Web Mercator (EPSG:3857)"
            db.rawQuery(
                "SELECT min_x,min_y,max_x,max_y,organization,organization_coordsys_id FROM gpkg_tile_matrix_set " +
                    "JOIN gpkg_spatial_ref_sys USING (srs_id) WHERE table_name=?",
                arrayOf(table)
            ).use {
                require(it.moveToFirst() && it.getString(4).equals("EPSG", ignoreCase = true) && it.getInt(5) == 3857 &&
                    (0..3).all { i ->
                        val expected = if (i < 2) -HALF_WORLD else HALF_WORLD
                        !it.isNull(i) && abs(it.getDouble(i) - expected) < 1.0
                    }) { compatible }
            }
            val zooms = db.rawQuery(
                "SELECT zoom_level,matrix_width,matrix_height,tile_width,tile_height,pixel_x_size,pixel_y_size " +
                    "FROM gpkg_tile_matrix WHERE table_name=?", arrayOf(table)
            ).use {
                buildSet {
                    while (it.moveToNext()) {
                        val z = it.getInt(0)
                        require(!it.isNull(0) && z in 0..22) { compatible }
                        val tiles = 1 shl z
                        val width = it.getInt(3)
                        val height = it.getInt(4)
                        require(it.getLong(1) == tiles.toLong() && it.getLong(2) == tiles.toLong() &&
                            width == height && width in setOf(256, 512) &&
                            (5..6).all { i -> abs(it.getDouble(i) * tiles * width - 2 * HALF_WORLD) < 1.0 }
                        ) { compatible }
                        add(z)
                    }
                }
            }
            db.rawQuery("SELECT DISTINCT zoom_level FROM ${quote(table)}", null).use {
                while (it.moveToNext()) require(!it.isNull(0) && it.getInt(0) in zooms) { compatible }
            }
        }

        private fun metadataCenter(metadata: Map<String, String>): Coordinate? {
            fun values(key: String) = metadata[key]?.split(",")?.map { it.trim().toDoubleOrNull() }
            val center = values("center")
            val bounds = values("bounds")
            return runCatching {
                when {
                    center != null && center.size >= 2 -> Coordinate(requireNotNull(center[1]), requireNotNull(center[0]))
                    bounds?.size == 4 -> {
                        val (west, south, east, north) = bounds.map(::requireNotNull)
                        val span = if (east < west) east - west + 360 else east - west
                        Coordinate((south + north) / 2, wrapLongitude(west + span / 2))
                    }
                    else -> null
                }
            }.getOrNull()
        }

        private fun columns(db: SQLiteDatabase, table: String) = db.rawQuery("PRAGMA table_info(${quote(table)})", null).use {
            buildSet { while (it.moveToNext()) add(it.getString(1).lowercase()) }
        }

        private fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
    }
}
