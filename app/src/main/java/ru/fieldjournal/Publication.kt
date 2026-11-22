package ru.fieldjournal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.round

internal fun writePublicCatalog(
    sites: List<Site>, decimals: Int, output: OutputStream, attachmentFile: (String) -> File
) {
    require(decimals in -1..2)
    val approved = sites.filter { it.research.fields["public"]?.equals("да", true) == true }
    require(approved.isNotEmpty()) { "Отметьте публикацию хотя бы в одной научной карточке" }
    ZipOutputStream(output).use { zip ->
        val photos = mutableListOf<Pair<String, Attachment>>()
        zip.putNextEntry(ZipEntry("objects.geojson"))
        fun write(value: String) = zip.write(value.toByteArray(Charsets.UTF_8))
        write("{\"type\":\"FeatureCollection\",\"features\":[")
        approved.forEachIndexed { index, site ->
            if (index > 0) write(",")
            val id = "public-${index + 1}"
            val point = if (decimals < 0 || !site.located) null else {
                val precision = if (decimals == 1) 10.0 else 100.0
                Coordinate(round(site.anchor.latitude * precision) / precision,
                    round(site.anchor.longitude * precision) / precision)
            }
            val feature = Site(id = id, title = site.research.fields["public_title"].orEmpty().ifBlank { site.symbol.title },
                type = site.type, vertices = listOfNotNull(point)).feature()
            // Deliberately whitelist public fields. Free-text descriptions can contain precise locations.
            feature.put("properties", JSONObject().put("title", feature.getJSONObject("properties").getString("title"))
                .put("type", site.type))
            write(feature.toString())
            val selected = site.research.entries.filter { it.kind == "media" && it.fields["public_photo"]?.equals("да", true) == true }
                .mapNotNull { entry -> site.attachments.find { it.id == entry.fields["original"] && it.mime.startsWith("image/") } }
                .distinctBy(Attachment::id)
            for ((photoIndex, photo) in selected.withIndex()) {
                photos += "media/$id-${photoIndex + 1}.jpg" to photo
            }
        }
        write("]}")
        zip.closeEntry()
        for ((path, attachment) in photos) {
            val file = attachmentFile(attachment.id)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, dimensions)
            require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "Не удалось прочитать выбранную фотографию" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(dimensions.outWidth, dimensions.outHeight) / inSampleSize > 2400) inSampleSize *= 2
            }
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Не удалось прочитать выбранную фотографию" }
            try {
                zip.putNextEntry(ZipEntry(path))
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, zip)) { "Не удалось сохранить копию фотографии" }
                zip.closeEntry()
            } finally {
                bitmap.recycle()
            }
        }
    }
}
