package ru.fieldjournal

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RasterArchiveTest {
    private val context = RuntimeEnvironment.getApplication()
    private val tiles = MapTiles(context)

    @Test fun osmAndReadsSimpleAndInvertedRowsAndRemembersFilename() {
        val tile = Tile(7, 75, 24)
        val file = sqliteDb("Выборг", tile, true, "simple", invertedY = true)
        assertEquals(RasterFormat.SQLITE_DB, install(file).format)
        val reopened = MapTiles(context)
        val info = requireNotNull(reopened.info(MapPackage.HISTORICAL))
        assertEquals("Выборг", info.name)
        assertEquals(7, info.minZoom)
        assertEquals(7, info.maxZoom)
        assertEquals(degreeLatitude(24.5 / 128), requireNotNull(info.center).latitude, 1e-10)
        assertEquals(Color.BLUE, pixel(reopened, tile))
        assertNull(pixel(reopened, Tile(7, 75, 103)))
    }

    @Test fun rMapsReadsLegacyBigPlanetAndSqliteWithoutInfoCanUseXyz() {
        val tile = Tile(12, 2429, 1200)
        for (invertedZoom in listOf(true, false)) {
            val sqlite = sqliteDb("legacy-$invertedZoom", tile, false, null, invertedZoom = invertedZoom)
            assertEquals(12, install(sqlite).minZoom)
            assertEquals(Color.BLUE, pixel(tiles, tile))
            assertEquals(Color.BLUE, pixel(tiles, Tile(13, tile.x * 2, tile.y * 2)))
            assertNull(pixel(tiles, Tile(12, 0, 0)))
        }
    }

    @Test fun projectionMismatchLeavesInstalledRasterUntouched() {
        val tile = Tile(12, 2429, 1200)
        install(sqliteDb("valid", tile, true, "BigPlanet", invertedZoom = true))
        val invalid = sqliteDb("ellipsoid", tile, true, "simple", ellipsoid = true)
        val error = assertThrows(IllegalArgumentException::class.java) { install(invalid) }
        assertTrue(error.message.orEmpty().contains("EPSG:3857"))
        assertEquals("valid", tiles.info(MapPackage.HISTORICAL)?.name)
        assertEquals(Color.BLUE, pixel(tiles, tile))
    }

    @Test fun geoPackageReadsMercatorTilesAndChecksTheGrid() {
        val tile = Tile(9, 303, 148)
        val file = geoPackage(tile)
        val info = install(file)
        assertEquals(RasterFormat.GEOPACKAGE, info.format)
        assertEquals("Архивная карта", info.name)
        assertEquals(Color.BLUE, pixel(tiles, tile))
        assertNull(pixel(tiles, Tile(9, 303, 363)))

        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE gpkg_tile_matrix_set SET min_x=0")
        }
        val error = assertThrows(IllegalArgumentException::class.java) { install(file) }
        assertTrue(error.message.orEmpty().contains("GoogleMapsCompatible"))
        assertEquals(Color.BLUE, pixel(tiles, tile))
    }

    @Test fun referencesUseTranslucentTilesFromSqliteDb() {
        val tile = Tile(9, 303, 148)
        val transparent = sqliteDb("подписи", tile, true, "simple", color = 0x80001824.toInt())
        transparent.inputStream().use { tiles.install(MapPackage.REFERENCES, it) }
        val pixel = tiles.read(MapLayer.HYBRID, true, "", tile, TilePass.PLACES)?.getPixel(20, 20)
        assertEquals(0x80001824.toInt(), pixel)
    }

    private fun sqliteDb(
        name: String, tile: Tile, info: Boolean, numbering: String?,
        invertedZoom: Boolean = false, invertedY: Boolean = false, ellipsoid: Boolean = false,
        color: Int = Color.BLUE
    ): File = database("$name.sqlitedb") {
        execSQL("CREATE TABLE tiles (x INTEGER,y INTEGER,z INTEGER,s INTEGER,image BLOB, PRIMARY KEY (x,y,z,s))")
        val z = if (invertedZoom) 17 - tile.z else tile.z
        val y = if (invertedY) (1 shl tile.z) - 1 - tile.y else tile.y
        execSQL("INSERT INTO tiles VALUES (?,?,?,?,?)", arrayOf(tile.x, y, z, 0, bitmap(color)))
        if (info) {
            execSQL("CREATE TABLE info (tilenumbering TEXT,inverted_y INTEGER,ellipsoid INTEGER)")
            execSQL("INSERT INTO info VALUES (?,?,?)", arrayOf(numbering, if (invertedY) 1 else 0, if (ellipsoid) 1 else 0))
        }
    }

    private fun geoPackage(tile: Tile): File = database("archive.gpkg") {
        val halfWorld = 6_378_137 * PI
        val matrixSize = 1 shl tile.z
        val resolution = 2 * halfWorld / (matrixSize * 256)
        val table = "archive 1916"
        execSQL("CREATE TABLE gpkg_contents (table_name TEXT,data_type TEXT,identifier TEXT,min_x DOUBLE,min_y DOUBLE,max_x DOUBLE,max_y DOUBLE)")
        execSQL("INSERT INTO gpkg_contents (table_name,data_type,identifier) VALUES (?,'tiles','Архивная карта')", arrayOf(table))
        execSQL("CREATE TABLE gpkg_spatial_ref_sys (srs_id INTEGER,organization TEXT,organization_coordsys_id INTEGER)")
        execSQL("INSERT INTO gpkg_spatial_ref_sys VALUES (3857,'EPSG',3857)")
        execSQL("CREATE TABLE gpkg_tile_matrix_set (table_name TEXT,srs_id INTEGER,min_x DOUBLE,min_y DOUBLE,max_x DOUBLE,max_y DOUBLE)")
        execSQL("INSERT INTO gpkg_tile_matrix_set VALUES (?,3857,?,?,?,?)", arrayOf(table, -halfWorld, -halfWorld, halfWorld, halfWorld))
        execSQL("CREATE TABLE gpkg_tile_matrix (table_name TEXT,zoom_level INTEGER,matrix_width INTEGER,matrix_height INTEGER,tile_width INTEGER,tile_height INTEGER,pixel_x_size DOUBLE,pixel_y_size DOUBLE)")
        execSQL("INSERT INTO gpkg_tile_matrix VALUES (?,?,?,?,256,256,?,?)", arrayOf(table, tile.z, matrixSize, matrixSize, resolution, resolution))
        execSQL("CREATE TABLE \"$table\" (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)")
        execSQL("INSERT INTO \"$table\" VALUES (?,?,?,?)", arrayOf(tile.z, tile.x, tile.y, bitmap(Color.BLUE)))
    }

    private fun database(name: String, init: SQLiteDatabase.() -> Unit): File {
        val file = File(context.cacheDir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use(init)
        return file
    }

    private fun install(file: File) = file.inputStream().use { tiles.install(MapPackage.HISTORICAL, it, file.name) }

    private fun pixel(map: MapTiles, tile: Tile) =
        map.read(MapLayer.STREET, false, "", tile, TilePass.HISTORICAL)?.getPixel(20, 20)

    private fun bitmap(color: Int) = ByteArrayOutputStream().apply {
        val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        image.eraseColor(color)
        image.compress(Bitmap.CompressFormat.PNG, 100, this)
        image.recycle()
    }.toByteArray()
}
