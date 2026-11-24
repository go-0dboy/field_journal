package ru.fieldjournal

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ozf2ArchiveTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun imageAndCalibrationPersistAndRenderInBothSlots() {
        val tiles = MapTiles(context)
        val image = raster()
        val map = calibration()
        for (slot in listOf(MapPackage.STREET, MapPackage.HISTORICAL)) {
            val prepared = tiles.prepareOzf2(slot, image.inputStream(), map.inputStream(), "fixture.ozf2")
            val installed = tiles.install(prepared)
            assertEquals(RasterFormat.OZF2, installed.format)
            assertEquals("Полевой лист", installed.name)
            assertEquals(Coordinate(59.5, 33.5).latitude, requireNotNull(installed.center).latitude, 1e-7)
            assertEquals(33.5, installed.center.longitude, 1e-7)
        }
        val reopened = MapTiles(context)
        val zoom = requireNotNull(reopened.info(MapPackage.STREET)).maxZoom
        val count = 1 shl zoom
        fun read(point: Coordinate, pass: TilePass): Int? {
            val x = (point.longitude + 180) / 360 * count
            val y = mercator(point.latitude) * count
            val bitmap = reopened.read(MapLayer.STREET, true, "", Tile(zoom, x.toInt(), y.toInt()), pass) ?: return null
            return bitmap.getPixel(((x % 1) * 256).toInt(), ((y % 1) * 256).toInt())
        }
        for (pass in listOf(TilePass.BASE, TilePass.HISTORICAL)) {
            assertEquals(Color.RED, read(Coordinate(59.8, 33.5), pass))
            assertEquals(Color.BLUE, read(Coordinate(59.2, 33.5), pass))
            assertEquals(Color.TRANSPARENT, read(Coordinate(61.0, 33.5), pass))
            assertNull(read(Coordinate(70.0, 33.5), pass))
        }
        val coverage = reopened.coverage(listOf(Coordinate(59.75, 33.5)), listOf(zoom), listOf(MapPackage.HISTORICAL))
        assertEquals(1, coverage.single().native)
    }

    @Test fun failedReplacementKeepsInstalledLayer() {
        val tiles = MapTiles(context)
        val slot = MapPackage.HISTORICAL
        val image = raster()
        tiles.install(tiles.prepareOzf2(slot, image.inputStream(), calibration().inputStream(), "fixture.ozf2"))
        assertThrows(IllegalArgumentException::class.java) {
            tiles.prepareOzf2(slot, image.inputStream(), calibration().inputStream(), "wrong.ozf2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tiles.prepareOzf2(slot, image.inputStream(), "без привязки".byteInputStream(), "fixture.ozf2")
        }
        assertEquals("Полевой лист", tiles.info(slot)?.name)
    }

    @Test fun transformsPulkovoGridCoordinatesToWgs84() {
        val map = """
            OziExplorer Map Data File Version 2.2
            СК-42
            fixture.ozf2
            1 ,Map Code,
            Pulkovo 1942 (1)
            Map Projection,Transverse Mercator,PolyCal,No
            Projection Setup,0,33,1,500000,0
            Point01,xy,0,0,in,deg,,,,,,,grid,6,520000,6600000,N
            Point02,xy,64,64,in,deg,,,,,,,grid,6,530000,6590000,N
        """.trimIndent().toByteArray()
        val calibration = OziCalibration(map, 64, 64)
        val center = calibration.coordinate(32.0, 32.0)
        // Reference computed with PROJ using the OziExplorer datum parameters.
        assertEquals(59.46794302, center.latitude, 0.00002)
        assertEquals(33.43877711, center.longitude, 0.00002)
        val sampler = calibration.Sampler()
        assertTrue(sampler.locate(center.longitude, center.latitude))
        assertEquals(32.0, sampler.x, 0.02)
        assertEquals(32.0, sampler.y, 0.02)
    }

    private fun calibration() = """
        OziExplorer Map Data File Version 2.2
        Полевой лист
        C:\Maps\fixture.ozf2
        1 ,Map Code,
        WGS 84
        Map Projection,Latitude/Longitude,PolyCal,No
        Point01,xy,0,0,in,deg,60,0,N,33,0,E
        Point02,xy,64,64,in,deg,59,0,N,34,0,E
    """.trimIndent().toByteArray()

    private fun raster(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val stream = DataOutputStream(bytes)
        fun int(value: Int) = stream.writeInt(Integer.reverseBytes(value))
        fun short(value: Int) = stream.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
        stream.write(byteArrayOf(0x78, 0x77, 0, 0, 0, 0, 64, 0, 1, 0, 0x36, 4, 0, 0))
        int(40)
        int(64)
        int(64)
        short(1)
        short(8)
        repeat(6) { int(0) }
        int(0x77777777)
        short(1)
        int(100f.toBits())
        int(0x77777777)
        val start = bytes.size()
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use {
            it.write(ByteArray(4096) { i -> if (i < 2048) 1 else 0 })
        }
        compressed.writeTo(stream)
        val level = bytes.size()
        int(64)
        int(64)
        short(1)
        short(1)
        int(0x00ff0000)
        int(0x000000ff)
        repeat(254) { int(0) }
        int(start)
        int(level)
        val table = bytes.size()
        int(level)
        int(table)
        return bytes.toByteArray()
    }
}
