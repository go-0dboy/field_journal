package ru.fieldjournal

import android.util.LruCache
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.InflaterInputStream

/** The image stays in its original packed form. A bounded cache holds decoded 64×64 blocks. */
internal class Ozf2Image(file: File) : AutoCloseable {
    private data class Level(
        val width: Int, val height: Int, val columns: Int, val offsets: Long, val palette: IntArray
    )

    private val input = RandomAccessFile(file, "r")
    private val imageLength: Long
    val mapData: ByteArray
    private val levels: List<Level>
    private val blocks = LruCache<Long, IntArray>(128)
    val width: Int get() = levels[0].width
    val height: Int get() = levels[0].height

    init {
        try {
            require(input.length() >= 70 && input.readUnsignedByte() == 0x78 && input.readUnsignedByte() == 0x77) {
                "Нужен файл изображения OZF2"
            }
            // MapTiles appends the .map and its length so the paired files can be installed atomically.
            input.seek(input.length() - 4)
            val mapSize = input.readInt()
            require(mapSize in 1..OziCalibration.MAX_SIZE && mapSize.toLong() <= input.length() - 70) {
                "В пакете OZF2 отсутствует файл привязки .map"
            }
            imageLength = input.length() - 4 - mapSize
            input.seek(imageLength)
            mapData = ByteArray(mapSize).also(input::readFully)
            input.seek(14)
            require(input.uint() == 40L) { INVALID }
            val fullWidth = input.uint()
            val fullHeight = input.uint()
            require(input.ushort() == 1 && input.ushort() == 8) { INVALID }
            input.seek(54)
            require(input.uint() == 0x77777777L) { INVALID }
            val count = input.ushort()
            require(count in 1..255) { INVALID }
            input.seek(imageLength - 4)
            val table = input.uint()
            require(table >= 60 + 4L * count && table + 4L * count <= imageLength - 4) { INVALID }
            input.seek(table)
            val offsets = List(count) { input.uint() }
            levels = offsets.map { offset ->
                require(offset >= 60 && offset + 1036 <= table) { INVALID }
                input.seek(offset)
                val w = input.uint()
                val h = input.uint()
                val columns = input.ushort()
                val rows = input.ushort()
                require(w in 1..(columns * 64L) && h in 1..(rows * 64L) &&
                    w <= fullWidth && h <= fullHeight && offset + 1036 + 4L * (columns.toLong() * rows + 1) <= table) { INVALID }
                val palette = IntArray(256) { (input.uint() and 0x00ffffffL).toInt() or 0xff000000.toInt() }
                Level(w.toInt(), h.toInt(), columns, offset + 1036, palette)
            }
            require(levels[0].width.toLong() == fullWidth && levels[0].height.toLong() == fullHeight) { INVALID }
            block(0, 0, 0)
        } catch (error: Exception) {
            input.close()
            throw error
        }
    }

    fun level(sourcePixelsPerPixel: Double): Int =
        levels.indices.minBy { kotlin.math.abs(kotlin.math.ln(sourcePixelsPerPixel * levels[it].width / width)) }

    inner class Sampler(private val index: Int) {
        private val level = levels[index]
        private var lastX = -1
        private var lastY = -1
        private lateinit var colors: IntArray

        fun color(x: Double, y: Double): Int {
            val px = (x * level.width / width).toInt().coerceAtMost(level.width - 1)
            val py = (y * level.height / height).toInt().coerceAtMost(level.height - 1)
            val bx = px / 64
            val by = py / 64
            if (bx != lastX || by != lastY) {
                colors = block(index, bx, by)
                lastX = bx
                lastY = by
            }
            return colors[(py % 64) * 64 + px % 64]
        }
    }

    private fun block(index: Int, x: Int, y: Int): IntArray {
        val level = levels[index]
        val offset = level.offsets + 4L * (y.toLong() * level.columns + x)
        blocks.get(offset)?.let { return it }
        input.seek(offset)
        val start = input.uint()
        val end = input.uint()
        require(start >= 60 && end <= imageLength && end - start in 1..(64L * 1024)) { INVALID }
        input.seek(start)
        val compressed = ByteArray((end - start).toInt()).also(input::readFully)
        val pixels = InflaterInputStream(compressed.inputStream()).use { data ->
            val indices = ByteArray(64 * 64)
            var size = 0
            while (size < indices.size) {
                val count = data.read(indices, size, indices.size - size)
                require(count > 0) { INVALID }
                size += count
            }
            require(data.read() == -1) { INVALID }
            IntArray(indices.size) { i ->
                level.palette[indices[(63 - i / 64) * 64 + i % 64].toInt() and 0xff]
            }
        }
        blocks.put(offset, pixels)
        return pixels
    }

    override fun close() = input.close()

    private fun RandomAccessFile.uint() = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
    private fun RandomAccessFile.ushort() = (readUnsignedByte() or (readUnsignedByte() shl 8))

    private companion object {
        const val INVALID = "Повреждён или не поддерживается растр OZF2"
    }
}
